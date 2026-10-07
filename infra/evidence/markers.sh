#!/usr/bin/env bash
# Prints the e2e test's markers: the persistence_check row in each database, the persistence-check topic's
# offsets, and Kafka's cluster ID. Not a recipe: only used for the test recorded in evidence/.
set -uo pipefail
for spec in "movies-db-0 postgres movies" "profiles-db-0 profiles profiles" "recommendations-db-0 recommendations recommendations" "keycloak-db-0 keycloak keycloak"; do
  set -- $spec
  kubectl -n movies exec "$1" -c postgres -- psql -U "$2" -d "$3" -tAc "select '$1', note, at from public.persistence_check" </dev/null
done
kubectl -n kafka exec movies-kafka-dual-0 -- bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic persistence-check </dev/null
echo "kafka cluster id: $(kubectl -n kafka get kafka movies-kafka -o jsonpath={.status.clusterId} </dev/null)"
