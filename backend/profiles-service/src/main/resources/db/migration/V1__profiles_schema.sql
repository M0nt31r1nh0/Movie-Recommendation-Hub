-- profiles-service's own database. It references movies and genres by their movies-service ids only:
-- they live in movies-service's database, which no other service may access, so there are no foreign
-- keys to them. The service checks ids against the movies API before storing them.
--
-- (This file and V2 were rewritten when profiles moved out of the movies database into their own.
-- Flyway never ran the earlier versions against this database, so their checksums don't apply here.)

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
    genre_id   integer NOT NULL,                        -- a movies-service genre id
    PRIMARY KEY (profile_id, genre_id)
);

CREATE TABLE profiles.watched_movie (
    profile_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    movie_id   integer     NOT NULL,                    -- a movies-service movie id
    watched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, movie_id)
);

CREATE TABLE profiles.follow (
    follower_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    followed_id uuid        NOT NULL REFERENCES profiles.profile (profile_id) ON DELETE CASCADE,
    followed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followed_id),
    CONSTRAINT follow_not_self CHECK (follower_id <> followed_id)
);

-- "Who follows this profile" reads by followed_id; the primary key covers the other direction.
CREATE INDEX idx_follow_followed ON profiles.follow (followed_id);
