# Task 5 requirements

## Global constraints

- Java 25; stable Spring Boot 4 compatible with Java 25; pinned dependencies/images.
- `docker compose up --build` starts the complete system without host Java/Maven.
- `./mvnw verify` runs unit and integration tests; Docker is required for integration tests.
- Generated Java belongs under `target/`, excluded from Git.
- Flyway resources: `src/main/resources/db/migration/` in each service.
- All-or-nothing reservations, duplicate protection, and transactional outboxes in v1.
- No service reads another service's database or calls its HTTP API for business flow.
- Metrics have bounded labels; order/event identifiers belong in structured logs.
- Follow `codex/git-workflow.md`: verify, update progress, commit, push, confirm sync.
- Keep all agent working documents under `codex/`; normal source files stay in modules.
- Include Keycloak identity infrastructure; do not add v2 features, a frontend,
  payment, or a custom authentication service.

## Source map and conventions

Use base package `io.github.tmejs.reservation` and feature-oriented packages.
`O` below means `order-service/src/main/java/io/github/tmejs/reservation/orders`;
`I` means `inventory-service/src/main/java/io/github/tmejs/reservation/inventory`.
Corresponding tests use `src/test/java` with the same package. `*Test` is a unit
test; `*IT` is a Maven Failsafe integration test. Every path using O/I below expands
to these exact roots. Domain classes remain separate from generated HTTP models.

Shared event records live in
`event-contracts/src/main/java/io/github/tmejs/reservation/events/`.
API sources live in `api-contracts/src/main/resources/openapi/{orders,inventory}.yaml`.
Generated API packages are `io.github.tmejs.reservation.api.{orders,inventory}`;
client packages are `io.github.tmejs.reservation.client.{orders,inventory}`.

Each behavioral step uses a red/green cycle: write the specified failing assertion,
run the focused test and confirm the expected behavior fails, implement the named
unit, then run the focused test and full reactor verification. Do not add artificial
tests for scaffolding or documentation. Use bounded Awaitility polling for async
assertions, not fixed sleeps. Test snippets below specify the required assertions;
fixtures are implemented in the named test class alongside the test.

## 5. Kafka transport and outbox publishing

**Files:** both services `messaging/{OutboxRepository,OutboxPublisher,KafkaConfiguration}.java`;
both services `messaging/OutboxPublisherIT.java`; Kafka configuration in application YAML.

**Consumes:** pending outbox records containing event ID, order ID, topic, payload.
**Produces:** acknowledged messages on `orders.v1` and `reservation-results.v1`.

- [ ] Write real Kafka/PostgreSQL tests proving success marks published, failed
  send leaves pending, and restart after send-before-mark republishes the same ID.
  ```java
  assertThat(publishedCopies).allMatch(e -> e.eventId().equals(originalEventId));
  assertThat(outboxRow.publishedAt()).isNotNull();
  ```
- [ ] Configure string key/value producers and broker acknowledgement. Poll bounded
  batches on a fixed delay, send keyed by order UUID, await acknowledgement with a
  timeout, then mark published. Persist attempt count/next-attempt time on failure.
- [ ] Keep broker sends outside the database transaction. Schedule one publisher
  per service instance, capped exponential backoff, and continue other eligible
  records if one fails. Preserve IDs and payload across attempts.
- [ ] Run outage/recovery tests and `./mvnw verify`; commit/push:
  `feat: publish transactional outbox events to Kafka`.

## Execution context

- Baseline is reviewed and remotely synchronized commit `6e30cba`: Java 25,
  Spring Boot 4.1.1, PostgreSQL/Flyway persistence, JWT security, and atomic order
  creation with a pending `orders.v1` outbox row. The local reactor has 50 tests.
- Java 25 is active through SDKMAN. There is no GitHub Actions pipeline; local
  `./mvnw -B verify` is the required gate. Docker is available for Testcontainers.
- Add Spring Kafka consistently to both service modules and use Spring Boot's
  managed compatible dependency versions. Select and pin an Apache Kafka container
  image compatible with the Testcontainers API in this build; record the actual
  version and rationale in the task report.
- Preserve the V1 schemas already pushed. Any needed attempt/backoff columns must
  be added through `V2__...sql` migrations in both service-owned databases.
- The publisher boundary must be testable without scheduling: expose one bounded
  publish-cycle method and let the scheduler call it. Tests should invoke the cycle
  deterministically and use Awaitility for broker results.
- Do not hold a database transaction open while awaiting Kafka acknowledgement.
  Claim/select work in a short transaction, perform the send outside it, then mark
  success or failure in a separate transaction. Prevent overlapping scheduled runs
  within one instance and document the single-instance v1 assumption if cross-node
  leasing is deferred.
- Publish key and value as strings: key is the order UUID, value is the exact stored
  payload. Preserve event ID and payload across retries and send-before-mark replay.
- One failed row must not prevent other eligible rows in the same bounded batch.
  Use capped exponential backoff based on persisted attempt count and next-attempt
  time. Avoid unbounded metric labels; detailed identifiers belong in logs.
- Cover both service implementations. Shared source is acceptable only if it keeps
  module ownership and configuration clear; do not add task 6 consumers yet.
- Parent owns ledger, acceptance matrix, briefs, review artifacts, and pushes.
  Commit implementation locally after focused tests and a full local verification;
  do not push.
