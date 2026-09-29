package psia.g17;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The JSON shapes the profiles API returns. */
public final class Views {

    private Views() {
    }

    public record GenreView(int id, String name) {
    }

    public record ProfileSummary(UUID id, String username) {
    }

    /**
     * @param me           whether this is the logged-in user's own profile
     * @param followedByMe whether the logged-in user follows this profile
     */
    public record ProfileView(UUID id, String username, Instant createdAt, List<GenreView> favoriteGenres,
                              long watchedCount, long followingCount, long followersCount,
                              boolean me, boolean followedByMe) {
    }

    public record WatchedMovieView(int movieId, String title, String posterPath, LocalDate releaseDate,
                                   Instant watchedAt) {
    }

    public record ErrorView(String error) {
    }
}
