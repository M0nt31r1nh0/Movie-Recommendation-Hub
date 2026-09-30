package psia.g17.client;

import io.quarkus.oidc.token.propagation.common.AccessToken;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * movies-service's API, the only way this service learns about movies and genres (their data is in
 * movies-service's own database). Calls carry the logged-in user's access token (@AccessToken), which
 * movies-service accepts too. Use it through MovieCatalog.
 */
@RegisterRestClient(configKey = "movies-api")
@AccessToken
public interface MoviesClient {

    @GET
    @Path("/genres")
    List<Genre> genres();

    /** The movies with these ids, in that order; unknown ids are left out. At most 100. */
    @GET
    @Path("/movies/batch")
    List<Movie> movies(@QueryParam("ids") List<Integer> ids);

    record Genre(int id, String name) {
    }

    /** The fields of movies-service's movie that profiles show; the rest are ignored. */
    record Movie(int id, String title, String posterPath, LocalDate releaseDate) {
    }
}
