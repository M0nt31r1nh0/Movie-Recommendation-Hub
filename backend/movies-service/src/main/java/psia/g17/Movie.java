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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "movie", schema = "movies")
public class Movie extends PanacheEntityBase {

    @Id
    @Column(name = "movie_id")
    public Integer id;

    @Column(nullable = false)
    public String title;

    @Column(columnDefinition = "text")
    public String description;

    @Column(name = "poster_path", length = 64)
    public String posterPath;

    /** Out of 10, as TMDB stores it. */
    @Column(precision = 3, scale = 1)
    public BigDecimal rating;

    @Column(name = "rating_count")
    public Integer ratingCount;

    @Column(name = "release_date")
    public LocalDate releaseDate;

    /** E.g. "tt0068646"; the movie's page is https://www.imdb.com/title/{imdbId}/. Null for a few movies. */
    @Column(name = "imdb_id", length = 12)
    public String imdbId;

    // Batch-loaded so a page of movies costs one extra query for all their genres, not one per movie.
    @ManyToMany
    @JoinTable(name = "movie_genre", schema = "movies",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    @OrderBy("name")
    @BatchSize(size = 100)
    public List<Genre> genres;
}
