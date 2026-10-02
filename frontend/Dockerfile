# syntax=docker/dockerfile:1
# The frontend as a Node.js server (Next.js standalone output), for Kubernetes:
#   docker build -t frontend frontend
#
# NEXT_PUBLIC_* values are compiled into the browser code. The defaults here are paths on the page's own
# origin, which the NGINX reverse proxy (infra/k8s/nginx.yaml.j2) sends to each API and to Keycloak, so
# the same image works at any address. Build with --build-arg to point it elsewhere.

FROM node:24-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-slim AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_MOVIES_API_URL=/api
ARG NEXT_PUBLIC_PROFILES_API_URL=/api
ARG NEXT_PUBLIC_RECOMMENDATIONS_API_URL=/api
ARG NEXT_PUBLIC_KEYCLOAK_URL=/auth
ARG NEXT_PUBLIC_KEYCLOAK_REALM=movies
ARG NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=movies-frontend
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:24-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app/public ./public
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node", "server.js"]
