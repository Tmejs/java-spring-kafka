# Task 8 requirements

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

## 8. Actuator, metrics, and structured logs

**Files:** both service POMs/YAML; each service
`observability/{BusinessMetrics,OutboxMetrics,CorrelationFilter}.java`;
both services `observability/ObservabilityIT.java`; listener correlation handling.

**Produces:** health/probe and Prometheus endpoints on management ports, JSON logs.

- [ ] Write HTTP metric/probe tests and assertions that a duplicate does not
  increment business counters. Test correlation cleanup even after an exception.
  ```java
  assertThat(afterDuplicates - beforeDuplicates).isEqualTo(1.0);
  assertThat(metricTagKeys).doesNotContain("orderId", "eventId", "productId");
  ```
- [ ] Add Actuator/Prometheus registry; expose only health and prometheus. Enable
  probes and separate management ports. Require `metrics.read` and the correct
  audience for metrics; permit minimal health responses without authentication. Keep liveness independent of DB/Kafka;
  readiness includes DB and a bounded Kafka connectivity check.
- [ ] Add orders-created and reservation-outcome counters after transaction commit,
  reservation timer, publisher failures, duplicates, and DLT outcomes. Refresh
  outbox count/oldest-age gauges periodically into atomic cached values.
- [ ] Enable framework HTTP/JVM/pool/Kafka metrics. Emit JSON logs and add order/event
  IDs with try/finally context cleanup; use no IDs in tags or raw payload logging.
- [ ] Run rollback/duplicate/endpoint tests and `./mvnw verify`; commit/push:
  `feat: expose operational metrics and structured correlation logs`.
