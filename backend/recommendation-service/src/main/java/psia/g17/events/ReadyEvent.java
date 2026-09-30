package psia.g17.events;

import java.util.UUID;

/**
 * On topic recommendation.ready, once a request finishes: every copy of this service reads it and
 * tells the user's open SSE connections, wherever they are.
 *
 * @param error why it failed, for status FAILED
 */
public record ReadyEvent(UUID requestId, UUID userId, Kind kind, Status status, String error) {
}
