package psia.g17.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;

@QuarkusTest
class MovieResourceAuthTest {

    @ParameterizedTest
    @ValueSource(strings = {"/movies", "/movies/1", "/movies/search?q=space", "/genres"})
    void rejectsRequestsWithoutAToken(String path) {
        given().when().get(path)
                .then()
                .statusCode(401);
    }

    @Test
    void keepsHealthCheckPublic() {
        given().when().get("/q/health")
                .then()
                .statusCode(200);
    }
}
