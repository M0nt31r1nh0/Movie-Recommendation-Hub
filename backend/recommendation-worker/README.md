# recommendation-worker

Takes recommendation jobs from Kafka (`recommendation.requests`), one at a time, narrows the catalogue down to a few dozen candidates through movies-service, lets the language model in Ollama choose ten of them under a JSON schema, and publishes the result to `recommendation.results`. It has no API and no database.

- **Port:** 8083, for `/q/health` and `/q/metrics` only. It's ready only when Ollama answers and has the model downloaded.
- **Database:** none, because each job carries a snapshot of the user's profile
- **Needs:** Kafka, Ollama with `qwen2.5:3b` (or `RECOMMENDATION_MODEL`), Keycloak (its own service account) and movies-service

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs neither Docker nor Ollama
```

The full documentation is in the [backend README → recommendation-worker](../README.md#recommendation-worker), with [Ask AI](../README.md#ask-ai) and [choosing a model](../README.md#choosing-a-model).
