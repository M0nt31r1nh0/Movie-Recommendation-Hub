-- Movies a profile wants to watch later. Only their owner sees them; marking a movie as watched
-- removes it from here (see ProfileResource).
CREATE TABLE profiles.watchlist (
    profile_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    movie_id   integer     NOT NULL REFERENCES movies.movie (movie_id) ON DELETE CASCADE,
    added_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, movie_id)
);

-- Deleting a movie checks this table for references; without an index that's a full scan.
CREATE INDEX idx_watchlist_movie ON profiles.watchlist (movie_id);
