# Task 6 requirements

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

## 6. Atomic inventory reservation

**Files:** `I/reservation/{ReservationService,ReservationRepository}.java`;
`I/messaging/OrderCreatedListener.java`; `I/reservation/ReservationIT.java`.

**Interface:** `ReservationService.reserve(OrderCreated event)` returns void and
commits the decision, stock changes, processed-event record, and result outbox.

- [ ] Test multi-item success, unknown product, insufficient stock, no partial
  reservation, identical duplicate event, different event ID for the same order,
  inconsistent repeated items, and competing orders:
  ```java
  assertThat(confirmedReservations).isEqualTo(1);
  assertThat(availableStock).isZero();
  assertThat(resultOutboxRows).isEqualTo(2); // one success and one rejection
  ```
- [ ] Claim event/order with conflict-safe inserts and compare normalized item
  fingerprints for repeated orders. Lock all existing product rows in sorted ID
  order; check every quantity before decrementing any stock.
- [ ] Save a decision and one StockReserved/StockRejected outbox entry atomically.
  Use bounded reasons UNKNOWN_PRODUCT and INSUFFICIENT_STOCK. Roll back all writes
  on technical failure. Duplicate deliveries do not create another result.
- [ ] Listener delegates to the transactional service through a Spring proxy;
  use record acknowledgement after successful return, never before DB commit.
- [ ] Run concurrency/redelivery tests and `./mvnw verify`; commit/push:
  `feat: reserve inventory atomically with duplicate protection`.
