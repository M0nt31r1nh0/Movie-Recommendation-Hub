package psia.g17.service;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.MoviesClient;
import psia.g17.client.MoviesClient.Genre;
import psia.g17.client.MoviesClient.Movie;
import psia.g17.dto.ErrorView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Movies and genres as movies-service knows them. If movies-service is down, requests that need it
 * answer 503 instead of failing with a stack trace.
 */
@ApplicationScoped
public class MovieCatalog {

    /** movies-service's limit for one batch request. */
    private static final int BATCH_SIZE = 100;

    private final MoviesClient movies;

    public MovieCatalog(@RestClient MoviesClient movies) {
        this.movies = movies;
    }

    /** All genres by id. There are only 20 and they rarely change, so they're cached (see application.properties). */
    @CacheResult(cacheName = "genres")
    public Map<Integer, Genre> genres() {
        return call(() -> movies.genres().stream()
                .collect(Collectors.toMap(Genre::id, Function.identity(), (a, b) -> a, LinkedHashMap::new)));
    }

    /** The movies with these ids that exist, by id. */
    public Map<Integer, Movie> movies(Collection<Integer> ids) {
        List<Integer> distinct = ids.stream().distinct().toList();
        Map<Integer, Movie> found = new LinkedHashMap<>();
        for (int from = 0; from < distinct.size(); from += BATCH_SIZE) {
            List<Integer> batch = distinct.subList(from, Math.min(from + BATCH_SIZE, distinct.size()));
            call(() -> movies.movies(new ArrayList<>(batch))).forEach(movie -> found.put(movie.id(), movie));
        }
        return found;
    }

    public boolean movieExists(int id) {
        return movies(List.of(id)).containsKey(id);
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ProcessingException | WebApplicationException e) {
            throw new WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ErrorView("The movies service isn't available: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON).build());
        }
    }
}
