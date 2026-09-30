-- recommendation-service's own database. Users and movies are referenced by their Keycloak and
-- movies-service ids only: that data belongs to other services, so there are no foreign keys to it.

-- One request for ten recommendations: for a user's profile (kind PROFILE, e.g. from the background
-- agent) or for something they typed (kind PROMPT). recommendation-worker fills it in via Kafka.
CREATE TABLE recommendations.request (
    request_id   uuid         PRIMARY KEY,
    user_id      uuid         NOT NULL,                 -- the Keycloak user id
    kind         varchar(16)  NOT NULL CHECK (kind IN ('PROFILE', 'PROMPT')),
    prompt       varchar(300),                          -- what the user typed, for PROMPT
    status       varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    error        varchar(500),
    model        varchar(100),                          -- the language model that answered
    duration_ms  bigint,                                -- how long the worker took
    created_at   timestamptz  NOT NULL DEFAULT now(),
    completed_at timestamptz
);

-- "This user's latest profile recommendations" and "their typed requests, newest first".
CREATE INDEX idx_request_user_kind_created ON recommendations.request (user_id, kind, created_at DESC);
-- The sweeper looks for requests stuck in PENDING.
CREATE INDEX idx_request_pending ON recommendations.request (created_at) WHERE status = 'PENDING';

-- The ten movies of a finished request, in order.
CREATE TABLE recommendations.request_item (
    request_id uuid         NOT NULL REFERENCES recommendations.request (request_id) ON DELETE CASCADE,
    position   integer      NOT NULL,
    movie_id   integer      NOT NULL,                   -- a movies-service movie id
    reason     varchar(300) NOT NULL,
    PRIMARY KEY (request_id, position)
);
