package psia.g17.messaging;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import psia.g17.events.RecommendationJob;
import psia.g17.events.RecommendationResult;
import psia.g17.events.Status;
import psia.g17.service.RecommendationPipeline;

import java.time.Instant;
import java.util.List;

/**
 * Takes jobs from recommendation.requests, one at a time, and publishes each outcome, success or
 * failure, to recommendation.results, keyed by user id. Kafka only records a job as done once its
 * result is sent, so a job a crashed worker was on goes to another worker.
 */
@ApplicationScoped
public class RecommendationWorker {

    private final RecommendationPipeline pipeline;
    

    public RecommendationWorker(RecommendationPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Incoming("recommendation-requests")
    @Outgoing("recommendation-results")
    @Blocking // on a worker thread, in order: one job at a time per worker
    public Record<String, RecommendationResult> process(RecommendationJob job) {
        long start = System.nanoTime();
        Log.infof("Recommending for request %s (%s)", job.requestId(), job.kind());
        RecommendationResult result;
        try {
            result = new RecommendationResult(job.requestId(), job.userId(), job.kind(), Status.DONE,
                    pipeline.recommend(job), null, pipeline.model(), pipeline.recipe(), millisSince(start), Instant.now());
        } catch (RuntimeException e) {
            Log.warnf(e, "Request %s failed", job.requestId());
            result = new RecommendationResult(job.requestId(), job.userId(), job.kind(), Status.FAILED, List.of(),
                    describe(e), pipeline.model(), pipeline.recipe(), millisSince(start), Instant.now());
        }
        Log.infof("Request %s: %s in %d ms", job.requestId(), result.status(), result.durationMs());
        return Record.of(job.userId().toString(), result);
    }

    /** A short explanation for the user, without a stack trace. */
    static String describe(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        if (root instanceof java.util.concurrent.TimeoutException || message.toLowerCase().contains("timed out")) {
            return "The language model took too long to answer.";
        }
        // Ollama or movies-service is down; the log has which.
        if (root instanceof java.net.ConnectException || root instanceof java.net.UnknownHostException) {
            return "A service the recommendations need isn't reachable. Try again later.";
        }
        return message.length() <= 300 ? message : message.substring(0, 299) + "…";
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
