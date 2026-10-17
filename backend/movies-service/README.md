# movies-service

The movie catalogue API serves about 45,000 movies from The Movies Dataset, with listing, search by words (full-text, prefix, accent-insensitive and typo-tolerant), and search by meaning with embeddings from Ollama in pgvector. It also picks the candidate movies that recommendation-worker's language model chooses from. Every endpoint needs a Keycloak access token.

- **Port:** 8080 (Swagger UI at `http://localhost:8080/q/swagger-ui`)
- **Database:** the movies database (`postgres` in `infra/compose.yaml`, `localhost:5432`), schema `movies`, with migrations in `src/main/resources/db/migration/`. Load `data/movies_dump.sql` before the first start.
- **Needs:** that database, Keycloak, and Ollama with `nomic-embed-text` (without it, search by meaning is skipped)

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs Docker: the tests start their own PostgreSQL
```

### Endpoints

| Method | Path | Query parameters | Returns |
|---|---|---|---|
| `GET` | `/movies` | `page` defaults to `0`, zero-based<br>`size` defaults to `20`, 1 to 100<br>`sort` is `releaseDate` (default), `id`, `title` or `rating`<br>`order` is `asc` or `desc`. Without `sort`, it's newest first (`releaseDate`, `desc`), and a `sort` without `order` is ascending | A page of movies, as `{ items, page, size, totalItems, totalPages }` |
| `GET` | `/movies/search` | `q` is required, up to 200 characters<br>`page` and `size` work as for `/movies`<br>`sort` is `relevance` (default), `id`, `title`, `rating` or `releaseDate`<br>`order` is `asc` (default) or `desc`, and is ignored for `relevance` | A page of matching movies, best matches first unless `sort` names a field (see [Search](#search)) |
| `GET` | `/movies/{id}` | | One movie, or `404` |
| `GET` | `/movies/batch` | `ids`, repeated, up to 100, e.g. `?ids=238&ids=27205` | Those movies, in the order asked for, leaving out unknown ids. Used by the other services |
| `POST` | `/movies/candidates` | A JSON body in which every field is optional, with `genreIds`, `preferredGenreIds` (up to 20 each), `yearFrom`, `yearTo`, `text` (up to 200 characters), `meaning` (a request in plain words, up to 500), `friendWatchers` (`[{ movieId, watchers }]`, up to 200), `onlyIds` (up to 200), `excludeIds` (up to 5,000), `minRatingCount` (default 50), `limit` (default 40, at most 100) | The best-scoring movies for a recommendation (see [Ask AI](#ask-ai)). Used by recommendation-worker |
| `GET` | `/genres` | | All 20 movie categories, by name, as `[{ id, name }]` |

Without a valid token they return `401`, and an invalid parameter returns `400`. In `/movies` and in sorted searches, movies without a value for the sort field always come last, and ties are broken by id so pages don't overlap.

```bash
TOKEN=...   # see "Calling the APIs yourself" below
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/movies?size=5"    # newest first
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/movies?sort=rating&order=desc&size=5"
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/movies/search?q=dark+knight"
curl -H "Authorization: Bearer $TOKEN" localhost:8080/movies/238
```

### Data and migrations

The `movies` schema holds `movie`, `genre`, `movie_genre`, the view `movie_card`, and `movie_embedding` for search by meaning. The service manages schema changes with [Flyway](https://documentation.red-gate.com/flyway). On every startup it applies the files in `movies-service/src/main/resources/db/migration/` that the database hasn't run yet, in version order, and records them in `movies.flyway_schema_history`.

| File | What it does |
|---|---|
| `V1__movies_schema.sql` | The base schema, the same as in the dump. It only runs on an empty database, such as in tests. A database restored from the dump is recorded as already at version 1 (`baseline-on-migrate`). |
| `V2__movie_full_text_search.sql` | Adds the full-text search columns `search_vector` (English word roots, with the title weighted A and the description B) and `title_vector` (title words as written), with GIN indexes. |
| `V3__movie_title_fuzzy_search.sql` | Enables the `pg_trgm` extension and adds a trigram index on `title`, for typo-tolerant search. |
| `V4__movie_imdb_id.sql` | Adds `imdb_id` to `movies.movie`, filled from the dump's CSV staging table (45,413 of 45,430 movies have one). |
| `V5__movie_accent_insensitive_search.sql` | Enables the `unaccent` extension and rebuilds the search columns and the trigram index without accents. The rebuilt columns only hold values computed from title and description, so no data is lost. |
| `V6__movie_embeddings.sql` | Enables the `vector` extension (pgvector) and adds `movies.movie_embedding`, which holds the vector for each movie and the model that made it. There's no index, because an exact search over about 9,000 vectors is fast enough. |

The migrations only add to the schema, and none of them drops data. The first start after restoring the dump takes about 15 extra seconds while V2 to V5 fill in the new columns for all 45,430 movies.

To change the schema, add a new file with the next version number, e.g. `V7__add_movie_runtime.sql`. Never edit a migration that has already run anywhere, because Flyway compares every applied file with its recorded checksum and refuses to start if one changed. Hibernate never changes the schema. In dev and tests it only checks at startup that the entities match the tables.

**Load the movies data before starting movies-service for the first time.** On an empty database, Flyway creates empty tables from V1, and the restore then clashes with them (see [Troubleshooting](#troubleshooting)):

```bash
docker exec -i postgres psql -U postgres -d movies < data/movies_dump.sql   # from the project root
```

The dump was produced by `pg_dump` 17, so PostgreSQL 16 prints one harmless error, `unrecognized configuration parameter "transaction_timeout"`. `just local-first-up` does this step for you.

### Tests

```bash
cd backend/movies-service
./mvnw test
```

The tests start their own temporary PostgreSQL container with pgvector (Quarkus Dev Services, so Docker must be running), where Flyway builds the schema from the migrations and loads a few sample movies from `src/test/resources/db/testdata/R__test_data.sql`. They don't need Keycloak, because they act as a logged-in user with `@TestSecurity`, and `MovieResourceAuthTest` checks that requests without a token are rejected. Ollama is a Mockito mock, and the embedding job doesn't run on a schedule. `MovieEmbeddingsTest` calls it instead. `MoviesMetricsTest` checks the application metrics. They run on a random port, so they don't clash with a running dev server.

