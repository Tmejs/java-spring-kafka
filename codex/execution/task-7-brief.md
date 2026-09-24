# Task 7 requirements

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

## 7. Order outcomes and dead-letter recovery

**Files:** `O/order/OrderOutcomeService.java`, `O/messaging/ReservationResultListener.java`;
both services `messaging/KafkaErrorHandlingConfiguration.java`;
`O/order/OrderOutcomeIT.java`; both services `messaging/DeadLetterIT.java`.

**Interfaces:** `OrderOutcomeService.confirm(StockReserved)` and
`reject(StockRejected)` update the order and processed event atomically.

- [ ] Test successful/rejected state updates, duplicate result, conflicting terminal
  result, unknown order, and redelivery after DB commit before offset commit:
  ```java
  assertThat(order.status()).isEqualTo(CONFIRMED);
  assertThat(processedEventCount).isEqualTo(1);
  ```
- [ ] Lock the order row; allow PENDING to transition, treat same terminal outcome
  as a no-op, and reject contradictory outcomes. Validate causation against the
  stored order-created event. Unknown orders are processing errors.
- [ ] Configure bounded blocking retries (three retries at one-second intervals),
  then dead-letter to `<source>.DLT`. Decode strings inside the listener so malformed
  payloads enter recovery. Require confirmed DLT send before advancing the offset.
- [ ] Test malformed JSON, unsupported version, transient recovery, exhausted retry,
  and DLT producer failure. Assert technical failures never mark an order REJECTED.
- [ ] Run focused tests and `./mvnw verify`; commit/push:
  `feat: apply order outcomes and recover failed Kafka messages`.
