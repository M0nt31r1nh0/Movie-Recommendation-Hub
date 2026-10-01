package psia.g17.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.OllamaClient;
import psia.g17.client.OllamaClient.EmbedRequest;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Search by meaning. Every movie gets an embedding: a vector that an embedding model (in Ollama) computes
 * from its title, year, genres and description, kept in movies.movie_embedding. A request is embedded the
 * same way, and the movies whose vectors are closest to it are about similar things, whatever words they
 * use: "astronauts stranded far from home" is close to The Martian. Movies are embedded in the background,
 * most voted first, and again when the model changes; only those candidates can be (enough votes).
 */
@ApplicationScoped
public class MovieEmbeddings {

    /** Recent requests' vectors: the worker asks for candidates several times per request. */
    private static final int CACHED_QUERIES = 200;

    private final OllamaClient ollama;
    private final EntityManager em;
    private final String model;
    private final String documentPrefix;
    private final String queryPrefix;
    private final String keepAlive;
    private final int minVotes;
    private final int batchSize;
    private final boolean backfill;
    private final Duration budget;
    private final Map<String, String> queries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHED_QUERIES;
        }
    });

    public MovieEmbeddings(@RestClient OllamaClient ollama, EntityManager em,
                           @ConfigProperty(name = "movies.embeddings.model") String model,
                           @ConfigProperty(name = "movies.embeddings.document-prefix", defaultValue = "") String documentPrefix,
                           @ConfigProperty(name = "movies.embeddings.query-prefix", defaultValue = "") String queryPrefix,
                           @ConfigProperty(name = "movies.embeddings.keep-alive", defaultValue = "10m") String keepAlive,
                           @ConfigProperty(name = "movies.embeddings.min-votes", defaultValue = "50") int minVotes,
                           @ConfigProperty(name = "movies.embeddings.batch-size", defaultValue = "16") int batchSize,
                           @ConfigProperty(name = "movies.embeddings.backfill", defaultValue = "true") boolean backfill,
                           @ConfigProperty(name = "movies.embeddings.budget", defaultValue = "PT50S") Duration budget) {
        this.ollama = ollama;
        this.em = em;
        this.model = model;
        this.documentPrefix = documentPrefix;
        this.queryPrefix = queryPrefix;
        this.keepAlive = keepAlive;
        this.minVotes = minVotes;
        this.batchSize = batchSize;
        this.backfill = backfill;
        this.budget = budget;
    }

    public String model() {
        return model;
    }

    /** The request's vector, as pgvector text ("[0.12,-0.3,...]"), or empty if the model can't be reached. */
    public Optional<String> query(String text) {
        String key = text.strip().toLowerCase(Locale.ROOT);
        String cached = queries.get(key);
        if (cached != null) return Optional.of(cached);
        try {
            String vector = literal(embed(List.of(prefixed(queryPrefix, text.strip()))).get(0));
            queries.put(key, vector);
            return Optional.of(vector);
        } catch (RuntimeException e) {
            Log.warnf("Couldn't embed a search, so it only matches words: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /** Embeds up to {@code limit} movies without a vector from the current model, most voted first; returns how many. */
    @SuppressWarnings("unchecked")
    public int embedMissing(int limit) {
        List<Object[]> movies = QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery("""
                        SELECT m.movie_id, m.title, extract(year FROM m.release_date)::int, m.description,
                               (SELECT string_agg(g.name, ', ' ORDER BY g.name) FROM movies.movie_genre mg
                                JOIN movies.genre g ON g.genre_id = mg.genre_id WHERE mg.movie_id = m.movie_id)
                        FROM movies.movie m
                        LEFT JOIN movies.movie_embedding e ON e.movie_id = m.movie_id AND e.model = :model
                        WHERE e.movie_id IS NULL AND coalesce(m.rating_count, 0) >= :minVotes
                        ORDER BY m.rating_count DESC, m.movie_id""")
                .setParameter("model", model)
                .setParameter("minVotes", minVotes)
                .setMaxResults(limit)
                .getResultList());
        if (movies.isEmpty()) return 0;
        List<List<Float>> vectors = embed(movies.stream().map(this::document).toList());
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < movies.size(); i++) {
                em.createNativeQuery("""
                                INSERT INTO movies.movie_embedding (movie_id, model, embedding) VALUES (:id, :model, CAST(:vector AS vector))
                                ON CONFLICT (movie_id) DO UPDATE SET model = excluded.model, embedding = excluded.embedding, created_at = now()""")
                        .setParameter("id", ((Number) movies.get(i)[0]).intValue())
                        .setParameter("model", model)
                        .setParameter("vector", literal(vectors.get(i)))
                        .executeUpdate();
            }
        });
        return movies.size();
    }

    /** How many movies still need a vector from the current model. */
    public long missing() {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery("""
                        SELECT count(*) FROM movies.movie m
                        LEFT JOIN movies.movie_embedding e ON e.movie_id = m.movie_id AND e.model = :model
                        WHERE e.movie_id IS NULL AND coalesce(m.rating_count, 0) >= :minVotes""")
                .setParameter("model", model)
                .setParameter("minVotes", minVotes)
                .getSingleResult()).longValue());
    }

    /** Embeds missing movies for up to {@code budget} per run, so the job can stop and resume at any time. */
    @Scheduled(every = "${movies.embeddings.every:1m}", delayed = "${movies.embeddings.delay:20s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void backfill() {
        if (!backfill) return;
        long end = System.nanoTime() + budget.toNanos();
        int done = 0;
        try {
            int batch;
            while (System.nanoTime() < end && (batch = embedMissing(batchSize)) > 0) {
                done += batch;
            }
        } catch (RuntimeException e) {
            Log.warnf("Couldn't embed movies, trying again later: %s", e.getMessage());
        }
        if (done > 0) Log.infof("Embedded %d movies with %s; %d to go", done, model, missing());
    }

    /** What a movie is embedded from: "Alien (1979). Horror, Science Fiction. During its return to the earth, ..." */
    private String document(Object[] movie) {
        StringBuilder text = new StringBuilder().append(movie[1]);
        if (movie[2] != null) text.append(" (").append(movie[2]).append(')');
        text.append('.');
        if (movie[4] != null) text.append(' ').append(movie[4]).append('.');
        if (movie[3] != null) text.append(' ').append(movie[3]);
        return prefixed(documentPrefix, text.toString());
    }

    /** Some models want texts marked as documents or searches, e.g. "search_query: ...". */
    private static String prefixed(String prefix, String text) {
        return prefix.isBlank() ? text : prefix.strip() + " " + text;
    }

    private List<List<Float>> embed(List<String> input) {
        List<List<Float>> vectors = ollama.embed(new EmbedRequest(model, input, keepAlive)).embeddings();
        if (vectors == null || vectors.size() != input.size()) {
            throw new IllegalStateException("Ollama returned " + (vectors == null ? 0 : vectors.size()) + " vectors for " + input.size() + " texts");
        }
        return vectors;
    }

    /** pgvector's text form of a vector. */
    static String literal(List<Float> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
