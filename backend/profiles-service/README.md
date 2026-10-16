# profiles-service

The profiles API holds each user's favourite genres, watched movies, private watch-later list and follows. A profile is created on the user's first request, from their Keycloak token. Movies and genres are stored as ids only and checked against movies-service. It also serves recommendation-service the profile snapshots and fingerprints that recommendations are made from (`/internal/profiles`, role `profiles-reader`).

- **Port:** 8081 in dev (Swagger UI at `http://localhost:8081/q/swagger-ui`)
- **Database:** the profiles database (`profiles-db` in `infra/compose.yaml`, `localhost:5433`), schema `profiles`, with migrations in `src/main/resources/db/migration/`
- **Needs:** that database, Keycloak, and movies-service for anything involving movies or genres

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs Docker: the tests start their own PostgreSQL; movies-service is mocked
```

The full documentation is in the [backend README → profiles-service](../README.md#profiles-service), with [profile changes and fingerprints](../README.md#profile-changes).
