package psia.g17.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Per user: the fingerprint and recipe their current "Recommended for you" set was made from, and
 * failed attempts since, which the background agent backs off from (failed requests are deleted).
 */
@Entity
@Table(name = "profile_state", schema = "recommendations")
public class ProfileState extends PanacheEntityBase {

    @Id
    @Column(name = "user_id")
    public UUID userId;

    @Column(name = "input_hash")
    public String inputHash;

    @Column(name = "own_hash")
    public String ownHash;

    @Column(name = "social_hash")
    public String socialHash;

    public String recipe;

    @Column(name = "last_success_at")
    public Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    public Instant lastFailureAt;

    @Column(name = "last_error")
    public String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    public int consecutiveFailures;

    public static ProfileState of(UUID userId) {
        ProfileState state = findById(userId);
        if (state == null) {
            state = new ProfileState();
            state.userId = userId;
            state.persist();
        }
        return state;
    }

    /** Whether the last attempt failed and it's too early to try again (waits double per failure). */
    public boolean backingOff(Instant now, Duration first, Duration longest) {
        if (consecutiveFailures == 0 || lastFailureAt == null) return false;
        Duration wait = first.multipliedBy(1L << Math.min(consecutiveFailures - 1, 20));
        return now.isBefore(lastFailureAt.plus(wait.compareTo(longest) > 0 ? longest : wait));
    }
}
