package psia.g17;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
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
                .body("totalItems", is(5))
                .body("totalPages", is(3));
    }

    @Test
    void sortsByRatingDescendingWithTiesByIdAndUnratedLast() {
        given().queryParam("sort", "rating").queryParam("order", "desc")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(1, 2, 3, 4, 5));
    }

    @Test
    void sortsByRatingAscendingWithUnratedStillLast() {
        given().queryParam("sort", "rating")
                .when().get("/movies")
                .then()
                .statusCode(200)
                .body("items.id", contains(4, 2, 3, 1, 5));
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
}
