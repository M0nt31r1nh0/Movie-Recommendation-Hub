-- What each request was made from, and each user's current "Recommended for you" state, so the
-- background agent only recomputes when the inputs really changed (see BackgroundAgent).

-- The fingerprint of the profile snapshot a request's job used (from profiles-service): root, and
-- its own and social halves. For a typed request, input_hash also covers the (normalised) prompt.
-- recipe is the model and pipeline version the worker reported.
ALTER TABLE recommendations.request ADD COLUMN input_hash  varchar(64);
ALTER TABLE recommendations.request ADD COLUMN own_hash    varchar(64);
ALTER TABLE recommendations.request ADD COLUMN social_hash varchar(64);
ALTER TABLE recommendations.request ADD COLUMN recipe      varchar(120);

-- Finished results that identical inputs can reuse.
CREATE INDEX idx_request_done_input ON recommendations.request (input_hash, recipe) WHERE status = 'DONE';

-- One row per user: what their current profile recommendations were made from, and failed attempts
-- since (failed requests themselves are deleted), so retries back off instead of repeating.
CREATE TABLE recommendations.profile_state (
    user_id              uuid         PRIMARY KEY,         -- the Keycloak user id
    input_hash           varchar(64), -- of the current set; null until one succeeds
    own_hash             varchar(64),
    social_hash          varchar(64),
    recipe               varchar(120),
    last_success_at      timestamptz,
    last_failure_at      timestamptz,
    last_error           varchar(500),
    consecutive_failures integer      NOT NULL DEFAULT 0
);
