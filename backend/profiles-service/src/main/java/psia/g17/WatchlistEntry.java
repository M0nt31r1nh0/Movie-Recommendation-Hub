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

/** A movie a profile wants to watch later. Rows are written with native SQL (see ProfileResource). */
@Entity
@Table(name = "watchlist", schema = "profiles")
public class WatchlistEntry extends PanacheEntityBase {

    @EmbeddedId
    public Key id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", insertable = false, updatable = false)
    public MovieRef movie;

    @Column(name = "added_at", insertable = false, updatable = false)
    public Instant addedAt;

    @Embeddable
    public record Key(@Column(name = "profile_id") UUID profileId, @Column(name = "movie_id") Integer movieId)
            implements Serializable {
    }
}
