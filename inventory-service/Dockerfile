# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-25-noble AS build

WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -pl inventory-service -am -DskipTests package

FROM eclipse-temurin:25.0.4_7-jre-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 reservation \
    && useradd --system --uid 10001 --gid reservation --home-dir /app reservation

WORKDIR /app
COPY --from=build --chown=reservation:reservation \
    /workspace/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar app.jar

USER reservation
EXPOSE 8081 9081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
