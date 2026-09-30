package psia.g17.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

/** The two endpoints of Ollama's HTTP API (https://github.com/ollama/ollama/blob/main/docs/api.md) that the worker uses. */
@RegisterRestClient(configKey = "ollama")
@Path("/api")
public interface OllamaClient {

    /** One chat completion, not streamed. */
    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    ChatResponse chat(ChatRequest request);

    /** The models Ollama has downloaded. */
    @GET
    @Path("/tags")
    Tags tags();

    /**
     * @param format a JSON schema the answer must follow; Ollama constrains the model's output to it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatRequest(String model, List<Message> messages, boolean stream, JsonNode format, Map<String, Object> options,
                       @JsonProperty("keep_alive") String keepAlive) {
    }

    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(Message message,
                        @JsonProperty("total_duration") Long totalDuration,
                        @JsonProperty("prompt_eval_count") Integer promptTokens,
                        @JsonProperty("eval_count") Integer outputTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tags(List<Model> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(String name) {
    }
}
