package psia.g17.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param watchlistCount how many movies are on the watchlist; only on your own profile (null otherwise),
 *                       since only its owner sees a watchlist
 * @param me             whether this is the logged-in user's own profile
 * @param followedByMe   whether the logged-in user follows this profile
 */
public record ProfileView(UUID id, String username, Instant createdAt, List<GenreView> favoriteGenres,
                          long watchedCount, Long watchlistCount, long followingCount, long followersCount,
                          boolean me, boolean followedByMe) {
}
