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

```bash
docker exec -i postgres psql -U postgres -d movies < data/movies_dump.sql
```

This runs `psql` inside the container, so you don't need it installed locally. It takes a few seconds and creates the `movies` schema with its tables, view and indexes.

The dump was produced by `pg_dump` 17, so Postgres 16 prints one error during the restore: `unrecognized configuration parameter "transaction_timeout"`. It's harmless and the rest of the restore completes.

Check the result:

```bash
docker exec postgres psql -U postgres -d movies -c "select count(*) from movies.movie"
# 45430
```

Run the restore only once, into an empty database. Running it again fails because the tables already exist.

To start over from scratch, run `docker compose -f infra/compose.yaml down -v`. **This deletes all the data**, and you'll need to restore the dump again.

`data/schema.sql` documents the schema and how the raw CSVs in `data/raw/` were cleaned. You don't need it to run the project: the dump already contains everything.

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

In dev mode the service connects to the database from step 1. It never creates, changes or drops tables there: at startup it only checks that its mapping matches the existing schema.

### Endpoints

| Method | Path | Query parameters | Returns |
|---|---|---|---|
| `GET` | `/movies` | `page`: default `0`, zero-based<br>`size`: default `20`, 1–100<br>`sort`: `id` (default), `title`, `rating` or `releaseDate`<br>`order`: `asc` (default) or `desc` | A page of movies: `{ items, page, size, totalItems, totalPages }` |
| `GET` | `/movies/{id}` | | One movie, or `404` |

An invalid parameter returns `400`. Movies without a value for the sort field always come last.

```bash
curl "localhost:8080/movies?sort=rating&order=desc&size=5"
curl localhost:8080/movies/238
```

### Tests

```bash
./mvnw test
```

The tests don't touch your database. They start their own temporary PostgreSQL container, so Docker must be running.

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
| The API fails at startup with a schema validation error about a missing table | The dump hasn't been loaded. See [Load the data](#load-the-data-first-time-only). |
| Port 8080 or 3000 is in use | Run the API with `./mvnw quarkus:dev -Dquarkus.http.port=8081`, or the frontend with `npm run dev -- -p 3001`. |
