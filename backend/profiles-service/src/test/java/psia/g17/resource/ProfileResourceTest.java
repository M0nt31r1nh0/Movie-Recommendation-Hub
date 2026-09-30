package psia.g17.resource;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import psia.g17.client.MoviesClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static psia.g17.TestUsers.ALICE;
import static psia.g17.TestUsers.BOB;
import static psia.g17.TestUsers.CAROL;
import static psia.g17.TestUsers.DAVE;

// Each test cleans up what it changes, since they share one database.
@QuarkusTest
class ProfileResourceTest {

    /** What the mocked movies-service knows. */
    private static final Map<Integer, MoviesClient.Movie> MOVIES = Map.of(
            1, new MoviesClient.Movie(1, "The Godfather", "/godfather.jpg", LocalDate.of(1972, 3, 14)),
            2, new MoviesClient.Movie(2, "Pulp Fiction", "/pulp.jpg", LocalDate.of(1994, 9, 10)),
            3, new MoviesClient.Movie(3, "Spirited Away", "/spirited.jpg", LocalDate.of(2001, 7, 20)));

    @InjectMock
    @RestClient
    MoviesClient movies;

    @BeforeEach
    void mockMoviesService() {
        when(movies.genres()).thenReturn(List.of(
                new MoviesClient.Genre(16, "Animation"), new MoviesClient.Genre(18, "Drama"), new MoviesClient.Genre(80, "Crime")));
        when(movies.movies(anyList())).thenAnswer(call -> call.<List<Integer>>getArgument(0).stream()
                .map(MOVIES::get).filter(Objects::nonNull).toList());
    }

    @Test
    @TestSecurity(user = "dave")
    @OidcSecurity(claims = {@Claim(key = "sub", value = DAVE), @Claim(key = "preferred_username", value = "dave")})
    void createsTheProfileOnTheUsersFirstRequest() {
        given().when().get("/profiles/me")
                .then()
                .statusCode(200)
                .body("id", is(DAVE))
                .body("username", is("dave"))
                .body("favoriteGenres", empty())
                .body("watchedCount", is(0))
                .body("followingCount", is(0))
                .body("followersCount", is(0))
                .body("me", is(true));
        given().when().get("/profiles/" + DAVE).then().statusCode(200).body("username", is("dave"));
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void replacesFavoriteGenres() {
        setFavoriteGenres("[80, 16, 80]")
                .statusCode(200)
                .body("favoriteGenres.name", contains("Animation", "Crime"));
        given().when().get("/profiles/me").then().body("favoriteGenres.id", contains(16, 80));
        setFavoriteGenres("[]").statusCode(200).body("favoriteGenres", empty());
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void rejectsUnknownGenres() {
        setFavoriteGenres("[16, 999]")
                .statusCode(400)
                .body("error", is("Unknown genre ids: [999]"));
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void marksMoviesAsWatched() {
        given().when().put("/profiles/me/watched/1").then().statusCode(204);
        given().when().put("/profiles/me/watched/3").then().statusCode(204);
        given().when().put("/profiles/me/watched/1").then().statusCode(204); // again: no change
        given().when().get("/profiles/me/watched")
                .then()
                .statusCode(200)
                .body("items.movieId", contains(3, 1))
                .body("items[1].title", is("The Godfather"))
                .body("totalItems", is(2));
        given().when().get("/profiles/me/watched/1").then().statusCode(200).body("title", is("The Godfather"));
        given().when().get("/profiles/me").then().body("watchedCount", is(2));

        given().when().delete("/profiles/me/watched/1").then().statusCode(204);
        given().when().delete("/profiles/me/watched/3").then().statusCode(204);
        given().when().get("/profiles/me/watched/1").then().statusCode(404);
        given().when().get("/profiles/me/watched").then().body("items", empty());
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void keepsAWatchLaterList() {
        given().when().put("/profiles/me/watchlist/2").then().statusCode(204);
        given().when().put("/profiles/me/watchlist/3").then().statusCode(204);
        given().when().put("/profiles/me/watchlist/2").then().statusCode(204); // again: no change
        given().when().get("/profiles/me/watchlist")
                .then()
                .statusCode(200)
                .body("items.movieId", contains(3, 2))
                .body("items[1].title", is("Pulp Fiction"));
        given().when().get("/profiles/me/watchlist/2").then().statusCode(200);
        given().when().get("/profiles/me").then().body("watchlistCount", is(2));

        given().when().delete("/profiles/me/watchlist/2").then().statusCode(204);
        given().when().delete("/profiles/me/watchlist/3").then().statusCode(204);
        given().when().get("/profiles/me/watchlist/2").then().statusCode(404);
        given().when().get("/profiles/me/watchlist").then().body("items", empty());
        given().when().put("/profiles/me/watchlist/9999").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void markingAMovieAsWatchedTakesItOffTheWatchlist() {
        given().when().put("/profiles/me/watchlist/1").then().statusCode(204);
        given().when().put("/profiles/me/watched/1").then().statusCode(204);
        given().when().get("/profiles/me/watchlist/1").then().statusCode(404);

        given().when().delete("/profiles/me/watched/1").then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void keepsOtherUsersWatchlistsPrivate() {
        given().when().get("/profiles/" + BOB + "/watchlist").then().statusCode(404);
        given().when().get("/profiles/" + BOB).then().statusCode(200).body("watchlistCount", nullValue());
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void answers503WhenTheMoviesServiceIsDown() {
        when(movies.movies(anyList())).thenThrow(new ProcessingException("Connection refused"));
        given().when().put("/profiles/me/watched/1")
                .then()
                .statusCode(503)
                .body("error", startsWith("The movies service isn't available"));
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void rejectsWatchingUnknownMovies() {
        given().when().put("/profiles/me/watched/9999").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void followsAndUnfollowsProfiles() {
        given().when().put("/profiles/me/following/" + BOB).then().statusCode(204);
        given().when().put("/profiles/me/following/" + CAROL).then().statusCode(204);
        given().when().put("/profiles/me/following/" + BOB).then().statusCode(204); // again: no change

        given().when().get("/profiles/me/following").then().body("items.username", contains("carol", "bob"));
        given().when().get("/profiles/" + BOB + "/followers").then().body("items.username", contains("alice"));
        given().when().get("/profiles/" + BOB)
                .then()
                .body("followedByMe", is(true))
                .body("followersCount", is(1))
                .body("me", is(false));
        given().when().get("/profiles/me").then().body("followingCount", is(2));

        given().when().delete("/profiles/me/following/" + BOB).then().statusCode(204);
        given().when().delete("/profiles/me/following/" + CAROL).then().statusCode(204);
        given().when().get("/profiles/" + BOB).then().body("followedByMe", is(false)).body("followersCount", is(0));
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void rejectsFollowingYourselfOrUnknownProfiles() {
        given().when().put("/profiles/me/following/" + ALICE).then().statusCode(400);
        given().when().put("/profiles/me/following/me").then().statusCode(400);
        given().when().put("/profiles/me/following/e5555555-5555-5555-5555-555555555555").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void searchesProfilesByUsernamePrefix() {
        given().queryParam("q", "BO").when().get("/profiles").then().statusCode(200).body("items.username", contains("bob"));
        given().queryParam("q", "%").when().get("/profiles").then().body("items", empty());
        given().queryParam("size", 2).when().get("/profiles")
                .then()
                .body("items.username", contains("alice", "bob"))
                .body("totalPages", is(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"e5555555-5555-5555-5555-555555555555", "not-a-uuid"})
    @TestSecurity(user = "alice")
    @OidcSecurity(claims = {@Claim(key = "sub", value = ALICE), @Claim(key = "preferred_username", value = "alice")})
    void returns404ForUnknownProfiles(String id) {
        given().when().get("/profiles/" + id).then().statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/profiles", "/profiles/me", "/profiles/" + BOB + "/watched"})
    void rejectsRequestsWithoutAToken(String path) {
        given().when().get(path).then().statusCode(401);
    }

    private static io.restassured.response.ValidatableResponse setFavoriteGenres(String json) {
        return given().contentType(ContentType.JSON).body(json)
                .when().put("/profiles/me/favorite-genres")
                .then();
    }
}
