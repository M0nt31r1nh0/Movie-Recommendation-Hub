package psia.g17.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What {@code POST /movies/candidates} looks for; every field is optional.
 *
 * @param genreIds          movies in at least one of these genres; each one they're in adds to the score
 * @param preferredGenreIds genres worth more (e.g. the user's favourites): each one a movie is in adds
 *                          as much again; they don't filter
 * @param yearFrom          released in or after this year
 * @param yearTo            released in or before this year
 * @param text              words to match in title or description, in the search syntax of
 *                          {@code /movies/search} ({@code "haunted or ghost or house"} matches any of them)
 * @param friendWatchers    movies people the user follows watched, with how many of them: a bonus for
 *                          each (up to 3); they don't filter
 * @param onlyIds           only these movies (e.g. just the friends' ones), still filtered and ranked
 * @param excludeIds        movies to leave out, e.g. ones the user has already seen
 * @param minRatingCount    only movies with at least this many votes (default 50), so obscure films with
 *                          a handful of perfect scores don't crowd out good ones
 * @param limit             how many to return (default 40, at most 100)
 */
public record CandidateQuery(
        @Size(max = 20) List<Integer> genreIds,
        @Size(max = 20) List<Integer> preferredGenreIds,
        @Min(1800) @Max(2100) Integer yearFrom,
        @Min(1800) @Max(2100) Integer yearTo,
        @Size(max = 200) String text,
        @Size(max = 200) List<@Valid FriendWatch> friendWatchers,
        @Size(max = 200) List<Integer> onlyIds,
        @Size(max = 5000) List<Integer> excludeIds,
        @Min(0) Integer minRatingCount,
        @Min(1) @Max(100) Integer limit) {

    public static final int DEFAULT_MIN_RATING_COUNT = 50;
    public static final int DEFAULT_LIMIT = 40;
}
