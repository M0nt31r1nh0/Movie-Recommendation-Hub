package psia.g17.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import psia.g17.client.OllamaClient;
import psia.g17.client.OllamaClient.EmbedRequest;
import psia.g17.client.OllamaClient.EmbedResponse;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestSecurity(user = "alice")
class MoviesMetricsTest {

    @InjectMock
    @RestClient
    OllamaClient ollama;

    @Inject
    MovieEmbeddings embeddings;

    @Test
    void timesSearchesByWhetherTheyFoundAnything() {
        given().queryParam("q", "godfather").when().get("/movies/search").then().statusCode(200);
        given().queryParam("q", "xqzvwk").when().get("/movies/search").then().statusCode(200);

        String metrics = metrics();
        assertThat(metrics, containsString("movies_searches_seconds_count{results=\"some\","));
        assertThat(metrics, containsString("movies_searches_seconds_count{results=\"none\","));
    }

    @Test
    void timesCandidateQueriesByRanking() {
        given().contentType(ContentType.JSON).body("{\"limit\": 2}").when().post("/movies/candidates").then().statusCode(200);

        assertThat(metrics(), containsString("movies_candidates_seconds_count{ranking=\"taste\"}"));
    }

    @Test
    void timesEmbeddingCallsAndCountsKnownVectors() {
        when(ollama.embed(any())).thenAnswer(call -> new EmbedResponse(call.<EmbedRequest>getArgument(0).input().stream()
                .map(text -> List.of(1f, 0f, 0f)).toList()));

        embeddings.query("a request only this test makes");
        embeddings.query("a request only this test makes");

        String metrics = metrics();
        assertThat(metrics, containsString("movies_embeddings_requests_seconds_count{outcome=\"success\",purpose=\"query\"}"));
        assertThat(metrics, containsString("movies_embeddings_query_cache_total{result=\"hit\"}"));
        assertThat(metrics, containsString("movies_embeddings_query_cache_total{result=\"miss\"}"));
        assertThat(metrics, containsString("movies_embeddings_missing "));
        assertThat(metrics, containsString("movies_embeddings_backfilled_total "));
    }

    private static String metrics() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }
}
