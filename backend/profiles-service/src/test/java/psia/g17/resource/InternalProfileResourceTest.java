package psia.g17.resource;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import psia.g17.client.MoviesClient;
import psia.g17.entity.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static psia.g17.TestUsers.ALICE;
import static psia.g17.TestUsers.BOB;
import static psia.g17.TestUsers.CAROL;

@QuarkusTest
class InternalProfileResourceTest {

    @InjectMock
    @RestClient
    MoviesClient movies;

    @Inject
    EntityManager em;

    @BeforeEach
    void mockMoviesService() {
        when(movies.movies(anyList())).thenAnswer(call -> call.<List<Integer>>getArgument(0).stream()
                .map(id -> new MoviesClient.Movie(id, "Movie " + id, null, null)).toList());
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("favorite_genre", "watched_movie", "watchlist")) {
            sql("delete from profiles." + table);
        }
        sql("delete from profiles.follow");
    }

    @Test
    @TestSecurity(user = "recommendation-service", roles = "profiles-reader")
    void listsEveryProfileWithItsFingerprint() {
        given().when().get("/internal/profiles")
                .then()
                .statusCode(200)
                .body("items.username", hasItem("carol"))
                .body("items[0].fingerprint.root", hasLength(64))
                .body("items[0].fingerprint.own", hasLength(64))
                .body("items[0].fingerprint.social", hasLength(64));
    }

    @Test
    @TestSecurity(user = "recommendation-service", roles = "profiles-reader")
    void snapshotsHoldGenresMoviesAndWhatFollowedPeopleWatchedWithHowMany() {
        sql("insert into profiles.favorite_genre values ('%s', 80), ('%s', 18)".formatted(CAROL, CAROL));
        sql("insert into profiles.watched_movie (profile_id, movie_id, watched_at) values ('%s', 1, now() - interval '1 day'), ('%s', 4, now())".formatted(CAROL, CAROL));
        sql("insert into profiles.watchlist (profile_id, movie_id) values ('%s', 2)".formatted(CAROL));
        sql("insert into profiles.follow (follower_id, followed_id) values ('%s', '%s'), ('%s', '%s')".formatted(CAROL, BOB, CAROL, ALICE));
        sql("insert into profiles.watched_movie (profile_id, movie_id) values ('%s', 3), ('%s', 3), ('%s', 5)".formatted(BOB, ALICE, ALICE));

        given().when().get("/internal/profiles/" + CAROL + "/snapshot")
                .then()
                .statusCode(200)
                .body("username", is("carol"))
                .body("favoriteGenreIds", contains(18, 80))
                .body("watchedMovieIds", contains(4, 1))
                .body("watchlistMovieIds", contains(2))
                .body("followedIds", containsInAnyOrder(BOB, ALICE))
                .body("followedWatched.movieId", contains(3, 5))
                .body("followedWatched.watchers", contains(2, 1))
                .body("fingerprint.root", hasLength(64));
    }

    @Test
    @TestSecurity(user = "recommendation-service", roles = "profiles-reader")
    void theFingerprintChangesWithTheInputsAndOnlyThem() {
        Map<String, String> empty = fingerprint(CAROL);

        sql("insert into profiles.watchlist (profile_id, movie_id) values ('%s', 2)".formatted(CAROL));
        Map<String, String> saved = fingerprint(CAROL);
        assertNotEquals(empty.get("root"), saved.get("root"));
        assertNotEquals(empty.get("own"), saved.get("own"), "the user's own part changed");
        assertEquals(empty.get("social"), saved.get("social"), "the social part didn't");

        sql("delete from profiles.watchlist where profile_id = '%s'".formatted(CAROL));
        assertEquals(empty, fingerprint(CAROL), "back to the same state, back to the same fingerprint");

        sql("insert into profiles.follow (follower_id, followed_id) values ('%s', '%s')".formatted(CAROL, BOB));
        sql("insert into profiles.watched_movie (profile_id, movie_id) values ('%s', 3)".formatted(BOB));
        Map<String, String> social = fingerprint(CAROL);
        assertEquals(empty.get("own"), social.get("own"));
        assertNotEquals(empty.get("social"), social.get("social"), "someone they follow watched something");
    }

    @Test
    @TestSecurity(user = "carol")
    @OidcSecurity(claims = {@Claim(key = "sub", value = CAROL), @Claim(key = "preferred_username", value = "carol")})
    void recordsWhenAProfilesInputsLastChanged() {
        Instant before = lastChanged(CAROL);
        given().when().put("/profiles/me/watched/1").then().statusCode(204);
        Instant afterWatching = lastChanged(CAROL);
        assertTrue(afterWatching.isAfter(before), "marking a movie as watched updates last_changed_at");

        given().when().delete("/profiles/me/watched/1").then().statusCode(204);
        assertTrue(lastChanged(CAROL).isAfter(afterWatching), "unmarking it does too");

        Instant beforeNoop = lastChanged(CAROL);
        given().when().delete("/profiles/me/watched/1").then().statusCode(204);
        assertEquals(beforeNoop, lastChanged(CAROL), "removing something that wasn't there changes nothing");
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void refusesUsersWithoutTheReaderRole() {
        given().when().get("/internal/profiles").then().statusCode(403);
        given().when().get("/internal/profiles/" + BOB + "/snapshot").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "recommendation-service", roles = "profiles-reader")
    void snapshotsHoldEveryWatchedMovieSoNoneIsRecommendedAgain() {
        sql("insert into profiles.watched_movie (profile_id, movie_id, watched_at) select '%s', m, now() - m * interval '1 hour' from generate_series(1, 120) m"
                .formatted(CAROL));

        given().when().get("/internal/profiles/" + CAROL + "/snapshot")
                .then()
                .body("watchedMovieIds.size()", is(120))
                .body("watchedMovieIds[0]", is(1));
    }

    @Test
    @TestSecurity(user = "recommendation-service", roles = "profiles-reader")
    void returns404ForUnknownProfiles() {
        given().when().get("/internal/profiles/e5555555-5555-5555-5555-555555555555/snapshot").then().statusCode(404);
    }

    private static Map<String, String> fingerprint(String profileId) {
        return given().when().get("/internal/profiles/" + profileId + "/snapshot").then().statusCode(200)
                .extract().jsonPath().getMap("fingerprint");
    }

    private void sql(String statement) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(statement).executeUpdate());
    }

    private Instant lastChanged(String profileId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return Profile.<Profile>findById(UUID.fromString(profileId)).lastChangedAt;
        });
    }
}
