package psia.g17;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/** The few movie fields a profile shows, owned by movies-service; read-only here. */
@Entity
@Immutable
@BatchSize(size = 100)
@Table(name = "movie", schema = "movies")
public class MovieRef extends PanacheEntityBase {

    @Id
    @Column(name = "movie_id")
    public Integer id;

    public String title;

    @Column(name = "poster_path")
    public String posterPath;

    @Column(name = "release_date")
    public LocalDate releaseDate;
}
