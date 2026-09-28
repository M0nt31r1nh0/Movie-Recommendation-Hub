-- Typo-tolerant title search ("godfater" -> "The Godfather") using trigram similarity.
-- pg_trgm ships with PostgreSQL; it goes in public so its functions and operators are on
-- the default search_path of every connection.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX idx_movie_title_trgm ON movies.movie USING GIN (title public.gin_trgm_ops);
