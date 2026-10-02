#!/usr/bin/env bash
# Builds the application's five images for linux/amd64 (the VMs' CPU) and pushes them to a registry:
#   infra/build-images.sh <registry> [tag]
#   e.g. infra/build-images.sh docker.io/<user> v1
#        infra/build-images.sh registry.gitlab.rnl.tecnico.ulisboa.pt/psia/groups26/g17 v1
# Log in first (docker login <registry host>). Set the same registry and tag as image_registry and
# image_tag in infra/ansible/group_vars/all.yml. Runs on your computer, which needs Docker with buildx
# (Docker Desktop has it), not in the management container.
set -euo pipefail

registry=${1:?usage: $0 <registry> [tag]}
tag=${2:-latest}
cd "$(dirname "$0")/.."

for service in movies-service profiles-service recommendation-service recommendation-worker; do
  echo "==> $registry/$service:$tag"
  docker buildx build --platform linux/amd64 -f backend/Dockerfile -t "$registry/$service:$tag" --push "backend/$service"
done

echo "==> $registry/frontend:$tag"
docker buildx build --platform linux/amd64 -t "$registry/frontend:$tag" --push frontend
