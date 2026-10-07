package psia.g17.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.ProfilesClient;
import psia.g17.client.ProfilesClient.Fingerprint;
import psia.g17.client.ProfilesClient.Snapshot;
import psia.g17.dto.ErrorView;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;
import psia.g17.events.ProfileSnapshot;
import psia.g17.events.RecommendationJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Queues requests for recommendation-worker: saves a PENDING request with the fingerprint of the
 * user's profile snapshot, and publishes the job, snapshot included (so the worker needs no access to
 * profiles-service, and no user token travels through Kafka), to recommendation.requests, keyed by
 * user id. Where a finished result made from identical inputs exists, it's reused instead.
 *
 * <p>Every request is counted in {@code recommendation_requests_total}, by {@code kind} and {@code outcome}:
 * <ul>
 *   <li>{@code queued}: a job for the worker (and the language model);</li>
 *   <li>{@code reused}: another user's set made from identical inputs, copied; {@code repeated}: the same
 *       question with an unchanged profile, answered again. Neither asks the model;</li>
 *   <li>{@code already_pending}: one was already being made; {@code rejected}: a second typed request
 *       while one is being made (409);</li>
 *   <li>{@code unavailable}: profiles-service or Kafka couldn't be reached.</li>
 * </ul>
 */
@ApplicationScoped
public class RecommendationQueue {

    private final Emitter<Record<String, RecommendationJob>> jobs;
    private final RecommendationStore store;
    private final ProfilesClient profiles;
    private final MeterRegistry metrics;

    public RecommendationQueue(@Channel("recommendation-requests") Emitter<Record<String, RecommendationJob>> jobs,
                               RecommendationStore store, @RestClient ProfilesClient profiles, MeterRegistry metrics) {
        this.jobs = jobs;
        this.store = store;
        this.profiles = profiles;
        this.metrics = metrics;
    }

    /**
     * The user's next profile recommendations: the ones being made, a copy of another user's made from
     * identical inputs, or a new job.
     */
    public RecommendationRequest forProfile(UUID userId) {
        Optional<RecommendationRequest> pending = RecommendationRequest.latest(userId, Kind.PROFILE, RecommendationRequest.Status.PENDING);
        if (pending.isPresent()) {
            counted(Kind.PROFILE, "already_pending");
            return pending.get();
        }
        return newProfile(userId, snapshot(Kind.PROFILE, userId));
    }

    /**
     * Recommendations for something the user typed. One at a time per user (409 while one is being
     * prepared); the same question with an unchanged profile gets its earlier answer at once.
     */
    public RecommendationRequest forPrompt(UUID userId, String prompt) {
        Optional<RecommendationRequest> pending = RecommendationRequest.latest(userId, Kind.PROMPT, RecommendationRequest.Status.PENDING);
        if (pending.isPresent()) {
            counted(Kind.PROMPT, "rejected");
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorView("You already have a request being prepared; wait for it to finish.", pending.get().id))
                    .type(MediaType.APPLICATION_JSON).build());
        }
        Snapshot snapshot = snapshot(Kind.PROMPT, userId);
        String inputHash = Hashes.prompt(snapshot.fingerprint().root(), prompt);
        Optional<RecommendationRequest> earlier = RecommendationRequest.currentRecipe()
                .flatMap(recipe -> RecommendationRequest.<RecommendationRequest>find(
                                "userId = ?1 and kind = ?2 and status = ?3 and inputHash = ?4 and recipe = ?5",
                                userId, Kind.PROMPT, RecommendationRequest.Status.DONE, inputHash, recipe)
                        .firstResultOptional());
        if (earlier.isPresent()) {
            Log.infof("Same question, same profile: request %s answered again", earlier.get().id);
            counted(Kind.PROMPT, "repeated");
            return store.bringToTop(earlier.get().id);
        }
        return enqueue(userId, Kind.PROMPT, prompt.strip(), snapshot, inputHash);
    }

    private RecommendationRequest newProfile(UUID userId, Snapshot snapshot) {
        Fingerprint fingerprint = snapshot.fingerprint();
        Optional<RecommendationRequest> identical = RecommendationRequest.currentRecipe()
                .flatMap(recipe -> RecommendationRequest.doneWith(Kind.PROFILE, fingerprint.root(), recipe));
        if (identical.isPresent()) {
            counted(Kind.PROFILE, "reused");
            return store.reuse(identical.get(), userId, fingerprint);
        }
        return enqueue(userId, Kind.PROFILE, null, snapshot, fingerprint.root());
    }

    private RecommendationRequest enqueue(UUID userId, Kind kind, String prompt, Snapshot snapshot, String inputHash) {
        RecommendationRequest request = QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest r = RecommendationRequest.create(userId, kind, prompt);
            r.inputHash = inputHash;
            r.ownHash = snapshot.fingerprint().own();
            r.socialHash = snapshot.fingerprint().social();
            r.persist();
            return r;
        });
        ProfileSnapshot profile = new ProfileSnapshot(snapshot.username(), snapshot.favoriteGenreIds(), snapshot.watchedMovieIds(),
                snapshot.watchlistMovieIds(), snapshot.followedWatched());
        try {
            // Wait for Kafka to accept the job, so a broker that's down shows up as an error now.
            jobs.send(Record.of(userId.toString(), new RecommendationJob(request.id, userId, kind, prompt, profile, request.createdAt)))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            store.discard(request.id);
            counted(kind, "unavailable");
            throw new QueueingException("Couldn't queue the request: " + rootMessage(e), e);
        }
        Log.infof("Queued %s request %s for user %s", kind, request.id, userId);
        counted(kind, "queued");
        return request;
    }

    /** What the user's profile says about their taste; an empty profile if they don't have one yet. */
    private Snapshot snapshot(Kind kind, UUID userId) {
        try {
            return profiles.snapshot(userId);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                List<Integer> none = List.of();
                return new Snapshot(userId, null, null, none, none, none, List.of(), List.of(), emptyFingerprint());
            }
            counted(kind, "unavailable");
            throw new QueueingException("Couldn't read the profile: " + rootMessage(e), e);
        } catch (RuntimeException e) {
            counted(kind, "unavailable");
            throw new QueueingException("Couldn't read the profile: " + rootMessage(e), e);
        }
    }

    private void counted(Kind kind, String outcome) {
        Counter.builder("recommendation.requests")
                .description("Recommendation requests, by kind and what became of them")
                .tag("kind", kind.name().toLowerCase())
                .tag("outcome", outcome)
                .register(metrics)
                .increment();
    }

    /** The fingerprint profiles-service gives an empty profile (same canonical form, see its ProfileSnapshots). */
    static Fingerprint emptyFingerprint() {
        String own = Hashes.sha256("g:|w:|l:");
        String social = Hashes.sha256("f:");
        return new Fingerprint(Hashes.sha256(own + ":" + social), own, social);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
