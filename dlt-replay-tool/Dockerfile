# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -pl dlt-replay-tool -am -DskipTests package

FROM eclipse-temurin:25.0.4_7-jre-noble
RUN groupadd --system --gid 10001 reservation \
    && useradd --system --uid 10001 --gid reservation --home-dir /app reservation
WORKDIR /app
COPY --from=build --chown=reservation:reservation \
    /workspace/dlt-replay-tool/target/dlt-replay-tool-0.0.1-SNAPSHOT.jar app.jar
USER reservation
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
