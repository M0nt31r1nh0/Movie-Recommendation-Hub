#!/usr/bin/env bash
# Updates a Keycloak whose movies realm was imported before the recommendation services existed, to
# match movies-realm.json (which Keycloak only reads when the realm doesn't exist yet):
#   - a "profiles-reader" realm role, for profiles-service's internal endpoints;
#   - confidential clients recommendation-service and recommendation-worker, with service accounts
#     (they log in as themselves), and recommendation-service's account holding profiles-reader;
#   - movies-frontend and movies-swagger-ui add "recommendation-service" to their tokens' audience;
#   - movies-swagger-ui also accepts recommendation-service's Swagger UI (port 8082).
# Keeps all user accounts. Safe to run more than once. Run add-profiles-service.sh first if you haven't.
set -euo pipefail

KC="docker exec keycloak /opt/keycloak/bin/kcadm.sh"
KC_STDIN="docker exec -i keycloak /opt/keycloak/bin/kcadm.sh" # only for commands that read a JSON body
$KC config credentials --server http://localhost:8080 --realm master \
  --user "${KEYCLOAK_ADMIN:-admin}" --password "${KEYCLOAK_ADMIN_PASSWORD:-admin}" >/dev/null 2>&1

client_id() {
  $KC get clients -r movies -q clientId="$1" --fields id --format csv --noquotes
}

add_audience() { # client, audience
  local id; id=$(client_id "$1")
  if $KC get "clients/$id/protocol-mappers/models" -r movies --fields name --format csv --noquotes | grep -qx "$2 audience"; then
    echo "$1: already adds the $2 audience"
  else
    echo "{\"name\": \"$2 audience\", \"protocol\": \"openid-connect\", \"protocolMapper\": \"oidc-audience-mapper\",
           \"config\": {\"included.custom.audience\": \"$2\", \"access.token.claim\": \"true\",
                        \"id.token.claim\": \"false\", \"introspection.token.claim\": \"true\"}}" \
      | $KC_STDIN create "clients/$id/protocol-mappers/models" -r movies -f - >/dev/null
    echo "$1: now adds the $2 audience"
  fi
}

add_service_client() { # client, secret
  if [ -n "$(client_id "$1")" ]; then
    echo "$1: client already exists"
  else
    echo "{\"clientId\": \"$1\", \"enabled\": true, \"publicClient\": false, \"clientAuthenticatorType\": \"client-secret\",
           \"secret\": \"$2\", \"serviceAccountsEnabled\": true, \"standardFlowEnabled\": false,
           \"implicitFlowEnabled\": false, \"directAccessGrantsEnabled\": false}" \
      | $KC_STDIN create clients -r movies -f - >/dev/null
    echo "$1: created, with a service account"
  fi
}

if $KC get roles/profiles-reader -r movies >/dev/null 2>&1; then
  echo "profiles-reader: role already exists"
else
  $KC create roles -r movies -s name=profiles-reader \
    -s 'description=May read any profile through profiles-service internal endpoints' >/dev/null
  echo "profiles-reader: role created"
fi

add_service_client recommendation-service recommendation-service-dev-secret
add_service_client recommendation-worker recommendation-worker-dev-secret
add_audience recommendation-service profiles-service
add_audience recommendation-service movies-service
add_audience recommendation-worker movies-service
$KC add-roles -r movies --uusername service-account-recommendation-service --rolename profiles-reader
echo "recommendation-service's service account: has profiles-reader"

add_audience movies-frontend recommendation-service
add_audience movies-swagger-ui recommendation-service
swagger_id=$(client_id movies-swagger-ui)
echo '{"redirectUris": ["http://localhost:8080/q/swagger-ui/*", "http://localhost:8081/q/swagger-ui/*", "http://localhost:8082/q/swagger-ui/*"],
       "webOrigins": ["http://localhost:8080", "http://localhost:8081", "http://localhost:8082"]}' \
  | $KC_STDIN update "clients/$swagger_id" -r movies -f -
echo "movies-swagger-ui: accepts all three Swagger UIs (ports 8080 to 8082)"
echo "Done. Users logged in before this need to log out and in again to get the new audience."
