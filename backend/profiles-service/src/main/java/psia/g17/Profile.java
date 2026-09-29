package psia.g17;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
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

    @ManyToMany
    @JoinTable(name = "favorite_genre", schema = "profiles",
            joinColumns = @JoinColumn(name = "profile_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    @OrderBy("name")
    public Set<Genre> favoriteGenres = new LinkedHashSet<>();
}
