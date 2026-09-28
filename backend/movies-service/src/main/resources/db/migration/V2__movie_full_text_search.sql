-- Full-text search over title and description.
--
-- search_vector: English word roots ("dreams" -> 'dream'), stop words removed. Title
-- words are weighted A and description words B, so the search can tell title matches apart.
ALTER TABLE movies.movie ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', title), 'A') ||
    setweight(to_tsvector('english', coalesce(description, '')), 'B')
) STORED;

-- title_vector: title words exactly as written, nothing removed. English search drops
-- stop words, which makes titles such as "It", "Up" or "Her" unfindable on search_vector.
ALTER TABLE movies.movie ADD COLUMN title_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', title)
) STORED;

CREATE INDEX idx_movie_search      ON movies.movie USING GIN (search_vector);
CREATE INDEX idx_movie_title_words ON movies.movie USING GIN (title_vector);
