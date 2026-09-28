-- Sample data for the test profile only (dev and prod use the real movies database).
insert into movies.genre (genre_id, name) values (16, 'Animation');
insert into movies.genre (genre_id, name) values (53, 'Thriller');
insert into movies.genre (genre_id, name) values (80, 'Crime');
insert into movies.genre (genre_id, name) values (878, 'Science Fiction');
insert into movies.movie (movie_id, title, description, poster_path, rating, rating_count, release_date) values (1, 'The Godfather', 'The aging patriarch of an organized crime dynasty transfers control to his reluctant son.', '/godfather.jpg', 8.5, 6024, '1972-03-14');
insert into movies.movie (movie_id, title, description, poster_path, rating, rating_count, release_date) values (2, 'Pulp Fiction', 'The lives of two mob hitmen, a boxer and a pair of diner bandits intertwine.', '/pulp.jpg', 8.3, 8670, '1994-09-10');
insert into movies.movie (movie_id, title, description, poster_path, rating, rating_count, release_date) values (3, 'Spirited Away', 'A girl wanders into a world ruled by gods, witches and spirits.', '/spirited.jpg', 8.3, 3968, '2001-07-20');
insert into movies.movie (movie_id, title, description, poster_path, rating, rating_count, release_date) values (4, 'Inception', 'A thief who steals corporate secrets through dream-sharing technology.', '/inception.jpg', 8.1, 14075, '2010-07-14');
insert into movies.movie (movie_id, title, description, poster_path, rating, rating_count, release_date) values (5, 'Untitled Project', null, null, null, 0, null);
insert into movies.movie_genre (movie_id, genre_id) values (1, 80);
insert into movies.movie_genre (movie_id, genre_id) values (2, 53);
insert into movies.movie_genre (movie_id, genre_id) values (2, 80);
insert into movies.movie_genre (movie_id, genre_id) values (3, 16);
insert into movies.movie_genre (movie_id, genre_id) values (4, 878);
