# recommendation-service

The recommendations API serves "Recommended for you" and "Ask AI". It answers at once (`202`), queues the job for recommendation-worker on Kafka, stores the result, and tells the browser over server-sent events. A background agent keeps each user's "Recommended for you" up to date when their profile's fingerprint changes, and a sweeper fails requests that stay pending for an hour.

- **Port:** 8082 in dev (Swagger UI at `http://localhost:8082/q/swagger-ui`)
- **Database:** the recommendations database (`recommendations-db` in `infra/compose.yaml`, `localhost:5434`), schema `recommendations`, with migrations in `src/main/resources/db/migration/`
- **Needs:** that database, Kafka, Keycloak (users' tokens and its own service account), profiles-service and movies-service

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs Docker: the tests start their own PostgreSQL; Kafka and the other services are replaced
```

The full documentation is in the [backend README → recommendation-service](../README.md#recommendation-service), with [Recommended for you](../README.md#recommended-for-you), [Ask AI](../README.md#ask-ai) and [Kafka topics](../README.md#kafka-topics).
