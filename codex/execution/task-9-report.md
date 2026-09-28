# Task 9 execution report

## Delivered runtime

- Added a root `compose.yaml` that starts PostgreSQL, one Kafka KRaft broker,
  one-shot topic initialization, Keycloak, Orders, and Inventory. APIs,
  management ports, and Keycloak bind only to loopback; Kafka and PostgreSQL have
  no host port.
- Added Java 25 multi-stage Dockerfiles. Maven 3.9.16 builds each executable jar
  with a shared BuildKit dependency cache, and the Java 25.0.4 runtime images run
  as the fixed non-root `reservation` user. Runtime images include CA certificates
  and curl for readiness checks.
- Pinned the runtime to PostgreSQL `17.11-alpine3.24`, Kafka Native `4.1.1`, Kafka
  tools `4.1.1`, Keycloak `26.7.4`, Alpine `3.22.2`, Maven
  `3.9.16-eclipse-temurin-25-noble`, and Temurin
  `25.0.4_7-jre-noble`. Every selected image has an arm64 manifest or ran natively
  on the arm64 verification host.
- Added idempotent PostgreSQL initialization for separate Orders, Inventory, and
  Keycloak owners/databases. Public database connectivity is revoked and granted
  only to each owner. Added explicit one-partition creation for `orders.v1`,
  `reservation-results.v1`, and their two `.DLT` topics with broker auto-creation
  disabled.
- Persisted PostgreSQL and Kafka KRaft data in named volumes. A one-shot volume
  initializer assigns Kafka storage to its non-root image user before broker
  startup.
- Configured Keycloak with the external issuer
  `http://localhost:8180/realms/reservation` and dynamic backchannel routing.
  Services validate that issuer while fetching JWKS from
  `http://keycloak:8080/...` on the container network.
- Added `.env.example` with explicit local-only credentials and required Compose
  substitutions. The ignored `.env` used for verification contains demo values
  only.
- Added a portfolio-ready root README covering architecture, modules, security,
  ports, startup, shutdown, destructive reset, verification, reliability
  semantics, limits, Codex records, and the version-two roadmap.

## Build and configuration evidence

- `docker compose config --quiet` passed with all required substitutions supplied
  by the ignored `.env`.
- `docker compose -p reservation-task9 up --build --wait` built both services from
  source without host Java or Maven and waited successfully for PostgreSQL, Kafka,
  Keycloak, Orders, and Inventory health gates. Topic and storage initializers
  exited successfully.
- Container inspection reported `user=reservation` for both application images.
- `docker compose ps` showed only localhost mappings for 8080, 9080, 8081, 9081,
  and 8180. Kafka exposed 9092 only inside the Compose network, and PostgreSQL had
  no host mapping.
- The first clean-volume startup exposed a missing executable bit on the mounted
  PostgreSQL initializer. After setting the repository mode to executable, another
  clean-volume build and startup passed all gates.
- Review cleanup replaced the broker's TCP-open probe with a bounded Kafka
  `ApiVersions` request. The Native 4.1.1 image intentionally contains the broker
  binary rather than the JVM distribution's administration scripts, so the probe
  sends a protocol request through `nc`, requires a Kafka response of at least the
  response-header length, and emits no response body.

## Runtime behavior evidence

- Both source OpenAPI documents, both Swagger UIs, and all four liveness/readiness
  probe URLs returned HTTP 200 from the built containers.
- Real Keycloak client-credentials flows issued Orders, Inventory, and monitoring
  tokens through localhost. Discovery reported the exact localhost issuer. The
  monitoring token read Orders Prometheus metrics, anonymous metrics returned 401,
  and an Orders-audience token sent to Inventory returned 401.
- A real API flow created a product with quantity 5, created an order for quantity
  2, observed the order reach `CONFIRMED`, and read remaining stock 3.
- With Kafka stopped, a second order was accepted and remained `PENDING`; direct
  database inspection found exactly one matching unpublished outbox row. After
  Kafka restarted, the same order reached `CONFIRMED` and its outbox row had a
  non-null publication timestamp.
- `docker compose down` followed by `docker compose up --wait` retained both
  confirmed orders and the product at quantity 2. Keycloak issued new tokens after
  restart, and Kafka topic initialization remained idempotent against its retained
  log volume.
- A connection attempt from the Orders database role to the Inventory database was
  denied after restart, proving the database-level isolation used by Compose.

## Local verification

- SDKMAN Temurin `25.0.4`, `./mvnw -B verify`: six-module reactor `BUILD SUCCESS`
  in 1:55; 104 tests, zero failures, zero errors, zero skipped across 25 reports.
- `git diff --check` passed.
- Focused review verification started Kafka storage initialization, the broker, and
  topic initialization in a fresh isolated project. The broker became healthy only
  after answering the protocol request; topic initialization then created and
  listed all four expected topics before exiting successfully.
- Runtime checks used the isolated `reservation-task9` Compose project. Its
  containers and named volumes were removed after verification, so no checkpoint
  test data was left running on the host.

## Deliberate limits

- The Compose topology is local development infrastructure: one broker, one
  PostgreSQL server, one instance of each application, plaintext internal traffic,
  and demo credentials.
- The PostgreSQL and Kafka named volumes are durable across ordinary `down`/`up`,
  but `docker compose down --volumes` intentionally deletes all demo data.
- DLT inspection/replay and a scripted demonstration are owned by checkpoint 10.
  Prometheus server and Grafana dashboards remain version-two work.
