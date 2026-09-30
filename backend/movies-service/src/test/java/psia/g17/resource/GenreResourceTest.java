package psia.g17.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestSecurity(user = "alice")
class GenreResourceTest {

    @Test
    void listsAllGenresByName() {
        given().when().get("/genres")
                .then()
                .statusCode(200)
                .body("name", contains("Animation", "Comedy", "Crime", "Horror", "Science Fiction", "Thriller"))
                .body("[0].id", is(16));
    }
}
