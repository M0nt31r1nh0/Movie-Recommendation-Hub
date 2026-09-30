package psia.g17.client;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.time.LocalDate;
import java.util.List;

/** movies-service's API, for the titles and posters of recommended movies; called as this service's service account. */
@RegisterRestClient(configKey = "movies-api")
@OidcClientFilter
public interface MoviesClient {

    /** These movies, in that order; unknown ids are left out. At most 100. */
    @GET
    @Path("/movies/batch")
    List<Movie> movies(@QueryParam("ids") List<Integer> ids);

    record Movie(int id, String title, String posterPath, LocalDate releaseDate, Double rating) {
    }
}
