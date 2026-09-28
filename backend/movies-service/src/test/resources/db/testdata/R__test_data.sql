-- Sample data for the test profile only; dev and prod use the real movies database.
insert into movies.genre (genre_id, name) values
    (16, 'Animation'), (53, 'Thriller'), (80, 'Crime'), (878, 'Science Fiction'), (27, 'Horror');

insert into movies.movie (movie_id, title, description, poster_path, rating, rating_count, release_date) values
    (1, 'The Godfather', 'The aging patriarch of an organized crime dynasty transfers control to his reluctant son.', '/godfather.jpg', 8.5, 6024, '1972-03-14'),
    (2, 'Pulp Fiction', 'The lives of two mob hitmen, a boxer and a pair of diner bandits intertwine.', '/pulp.jpg', 8.3, 8670, '1994-09-10'),
    (3, 'Spirited Away', 'A girl wanders into a world ruled by gods, witches and spirits.', '/spirited.jpg', 8.3, 3968, '2001-07-20'),
    (4, 'Inception', 'A thief who steals corporate secrets through dream-sharing technology.', '/inception.jpg', 8.1, 14075, '2010-07-14'),
    (5, 'Untitled Project', null, null, null, 0, null),
    (6, 'It', 'A group of kids face a shape-shifting clown.', '/it.jpg', 7.2, 3000, '2017-09-06'),
    (7, 'Space Station 76', 'A 1970s vision of the future, aboard a crew in orbit.', '/ss76.jpg', 5.9, 92, '2014-03-08'),
    (8, 'Solaris', 'A psychologist is sent to a space station orbiting a distant planet.', '/solaris.jpg', 7.0, 364, '1972-03-20');

insert into movies.movie_genre (movie_id, genre_id) values
    (1, 80), (2, 53), (2, 80), (3, 16), (4, 878), (6, 27), (7, 878), (8, 878);
