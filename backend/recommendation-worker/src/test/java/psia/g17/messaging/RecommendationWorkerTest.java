package psia.g17.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.Record;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import psia.g17.InMemoryKafka;
import psia.g17.events.Kind;
import psia.g17.events.ProfileSnapshot;
import psia.g17.events.RecommendationJob;
import psia.g17.events.RecommendationResult;
import psia.g17.events.Status;
import psia.g17.service.RecommendationPipeline;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@WithTestResource(InMemoryKafka.class)
class RecommendationWorkerTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @InjectMock
    RecommendationPipeline pipeline;

    @Inject
    MeterRegistry metrics;

    private InMemorySink<Record<String, RecommendationResult>> results;

    @BeforeEach
    void clearResults() {
        results = connector.sink("recommendation-results");
        results.clear();
        when(pipeline.model()).thenReturn("qwen2.5:3b");
        when(pipeline.recipe()).thenReturn("qwen2.5:3b/p2");
    }

    @Test
    void publishesTheRecommendationsForAJobKeyedByUser() {
        when(pipeline.recommend(any())).thenReturn(List.of(238, 155));
        RecommendationJob job = job();

        connector.source("recommendation-requests").send(job);

        RecommendationResult result = awaitResult(job);
        assertEquals(Status.DONE, result.status());
        assertEquals(List.of(238, 155), result.movieIds());
        assertEquals(job.requestId(), result.requestId());
        assertEquals("qwen2.5:3b", result.model());
        assertEquals("qwen2.5:3b/p2", result.recipe(), "results say which model and pipeline version made them");
    }

    @Test
    void publishesAFailureInsteadOfDroppingTheJob() {
        when(pipeline.recommend(any())).thenThrow(new IllegalStateException("No candidate movies matched"));
        RecommendationJob job = job();

        connector.source("recommendation-requests").send(job);

        RecommendationResult result = awaitResult(job);
        assertEquals(Status.FAILED, result.status());
        assertEquals("No candidate movies matched", result.error());
        assertEquals(List.of(), result.movieIds());
    }

    @Test
    void timesEachJobAndHowLongItWaited() {
        when(pipeline.recommend(any())).thenReturn(List.of(238));
        RecommendationJob job = new RecommendationJob(UUID.randomUUID(), UUID.randomUUID(), Kind.PROMPT, "space",
                new ProfileSnapshot("carol", List.of(), List.of(), List.of(), List.of()), Instant.now().minusSeconds(90));

        connector.source("recommendation-requests").send(job);
        awaitResult(job);

        Timer jobs = metrics.find("recommendation.jobs").tags("kind", "prompt", "status", "done").timer();
        assertNotNull(jobs, "recommendation_jobs_seconds{kind=prompt,status=done}");
        Timer waited = metrics.find("recommendation.jobs.waited").tags("kind", "prompt").timer();
        assertNotNull(waited, "recommendation_jobs_waited_seconds{kind=prompt}");
        assertTrue(waited.max(java.util.concurrent.TimeUnit.SECONDS) >= 90, "it was requested 90 s before the worker took it");
    }

    @Test
    void explainsFailuresInPlainWords() {
        assertEquals("A service the recommendations need isn't reachable. Try again later.",
                RecommendationWorker.describe(new ProcessingException(new ConnectException("Connection refused (connect failed)"))));
        assertEquals("The language model took too long to answer.",
                RecommendationWorker.describe(new ProcessingException(new TimeoutException())));
        assertEquals("No candidate movies matched", RecommendationWorker.describe(new IllegalStateException("No candidate movies matched")));
    }

    private RecommendationResult awaitResult(RecommendationJob job) {
        await().atMost(Duration.ofSeconds(10)).until(() -> results.received().stream()
                .anyMatch(m -> m.getPayload().value().requestId().equals(job.requestId())));
        Record<String, RecommendationResult> record = results.received().stream()
                .map(m -> m.getPayload())
                .filter(r -> r.value().requestId().equals(job.requestId()))
                .findFirst().orElseThrow();
        assertEquals(job.userId().toString(), record.key());
        return record.value();
    }

    private static RecommendationJob job() {
        return new RecommendationJob(UUID.randomUUID(), UUID.randomUUID(), Kind.PROFILE, null,
                new ProfileSnapshot("carol", List.of(), List.of(), List.of(), List.of()), Instant.now());
    }
}
