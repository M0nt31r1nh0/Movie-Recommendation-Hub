-- Sample data for the test profile only.
insert into movies.genre (genre_id, name) values (16, 'Animation'), (18, 'Drama'), (80, 'Crime');

insert into movies.movie (movie_id, title, poster_path, release_date) values
    (1, 'The Godfather', '/godfather.jpg', '1972-03-14'),
    (2, 'Pulp Fiction', '/pulp.jpg', '1994-09-10'),
    (3, 'Spirited Away', '/spirited.jpg', '2001-07-20');

-- Ids as Keycloak would issue them (see TestUsers).
insert into profiles.profile (profile_id, username) values
    ('a1111111-1111-1111-1111-111111111111', 'alice'),
    ('b2222222-2222-2222-2222-222222222222', 'bob'),
    ('c3333333-3333-3333-3333-333333333333', 'carol');
