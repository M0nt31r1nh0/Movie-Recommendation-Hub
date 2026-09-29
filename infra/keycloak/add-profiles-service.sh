#!/usr/bin/env bash
# Updates a Keycloak whose movies realm was imported before profiles-service existed, to match
# movies-realm.json (which Keycloak only reads when the realm doesn't exist yet):
#   - movies-frontend and movies-swagger-ui add "profiles-service" to their tokens' audience;
#   - movies-swagger-ui also accepts logins from profiles-service's Swagger UI (port 8081).
# Keeps all user accounts. Safe to run more than once.
set -euo pipefail

KC="docker exec keycloak /opt/keycloak/bin/kcadm.sh"
KC_STDIN="docker exec -i keycloak /opt/keycloak/bin/kcadm.sh" # only for commands that read a JSON body
$KC config credentials --server http://localhost:8080 --realm master \
  --user "${KEYCLOAK_ADMIN:-admin}" --password "${KEYCLOAK_ADMIN_PASSWORD:-admin}" >/dev/null 2>&1

client_id() {
  $KC get clients -r movies -q clientId="$1" --fields id --format csv --noquotes
}

for client in movies-frontend movies-swagger-ui; do
  id=$(client_id "$client")
  if $KC get "clients/$id/protocol-mappers/models" -r movies --fields name --format csv --noquotes \
      | grep -qx "profiles-service audience"; then
    echo "$client: already adds the profiles-service audience"
  else
    echo '{"name": "profiles-service audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
           "config": {"included.custom.audience": "profiles-service", "access.token.claim": "true",
                      "id.token.claim": "false", "introspection.token.claim": "true"}}' \
      | $KC_STDIN create "clients/$id/protocol-mappers/models" -r movies -f - >/dev/null
    echo "$client: now adds the profiles-service audience"
  fi
done

swagger_id=$(client_id movies-swagger-ui)
echo '{"redirectUris": ["http://localhost:8080/q/swagger-ui/*", "http://localhost:8081/q/swagger-ui/*"],
       "webOrigins": ["http://localhost:8080", "http://localhost:8081"]}' \
  | $KC_STDIN update "clients/$swagger_id" -r movies -f -
echo "movies-swagger-ui: accepts both Swagger UIs (ports 8080 and 8081)"
echo "Done. Users logged in before this need to log out and in again to get the new audience."
