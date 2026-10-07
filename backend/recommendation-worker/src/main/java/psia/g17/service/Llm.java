package psia.g17.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.OllamaClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The language model, served by Ollama: a system and user message in, JSON following a schema out.
 *
 * <p>Ollama has no metrics endpoint of its own, so the time and tokens of each answer are measured here and
 * published at /q/metrics: {@code recommendation_llm_requests_seconds} (by model and outcome) and
 * {@code recommendation_llm_tokens_total} (by model and type, prompt or output).
 */
@ApplicationScoped
public class Llm {

    private final OllamaClient ollama;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final String model;
    private final double temperature;
    private final int contextTokens;
    private final String keepAlive;

    public Llm(@RestClient OllamaClient ollama, ObjectMapper json, MeterRegistry metrics,
               @ConfigProperty(name = "recommendation.model") String model,
               @ConfigProperty(name = "recommendation.temperature", defaultValue = "0.4") double temperature,
               @ConfigProperty(name = "recommendation.context-tokens", defaultValue = "8192") int contextTokens,
               @ConfigProperty(name = "recommendation.keep-alive", defaultValue = "10m") String keepAlive) {
        this.ollama = ollama;
        this.json = json;
        this.metrics = metrics;
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
        String outcome = "error";
        OllamaClient.ChatResponse response;
        try {
            response = ollama.chat(new OllamaClient.ChatRequest(model,
                    List.of(new OllamaClient.Message("system", system), new OllamaClient.Message("user", user)),
                    false, schema, Map.of("temperature", temperature, "num_ctx", contextTokens), keepAlive));
            outcome = "success";
        } finally {
            answerTime(outcome).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
        Log.infof("%s answered in %d ms (%s prompt tokens, %s output tokens)", model, (System.nanoTime() - start) / 1_000_000,
                response.promptTokens(), response.outputTokens());
        if (response.promptTokens() != null) tokens("prompt").increment(response.promptTokens());
        if (response.outputTokens() != null) tokens("output").increment(response.outputTokens());
        try {
            return json.readValue(response.message().content(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The model's answer wasn't the JSON asked for: " + e.getOriginalMessage(), e);
        }
    }

    private Timer answerTime(String outcome) {
        return Timer.builder("recommendation.llm.requests")
                .description("How long Ollama took to answer")
                .tag("model", model)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                // Answers take minutes on a CPU: without this, the histogram stops at Micrometer's default 30 s.
                .maximumExpectedValue(Duration.ofMinutes(30))
                .register(metrics);
    }

    private Counter tokens(String kind) {
        return Counter.builder("recommendation.llm.tokens")
                .description("Tokens Ollama read (prompt) and wrote (output)")
                .tag("model", model)
                .tag("type", kind)
                .register(metrics);
    }
}
