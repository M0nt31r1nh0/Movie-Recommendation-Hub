package psia.g17;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/movies")
@Produces(MediaType.APPLICATION_JSON)
public class MovieResource {

    @GET
    public PageResponse<Movie> list(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size,
            @QueryParam("sort") @DefaultValue("id") @Pattern(regexp = "id|title|rating|releaseDate") String sort,
            @QueryParam("order") @DefaultValue("asc") @Pattern(regexp = "asc|desc") String order) {
        Sort.Direction direction = order.equals("desc") ? Sort.Direction.Descending : Sort.Direction.Ascending;
        Sort orderBy = Sort.by(sort, direction, Sort.NullPrecedence.NULLS_LAST);
        if (!sort.equals("id")) {
            // Many movies share a rating or date; tie-break on id so pages don't overlap or skip rows.
            orderBy = orderBy.and("id");
        }
        PanacheQuery<Movie> query = Movie.findAll(orderBy).page(Page.of(page, size));
        return new PageResponse<>(query.list(), page, size, query.count(), query.pageCount());
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
