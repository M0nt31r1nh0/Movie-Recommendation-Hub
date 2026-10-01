package psia.g17.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/** Ollama's embedding endpoint (see https://github.com/ollama/ollama/blob/main/docs/api.md), for search by meaning. */
@RegisterRestClient(configKey = "ollama")
@Path("/api")
public interface OllamaClient {

    @POST
    @Path("/embed")
    EmbedResponse embed(EmbedRequest request);

    /** @param keepAlive how long Ollama keeps the model loaded afterwards, e.g. "10m" */
    record EmbedRequest(String model, List<String> input, @JsonProperty("keep_alive") String keepAlive) {
    }

    /** One vector per input, in the same order. */
    record EmbedResponse(List<List<Float>> embeddings) {
    }
}
