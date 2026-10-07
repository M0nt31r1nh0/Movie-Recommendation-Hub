package psia.g17.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import psia.g17.dto.CandidateQuery;
import psia.g17.entity.Movie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Picks candidate movies for recommendation-worker: well-regarded movies matching optional filters,
 * from which its language model then chooses. Movies are scored by a vote-weighted rating (a
 * Bayesian average: a 10/10 from 3 voters counts as about 6, a 8.5 from 6,000 as about 8.4), plus:
 * <ul>
 *   <li>0.3 for each requested genre they're in, and 0.3 more for each preferred (favourite) one;</li>
 *   <li>for a request in plain words, {@value #MEANING_WEIGHT} times how close its meaning is to theirs
 *       (0 to 1, see {@link MovieEmbeddings}), which outweighs the rest;</li>
 *   <li>for words to match, up to 2 for how well they match (with a request, at most
 *       {@value #TEXT_WITH_MEANING}, as a tie-breaker for exact titles and names);</li>
 *   <li>0.2 for each person the user follows who watched them, counting at most 3.</li>
 * </ul>
 * Each query is timed in {@code movies_candidates_seconds}, by {@code ranking}: {@code meaning} (a request
 * in plain words, embedded), {@code words} (its words only: no request text to embed, or Ollama couldn't
 * embed it) or {@code taste} (no request text: the profile's genres, friends and ratings).
 */
@ApplicationScoped
public class MovieCandidates {

    /** The weighted rating pulls every movie towards this rating with the weight of this many votes. */
    private static final double PRIOR_RATING = 6.0;
    private static final int PRIOR_VOTES = 200;
    private static final int MAX_FRIENDS_COUNTED = 3;
    /**
     * nomic-embed-text's similarities fall between about 0.5 and 0.7, and a good match is often only
     * 0.05 above the rest: this makes that worth 1.5 rating points, so meaning comes first and the
     * rating separates movies about equally close.
     */
    static final int MEANING_WEIGHT = 30;
    static final double TEXT_WITH_MEANING = 0.3;

    private static final String QUALITY = "(coalesce(m.rating, 0) * coalesce(m.rating_count, 0) + " + PRIOR_RATING + " * " + PRIOR_VOTES
            + ") / (coalesce(m.rating_count, 0) + " + PRIOR_VOTES + ")";

    private final EntityManager em;
    private final MovieEmbeddings embeddings;
    private final MeterRegistry metrics;

    public MovieCandidates(EntityManager em, MovieEmbeddings embeddings, MeterRegistry metrics) {
        this.em = em;
        this.embeddings = embeddings;
        this.metrics = metrics;
    }

    @SuppressWarnings("unchecked")
    public List<Movie> find(CandidateQuery q) {
        Timer.Sample sample = Timer.start(metrics);
        List<String> where = new ArrayList<>();
        List<String> score = new ArrayList<>(List.of(QUALITY));
        Map<String, Object> params = new LinkedHashMap<>();

        where.add("coalesce(m.rating_count, 0) >= :minVotes");
        params.put("minVotes", q.minRatingCount() != null ? q.minRatingCount() : CandidateQuery.DEFAULT_MIN_RATING_COUNT);
        if (q.genreIds() != null && !q.genreIds().isEmpty()) {
            String inGenres = "(SELECT count(*) FROM movies.movie_genre mg WHERE mg.movie_id = m.movie_id AND mg.genre_id IN (:genres))";
            where.add(inGenres + " > 0");
            score.add("0.3 * " + inGenres);
            params.put("genres", q.genreIds());
        }
        if (q.preferredGenreIds() != null && !q.preferredGenreIds().isEmpty()) {
            score.add("0.3 * (SELECT count(*) FROM movies.movie_genre mg WHERE mg.movie_id = m.movie_id AND mg.genre_id IN (:preferred))");
            params.put("preferred", q.preferredGenreIds());
        }
        if (q.yearFrom() != null) {
            where.add("m.release_date >= make_date(:yearFrom, 1, 1)");
            params.put("yearFrom", q.yearFrom());
        }
        if (q.yearTo() != null) {
            where.add("m.release_date < make_date(:yearTo + 1, 1, 1)");
            params.put("yearTo", q.yearTo());
        }
        // A request in plain words ranks by meaning; if it can't be embedded, its words still filter.
        Optional<String> meaning = q.meaning() == null || q.meaning().isBlank() ? Optional.empty() : embeddings.query(q.meaning());
        String closeness = "";
        if (meaning.isPresent()) {
            closeness = " LEFT JOIN movies.movie_embedding e ON e.movie_id = m.movie_id AND e.model = :model";
            score.add(MEANING_WEIGHT + " * coalesce(1 - (e.embedding <=> CAST(:meaning AS vector)), 0)");
            params.put("model", embeddings.model());
            params.put("meaning", meaning.get());
        }
        if (q.text() != null && !q.text().isBlank()) {
            String words = "websearch_to_tsquery('english', movies.immutable_unaccent(:text))";
            if (meaning.isPresent()) {
                score.add("LEAST(2 * ts_rank_cd(m.search_vector, " + words + "), " + TEXT_WITH_MEANING + ")");
            } else {
                where.add("m.search_vector @@ " + words);
                score.add("2 * ts_rank_cd(m.search_vector, " + words + ")");
            }
            params.put("text", q.text().strip());
        }
        if (q.onlyIds() != null && !q.onlyIds().isEmpty()) {
            where.add("m.movie_id IN (:only)");
            params.put("only", q.onlyIds());
        }
        if (q.excludeIds() != null && !q.excludeIds().isEmpty()) {
            where.add("m.movie_id NOT IN (:exclude)");
            params.put("exclude", q.excludeIds());
        }
        String friends = "";
        if (q.friendWatchers() != null && !q.friendWatchers().isEmpty()) {
            // Plain ints from the request (never text), so they can go straight into a VALUES list.
            friends = " LEFT JOIN (VALUES " + q.friendWatchers().stream()
                    .map(f -> "(" + f.movieId() + ", " + Math.clamp(f.watchers(), 0, MAX_FRIENDS_COUNTED) + ")")
                    .collect(Collectors.joining(", ")) + ") AS friends (movie_id, watchers) ON friends.movie_id = m.movie_id";
            score.add("0.2 * coalesce(friends.watchers, 0)");
        }

        String sql = "SELECT m.* FROM movies.movie m" + friends + closeness + " WHERE " + String.join(" AND ", where)
                + " ORDER BY " + String.join(" + ", score) + " DESC, m.movie_id";
        Query query = em.createNativeQuery(sql, Movie.class)
                .setMaxResults(q.limit() != null ? q.limit() : CandidateQuery.DEFAULT_LIMIT);
        params.forEach(query::setParameter);
        List<Movie> found = query.getResultList();
        String ranking = meaning.isPresent() ? "meaning" : q.text() != null && !q.text().isBlank() ? "words" : "taste";
        sample.stop(Timer.builder("movies.candidates")
                .description("Candidate queries from recommendation-worker, by how they were ranked")
                .tag("ranking", ranking)
                .publishPercentileHistogram()
                .register(metrics));
        return found;
    }
}
