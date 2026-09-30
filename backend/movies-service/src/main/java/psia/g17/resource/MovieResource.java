package psia.g17.resource;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import psia.g17.dto.CandidateQuery;
import psia.g17.dto.PageResponse;
import psia.g17.entity.Movie;
import psia.g17.service.MovieCandidates;
import psia.g17.service.MovieSearch;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every endpoint needs a valid Keycloak access token ({@code Authorization: Bearer ...}); any logged-in user may use all of them.
 */
@Path("/movies")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MovieResource {

    private final MovieSearch movieSearch;
    private final MovieCandidates candidates;

    public MovieResource(MovieSearch movieSearch, MovieCandidates candidates) {
        this.movieSearch = movieSearch;
        this.candidates = candidates;
    }

    /**
     * All movies, newest release first unless {@code sort} says otherwise. An explicit {@code sort}
     * without an {@code order} is ascending.
     */
    @GET
    public PageResponse<Movie> list(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size,
            @QueryParam("sort") @Pattern(regexp = "id|title|rating|releaseDate") String sort,
            @QueryParam("order") @Pattern(regexp = "asc|desc") String order) {
        if (sort == null) {
            sort = "releaseDate";
            order = order == null ? "desc" : order;
        } else if (order == null) {
            order = "asc";
        }
        Sort.Direction direction = order.equals("desc") ? Sort.Direction.Descending : Sort.Direction.Ascending;
        Sort orderBy = Sort.by(sort, direction, Sort.NullPrecedence.NULLS_LAST);
        if (!sort.equals("id")) {
            // Many movies share a rating or date; tie-break on id so pages don't overlap or skip rows.
            orderBy = orderBy.and("id");
        }
        PanacheQuery<Movie> query = Movie.findAll(orderBy).page(Page.of(page, size));
        return new PageResponse<>(query.list(), page, size, query.count(), query.pageCount());
    }

    /**
     * Full-text search on title and description. Supports web-search syntax: {@code "exact phrase"},
     * {@code or}, and {@code -excluded}. Also matches the start of title words ("Ava" finds "Avatar"),
     * ignores accents, and adds similar-looking titles when there are few matches ("godfater").
     * Best matches come first, unless {@code sort} names a field to order the matches by instead.
     */
    @GET
    @Path("/search")
    public PageResponse<Movie> search(
            @QueryParam("q") @NotBlank @Size(max = 200) String q,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size,
            @QueryParam("sort") @DefaultValue("relevance") @Pattern(regexp = "relevance|id|title|rating|releaseDate") String sort,
            @QueryParam("order") @DefaultValue("asc") @Pattern(regexp = "asc|desc") String order) {
        return movieSearch.search(q.strip(), page, size, sort, order);
    }

    /**
     * Several movies at once ({@code ?ids=238&ids=27205}), in the order asked for; unknown ids are left
     * out. For other services, e.g. profiles-service showing someone's watched movies.
     */
    @GET
    @Path("/batch")
    public List<Movie> batch(@QueryParam("ids") @NotNull @Size(max = 100) List<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, Movie> found = Movie.<Movie>list("id in ?1", ids).stream()
                .collect(Collectors.toMap(movie -> movie.id, Function.identity()));
        return ids.stream().distinct().map(found::get).filter(movie -> movie != null).toList();
    }

    /**
     * Candidate movies for recommendations: well-regarded movies matching the filters, best first. For
     * recommendation-worker, which lets its language model choose among them (see MovieCandidates).
     */
    @POST
    @Path("/candidates")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<Movie> candidates(@Valid @NotNull CandidateQuery query) {
        return candidates.find(query);
    }

    @GET
    @Path("/{id}")
    public Movie get(@PathParam("id") int id) {
        Movie movie = Movie.findById(id);
        if (movie == null) {
            throw new NotFoundException("Movie " + id + " not found");
        }
        return movie;
    }
}
