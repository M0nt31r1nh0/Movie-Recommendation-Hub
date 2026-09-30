package psia.g17.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import psia.g17.entity.RecommendationRequest;
import psia.g17.service.RecommendationStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gives up on requests still PENDING after {@code timeout}, a safety net for a job lost from Kafka, so
 * users aren't blocked forever (they can have one typed request at a time); a slow worker only makes
 * them wait. Also deletes failed typed requests once they're {@code failed.keep-for} old: by then a
 * returning user has had the chance to be told.
 */
@ApplicationScoped
public class PendingSweeper {

    private final RecommendationStore store;
    private final Duration timeout;
    private final Duration keepFailed;

    public PendingSweeper(RecommendationStore store,
                          @ConfigProperty(name = "recommendation.timeout", defaultValue = "PT1H") Duration timeout,
                          @ConfigProperty(name = "recommendation.failed.keep-for", defaultValue = "PT1H") Duration keepFailed) {
        this.store = store;
        this.timeout = timeout;
        this.keepFailed = keepFailed;
    }

    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        Instant now = Instant.now();
        List<UUID> stuck = QuarkusTransaction.requiringNew().call(() ->
                RecommendationRequest.<RecommendationRequest>find("status = ?1 and createdAt < ?2",
                                RecommendationRequest.Status.PENDING, now.minus(timeout))
                        .stream().map(r -> r.id).toList());
        stuck.forEach(id -> store.fail(id, "No answer after " + describe(timeout) + "."));
        QuarkusTransaction.requiringNew().run(() -> RecommendationRequest.delete("status = ?1 and completedAt < ?2",
                RecommendationRequest.Status.FAILED, now.minus(keepFailed)));
    }

    /** "1 hour", "90 minutes", ... */
    static String describe(Duration d) {
        long minutes = d.toMinutes();
        if (minutes % 60 == 0) return minutes / 60 + (minutes == 60 ? " hour" : " hours");
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
