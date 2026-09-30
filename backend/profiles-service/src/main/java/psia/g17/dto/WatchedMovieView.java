package psia.g17.dto;

import java.time.Instant;
import java.time.LocalDate;

public record WatchedMovieView(int movieId, String title, String posterPath, LocalDate releaseDate,
                               Instant watchedAt) {
}
