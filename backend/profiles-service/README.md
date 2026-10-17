# profiles-service

The profiles API holds each user's favourite genres, watched movies, private watch-later list and follows. A profile is created on the user's first request, from their Keycloak token. Movies and genres are stored as ids only and checked against movies-service. It also serves recommendation-service the profile snapshots and fingerprints that recommendations are made from (`/internal/profiles`, role `profiles-reader`).

- **Port:** 8081 in dev (Swagger UI at `http://localhost:8081/q/swagger-ui`)
- **Database:** the profiles database (`profiles-db` in `infra/compose.yaml`, `localhost:5433`), schema `profiles`, with migrations in `src/main/resources/db/migration/`
- **Needs:** that database, Keycloak, and movies-service for anything involving movies or genres

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs Docker: the tests start their own PostgreSQL; movies-service is mocked
```

### Endpoints

All need an access token, and return `401` without one. Anyone logged in can read any profile except its watchlist, which only its owner sees. Changes always go through `/profiles/me/...`, so users can only change their own. Wherever a path takes a profile id, `me` means the logged-in user.

| Method | Path | Does |
|---|---|---|
| `GET` | `/profiles?q=<prefix>` | Profiles whose username starts with `q` (any case), or all of them, by username. `page` and `size` as for `/movies` |
| `GET` | `/profiles/{id}` | One profile, as `{ id, username, createdAt, favoriteGenres, watchedCount, watchlistCount, followingCount, followersCount, me, followedByMe }`, or `404`. `watchlistCount` is only set on your own profile (`null` on others) |
| `PUT` | `/profiles/me/favorite-genres` | Replaces your favourite genres with a JSON array of genre ids, e.g. `[18, 878]`. Unknown ids return `400` |
| `GET` | `/profiles/{id}/watched` | The movies a profile has seen, most recently marked first (paginated) |
| `GET` | `/profiles/me/watched/{movieId}` | `200` if you've marked the movie as seen, `404` if not |
| `PUT` / `DELETE` | `/profiles/me/watched/{movieId}` | Marks a movie as seen, or unmarks it (`204`). Marking it also takes it off your watchlist. Unknown movies return `404` |
| `GET` | `/profiles/me/watchlist` | Your watch-later list, most recently added first (paginated). There's no way to read someone else's |
| `GET` | `/profiles/me/watchlist/{movieId}` | `200` if the movie is on your watchlist, `404` if not |
| `PUT` / `DELETE` | `/profiles/me/watchlist/{movieId}` | Adds a movie to your watchlist, or removes it (`204`). Unknown movies return `404` |
| `GET` | `/profiles/{id}/following` | The profiles a profile follows, most recent first (paginated) |
| `GET` | `/profiles/{id}/followers` | The profiles following a profile, most recent first (paginated) |
| `PUT` / `DELETE` | `/profiles/me/following/{id}` | Follows or unfollows a profile (`204`). Yourself returns `400`, unknown profiles `404` |

The `PUT`s are safe to repeat, since marking a movie, adding it to the watchlist or following a user twice changes nothing and keeps the original date. Errors come with a JSON body, `{ "error": "..." }`. If movies-service is down, requests that need it answer `503` with `{ "error": "The movies service isn't available: ..." }`.

Two more endpoints are only for recommendation-service, which calls them as itself. They need the `profiles-reader` role, so users get `403`. Unlike the others, they never create a profile for the caller.

| Method | Path | Does |
|---|---|---|
| `GET` | `/internal/profiles?page&size` | Every profile's id, username, last change and [fingerprint](#profile-changes), 100 per page by default and 200 at most. The background agent uses it to find profiles that changed |
| `GET` | `/internal/profiles/{id}/snapshot` | What a recommendation is made from, namely the favourite genres, the movies watched and saved, newest first (up to 2,000 and 500, so none of them is recommended), the 20 profiles most recently followed, what those watched (the 100 most watched among them, with how many of them watched each), and the fingerprint |

### Data

Profiles live in profiles-service's own database, `profiles-db`, in a `profiles` schema that the Flyway migrations in `profiles-service/src/main/resources/db/migration/` create on first start (`V1__profiles_schema.sql`, `V2__watchlist.sql`, `V3__profile_last_changed.sql`).

| Table | Contents |
|---|---|
| `profiles.profile` | One row per Keycloak user, with their Keycloak user id (the token's `sub`), username, join date, and `last_changed_at`, which records when their genres, watched movies, watchlist or follows last changed |
| `profiles.favorite_genre` | Profile → genre id |
| `profiles.watched_movie` | Profile → movie id, with when it was marked |
| `profiles.watchlist` | Profile → movie id to watch later, with when it was added. Only the owner can see it |
| `profiles.follow` | Profile → profile it follows, with when. Following yourself is refused (also by a check constraint) |

Movies and genres belong to movies-service, so this database stores only their ids. profiles-service asks movies-service instead, passing on the logged-in user's token (`MoviesClient.java`, `MovieCatalog.java`). It asks before storing an id, to check that the movie or genre exists, and when showing a profile, for genre names (cached for 10 minutes) and for the titles, posters and dates of watched and saved movies, fetched with one `GET /movies/batch` request per page.

Deleting a user in Keycloak doesn't delete their profile yet, because that would need Keycloak to notify this service, e.g. through a Keycloak event listener extension, or a job that compares profiles with Keycloak's users.

### Tests

```bash
cd backend/profiles-service
./mvnw test
```

Like movies-service's tests, they use a temporary PostgreSQL container, `@TestSecurity` instead of Keycloak, and a random port. movies-service is replaced by a Mockito mock (`@InjectMock`), so it doesn't need to run. `@OidcSecurity` sets the token claims the service reads (`sub` and `preferred_username`), so each test can act as a different user (`TestUsers`). A test-only migration (`src/test/resources/db/testdata/R__test_data.sql`) adds three sample profiles. `InternalProfileResourceTest` covers the internal endpoints with the `profiles-reader` role, and `ProfileMetricsTest` the metrics.
