-- Keycloak's own database and login, kept apart from the movies data.
-- Runs automatically when the postgres volume is first created (see compose.yaml). On an
-- existing volume, run it once by hand:
--   docker exec -i postgres psql -U postgres < infra/init/01-keycloak.sql
CREATE ROLE keycloak WITH LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
