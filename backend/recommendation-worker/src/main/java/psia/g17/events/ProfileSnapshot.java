package psia.g17.events;

import java.util.List;

/**
 * What the user's profile says about their taste, by movies-service id; lists are most recent first.
 *
 * @param followedWatched what the people the user followed most recently watched: most watchers first
 */
public record ProfileSnapshot(String username, List<Integer> favoriteGenreIds, List<Integer> watchedMovieIds,
                              List<Integer> watchlistMovieIds, List<FriendWatch> followedWatched) {
}
