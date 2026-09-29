-- Profiles live next to the movie data, in the same database, so they can reference movies and genres
-- with real foreign keys. The movies schema must exist first: restore data/movies_dump.sql before
-- starting this service.

-- One profile per Keycloak user, created on their first request to this service.
CREATE TABLE profiles.profile (
    profile_id uuid         PRIMARY KEY,                -- the Keycloak user id (the token's "sub" claim)
    username   varchar(255) NOT NULL UNIQUE,            -- the Keycloak username, refreshed on each request
    created_at timestamptz  NOT NULL DEFAULT now()
);

-- Username search matches from the start of the name, case-insensitively.
CREATE INDEX idx_profile_username_prefix ON profiles.profile (lower(username) text_pattern_ops);

CREATE TABLE profiles.favorite_genre (
    profile_id uuid    NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    genre_id   integer NOT NULL REFERENCES movies.genre (genre_id) ON DELETE CASCADE,
    PRIMARY KEY (profile_id, genre_id)
);

CREATE TABLE profiles.watched_movie (
    profile_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    movie_id   integer     NOT NULL REFERENCES movies.movie (movie_id) ON DELETE CASCADE,
    watched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, movie_id)
);

-- Deleting a movie checks this table for references; without an index that's a full scan.
CREATE INDEX idx_watched_movie_movie ON profiles.watched_movie (movie_id);

CREATE TABLE profiles.follow (
    follower_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    followed_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    followed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followed_id),
    CONSTRAINT follow_not_self CHECK (follower_id <> followed_id)
);

-- "Who follows this profile" reads by followed_id; the primary key covers the other direction.
CREATE INDEX idx_follow_followed ON profiles.follow (followed_id);
