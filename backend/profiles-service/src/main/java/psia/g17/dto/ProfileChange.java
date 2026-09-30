package psia.g17.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A profile's current fingerprint, for recommendation-service's background agent to compare with the
 * one its current recommendations were made from, and when the user last changed their own inputs.
 */
public record ProfileChange(UUID id, String username, Instant lastChangedAt, Fingerprint fingerprint) {
}
