package psia.g17.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.MoviesClient;
import psia.g17.client.MoviesClient.CandidateQuery;
import psia.g17.client.MoviesClient.Genre;
import psia.g17.client.MoviesClient.Movie;
import psia.g17.events.FriendWatch;
import psia.g17.events.Kind;
import psia.g17.events.ProfileSnapshot;
import psia.g17.events.RecommendationJob;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns a job into ten recommendations. The catalogue is far too big for the model to read, so:
 * <ol>
 *   <li>a typed request becomes search filters: the genres it names, the years it mentions (read by the
 *       model), and its other words as keywords;</li>
 *   <li>movies-service picks a few dozen well-regarded candidates matching the filters (for a typed
 *       request, closest in meaning to it), or the user's
 *       taste, leaving out movies they've watched or saved; favourite genres and movies the people they
 *       follow watched rank higher;</li>
 *   <li>up to {@code friend-candidates} more come from what the people they follow watched, if they pass
 *       the same filters;</li>
 *   <li>the model chooses ten of them, constrained to the candidates' ids.</li>
 * </ol>
 */
@ApplicationScoped
public class RecommendationPipeline {

    /** Bump when the prompts or the candidate rules change, so old recommendations count as out of date. */
    public static final String VERSION = "p5";
    static final int RECOMMENDATIONS = 10;
    /** Recently watched and saved movies shown to the model to describe the user's taste. */
    private static final int HISTORY_SHOWN = 10;
    private static final int WATCHLIST_SHOWN = 5;
    /** Genres, beyond the favourites, taken from what the user watched and saved. */
    private static final int HISTORY_GENRES = 3;
    /** Words in requests that say nothing about the movie, so they never become keywords ("movies like ..."). */
    private static final Set<String> FILLER = Set.of("movie", "movies", "film", "films", "flick", "flicks", "cinema", "like", "likes",
            "similar", "want", "wanna", "watch", "watching", "see", "something", "anything", "some", "any", "good", "great", "best",
            "nice", "recommend", "recommendation", "recommendations", "suggest", "please", "give", "show", "find", "looking", "about",
            "with", "that", "this", "the", "and", "for", "from", "one", "ones", "kind", "type", "sort", "are", "was", "who",
            "what", "where", "when", "which", "their", "them", "they", "there", "into", "have", "has", "not", "but", "all",
            "set", "after", "before", "between", "since", "until", "during", "early", "late", "mid", "decade", "decades",
            "year", "years", "old", "new", "recent", "latest", "newest", "older");
    /** movies-service's limit on friends' movies per query. */
    private static final int MAX_FRIEND_MOVIES = 200;
    /** A year ("1994", "1990s"), a decade ("80s", "'90s") or a decade's name ("the eighties"). */
    private static final Pattern DATES = Pattern.compile(
            "\\b(?:(?:18|19|20)\\d\\d(?:'?s)?|\\d0'?s|twenties|thirties|forties|fifties|sixties|seventies|eighties|nineties)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * The words that name a genre in a request, by genre name (a genre not listed is named by its own
     * name). Plurals are listed rather than guessed, so "Star Wars" doesn't ask for war movies.
     */
    private static final Map<String, List<String>> GENRE_WORDS = Map.ofEntries(
            Map.entry("adventure", List.of("adventure", "adventures")),
            Map.entry("animation", List.of("animation", "animated", "cartoon", "cartoons", "anime")),
            Map.entry("comedy", List.of("comedy", "comedies", "funny")),
            Map.entry("documentary", List.of("documentary", "documentaries")),
            Map.entry("drama", List.of("drama", "dramas")),
            Map.entry("history", List.of("history", "historical")),
            Map.entry("horror", List.of("horror", "scary")),
            Map.entry("music", List.of("music", "musical", "musicals")),
            Map.entry("mystery", List.of("mystery", "mysteries")),
            Map.entry("romance", List.of("romance", "romantic", "rom com", "romcom")),
            Map.entry("science fiction", List.of("science fiction", "sci fi", "scifi")),
            Map.entry("thriller", List.of("thriller", "thrillers")),
            Map.entry("western", List.of("western", "westerns")));

    private final MoviesClient movies;
    private final Llm llm;
    private final ObjectMapper json;
    private final int candidateCount;
    private final int friendCandidates;
    private final int descriptionChars;

    public RecommendationPipeline(@RestClient MoviesClient movies, Llm llm, ObjectMapper json,
                                  @ConfigProperty(name = "recommendation.candidates", defaultValue = "30") int candidateCount,
                                  @ConfigProperty(name = "recommendation.friend-candidates", defaultValue = "20") int friendCandidates,
                                  @ConfigProperty(name = "recommendation.description-chars", defaultValue = "160") int descriptionChars) {
        this.movies = movies;
        this.llm = llm;
        this.json = json;
        this.candidateCount = candidateCount;
        this.friendCandidates = friendCandidates;
        this.descriptionChars = descriptionChars;
    }

    public String model() {
        return llm.model();
    }

    /** The model and the pipeline version, reported with every result. */
    public String recipe() {
        return llm.model() + "/" + VERSION;
    }

    /** What a typed request asks for. Years are 0 when it doesn't say. */
    record Filters(List<String> genres, int yearFrom, int yearTo, List<String> keywords) {
        static final Filters NONE = new Filters(List.of(), 0, 0, List.of());
    }

    /** The years the model reads in a request that mentions a date; 0 for an end it leaves open. */
    record Years(int yearFrom, int yearTo) {
    }

    /** The model's choice: movie ids from the candidates, best first. */
    record Picks(List<Integer> movieIds) {
    }

    /** The genre, year and keyword filters a job's candidates must pass. */
    record Search(List<Integer> genreIds, Integer yearFrom, Integer yearTo, String text) {
    }

    /** The recommended movies' ids, best first. */
    public List<Integer> recommend(RecommendationJob job) {
        ProfileSnapshot profile = job.profile() != null ? job.profile()
                : new ProfileSnapshot("someone", List.of(), List.of(), List.of(), List.of());
        Map<Integer, Genre> genres = movies.genres().stream()
                .collect(Collectors.toMap(Genre::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<Movie> watched = details(first(profile.watchedMovieIds(), 20));
        List<Movie> saved = details(first(profile.watchlistMovieIds(), 10));
        Set<Integer> exclude = new LinkedHashSet<>(orEmpty(profile.watchedMovieIds()));
        exclude.addAll(orEmpty(profile.watchlistMovieIds()));
        List<Integer> favourites = orEmpty(profile.favoriteGenreIds());
        List<Integer> taste = tasteGenres(favourites, watched, saved);
        Map<Integer, Integer> friends = orEmpty(profile.followedWatched()).stream()
                .filter(f -> !exclude.contains(f.movieId()))
                .collect(Collectors.toMap(FriendWatch::movieId, FriendWatch::watchers, (a, b) -> a, LinkedHashMap::new));

        Filters filters = job.kind() == Kind.PROMPT ? understand(job.prompt(), genres.values()) : Filters.NONE;
        Search search = search(job.kind(), filters, genres.values(), taste);
        String meaning = job.kind() == Kind.PROMPT ? job.prompt() : null;
        List<Movie> candidates = candidates(job.kind(), search, meaning, taste, favourites, friends, exclude);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No candidate movies matched");
        }
        Picks picks = choose(job, profile, genres, watched, saved, candidates, friends);
        return validated(picks, candidates);
    }

    /** Favourite genres first, then the ones most common in what the user watched and saved. */
    List<Integer> tasteGenres(List<Integer> favourites, List<Movie> watched, List<Movie> saved) {
        Set<Integer> taste = new LinkedHashSet<>(favourites);
        Stream.concat(watched.stream(), saved.stream())
                .flatMap(movie -> orEmpty(movie.genres()).stream())
                .collect(Collectors.groupingBy(Genre::id, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .limit(HISTORY_GENRES)
                .forEach(entry -> taste.add(entry.getKey()));
        return List.copyOf(taste);
    }

    /**
     * The filters a typed request asks for, only from what it states: the genres it names, the years it
     * mentions (the model turns "from the 80s" into 1980 to 1989), and its other words as keywords. A
     * small model asked for more fills it in, with years, genres or keywords nobody asked for.
     */
    Filters understand(String request, Collection<Genre> genres) {
        int from = 0;
        int to = 0;
        if (mentionsDate(request)) {
            ObjectNode schema = objectSchema();
            properties(schema).set("yearFrom", type("integer"));
            properties(schema).set("yearTo", type("integer"));
            required(schema, "yearFrom", "yearTo");
            String system = """
                    You read the release years a request for a movie asks for. Answer only with JSON: yearFrom and \
                    yearTo, e.g. 1980 and 1989 for "from the 80s", or 1990 and 0 for "after 1990"; 0 for an end it \
                    leaves open.""";
            Years years = llm.ask(system, "Request: " + request, schema, Years.class);
            if (years != null) {
                from = years.yearFrom();
                to = years.yearTo();
            }
        }
        return new Filters(namedGenres(request, genres), from, to, keywords(request, genreWordsIn(request, genres)));
    }

    /** Whether the request mentions a year or a decade: only then does it get a date filter. */
    static boolean mentionsDate(String request) {
        return DATES.matcher(request).find();
    }

    /** The genres the request names, e.g. Horror for "something scary". */
    static List<String> namedGenres(String request, Collection<Genre> genres) {
        String said = words(request);
        return genres.stream()
                .map(Genre::name)
                .filter(name -> formsOf(name).stream().anyMatch(form -> said.contains(words(form))))
                .toList();
    }

    /**
     * The request's words that say something about the movie itself, to match titles and descriptions
     * with: not the genres it names or its dates (they're filters already); filler words ("movies",
     * "like") and short ones are dropped later, by {@link #keywordQuery}.
     */
    static List<String> keywords(String request, List<String> genreWords) {
        return Arrays.stream(words(request).strip().split(" "))
                .filter(word -> !word.isEmpty() && !genreWords.contains(word) && !DATES.matcher(word).matches())
                .toList();
    }

    /** The words of the request that named a genre, e.g. "sci" and "fi" in "a sci-fi movie". */
    private static List<String> genreWordsIn(String request, Collection<Genre> genres) {
        String said = words(request);
        return genres.stream()
                .flatMap(genre -> formsOf(genre.name()).stream())
                .filter(form -> said.contains(words(form)))
                .flatMap(form -> Arrays.stream(words(form).strip().split(" ")))
                .toList();
    }

    private static List<String> formsOf(String genre) {
        String name = genre.toLowerCase(Locale.ROOT);
        return GENRE_WORDS.getOrDefault(name, List.of(name));
    }

    /** Lower case, with anything but letters and digits as single spaces, padded: " sci fi movies ". */
    private static String words(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip() + " ";
    }

    /** A typed request's filters, or for profile recommendations (and vague requests) the user's taste genres. */
    Search search(Kind kind, Filters filters, Collection<Genre> allGenres, List<Integer> taste) {
        if (kind == Kind.PROMPT) {
            List<Integer> genreIds = genreIds(filters.genres(), allGenres);
            Integer from = year(filters.yearFrom());
            Integer to = year(filters.yearTo());
            if (from != null && to != null && from > to) {
                Integer swap = from;
                from = to;
                to = swap;
            }
            String text = keywordQuery(filters.keywords());
            if (!genreIds.isEmpty() || from != null || to != null || text != null) {
                return new Search(genreIds, from, to, text);
            }
        }
        return new Search(taste, null, null, null);
    }

    /**
     * Up to {@code candidateCount} candidates, from the most specific query to looser ones, until there
     * are enough (a typed request can easily be too narrow for the catalogue); then up to
     * {@code friendCandidates} movies the people the user follows watched, if they pass the search's
     * genre and year filters. A typed request is also passed as {@code meaning}: movies-service then ranks
     * by closeness of meaning and its keywords only add to the score, so the first query is usually enough.
     */
    List<Movie> candidates(Kind kind, Search s, String meaning, List<Integer> taste, List<Integer> favourites,
                           Map<Integer, Integer> friends, Set<Integer> exclude) {
        List<Search> queries = new ArrayList<>();
        if (kind == Kind.PROMPT) {
            queries.add(s);
            queries.add(new Search(s.genreIds(), s.yearFrom(), s.yearTo(), null));
            queries.add(new Search(s.genreIds(), null, null, null));
            queries.add(new Search(List.of(), s.yearFrom(), s.yearTo(), null));
            queries.add(new Search(List.of(), null, null, s.text()));
        } else if (!taste.isEmpty()) {
            queries.add(s);
        }
        queries.add(new Search(List.of(), null, null, null));

        List<MoviesClient.FriendWatch> bonus = friends.entrySet().stream().limit(MAX_FRIEND_MOVIES)
                .map(e -> new MoviesClient.FriendWatch(e.getKey(), e.getValue())).toList();
        Map<Integer, Movie> found = new LinkedHashMap<>();
        for (Search q : queries.stream().distinct().toList()) {
            if (found.size() >= candidateCount) break;
            if (kind == Kind.PROMPT && isUnfiltered(q) && !found.isEmpty()) break; // don't pad a request with random picks
            fetch(q, meaning, favourites, bonus, null, skip(exclude, found), candidateCount - found.size())
                    .forEach(movie -> found.putIfAbsent(movie.id(), movie));
        }

        List<Integer> friendsLeft = friends.keySet().stream().filter(id -> !found.containsKey(id)).limit(MAX_FRIEND_MOVIES).toList();
        if (friendCandidates > 0 && !friendsLeft.isEmpty()) {
            Search filters = new Search(s.genreIds(), s.yearFrom(), s.yearTo(), null); // keywords would be too strict here
            fetch(filters, meaning, favourites, bonus, friendsLeft, skip(exclude, found), friendCandidates)
                    .forEach(movie -> found.putIfAbsent(movie.id(), movie));
        }
        return List.copyOf(found.values());
    }

    private List<Movie> fetch(Search q, String meaning, List<Integer> favourites, List<MoviesClient.FriendWatch> bonus,
                              List<Integer> onlyIds, List<Integer> skip, int limit) {
        return movies.candidates(new CandidateQuery(nullIfEmpty(q.genreIds()), nullIfEmpty(favourites), q.yearFrom(), q.yearTo(),
                q.text(), meaning, nullIfEmpty(bonus), onlyIds, skip, null, limit));
    }

    Picks choose(RecommendationJob job, ProfileSnapshot profile, Map<Integer, Genre> genres, List<Movie> watched,
                 List<Movie> saved, List<Movie> candidates, Map<Integer, Integer> friends) {
        int count = Math.min(RECOMMENDATIONS, candidates.size());
        ObjectNode ids = arrayOf(enumOf("integer", candidates.stream().map(Movie::id).toList())).put("minItems", count).put("maxItems", count);
        ObjectNode schema = objectSchema();
        properties(schema).set("movieIds", ids);
        required(schema, "movieIds");

        String system = """
                You recommend movies. From the candidate list, choose the %d movies that best suit this user. \
                If they made a request, read each candidate's description and choose the ones that best match \
                what they asked for; otherwise match their favourite genres and the kind of movies they watched. \
                Movies the people they follow watched are a good sign. Answer only with JSON: the ids of the \
                movies you choose, best first, each at most once, only from the list.""".formatted(count);

        StringBuilder user = new StringBuilder("About the user:\n");
        List<String> favourites = orEmpty(profile.favoriteGenreIds()).stream().map(genres::get).filter(Objects::nonNull).map(Genre::name).toList();
        user.append("- Favourite genres: ").append(favourites.isEmpty() ? "none picked" : String.join(", ", favourites)).append('\n');
        user.append("- Recently watched: ").append(watched.isEmpty() ? "nothing yet" : titles(watched, HISTORY_SHOWN)).append('\n');
        if (!saved.isEmpty()) user.append("- Wants to watch: ").append(titles(saved, WATCHLIST_SHOWN)).append('\n');
        if (job.kind() == Kind.PROMPT) user.append("\nTheir request: \"").append(job.prompt()).append("\"\n");
        user.append("\nCandidates (id | title (year) | genres | rating | about):\n");
        for (Movie movie : candidates) {
            user.append(movie.id()).append(" | ").append(title(movie)).append(" | ")
                    .append(orEmpty(movie.genres()).stream().map(Genre::name).collect(Collectors.joining(", "))).append(" | ")
                    .append(movie.rating() != null ? String.format(Locale.ROOT, "%.1f/10", movie.rating()) : "unrated").append(" | ")
                    .append(shorten(movie.description()));
            Integer watchers = friends.get(movie.id());
            if (watchers != null) {
                user.append(" | watched by ").append(watchers).append(watchers == 1 ? " person" : " people").append(" you follow");
            }
            user.append('\n');
        }
        return llm.ask(system, user.toString(), schema, Picks.class);
    }

    /**
     * The model's picks that really are candidates, once each, in its order, topped up with the best
     * remaining candidates if it chose fewer than ten.
     */
    /** The model's choices that are candidates, each once, then the best-ranked candidates if it chose too few. */
    List<Integer> validated(Picks picks, List<Movie> candidates) {
        Set<Integer> candidateIds = candidates.stream().map(Movie::id).collect(Collectors.toSet());
        Set<Integer> chosen = new LinkedHashSet<>();
        for (Integer id : picks != null && picks.movieIds() != null ? picks.movieIds() : List.<Integer>of()) {
            if (chosen.size() < RECOMMENDATIONS && id != null && candidateIds.contains(id)) chosen.add(id);
        }
        for (Movie movie : candidates) {
            if (chosen.size() >= RECOMMENDATIONS) break;
            chosen.add(movie.id());
        }
        return List.copyOf(chosen);
    }

    private List<Movie> details(List<Integer> ids) {
        return ids.isEmpty() ? List.of() : movies.movies(ids);
    }

    private static List<Integer> skip(Set<Integer> exclude, Map<Integer, Movie> found) {
        List<Integer> skip = new ArrayList<>(exclude);
        skip.addAll(found.keySet());
        return skip;
    }

    private static List<Integer> genreIds(List<String> names, Collection<Genre> genres) {
        Map<String, Integer> byName = genres.stream().collect(Collectors.toMap(g -> g.name().toLowerCase(Locale.ROOT), Genre::id, (a, b) -> a));
        return orEmpty(names).stream().map(name -> byName.get(name.toLowerCase(Locale.ROOT))).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * The keywords' words as a search matching any of them ("ghost or haunted"), or null if there are
     * none. The model often answers with phrases, even titles ("Lord of the Rings"), so they're split
     * into words; short and filler words ("movies", "like") go.
     */
    static String keywordQuery(List<String> keywords) {
        List<String> words = orEmpty(keywords).stream()
                .flatMap(keyword -> Arrays.stream(keyword.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")))
                .filter(word -> word.length() >= 3 && !FILLER.contains(word))
                .distinct()
                .limit(8)
                .toList();
        return words.isEmpty() ? null : String.join(" or ", words);
    }

    private static Integer year(int year) {
        return year >= 1870 && year <= 2100 ? year : null;
    }

    private static boolean isUnfiltered(Search q) {
        return q.genreIds().isEmpty() && q.yearFrom() == null && q.yearTo() == null && q.text() == null;
    }

    private static String titles(List<Movie> movies, int max) {
        return movies.stream().limit(max).map(RecommendationPipeline::title).collect(Collectors.joining("; "));
    }

    private static String title(Movie movie) {
        return movie.releaseDate() != null ? movie.title() + " (" + movie.releaseDate().getYear() + ")" : movie.title();
    }

    private String shorten(String text) {
        if (text == null || text.isBlank()) return "no description";
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= descriptionChars) return flat;
        int cut = flat.lastIndexOf(' ', descriptionChars);
        return flat.substring(0, cut > descriptionChars / 2 ? cut : descriptionChars) + "…";
    }

    private static <T> List<T> first(List<T> list, int n) {
        return orEmpty(list).stream().limit(n).toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }

    private static <T> List<T> nullIfEmpty(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    // JSON schema helpers for Ollama's structured output.

    private ObjectNode objectSchema() {
        ObjectNode node = json.createObjectNode().put("type", "object");
        node.putObject("properties");
        return node;
    }

    private static ObjectNode properties(ObjectNode schema) {
        return (ObjectNode) schema.get("properties");
    }

    private static void required(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String name : names) required.add(name);
    }

    private ObjectNode type(String type) {
        return json.createObjectNode().put("type", type);
    }

    private ObjectNode arrayOf(ObjectNode items) {
        ObjectNode node = type("array");
        node.set("items", items);
        return node;
    }

    private ObjectNode enumOf(String type, List<?> values) {
        ObjectNode node = type(type);
        ArrayNode allowed = node.putArray("enum");
        values.forEach(allowed::addPOJO);
        return node;
    }
}
