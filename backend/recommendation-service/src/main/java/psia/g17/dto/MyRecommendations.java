package psia.g17.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The user's profile recommendations.
 *
 * @param status     READY (movies are there), PREPARING (the first set is being made) or FAILED
 * @param refreshing whether a newer set is being made while these are shown
 */
public record MyRecommendations(String status, UUID requestId, Instant generatedAt, boolean refreshing, String error,
                                List<MovieView> movies) {
}
