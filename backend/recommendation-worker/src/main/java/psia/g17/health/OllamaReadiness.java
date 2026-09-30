package psia.g17.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.OllamaClient;

/** Ready only when Ollama answers and has the configured model downloaded. Kafka has its own check. */
@Readiness
@ApplicationScoped
public class OllamaReadiness implements HealthCheck {

    private final OllamaClient ollama;
    private final String model;

    public OllamaReadiness(@RestClient OllamaClient ollama, @ConfigProperty(name = "recommendation.model") String model) {
        this.ollama = ollama;
        this.model = model;
    }

    @Override
    public HealthCheckResponse call() {
        var check = HealthCheckResponse.named("ollama").withData("model", model);
        try {
            boolean downloaded = ollama.tags().models().stream()
                    .anyMatch(m -> m.name().equals(model) || m.name().equals(model + ":latest"));
            return downloaded ? check.up().build()
                    : check.down().withData("reason", "the model isn't downloaded: run 'ollama pull " + model + "'").build();
        } catch (RuntimeException e) {
            return check.down().withData("reason", "Ollama isn't reachable: " + e.getMessage()).build();
        }
    }
}
