-- Recommendations no longer come with the model's reason for each movie: a request_item row is just
-- a request's movie at a position.
ALTER TABLE recommendations.request_item DROP COLUMN reason;
