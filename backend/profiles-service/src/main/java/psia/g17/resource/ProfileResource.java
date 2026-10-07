package psia.g17.resource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import psia.g17.client.MoviesClient;
import psia.g17.dto.ErrorView;
import psia.g17.dto.GenreView;
import psia.g17.dto.PageResponse;
import psia.g17.dto.ProfileSummary;
import psia.g17.dto.ProfileView;
import psia.g17.dto.WatchedMovieView;
import psia.g17.dto.WatchlistEntryView;
import psia.g17.entity.Follow;
import psia.g17.entity.Profile;
import psia.g17.entity.WatchedMovie;
import psia.g17.entity.WatchlistEntry;
import psia.g17.security.CurrentUser;
import psia.g17.service.MovieCatalog;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * User profiles: favourite genres, watched movies, a watch-later list and follows. Every endpoint needs a
 * Keycloak access token. Anyone logged in can read any profile, except its watchlist, which only its
 * owner sees; changes go through {@code /profiles/me/...}, so users only ever change their own. Wherever
 * a path takes a profile id, {@code me} means the logged-in user.
 *
 * <p>Each change is counted in {@code profiles_actions_total}, by {@code action}: favorite_genres_set,
 * watched_added, watched_removed, watchlist_added, watchlist_removed, followed, unfollowed. A removal that
 * removed nothing isn't counted.
 */
@Path("/profiles")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ProfileResource {

    private final CurrentUser currentUser;
    private final MovieCatalog catalog;
    private final EntityManager em;
    private final MeterRegistry metrics;

    public ProfileResource(CurrentUser currentUser, MovieCatalog catalog, EntityManager em, MeterRegistry metrics) {
        this.currentUser = currentUser;
        this.catalog = catalog;
        this.em = em;
        this.metrics = metrics;
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
        Set<Integer> unknown = new LinkedHashSet<>(wanted);
        unknown.removeAll(catalog.genres().keySet());
        if (!unknown.isEmpty()) {
            throw error(Response.Status.BAD_REQUEST, "Unknown genre ids: " + unknown);
        }
        me.favoriteGenreIds.clear();
        me.favoriteGenreIds.addAll(wanted);
        touch(me.id);
        counted("favorite_genres_set");
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
        return withMovies(query, page, size, w -> w.id.movieId(), (w, m) -> new WatchedMovieView(
                m.id(), m.title(), m.posterPath(), m.releaseDate(), w.watchedAt));
    }

    /** Whether the logged-in user has seen a movie: 200 with the details if so, 404 if not. */
    @GET
    @Path("/me/watched/{movieId}")
    public WatchedMovieView watchedMovie(@PathParam("movieId") int movieId) {
        WatchedMovie w = WatchedMovie.findById(new WatchedMovie.Key(currentUser.profileId(), movieId));
        if (w == null) throw error(Response.Status.NOT_FOUND, "Not marked as watched");
        MoviesClient.Movie m = movie(movieId);
        return new WatchedMovieView(m.id(), m.title(), m.posterPath(), m.releaseDate(), w.watchedAt);
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
        touch(me);
        counted("watched_added");
    }

    @DELETE
    @Path("/me/watched/{movieId}")
    @Transactional
    public void unmarkWatched(@PathParam("movieId") int movieId) {
        if (WatchedMovie.delete("id.profileId = ?1 and id.movieId = ?2", currentUser.profileId(), movieId) > 0) {
            touch(currentUser.profileId());
            counted("watched_removed");
        }
    }

    /** The logged-in user's watch-later list, most recently added first. Nobody else can see it. */
    @GET
    @Path("/me/watchlist")
    public PageResponse<WatchlistEntryView> watchlist(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        PanacheQuery<WatchlistEntry> query = WatchlistEntry.find("id.profileId", byNewest("addedAt", "id.movieId"), currentUser.profileId());
        return withMovies(query, page, size, w -> w.id.movieId(), (w, m) -> new WatchlistEntryView(
                m.id(), m.title(), m.posterPath(), m.releaseDate(), w.addedAt));
    }

    /** Whether a movie is on the logged-in user's watchlist: 200 with the details if so, 404 if not. */
    @GET
    @Path("/me/watchlist/{movieId}")
    public WatchlistEntryView watchlistEntry(@PathParam("movieId") int movieId) {
        WatchlistEntry w = WatchlistEntry.findById(new WatchlistEntry.Key(currentUser.profileId(), movieId));
        if (w == null) throw error(Response.Status.NOT_FOUND, "Not on the watchlist");
        MoviesClient.Movie m = movie(movieId);
        return new WatchlistEntryView(m.id(), m.title(), m.posterPath(), m.releaseDate(), w.addedAt);
    }

    /** Adds a movie to the logged-in user's watchlist. Idempotent: adding it again keeps the original date. */
    @PUT
    @Path("/me/watchlist/{movieId}")
    @Transactional
    public void addToWatchlist(@PathParam("movieId") int movieId) {
        addMovie("watchlist", currentUser.profileId(), movieId);
        touch(currentUser.profileId());
        counted("watchlist_added");
    }

    @DELETE
    @Path("/me/watchlist/{movieId}")
    @Transactional
    public void removeFromWatchlist(@PathParam("movieId") int movieId) {
        if (WatchlistEntry.delete("id.profileId = ?1 and id.movieId = ?2", currentUser.profileId(), movieId) > 0) {
            touch(currentUser.profileId());
            counted("watchlist_removed");
        }
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
        touch(me);
        counted("followed");
    }

    @DELETE
    @Path("/me/following/{id}")
    @Transactional
    public void unfollow(@PathParam("id") String id) {
        if (Follow.delete("id.followerId = ?1 and id.followedId = ?2", currentUser.profileId(), findProfile(id).id) > 0) {
            touch(currentUser.profileId());
            counted("unfollowed");
        }
    }

    private void counted(String action) {
        Counter.builder("profiles.actions")
                .description("Changes users made to their profiles, by action")
                .tag("action", action)
                .register(metrics)
                .increment();
    }

    /** Records that a profile's recommendation inputs changed, for recommendation-service's background agent. */
    private void touch(UUID profileId) {
        em.createNativeQuery("UPDATE profiles.profile SET last_changed_at = now() WHERE profile_id = :id")
                .setParameter("id", profileId)
                .executeUpdate();
    }

    /** Adds a (profile, movie) row to {@code table}, a table keyed by both; 404 if movies-service has no such movie. */
    private void addMovie(String table, UUID profileId, int movieId) {
        if (!catalog.movieExists(movieId)) throw error(Response.Status.NOT_FOUND, "No movie with id " + movieId);
        em.createNativeQuery("INSERT INTO profiles." + table + " (profile_id, movie_id) VALUES (:profile, :movie) ON CONFLICT DO NOTHING")
                .setParameter("profile", profileId)
                .setParameter("movie", movieId)
                .executeUpdate();
    }

    private MoviesClient.Movie movie(int movieId) {
        MoviesClient.Movie m = catalog.movies(List.of(movieId)).get(movieId);
        if (m == null) throw error(Response.Status.NOT_FOUND, "No movie with id " + movieId);
        return m;
    }

    /**
     * A page of rows that reference movies, with each movie's details fetched from movies-service in one
     * request. Rows whose movie movies-service no longer has are left out of the page.
     */
    private <E, V> PageResponse<V> withMovies(PanacheQuery<E> query, int page, int size, Function<E, Integer> movieId,
                                              BiFunction<E, MoviesClient.Movie, V> toView) {
        PanacheQuery<E> paged = query.page(Page.of(page, size));
        List<E> rows = paged.list();
        Map<Integer, MoviesClient.Movie> movies = catalog.movies(rows.stream().map(movieId).toList());
        List<V> items = rows.stream()
                .map(row -> {
                    MoviesClient.Movie m = movies.get(movieId.apply(row));
                    return m == null ? null : toView.apply(row, m);
                })
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, page, size, paged.count(), paged.pageCount());
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
        Map<Integer, MoviesClient.Genre> genres = p.favoriteGenreIds.isEmpty() ? Map.of() : catalog.genres();
        return new ProfileView(
                p.id, p.username, p.createdAt,
                p.favoriteGenreIds.stream()
                        .map(genres::get)
                        .filter(Objects::nonNull)
                        .map(g -> new GenreView(g.id(), g.name()))
                        .sorted(Comparator.comparing(GenreView::name))
                        .toList(),
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
