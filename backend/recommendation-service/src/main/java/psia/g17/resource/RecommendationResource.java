package psia.g17.resource;

import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.MoviesClient;
import psia.g17.dto.ErrorView;
import psia.g17.dto.MovieView;
import psia.g17.dto.MyRecommendations;
import psia.g17.dto.PromptRequest;
import psia.g17.dto.RequestView;
import psia.g17.entity.ProfileState;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;
import psia.g17.service.QueueingException;
import psia.g17.service.RecommendationQueue;
import psia.g17.service.RecommendationStore;
import psia.g17.service.SseBroadcaster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI movie recommendations for the logged-in user. Making them takes a while, so requests return at
 * once (202) and the answer arrives later: listen on {@code /recommendations/events} (SSE) for
 * "recommendations-ready", then fetch it (or show its error, for status FAILED).
 */
@Path("/recommendations")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class RecommendationResource {

    private final JsonWebToken token;
    private final RecommendationQueue queue;
    private final RecommendationStore store;
    private final SseBroadcaster broadcaster;
    private final MoviesClient movies;
    private final Duration firstRetry;
    private final Duration longestRetry;

    public RecommendationResource(JsonWebToken token, RecommendationQueue queue, RecommendationStore store, SseBroadcaster broadcaster,
                                  @RestClient MoviesClient movies,
                                  @ConfigProperty(name = "recommendation.retry.first", defaultValue = "PT30M") Duration firstRetry,
                                  @ConfigProperty(name = "recommendation.retry.longest", defaultValue = "PT6H") Duration longestRetry) {
        this.token = token;
        this.queue = queue;
        this.store = store;
        this.broadcaster = broadcaster;
        this.movies = movies;
        this.firstRetry = firstRetry;
        this.longestRetry = longestRetry;
    }

    /**
     * The user's current "Recommended for you". If they have none yet, making them starts now: status
     * PREPARING, and an SSE event follows when they're ready (or FAILED, with why).
     */
    @GET
    @Path("/me")
    public MyRecommendations mine() {
        UUID me = me();
        var done = RecommendationRequest.latest(me, Kind.PROFILE, RecommendationRequest.Status.DONE);
        boolean refreshing = RecommendationRequest.pending(me, Kind.PROFILE) > 0;
        if (done.isPresent()) {
            RecommendationRequest r = done.get();
            return new MyRecommendations("READY", r.id, r.completedAt, refreshing, null, movies(r));
        }
        if (refreshing) {
            return new MyRecommendations("PREPARING", RecommendationRequest.latest(me, Kind.PROFILE).map(r -> r.id).orElse(null),
                    null, true, null, List.of());
        }
        ProfileState state = ProfileState.findById(me);
        if (state != null && state.lastError != null && state.backingOff(Instant.now(), firstRetry, longestRetry)) {
            return new MyRecommendations("FAILED", null, null, false, state.lastError, List.of());
        }
        try {
            RecommendationRequest r = queue.forProfile(me);
            return r.status == RecommendationRequest.Status.DONE
                    ? new MyRecommendations("READY", r.id, r.completedAt, false, null, movies(r))
                    : new MyRecommendations("PREPARING", r.id, null, true, null, List.of());
        } catch (QueueingException e) {
            store.profileFailed(me, e.getMessage());
            return new MyRecommendations("FAILED", null, null, false, e.getMessage(), List.of());
        }
    }

    /**
     * Asks for ten movies matching what the user typed, e.g. "a horror movie from the 80s": 202 with the
     * PENDING request. One at a time (409, with the id of the one being prepared); the same question
     * with an unchanged profile is answered at once (200, DONE).
     */
    @POST
    @Path("/requests")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response ask(@Valid @NotNull PromptRequest body) {
        try {
            RecommendationRequest r = queue.forPrompt(me(), body.prompt());
            boolean done = r.status == RecommendationRequest.Status.DONE;
            return Response.status(done ? Response.Status.OK : Response.Status.ACCEPTED).entity(view(r, done)).build();
        } catch (QueueingException e) {
            throw unavailable(e);
        }
    }

    /** The user's typed requests: the one being prepared (if any), then their last five answers, without movies. */
    @GET
    @Path("/requests")
    public List<RequestView> history() {
        return RecommendationRequest.<RecommendationRequest>find("userId = ?1 and kind = ?2 and status in ?3",
                        Sort.by("createdAt", Sort.Direction.Descending), me(), Kind.PROMPT,
                        List.of(RecommendationRequest.Status.PENDING, RecommendationRequest.Status.DONE))
                .page(0, RecommendationStore.PROMPT_HISTORY + 1).list().stream().map(r -> view(r, false)).toList();
    }

    /**
     * The typed request being prepared, or else the last one that failed in the past hour (with why),
     * so a page opened after the SSE event can still tell the user. 204 if there's neither.
     */
    @GET
    @Path("/requests/current")
    public Response current() {
        UUID me = me();
        return RecommendationRequest.latest(me, Kind.PROMPT, RecommendationRequest.Status.PENDING)
                .or(() -> RecommendationRequest.latest(me, Kind.PROMPT, RecommendationRequest.Status.FAILED))
                .map(r -> Response.ok(view(r, false)).build())
                .orElseGet(() -> Response.noContent().build());
    }

    /** One of the user's requests, with its movies once it's DONE. 404 for other users' requests. */
    @GET
    @Path("/requests/{id}")
    public RequestView request(@PathParam("id") UUID id) {
        RecommendationRequest r = RecommendationRequest.findById(id);
        if (r == null || !r.userId.equals(me())) throw new NotFoundException();
        return view(r, true);
    }

    /**
     * Server-sent events for the user: "recommendations-ready" with {@code {requestId, userId, kind, status, error}}
     * whenever one of their requests finishes, plus a comment every 20 seconds to keep it open.
     */
    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void events(@Context SseEventSink sink, @Context Sse sse) {
        broadcaster.register(me(), sink, sse);
    }

    private static WebApplicationException unavailable(QueueingException e) {
        return new WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(new ErrorView(e.getMessage())).type(MediaType.APPLICATION_JSON).build());
    }

    private UUID me() {
        return UUID.fromString(token.getSubject());
    }

    private RequestView view(RecommendationRequest r, boolean withMovies) {
        return new RequestView(r.id, r.kind, r.prompt, r.status, r.error, r.createdAt, r.completedAt, r.model,
                withMovies ? movies(r) : List.of());
    }

    /** The request's movies, in order, with titles and posters from movies-service. */
    private List<MovieView> movies(RecommendationRequest r) {
        if (r.movieIds.isEmpty()) return List.of();
        Map<Integer, MoviesClient.Movie> byId = movies.movies(r.movieIds).stream()
                .collect(Collectors.toMap(MoviesClient.Movie::id, Function.identity(), (a, b) -> a));
        return r.movieIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(m -> new MovieView(m.id(), m.title(), m.posterPath(), m.releaseDate(), m.rating()))
                .toList();
    }
}
