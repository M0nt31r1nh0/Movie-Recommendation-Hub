-- Sample data for the test profile only.
-- Movies and genres come from a mocked movies-service (see ProfileResourceTest).

-- Ids as Keycloak would issue them (see TestUsers).
insert into profiles.profile (profile_id, username) values
    ('a1111111-1111-1111-1111-111111111111', 'alice'),
    ('b2222222-2222-2222-2222-222222222222', 'bob'),
    ('c3333333-3333-3333-3333-333333333333', 'carol');
