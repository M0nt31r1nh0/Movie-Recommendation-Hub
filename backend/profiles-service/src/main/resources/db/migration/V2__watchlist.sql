-- Movies a profile wants to watch later. Only their owner sees them; marking a movie as watched
-- removes it from here (see ProfileResource).
CREATE TABLE profiles.watchlist (
    profile_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    movie_id   integer     NOT NULL,                    -- a movies-service movie id
    added_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, movie_id)
);
