# Movies

A movie catalogue built on [The Movies Dataset](https://www.kaggle.com/datasets/rounakbanik/the-movies-dataset) (about 45,000 TMDB movies).

| Part | Path | Stack | Port |
|---|---|---|---|
| Database | `infra/compose.yaml`, `data/` | PostgreSQL 16 in Docker | 5432 |
| Accounts and login | `infra/compose.yaml`, `infra/keycloak/` | Keycloak 26 in Docker | 8180 |
| Movies API | `backend/movies-service/` | Java 21+, Quarkus, Hibernate ORM with Panache | 8080 |
| Frontend | `frontend/` | Next.js 16, React 19, Tailwind CSS 4 | 3000 |

## Install and configure

You need four tools. The versions below are the ones the project is developed with.

| Tool | Version | Needed for |
|---|---|---|
| Docker Engine with the Compose plugin | Compose v2+ | The database, Keycloak, and the backend tests |
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
| Keycloak admin user and password | `admin`, `admin` | Set `KEYCLOAK_ADMIN` or `KEYCLOAK_ADMIN_PASSWORD` before the first `docker compose up` |
| Keycloak port | `8180` | `ports` and `KC_HOSTNAME` in `infra/compose.yaml`, and `%dev.quarkus.oidc.auth-server-url` in `application.properties` |
| API port | `8080` | `-Dquarkus.http.port=<port>` when starting the API |
| Frontend port | `3000` | `npm run dev -- -p <port>` |

## 1. Start the database and Keycloak

```bash
docker compose -f infra/compose.yaml up -d
```

This starts two containers:

- **PostgreSQL** on `localhost:5432`, with database `movies`, user `postgres` and password `postgres`. To use different values, set `POSTGRES_USER`, `POSTGRES_PASSWORD` or `POSTGRES_DB` before running it. The data is kept in the `postgres_data` Docker volume, so it survives restarts.
- **Keycloak** on `localhost:8180`, which handles user accounts and login (see [Accounts and login](#accounts-and-login)). It stores its data in a separate `keycloak` database in the same PostgreSQL server, and needs about 30 seconds to start. `docker ps` shows it as `healthy` when it's ready.

The `keycloak` database is created automatically, by `infra/init/01-keycloak.sql`, but only when the `postgres_data` volume is new. **If your database volume existed before Keycloak was added**, create it once by hand, then start Keycloak:

```bash
docker exec -i postgres psql -U postgres < infra/init/01-keycloak.sql
docker compose -f infra/compose.yaml up -d keycloak
```

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

To start over from scratch, run `docker compose -f infra/compose.yaml down -v`. **This deletes all the data, including every user account**, and you'll need to restore the dump again.

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

In dev mode the service connects to the database and Keycloak from step 1. Every `/movies` endpoint needs a logged-in user: see [Accounts and login](#accounts-and-login) for how to get a token.

### Database migrations

The service manages schema changes with [Flyway](https://documentation.red-gate.com/flyway). On every startup, it applies any SQL file in `backend/movies-service/src/main/resources/db/migration/` that the database hasn't run yet, in version order, and records it in the `movies.flyway_schema_history` table.

| File | What it does |
|---|---|
| `V1__movies_schema.sql` | The base schema, the same as in the dump. It only runs on an empty database, such as in tests. A database restored from the dump is recorded as already at version 1. |
| `V2__movie_full_text_search.sql` | Adds the full-text search columns `search_vector` and `title_vector` to `movies.movie`, with their indexes. |
| `V3__movie_title_fuzzy_search.sql` | Enables the `pg_trgm` extension and adds a trigram index on `title`, for typo-tolerant search. |
| `V4__movie_imdb_id.sql` | Adds `imdb_id` to `movies.movie`, filled from the dump's CSV staging table (45,413 of 45,430 movies have one). |
| `V5__movie_accent_insensitive_search.sql` | Enables the `unaccent` extension and rebuilds the search columns and the trigram index without accents. The rebuilt columns only hold values computed from title and description, so no data is lost. |

The migrations only add to the schema; none of them drops anything. The first start after restoring the dump takes about 15 extra seconds while V2 to V5 fill in the new columns for all 45,430 movies. After that, startup skips them.

To change the schema, **add a new file** with the next version number, e.g. `V4__add_movie_runtime.sql`. Never edit a migration that has already run anywhere: Flyway compares every applied file with its recorded checksum, and refuses to start if one changed.

Hibernate doesn't change the schema at all. In dev and tests, it only checks at startup that the Java entities match the tables.

### Endpoints

| Method | Path | Query parameters | Returns |
|---|---|---|---|
| `GET` | `/movies` | `page`: default `0`, zero-based<br>`size`: default `20`, 1–100<br>`sort`: `releaseDate` (default), `id`, `title` or `rating`<br>`order`: `asc` or `desc`. Without `sort`, it's newest first (`releaseDate`, `desc`); a `sort` without `order` is ascending | A page of movies: `{ items, page, size, totalItems, totalPages }` |
| `GET` | `/movies/search` | `q`: required, up to 200 characters<br>`page`, `size`: as for `/movies`<br>`sort`: `relevance` (default), `id`, `title`, `rating` or `releaseDate`<br>`order`: `asc` (default) or `desc`; ignored for `relevance` | A page of matching movies, best matches first unless `sort` names a field |
| `GET` | `/movies/{id}` | | One movie, or `404` |

All three need an access token in an `Authorization: Bearer <token>` header. Without a valid one they return `401`; any logged-in user can use all of them. An invalid parameter returns `400`. In `/movies`, movies without a value for the sort field always come last.

```bash
TOKEN=...   # see "Calling the API yourself" below
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

### Tests

```bash
./mvnw test
```

The tests don't touch your database. They start their own temporary PostgreSQL container, so Docker must be running. Flyway builds the schema there from the migrations, then loads a few sample movies from `src/test/resources/db/testdata/R__test_data.sql`.

The tests don't need Keycloak either. They act as a logged-in user with Quarkus's `@TestSecurity`, and `MovieResourceAuthTest` checks that requests without a token are rejected.

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

## Accounts and login

User accounts live in [Keycloak](https://www.keycloak.org/), in a realm called `movies`. The realm is defined in `infra/keycloak/movies-realm.json`, which Keycloak imports on its first start.

- **Anyone can create an account** from the login page's "Register" link: username, email, first and last name, and a password of at least 8 characters that isn't the username or email.
- **Users log in with their username or email.** Repeated wrong passwords temporarily lock the account.
- **There's no email verification or password reset yet**, because Keycloak has no mail server configured.
- **Every logged-in user can do everything** the API offers. There are no roles yet.

### How the pieces fit

1. An app sends the user to Keycloak's login page. Keycloak handles registration and login, then sends the user back to the app with a code.
2. The app exchanges that code for an **access token**: a signed JWT that says who the user is, valid for 5 minutes.
3. The app sends the token to the API in the `Authorization: Bearer` header. The API checks the signature, the expiry, the issuer (`http://localhost:8180/realms/movies`) and the audience. It never sees passwords.

The realm has two apps (clients) that can log users in. Both are public clients using the authorization code flow with PKCE, and both add `movies-service` to their tokens' audience. The API rejects tokens without it, so a token issued for some other app in the realm doesn't work against the API.

| Client | Used by | Redirect URIs |
|---|---|---|
| `movies-frontend` | The Next.js app | `http://localhost:3000/*` |
| `movies-swagger-ui` | The API's Swagger UI | `http://localhost:8080/q/swagger-ui/*` |

### Calling the API yourself

1. Open `http://localhost:8080/q/swagger-ui` and click **Authorize**. Leave `client_secret` empty, tick the `openid` scope, and click **Authorize** again.
2. Log in on the Keycloak page that opens, or click **Register** to create an account first.
3. Back in Swagger UI, expand an endpoint and use **Try it out**. The request goes out with your token.

The `curl` command Swagger UI shows for each request includes the token, if you want to reuse it from a terminal. It expires after 5 minutes.

### Keycloak admin console

`http://localhost:8180/admin`, user `admin`, password `admin`. Switch to the `movies` realm in the top-left menu to see users, clients and realm settings.

Changes made in the console are stored in Keycloak's database, not in `movies-realm.json`. The realm file is only imported when the realm doesn't exist yet, so later edits to it don't reach an existing Keycloak. To apply them, either make the same change in the console, or delete the `movies` realm in the console (**this deletes its user accounts**) and restart Keycloak with `docker compose -f infra/compose.yaml restart keycloak`.

## 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:3000`. Keycloak and the API must be running (steps 1 and 2).

| Page | What it does |
|---|---|
| `/` | A top bar with **Log in** and **Register**. Both go to Keycloak's pages, and then on to `/movies`. |
| `/movies` | All movies in a grid that loads more as you scroll. Below the top bar: a search box (type, then press **Enter**; empty + **Enter** shows all movies again) and a **Sort by** dropdown (newest first, which is the default, oldest first, highest or lowest rated, title A–Z or Z–A; while searching there's also best match, the default for searches). Search and sort are kept in the URL, e.g. `/movies?q=batman&sort=newest`, so reloading or going back keeps them. |
| `/movies/<id>` | One movie: poster, title, release date, rating and vote count, genres, description, and links to its IMDb (when known) and TMDB pages. Clicking a movie in the grid opens it. |

Every page under `/movies` needs a login: opening one while logged out goes to Keycloak's login page first, then back to that page. The top bar shows the logged-in user and **Log out**.

**Log out** ends the Keycloak session too, so the next login asks for the password again.

### How the login works

The frontend uses [`keycloak-js`](https://www.npmjs.com/package/keycloak-js), Keycloak's own browser library, with the `movies-frontend` client:

1. **Log in** or **Register** sends the browser to Keycloak's login or sign-up page.
2. Keycloak sends the browser back to `/movies` with a one-time code, which `keycloak-js` exchanges for an access token, using PKCE.
3. The page calls the API from the browser with `Authorization: Bearer <token>`. Before each API call, the token is renewed if it's about to expire.

The token only lives in the page's memory, so reloading `/movies` goes through Keycloak again. That's quick, because Keycloak still remembers the login, so it doesn't ask for the password.

The code is in `frontend/lib/keycloak.ts` (Keycloak setup) and `frontend/lib/api.ts` (API calls). Because the page calls the API from a different origin (`localhost:3000` to `localhost:8080`), the API allows that origin through CORS: `quarkus.http.cors.origins` in `application.properties`.

### Configuration

The defaults match the local setup. To point the frontend elsewhere, set these in `frontend/.env.local`. They're read at build time.

| Variable | Default |
|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` |
| `NEXT_PUBLIC_KEYCLOAK_URL` | `http://localhost:8180` |
| `NEXT_PUBLIC_KEYCLOAK_REALM` | `movies` |
| `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` | `movies-frontend` |

### Running on a remote machine

If the project runs on another machine (a VM, or through IntelliJ remote development) and your browser runs on your own computer, forward ports **3000**, **8080** and **8180** to the **same port numbers** on your computer. Keycloak's login redirects and tokens name `http://localhost:8180` and `http://localhost:3000` exactly, so different local port numbers break the login.

## Troubleshooting

| Problem | Fix |
|---|---|
| `The JAVA_HOME environment variable is not defined correctly` | Set `JAVA_HOME` as shown in [Java 27 with SDKMAN!](#java-27-with-sdkman). |
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | Another PostgreSQL is running on 5432. Stop it, or change the host port in `infra/compose.yaml` and update `%dev.quarkus.datasource.jdbc.url` in `backend/movies-service/src/main/resources/application.properties` to match. |
| `The container name "/postgres" is already in use` | A container named `postgres` already exists, maybe from another project. If it's this database, keep using it. Otherwise stop and remove it with `docker rm -f postgres`, which **deletes its data unless it's stored in a volume**. |
| The API starts, but `/movies` returns no movies | The API started before the dump was restored, so Flyway created empty tables. Reset the database with `docker compose -f infra/compose.yaml down -v` (this deletes all its data) and `docker compose -f infra/compose.yaml up -d`, then restore the dump before starting the API again. |
| The restore prints many `already exists` errors | Same cause: the API created the tables first. Reset the database as in the previous row, then restore before starting the API. |
| The API fails at startup with `Validate failed: Migrations have failed validation` | A migration file changed after it had already run. Undo the edit, e.g. with `git checkout` on that file, and put the change in a new `V<next>__...sql` file instead. |
| The API returns `401` even with a token | Tokens expire after 5 minutes: get a new one. If the token comes from your own app, check that it was issued by the `movies-frontend` or `movies-swagger-ui` client, and that its `iss` claim is exactly `http://localhost:8180/realms/movies`. Decoding it at [jwt.io](https://jwt.io) shows both. |
| Keycloak doesn't become `healthy`, and its logs mention `database "keycloak" does not exist` | The database volume predates Keycloak. Create the database as shown in [step 1](#1-start-the-database-and-keycloak). |
| A change to `movies-realm.json` has no effect | The realm is only imported the first time. See [Keycloak admin console](#keycloak-admin-console). |
| The movies page says `Couldn't load movies: Failed to fetch` | The browser couldn't reach the API. Check that it's running, and that port 8080 is forwarded if you use a remote machine. If the browser console mentions CORS, the page's origin isn't in `quarkus.http.cors.origins`. |
| Log in or Register does nothing, or Keycloak's page doesn't load | The browser can't reach `http://localhost:8180`. Forward port 8180; see [Running on a remote machine](#running-on-a-remote-machine). |
| Most movies show "No poster" | Expected: TMDB has since deleted most of the poster files the 2017 dataset points to (only about 1 in 10 still exists). |
| Port 8080 or 3000 is in use | Run the API with `./mvnw quarkus:dev -Dquarkus.http.port=8081`, or the frontend with `npm run dev -- -p 3001`. |
