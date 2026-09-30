package psia.g17.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything recommendation-service needs about a profile, by movies-service id, with its
 * fingerprint. Movie lists are most recent first and capped (see ProfileSnapshots).
 *
 * @param followedIds     the people the user followed most recently (at most 20)
 * @param followedWatched what those people watched: most watchers first, then most recent
 */
public record ProfileSnapshot(UUID id, String username, Instant lastChangedAt, List<Integer> favoriteGenreIds,
                              List<Integer> watchedMovieIds, List<Integer> watchlistMovieIds, List<UUID> followedIds,
                              List<FriendWatch> followedWatched, Fingerprint fingerprint) {
}
