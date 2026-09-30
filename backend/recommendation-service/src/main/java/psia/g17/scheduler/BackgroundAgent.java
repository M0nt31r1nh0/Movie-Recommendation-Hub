package psia.g17.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import psia.g17.client.ProfilesClient;
import psia.g17.client.ProfilesClient.ProfileChange;
import psia.g17.entity.ProfileState;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;
import psia.g17.service.QueueingException;
import psia.g17.service.RecommendationQueue;
import psia.g17.service.RecommendationStore;

import java.time.Duration;
import java.time.Instant;

/**
 * The background agent: keeps "Recommended for you" in step with each profile, without recomputing
 * anything whose inputs didn't change. Each run compares every profile's fingerprint (from
 * profiles-service) with the one its current set was made from (profile_state), and queues new
 * recommendations only when (see {@link #needsRefresh}):
 * <ul>
 *   <li>the user has none yet;</li>
 *   <li>their own inputs changed, once they've stopped changing for {@code settle};</li>
 *   <li>what the people they follow watched changed;</li>
 * </ul>
 * never while a set is being made, and after failures only once the back-off has passed. At most
 * {@code max-per-run} are queued per run, so a burst of changes doesn't bury users' Ask AI requests.
 */
@ApplicationScoped
public class BackgroundAgent {

    /** Only one copy of the service runs the agent at a time (a Postgres advisory lock). */
    private static final long LOCK_ID = 7_424_512;
    private static final int PAGE_SIZE = 100;

    private final ProfilesClient profiles;
    private final RecommendationQueue queue;
    private final RecommendationStore store;
    private final EntityManager em;
    private final Settings settings;

    /** The rules' timings; see application.properties. */
    public record Settings(Duration settle, Duration firstRetry, Duration longestRetry, int maxPerRun) {
    }

    public BackgroundAgent(@RestClient ProfilesClient profiles, RecommendationQueue queue, RecommendationStore store, EntityManager em,
                           @ConfigProperty(name = "recommendation.agent.settle", defaultValue = "PT2M") Duration settle,
                           @ConfigProperty(name = "recommendation.retry.first", defaultValue = "PT30M") Duration firstRetry,
                           @ConfigProperty(name = "recommendation.retry.longest", defaultValue = "PT6H") Duration longestRetry,
                           @ConfigProperty(name = "recommendation.agent.max-per-run", defaultValue = "20") int maxPerRun) {
        this.profiles = profiles;
        this.queue = queue;
        this.store = store;
        this.em = em;
        this.settings = new Settings(settle, firstRetry, longestRetry, maxPerRun);
    }

    @Scheduled(every = "${recommendation.agent.every:10m}", delayed = "${recommendation.agent.delay:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!Boolean.TRUE.equals(em.createNativeQuery("SELECT pg_try_advisory_xact_lock(" + LOCK_ID + ")").getSingleResult())) {
                Log.debug("Another copy is running the background agent");
                return;
            }
            Log.infof("Background agent queued %d profile recommendation requests", refreshStale());
        });
    }

    /** Queues profile recommendations for the users who need them; returns how many. */
    int refreshStale() {
        Instant now = Instant.now();
        int queued = 0;
        int page = 0;
        ProfilesClient.Page<ProfileChange> profilesPage;
        do {
            profilesPage = profiles.profiles(page++, PAGE_SIZE);
            for (ProfileChange profile : profilesPage.items()) {
                if (queued >= settings.maxPerRun()) {
                    Log.infof("Queued the most allowed per run (%d); the rest wait for the next run", settings.maxPerRun());
                    return queued;
                }
                boolean pending = RecommendationRequest.pending(profile.id(), Kind.PROFILE) > 0;
                if (needsRefresh(profile, ProfileState.findById(profile.id()), pending, now, settings)) {
                    try {
                        queue.forProfile(profile.id());
                        queued++;
                    } catch (QueueingException e) {
                        Log.warnf("Couldn't queue recommendations for %s: %s", profile.id(), e.getMessage());
                        store.profileFailed(profile.id(), e.getMessage());
                    }
                }
            }
        } while (page < profilesPage.totalPages());
        return queued;
    }

    /** The agent's rules, for one profile; {@code state} is null if the user never had recommendations. */
    static boolean needsRefresh(ProfileChange profile, ProfileState state, boolean pending, Instant now, Settings s) {
        if (pending) return false;
        if (state != null && state.backingOff(now, s.firstRetry(), s.longestRetry())) return false;
        if (state == null || state.inputHash == null) return true;
        if (profile.fingerprint().root().equals(state.inputHash)) return false;
        // The user's own changes wait until they've stopped for a moment (marking five movies in a row
        // makes one set, not five); what the people they follow watched counts at the next run.
        boolean ownChanged = !profile.fingerprint().own().equals(state.ownHash);
        return !ownChanged || profile.lastChangedAt() == null || profile.lastChangedAt().isBefore(now.minus(s.settle()));
    }
}
