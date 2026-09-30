package psia.g17.client;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import psia.g17.events.FriendWatch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * profiles-service's internal endpoints, called as this service's Keycloak service account
 * (@OidcClientFilter), which holds the profiles-reader role they require.
 */
@RegisterRestClient(configKey = "profiles-api")
@OidcClientFilter
@Path("/internal/profiles")
public interface ProfilesClient {

    /** Every profile with its current fingerprint and when the user last changed their own inputs. */
    @GET
    Page<ProfileChange> profiles(@QueryParam("page") int page, @QueryParam("size") int size);

    @GET
    @Path("/{id}/snapshot")
    Snapshot snapshot(@PathParam("id") UUID id);

    record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }

    record ProfileChange(UUID id, String username, Instant lastChangedAt, Fingerprint fingerprint) {
    }

    /** SHA-256 of the snapshot: {@code own} covers the user's own inputs, {@code social} what people they follow watched. */
    record Fingerprint(String root, String own, String social) {
    }

    record Snapshot(UUID id, String username, Instant lastChangedAt, List<Integer> favoriteGenreIds,
                    List<Integer> watchedMovieIds, List<Integer> watchlistMovieIds, List<UUID> followedIds,
                    List<FriendWatch> followedWatched, Fingerprint fingerprint) {
    }
}
