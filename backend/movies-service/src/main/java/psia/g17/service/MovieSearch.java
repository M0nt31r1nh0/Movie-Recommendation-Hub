package psia.g17.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import psia.g17.dto.PageResponse;
import psia.g17.entity.Movie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Full-text search over movie titles and descriptions, backed by the columns and indexes from
 * db/migration/V2, V3 and V5. Accents never matter ("amelie" finds "Amélie"). A movie matches if:
 * <ol>
 *   <li>its title contains the query's words, in any form ("godfathers" finds "The Godfather"), or, for
 *       a query made only of stop words ("It", "The Who"), those words exactly;</li>
 *   <li>its title has words starting with the query's words ("Ava" finds "Avatar");</li>
 *   <li>its description contains the query's words; or</li>
 *   <li>its title looks similar to the query ("godfater" finds "The Godfather"), but only when the
 *       other three find fewer than {@link #FEW_MATCHES} movies.</li>
 * </ol>
 * By relevance, results come in that order: title matches by popularity, description matches by how
 * well they match, similar titles by similarity.
 *
 * <p>Each search is timed in {@code movies_searches_seconds}, by whether it found anything
 * ({@code results}: none or some) and whether it had to add similar titles ({@code similar_titles}).
 */
@ApplicationScoped
public class MovieSearch {

    /** Below this many matches, similar-looking titles are added to the results. */
    static final int FEW_MATCHES = 10;

    /** Sort fields a search can use instead of relevance, as in GET /movies. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "m.movie_id",
            "title", "m.title",
            "rating", "m.rating",
            "releaseDate", "m.release_date");

    /**
     * Web-search syntax: a quoted phrase, "or", or a -excluded word. Prefix and similar-title matching
     * ignore it, so they're skipped for such queries rather than, say, matching an excluded word.
     */
    private static final Pattern SEARCH_SYNTAX = Pattern.compile("\"|(^|\\s)-\\S|\\sor\\s", Pattern.CASE_INSENSITIVE);

    /** The query in the forms the conditions below compare against, all with accents removed. */
    private static final String QUERY_FORMS = """
            CROSS JOIN (SELECT websearch_to_tsquery('english', movies.immutable_unaccent(:text)) AS words,
                               websearch_to_tsquery('simple', movies.immutable_unaccent(:text)) AS exact_words,
                               (SELECT to_tsquery('simple', string_agg(quote_literal(w) || ':*', ' & '))
                                  FROM regexp_split_to_table(lower(movies.immutable_unaccent(:text)), '\\W+') AS w
                                 WHERE w <> '') AS prefixes,
                               lower(movies.immutable_unaccent(:text)) AS plain) q""";

    private static final String TITLE_HAS_WORDS = "ts_filter(m.search_vector, cast(array['a'] as \"char\"[])) @@ q.words";
    private static final String TITLE_HAS_EXACT_WORDS = "m.title_vector @@ q.exact_words";
    private static final String TITLE_HAS_PREFIXES = "m.title_vector @@ q.prefixes";
    private static final String TITLE_OR_DESCRIPTION_HAS_WORDS = "m.search_vector @@ q.words";
    private static final String TITLE_LOOKS_SIMILAR = "q.plain <% movies.immutable_unaccent(m.title)";

    /** Which of the match kinds in the class comment a search uses. */
    private record Match(boolean stopWordsOnly, boolean prefixes, boolean similarTitles) {

        Match withSimilarTitles() {
            return new Match(stopWordsOnly, prefixes, true);
        }

        /** Every branch can use an index; conditions that can't (like ts_filter) only rank, in {@link #tier()}. */
        String where() {
            List<String> conditions = new ArrayList<>();
            conditions.add(stopWordsOnly ? TITLE_HAS_EXACT_WORDS : TITLE_OR_DESCRIPTION_HAS_WORDS);
            if (prefixes) conditions.add(TITLE_HAS_PREFIXES);
            if (similarTitles) conditions.add(TITLE_LOOKS_SIMILAR);
            return " WHERE " + String.join(" OR ", conditions);
        }

        /** 0: title has the words, 1: title words start with them, 2: description has them, 3: similar title. */
        String tier() {
            return " CROSS JOIN LATERAL (SELECT CASE"
                    + " WHEN " + (stopWordsOnly ? TITLE_HAS_EXACT_WORDS : TITLE_HAS_WORDS) + " THEN 0"
                    + (prefixes ? " WHEN " + TITLE_HAS_PREFIXES + " THEN 1" : "")
                    + " WHEN " + TITLE_OR_DESCRIPTION_HAS_WORDS + " THEN 2"
                    + " ELSE 3 END AS tier) t";
        }
    }

    // Title matches by popularity, where a title that is exactly the query counts as ten times as
    // popular: "batman" puts "Batman" (1989) first, but "godfather" still puts "The Godfather" before
    // an obscure film called "Godfather".
    private static final String RELEVANCE_ORDER = """
            t.tier,
            CASE WHEN t.tier <= 1 THEN coalesce(m.rating_count, 0)
                      * CASE WHEN lower(movies.immutable_unaccent(m.title)) = q.plain THEN 10 ELSE 1 END END DESC NULLS LAST,
            CASE WHEN t.tier = 2 THEN ts_rank_cd(m.search_vector, q.words) END DESC NULLS LAST,
            CASE WHEN t.tier = 3 THEN word_similarity(q.plain, movies.immutable_unaccent(m.title)) END DESC NULLS LAST,
            m.rating_count DESC NULLS LAST, m.movie_id""";

    private final EntityManager em;
    private final MeterRegistry metrics;

    public MovieSearch(EntityManager em, MeterRegistry metrics) {
        this.em = em;
        this.metrics = metrics;
    }

    /**
     * @param sort  "relevance", or one of {@link #SORT_COLUMNS}' keys to order the matches by that field instead
     * @param order "asc" or "desc"; ignored for relevance
     */
    public PageResponse<Movie> search(String text, int page, int size, String sort, String order) {
        Timer.Sample sample = Timer.start(metrics);
        Result result = searchPage(text, page, size, sort, order);
        sample.stop(Timer.builder("movies.searches")
                .description("Searches by title and description, and how long they took")
                .tag("results", result.page().totalItems() == 0 ? "none" : "some")
                .tag("similar_titles", String.valueOf(result.similarTitles()))
                .publishPercentileHistogram()
                .register(metrics));
        return result.page();
    }

    /** A page of matches, and whether similar titles were added to find them. */
    private record Result(PageResponse<Movie> page, boolean similarTitles) {
    }

    private Result searchPage(String text, int page, int size, String sort, String order) {
        boolean plainWords = !SEARCH_SYNTAX.matcher(text).find();
        Match match = new Match(onlyStopWords(text), plainWords, false);
        long total = count(match, text);
        if (plainWords && total < FEW_MATCHES) {
            match = match.withSimilarTitles();
            total = count(match, text);
        }
        String orderBy = RELEVANCE_ORDER;
        if (SORT_COLUMNS.containsKey(sort)) {
            // Same order as GET /movies: missing values last, ties broken by id so pages are stable.
            String direction = order.equals("desc") ? "DESC" : "ASC";
            orderBy = SORT_COLUMNS.get(sort) + " " + direction + " NULLS LAST" + (sort.equals("id") ? "" : ", m.movie_id");
        }
        List<Movie> items = total == 0 ? List.of() : find(match, orderBy, text, page, size);
        int totalPages = (int) ((total + size - 1) / size);
        return new Result(new PageResponse<>(items, page, size, total, totalPages), match.similarTitles());
    }

    private boolean onlyStopWords(String text) {
        Number nodes = (Number) em.createNativeQuery(
                        "SELECT numnode(websearch_to_tsquery('english', movies.immutable_unaccent(:text)))")
                .setParameter("text", text)
                .getSingleResult();
        return nodes.intValue() == 0;
    }

    private long count(Match match, String text) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM movies.movie m " + QUERY_FORMS + match.where())
                .setParameter("text", text)
                .getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Movie> find(Match match, String orderBy, String text, int page, int size) {
        String sql = "SELECT m.* FROM movies.movie m " + QUERY_FORMS + match.tier() + match.where() + " ORDER BY " + orderBy;
        return em.createNativeQuery(sql, Movie.class)
                .setParameter("text", text)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }
}
