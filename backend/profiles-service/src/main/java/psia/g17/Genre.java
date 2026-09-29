package psia.g17;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** A movie category, owned by movies-service; read-only here. */
@Entity
@Immutable
@Table(name = "genre", schema = "movies")
public class Genre extends PanacheEntityBase {

    @Id
    @Column(name = "genre_id")
    public Integer id;

    public String name;
}
