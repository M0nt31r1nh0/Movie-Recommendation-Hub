-- When a profile's recommendation inputs (favourite genres, watched movies, watchlist, follows) last
-- changed, so recommendation-service's background agent can tell whose recommendations are stale.
ALTER TABLE profiles.profile ADD COLUMN last_changed_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX idx_profile_last_changed ON profiles.profile (last_changed_at);
