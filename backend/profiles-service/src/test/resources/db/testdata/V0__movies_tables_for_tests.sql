-- Test-only stand-ins for the movies-service tables that V1's foreign keys point at (just the columns
-- this service reads). In dev and prod they come from data/movies_dump.sql.
CREATE SCHEMA IF NOT EXISTS movies;

CREATE TABLE movies.genre (
    genre_id integer     PRIMARY KEY,
    name     varchar(32) NOT NULL UNIQUE
);

CREATE TABLE movies.movie (
    movie_id     integer      PRIMARY KEY,
    title        varchar(255) NOT NULL,
    poster_path  varchar(64),
    release_date date
);
