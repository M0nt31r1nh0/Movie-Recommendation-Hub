package psia.g17.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.Startup;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Search by meaning. Every movie gets an embedding: a vector that an embedding model (in Ollama) computes
 * from its title, year, genres and description, kept in movies.movie_embedding. A request is embedded the
 * same way, and the movies whose vectors are closest to it are about similar things, whatever words they
 * use: "astronauts stranded far from home" is close to The Martian. Movies are embedded in the background,
 * most voted first, and again when the model changes; only those candidates can be (enough votes).
 *
 * <p>Metrics: {@code movies_embeddings_requests_seconds} (each call to Ollama, by {@code purpose}, query or
 * backfill, and {@code outcome}), {@code movies_embeddings_query_cache_total} (requests whose vector was
 * already known, {@code result} hit, or not), {@code movies_embeddings_backfilled_total} (movies embedded)
 * and {@code movies_embeddings_missing} (movies still to embed, counted at each background run).
 */
// Created at startup, so its metrics are published from the first scrape (beans are otherwise created on first use).
@Startup
@ApplicationScoped
public class MovieEmbeddings {

    /** Recent requests' vectors: the worker asks for candidates several times per request. */
    private static final int CACHED_QUERIES = 200;

    private final OllamaClient ollama;
    private final EntityManager em;
    private final MeterRegistry metrics;
    private final String model;
    private final String documentPrefix;
    private final String queryPrefix;
    private final String keepAlive;
    private final int minVotes;
    private final int batchSize;
    private final boolean backfill;
    private final Duration budget;
    private final Counter backfilled;
    /** Movies still to embed, as of the last background run; unknown (NaN) until the first. */
    private volatile double stillMissing = Double.NaN;
    private final Map<String, String> queries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHED_QUERIES;
        }
    });

    public MovieEmbeddings(@RestClient OllamaClient ollama, EntityManager em, MeterRegistry metrics,
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
        this.metrics = metrics;
        this.model = model;
        this.documentPrefix = documentPrefix;
        this.queryPrefix = queryPrefix;
        this.keepAlive = keepAlive;
        this.minVotes = minVotes;
        this.batchSize = batchSize;
        this.backfill = backfill;
        this.budget = budget;
        this.backfilled = Counter.builder("movies.embeddings.backfilled")
                .description("Movies embedded by the background job")
                .register(metrics);
        Gauge.builder("movies.embeddings.missing", this, e -> e.stillMissing)
                .description("Movies that can be candidates but have no vector from the current model yet")
                .register(metrics);
    }

    public String model() {
        return model;
    }

    /** The request's vector, as pgvector text ("[0.12,-0.3,...]"), or empty if the model can't be reached. */
    public Optional<String> query(String text) {
        String key = text.strip().toLowerCase(Locale.ROOT);
        String cached = queries.get(key);
        queryCache(cached != null ? "hit" : "miss").increment();
        if (cached != null) return Optional.of(cached);
        try {
            String vector = literal(embed("query", List.of(prefixed(queryPrefix, text.strip()))).get(0));
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
        List<List<Float>> vectors = embed("backfill", movies.stream().map(this::document).toList());
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
        backfilled.increment(movies.size());
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
        if (!backfill) {
            countMissing();
            return;
        }
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
        long left = countMissing();
        if (done > 0) Log.infof("Embedded %d movies with %s; %d to go", done, model, left);
    }

    /** Counts the movies still to embed, for the log and movies_embeddings_missing; -1 if the database can't say. */
    private long countMissing() {
        try {
            long left = missing();
            stillMissing = left;
            return left;
        } catch (RuntimeException e) {
            Log.warnf("Couldn't count the movies still to embed: %s", e.getMessage());
            return -1;
        }
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

    /** Embeds {@code input} with Ollama, timing the call by {@code purpose} (query or backfill). */
    private List<List<Float>> embed(String purpose, List<String> input) {
        long start = System.nanoTime();
        String outcome = "error";
        List<List<Float>> vectors;
        try {
            vectors = ollama.embed(new EmbedRequest(model, input, keepAlive)).embeddings();
            outcome = "success";
        } finally {
            Timer.builder("movies.embeddings.requests")
                    .description("How long Ollama took to embed texts")
                    .tag("purpose", purpose)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .maximumExpectedValue(Duration.ofMinutes(2))
                    .register(metrics)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
        if (vectors == null || vectors.size() != input.size()) {
            throw new IllegalStateException("Ollama returned " + (vectors == null ? 0 : vectors.size()) + " vectors for " + input.size() + " texts");
        }
        return vectors;
    }

    private Counter queryCache(String result) {
        return Counter.builder("movies.embeddings.query.cache")
                .description("Requests embedded for search by meaning, by whether their vector was already known")
                .tag("result", result)
                .register(metrics);
    }

    /** pgvector's text form of a vector. */
    static String literal(List<Float> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
