package psia.g17.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import psia.g17.InMemoryKafka;
import psia.g17.TestSupport;
import psia.g17.client.MoviesClient;
import psia.g17.client.ProfilesClient;
import psia.g17.client.ProfilesClient.Fingerprint;
import psia.g17.client.ProfilesClient.ProfileChange;
import psia.g17.entity.ProfileState;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.Kind;
import psia.g17.events.Status;
import psia.g17.scheduler.BackgroundAgent.Settings;
import psia.g17.service.RecommendationQueue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static psia.g17.TestSupport.jobsFor;

@QuarkusTest
@WithTestResource(InMemoryKafka.class)
class BackgroundJobsTest {

    private static final Settings SETTINGS = new Settings(Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofHours(6), 20);
    private static final String CAROL = "40000000-0000-0000-0000-000000000001";
    private static final Fingerprint NOW = TestSupport.fingerprintOf("now");
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    @InjectMock
    @RestClient
    ProfilesClient profiles;

    @InjectMock
    @RestClient
    MoviesClient movies;

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    BackgroundAgent agent;

    @Inject
    PendingSweeper sweeper;

    @Inject
    RecommendationQueue queue;

    @BeforeEach
    void setUp() {
        TestSupport.mockProfilesAndMovies(profiles, movies);
    }

    // The agent's rules, one case each.

    @Test
    void recommendsForUsersWhoHaveNoneYet() {
        assertTrue(needs(profile(NOW, T), null, false));
    }

    @Test
    void neverRemakesAnUnchangedProfile() {
        assertFalse(needs(profile(NOW, T), state(NOW, T.minus(Duration.ofDays(1)), 0, null), false));
        assertFalse(needs(profile(NOW, T), state(NOW, T.minus(Duration.ofDays(90)), 0, null), false), "however old the set");
    }

    @Test
    void waitsForOwnChangesToSettle() {
        Fingerprint ownChanged = new Fingerprint("different root", "different own", NOW.social());
        assertFalse(needs(profile(ownChanged, T.minus(Duration.ofSeconds(30))), state(NOW, T.minus(Duration.ofHours(1)), 0, null), false),
                "the user is still changing things");
        assertTrue(needs(profile(ownChanged, T.minus(Duration.ofMinutes(5))), state(NOW, T.minus(Duration.ofHours(1)), 0, null), false));
    }

    @Test
    void remakesAsSoonAsWhatFollowedPeopleWatchedChanges() {
        Fingerprint socialChanged = new Fingerprint("different root", NOW.own(), "different social");
        assertTrue(needs(profile(socialChanged, T.minus(Duration.ofDays(3))), state(NOW, T.minus(Duration.ofMinutes(10)), 0, null), false));
    }

    @Test
    void aNewModelAloneDoesNotRemake() {
        ProfileState old = state(NOW, T.minus(Duration.ofHours(1)), 0, null);
        old.recipe = "qwen2.5:0.5b/p1";
        assertFalse(needs(profile(NOW, T), old, false));
    }

    @Test
    void neverWhileASetIsBeingMade() {
        assertFalse(needs(profile(NOW, T), null, true));
    }

    @Test
    void backsOffAfterFailuresDoublingTheWait() {
        assertFalse(needs(profile(NOW, T), state(null, null, 1, T.minus(Duration.ofMinutes(20))), false), "30 minutes after one failure");
        assertTrue(needs(profile(NOW, T), state(null, null, 1, T.minus(Duration.ofMinutes(40))), false));
        assertFalse(needs(profile(NOW, T), state(null, null, 3, T.minus(Duration.ofMinutes(100))), false), "2 hours after three");
        assertFalse(needs(profile(NOW, T), state(null, null, 10, T.minus(Duration.ofHours(5))), false), "at most 6 hours");
        assertTrue(needs(profile(NOW, T), state(null, null, 10, T.minus(Duration.ofHours(7))), false));
    }

    @Test
    void queuesAtMostMaxPerRunAndOnlyForThoseWhoNeedIt() {
        List<ProfileChange> changes = IntStream.range(0, 25)
                .mapToObj(i -> new ProfileChange(UUID.fromString("30000000-0000-0000-0000-0000000000%02d".formatted(i)), "u" + i,
                        Instant.now().minus(Duration.ofDays(1)), TestSupport.fingerprint(UUID.fromString("30000000-0000-0000-0000-0000000000%02d".formatted(i)))))
                .toList();
        String upToDate = changes.get(0).id().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            ProfileState s = ProfileState.of(changes.get(0).id());
            s.inputHash = changes.get(0).fingerprint().root();
            s.ownHash = changes.get(0).fingerprint().own();
            s.socialHash = changes.get(0).fingerprint().social();
            s.recipe = RecommendationRequest.currentRecipe().orElse(null);
            s.lastSuccessAt = Instant.now();
        });
        when(profiles.profiles(anyInt(), anyInt())).thenReturn(new ProfilesClient.Page<>(changes, 0, 100, 25, 1));

        assertEquals(20, agent.refreshStale());
        assertTrue(jobsFor(connector, upToDate).isEmpty(), "an up-to-date user is skipped");
        assertEquals(1, jobsFor(connector, changes.get(1).id().toString()).size());
        assertTrue(jobsFor(connector, changes.get(24).id().toString()).isEmpty(), "the rest wait for the next run");
    }

    // A whole cycle: the agent remakes a changed profile, keeps the old set if that fails, and retries.

    @Test
    void remakesAChangedProfileAndKeepsTheOldSetUntilANewOneSucceeds() {
        UUID carol = UUID.fromString(CAROL);
        UUID first = queueFor(carol);
        TestSupport.answer(connector, TestSupport.done(first, CAROL, Kind.PROFILE, 1));
        agentSees(carol, TestSupport.fingerprint(carol));
        assertEquals(0, agentRun(), "unchanged");

        Fingerprint changed = TestSupport.fingerprintOf("carol watched something");
        TestSupport.setFingerprint(CAROL, changed);
        agentSees(carol, changed);
        assertEquals(1, agentRun());
        UUID failing = TestSupport.pendingProfile(CAROL);
        TestSupport.answer(connector, TestSupport.failed(failing, CAROL, Kind.PROFILE, "Ollama isn't reachable"));
        assertNull(TestSupport.reload(failing), "failed profile requests are deleted");
        assertEquals(RecommendationRequest.Status.DONE, TestSupport.reload(first).status, "the old set stays");
        assertEquals(1, TestSupport.state(CAROL).consecutiveFailures);
        assertEquals(0, agentRun(), "backing off");

        QuarkusTransaction.requiringNew().run(() -> ProfileState.<ProfileState>findById(carol).lastFailureAt = Instant.now().minus(Duration.ofMinutes(31)));
        assertEquals(1, agentRun(), "retried after the back-off");
        UUID second = TestSupport.pendingProfile(CAROL);
        TestSupport.answer(connector, TestSupport.done(second, CAROL, Kind.PROFILE, 2));
        assertNull(TestSupport.reload(first), "the old set goes once the new one is stored");
        assertEquals(0, TestSupport.state(CAROL).consecutiveFailures);
        assertEquals(changed.root(), TestSupport.state(CAROL).inputHash);
    }

    // The sweeper.

    @Test
    void failsRequestsStillPendingAfterAnHourAndDeletesOldFailures() {
        String user = "20000000-0000-0000-0000-000000000001";
        UUID stuck = TestSupport.insertRequest(user, Kind.PROMPT, RecommendationRequest.Status.PENDING, Instant.now().minus(Duration.ofMinutes(61)));
        UUID waiting = TestSupport.insertRequest(user, Kind.PROMPT, RecommendationRequest.Status.PENDING, Instant.now().minus(Duration.ofMinutes(50)));
        UUID oldFailure = TestSupport.insertRequest(user, Kind.PROMPT, RecommendationRequest.Status.FAILED, Instant.now().minus(Duration.ofHours(2)));

        sweeper.sweep();

        RecommendationRequest swept = TestSupport.reload(stuck);
        assertEquals(RecommendationRequest.Status.FAILED, swept.status, "kept for an hour, so a returning user is told");
        assertEquals("No answer after 1 hour.", swept.error);
        assertEquals(RecommendationRequest.Status.PENDING, TestSupport.reload(waiting).status, "a long queue only makes it wait");
        assertNull(TestSupport.reload(oldFailure), "failures older than an hour are deleted");
        assertEquals(Status.FAILED, TestSupport.readyEventsFor(connector, stuck).get(0).status(), "the user is told");
    }

    @Test
    void deletesProfileRequestsThatWaitedTooLongAndCountsTheFailure() {
        String user = "20000000-0000-0000-0000-000000000002";
        UUID stuck = TestSupport.insertRequest(user, Kind.PROFILE, RecommendationRequest.Status.PENDING, Instant.now().minus(Duration.ofHours(2)));

        sweeper.sweep();

        assertNull(TestSupport.reload(stuck));
        assertEquals(1, TestSupport.state(user).consecutiveFailures);
        assertEquals("No answer after 1 hour.", TestSupport.state(user).lastError);
    }

    @Test
    void describesTheTimeoutInWords() {
        assertEquals("1 hour", PendingSweeper.describe(Duration.ofHours(1)));
        assertEquals("3 hours", PendingSweeper.describe(Duration.ofHours(3)));
        assertEquals("90 minutes", PendingSweeper.describe(Duration.ofMinutes(90)));
    }

    /** One run of the agent, in its own transaction as when it's scheduled (so it reads fresh state). */
    private int agentRun() {
        return QuarkusTransaction.requiringNew().call(agent::refreshStale);
    }

    private UUID queueFor(UUID userId) {
        return queue.forProfile(userId).id;
    }

    /** What profiles-service's list shows the agent: only this profile, last changed a while ago. */
    private void agentSees(UUID userId, Fingerprint fingerprint) {
        when(profiles.profiles(anyInt(), anyInt())).thenReturn(new ProfilesClient.Page<>(
                List.of(new ProfileChange(userId, "carol", Instant.now().minus(Duration.ofMinutes(5)), fingerprint)), 0, 100, 1, 1));
    }

    private static boolean needs(ProfileChange profile, ProfileState state, boolean pending) {
        return BackgroundAgent.needsRefresh(profile, state, pending, T, SETTINGS);
    }

    private static ProfileChange profile(Fingerprint fingerprint, Instant lastChangedAt) {
        return new ProfileChange(UUID.randomUUID(), "someone", lastChangedAt, fingerprint);
    }

    private static ProfileState state(Fingerprint fingerprint, Instant lastSuccess, int failures, Instant lastFailure) {
        ProfileState s = new ProfileState();
        if (fingerprint != null) {
            s.inputHash = fingerprint.root();
            s.ownHash = fingerprint.own();
            s.socialHash = fingerprint.social();
        }
        s.recipe = "qwen2.5:3b/p2";
        s.lastSuccessAt = lastSuccess;
        s.consecutiveFailures = failures;
        s.lastFailureAt = lastFailure;
        return s;
    }
}
