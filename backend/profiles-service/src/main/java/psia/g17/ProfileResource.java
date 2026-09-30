package psia.g17;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import psia.g17.Views.ErrorView;
import psia.g17.Views.GenreView;
import psia.g17.Views.ProfileSummary;
import psia.g17.Views.ProfileView;
import psia.g17.Views.WatchedMovieView;
import psia.g17.Views.WatchlistEntryView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * User profiles: favourite genres, watched movies, a watch-later list and follows. Every endpoint needs a
 * Keycloak access token. Anyone logged in can read any profile, except its watchlist, which only its
 * owner sees; changes go through {@code /profiles/me/...}, so users only ever change their own. Wherever
 * a path takes a profile id, {@code me} means the logged-in user.
 */
@Path("/profiles")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ProfileResource {

    private final CurrentUser currentUser;
    private final EntityManager em;

    public ProfileResource(CurrentUser currentUser, EntityManager em) {
        this.currentUser = currentUser;
        this.em = em;
    }

    /** Profiles whose username starts with {@code q} (any case), or all of them, by username. */
    @GET
    public PageResponse<ProfileSummary> search(
            @QueryParam("q") @Size(max = 100) String q,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        currentUser.profileId(); // so the caller is listed too
        String prefix = q == null ? "" : q.strip().toLowerCase();
        PanacheQuery<Profile> query = prefix.isEmpty()
                ? Profile.findAll(Sort.by("username"))
                : Profile.find("lower(username) like ?1 escape '\\'", Sort.by("username"), escapeLike(prefix) + "%");
        return page(query, page, size, p -> new ProfileSummary(p.id, p.username));
    }

    @GET
    @Path("/{id}")
    public ProfileView get(@PathParam("id") String id) {
        return view(findProfile(id));
    }

    /** Replaces the logged-in user's favourite genres with the given genre ids. */
    @PUT
    @Path("/me/favorite-genres")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public ProfileView setFavoriteGenres(@NotNull @Size(max = 50) List<@NotNull Integer> genreIds) {
        Profile me = Profile.findById(currentUser.profileId());
        Set<Integer> wanted = new LinkedHashSet<>(genreIds);
        List<Genre> genres = wanted.isEmpty() ? List.of() : Genre.list("id in ?1", wanted);
        if (genres.size() != wanted.size()) {
            genres.forEach(g -> wanted.remove(g.id));
            throw error(Response.Status.BAD_REQUEST, "Unknown genre ids: " + wanted);
        }
        me.favoriteGenres.clear();
        me.favoriteGenres.addAll(genres);
        return view(me);
    }

    /** The movies a profile has seen, most recently marked first. */
    @GET
    @Path("/{id}/watched")
    public PageResponse<WatchedMovieView> watched(
            @PathParam("id") String id,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        UUID profileId = findProfile(id).id;
        PanacheQuery<WatchedMovie> query = WatchedMovie.find("id.profileId", byNewest("watchedAt", "id.movieId"), profileId);
        return page(query, page, size, w -> new WatchedMovieView(
                w.movie.id, w.movie.title, w.movie.posterPath, w.movie.releaseDate, w.watchedAt));
    }

    /** Whether the logged-in user has seen a movie: 200 with the details if so, 404 if not. */
    @GET
    @Path("/me/watched/{movieId}")
    public WatchedMovieView watchedMovie(@PathParam("movieId") int movieId) {
        WatchedMovie w = WatchedMovie.findById(new WatchedMovie.Key(currentUser.profileId(), movieId));
        if (w == null) throw error(Response.Status.NOT_FOUND, "Not marked as watched");
        return new WatchedMovieView(w.movie.id, w.movie.title, w.movie.posterPath, w.movie.releaseDate, w.watchedAt);
    }

    /**
     * Marks a movie as seen, and takes it off the watchlist. Idempotent: marking it again keeps the
     * original date.
     */
    @PUT
    @Path("/me/watched/{movieId}")
    @Transactional
    public void markWatched(@PathParam("movieId") int movieId) {
        UUID me = currentUser.profileId();
        addMovie("watched_movie", me, movieId);
        WatchlistEntry.delete("id.profileId = ?1 and id.movieId = ?2", me, movieId);
    }

    @DELETE
    @Path("/me/watched/{movieId}")
    @Transactional
    public void unmarkWatched(@PathParam("movieId") int movieId) {
        WatchedMovie.delete("id.profileId = ?1 and id.movieId = ?2", currentUser.profileId(), movieId);
    }

    /** The logged-in user's watch-later list, most recently added first. Nobody else can see it. */
    @GET
    @Path("/me/watchlist")
    public PageResponse<WatchlistEntryView> watchlist(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        PanacheQuery<WatchlistEntry> query = WatchlistEntry.find("id.profileId", byNewest("addedAt", "id.movieId"), currentUser.profileId());
        return page(query, page, size, this::toView);
    }

    /** Whether a movie is on the logged-in user's watchlist: 200 with the details if so, 404 if not. */
    @GET
    @Path("/me/watchlist/{movieId}")
    public WatchlistEntryView watchlistEntry(@PathParam("movieId") int movieId) {
        WatchlistEntry w = WatchlistEntry.findById(new WatchlistEntry.Key(currentUser.profileId(), movieId));
        if (w == null) throw error(Response.Status.NOT_FOUND, "Not on the watchlist");
        return toView(w);
    }

    /** Adds a movie to the logged-in user's watchlist. Idempotent: adding it again keeps the original date. */
    @PUT
    @Path("/me/watchlist/{movieId}")
    @Transactional
    public void addToWatchlist(@PathParam("movieId") int movieId) {
        addMovie("watchlist", currentUser.profileId(), movieId);
    }

    @DELETE
    @Path("/me/watchlist/{movieId}")
    @Transactional
    public void removeFromWatchlist(@PathParam("movieId") int movieId) {
        WatchlistEntry.delete("id.profileId = ?1 and id.movieId = ?2", currentUser.profileId(), movieId);
    }

    /** The profiles a profile follows, most recently followed first. */
    @GET
    @Path("/{id}/following")
    public PageResponse<ProfileSummary> following(
            @PathParam("id") String id,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        PanacheQuery<Follow> query = Follow.find("id.followerId", byNewest("followedAt", "id.followedId"), findProfile(id).id);
        return page(query, page, size, f -> new ProfileSummary(f.followed.id, f.followed.username));
    }

    /** The profiles following a profile, most recent first. */
    @GET
    @Path("/{id}/followers")
    public PageResponse<ProfileSummary> followers(
            @PathParam("id") String id,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        PanacheQuery<Follow> query = Follow.find("id.followedId", byNewest("followedAt", "id.followerId"), findProfile(id).id);
        return page(query, page, size, f -> new ProfileSummary(f.follower.id, f.follower.username));
    }

    /** Follows another profile. Idempotent. */
    @PUT
    @Path("/me/following/{id}")
    @Transactional
    public void follow(@PathParam("id") String id) {
        UUID me = currentUser.profileId();
        UUID other = findProfile(id).id;
        if (other.equals(me)) throw error(Response.Status.BAD_REQUEST, "You can't follow yourself");
        em.createNativeQuery("""
                        INSERT INTO profiles.follow (follower_id, followed_id) VALUES (:me, :other)
                        ON CONFLICT DO NOTHING""")
                .setParameter("me", me)
                .setParameter("other", other)
                .executeUpdate();
    }

    @DELETE
    @Path("/me/following/{id}")
    @Transactional
    public void unfollow(@PathParam("id") String id) {
        Follow.delete("id.followerId = ?1 and id.followedId = ?2", currentUser.profileId(), findProfile(id).id);
    }

    /** Adds a (profile, movie) row to {@code table}, a table keyed by both; 404 if there's no such movie. */
    private void addMovie(String table, UUID profileId, int movieId) {
        if (MovieRef.findById(movieId) == null) throw error(Response.Status.NOT_FOUND, "No movie with id " + movieId);
        em.createNativeQuery("INSERT INTO profiles." + table + " (profile_id, movie_id) VALUES (:profile, :movie) ON CONFLICT DO NOTHING")
                .setParameter("profile", profileId)
                .setParameter("movie", movieId)
                .executeUpdate();
    }

    private WatchlistEntryView toView(WatchlistEntry w) {
        return new WatchlistEntryView(w.movie.id, w.movie.title, w.movie.posterPath, w.movie.releaseDate, w.addedAt);
    }

    /** "me", or a profile id; 404 for anything else. */
    private Profile findProfile(String id) {
        UUID profileId;
        if (id.equals("me")) {
            profileId = currentUser.profileId();
        } else {
            try {
                profileId = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw error(Response.Status.NOT_FOUND, "No profile with id " + id);
            }
        }
        Profile profile = Profile.findById(profileId);
        if (profile == null) throw error(Response.Status.NOT_FOUND, "No profile with id " + id);
        return profile;
    }

    private ProfileView view(Profile p) {
        UUID me = currentUser.profileId();
        return new ProfileView(
                p.id, p.username, p.createdAt,
                p.favoriteGenres.stream().map(g -> new GenreView(g.id, g.name)).toList(),
                WatchedMovie.count("id.profileId", p.id),
                p.id.equals(me) ? WatchlistEntry.count("id.profileId", p.id) : null,
                Follow.count("id.followerId", p.id),
                Follow.count("id.followedId", p.id),
                p.id.equals(me),
                Follow.count("id.followerId = ?1 and id.followedId = ?2", me, p.id) > 0);
    }

    private static Sort byNewest(String dateField, String tieBreaker) {
        return Sort.by(dateField, Sort.Direction.Descending).and(tieBreaker);
    }

    private static <E, V> PageResponse<V> page(PanacheQuery<E> query, int page, int size, Function<E, V> toView) {
        PanacheQuery<E> paged = query.page(Page.of(page, size));
        return new PageResponse<>(paged.list().stream().map(toView).toList(), page, size, paged.count(), paged.pageCount());
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static WebApplicationException error(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status).entity(new ErrorView(message))
                .type(MediaType.APPLICATION_JSON).build());
    }
}
