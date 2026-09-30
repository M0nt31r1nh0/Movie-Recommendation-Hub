package psia.g17.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * On topic recommendation.results, keyed by user id.
 *
 * @param movieIds the recommended movies, best first; empty if it FAILED
 * @param recipe   what made the recommendations: the model and the worker's pipeline version
 */
public record RecommendationResult(UUID requestId, UUID userId, Kind kind, Status status, List<Integer> movieIds,
                                   String error, String model, String recipe, long durationMs, Instant completedAt) {
}
