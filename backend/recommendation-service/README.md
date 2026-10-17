# recommendation-service

The recommendations API serves "Recommended for you" and "Ask AI". It answers at once (`202`), queues the job for recommendation-worker on Kafka, stores the result, and tells the browser over server-sent events. A background agent keeps each user's "Recommended for you" up to date when their profile's fingerprint changes, and a sweeper fails requests that stay pending for an hour.

- **Recommended for you** (`PROFILE`) gives ten movies picked from the user's favourite genres, what they watched and saved, and what the people they follow watched. A background agent keeps it up to date (see [Recommended for you](#recommended-for-you)).
- **Ask AI** (`PROMPT`) gives ten movies matching what the user typed, e.g. "a horror movie from the 80s", from what they haven't watched or saved yet (see [Ask AI](#ask-ai)).


- **Port:** 8082 in dev (Swagger UI at `http://localhost:8082/q/swagger-ui`)
- **Database:** the recommendations database (`recommendations-db` in `infra/compose.yaml`, `localhost:5434`), schema `recommendations`, with migrations in `src/main/resources/db/migration/`
- **Needs:** that database, Kafka, Keycloak (users' tokens and its own service account), profiles-service and movies-service

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs Docker: the tests start their own PostgreSQL; Kafka and the other services are replaced
```

### Endpoints

All need a user's access token, and each user only sees their own requests.

| Method | Path | Does |
|---|---|---|
| `GET` | `/recommendations/me` | Recommended for you, as `{ status, requestId, generatedAt, refreshing, error, movies }`. `status` is `READY` (with `movies`), `PREPARING` (the first set is being made, which this call starts if needed) or `FAILED` (with `error`, and the agent tries again later). `refreshing` says whether a newer set is being made |
| `POST` | `/recommendations/requests` | Asks for movies matching `{ "prompt": "..." }` (up to 300 characters). It returns `202` with the `PENDING` request, or `200` with the answer if the same question was answered for the same profile. It returns `409` with `{ error, requestId }` while another request is being prepared |
| `GET` | `/recommendations/requests` | The request being prepared, if any, then the last five answers, newest first, without their movies |
| `GET` | `/recommendations/requests/current` | The request being prepared, or else the last one that failed in the past hour (with `error`). `204` if there's neither |
| `GET` | `/recommendations/requests/{id}` | One request, `{ id, kind, prompt, status, error, createdAt, completedAt, model, movies }`, with its `movies` once it's `DONE`, best first, as `[{ id, title, posterPath, releaseDate, rating }]`. `404` for other users' requests |
| `GET` | `/recommendations/events` | [Server-sent events](https://developer.mozilla.org/docs/Web/API/Server-sent_events) carrying `recommendations-ready`, with `{ requestId, userId, kind, status, error }`, whenever one of the user's requests finishes. It sends `connected` when it opens, and a comment every 20 seconds to keep it open |

If Kafka or profiles-service is down, `POST /recommendations/requests` returns `503` with `{ "error": "Couldn't queue the request: ..." }` or `"Couldn't read the profile: ..."`, and `GET /recommendations/me` returns `FAILED` with the same message.

```bash
curl -H "Authorization: Bearer $TOKEN" localhost:8082/recommendations/me
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt": "a horror movie from the 80s"}' localhost:8082/recommendations/requests
curl -N -H "Authorization: Bearer $TOKEN" localhost:8082/recommendations/events    # waits for events
```

### Data

Flyway migrations in `recommendation-service/src/main/resources/db/migration/` (`V1__recommendations_schema.sql`, `V2__fingerprints_and_profile_state.sql`, `V3__drop_reasons.sql`) create the `recommendations` schema. Users and movies are stored as ids only, and titles and posters come from movies-service when a request is read.

| Table | Contents |
|---|---|
| `recommendations.request` | One row per request, recording whose it is, which kind, the prompt, `PENDING`, `DONE` or `FAILED` (with why), the model and recipe, the fingerprints it was made from, how long the worker took, and when |
| `recommendations.request_item` | A finished request's movies, in order |
| `recommendations.profile_state` | For each user, the fingerprints and recipe their current Recommended for you was made from, and the failed attempts since then |

### Tests

```bash
cd backend/recommendation-service
./mvnw test
```

They use a temporary PostgreSQL container, like the other APIs'. Kafka is replaced by in-memory channels (`InMemoryKafka`), so the tests send the worker's results themselves, and profiles-service and movies-service are Mockito mocks. The background agent and the sweeper don't run on a schedule. `BackgroundJobsTest` calls them instead. `RecommendationEventsTest` covers the SSE stream.
