package psia.g17.resource;

import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import psia.g17.entity.Genre;

import java.util.List;

/** All movie categories, by name. Needs a Keycloak access token, like every other endpoint. */
@Path("/genres")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class GenreResource {

    @GET
    public List<Genre> list() {
        return Genre.listAll(Sort.by("name"));
    }
}
