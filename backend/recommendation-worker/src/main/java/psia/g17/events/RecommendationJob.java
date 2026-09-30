package psia.g17.events;

import java.time.Instant;
import java.util.UUID;

/** On topic recommendation.requests, keyed by user id. */
public record RecommendationJob(UUID requestId, UUID userId, Kind kind, String prompt, ProfileSnapshot profile,
                                Instant requestedAt) {
}
