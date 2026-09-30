package psia.g17.service;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import psia.g17.client.ProfilesClient.Fingerprint;
import psia.g17.entity.ProfileState;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;
import psia.g17.events.ReadyEvent;
import psia.g17.events.RecommendationResult;
import psia.g17.events.Status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What happens to requests once they're answered, in one place:
 * <ul>
 *   <li>"Recommended for you": each user keeps only their current set, plus one being made. A new set
 *       replaces the old one once it's stored; a failure is deleted, leaving the current set, and
 *       counted in profile_state so retries back off.</li>
 *   <li>Typed requests: each user keeps their last {@value #PROMPT_HISTORY} answers. A failure stays for
 *       an hour, outside the history, so a returning user can be told (see PendingSweeper).</li>
 * </ul>
 * Every outcome is announced on recommendation.ready, which reaches the user's open SSE connections.
 */
@ApplicationScoped
public class RecommendationStore {

    public static final int PROMPT_HISTORY = 5;

    private final Emitter<ReadyEvent> ready;

    public RecommendationStore(@Channel("recommendation-ready") Emitter<ReadyEvent> ready) {
        this.ready = ready;
    }

    /** Applies a worker's result. Receiving it twice, or for a request that was given up on, changes nothing. */
    public void complete(RecommendationResult result) {
        announce(QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest r = RecommendationRequest.findById(result.requestId());
            if (r == null || r.status != RecommendationRequest.Status.PENDING) {
                Log.infof("Result for request %s ignored: %s", result.requestId(), r == null ? "it's gone" : "already " + r.status);
                return null;
            }
            r.model = result.model();
            r.recipe = result.recipe();
            r.durationMs = result.durationMs();
            if (result.status() != Status.DONE) {
                return failed(r, result.error());
            }
            r.status = RecommendationRequest.Status.DONE;
            r.completedAt = result.completedAt() != null ? result.completedAt() : Instant.now();
            r.movieIds.clear();
            if (result.movieIds() != null) r.movieIds.addAll(result.movieIds());
            succeeded(r);
            Log.infof("Request %s DONE in %d ms", r.id, r.durationMs);
            return new ReadyEvent(r.id, r.userId, r.kind, Status.DONE, null);
        }));
    }

    /** Gives up on a request still waiting (e.g. no answer in time). */
    public void fail(UUID requestId, String error) {
        announce(QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest r = RecommendationRequest.findById(requestId);
            return r == null || r.status != RecommendationRequest.Status.PENDING ? null : failed(r, error);
        }));
    }

    /** Removes a request that never reached the queue. */
    public void discard(UUID requestId) {
        QuarkusTransaction.requiringNew().run(() -> RecommendationRequest.deleteById(requestId));
    }

    /** Counts a failed attempt at the user's profile recommendations that never got a request (e.g. couldn't be queued). */
    public void profileFailed(UUID userId, String error) {
        QuarkusTransaction.requiringNew().run(() -> recordFailure(ProfileState.of(userId), error));
    }

    /**
     * Gives the user a copy of another user's finished profile recommendations made from identical
     * inputs by the current recipe (e.g. every new user's empty profile): no job needed.
     */
    public RecommendationRequest reuse(RecommendationRequest source, UUID userId, Fingerprint fingerprint) {
        RecommendationRequest copy = QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest from = RecommendationRequest.findById(source.id);
            RecommendationRequest r = RecommendationRequest.create(userId, Kind.PROFILE, null);
            r.status = RecommendationRequest.Status.DONE;
            r.model = from.model;
            r.recipe = from.recipe;
            r.durationMs = 0L;
            r.completedAt = Instant.now();
            r.inputHash = fingerprint.root();
            r.ownHash = fingerprint.own();
            r.socialHash = fingerprint.social();
            r.movieIds.addAll(from.movieIds);
            r.persist();
            succeeded(r);
            return r;
        });
        Log.infof("Reused request %s's recommendations for user %s (identical inputs)", source.id, userId);
        announce(new ReadyEvent(copy.id, userId, Kind.PROFILE, Status.DONE, null));
        return copy;
    }

    /** Moves an earlier answer to the top of the user's history, for a question asked again with an unchanged profile. */
    public RecommendationRequest bringToTop(UUID requestId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest r = RecommendationRequest.findById(requestId);
            r.createdAt = Instant.now();
            r.movieIds.size(); // loaded while the transaction is open
            return r;
        });
    }

    private void succeeded(RecommendationRequest r) {
        if (r.kind == Kind.PROFILE) {
            RecommendationRequest.delete("userId = ?1 and kind = ?2 and id <> ?3", r.userId, Kind.PROFILE, r.id);
            ProfileState state = ProfileState.of(r.userId);
            state.inputHash = r.inputHash;
            state.ownHash = r.ownHash;
            state.socialHash = r.socialHash;
            state.recipe = r.recipe;
            state.lastSuccessAt = r.completedAt;
            state.lastFailureAt = null;
            state.lastError = null;
            state.consecutiveFailures = 0;
        } else {
            List<UUID> keep = RecommendationRequest.<RecommendationRequest>find("userId = ?1 and kind = ?2 and status = ?3",
                            Sort.by("createdAt", Sort.Direction.Descending), r.userId, Kind.PROMPT, RecommendationRequest.Status.DONE)
                    .page(0, PROMPT_HISTORY).list().stream().map(request -> request.id).toList();
            RecommendationRequest.delete("userId = ?1 and kind = ?2 and status = ?3 and id not in ?4",
                    r.userId, Kind.PROMPT, RecommendationRequest.Status.DONE, keep);
            RecommendationRequest.delete("userId = ?1 and kind = ?2 and status = ?3", r.userId, Kind.PROMPT, RecommendationRequest.Status.FAILED);
        }
    }

    private ReadyEvent failed(RecommendationRequest r, String error) {
        String message = error == null || error.isBlank() ? "Something went wrong." : error.length() <= 500 ? error : error.substring(0, 499) + "…";
        if (r.kind == Kind.PROFILE) {
            recordFailure(ProfileState.of(r.userId), message);
            r.delete();
        } else {
            r.status = RecommendationRequest.Status.FAILED;
            r.error = message;
            r.completedAt = Instant.now();
        }
        Log.infof("Request %s FAILED: %s", r.id, message);
        return new ReadyEvent(r.id, r.userId, r.kind, Status.FAILED, message);
    }

    private static void recordFailure(ProfileState state, String error) {
        state.lastFailureAt = Instant.now();
        state.lastError = error;
        state.consecutiveFailures++;
    }

    private void announce(ReadyEvent event) {
        if (event != null) ready.send(event);
    }
}
