package psia.g17.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import psia.g17.dto.Fingerprint;
import psia.g17.dto.FriendWatch;
import psia.g17.dto.ProfileSnapshot;
import psia.g17.entity.Profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds the snapshots recommendation-service reads, and their fingerprints. */
@ApplicationScoped
public class ProfileSnapshots {

    /**
     * The most watched and saved movies a snapshot holds, newest first. All of them, in practice: the
     * worker leaves every one out of the recommendations (and shows the model only the newest few).
     * movies-service accepts up to 5,000 ids to leave out.
     */
    static final int WATCHED_MOVIES = 2000;
    static final int WATCHLIST_MOVIES = 500;
    /** Whose movies count as friends' movies: the people the user followed most recently. */
    static final int FOLLOWED = 20;
    static final int FRIEND_MOVIES = 100;

    private final EntityManager em;

    public ProfileSnapshots(EntityManager em) {
        this.em = em;
    }

    public ProfileSnapshot of(Profile p) {
        List<Integer> genres = p.favoriteGenreIds.stream().sorted().toList();
        List<Integer> watched = recentMovies("WatchedMovie", "watchedAt", p.id, WATCHED_MOVIES);
        List<Integer> watchlist = recentMovies("WatchlistEntry", "addedAt", p.id, WATCHLIST_MOVIES);
        List<UUID> followed = em.createQuery(
                        "select f.id.followedId from Follow f where f.id.followerId = ?1 order by f.followedAt desc, f.id.followedId", UUID.class)
                .setParameter(1, p.id)
                .setMaxResults(FOLLOWED)
                .getResultList();
        List<FriendWatch> friends = followed.isEmpty() ? List.of() : em.createQuery("""
                        select w.id.movieId, count(w) from WatchedMovie w where w.id.profileId in ?1
                        group by w.id.movieId order by count(w) desc, max(w.watchedAt) desc, w.id.movieId""", Object[].class)
                .setParameter(1, followed)
                .setMaxResults(FRIEND_MOVIES)
                .getResultList().stream()
                .map(row -> new FriendWatch((Integer) row[0], ((Number) row[1]).intValue()))
                .toList();
        return new ProfileSnapshot(p.id, p.username, p.lastChangedAt, genres, watched, watchlist, followed, friends,
                fingerprint(genres, watched, watchlist, friends));
    }

    /**
     * SHA-256 of a canonical text form of the inputs. Genres are a set (sorted); watched and watchlist
     * keep their recency order, which the recommendations depend on; friends' movies are compared as a
     * set of (movie, watchers). The username isn't included: it doesn't change the recommendations.
     */
    static Fingerprint fingerprint(List<Integer> genres, List<Integer> watched, List<Integer> watchlist, List<FriendWatch> friends) {
        String own = sha256("g:" + join(genres) + "|w:" + join(watched) + "|l:" + join(watchlist));
        String social = sha256("f:" + friends.stream()
                .sorted((a, b) -> Integer.compare(a.movieId(), b.movieId()))
                .map(f -> f.movieId() + "*" + f.watchers())
                .collect(Collectors.joining(",")));
        return new Fingerprint(sha256(own + ":" + social), own, social);
    }

    private List<Integer> recentMovies(String entity, String dateField, UUID profileId, int limit) {
        return em.createQuery("select e.id.movieId from " + entity + " e where e.id.profileId = ?1 order by e." + dateField
                        + " desc, e.id.movieId", Integer.class)
                .setParameter(1, profileId)
                .setMaxResults(limit)
                .getResultList();
    }

    private static String join(List<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
