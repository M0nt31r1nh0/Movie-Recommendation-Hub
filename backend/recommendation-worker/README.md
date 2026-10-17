# recommendation-worker

Takes recommendation jobs from Kafka (`recommendation.requests`), one at a time, narrows the catalogue down to a few dozen candidates through movies-service, lets the language model in Ollama choose ten of them under a JSON schema, and publishes the result to `recommendation.results`. It has no API and no database.

- **Port:** 8083, for `/q/health` and `/q/metrics` only. It's ready only when Ollama answers and has the model downloaded.
- **Database:** none, because each job carries a snapshot of the user's profile
- **Needs:** Kafka, Ollama with `qwen2.5:3b` (or `RECOMMENDATION_MODEL`), Keycloak (its own service account) and movies-service

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs neither Docker nor Ollama
```

### Tests

```bash
cd backend/recommendation-worker
./mvnw test
```

They need neither Docker nor Ollama. `RecommendationPipelineTest` replaces the model with a Mockito mock and movies-service with a small in-memory catalogue (`FakeMovies`). `RecommendationWorkerTest` sends jobs through in-memory channels instead of Kafka (`InMemoryKafka`), with the pipeline mocked, and checks the results, their keys and the metrics.
