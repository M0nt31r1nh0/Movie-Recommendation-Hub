package psia.g17.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import psia.g17.client.MoviesClient;
import psia.g17.events.FriendWatch;
import psia.g17.events.Kind;
import psia.g17.events.ProfileSnapshot;
import psia.g17.events.RecommendationJob;
import psia.g17.service.RecommendationPipeline.Picks;
import psia.g17.service.RecommendationPipeline.Years;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationPipelineTest {

    private FakeMovies movies;
    private Llm llm;
    private RecommendationPipeline pipeline;

    @BeforeEach
    void setUp() {
        movies = new FakeMovies();
        llm = mock(Llm.class);
        pipeline = new RecommendationPipeline(movies, llm, new ObjectMapper(), 30, 20, 160);
    }

    @Test
    void profileRecommendationsUseTheUsersTasteAndSkipWhatTheyWatchedOrSaved() {
        // Favourite: science fiction. Watched The Godfather (drama), saved Airplane! (comedy).
        ProfileSnapshot profile = new ProfileSnapshot("carol", List.of(878), List.of(1), List.of(5),
                List.of(new FriendWatch(20, 2), new FriendWatch(1, 1)));
        when(llm.ask(anyString(), anyString(), any(), eq(Picks.class)))
                .thenReturn(new Picks(List.of(3, 999, 3, 2)));

        List<Integer> ids = pipeline.recommend(job(Kind.PROFILE, null, profile));

        assertEquals(List.of(878, 18, 35), movies.queries.get(0).genreIds(), "favourite genres first, then watched and saved ones");
        assertEquals(List.of(878), movies.queries.get(0).preferredGenreIds(), "favourites count double");
        assertEquals(List.of(new MoviesClient.FriendWatch(20, 2)), movies.queries.get(0).friendWatchers(),
                "friends' movies get a bonus, except ones the user already watched");
        assertTrue(movies.queries.get(0).excludeIds().containsAll(List.of(1, 5)));
        assertNull(movies.queries.get(0).meaning(), "no request to match");
        assertEquals(10, ids.size());
        assertEquals(List.of(3, 2), ids.subList(0, 2), "the model's choices first, in its order");
        assertEquals(ids.size(), ids.stream().distinct().count(), "each movie once");
        assertFalse(ids.contains(999), "only candidates");
        assertFalse(ids.contains(1) || ids.contains(5), "nothing watched or saved");

        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(llm).ask(anyString(), userMessage.capture(), schema.capture(), eq(Picks.class));
        assertTrue(userMessage.getValue().contains("Favourite genres: Science Fiction"));
        assertTrue(userMessage.getValue().contains("Recently watched: The Godfather (1972)"));
        assertTrue(userMessage.getValue().contains("Wants to watch: Airplane! (1980)"));
        assertTrue(userMessage.getValue().contains("20 | A Friend's Pick (2001) | Drama | 7.0/10 | Something a friend liked. | watched by 2 people you follow"),
                "a movie people they follow watched, passing the taste filter, marked as such");
        JsonNode allowedIds = schema.getValue().at("/properties/movieIds/items/enum");
        assertTrue(allowedIds.isArray() && allowedIds.size() > 0, "the model may only answer with candidate ids");
        assertEquals(10, schema.getValue().at("/properties/movieIds/maxItems").asInt());
    }

    @Test
    void requestsAreTurnedIntoFiltersBeforeLookingForCandidates() {
        when(llm.ask(anyString(), anyString(), any(), eq(Years.class))).thenReturn(new Years(1989, 1980));
        when(llm.ask(anyString(), anyString(), any(), eq(Picks.class))).thenReturn(new Picks(List.of(4)));
        ProfileSnapshot profile = new ProfileSnapshot("frank", List.of(), List.of(), List.of(),
                List.of(new FriendWatch(20, 3), new FriendWatch(3, 1)));

        List<Integer> ids = pipeline.recommend(job(Kind.PROMPT, "a haunted house horror movie with ghosts from the 80s", profile));

        MoviesClient.CandidateQuery first = movies.queries.get(0);
        assertEquals(List.of(27), first.genreIds(), "the genre the request names");
        assertEquals(1980, first.yearFrom(), "a reversed range is put the right way round");
        assertEquals(1989, first.yearTo());
        assertEquals("haunted or house or ghosts", first.text(), "its own words, without filler, the genre or the date");
        assertEquals(List.of(4, 3), ids.subList(0, 2), "the model's choice, then the looser query's best match");
        assertTrue(ids.stream().noneMatch(id -> List.of(6, 7, 8).contains(id)),
                "a request isn't padded with unrelated movies");

        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(llm).ask(anyString(), userMessage.capture(), any(), eq(Picks.class));
        assertTrue(userMessage.getValue().contains("Their request: \"a haunted house horror movie with ghosts from the 80s\""));
        assertTrue(userMessage.getValue().contains("3 | The Thing (1982)") && userMessage.getValue().contains("watched by 1 person you follow"),
                "a friend's movie that fits the request is marked");
        assertFalse(userMessage.getValue().contains("A Friend's Pick"), "a friend's movie that doesn't fit the request is left out");
    }

    @Test
    void keepsOnlyTheFiltersTheRequestStates() {
        // qwen2.5:0.5b, asked for filters, answered this one with years and genres nobody asked for.
        pipeline.recommend(job(Kind.PROMPT, "lord of the rings", emptyProfile()));

        MoviesClient.CandidateQuery first = movies.queries.get(0);
        assertNull(first.genreIds(), "it names no genre");
        assertNull(first.yearFrom(), "it mentions no date");
        assertNull(first.yearTo());
        assertEquals("lord or rings", first.text(), "its own words, without filler");
        verify(llm, never()).ask(anyString(), anyString(), any(), eq(Years.class));
    }

    @Test
    void readsYearsOnlyFromRequestsThatMentionThem() {
        when(llm.ask(anyString(), anyString(), any(), eq(Years.class))).thenReturn(new Years(2000, 2009));

        pipeline.recommend(job(Kind.PROMPT, "movies about serial killers from the 2000s", emptyProfile()));

        MoviesClient.CandidateQuery first = movies.queries.get(0);
        assertEquals(2000, first.yearFrom());
        assertEquals(2009, first.yearTo());
        assertEquals("serial or killers", first.text(), "not \"movies\", \"about\", \"from\" or \"2000s\"");
        assertEquals("movies about serial killers from the 2000s", first.meaning(), "the whole request, to rank by meaning");
        assertNull(first.genreIds());
    }

    @Test
    void recognisesDatesAndGenresInRequests() {
        for (String dated : List.of("a horror movie from the 80s", "'90s comedies", "something from the 1990s", "after 1994", "the eighties")) {
            assertTrue(RecommendationPipeline.mentionsDate(dated), dated);
        }
        for (String undated : List.of("lord of the rings", "top 10 thrillers", "under 90 minutes", "recent movies")) {
            assertFalse(RecommendationPipeline.mentionsDate(undated), undated);
        }
        List<MoviesClient.Genre> genres = List.of(new MoviesClient.Genre(27, "Horror"), new MoviesClient.Genre(878, "Science Fiction"),
                new MoviesClient.Genre(10752, "War"), new MoviesClient.Genre(16, "Animation"), new MoviesClient.Genre(35, "Comedy"));
        assertEquals(List.of("Horror", "Science Fiction"), RecommendationPipeline.namedGenres("something scary and sci-fi", genres));
        assertEquals(List.of("Animation", "Comedy"), RecommendationPipeline.namedGenres("funny animated movies", genres));
        assertEquals(List.of("War"), RecommendationPipeline.namedGenres("a war movie", genres));
        assertEquals(List.of(), RecommendationPipeline.namedGenres("Star Wars", genres), "a plural that isn't listed doesn't count");
    }

    @Test
    void aVagueRequestFallsBackToTheUsersTaste() {
        when(llm.ask(anyString(), anyString(), any(), eq(Picks.class))).thenReturn(new Picks(List.of()));

        pipeline.recommend(job(Kind.PROMPT, "something good", new ProfileSnapshot("dave", List.of(35), List.of(), List.of(), List.of())));

        assertTrue(movies.queries.stream().anyMatch(q -> List.of(35).equals(q.genreIds())));
    }

    @Test
    void fillsUpFromTheCandidatesWhenTheModelChoosesTooFew() {
        when(llm.ask(anyString(), anyString(), any(), eq(Picks.class))).thenReturn(new Picks(null));

        List<Integer> ids = pipeline.recommend(job(Kind.PROFILE, null, emptyProfile()));

        assertEquals(10, ids.size());
    }

    @Test
    void failsWhenNoMovieMatchesAtAll() {
        ProfileSnapshot watchedEverything = new ProfileSnapshot("eve", List.of(), FakeMovies.MOVIES.stream().map(MoviesClient.Movie::id).toList(),
                List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> pipeline.recommend(job(Kind.PROFILE, null, watchedEverything)));
    }

    @Test
    void theRecipeNamesTheModelAndThePipelineVersion() {
        when(llm.model()).thenReturn("qwen2.5:3b");
        assertEquals("qwen2.5:3b/" + RecommendationPipeline.VERSION, pipeline.recipe());
    }

    @Test
    void keywordQueriesKeepOnlyRealWords() {
        assertEquals("space or aliens or ufo", RecommendationPipeline.keywordQuery(List.of("Space", "aliens", "space", "!!", "UFO?s")));
        assertEquals("lord or rings", RecommendationPipeline.keywordQuery(List.of("Lord of the Rings")), "phrases become words");
        assertEquals("lord or rings", RecommendationPipeline.keywordQuery(List.of("movies", "like", "Lord of the Rings")), "filler words go");
        assertEquals("mario or kart or games or video", RecommendationPipeline.keywordQuery(List.of("Mario", "Mario Kart", "Mario games", "Mario video games")));
        assertNull(RecommendationPipeline.keywordQuery(List.of()));
        assertNull(RecommendationPipeline.keywordQuery(null));
    }

    private static RecommendationJob job(Kind kind, String prompt, ProfileSnapshot profile) {
        return new RecommendationJob(UUID.randomUUID(), UUID.randomUUID(), kind, prompt, profile, Instant.now());
    }

    private static ProfileSnapshot emptyProfile() {
        return new ProfileSnapshot("frank", List.of(), List.of(), List.of(), List.of());
    }
}
