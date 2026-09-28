package psia.g17;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
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
                .body("genres.name", contains("Crime", "Thriller"));
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
                .body("items[0].id", is(3))
                .body("page", is(1))
                .body("size", is(2))
                .body("totalItems", is(8))
                .body("totalPages", is(4));
    }

    @Test
    void sortsByRatingDescendingWithTiesByIdAndUnratedLast() {
        given().queryParam("sort", "rating").queryParam("order", "desc")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(1, 2, 3, 4, 6, 8, 7, 5));
    }

    @Test
    void sortsByRatingAscendingWithUnratedStillLast() {
        given().queryParam("sort", "rating")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(7, 8, 6, 4, 2, 3, 1, 5));
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
    void searchToleratesTyposInTitles() {
        search("godfater")
                .body("items.id", contains(1));
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
