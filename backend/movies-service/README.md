# movies-service

The movie catalogue API serves about 45,000 movies from The Movies Dataset, with listing, search by words (full-text, prefix, accent-insensitive and typo-tolerant), and search by meaning with embeddings from Ollama in pgvector. It also picks the candidate movies that recommendation-worker's language model chooses from. Every endpoint needs a Keycloak access token.

- **Port:** 8080 (Swagger UI at `http://localhost:8080/q/swagger-ui`)
- **Database:** the movies database (`postgres` in `infra/compose.yaml`, `localhost:5432`), schema `movies`, with migrations in `src/main/resources/db/migration/`. Load `data/movies_dump.sql` before the first start.
- **Needs:** that database, Keycloak, and Ollama with `nomic-embed-text` (without it, search by meaning is skipped)

```bash
./mvnw quarkus:dev    # dev mode with live reload, against the infrastructure from infra/compose.yaml
./mvnw test           # needs Docker: the tests start their own PostgreSQL
```

The full documentation is in the [backend README → movies-service](../README.md#movies-service), with [search](../README.md#search) and [search by meaning](../README.md#search-by-meaning).
