# Movies

A movie catalogue built on [The Movies Dataset](https://www.kaggle.com/datasets/rounakbanik/the-movies-dataset) (about 45,000 TMDB movies).

| Part | Path | Stack | Port |
|---|---|---|---|
| Database | `infra/compose.yaml`, `data/` | PostgreSQL 16 in Docker | 5432 |
| Movies API | `backend/movies-service/` | Java 21+, Quarkus, Hibernate ORM with Panache | 8080 |
| Frontend | `frontend/movies-frontend/` | Next.js 16, React 19, Tailwind CSS 4 | 3000 |

## Install and configure

You need four tools. The versions below are the ones the project is developed with.

| Tool | Version | Needed for |
|---|---|---|
| Docker Engine with the Compose plugin | Compose v2+ | The database, and the backend tests |
| Java, installed with SDKMAN! | 27 (any JDK 21+ works) | The backend |
| Node.js with npm, installed with nvm | 24 LTS (20.9+ works) | The frontend |
| `psql` | any | Optional: querying the database from your machine |

The commands below are for Debian or Ubuntu. On macOS, the SDKMAN! and nvm steps are the same, and for Docker you install [Docker Desktop](https://docs.docker.com/desktop/).

You don't need to install Maven: the backend ships the Maven wrapper (`./mvnw`), which downloads the right Maven version on first use.

### Docker

Install Docker Engine and the Compose plugin by following the [official guide](https://docs.docker.com/engine/install/) for your distribution, or use Docker's convenience script:

```bash
curl -fsSL https://get.docker.com | sh
```

**Configure:** let your user run Docker without `sudo`. The backend tests also need this, because they start containers themselves.

```bash
sudo usermod -aG docker $USER
```

Log out and back in for the group change to apply, then check:

```bash
docker run --rm hello-world
docker compose version
```

### Java 27 with SDKMAN!

SDKMAN! needs `curl`, `zip` and `unzip`:

```bash
sudo apt install -y curl zip unzip
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

Install Java 27 and make it the default:

```bash
sdk install java 27.0.0-oracle
sdk default java 27.0.0-oracle
```

To use a different vendor, `sdk list java` shows every available build, e.g. Temurin (`-tem`).

**Configure:** the installer adds SDKMAN! to `~/.bashrc` (or `~/.zshrc`), which sets `JAVA_HOME` in every new terminal. The Maven wrapper needs `JAVA_HOME`, so open a new terminal and check:

```bash
java -version      # java version "27" ...
echo $JAVA_HOME    # /home/<you>/.sdkman/candidates/java/current
```

If `JAVA_HOME` is empty, e.g. in a shell that doesn't read `~/.bashrc`, set it yourself:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
```

### Node.js 24 with nvm

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc
nvm install --lts
nvm alias default 'lts/*'
```

**Configure:** like SDKMAN!, the installer adds nvm to `~/.bashrc`, so `node` and `npm` are available in every new terminal. Check:

```bash
node -v    # v24.x
npm -v
```

### psql (optional)

You only need this to query the database from your machine. The steps below run `psql` inside the database container instead.

```bash
sudo apt install -y postgresql-client
```

### Project configuration

Nothing else needs configuring if you keep the defaults:

| Setting | Default | Where to change it |
|---|---|---|
| Database name, user and password | `movies`, `postgres`, `postgres` | Set `POSTGRES_DB`, `POSTGRES_USER` or `POSTGRES_PASSWORD` before the first `docker compose up`. If you do, also change the matching `%dev.quarkus.datasource.*` lines in `backend/movies-service/src/main/resources/application.properties`. |
| Database port | `5432` | `ports` in `infra/compose.yaml`, and `%dev.quarkus.datasource.jdbc.url` in `application.properties` |
| API port | `8080` | `-Dquarkus.http.port=<port>` when starting the API |
| Frontend port | `3000` | `npm run dev -- -p <port>` |

## 1. Start the database

```bash
docker compose -f infra/compose.yaml up -d
```

This starts PostgreSQL on `localhost:5432` with database `movies`, user `postgres` and password `postgres`. To use different values, set `POSTGRES_USER`, `POSTGRES_PASSWORD` or `POSTGRES_DB` before running it. The data is kept in the `postgres_data` Docker volume, so it survives restarts.

### Load the data (first time only)

Do this **before you start the API for the first time**. On an empty database, the API creates empty tables on startup, and the restore then clashes with them (see [Troubleshooting](#troubleshooting)).

```bash
docker exec -i postgres psql -U postgres -d movies < data/movies_dump.sql
```

This runs `psql` inside the container, so you don't need it installed locally. It takes a few seconds and creates the `movies` schema with its tables, view and indexes. The columns and indexes for search aren't in the dump: the API adds them the first time it starts (see [Database migrations](#database-migrations)).

The dump was produced by `pg_dump` 17, so Postgres 16 prints one error during the restore: `unrecognized configuration parameter "transaction_timeout"`. It's harmless and the rest of the restore completes.

Check the result:

```bash
docker exec postgres psql -U postgres -d movies -c "select count(*) from movies.movie"
# 45430
```

Run the restore only once, into an empty database. Running it again fails because the tables already exist.

To start over from scratch, run `docker compose -f infra/compose.yaml down -v`. **This deletes all the data**, and you'll need to restore the dump again.

`data/schema.sql` documents the schema as it is in the dump, and how the raw CSVs in `data/raw/` were cleaned. You don't need it to run the project. Later schema changes, such as the search columns, live in the API's migration files instead.

## 2. Start the movies API

```bash
cd backend/movies-service
./mvnw quarkus:dev
```

The first run downloads the dependencies and takes a while. Once it's up:

- `http://localhost:8080/movies`: paginated list of movies
- `http://localhost:8080/q/swagger-ui`: interactive API docs
- `http://localhost:8080/q/openapi`: OpenAPI document
- `http://localhost:8080/q/health`: health check

In dev mode the service connects to the database from step 1.

### Database migrations

The service manages schema changes with [Flyway](https://documentation.red-gate.com/flyway). On every startup, it applies any SQL file in `backend/movies-service/src/main/resources/db/migration/` that the database hasn't run yet, in version order, and records it in the `movies.flyway_schema_history` table.

| File | What it does |
|---|---|
| `V1__movies_schema.sql` | The base schema, the same as in the dump. It only runs on an empty database, such as in tests. A database restored from the dump is recorded as already at version 1. |
| `V2__movie_full_text_search.sql` | Adds the full-text search columns `search_vector` and `title_vector` to `movies.movie`, with their indexes. |
| `V3__movie_title_fuzzy_search.sql` | Enables the `pg_trgm` extension and adds a trigram index on `title`, for typo-tolerant search. |

The migrations only add to the schema; none of them drops anything. The first start after restoring the dump takes a few extra seconds while V2 and V3 fill in the search columns for all 45,430 movies. After that, startup skips them.

To change the schema, **add a new file** with the next version number, e.g. `V4__add_movie_runtime.sql`. Never edit a migration that has already run anywhere: Flyway compares every applied file with its recorded checksum, and refuses to start if one changed.

Hibernate doesn't change the schema at all. In dev and tests, it only checks at startup that the Java entities match the tables.

### Endpoints

| Method | Path | Query parameters | Returns |
|---|---|---|---|
| `GET` | `/movies` | `page`: default `0`, zero-based<br>`size`: default `20`, 1–100<br>`sort`: `id` (default), `title`, `rating` or `releaseDate`<br>`order`: `asc` (default) or `desc` | A page of movies: `{ items, page, size, totalItems, totalPages }` |
| `GET` | `/movies/search` | `q`: required, up to 200 characters<br>`page`, `size`: as for `/movies` | A page of matching movies, best matches first |
| `GET` | `/movies/{id}` | | One movie, or `404` |

An invalid parameter returns `400`. In `/movies`, movies without a value for the sort field always come last.

```bash
curl "localhost:8080/movies?sort=rating&order=desc&size=5"
curl "localhost:8080/movies/search?q=dark+knight"
curl localhost:8080/movies/238
```

### Search

`/movies/search` looks for the words of `q` in movie titles and descriptions, using PostgreSQL's built-in full-text search.

- **Words match in any form:** `dreams` also finds "dream", and `running` finds "run".
- **Search-engine syntax works:** `"dark knight"` for an exact phrase, `alien or predator` for either word, `heist -casino` to exclude a word.
- **Title matches come first**, most popular (most rated) first, followed by movies that match only in their description, most relevant first.
- **Common words are ignored** ("the", "of", "it"). A query made only of such words, like `It` or `The Who`, instead matches title words exactly as written.
- **Typos in titles are tolerated:** if nothing matches, the search falls back to titles that look similar, so `godfater` finds *The Godfather* and `incepton` finds *Inception*.

Descriptions are matched as English, and accents count: `amelie` doesn't find *Amélie*.

### Tests

```bash
./mvnw test
```

The tests don't touch your database. They start their own temporary PostgreSQL container, so Docker must be running. Flyway builds the schema there from the migrations, then loads a few sample movies from `src/test/resources/db/testdata/R__test_data.sql`.

### Production build

```bash
./mvnw package
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/movies \
QUARKUS_DATASOURCE_USERNAME=postgres \
QUARKUS_DATASOURCE_PASSWORD=postgres \
java -jar target/quarkus-app/quarkus-run.jar
```

## 3. Start the frontend

```bash
cd frontend/movies-frontend
npm install
npm run dev
```

Open `http://localhost:3000`. The frontend is still the Next.js starter page and doesn't call the API yet.

## Troubleshooting

| Problem | Fix |
|---|---|
| `The JAVA_HOME environment variable is not defined correctly` | Set `JAVA_HOME` as shown in [Java 27 with SDKMAN!](#java-27-with-sdkman). |
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | Another PostgreSQL is running on 5432. Stop it, or change the host port in `infra/compose.yaml` and update `%dev.quarkus.datasource.jdbc.url` in `backend/movies-service/src/main/resources/application.properties` to match. |
| `The container name "/postgres" is already in use` | A container named `postgres` already exists, maybe from another project. If it's this database, keep using it. Otherwise stop and remove it with `docker rm -f postgres`, which **deletes its data unless it's stored in a volume**. |
| The API starts, but `/movies` returns no movies | The API started before the dump was restored, so Flyway created empty tables. Reset the database with `docker compose -f infra/compose.yaml down -v` (this deletes all its data) and `docker compose -f infra/compose.yaml up -d`, then restore the dump before starting the API again. |
| The restore prints many `already exists` errors | Same cause: the API created the tables first. Reset the database as in the previous row, then restore before starting the API. |
| The API fails at startup with `Validate failed: Migrations have failed validation` | A migration file changed after it had already run. Undo the edit, e.g. with `git checkout` on that file, and put the change in a new `V<next>__...sql` file instead. |
| Port 8080 or 3000 is in use | Run the API with `./mvnw quarkus:dev -Dquarkus.http.port=8081`, or the frontend with `npm run dev -- -p 3001`. |
