package psia.g17.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestSecurity(user = "alice")
class MovieResourceTest {

    @Test
    void getsSingleMovie() {
        given().when().get("/movies/2")
                .then()
                .statusCode(200)
                .body("id", is(2))
                .body("title", is("Pulp Fiction"))
                .body("rating", is(8.3f))
                .body("releaseDate", is("1994-09-10"))
                .body("imdbId", is("tt0110912"))
                .body("genres.name", contains("Crime", "Thriller"));
    }

    @Test
    void getsSeveralMoviesInTheOrderAskedLeavingOutUnknownIds() {
        given().queryParam("ids", 3, 1, 9999, 3)
                .when().get("/movies/batch")
                .then()
                .statusCode(200)
                .body("id", contains(3, 1))
                .body("[1].title", is("The Godfather"));
    }

    @Test
    void rejectsBatchesOfMoreThan100Movies() {
        given().queryParam("ids", IntStream.rangeClosed(1, 101).boxed().toArray())
                .when().get("/movies/batch")
                .then()
                .statusCode(400);
    }

    @Test
    void picksTheBestRegardedCandidatesFirst() {
        candidates("{\"limit\": 3}").body("id", contains(1, 2, 3));
    }

    @Test
    void filtersCandidatesByGenreYearTextAndExcludedIds() {
        candidates("{\"genreIds\": [878]}").body("id", contains(4, 8, 7));
        candidates("{\"genreIds\": [878], \"excludeIds\": [4]}").body("id", contains(8, 7));
        candidates("{\"yearFrom\": 1970, \"yearTo\": 1979}").body("id", contains(1, 8));
        candidates("{\"text\": \"space\"}").body("id", containsInAnyOrder(7, 8));
        candidates("{\"genreIds\": [878], \"minRatingCount\": 1000}").body("id", contains(4));
    }

    @Test
    void favouriteGenresCountDoubleWithoutFiltering() {
        // Crime or animation; animation is preferred: Spirited Away (8.19) overtakes The Godfather (8.42).
        candidates("{\"genreIds\": [80, 16], \"preferredGenreIds\": [16], \"limit\": 3}").body("id", contains(3, 1, 2));
    }

    @Test
    void moviesPeopleTheUserFollowsWatchedRankHigher() {
        // Amélie (7.70) + 0.2 for each of at most 3 of the 5 watchers = 8.30: past Pulp Fiction (8.25).
        candidates("{\"friendWatchers\": [{\"movieId\": 9, \"watchers\": 5}], \"limit\": 3}").body("id", contains(1, 9, 2));
    }

    @Test
    void canBeLimitedToGivenMoviesStillFilteredAndRanked() {
        candidates("{\"onlyIds\": [7, 8, 4], \"genreIds\": [878]}").body("id", contains(4, 8, 7));
        candidates("{\"onlyIds\": [4, 1], \"genreIds\": [878]}").body("id", contains(4));
    }

    @Test
    void leavesOutMoviesWithFewVotesByDefault() {
        candidates("{}").body("id", not(hasItem(5)));
    }

    @Test
    void rejectsCandidateQueriesOverTheLimit() {
        given().contentType(ContentType.JSON).body("{\"limit\": 101}")
                .when().post("/movies/candidates")
                .then()
                .statusCode(400);
    }

    private static io.restassured.response.ValidatableResponse candidates(String json) {
        return given().contentType(ContentType.JSON).body(json)
                .when().post("/movies/candidates")
                .then()
                .statusCode(200);
    }

    @Test
    void returns404ForMissingMovie() {
        given().when().get("/movies/9999")
                .then()
                .statusCode(404);
    }

    @Test
    void listsMoviesPaginated() {
        given().queryParam("page", 1).queryParam("size", 2)
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items.id", contains(4, 3))
                .body("page", is(1))
                .body("size", is(2))
                .body("totalItems", is(9))
                .body("totalPages", is(5));
    }

    @Test
    void listsNewestFirstByDefaultWithUndatedLast() {
        given().when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(6, 7, 4, 3, 9, 2, 8, 1, 5));
    }

    @Test
    void sortsAscendingWhenOnlyTheSortFieldIsGiven() {
        given().queryParam("sort", "title")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items[0].title", is("Amélie"));
    }

    @Test
    void sortsByRatingDescendingWithTiesByIdAndUnratedLast() {
        given().queryParam("sort", "rating").queryParam("order", "desc")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(1, 2, 3, 4, 9, 6, 8, 7, 5));
    }

    @Test
    void sortsByRatingAscendingWithUnratedStillLast() {
        given().queryParam("sort", "rating")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(7, 8, 6, 9, 4, 2, 3, 1, 5));
    }

    @Test
    void rejectsUnknownSortField() {
        given().queryParam("sort", "budget")
                .when().get("/movies")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsInvalidPageSize() {
        given().queryParam("size", 0)
                .when().get("/movies")
                .then()
                .statusCode(400);
    }

    @Test
    void searchRanksTitleMatchesAboveDescriptionMatches() {
        search("space station")
                .body("items.id", contains(7, 8))
                .body("totalItems", is(2));
    }

    @Test
    void searchMatchesOtherFormsOfAWord() {
        search("dreams")
                .body("items.id", contains(4));
    }

    @Test
    void searchMatchesExactPhrases() {
        search("\"crime dynasty\"")
                .body("items.id", contains(1));
    }

    @Test
    void searchExcludesWordsPrefixedWithMinus() {
        search("space -planet")
                .body("items.id", contains(7));
    }

    @Test
    void searchFindsTitlesMadeOfStopWords() {
        search("It")
                .body("items.id", contains(6));
    }

    @Test
    void searchMatchesTheStartOfTitleWords() {
        search("incep")
                .body("items.id", contains(4));
    }

    @Test
    void searchRanksTitlesStartingWithTheQueryByPopularity() {
        search("sp")
                .body("items.id", contains(3, 7));
    }

    @Test
    void searchIgnoresAccents() {
        search("amelie").body("items.id", contains(9));
        search("AMÉLIE").body("items.id", contains(9));
    }

    @Test
    void searchAddsSimilarTitlesBelowOtherMatchesWhenThereAreFew() {
        // Solaris mentions Amelia in its description; Amélie only looks similar.
        search("amelia")
                .body("items.id", contains(8, 9));
    }

    @Test
    void searchToleratesTyposInTitles() {
        search("godfater")
                .body("items.id", contains(1));
    }

    @Test
    void searchCanOrderMatchesByAFieldInsteadOfRelevance() {
        given().queryParam("q", "space station").queryParam("sort", "releaseDate").queryParam("order", "asc")
                .when().get("/movies/search")
                .then()
                .statusCode(200)
                .body("items.id", contains(8, 7));
    }

    @Test
    void searchRejectsUnknownSortField() {
        given().queryParam("q", "space").queryParam("sort", "budget")
                .when().get("/movies/search")
                .then()
                .statusCode(400);
    }

    @Test
    void searchIsPaginated() {
        given().queryParam("q", "space").queryParam("page", 1).queryParam("size", 1)
                .when().get("/movies/search")
                .then()
                .statusCode(200)
                .body("items.id", contains(8))
                .body("page", is(1))
                .body("totalItems", is(2))
                .body("totalPages", is(2));
    }

    @Test
    void searchReturnsEmptyPageWhenNothingMatches() {
        search("xylophonequartz")
                .body("items", empty())
                .body("totalItems", is(0))
                .body("totalPages", is(0));
    }

    @Test
    void searchRejectsMissingBlankOrTooLongQueries() {
        given().when().get("/movies/search").then().statusCode(400);
        given().queryParam("q", "   ").when().get("/movies/search").then().statusCode(400);
        given().queryParam("q", "a".repeat(201)).when().get("/movies/search").then().statusCode(400);
    }

    private static io.restassured.response.ValidatableResponse search(String q) {
        return given().queryParam("q", q)
                .when().get("/movies/search")
                .then()
                .statusCode(200);
    }
}
