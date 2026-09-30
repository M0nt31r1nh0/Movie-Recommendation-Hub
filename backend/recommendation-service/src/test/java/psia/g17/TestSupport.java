package psia.g17;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.reactive.messaging.kafka.Record;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import psia.g17.client.MoviesClient;
import psia.g17.client.ProfilesClient;
import psia.g17.client.ProfilesClient.Fingerprint;
import psia.g17.entity.ProfileState;
import psia.g17.entity.RecommendationRequest;
import psia.g17.events.FriendWatch;
import psia.g17.events.Kind;
import psia.g17.events.ReadyEvent;
import psia.g17.events.RecommendationJob;
import psia.g17.events.RecommendationResult;
import psia.g17.events.Status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Mocks and shortcuts shared by the tests. */
public final class TestSupport {

    public static final String RECIPE = "qwen2.5:3b/p2";

    /** Fingerprints the mocked profiles-service reports; by default every user's is different. */
    private static final Map<UUID, Fingerprint> FINGERPRINTS = new ConcurrentHashMap<>();

    private TestSupport() {
    }

    /** Every user likes drama (18) and has watched movie 1; movies are "Movie <id>". */
    public static void mockProfilesAndMovies(ProfilesClient profiles, MoviesClient movies) {
        FINGERPRINTS.clear();
        when(profiles.snapshot(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return new ProfilesClient.Snapshot(id, "someone", Instant.now(), List.of(18), List.of(1), List.of(2), List.of(),
                    List.of(new FriendWatch(3, 2)), fingerprint(id));
        });
        when(movies.movies(anyList())).thenAnswer(call -> call.<List<Integer>>getArgument(0).stream()
                .map(id -> new MoviesClient.Movie(id, "Movie " + id, "/poster" + id + ".jpg", LocalDate.of(2000, 1, 1), 7.5))
                .toList());
    }

    public static Fingerprint fingerprint(UUID userId) {
        return FINGERPRINTS.computeIfAbsent(userId, id -> fingerprintOf("user " + id));
    }

    public static void setFingerprint(String userId, Fingerprint fingerprint) {
        FINGERPRINTS.put(UUID.fromString(userId), fingerprint);
    }

    public static Fingerprint fingerprintOf(String seed) {
        String own = sha256(seed + " own");
        String social = sha256(seed + " social");
        return new Fingerprint(sha256(own + ":" + social), own, social);
    }

    /** The jobs published so far for this user. */
    public static List<RecommendationJob> jobsFor(InMemoryConnector connector, String userId) {
        return connector.<Record<String, RecommendationJob>>sink("recommendation-requests").received().stream()
                .map(m -> m.getPayload())
                .filter(r -> r.key().equals(userId))
                .map(Record::value)
                .toList();
    }

    public static List<ReadyEvent> readyEventsFor(InMemoryConnector connector, UUID requestId) {
        return connector.<ReadyEvent>sink("recommendation-ready").received().stream().map(m -> m.getPayload())
                .filter(e -> e.requestId().equals(requestId)).toList();
    }

    /** Sends a result as the worker would, and waits until it's been handled. */
    public static void answer(InMemoryConnector connector, RecommendationResult result) {
        connector.source("recommendation-results").send(result);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> Optional.ofNullable(reload(result.requestId())).map(r -> r.status != RecommendationRequest.Status.PENDING).orElse(true));
    }

    /** The user's profile request being made, if any. */
    public static UUID pendingProfile(String userId) {
        return QuarkusTransaction.requiringNew().call(() -> RecommendationRequest.latest(UUID.fromString(userId), Kind.PROFILE,
                RecommendationRequest.Status.PENDING).map(r -> r.id).orElse(null));
    }

    public static RecommendationResult done(UUID requestId, String userId, Kind kind, int... movieIds) {
        List<Integer> ids = Arrays.stream(movieIds).boxed().toList();
        return new RecommendationResult(requestId, UUID.fromString(userId), kind, Status.DONE, ids, null, "qwen2.5:3b", RECIPE, 1234, Instant.now());
    }

    public static RecommendationResult failed(UUID requestId, String userId, Kind kind, String error) {
        return new RecommendationResult(requestId, UUID.fromString(userId), kind, Status.FAILED, List.of(), error, "qwen2.5:3b", RECIPE, 900000, Instant.now());
    }

    /** Saves a request directly, as if it had been made at {@code createdAt}. */
    public static UUID insertRequest(String userId, Kind kind, RecommendationRequest.Status status, Instant createdAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest r = RecommendationRequest.create(UUID.fromString(userId), kind, kind == Kind.PROMPT ? "old request" : null);
            r.status = status;
            r.createdAt = createdAt;
            if (status != RecommendationRequest.Status.PENDING) r.completedAt = createdAt;
            if (status == RecommendationRequest.Status.DONE) r.movieIds.add(42);
            r.persist();
            return r.id;
        });
    }

    /** The request as stored now, or null if it was deleted. */
    public static RecommendationRequest reload(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            RecommendationRequest r = RecommendationRequest.findById(id);
            if (r != null) r.movieIds.size(); // load them inside the transaction
            return r;
        });
    }

    public static ProfileState state(String userId) {
        return QuarkusTransaction.requiringNew().call(() -> ProfileState.<ProfileState>findById(UUID.fromString(userId)));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
