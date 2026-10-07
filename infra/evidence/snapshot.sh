#!/usr/bin/env bash
# Records the application's state, to compare before and after stopping and starting the VMs, or destroying
# them and deploying again on the same data disks:
#   evidence/snapshot.sh <label>      (or: just snapshot <label>)
# Writes evidence/<label>.txt (what must survive) and evidence/<label>-pods.txt (the pods, for reference).
# Run it in the management container, where kubectl points at the cluster. See evidence/README.md.
set -uo pipefail

label=${1:?usage: $0 <label>}
cd "$(dirname "$0")"

# psql in a database's pod: sql <pod> <user> <database> <psql arguments>
sql() {
  local pod=$1 user=$2 db=$3
  shift 3
  kubectl -n movies exec "$pod" -c postgres -- psql -U "$user" -d "$db" "$@"
}

{
  echo "== recommendations: PENDING must be 0 before the 'before' snapshot"
  sql recommendations-db-0 recommendations recommendations \
    -c "select status, count(*) from recommendations.request group by status order by status"

  echo "== profiles-db"
  sql profiles-db-0 profiles profiles \
    -c 'select (select count(*) from profile) as profiles, (select count(*) from follow) as follows, (select count(*) from watched_movie) as watched, (select count(*) from watchlist) as watchlist, (select count(*) from favorite_genre) as favorite_genres'

  echo "== movies-db"
  sql movies-db-0 postgres movies -c 'select count(*) as movies from movies.movie'

  echo "== recommendations-db: finished requests and their items"
  sql recommendations-db-0 recommendations recommendations \
    -c "select request_id, kind, status from recommendations.request where status = 'DONE' order by request_id"
  sql recommendations-db-0 recommendations recommendations \
    -c "select count(*) as items from recommendations.request_item"

  echo "== keycloak-db"
  sql keycloak-db-0 keycloak keycloak -c 'select username from user_entity order by username'

  echo "== kafka topics"
  kubectl -n kafka exec movies-kafka-dual-0 -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

  # Without the groups named after a random UUID (one per recommendation-service instance, for the
  # broadcast topic recommendation.ready): a new instance has a new one, by design.
  echo "== kafka consumer groups: where each one is in each partition"
  kubectl -n kafka exec movies-kafka-dual-0 -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --all-groups 2>/dev/null | awk 'NF && $1 != "GROUP" {print $1, $2, $3, $4, $5}' \
    | grep -v -E -- '-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-' | sort

  echo "== ollama models"
  kubectl -n movies exec deploy/ollama -- ollama list

  echo "== volumes"
  kubectl get pvc -A --no-headers -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,STATUS:.status.phase
} 2>&1 | tee "$label.txt"

kubectl get pods -A > "$label-pods.txt"
