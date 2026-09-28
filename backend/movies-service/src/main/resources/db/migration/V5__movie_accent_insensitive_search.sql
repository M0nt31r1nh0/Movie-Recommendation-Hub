-- Accent-insensitive search: "amelie" finds "Amélie", and "Amélie" finds "Amelie".
--
-- unaccent ships with PostgreSQL. Its unaccent() function isn't marked immutable, which generated
-- columns and index expressions require, so it's wrapped in one that is (the dictionary is fixed).
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

CREATE FUNCTION movies.immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

-- PostgreSQL 16 can't change a generated column's expression, so the two search columns from V2 are
-- rebuilt with accents removed. They only hold values computed from title and description, so no
-- data is lost; dropping them also drops their indexes, which are recreated below.
ALTER TABLE movies.movie DROP COLUMN search_vector, DROP COLUMN title_vector;

ALTER TABLE movies.movie ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', movies.immutable_unaccent(title)), 'A') ||
    setweight(to_tsvector('english', movies.immutable_unaccent(coalesce(description, ''))), 'B')
) STORED;

ALTER TABLE movies.movie ADD COLUMN title_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', movies.immutable_unaccent(title))
) STORED;

CREATE INDEX idx_movie_search      ON movies.movie USING GIN (search_vector);
CREATE INDEX idx_movie_title_words ON movies.movie USING GIN (title_vector);

-- The typo-tolerant title match compares unaccented titles too.
DROP INDEX movies.idx_movie_title_trgm;
CREATE INDEX idx_movie_title_trgm ON movies.movie USING GIN (movies.immutable_unaccent(title) public.gin_trgm_ops);
