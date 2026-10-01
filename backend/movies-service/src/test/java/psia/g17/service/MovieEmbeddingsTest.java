package psia.g17.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import psia.g17.client.OllamaClient;
import psia.g17.client.OllamaClient.EmbedRequest;
import psia.g17.client.OllamaClient.EmbedResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Vectors here have 3 numbers instead of 768, which pgvector doesn't mind.
@QuarkusTest
@TestSecurity(user = "alice")
class MovieEmbeddingsTest {

    @InjectMock
    @RestClient
    OllamaClient ollama;

    @Inject
    MovieEmbeddings embeddings;

    @Inject
    EntityManager em;

    @BeforeEach
    void noEmbeddingsYet() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("DELETE FROM movies.movie_embedding").executeUpdate());
    }

    @Test
    void embedsTheMostVotedCandidatesFirstUntilAllAreDone() {
        when(ollama.embed(any())).thenAnswer(call -> new EmbedResponse(call.<EmbedRequest>getArgument(0).input().stream()
                .map(text -> List.of(1f, 0f, 0f)).toList()));

        assertEquals(3, embeddings.embedMissing(3));

        ArgumentCaptor<EmbedRequest> request = ArgumentCaptor.forClass(EmbedRequest.class);
        verify(ollama).embed(request.capture());
        assertEquals("nomic-embed-text", request.getValue().model());
        assertEquals("search_document: Inception (2010). Science Fiction. A thief who steals corporate secrets through dream-sharing technology.",
                request.getValue().input().get(0), "the most voted first, with its year, genres and description");
        assertEquals(5, embeddings.missing(), "the 8 sample movies with at least 50 votes, less those 3");
        assertEquals(5, embeddings.embedMissing(100));
        assertEquals(0, embeddings.embedMissing(100), "nothing left to do");
        assertEquals(0L, count("SELECT count(*) FROM movies.movie_embedding WHERE movie_id = 5"), "no votes, never a candidate");
    }

    @Test
    void ranksCandidatesByMeaningOverRating() {
        store(Map.of(7, "[0,1,0]", 8, "[0.2,0.8,0]", 1, "[0,0,1]", 4, "[1,0,0]"));
        answer("a crew somewhere up there", "[0,1,0]");

        // Space Station 76 (5.9/10) and Solaris (7.0) are about that; The Godfather (8.5) and Inception (8.1)
        // aren't. Space Station 76 is a little closer, which outweighs Solaris's better rating.
        candidates("{\"meaning\": \"a crew somewhere up there\", \"limit\": 2}").body("id", is(List.of(7, 8)));
    }

    @Test
    void wordsOnlyAddToTheScoreWhenThereIsAMeaning() {
        store(Map.of(1, "[0,0,1]", 2, "[0,0,1]"));
        answer("mob stories", "[0,0,1]");

        candidates("{\"meaning\": \"mob stories\", \"text\": \"space\", \"limit\": 3}").body("id", hasItem(1));
    }

    @Test
    void fallsBackToMatchingWordsWhenTheModelIsDown() {
        when(ollama.embed(any())).thenThrow(new ProcessingException("Connection refused"));

        candidates("{\"meaning\": \"somewhere in orbit\", \"text\": \"space\"}").body("id", containsInAnyOrder(7, 8));
        verify(ollama, atLeastOnce()).embed(any());
    }

    @Test
    void remembersRecentSearches() {
        answer("a heist in Paris", "[1,0,0]");

        embeddings.query("a heist in Paris");
        embeddings.query("  A heist in paris ");

        verify(ollama).embed(any());
        assertTrue(embeddings.query("a heist in Paris").orElseThrow().startsWith("[1.0,0.0,0.0"));
    }

    private void answer(String search, String vector) {
        List<Float> numbers = Arrays.stream(vector.substring(1, vector.length() - 1).split(",")).map(Float::parseFloat).toList();
        when(ollama.embed(any())).thenAnswer(call -> {
            EmbedRequest request = call.getArgument(0);
            assertEquals(List.of("search_query: " + search), request.input(), "a search is marked as one");
            return new EmbedResponse(List.of(numbers));
        });
    }

    private void store(Map<Integer, String> vectors) {
        QuarkusTransaction.requiringNew().run(() -> vectors.forEach((id, vector) -> em.createNativeQuery(
                        "INSERT INTO movies.movie_embedding (movie_id, model, embedding) VALUES (:id, 'nomic-embed-text', CAST(:v AS vector))")
                .setParameter("id", id).setParameter("v", vector).executeUpdate()));
    }

    private long count(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private static ValidatableResponse candidates(String json) {
        return given().contentType(ContentType.JSON).body(json)
                .when().post("/movies/candidates")
                .then()
                .statusCode(200);
    }
}
