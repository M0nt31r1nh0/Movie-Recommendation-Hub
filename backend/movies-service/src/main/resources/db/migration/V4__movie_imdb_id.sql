-- IMDb ids, for linking to a movie's IMDb page (https://www.imdb.com/title/<imdb_id>/).
-- The values come from the CSV staging table included in data/movies_dump.sql; about 17 movies
-- have none. Databases without that table (e.g. the tests' empty database) get the column only.
ALTER TABLE movies.movie ADD COLUMN imdb_id varchar(12);
ALTER TABLE movies.movie ADD CONSTRAINT movie_imdb_id_chk CHECK (imdb_id ~ '^tt[0-9]{7,8}$');

DO $$
BEGIN
    IF to_regclass('movies.staging_movies_metadata') IS NOT NULL THEN
        UPDATE movies.movie m
        SET    imdb_id = s.imdb_id
        FROM   (SELECT DISTINCT ON (id) id, imdb_id
                  FROM movies.staging_movies_metadata
                 WHERE imdb_id ~ '^tt[0-9]{7,8}$'
                 ORDER BY id) s
        WHERE  s.id = m.movie_id::text;
    END IF;
END $$;
