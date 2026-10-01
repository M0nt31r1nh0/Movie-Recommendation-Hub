-- Search by meaning (see MovieEmbeddings): each movie's embedding, a vector an embedding model computes
-- from its title, year, genres and description; movies about similar things have nearby vectors.
-- Filled in the background, most-voted movies first. model says which model made a vector, so a new
-- model's vectors replace the old ones. No index: an exact search over ~9,000 vectors is fast enough.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE movies.movie_embedding (
    movie_id   integer      PRIMARY KEY REFERENCES movies.movie (movie_id) ON DELETE CASCADE,
    model      varchar(100) NOT NULL,
    embedding  vector       NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);
