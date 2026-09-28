package psia.g17;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.List;

/**
 * Full-text search over movie titles and descriptions, backed by the columns and indexes
 * from db/migration/V2 and V3. Tries each strategy in turn and returns the first with results:
 * <ol>
 *   <li>English full-text search on title and description, or, when the query is only stop
 *       words ("It", "The Who"), a word match on the title as written;</li>
 *   <li>a typo-tolerant trigram match on the title ("godfater" finds "The Godfather").</li>
 * </ol>
 */
@ApplicationScoped
public class MovieSearch {

    private record Strategy(String from, String orderBy) {
    }

    // Title matches first, most popular first; then description-only matches by relevance.
    private static final Strategy FULL_TEXT = new Strategy("""
            FROM movies.movie m, websearch_to_tsquery('english', :text) query
            WHERE m.search_vector @@ query""", """
            ts_filter(m.search_vector, cast(array['a'] as "char"[])) @@ query DESC,
            CASE WHEN ts_filter(m.search_vector, cast(array['a'] as "char"[])) @@ query
                 THEN m.rating_count END DESC NULLS LAST,
            ts_rank_cd(m.search_vector, query) DESC, m.rating_count DESC NULLS LAST, m.movie_id""");

    // Shortest matching titles first, so "It" comes before "It Follows".
    private static final Strategy TITLE_WORDS = new Strategy("""
            FROM movies.movie m, websearch_to_tsquery('simple', :text) query
            WHERE m.title_vector @@ query""", """
            ts_rank_cd(m.title_vector, query, 2) DESC, m.rating_count DESC NULLS LAST, m.movie_id""");

    private static final Strategy FUZZY_TITLE = new Strategy("""
            FROM movies.movie m
            WHERE :text <% m.title""", """
            word_similarity(:text, m.title) DESC, m.rating_count DESC NULLS LAST, m.movie_id""");

    private final EntityManager em;

    public MovieSearch(EntityManager em) {
        this.em = em;
    }

    public PageResponse<Movie> search(String text, int page, int size) {
        Strategy exact = onlyStopWords(text) ? TITLE_WORDS : FULL_TEXT;
        long total = count(exact, text);
        Strategy strategy = exact;
        if (total == 0) {
            strategy = FUZZY_TITLE;
            total = count(strategy, text);
        }
        List<Movie> items = total == 0 ? List.of() : find(strategy, text, page, size);
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    private boolean onlyStopWords(String text) {
        Number nodes = (Number) em.createNativeQuery("SELECT numnode(websearch_to_tsquery('english', :text))")
                .setParameter("text", text)
                .getSingleResult();
        return nodes.intValue() == 0;
    }

    private long count(Strategy strategy, String text) {
        return ((Number) em.createNativeQuery("SELECT count(*) " + strategy.from())
                .setParameter("text", text)
                .getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Movie> find(Strategy strategy, String text, int page, int size) {
        return em.createNativeQuery("SELECT m.* " + strategy.from() + " ORDER BY " + strategy.orderBy(), Movie.class)
                .setParameter("text", text)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }
}
