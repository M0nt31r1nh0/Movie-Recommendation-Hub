package psia.g17.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@BatchSize(size = 100)
@Table(name = "profile", schema = "profiles")
public class Profile extends PanacheEntityBase {

    /** The Keycloak user id. */
    @Id
    @Column(name = "profile_id")
    public UUID id;

    @Column(nullable = false)
    public String username;

    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;

    /** When favourite genres, watched movies, the watchlist or follows last changed (see ProfileResource#touch). */
    @Column(name = "last_changed_at", insertable = false, updatable = false)
    public Instant lastChangedAt;

    /** movies-service genre ids; their names come from its API (see MovieCatalog). */
    @ElementCollection
    @CollectionTable(name = "favorite_genre", schema = "profiles", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "genre_id")
    public Set<Integer> favoriteGenreIds = new LinkedHashSet<>();
}
