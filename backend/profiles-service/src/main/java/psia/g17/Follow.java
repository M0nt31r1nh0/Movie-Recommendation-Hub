package psia.g17;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** One profile following another. Rows are written with native SQL (see ProfileResource). */
@Entity
@Table(name = "follow", schema = "profiles")
public class Follow extends PanacheEntityBase {

    @EmbeddedId
    public Key id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_id", insertable = false, updatable = false)
    public Profile follower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "followed_id", insertable = false, updatable = false)
    public Profile followed;

    @Column(name = "followed_at", insertable = false, updatable = false)
    public Instant followedAt;

    @Embeddable
    public record Key(@Column(name = "follower_id") UUID followerId, @Column(name = "followed_id") UUID followedId)
            implements Serializable {
    }
}
