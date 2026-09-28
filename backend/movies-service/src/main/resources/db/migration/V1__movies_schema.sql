-- Base schema, identical to the tables in data/schema.sql and data/movies_dump.sql
-- (without the CSV staging table, which the service doesn't use).
--
-- Databases restored from the dump already have this schema: Flyway baselines them at
-- version 1 and never runs this file there. It only runs on an empty database, e.g. in tests.

CREATE TABLE movies.genre (
    genre_id integer     PRIMARY KEY,
    name     varchar(32) NOT NULL UNIQUE
);

CREATE TABLE movies.movie (
    movie_id     integer      PRIMARY KEY,
    title        varchar(255) NOT NULL,
    description  text,
    poster_path  varchar(64),
    rating       numeric(3,1),
    rating_count integer,
    release_date date,
    CONSTRAINT movie_rating_chk       CHECK (rating BETWEEN 0 AND 10),
    CONSTRAINT movie_rating_count_chk CHECK (rating_count >= 0)
);

COMMENT ON COLUMN movies.movie.rating IS
    'Out of 10, as TMDB stores it. For a 5-star display use round(rating / 2 * 2) / 2.';

CREATE TABLE movies.movie_genre (
    movie_id integer NOT NULL REFERENCES movies.movie (movie_id) ON DELETE CASCADE,
    genre_id integer NOT NULL REFERENCES movies.genre (genre_id),
    PRIMARY KEY (movie_id, genre_id)
);

CREATE INDEX idx_movie_genre_genre  ON movies.movie_genre (genre_id);
CREATE INDEX idx_movie_title        ON movies.movie (lower(title));
CREATE INDEX idx_movie_rating       ON movies.movie (rating DESC NULLS LAST);
CREATE INDEX idx_movie_release_date ON movies.movie (release_date DESC NULLS LAST);

CREATE VIEW movies.movie_card AS
SELECT m.movie_id,
       m.title,
       m.description,
       'https://image.tmdb.org/t/p/w500' || m.poster_path AS poster_url,
       m.rating,
       round(m.rating / 2, 1)                             AS rating_out_of_5,
       m.rating_count,
       m.release_date,
       (SELECT array_agg(g.name ORDER BY g.name)
          FROM movies.movie_genre mg
          JOIN movies.genre g USING (genre_id)
         WHERE mg.movie_id = m.movie_id)                  AS categories
FROM   movies.movie m;
