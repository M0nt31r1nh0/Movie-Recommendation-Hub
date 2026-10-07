package psia.g17.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The requests waiting for an answer, for Prometheus: {@code recommendation_pending}, by {@code kind}.
 * Counted every {@code recommendation.metrics.every} rather than on each scrape, so Prometheus never waits
 * on the database; unknown (NaN) until the first count.
 */
// Created at startup, so its metrics are published from the first scrape (beans are otherwise created on first use).
@Startup
@ApplicationScoped
public class PendingRequests {

    private final Map<Kind, Double> pending = new ConcurrentHashMap<>();

    public PendingRequests(MeterRegistry metrics) {
        for (Kind kind : Kind.values()) {
            pending.put(kind, Double.NaN);
            Gauge.builder("recommendation.pending", pending, p -> p.get(kind))
                    .description("Recommendation requests waiting for an answer, by kind")
                    .tag("kind", kind.name().toLowerCase())
                    .register(metrics);
        }
    }

    @Scheduled(every = "${recommendation.metrics.every:30s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void count() {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (Kind kind : Kind.values()) {
                    pending.put(kind, (double) RecommendationRequest.count("kind = ?1 and status = ?2", kind, RecommendationRequest.Status.PENDING));
                }
            });
        } catch (RuntimeException e) {
            Log.warnf("Couldn't count the pending recommendation requests: %s", e.getMessage());
        }
    }
}
