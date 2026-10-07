package psia.g17.resource;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import psia.g17.InMemoryKafka;
import psia.g17.TestSupport;
import psia.g17.metrics.PendingRequests;
import psia.g17.client.MoviesClient;
import psia.g17.client.ProfilesClient;
import psia.g17.dto.PromptRequest;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;
import psia.g17.events.ReadyEvent;
import psia.g17.events.RecommendationJob;
import psia.g17.events.RecommendationResult;
import psia.g17.events.Status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static psia.g17.TestSupport.done;
import static psia.g17.TestSupport.failed;
import static psia.g17.TestSupport.jobsFor;
import static psia.g17.TestSupport.readyEventsFor;
import static psia.g17.TestSupport.reload;

// Each test acts as its own user, so their requests don't meet.
@QuarkusTest
@WithTestResource(InMemoryKafka.class)
class RecommendationResourceTest {

    @InjectMock
    @RestClient
    ProfilesClient profiles;

    @InjectMock
    @RestClient
    MoviesClient movies;

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    PendingRequests pending;

    @BeforeEach
    void setUp() {
        TestSupport.mockProfilesAndMovies(profiles, movies);
    }

    @Test
    @TestSecurity(user = "u1")
    @OidcSecurity(claims = @Claim(key = "sub", value = U1))
    void queuesATypedRequestWithTheUsersProfile() {
        UUID id = UUID.fromString(ask("a horror movie from the 80s").statusCode(202)
                .body("status", is("PENDING")).body("kind", is("PROMPT")).extract().path("id"));

        RecommendationJob job = jobsFor(connector, U1).stream().filter(j -> j.requestId().equals(id)).findFirst().orElseThrow();
        assertEquals("a horror movie from the 80s", job.prompt());
        assertEquals(List.of(18), job.profile().favoriteGenreIds());
        assertEquals(List.of(1), job.profile().watchedMovieIds());
        assertEquals(3, job.profile().followedWatched().get(0).movieId());
        assertEquals(2, job.profile().followedWatched().get(0).watchers());
    }

    @Test
    @TestSecurity(user = "u2")
    @OidcSecurity(claims = @Claim(key = "sub", value = U2))
    void storesTheWorkersResultAndShowsItWithMovieDetails() {
        UUID id = askAndGetId("space adventures");
        answer(done(id, U2, Kind.PROMPT, 238, 155));

        given().when().get("/recommendations/requests/" + id)
                .then()
                .statusCode(200)
                .body("status", is("DONE"))
                .body("model", is("qwen2.5:3b"))
                .body("movies.id", contains(238, 155))
                .body("movies[0].title", is("Movie 238"))
                .body("movies[0]", not(hasKey("reason")));
        given().when().get("/recommendations/requests").then().body("prompt", hasItem("space adventures"));
        assertEquals(List.of(new ReadyEvent(id, UUID.fromString(U2), Kind.PROMPT, Status.DONE, null)), readyEventsFor(connector, id));
    }

    @Test
    @TestSecurity(user = "u3")
    @OidcSecurity(claims = @Claim(key = "sub", value = U3))
    void failedTypedRequestsAreReportedButStayOutOfTheHistory() {
        UUID id = askAndGetId("anything");
        answer(failed(id, U3, Kind.PROMPT, "The language model took too long to answer."));

        given().when().get("/recommendations/requests").then().body("id", not(hasItem(id.toString())));
        given().when().get("/recommendations/requests/current")
                .then()
                .statusCode(200)
                .body("id", is(id.toString()))
                .body("status", is("FAILED"))
                .body("error", is("The language model took too long to answer."));
        assertEquals(List.of(new ReadyEvent(id, UUID.fromString(U3), Kind.PROMPT, Status.FAILED, "The language model took too long to answer.")),
                readyEventsFor(connector, id), "the user's open connections are told why");

        UUID next = askAndGetId("something else");
        given().when().get("/recommendations/requests/current").then().body("id", is(next.toString())).body("status", is("PENDING"));
        answer(done(next, U3, Kind.PROMPT, 7));
        assertNull(reload(id), "a success clears earlier failures");
        given().when().get("/recommendations/requests/current").then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "u4")
    @OidcSecurity(claims = @Claim(key = "sub", value = U4))
    void allowsOneTypedRequestAtATime() {
        UUID first = askAndGetId("one");
        ask("two").statusCode(409)
                .body("error", is("You already have a request being prepared; wait for it to finish."))
                .body("requestId", is(first.toString()));
        answer(done(first, U4, Kind.PROMPT, 1));
        ask("two").statusCode(202);
    }

    @Test
    @TestSecurity(user = "u5")
    @OidcSecurity(claims = @Claim(key = "sub", value = U5))
    void answersTheSameQuestionWithAnUnchangedProfileAtOnce() {
        UUID id = askAndGetId("a horror movie from the 80s");
        answer(done(id, U5, Kind.PROMPT, 694));
        UUID other = askAndGetId("westerns");
        answer(done(other, U5, Kind.PROMPT, 1));

        ask("  A horror movie   from the 80s ").statusCode(200)
                .body("id", is(id.toString()))
                .body("status", is("DONE"))
                .body("movies[0].id", is(694));
        assertEquals(2, jobsFor(connector, U5).size(), "no new job");
        given().when().get("/recommendations/requests").then().body("id[0]", is(id.toString()));

        TestSupport.setFingerprint(U5, TestSupport.fingerprintOf("u5 after watching more"));
        ask("a horror movie from the 80s").statusCode(202);
    }

    @Test
    @TestSecurity(user = "u6")
    @OidcSecurity(claims = @Claim(key = "sub", value = U6))
    void keepsTheLastFiveAnswers() {
        for (int i = 1; i <= 6; i++) {
            answer(done(askAndGetId("question " + i), U6, Kind.PROMPT, i));
        }
        given().when().get("/recommendations/requests")
                .then()
                .body("prompt", contains("question 6", "question 5", "question 4", "question 3", "question 2"))
                .body("status", everyItem(is("DONE")));
    }

    @Test
    @TestSecurity(user = "u7")
    @OidcSecurity(claims = @Claim(key = "sub", value = U7))
    void preparesProfileRecommendationsOnTheFirstVisitWithoutDuplicates() {
        UUID id = UUID.fromString(given().when().get("/recommendations/me")
                .then().statusCode(200).body("status", is("PREPARING")).body("refreshing", is(true))
                .extract().path("requestId"));
        given().when().get("/recommendations/me").then().body("status", is("PREPARING")).body("requestId", is(id.toString()));
        assertEquals(1, jobsFor(connector, U7).size(), "one job, however often they ask");

        answer(done(id, U7, Kind.PROFILE, 13));
        given().when().get("/recommendations/me")
                .then()
                .body("status", is("READY"))
                .body("refreshing", is(false))
                .body("generatedAt", notNullValue())
                .body("movies[0].title", is("Movie 13"));
        assertEquals(TestSupport.fingerprint(UUID.fromString(U7)).root(), TestSupport.state(U7).inputHash,
                "profile_state remembers what the current set was made from");
    }

    @Test
    @TestSecurity(user = "u9")
    @OidcSecurity(claims = @Claim(key = "sub", value = U9))
    void identicalProfilesReuseEachOthersRecommendations() {
        TestSupport.setFingerprint(U10, TestSupport.fingerprintOf("an empty profile"));
        TestSupport.setFingerprint(U9, TestSupport.fingerprintOf("an empty profile"));
        UUID theirs = TestSupport.insertRequest(U10, Kind.PROFILE, RecommendationRequest.Status.PENDING, Instant.now());
        QuarkusTransaction.requiringNew().run(() -> {
            RecommendationRequest r = RecommendationRequest.findById(theirs);
            r.inputHash = TestSupport.fingerprintOf("an empty profile").root();
        });
        answer(done(theirs, U10, Kind.PROFILE, 5, 6));

        given().when().get("/recommendations/me")
                .then()
                .body("status", is("READY"))
                .body("movies.id", contains(5, 6));
        assertTrue(jobsFor(connector, U9).isEmpty(), "no job: the other user's result was copied");
    }

    @Test
    @TestSecurity(user = "u11")
    @OidcSecurity(claims = @Claim(key = "sub", value = U11))
    void keepsOtherUsersRequestsPrivate() {
        UUID someoneElses = TestSupport.insertRequest(U10, Kind.PROMPT, RecommendationRequest.Status.DONE, Instant.now());
        given().when().get("/recommendations/requests/" + someoneElses).then().statusCode(404);
        given().when().get("/recommendations/requests").then().body("id", not(hasItem(someoneElses.toString())));
    }

    @Test
    @TestSecurity(user = "u12")
    @OidcSecurity(claims = @Claim(key = "sub", value = U12))
    void rejectsBlankOrTooLongRequests() {
        ask("  ").statusCode(400);
        ask("x".repeat(301)).statusCode(400);
    }

    @Test
    @TestSecurity(user = "u13")
    @OidcSecurity(claims = @Claim(key = "sub", value = U13))
    void explainsWhenProfilesServiceIsDown() {
        doThrow(new ProcessingException("Connection refused")).when(profiles).snapshot(any());
        ask("anything").statusCode(503).body("error", is("Couldn't read the profile: Connection refused"));
        given().when().get("/recommendations/me").then().body("status", is("FAILED"))
                .body("error", is("Couldn't read the profile: Connection refused"));
        given().when().get("/recommendations/requests").then().body("", hasSize(0));
    }

    @Test
    @TestSecurity(user = "u14")
    @OidcSecurity(claims = @Claim(key = "sub", value = U14))
    void countsRequestsAndTimesTheirAnswers() {
        UUID id = askAndGetId("a quiet film about the sea");
        ask("another one while it's prepared").statusCode(409);
        pending.count();
        assertThat(metrics(), containsString("recommendation_pending{kind=\"prompt\"}"));
        answer(done(id, U14, Kind.PROMPT, 238));
        ask("a quiet film about the sea").statusCode(200);

        String metrics = metrics();
        assertThat(metrics, containsString("recommendation_requests_total{kind=\"prompt\",outcome=\"queued\"}"));
        assertThat(metrics, containsString("recommendation_requests_total{kind=\"prompt\",outcome=\"rejected\"}"));
        assertThat(metrics, containsString("recommendation_requests_total{kind=\"prompt\",outcome=\"repeated\"}"));
        assertThat(metrics, containsString("recommendation_turnaround_seconds_count{kind=\"prompt\",status=\"done\"}"));
        assertThat(metrics, containsString("recommendation_sse_connections "));
    }

    private static String metrics() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }

    @Test
    void rejectsRequestsWithoutAToken() {
        given().when().get("/recommendations/me").then().statusCode(401);
        given().when().get("/recommendations/events").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{\"prompt\": \"x\"}").when().post("/recommendations/requests").then().statusCode(401);
    }

    static final String U1 = "a0000000-0000-0000-0000-000000000001";
    static final String U2 = "a0000000-0000-0000-0000-000000000002";
    static final String U3 = "a0000000-0000-0000-0000-000000000003";
    static final String U4 = "a0000000-0000-0000-0000-000000000004";
    static final String U5 = "a0000000-0000-0000-0000-000000000005";
    static final String U6 = "a0000000-0000-0000-0000-000000000006";
    static final String U7 = "a0000000-0000-0000-0000-000000000007";
    static final String U9 = "a0000000-0000-0000-0000-000000000009";
    static final String U10 = "a0000000-0000-0000-0000-000000000010";
    static final String U11 = "a0000000-0000-0000-0000-000000000011";
    static final String U12 = "a0000000-0000-0000-0000-000000000012";
    static final String U13 = "a0000000-0000-0000-0000-000000000013";
    static final String U14 = "a0000000-0000-0000-0000-000000000014";

    private UUID askAndGetId(String prompt) {
        return UUID.fromString(ask(prompt).statusCode(202).extract().path("id"));
    }

    private void answer(RecommendationResult result) {
        TestSupport.answer(connector, result);
    }

    private static ValidatableResponse ask(String prompt) {
        return given().contentType(ContentType.JSON).body(new PromptRequest(prompt))
                .when().post("/recommendations/requests")
                .then();
    }
}
