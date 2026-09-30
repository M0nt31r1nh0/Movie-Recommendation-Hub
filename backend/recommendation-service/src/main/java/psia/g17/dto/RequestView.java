package psia.g17.dto;

import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One request; {@code movies} is empty until it's DONE. */
public record RequestView(UUID id, Kind kind, String prompt, RecommendationRequest.Status status, String error,
                          Instant createdAt, Instant completedAt, String model, List<MovieView> movies) {
}
