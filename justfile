# The whole system on your computer, in Docker: three Compose projects, started in this order
#   1. infra/compose.yaml     databases, Kafka, Keycloak, Ollama
#   2. backend/compose.yaml   the four Quarkus services
#   3. frontend/compose.yaml  the website
#
# Run `just` from this folder to list the recipes:
#   just local-first-up   the first time on a machine: also loads the movies data
#   just local-up         every other time
#   just local-down       stop and remove the containers (the data is kept)
#
# The deployment to Google Cloud has its own recipes, in infra/justfile.

set shell := ["bash", "-euo", "pipefail", "-c"]

default:
    @just --list

# The FIRST time: infrastructure, load the movies data, then the backend and the frontend
local-first-up: _local-infra _local-load-data _local-apps

# Every other time: the same, without loading the movies data again
local-up: _local-infra _local-apps

# Stop and remove the containers, in reverse order. The data is kept (there is no -v)
local-down:
    docker compose -f frontend/compose.yaml down
    docker compose -f backend/compose.yaml down
    docker compose -f infra/compose.yaml down

# (private) The infrastructure, healthy, with the Ollama models downloaded (that container exits when done)
_local-infra:
    docker compose -f infra/compose.yaml up -d
    docker compose -f infra/compose.yaml up -d --wait postgres profiles-db recommendations-db kafka keycloak ollama
    test "$(docker wait ollama-models)" = "0"

# (private) Load the movies data, unless it is already there. It must happen before movies-service starts:
# its migrations build on the tables in the dump. psql prints one harmless error (transaction_timeout).
_local-load-data:
    if docker exec postgres psql -U postgres -d movies -tAc "select to_regclass('movies.movie')" | grep -q 'movies.movie'; then \
        echo "The movies data is already loaded: skipping the import"; \
    else \
        docker exec -i postgres psql -U postgres -d movies < data/movies_dump.sql; \
    fi

# (private) The backend, then the frontend: built, started and healthy
_local-apps:
    docker compose -f backend/compose.yaml up -d --build --wait
    docker compose -f frontend/compose.yaml up -d --build --wait
