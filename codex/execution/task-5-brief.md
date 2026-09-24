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
