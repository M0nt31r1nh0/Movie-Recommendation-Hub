package psia.g17.resource;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import psia.g17.dto.PageResponse;
import psia.g17.dto.ProfileChange;
import psia.g17.dto.ProfileSnapshot;
import psia.g17.entity.Profile;
import psia.g17.service.ProfileSnapshots;

import java.util.UUID;

/**
 * Read access to every profile, including watchlists, for recommendation-service. Only tokens with the
 * Keycloak realm role {@code profiles-reader} may call it: recommendation-service's service account has
 * it, users don't. Unlike ProfileResource, it never creates a profile for the caller.
 */
@Path("/internal/profiles")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("profiles-reader")
public class InternalProfileResource {

    private final ProfileSnapshots snapshots;

    public InternalProfileResource(ProfileSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * Every profile with its current fingerprint, for the background agent to spot whose inputs
     * changed. A snapshot is built for each, so pages are kept moderate.
     */
    @GET
    public PageResponse<ProfileChange> profiles(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("100") @Min(1) @Max(200) int size) {
        var paged = Profile.<Profile>findAll(Sort.by("id")).page(Page.of(page, size));
        return new PageResponse<>(paged.list().stream()
                .map(p -> new ProfileChange(p.id, p.username, p.lastChangedAt, snapshots.of(p).fingerprint()))
                .toList(), page, size, paged.count(), paged.pageCount());
    }

    @GET
    @Path("/{id}/snapshot")
    public ProfileSnapshot snapshot(@PathParam("id") UUID id) {
        Profile p = Profile.findById(id);
        if (p == null) throw new NotFoundException("No profile with id " + id);
        return snapshots.of(p);
    }
}
