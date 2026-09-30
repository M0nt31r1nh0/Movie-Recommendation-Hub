package psia.g17.client;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.time.LocalDate;
import java.util.List;

/** movies-service's API, called as the worker's own Keycloak service account (@OidcClientFilter). */
@RegisterRestClient(configKey = "movies-api")
@OidcClientFilter
public interface MoviesClient {

    @GET
    @Path("/genres")
    List<Genre> genres();

    /** These movies, in that order; unknown ids are left out. At most 100. */
    @GET
    @Path("/movies/batch")
    List<Movie> movies(@QueryParam("ids") List<Integer> ids);

    /** Well-regarded movies matching the query, best first (see movies-service's MovieCandidates). */
    @POST
    @Path("/movies/candidates")
    @Consumes(MediaType.APPLICATION_JSON)
    List<Movie> candidates(CandidateQuery query);

    record Genre(int id, String name) {
    }

    record Movie(int id, String title, String description, LocalDate releaseDate, Double rating, Integer ratingCount,
                 List<Genre> genres) {
    }

    /** See movies-service's CandidateQuery; null fields don't filter. */
    record CandidateQuery(List<Integer> genreIds, List<Integer> preferredGenreIds, Integer yearFrom, Integer yearTo,
                          String text, List<FriendWatch> friendWatchers, List<Integer> onlyIds, List<Integer> excludeIds,
                          Integer minRatingCount, Integer limit) {
    }

    record FriendWatch(int movieId, int watchers) {
    }
}
