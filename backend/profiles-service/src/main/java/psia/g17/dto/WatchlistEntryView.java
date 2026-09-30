package psia.g17.dto;

import java.time.Instant;
import java.time.LocalDate;

public record WatchlistEntryView(int movieId, String title, String posterPath, LocalDate releaseDate,
                                 Instant addedAt) {
}
