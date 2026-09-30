package psia.g17.messaging;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import psia.g17.events.RecommendationResult;
import psia.g17.service.RecommendationStore;

/**
 * Stores the worker's results. All copies of this service share one consumer group here, so each
 * result is stored once; recommendation.ready then tells every copy (see ReadyListener).
 */
@ApplicationScoped
public class ResultConsumer {

    private final RecommendationStore store;

    public ResultConsumer(RecommendationStore store) {
        this.store = store;
    }

    @Incoming("recommendation-results")
    @Blocking
    public void store(RecommendationResult result) {
        store.complete(result);
    }
}
