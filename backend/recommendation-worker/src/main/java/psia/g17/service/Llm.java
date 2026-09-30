package psia.g17.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.OllamaClient;

import java.util.List;
import java.util.Map;

/** The language model, served by Ollama: a system and user message in, JSON following a schema out. */
@ApplicationScoped
public class Llm {

    private final OllamaClient ollama;
    private final ObjectMapper json;
    private final String model;
    private final double temperature;
    private final int contextTokens;
    private final String keepAlive;

    public Llm(@RestClient OllamaClient ollama, ObjectMapper json,
               @ConfigProperty(name = "recommendation.model") String model,
               @ConfigProperty(name = "recommendation.temperature", defaultValue = "0.4") double temperature,
               @ConfigProperty(name = "recommendation.context-tokens", defaultValue = "8192") int contextTokens,
               @ConfigProperty(name = "recommendation.keep-alive", defaultValue = "10m") String keepAlive) {
        this.ollama = ollama;
        this.json = json;
        this.model = model;
        this.temperature = temperature;
        this.contextTokens = contextTokens;
        this.keepAlive = keepAlive;
    }

    public String model() {
        return model;
    }

    /** Asks the model, and parses its answer, which Ollama forces to follow {@code schema}, as {@code type}. */
    public <T> T ask(String system, String user, JsonNode schema, Class<T> type) {
        long start = System.nanoTime();
        OllamaClient.ChatResponse response = ollama.chat(new OllamaClient.ChatRequest(model,
                List.of(new OllamaClient.Message("system", system), new OllamaClient.Message("user", user)),
                false, schema, Map.of("temperature", temperature, "num_ctx", contextTokens), keepAlive));
        Log.infof("%s answered in %d ms (%s prompt tokens, %s output tokens)", model, (System.nanoTime() - start) / 1_000_000,
                response.promptTokens(), response.outputTokens());
        try {
            return json.readValue(response.message().content(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The model's answer wasn't the JSON asked for: " + e.getOriginalMessage(), e);
        }
    }
}
