# syntax=docker/dockerfile:1
# Builds any of the four Quarkus services, from source. Run it with the service's folder as the context:
#   docker build -f backend/Dockerfile -t movies-service backend/movies-service
# (infra/build-images.sh does it for all of them). Dockerfile.dockerignore, next to this file, replaces
# each service's own .dockerignore, which only lets in target/ for src/main/docker/Dockerfile.jvm.

# The jar is the same on every CPU, so Maven runs natively even when building for another platform
# (e.g. linux/amd64 VMs from an ARM Mac); only the runtime image is the target platform's.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# Tests need Docker (Dev Services), so they run outside the image build: ./mvnw test.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# The same runtime as src/main/docker/Dockerfile.jvm.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24
ENV LANGUAGE='en_US:en'
COPY --from=build --chown=185 /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /build/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /build/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /build/target/quarkus-app/quarkus/ /deployments/quarkus/
EXPOSE 8080
USER 185
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
