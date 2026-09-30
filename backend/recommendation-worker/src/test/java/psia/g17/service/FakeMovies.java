package psia.g17.service;

import psia.g17.client.MoviesClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A tiny movies-service that really filters, so the pipeline's queries and fallbacks can be checked. */
class FakeMovies implements MoviesClient {

    // Filters like movies-service, and keeps its best-first order; preferred genres and friends' bonuses
    // aren't scored here (movies-service's own tests cover them), only recorded in queries.

    static final Genre DRAMA = new Genre(18, "Drama");
    static final Genre HORROR = new Genre(27, "Horror");
    static final Genre COMEDY = new Genre(35, "Comedy");
    static final Genre SCIFI = new Genre(878, "Science Fiction");

    /** Best regarded first, as movies-service ranks candidates. */
    static final List<Movie> MOVIES = List.of(
            movie(1, "The Godfather", 1972, "A crime family patriarch hands over control.", DRAMA),
            movie(2, "Alien", 1979, "A spaceship crew is hunted by a creature.", HORROR, SCIFI),
            movie(3, "The Thing", 1982, "A shape-shifter terrorises an Antarctic research station.", HORROR, SCIFI),
            movie(4, "Poltergeist", 1982, "A family's haunted house is full of ghosts.", HORROR),
            movie(5, "Airplane!", 1980, "A comedy of disasters aboard a passenger jet.", COMEDY),
            movie(6, "Drama Six", 1990, "Drama.", DRAMA),
            movie(7, "Drama Seven", 1991, "Drama.", DRAMA),
            movie(8, "Drama Eight", 1992, "Drama.", DRAMA),
            movie(9, "Drama Nine", 1993, "Drama.", DRAMA),
            movie(10, "Drama Ten", 1994, "Drama.", DRAMA),
            movie(11, "Drama Eleven", 1995, "Drama.", DRAMA),
            movie(12, "Drama Twelve", 1996, "Drama.", DRAMA),
            movie(20, "A Friend's Pick", 2001, "Something a friend liked.", DRAMA));

    private static final Map<Integer, Movie> BY_ID = MOVIES.stream().collect(Collectors.toMap(Movie::id, Function.identity()));

    final List<CandidateQuery> queries = new ArrayList<>();

    @Override
    public List<Genre> genres() {
        return List.of(DRAMA, HORROR, COMEDY, SCIFI);
    }

    @Override
    public List<Movie> movies(List<Integer> ids) {
        return ids.stream().map(BY_ID::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Movie> candidates(CandidateQuery q) {
        queries.add(q);
        return MOVIES.stream()
                .filter(m -> q.onlyIds() == null || q.onlyIds().contains(m.id()))
                .filter(m -> m.id() != 20 || q.onlyIds() != null) // only reachable as a friend's pick
                .filter(m -> q.genreIds() == null || m.genres().stream().anyMatch(g -> q.genreIds().contains(g.id())))
                .filter(m -> q.yearFrom() == null || m.releaseDate().getYear() >= q.yearFrom())
                .filter(m -> q.yearTo() == null || m.releaseDate().getYear() <= q.yearTo())
                .filter(m -> q.text() == null || List.of(q.text().split(" or ")).stream().anyMatch(w -> m.description().toLowerCase().contains(w)))
                .filter(m -> q.excludeIds() == null || !q.excludeIds().contains(m.id()))
                .limit(q.limit() != null ? q.limit() : 40)
                .toList();
    }

    private static Movie movie(int id, String title, int year, String description, Genre... genres) {
        return new Movie(id, title, description, LocalDate.of(year, 1, 1), 7.0, 500, List.of(genres));
    }
}
