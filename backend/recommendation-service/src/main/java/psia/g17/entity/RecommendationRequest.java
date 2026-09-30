package psia.g17.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import psia.g17.events.Kind;
import psia.g17.events.Status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A request for ten recommendations, and once the worker answers, the recommendations themselves. */
@Entity
@Table(name = "request", schema = "recommendations")
public class RecommendationRequest extends PanacheEntityBase {

    public enum Status { PENDING, DONE, FAILED }

    @Id
    @Column(name = "request_id")
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Kind kind;

    public String prompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status;

    public String error;

    public String model;

    /** The model and pipeline version the worker reported. */
    public String recipe;

    /** The fingerprint of the snapshot the job used (for typed requests, with the prompt); see profile_state. */
    @Column(name = "input_hash")
    public String inputHash;

    @Column(name = "own_hash")
    public String ownHash;

    @Column(name = "social_hash")
    public String socialHash;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    /** The recommended movies (movies-service ids), best first; empty until it's DONE. */
    @ElementCollection
    @CollectionTable(name = "request_item", schema = "recommendations", joinColumns = @JoinColumn(name = "request_id"))
    @OrderColumn(name = "position")
    @Column(name = "movie_id", nullable = false)
    public List<Integer> movieIds = new ArrayList<>();

    public static RecommendationRequest create(UUID userId, Kind kind, String prompt) {
        RecommendationRequest r = new RecommendationRequest();
        r.id = UUID.randomUUID();
        r.userId = userId;
        r.kind = kind;
        r.prompt = prompt;
        r.status = Status.PENDING;
        r.createdAt = Instant.now();
        return r;
    }

    /** The user's newest request of this kind, in any status. */
    public static Optional<RecommendationRequest> latest(UUID userId, Kind kind) {
        return find("userId = ?1 and kind = ?2", Sort.by("createdAt", Sort.Direction.Descending), userId, kind).firstResultOptional();
    }

    /** The user's newest request of this kind with this status. */
    public static Optional<RecommendationRequest> latest(UUID userId, Kind kind, Status status) {
        return find("userId = ?1 and kind = ?2 and status = ?3", Sort.by("createdAt", Sort.Direction.Descending), userId, kind, status)
                .firstResultOptional();
    }

    public static long pending(UUID userId, Kind kind) {
        return count("userId = ?1 and kind = ?2 and status = ?3", userId, kind, Status.PENDING);
    }

    /** The newest finished request of this kind made from these inputs by this recipe, for any user. */
    public static Optional<RecommendationRequest> doneWith(Kind kind, String inputHash, String recipe) {
        return find("kind = ?1 and status = ?2 and inputHash = ?3 and recipe = ?4",
                Sort.by("completedAt", Sort.Direction.Descending), kind, Status.DONE, inputHash, recipe).firstResultOptional();
    }

    /** The recipe of the newest finished request: what the workers currently use, as far as this service knows. */
    public static Optional<String> currentRecipe() {
        return find("status = ?1 and recipe is not null", Sort.by("completedAt", Sort.Direction.Descending), Status.DONE)
                .firstResultOptional().map(r -> ((RecommendationRequest) r).recipe);
    }
}
