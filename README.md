# Movies

A movie catalogue built on [The Movies Dataset](https://www.kaggle.com/datasets/rounakbanik/the-movies-dataset) (about 45,000 TMDB movies). Users register and log in through Keycloak, then browse and search movies, keep a profile of their favourite genres and the movies they've seen, save movies to watch later, and follow other users. A language model running on your machine recommends movies to them, from their profile and the people they follow, or from a request they type, like "a horror movie from the 80s".

It runs in two ways: **on your own machine**, entirely in Docker with one command (the [Quick start](#quick-start) below; the services can also be started by hand for development), or **in Google Cloud**, on a self-hosted Kubernetes cluster that Terraform and Ansible create and deploy from nothing ([Deploy to Google Cloud](#deploy-to-google-cloud), with the full guide in [infra/README.md](infra/README.md)).

| Part | Path | Stack | Port |
|---|---|---|---|
| Movies database | `infra/compose.yaml` (`postgres`), `data/` | PostgreSQL 16 in Docker | 5432 |
| Profiles database | `infra/compose.yaml` (`profiles-db`) | PostgreSQL 16 in Docker | 5433 |
| Recommendations database | `infra/compose.yaml` (`recommendations-db`) | PostgreSQL 16 in Docker | 5434 |
| Message queue | `infra/compose.yaml` (`kafka`) | Apache Kafka 4 in Docker (KRaft, one broker) | 9092 |
| Language models | `infra/compose.yaml` (`ollama`) | Ollama in Docker, with `qwen2.5:3b` (chat) and `nomic-embed-text` (search by meaning) | 11434 |
| Accounts and login | `infra/compose.yaml`, `infra/keycloak/` | Keycloak 26 in Docker | 8180 |
| Movies API | `backend/movies-service/` | Java 21+, Quarkus, Hibernate ORM with Panache | 8080 |
| Profiles API | `backend/profiles-service/` | Java 21+, Quarkus, Hibernate ORM with Panache | 8081 |
| Recommendations API | `backend/recommendation-service/` | Java 21+, Quarkus, Hibernate ORM with Panache, Kafka | 8082 |
| Recommendation worker | `backend/recommendation-worker/` | Java 21+, Quarkus, Kafka, Ollama's API | 8083 (health checks only) |
| Frontend | `frontend/` | Next.js 16, React 19, Tailwind CSS 4 | 3000 |

Each service has its own database instance, and no service reads another's database: movies-service owns the movies database, profiles-service the profiles database, and recommendation-service the recommendations database. When a service needs another's data, it asks that service's API: profiles-service asks movies-service about movies and genres, and the recommendation services ask profiles-service for profiles and movies-service for movies. The worker has no database. Keycloak keeps its data in its own `keycloak` database, which only Keycloak accesses.

## Quick start

You need [Docker](#docker) and [just](https://github.com/casey/just#installation). From the project root:

```bash
just local-first-up    # the first time: infrastructure, movies data, backend, frontend
just local-up          # every later time (the movies data is already in its volume)
just local-down        # stop and remove the containers; the data is kept
```

Then open `http://localhost:3000` and register.

`just local-first-up` runs these steps in order, each waiting until the previous one is healthy:

1. **Infrastructure** (`infra/compose.yaml`): the three databases, Kafka, Ollama and Keycloak. It also waits for the language models to finish downloading.
2. **The movies data**: it loads `data/movies_dump.sql` into the movies database. It skips this step if the data is already there, so running it twice is safe. It has to happen before the backend starts.
3. **Backend** (`backend/compose.yaml`): the four Quarkus services, built from `backend/Dockerfile`.
4. **Frontend** (`frontend/compose.yaml`): the website, built from `frontend/Dockerfile`.

`just local-up` does the same without the data import. The first run takes several minutes: it downloads the images and the models (about 2.2 GB) and builds five images.

These are three Docker Compose projects that share one network, `movies-net`:

| Project | File | Contains |
|---|---|---|
| `infra` | `infra/compose.yaml` | `postgres` (movies and Keycloak), `profiles-db`, `recommendations-db`, `kafka`, `ollama`, `ollama-models` (downloads the models, then exits), `keycloak` |
| `backend` | `backend/compose.yaml` | `movies-service`, `profiles-service`, `recommendation-service`, `recommendation-worker` |
| `frontend` | `frontend/compose.yaml` | `frontend` |

Compose can't make one project wait for another, so the `justfile` in the project root starts them in order and waits for each to be healthy. Inside a project, `depends_on` does the same: profiles-service waits for movies-service, and so on. Containers reach each other by name (`postgres`, `keycloak`, `kafka:29092`...), while your browser uses the published ports on `localhost`.

Useful commands, from the project root:

```bash
docker ps                                                             # all containers, with their health
docker compose -f backend/compose.yaml logs -f movies-service         # a service's log
docker compose -f backend/compose.yaml up -d --build movies-service   # rebuild one service after changing its code
```

To delete the data as well, run `just local-down`, then `docker compose -f infra/compose.yaml down -v`. **This deletes every user, profile and downloaded model.** The four services are capped at 768 MB each and Ollama at 4 GB, so the stack needs several gigabytes of memory. On Windows with WSL 2, give it at least 10 GB.

### Running the services by hand (for development)

To work on a service with live reload, start only the infrastructure and run the services yourself, each in its own terminal. Don't run both ways at once, since they use the same ports.

```bash
docker compose -f infra/compose.yaml up -d
docker exec -i postgres psql -U postgres -d movies < data/movies_dump.sql     # first time only
(cd backend/movies-service && ./mvnw quarkus:dev)                             # movies API, port 8080
(cd backend/profiles-service && ./mvnw quarkus:dev)                           # profiles API, port 8081
(cd backend/recommendation-service && ./mvnw quarkus:dev)                     # recommendations API, port 8082
(cd backend/recommendation-worker && ./mvnw quarkus:dev)                      # recommendation worker, port 8083
(cd frontend && npm install && npm run dev)                                   # app, port 3000
```

The numbered sections below explain each step.

## Deploy to Google Cloud

The same system, on a Kubernetes cluster in Google Cloud: 1 control-plane and 2 worker VMs (kubeadm, containerd, Calico), with NGINX as the only public entry point (HTTPS), Kafka with 3 brokers, one PostgreSQL per service, Ollama, Keycloak, and Prometheus and Grafana monitoring all of it. [infra/README.md](infra/README.md) explains every step, what's exposed and why, and how to fix what can go wrong. In short, from `infra/`:

**Once:** a Google Cloud project with billing, a service account key for Terraform, and a Docker Hub (or other registry) login. The exact commands are in [infra/README.md → One-time setup](infra/README.md#one-time-setup).

```bash
docker login                                              # once, to push the images
just images docker.io/<user> v1                           # build the 5 images for linux/amd64 and push them (after code changes)
cp terraform/terraform.tfvars.example terraform/terraform.tfvars   # set gcp_project_id
just keygen                                               # SSH key for the VMs
just mgmt-start                                           # the management container (Terraform, Ansible, kubectl)
just mgmt                                                 # a shell in it, then:
just all                                                  # VMs + Kubernetes + platform + application, ~25 minutes
```

Set `image_registry` and `image_tag` in `infra/ansible/group_vars/all.yml` to what you pushed. At the end, `just all` prints the application's address (`https://<static IP>`), Grafana's (`/grafana`) and Keycloak's admin console (`/auth/admin`); the passwords are in `infra/ansible/.secrets/`. **When you're done, `just destroy`**: the VMs cost about 0.40 USD an hour while they exist. `just destroy` deletes the VMs but keeps the data: the workers' data disks and the static IP survive it, and the next `just all` comes back with every account and recommendation ([infra/README.md → Persistent data](infra/README.md#persistent-data)); `just destroy-data` deletes those too. To pause it for a shorter time, `just stop` and `just start` stop and start all the VMs ([infra/README.md → Stop and start the VMs](infra/README.md#stop-and-start-the-vms)).

## Install and configure

The [Quick start](#quick-start) needs only **Docker** and **just**. To work on the code, you also need **Java** and **Node.js**. The versions below are the ones the project is developed with.

| Tool | Version | Needed for |
|---|---|---|
| Docker Engine with the Compose plugin | Compose v2+ | The whole system in Docker, and the backend tests |
| [just](https://github.com/casey/just#installation) | any | The Quick start's commands (`just local-up`...) |
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
| Movies database name, user and password | `movies`, `postgres`, `postgres` | Set `POSTGRES_DB`, `POSTGRES_USER` or `POSTGRES_PASSWORD` before the first `docker compose up`. If you do, also change the matching `%dev.quarkus.datasource.*` lines in `backend/movies-service/src/main/resources/application.properties`. |
| Profiles database user and password | `profiles`, `profiles` | Set `PROFILES_DB_USER` or `PROFILES_DB_PASSWORD` before the first `docker compose up`, and change `%dev.quarkus.datasource.*` in `backend/profiles-service/src/main/resources/application.properties` to match. |
| Recommendations database user and password | `recommendations`, `recommendations` | Set `RECOMMENDATIONS_DB_USER` or `RECOMMENDATIONS_DB_PASSWORD` before the first `docker compose up`, and change `%dev.quarkus.datasource.*` in `backend/recommendation-service/src/main/resources/application.properties` to match. |
| Database ports | `5432` (movies), `5433` (profiles), `5434` (recommendations) | `ports` in `infra/compose.yaml`, and `%dev.quarkus.datasource.jdbc.url` in the matching service's `application.properties` |
| The language model | `qwen2.5:3b` | Set `OLLAMA_MODEL` before `docker compose up` (it downloads that one), and start the worker with `RECOMMENDATION_MODEL` set to the same. See [Choosing a model](#choosing-a-model) |
| The embedding model, for search by meaning | `nomic-embed-text` | Set `OLLAMA_EMBEDDING_MODEL` before `docker compose up`, and start movies-service with `EMBEDDING_MODEL` set to the same. Changing it embeds every movie again |
| Ollama's memory limit, and how long an idle model stays loaded | `4g`, `10m` | `OLLAMA_MEMORY_LIMIT` and `OLLAMA_KEEP_ALIVE` before `docker compose up` |
| Keycloak admin user and password | `admin`, `admin` | Set `KEYCLOAK_ADMIN` or `KEYCLOAK_ADMIN_PASSWORD` before the first `docker compose up` |
| Keycloak port | `8180` | `ports` and `KC_HOSTNAME` in `infra/compose.yaml`, `%dev.quarkus.oidc.auth-server-url` in the APIs' `application.properties`, `KEYCLOAK_REALM_URL` for the recommendation services' own logins, and `NEXT_PUBLIC_KEYCLOAK_URL` for the frontend |
| API ports | `8080` (movies), `8081` (profiles), `8082` (recommendations) | `-Dquarkus.http.port=<port>` when starting an API. Changing them also means updating the frontend's configuration, the `%dev.quarkus.rest-client.*.url` lines of the services that call it, and, for Swagger UI logins, the `movies-swagger-ui` client in Keycloak |
| Frontend port | `3000` | `npm run dev -- -p <port>` |
| The same settings in Docker (`just local-first-up`) | The defaults above | The backend containers read `POSTGRES_*`, `PROFILES_DB_*`, `RECOMMENDATIONS_DB_*`, `OLLAMA_MODEL` and `OLLAMA_EMBEDDING_MODEL` from your environment too, so set them in your shell before `just local-first-up`. The ports and addresses are fixed in `backend/compose.yaml`; the frontend's addresses are compiled into its image (`build.args` in `frontend/compose.yaml`), so rebuild it after changing them |

## 1. Start the databases and Keycloak

```bash
docker compose -f infra/compose.yaml up -d
```

This starts these containers:

- **`postgres`**, movies-service's database, on `localhost:5432`, with database `movies`, user `postgres` and password `postgres`. It's PostgreSQL 16 with the [pgvector](https://github.com/pgvector/pgvector) extension, for search by meaning. To use different values, set `POSTGRES_USER`, `POSTGRES_PASSWORD` or `POSTGRES_DB` before running it. The data is kept in the `postgres_data` Docker volume, so it survives restarts.
- **`profiles-db`**, profiles-service's database, on `localhost:5433`, with database `profiles`, user `profiles` and password `profiles`, kept in the `profiles_data` volume.
- **`recommendations-db`**, recommendation-service's database, on `localhost:5434`, with database, user and password `recommendations`, kept in the `recommendations_data` volume.
- **`kafka`**, the queue between the recommendations API and its worker, on `localhost:9092` (containers reach it at `kafka:29092`), kept in the `kafka_data` volume.
- **`ollama`**, which runs the language models, on `localhost:11434`: the chat model for the worker and the embedding model for movies-service, both loaded at once. Models are kept in the `ollama_models` volume.
- **`ollama-models`**, which downloads the models (`qwen2.5:3b`, about 2 GB, and `nomic-embed-text`, about 270 MB) into that volume the first time, then exits. `docker logs -f ollama-models` shows the progress, and `docker exec ollama ollama list` what's downloaded. Later runs find it there and exit at once.
- **Keycloak** on `localhost:8180`, which handles user accounts and login (see [Accounts and login](#accounts-and-login)). It stores its data in its own `keycloak` database, in the `postgres` server, and needs about 30 seconds to start. `docker ps` shows it as `healthy` when it's ready.

The `keycloak` database is created automatically, by `infra/init/01-keycloak.sql`, but only when the `postgres_data` volume is new. **If your database volume existed before Keycloak was added**, create it once by hand, then start Keycloak (and the other containers that didn't exist yet when you set up):

```bash
docker exec -i postgres psql -U postgres < infra/init/01-keycloak.sql
docker compose -f infra/compose.yaml up -d
```

**If your `postgres` container was created before search by meaning**, it runs the plain `postgres:16` image. `docker compose -f infra/compose.yaml up -d postgres` recreates it with `pgvector/pgvector:pg16-trixie` and keeps its volume, so no data is lost. That image is built on the same Debian release as `postgres:16`, so text keeps sorting the same way; the `pg16` tag without `-trixie` is built on an older one, which would leave existing text indexes out of order.

### Load the data (first time only)

Do this **before you start the movies API for the first time**. On an empty database, the movies API creates empty tables on startup, and the restore then clashes with them (see [Troubleshooting](#troubleshooting)).

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

To start over from scratch, run `just local-down` first if you started everything with `just` (otherwise the backend containers keep running), then `docker compose -f infra/compose.yaml down -v`. **This deletes all the data, including every user account and profile**, and you'll need to restore the dump again (`just local-first-up` does it for you).

`data/schema.sql` documents the schema as it is in the dump, and how the raw CSVs in `data/raw/` were cleaned. You don't need it to run the project. Later schema changes, such as the search columns, live in each API's migration files instead.

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

In dev mode the service connects to the database and Keycloak from step 1. Every endpoint (`/movies`, `/genres`) needs a logged-in user: see [Accounts and login](#accounts-and-login) for how to get a token.

### Database migrations

The service manages schema changes with [Flyway](https://documentation.red-gate.com/flyway). On every startup, it applies any SQL file in `backend/movies-service/src/main/resources/db/migration/` that the database hasn't run yet, in version order, and records it in the `movies.flyway_schema_history` table.

| File | What it does |
|---|---|
| `V1__movies_schema.sql` | The base schema, the same as in the dump. It only runs on an empty database, such as in tests. A database restored from the dump is recorded as already at version 1. |
| `V2__movie_full_text_search.sql` | Adds the full-text search columns `search_vector` and `title_vector` to `movies.movie`, with their indexes. |
| `V3__movie_title_fuzzy_search.sql` | Enables the `pg_trgm` extension and adds a trigram index on `title`, for typo-tolerant search. |
| `V4__movie_imdb_id.sql` | Adds `imdb_id` to `movies.movie`, filled from the dump's CSV staging table (45,413 of 45,430 movies have one). |
| `V5__movie_accent_insensitive_search.sql` | Enables the `unaccent` extension and rebuilds the search columns and the trigram index without accents. The rebuilt columns only hold values computed from title and description, so no data is lost. |
| `V6__movie_embeddings.sql` | Enables the `vector` extension and adds `movies.movie_embedding`, each movie's vector for search by meaning (see [Search by meaning](#search-by-meaning)), filled in the background. |

The migrations only add to the schema; none of them drops anything. The first start after restoring the dump takes about 15 extra seconds while V2 to V5 fill in the new columns for all 45,430 movies. After that, startup skips them.

To change the schema, **add a new file** with the next version number, e.g. `V7__add_movie_runtime.sql`. Never edit a migration that has already run anywhere: Flyway compares every applied file with its recorded checksum, and refuses to start if one changed.

Hibernate doesn't change the schema at all. In dev and tests, it only checks at startup that the Java entities match the tables.

### Endpoints

| Method | Path | Query parameters | Returns |
|---|---|---|---|
| `GET` | `/movies` | `page`: default `0`, zero-based<br>`size`: default `20`, 1–100<br>`sort`: `releaseDate` (default), `id`, `title` or `rating`<br>`order`: `asc` or `desc`. Without `sort`, it's newest first (`releaseDate`, `desc`); a `sort` without `order` is ascending | A page of movies: `{ items, page, size, totalItems, totalPages }` |
| `GET` | `/movies/search` | `q`: required, up to 200 characters<br>`page`, `size`: as for `/movies`<br>`sort`: `relevance` (default), `id`, `title`, `rating` or `releaseDate`<br>`order`: `asc` (default) or `desc`; ignored for `relevance` | A page of matching movies, best matches first unless `sort` names a field |
| `GET` | `/movies/{id}` | | One movie, or `404` |
| `GET` | `/movies/batch` | `ids`: repeated, up to 100, e.g. `?ids=238&ids=27205` | Those movies, in the order asked for; unknown ids are left out. Used by the other services |
| `POST` | `/movies/candidates` | A JSON body: `genreIds`, `preferredGenreIds`, `yearFrom`, `yearTo`, `text`, `meaning` (a request in plain words, to rank by meaning), `friendWatchers` (`[{ movieId, watchers }]`), `onlyIds`, `excludeIds` (up to 5,000), `minRatingCount` (default 50), `limit` (default 40), all optional | The best-scoring movies for a recommendation (see [How the worker chooses](#how-the-worker-chooses)). Used by recommendation-worker |
| `GET` | `/genres` | | All 20 movie categories, by name: `[{ id, name }]` |

All of them need an access token in an `Authorization: Bearer <token>` header. Without a valid one they return `401`; any logged-in user can use all of them. An invalid parameter returns `400`. In `/movies`, movies without a value for the sort field always come last.

```bash
TOKEN=...   # see "Calling the APIs yourself" below
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/movies?size=5"    # newest first
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/movies?sort=rating&order=desc&size=5"
curl -H "Authorization: Bearer $TOKEN" "localhost:8080/movies/search?q=dark+knight"
curl -H "Authorization: Bearer $TOKEN" localhost:8080/movies/238
```

`/q/health` stays public, as do the OpenAPI document and the Swagger UI.

### Search

`/movies/search` looks for the words of `q` in movie titles and descriptions, using PostgreSQL's built-in full-text search.

- **Words match in any form:** `dreams` also finds "dream", and `running` finds "run".
- **Search-engine syntax works:** `"dark knight"` for an exact phrase, `alien or predator` for either word, `heist -casino` to exclude a word.
- **The start of a title word is enough:** `Ava` finds *Avatar*, `incep` finds *Inception*, and `star wa` finds the *Star Wars* films.
- **Accents don't matter:** `amelie` finds *Amélie*, and `pokemon` finds the *Pokémon* films.
- **Common words are ignored** ("the", "of", "it"). A query made only of such words, like `It` or `The Who`, instead matches title words exactly as written.
- **Typos in titles are tolerated:** when there are fewer than 10 matches, titles that look similar are added below them, so `godfater` finds *The Godfather* and `incepton` finds *Inception*.

By best match, results come in this order:

1. Titles containing the words, most popular (most rated) first. A title that is exactly the query counts as ten times as popular, so `batman` puts *Batman* (1989) first.
2. Titles with words starting with the query, most popular first.
3. Movies that match only in their description, most relevant first.
4. Similar-looking titles, most similar first.

Prefix and similar-title matching are skipped for queries that use search syntax (quotes, `or`, or `-word`), so an excluded word stays excluded. Descriptions are matched as English.

### Search by meaning

Word search only finds movies that use the request's words: "astronauts stranded far from home" finds *Home Alone* before *The Martian*. So for Ask AI, movies-service also searches by meaning (`MovieEmbeddings.java`):

- **Every candidate movie gets an embedding**, a list of 768 numbers that the embedding model (`nomic-embed-text`, in Ollama) computes from its title, year, genres and description. Movies about similar things get nearby vectors, whatever words they use. They're kept in `movies.movie_embedding`.
- **A background job fills that table**: the movies with at least 50 votes (the ones that can be candidates, about 9,100), most voted first, for up to 50 seconds a minute until all are done, and again for every movie if the model changes. It's resumable, and `MOVIES_EMBEDDINGS_BACKFILL=false` pauses it. The log says how many are left (`Embedded 16 movies with nomic-embed-text; 9078 to go`).
- **A request's `meaning` is embedded the same way** (recent ones are remembered), and candidates are ranked by how close they are to it, then by quality. Its keywords only add a small bonus, for exact titles and names, instead of filtering. Genres, years and everything else still filter.
- **If Ollama can't be reached**, the meaning is ignored and the keywords filter as before.

How long the first run takes depends on the CPU: minutes on a machine with AVX2, but on a virtual CPU without it (`grep -c avx2 /proc/cpuinfo` prints 0) about 3 seconds a movie, some 8 hours in all, during which the chat model is slower too. Movies not embedded yet rank below those that are. On a VM, giving it the host's CPU type (in Proxmox, **Hardware → Processor → Type: host**) fixes both.

### Tests

```bash
./mvnw test
```

The tests don't touch your database. They start their own temporary PostgreSQL container, so Docker must be running. Flyway builds the schema there from the migrations, then loads a few sample movies from `src/test/resources/db/testdata/R__test_data.sql`.

The tests don't need Keycloak either. They act as a logged-in user with Quarkus's `@TestSecurity`, and `MovieResourceAuthTest` checks that requests without a token are rejected. They run on a random port, so they don't clash with a running dev server.

### Production build

```bash
./mvnw package
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/movies \
QUARKUS_DATASOURCE_USERNAME=postgres \
QUARKUS_DATASOURCE_PASSWORD=postgres \
QUARKUS_OIDC_AUTH_SERVER_URL=http://localhost:8180/realms/movies \
QUARKUS_HTTP_CORS_ORIGINS=http://localhost:3000 \
java -jar target/quarkus-app/quarkus-run.jar
```

## 3. Start the profiles API

```bash
cd backend/profiles-service
./mvnw quarkus:dev
```

It runs on `http://localhost:8081`, next to the movies API, with its Swagger UI at `http://localhost:8081/q/swagger-ui`. It needs `profiles-db` and Keycloak (step 1), and the movies API (step 2) for anything involving movies or genres.

Every user has a profile listing their favourite genres, the movies they've seen, and the users they follow, plus a private watch-later list.

**When profiles are created:** registering in Keycloak doesn't create a profile. The profile is created on the user's first request to this API, from their token (Keycloak user id and username), and the frontend makes that request right after login. So users show up under **Users** as soon as they log in to the app for the first time. Accounts created in the Keycloak admin console stay out of the list until their owner logs in once. A username changed in Keycloak is picked up on the user's next request.

### Data

Profiles live in profiles-service's own database, `profiles-db`, in a `profiles` schema that the service's Flyway migrations (`backend/profiles-service/src/main/resources/db/migration/`) create on first start.

| Table | Contents |
|---|---|
| `profiles.profile` | One row per Keycloak user: their Keycloak user id (the token's `sub`), username, join date, and when their genres, watched movies, watchlist or follows last changed |
| `profiles.favorite_genre` | Profile → genre id |
| `profiles.watched_movie` | Profile → movie id, with when it was marked |
| `profiles.watchlist` | Profile → movie id to watch later, with when it was added. Only the owner can see it |
| `profiles.follow` | Profile → profile it follows, with when. Following yourself is refused |

Movies and genres belong to movies-service, so this database stores only their ids, with no foreign keys to them. profiles-service asks movies-service's API instead, passing on the logged-in user's token (`MoviesClient.java`, `MovieCatalog.java`):

- **before storing an id**, to check the movie or genre exists; unknown ones are refused;
- **when showing a profile**, for genre names (cached for 10 minutes) and for the titles, posters and dates of watched and watch-later movies, fetched with one `GET /movies/batch` request per page.

If movies-service is down, profile requests that need it answer `503` with `{ "error": "The movies service isn't available: ..." }`.

Deleting a user in Keycloak doesn't delete their profile yet: that would need Keycloak to notify this service, e.g. through a Keycloak event listener extension, or a job that compares profiles with Keycloak's users.

### Endpoints

All need an access token (see [Calling the APIs yourself](#calling-the-apis-yourself)), and return `401` without one. Anyone logged in can read any profile except its watchlist, which only its owner sees; changes always go through `/profiles/me/...`, so users can only change their own. Wherever a path takes a profile id, `me` means the logged-in user.

| Method | Path | Does |
|---|---|---|
| `GET` | `/profiles?q=<prefix>` | Profiles whose username starts with `q` (any case), or all of them, by username. `page` and `size` as for `/movies` |
| `GET` | `/profiles/{id}` | One profile: `{ id, username, createdAt, favoriteGenres, watchedCount, watchlistCount, followingCount, followersCount, me, followedByMe }`, or `404`. `watchlistCount` is only set on your own profile (`null` on others) |
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

The `PUT`s are safe to repeat: marking a movie, adding it to the watchlist or following a user twice changes nothing. Errors come with a JSON body: `{ "error": "..." }`.

Two more endpoints are only for recommendation-service, which calls them as itself (see [Service accounts](#service-accounts)); they need the `profiles-reader` role, so users get `403`:

| Method | Path | Does |
|---|---|---|
| `GET` | `/internal/profiles?page&size` | Every profile's id, username, last change and fingerprint (below), 200 per page at most. The background agent uses it to find profiles that changed |
| `GET` | `/internal/profiles/{id}/snapshot` | What a recommendation is made from: favourite genres, the movies watched and saved, newest first (up to 2,000 and 500, so none of them is recommended), the 20 profiles most recently followed, the movies those watched (the 100 most watched among them, with how many of them watched each), and the fingerprint |

A **fingerprint** is a SHA-256 hash of the profile's inputs, so recommendation-service can tell whether anything changed without storing the profile: `own` covers the favourite genres, watched movies and watchlist; `social` covers what the followed profiles watched; `root` covers both. The username isn't part of it.

### Tests

```bash
./mvnw test
```

Like the movies API's tests, they use a temporary PostgreSQL container, `@TestSecurity` instead of Keycloak, and a random port. movies-service's API is replaced by a Mockito mock (`@InjectMock`), so movies-service doesn't need to run. `@OidcSecurity` sets the token claims the service reads (`sub` and `preferred_username`), so each test can act as a different user. Since there's no movie data there, a test-only migration (`src/test/resources/db/testdata/`) creates small stand-ins for `movies.genre` and `movies.movie` before the real ones run.

### Production build

As for the movies API (`./mvnw package`, then `java -jar target/quarkus-app/quarkus-run.jar`), with the same environment variables. In production the port is 8080 unless you set `QUARKUS_HTTP_PORT`.

## 4. Start the recommendation services

Two services recommend movies with a language model that runs on your machine, in [Ollama](https://ollama.com):

- **recommendation-service** (`backend/recommendation-service/`, port 8082) is the API the frontend calls. It keeps the recommendations in its own database, `recommendations-db`, and queues the work for the worker.
- **recommendation-worker** (`backend/recommendation-worker/`) takes jobs off the queue, asks movies-service for candidate movies and the model to choose among them, and sends back the result. It has no API: its port, 8083, only serves health checks (`/q/health`), which also say whether Ollama is reachable.

They talk through [Kafka](https://kafka.apache.org/), so a request is answered at once (`202`), even though the model takes minutes, and the result arrives later as an event. Each in its own terminal:

```bash
cd backend/recommendation-service
./mvnw quarkus:dev
```

```bash
cd backend/recommendation-worker
./mvnw quarkus:dev
```

They need everything from steps 1 to 3 running, and the model downloaded (see step 1). The API's Swagger UI is at `http://localhost:8082/q/swagger-ui`.

There are two kinds of recommendations:

- **Recommended for you** (`PROFILE`): ten movies picked from the user's favourite genres, what they watched and saved for later, and what the people they follow watched. The first set is made on the user's first visit to `/movies`; after that, a background agent keeps it up to date.
- **Ask AI** (`PROMPT`): ten movies matching what the user typed, e.g. "a horror movie from the 80s" or "feel-good comedies about friendship", from what they haven't watched or saved yet.

### How the worker chooses

45,000 movies are far too many for a model to read, so the worker narrows them down first (`RecommendationPipeline.java`):

1. **For Ask AI, the request becomes search filters, only from what it states**: the genres it names ("scary" counts as horror, "sci-fi" as science fiction), a range of years only if it mentions a year or decade (the model turns "from the 80s" into 1980–1989), and its other words as keywords, which match titles and descriptions. Filler words ("movies", "about", "like", "from"…) never count. So "movies about serial killers from the 2000s" searches for *serial* or *killers*, released 2000–2009, and "lord of the rings" for *lord* or *rings*. The model isn't asked for genres or keywords: a small model asked for a field fills it in, with years, genres or words nobody asked for. A vague request ("something good") falls back to the user's taste.
2. **movies-service picks 30 candidates** (`POST /movies/candidates`) that match the filters, or the user's genres for Recommended for you, leaving out what the user has watched or saved and movies with fewer than 50 votes. For Ask AI, they're the movies closest in meaning to the request (see [Search by meaning](#search-by-meaning)), then the best rated. Otherwise they're ranked by a quality score, the rating weighted by the number of votes (so a 9.0 from 3 votes doesn't beat an 8.3 from 20,000), plus bonuses: 0.3 for each genre in common, 0.3 more for each favourite genre, and 0.2 for each of the 20 most recently followed people who watched it (up to 0.6). If too few match, the filters are loosened step by step.
3. **Up to 20 more are movies the people the user follows watched**, if they pass the same filters. The model is told how many of them watched each.
4. **The model chooses ten of the candidates**, reading their descriptions to match the request. Its answer must follow a JSON schema that only allows the candidates' ids, so it can't make movies up; anything else is dropped, and if it chooses fewer than ten, the best-ranked candidates fill the gaps.

### When recommendations are made

Making a set takes the model minutes, so the service only makes one when its inputs changed. Each request records the **fingerprint** of the profile it was made from (see [the profiles API](#endpoints-1)), and the **recipe**: the model and the pipeline's version (`RecommendationPipeline.VERSION`, bumped when the prompts or the candidate rules change), so results are only reused when they were made the same way.

- **Opening `/movies` for the first time** starts the first set. Asking again while it's being made returns the same request.
- **After that, the background agent** (`BackgroundAgent.java`) keeps it up to date. Every 10 minutes it compares every profile's fingerprint with the one its set was made from, and makes a new set when the user's own genres, watched movies or watchlist changed (once they've stopped changing for 2 minutes, so marking five movies in a row makes one set, not five), or when what the people they follow watched changed. An unchanged profile keeps its set, however old, and so does a change of model. It queues at most 20 per run, so a burst of changes doesn't bury users' Ask AI requests. With more than one copy of the service, a database lock makes only one of them run it.
- **Identical inputs share results:** two profiles with the same fingerprint, such as two new users with empty profiles, get a copy of the same set, and the same Ask AI question with an unchanged profile is answered at once from the earlier answer.
- **One Ask AI request at a time** per user: another one while it's being prepared is refused with `409`.

### What's kept

| Table | Contents |
|---|---|
| `recommendations.request` | One row per request: whose, which kind, the prompt, `PENDING`, `DONE` or `FAILED` (with why), the model and recipe, the fingerprints, and when |
| `recommendations.request_item` | A finished request's movies, in order |
| `recommendations.profile_state` | Per user: what their current Recommended for you was made from, and failed attempts since then |

- **Recommended for you:** only the current set, plus the one being made. The old set is deleted once the new one is stored. A failed attempt is deleted and counted in `profile_state`, and the old set stays; the agent retries after 30 minutes, then waits twice as long after each further failure, up to 6 hours.
- **Ask AI:** the last five answers. A failed request stays out of the history. It's kept for an hour, so a user who comes back is told, and deleted with the next success.
- **A request still pending after an hour fails.** It's a safety net for a job lost from Kafka, which would otherwise block the user's Ask AI for good; a slow worker just makes requests wait. The hour includes the time spent waiting in the queue, so on a slow machine keep `recommendation.timeout` well above a few jobs' time. Ask AI requests aren't retried; Recommended for you is, by the agent.

Movies are stored as ids only; titles and posters come from movies-service when a request is read.

### Endpoints

All need a user's access token, like the other APIs', and each user only sees their own requests.

| Method | Path | Does |
|---|---|---|
| `GET` | `/recommendations/me` | Recommended for you: `{ status, requestId, generatedAt, refreshing, error, movies }`. `status` is `READY` (with `movies`), `PREPARING` (the first set is being made, which this call starts if needed) or `FAILED` (with `error`; the agent tries again later). `refreshing` says whether a newer set is being made |
| `POST` | `/recommendations/requests` | Asks for movies matching `{ "prompt": "..." }` (up to 300 characters): `202` with the `PENDING` request, or `200` with the answer if the same question was answered for the same profile. `409` with `{ error, requestId }` while another request is being prepared |
| `GET` | `/recommendations/requests` | The request being prepared, if any, then the last five answers, newest first, without their movies |
| `GET` | `/recommendations/requests/current` | The request being prepared, or else the last one that failed in the past hour (with `error`). `204` if there's neither |
| `GET` | `/recommendations/requests/{id}` | One request, with its `movies` once it's `DONE`, best first: `[{ id, title, posterPath, releaseDate, rating }]`. `404` for other users' requests |
| `GET` | `/recommendations/events` | [Server-sent events](https://developer.mozilla.org/docs/Web/API/Server-sent_events): `recommendations-ready`, with `{ requestId, userId, kind, status, error }`, whenever one of the user's requests finishes. It sends `connected` when it opens, and a comment every 20 seconds to keep it open |

If Kafka or profiles-service is down, asking returns `503` with `{ "error": "Couldn't queue the request: ..." }` or `"Couldn't read the profile: ..."`.

```bash
curl -H "Authorization: Bearer $TOKEN" localhost:8082/recommendations/me
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt": "a horror movie from the 80s"}' localhost:8082/recommendations/requests
curl -N -H "Authorization: Bearer $TOKEN" localhost:8082/recommendations/events    # waits for events
```

### Kafka topics

Kafka creates them on first use, with 6 partitions each.

| Topic | From → to | Carries |
|---|---|---|
| `recommendation.requests` | recommendation-service → worker | Jobs: the request, and a snapshot of the profile. Keyed by user id, so one user's jobs are handled in order. All workers share one consumer group, so each job goes to one of them, and up to 6 can work in parallel |
| `recommendation.results` | worker → recommendation-service | The chosen movies, or why it failed. All copies of the service share a group, so each result is stored once |
| `recommendation.ready` | recommendation-service → every copy of it | "A result was stored". Each copy reads all of them, to tell the users connected to it |

A job is only taken off the queue once it's done, one at a time per worker, so a worker that stops mid-job leaves it for the next one.

### Choosing a model

The default, `qwen2.5:3b`, needs about 2.5 GB of memory and chooses well, but on a CPU it takes a few minutes per set. Smaller models are faster, but choose less well:

```bash
docker exec ollama ollama pull qwen2.5:0.5b     # or qwen2.5:1.5b
cd backend/recommendation-worker
RECOMMENDATION_MODEL=qwen2.5:0.5b ./mvnw quarkus:dev
```

`RECOMMENDATION_CANDIDATES` (default 30) and `RECOMMENDATION_FRIEND_CANDIDATES` (default 20) set how many movies the model reads; fewer is faster. Sets already made stay as they are when you change the model; each user's next set, after their profile changes, uses the new one.

On a virtual machine, check that the CPU supports AVX (`grep -c avx /proc/cpuinfo` prints more than 0): without it, the model runs several times slower. In Proxmox, for example, set the VM's CPU type to `host`.

`docker exec ollama ollama ps` shows the loaded model, and `docker logs -f ollama` its progress while it answers.

### Service accounts

Some calls aren't made for a user: the background agent reads every profile, and the worker looks up movies while nobody waits. So both services log in to Keycloak as themselves, with a client id and secret (the OAuth client credentials grant), and send that token instead:

| Client | Calls | Secret in dev |
|---|---|---|
| `recommendation-service` | profiles-service's `/internal/...` endpoints (with the `profiles-reader` role), and movies-service | `recommendation-service-dev-secret`, or `RECOMMENDATION_SERVICE_SECRET` |
| `recommendation-worker` | movies-service | `recommendation-worker-dev-secret`, or `RECOMMENDATION_WORKER_SECRET` |

Set real secrets anywhere but on your own machine, in Keycloak (the client's **Credentials** tab) and in the matching variable.

### Tests

```bash
./mvnw test
```

recommendation-service's tests use a temporary PostgreSQL container, like the other APIs'. Kafka is replaced by in-memory channels (`InMemoryKafka`), so tests send the worker's results themselves, and profiles-service and movies-service are Mockito mocks. The background agent and the sweeper don't run on a schedule there; the tests call them.

The worker's tests need neither Docker nor Ollama: the model and movies-service are replaced by a mock and a small in-memory catalogue (`FakeMovies`).

### Production build

As for the other APIs: `./mvnw package`, then `java -jar target/quarkus-app/quarkus-run.jar`. Each `application.properties` ends with the environment variables it reads in production, e.g. `KAFKA_BOOTSTRAP_SERVERS`, the service secrets, and the other services' URLs (`QUARKUS_REST_CLIENT_PROFILES_API_URL`, `QUARKUS_REST_CLIENT_MOVIES_API_URL`, `QUARKUS_REST_CLIENT_OLLAMA_URL`).

## Accounts and login

User accounts live in [Keycloak](https://www.keycloak.org/), in a realm called `movies`. The realm is defined in `infra/keycloak/movies-realm.json`, which Keycloak imports on its first start.

- **Anyone can create an account** from the login page's "Register" link: username, email, first and last name, and a password of at least 8 characters that isn't the username or email.
- **Users log in with their username or email.** Repeated wrong passwords temporarily lock the account.
- **There's no email verification or password reset yet**, because Keycloak has no mail server configured.
- **Every logged-in user can do everything** the APIs offer, except change someone else's profile or see someone else's watchlist and recommendations. Users have no roles; the realm's one role, `profiles-reader`, belongs to recommendation-service's own account (see [Service accounts](#service-accounts)).

### How the pieces fit

1. An app sends the user to Keycloak's login page. Keycloak handles registration and login, then sends the user back to the app with a code.
2. The app exchanges that code for an **access token**: a signed JWT that says who the user is, valid for 5 minutes.
3. The app sends the token to the APIs in the `Authorization: Bearer` header. Each API checks the signature, the expiry, the issuer (`http://localhost:8180/realms/movies`) and the audience. They never see passwords.

The realm has two apps (clients) that can log users in. Both are public clients using the authorization code flow with PKCE, and both add `movies-service`, `profiles-service` and `recommendation-service` to their tokens' audience. Each API rejects tokens without its own name there, so a token issued for some other app in the realm doesn't work against them.

| Client | Used by | Redirect URIs |
|---|---|---|
| `movies-frontend` | The Next.js app | `http://localhost:3000/*` |
| `movies-swagger-ui` | The APIs' Swagger UIs | `http://localhost:8080/q/swagger-ui/*`, `http://localhost:8081/q/swagger-ui/*`, `http://localhost:8082/q/swagger-ui/*` |

Two more clients, `recommendation-service` and `recommendation-worker`, are confidential: they don't log users in, only themselves (see [Service accounts](#service-accounts)).

### Calling the APIs yourself

1. Open `http://localhost:8080/q/swagger-ui` (movies), `http://localhost:8081/q/swagger-ui` (profiles) or `http://localhost:8082/q/swagger-ui` (recommendations) and click **Authorize**. Leave `client_secret` empty, tick the `openid` scope, and click **Authorize** again.
2. Log in on the Keycloak page that opens, or click **Register** to create an account first.
3. Back in Swagger UI, expand an endpoint and use **Try it out**. The request goes out with your token.

The `curl` command Swagger UI shows for each request includes the token, if you want to reuse it from a terminal. It expires after 5 minutes.

### Keycloak admin console

`http://localhost:8180/admin`, user `admin`, password `admin`. Switch to the `movies` realm in the top-left menu to see users, clients and realm settings.

Changes made in the console are stored in Keycloak's database, not in `movies-realm.json`. The realm file is only imported when the realm doesn't exist yet, so later edits to it don't reach an existing Keycloak. To apply them, either make the same change in the console, or delete the `movies` realm in the console (**this deletes its user accounts**) and restart Keycloak with `docker compose -f infra/compose.yaml restart keycloak`.

**If your Keycloak was set up before the profiles API existed**, run this once. It adds the `profiles-service` audience and the second Swagger UI address, and keeps all accounts:

```bash
./infra/keycloak/add-profiles-service.sh
```

**If it was set up before the recommendation services existed**, run this too, after the one above. It adds the `profiles-reader` role, the two service clients with their dev secrets, the `recommendation-service` audience and the third Swagger UI address, and also keeps all accounts:

```bash
./infra/keycloak/add-recommendations.sh
```

Then log out and in again, so your token includes the new audiences.

## 5. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:3000`. Keycloak and the movies and profiles APIs must be running (steps 1 to 3). Without step 4, everything works except the recommendations.

| Page | What it does |
|---|---|
| `/` | A top bar with **Log in** and **Register**. Both go to Keycloak's pages, and then on to `/movies`. |
| `/movies` | **Recommended for you** on top: a row of ten movies picked for you. The first set takes a few minutes and appears on its own when it's ready. After that, it's remade in the background whenever your profile, or what the people you follow watched, changes, usually within 10 minutes plus the model's time, and the row updates itself. Below it, all movies in a grid that loads more as you scroll. Below the top bar: a search box (type, then press **Enter**; empty + **Enter** shows all movies again) and a **Sort by** dropdown (newest first, which is the default, oldest first, highest or lowest rated, title A–Z or Z–A; while searching there's also best match, the default for searches). Search and sort are kept in the URL, e.g. `/movies?q=batman&sort=newest`, so reloading or going back keeps them. |
| `/movies/<id>` | One movie: poster, title, release date, rating and vote count, genres, description, links to its IMDb (when known) and TMDB pages, **Mark as watched** and **Watch later**. Each shows its state once clicked (**Watched ✓**, **On your watchlist ✓**) and undoes it when clicked again; marking a movie as watched also takes it off the watchlist. Clicking a movie in the grid opens it, and **← Back** (or the browser's back button) returns to the page you came from as you left it: the same search, sort, loaded movies and scroll position, or the Ask AI answer you had open. |
| `/recommendations` | **Ask AI**: describe what you feel like watching, press **Ask** (or **Enter**), and get ten movies you haven't watched or saved. It takes a few minutes; you can leave the page meanwhile, and a notification says when it's ready or why it failed. One request at a time: the box is disabled while one is being prepared. On the left, your last five requests; click one to see its answer again. |
| `/users` | All users, by username, loading more as you scroll, with a search box (type the start of a username, then press **Enter**). |
| `/users/<id>` | A user's profile: favourite genres, and tabs for the movies they've watched, who they follow and who follows them. Other users' profiles have **Follow**. On your own (`/users/me`), **Edit** picks your favourite genres from all 20, and a **Watch later** tab shows your watchlist, which nobody else sees. |

Every page under `/movies`, `/recommendations` and `/users` needs a login: opening one while logged out goes to Keycloak's login page first, then back to that page. The top bar has **Movies**, **Ask AI** and **Users**, and on the right your username, which opens your profile, and **Log out**.

**Log out** ends the Keycloak session too, so the next login asks for the password again.

### How the login works

The frontend uses [`keycloak-js`](https://www.npmjs.com/package/keycloak-js), Keycloak's own browser library, with the `movies-frontend` client:

1. **Log in** or **Register** sends the browser to Keycloak's login or sign-up page.
2. Keycloak sends the browser back to `/movies` with a one-time code, which `keycloak-js` exchanges for an access token, using PKCE.
3. The pages call the APIs from the browser with `Authorization: Bearer <token>`. Before each API call, the token is renewed if it's about to expire.

The token only lives in the page's memory, so reloading `/movies` goes through Keycloak again. That's quick, because Keycloak still remembers the login, so it doesn't ask for the password.

The code is in `frontend/lib/keycloak.ts` (Keycloak setup) and `frontend/lib/api.ts` (calls to the APIs). Because the pages call the APIs from a different origin (`localhost:3000` to `localhost:8080`, `8081` and `8082`), the APIs allow that origin through CORS: `quarkus.http.cors.origins` in their `application.properties`.

While you're logged in, the app keeps the recommendations API's event stream open (`frontend/lib/recommendation-events.ts`), to hear when a request is ready. It reads the stream with `fetch`, because the browser's `EventSource` can't send the `Authorization` header, and reopens it every 4 minutes with a fresh token, or after a growing pause if it drops. After it reopens, pages that are still waiting ask the API again, in case they missed an event meanwhile.

### Configuration

The defaults match the local setup. To point the frontend elsewhere, set these in `frontend/.env.local`. They're read at build time.

| Variable | Default |
|---|---|
| `NEXT_PUBLIC_MOVIES_API_URL` | `http://localhost:8080` |
| `NEXT_PUBLIC_PROFILES_API_URL` | `http://localhost:8081` |
| `NEXT_PUBLIC_RECOMMENDATIONS_API_URL` | `http://localhost:8082` |
| `NEXT_PUBLIC_KEYCLOAK_URL` | `http://localhost:8180` |
| `NEXT_PUBLIC_KEYCLOAK_REALM` | `movies` |
| `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` | `movies-frontend` |

### Running on a remote machine

If the project runs on another machine (a VM, or through IntelliJ remote development) and your browser runs on your own computer, forward ports **3000**, **8080**, **8081**, **8082** and **8180** to the **same port numbers** on your computer. Keycloak's login redirects and tokens name `http://localhost:8180` and `http://localhost:3000` exactly, so different local port numbers break the login.

## Code layout

Each backend service keeps its Java code in `src/main/java/psia/g17/`, split into packages by role:

| Package | Contents |
|---|---|
| `resource` | The REST endpoints |
| `dto` | What the endpoints take and return |
| `entity` | The database tables, as Hibernate entities |
| `client` | REST clients for the other services and Ollama |
| `service` | The logic between them, e.g. search, the recommendation pipeline |
| `events` | The messages sent through Kafka |
| `messaging` | Kafka consumers |
| `scheduler` | Scheduled jobs: the background agent, and the sweeper that fails requests stuck pending |
| `security` | Who the logged-in user is |
| `metrics` | Totals counted on a schedule for Prometheus (most metrics are recorded where they happen) |
| `openapi` | Swagger UI's Keycloak login |
| `health` | Health checks |

A service only has the packages it needs. Tests mirror the same packages under `src/test/java/psia/g17/`.

Each service exposes Prometheus metrics at `/q/metrics` (Micrometer). Besides the ones Micrometer and Quarkus give for free (HTTP requests per endpoint, the JVM, the database pool, the Kafka clients), each service records **its own application metrics**, which the Grafana dashboard is built on:

| Service | Metric | Type | Labels | What it says |
|---|---|---|---|---|
| movies-service | `movies_searches_seconds` | timer | `results` (none, some), `similar_titles` | Searches, how long they took, how many found nothing |
| movies-service | `movies_candidates_seconds` | timer | `ranking` (meaning, words, taste) | The worker's candidate queries, and whether search by meaning was available |
| movies-service | `movies_embeddings_requests_seconds` | timer | `purpose` (query, backfill), `outcome` | Each embedding call to Ollama |
| movies-service | `movies_embeddings_query_cache_total` | counter | `result` (hit, miss) | Ask AI requests whose vector was already known |
| movies-service | `movies_embeddings_backfilled_total` | counter | | Movies embedded by the background job |
| movies-service | `movies_embeddings_missing` | gauge | | Movies still to embed (counted each minute) |
| profiles-service | `profiles_actions_total` | counter | `action` (watched_added, watched_removed, watchlist_added, watchlist_removed, followed, unfollowed, favorite_genres_set) | What users change in their profiles |
| profiles-service | `profiles_stored` | gauge | `what` (profiles, follows, watched_movies, watchlist_entries, favorite_genres) | What the database holds (counted every 30 s) |
| recommendation-service | `recommendation_requests_total` | counter | `kind` (profile, prompt), `outcome` (queued, reused, repeated, already_pending, rejected, unavailable) | Demand, and how often an answer needs no model (reused, repeated) |
| recommendation-service | `recommendation_turnaround_seconds` | timer | `kind`, `status` (done, failed, timed_out) | How long users wait, from the request to its answer |
| recommendation-service | `recommendation_pending` | gauge | `kind` | Requests waiting for an answer (counted every 30 s) |
| recommendation-service | `recommendation_sse_connections` | gauge | | Open live connections: about the users with the app open |
| recommendation-service | `recommendation_agent_queued_total` | counter | | Profile sets the background agent prepared |
| recommendation-worker | `recommendation_jobs_seconds` | timer | `kind`, `status` (done, failed) | The worker's time per job |
| recommendation-worker | `recommendation_jobs_waited_seconds` | timer | `kind` | How long a job waited before the worker took it (in Kafka, behind others) |
| recommendation-worker | `recommendation_llm_requests_seconds` | timer | `model`, `outcome` | Each answer from Ollama, which has no metrics of its own |
| recommendation-worker | `recommendation_llm_tokens_total` | counter | `model`, `type` (prompt, output) | Tokens Ollama read and wrote |

A timer gives `_count`, `_sum`, `_max` and `_bucket` (a histogram, for percentiles). The long ones (Ollama, jobs, turnaround) have buckets up to 30 minutes or an hour: Micrometer's default stops at 30 seconds, which a CPU-bound model call easily exceeds. Totals that need the database (`*_stored`, `*_pending`, `*_missing`) are counted on a schedule, so a scrape never waits on a query.

The deployment lives next to the code:

| Path | Contents |
|---|---|
| `backend/Dockerfile` | Builds any of the four Quarkus services' images from source: `docker build -f backend/Dockerfile backend/<service>` |
| `frontend/Dockerfile` | The frontend's image (Next.js standalone server), calling the APIs at `/api` and Keycloak at `/auth` on its own origin |
| `infra/compose.yaml`, `infra/keycloak/`, `infra/init/` | The local infrastructure, and the Keycloak realm both setups import |
| `backend/compose.yaml`, `frontend/compose.yaml` | The four services and the website in Docker, for the local setup |
| `justfile` (project root) | The local commands: `just local-first-up`, `just local-up`, `just local-down` |
| `infra/terraform/` | Google Cloud: network, firewall, static IP, VMs |
| `infra/ansible/` | Playbooks that install Kubernetes and deploy everything onto it, and their settings (`group_vars/all.yml`) |
| `infra/k8s/` | The Kubernetes manifests (templates Ansible fills in) and Grafana's dashboard |
| `infra/mgmt/`, `infra/justfile`, `infra/build-images.sh` | The management container, the cloud deployment's commands, and the image build |

## Troubleshooting

| Problem | Fix |
|---|---|
| `The JAVA_HOME environment variable is not defined correctly` | Set `JAVA_HOME` as shown in [Java 27 with SDKMAN!](#java-27-with-sdkman). |
| `Bind for 0.0.0.0:5432 failed: port is already allocated` (or 5433) | Another PostgreSQL is running on that port. Stop it, or change the host port in `infra/compose.yaml` and update `%dev.quarkus.datasource.jdbc.url` in the matching service's `src/main/resources/application.properties`. |
| `The container name "/postgres" is already in use` | A container named `postgres` already exists, maybe from another project. If it's this database, keep using it. Otherwise stop and remove it with `docker rm -f postgres`, which **deletes its data unless it's stored in a volume**. |
| The movies API starts, but `/movies` returns no movies | It started before the dump was restored, so Flyway created empty tables. Reset the database with `docker compose -f infra/compose.yaml down -v` (this deletes all its data) and `docker compose -f infra/compose.yaml up -d`, then restore the dump before starting the APIs again. |
| The restore prints many `already exists` errors | Same cause: the movies API created the tables first. Reset the database as in the previous row, then restore before starting the APIs. |
| An API fails at startup with `Validate failed: Migrations have failed validation` | A migration file changed after it had already run. Undo the edit, e.g. with `git checkout` on that file, and put the change in a new `V<next>__...sql` file instead. |
| An API returns `401` even with a token | Tokens expire after 5 minutes: get a new one. If the token comes from your own app, check that it was issued by the `movies-frontend` or `movies-swagger-ui` client, and that its `iss` claim is exactly `http://localhost:8180/realms/movies`. Decoding it at [jwt.io](https://jwt.io) shows both. |
| Keycloak doesn't become `healthy`, and its logs mention `database "keycloak" does not exist` | The database volume predates Keycloak. Create the database as shown in [step 1](#1-start-the-databases-and-keycloak). |
| A change to `movies-realm.json` has no effect | The realm is only imported the first time. See [Keycloak admin console](#keycloak-admin-console). |
| `network movies-net declared as external, but could not be found` | The infrastructure isn't running: `backend/compose.yaml` joins the network that `infra/compose.yaml` creates. Use `just local-up`, or start the infrastructure first. |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` (or 8081, 8082, 8083, 3000) | A service started by hand with `./mvnw` or `npm run dev` is still running. Stop it, and use one way or the other. |
| `ports are not available: exposing port TCP 0.0.0.0:11434` on Windows | The Ollama app for Windows is using that port. Quit it from the system tray, then run the recipe again. |
| A container shows `starting` or `unhealthy` right after the launch | The Java services need up to about 40 seconds. Wait, then check `docker ps`; if it stays unhealthy, read its log: `docker compose -f backend/compose.yaml logs <service>`. |
| The movies page says `Couldn't load movies: Failed to fetch` | The browser couldn't reach the movies API. Check that it's running, and that port 8080 is forwarded if you use a remote machine. If the browser console mentions CORS, the page's origin isn't in `quarkus.http.cors.origins`. |
| Log in or Register does nothing, or Keycloak's page doesn't load | The browser can't reach `http://localhost:8180`. Forward port 8180; see [Running on a remote machine](#running-on-a-remote-machine). |
| Most movies show "No poster" | Expected: TMDB has since deleted most of the poster files the 2017 dataset points to (only about 1 in 10 still exists). |
| profiles-service fails at startup with `Connection to localhost:5433 refused` | `profiles-db` isn't running: `docker compose -f infra/compose.yaml up -d profiles-db`. |
| A profile page or button says `The movies service isn't available` | profiles-service couldn't reach movies-service: start the movies API (step 2). |
| The Users page or a profile says `Failed to fetch` | The profiles API isn't reachable: start it (step 3), and forward port 8081 if you use a remote machine. |
| The profiles API returns `401` even though the movies API works | Your token predates the `profiles-service` audience. Run `./infra/keycloak/add-profiles-service.sh` if you haven't, then log out and in again. |
| Recommended for you stays at "Picking movies for you" for many minutes | The worker is still on it, or on other users' jobs first: on a CPU, each set takes minutes. `docker exec ollama ollama ps` shows whether the model is loaded, and the worker's log which request it's on. Start the worker (step 4) if it isn't running, or use a smaller model (see [Choosing a model](#choosing-a-model)). After an hour, the request fails; the agent tries again later. |
| Requests fail with `model "qwen2.5:3b" not found` | The model hasn't been downloaded: `docker compose -f infra/compose.yaml up ollama-models`, or `docker exec ollama ollama pull <model>` for another one. |
| Asking for recommendations returns `Couldn't queue the request: ...` | recommendation-service can't reach Kafka: `docker compose -f infra/compose.yaml up -d kafka`. |
| Recommendations fail with `Couldn't read the profile: ...` | recommendation-service can't reach profiles-service (start it, step 3), or Keycloak doesn't know the service clients yet: run `./infra/keycloak/add-recommendations.sh`. |
| The recommendations API returns `401` even though the other APIs work | Your token predates the `recommendation-service` audience. Run `./infra/keycloak/add-recommendations.sh` if you haven't, then log out and in again. |
| The machine runs out of memory | The model is the biggest part: about 2.5 GB for `qwen2.5:3b`, 750 MB for `qwen2.5:0.5b`, capped at `OLLAMA_MEMORY_LIMIT` (4 GB) and unloaded after 10 idle minutes. Keycloak takes about 600 MB and Kafka 500 MB. Each `./mvnw quarkus:dev` runs a Maven process next to its service (whose heap is capped at 256 MB by `jvm.args` in its `pom.xml`); for less memory, run the services packaged instead, about 200 MB each: `./mvnw package -DskipTests`, then `java -Xmx256m -Dquarkus.profile=dev -jar target/quarkus-app/quarkus-run.jar`, which uses the same ports and settings as dev mode. |
| Port 8080, 8081, 8082 or 3000 is in use | Find what's using it with `ss -ltnp \| grep :<port>` (or `lsof -i :<port>`), often an API or frontend you started earlier, and stop it. To use another port instead, start the API with `-Dquarkus.http.port=<port>` or the frontend with `npm run dev -- -p <port>`, and update the matching settings from [Project configuration](#project-configuration). |
