package psia.g17.dto;

import java.time.LocalDate;

public record MovieView(int id, String title, String posterPath, LocalDate releaseDate, Double rating) {
}
