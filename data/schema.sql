-- ============================================================================
--  The Movies Dataset — minimal PostgreSQL schema
-- ============================================================================
--  Scope: title, description, poster image, category (genre), rating.
--  All of it comes from data/movies_metadata.csv; the other four CSVs
--  (ratings, credits, keywords, links) are not needed and are not modelled.
--
--  Source facts that shape this schema (measured on the actual file):
--    * 45 466 data rows, of which 3 are column-shifted garbage (non-numeric id)
--      and 29 ids repeat  ->  45 433 usable movies after dedup on id
--    * each shifted record is split across 2 physical lines (10 + 15 fields),
--      so COPY cannot read the file at all until they are removed; that costs
--      3 movies (ids 82663, 122662, 249260) -> 45 430 rows actually loaded
--    * 959 movies have no description and 383 no poster -> both NULLable
--      (5 of those descriptions are whitespace-only and btrim()ed to NULL)
--    * 20 distinct genres; a movie has 0..8 of them (avg 2.0, 2 442 have none),
--      so genre is an m:n bridge, not a column
--    * poster_path is a relative path ('/rhIRb....jpg'), max length 32
--    * vote_average is 0.0-10.0 with one decimal; vote_count up to 14 075
--    * title max 105 chars, overview is free text (no useful bound)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS movies;
SET search_path = movies, public;


-- ---------------------------------------------------------------------------
--  Staging: mirrors the CSV header one-to-one, every column text.
--  The raw file cannot be COPYed into typed columns directly (empty strings in
--  numeric fields, Python-literal lists in `genres`, 3 malformed rows), so load
--  here first, then transform into the tables below.
--    \copy staging_movies_metadata FROM 'data/movies_metadata.csv' WITH (FORMAT csv, HEADER true)
-- ---------------------------------------------------------------------------

CREATE TABLE staging_movies_metadata (
    adult                 text,
    belongs_to_collection text,
    budget                text,
    genres                text,   -- "[{'id': 16, 'name': 'Animation'}, ...]"
    homepage              text,
    id                    text,
    imdb_id               text,
    original_language     text,
    original_title        text,
    overview              text,
    popularity            text,
    poster_path           text,
    production_companies  text,
    production_countries  text,
    release_date          text,
    revenue               text,
    runtime               text,
    spoken_languages      text,
    status                text,
    tagline               text,
    title                 text,
    video                 text,
    vote_average          text,
    vote_count            text
);


-- ---------------------------------------------------------------------------
--  Core
-- ---------------------------------------------------------------------------

-- 20 categories, ids are TMDB's own.
CREATE TABLE genre (
    genre_id integer     PRIMARY KEY,
    name     varchar(32) NOT NULL UNIQUE
);

CREATE TABLE movie (
    movie_id     integer      PRIMARY KEY,   -- TMDB id, max observed 469 172
    title        varchar(255) NOT NULL,   -- COALESCE(title, original_title) at load
    description  text,                       -- movies_metadata.overview
    poster_path  varchar(64),                -- relative; full URL =
                                             -- https://image.tmdb.org/t/p/w500 || poster_path
    rating       numeric(3,1),               -- TMDB vote_average, 0.0 .. 10.0
    rating_count integer,                    -- TMDB vote_count, how many votes back `rating`
    release_date date,                       -- cheap to keep, and every list view wants a year
    CONSTRAINT movie_rating_chk       CHECK (rating BETWEEN 0 AND 10),
    CONSTRAINT movie_rating_count_chk CHECK (rating_count >= 0)
);

COMMENT ON COLUMN movie.rating IS
    'Out of 10, as TMDB stores it. For a 5-star display use round(rating / 2 * 2) / 2.';

-- A movie carries several categories (avg 2.0, up to 8), so this is a bridge.
CREATE TABLE movie_genre (
    movie_id integer NOT NULL REFERENCES movie (movie_id) ON DELETE CASCADE,
    genre_id integer NOT NULL REFERENCES genre (genre_id),
    PRIMARY KEY (movie_id, genre_id)
);


-- ---------------------------------------------------------------------------
--  Indexes
-- ---------------------------------------------------------------------------

CREATE INDEX idx_movie_genre_genre  ON movie_genre (genre_id);          -- browse by category
CREATE INDEX idx_movie_title        ON movie (lower(title));            -- case-insensitive lookup
CREATE INDEX idx_movie_rating       ON movie (rating DESC NULLS LAST);  -- "top rated"
CREATE INDEX idx_movie_release_date ON movie (release_date DESC NULLS LAST);


-- ---------------------------------------------------------------------------
--  One flat row per movie, categories folded into an array — what a list or
--  detail page actually reads.
-- ---------------------------------------------------------------------------

CREATE VIEW movie_card AS
SELECT m.movie_id,
       m.title,
       m.description,
       'https://image.tmdb.org/t/p/w500' || m.poster_path AS poster_url,
       m.rating,
       round(m.rating / 2, 1)                             AS rating_out_of_5,
       m.rating_count,
       m.release_date,
       (SELECT array_agg(g.name ORDER BY g.name)
          FROM movie_genre mg
          JOIN genre g USING (genre_id)
         WHERE mg.movie_id = m.movie_id)                  AS categories
FROM   movie m;
