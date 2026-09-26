# Task 4 requirements

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

## 4. Event contracts and atomic order creation

**Files:** event package `{EventMetadata,OrderLine,OrderCreated,StockReserved,
StockRejected,EventCodec}.java`; `O/order/{OrderService,OrdersController,
OrderRepository}.java`; `O/idempotency/IdempotencyRepository.java`;
`O/order/OrderCreationIT.java`; `event-contracts/.../EventCodecTest.java`.

**Interfaces:** `OrderLine(UUID productId, int quantity)`;
`EventMetadata(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
UUID orderId)`; `OrderCreated(EventMetadata metadata, List<OrderLine> items)`;
`StockReserved(EventMetadata metadata, UUID causationId)`;
`StockRejected(EventMetadata metadata, UUID causationId, String reason)`.
`EventCodec.encode(Object)` returns JSON; explicit typed decode methods validate
event type/version and reject unsupported versions without trusting class headers.

- [ ] Write codec round-trip/malformed/version tests and HTTP creation tests for
  replay, changed payload, simultaneous same-key requests, empty/duplicate items.
  ```java
  assertThat(replayedOrderId).isEqualTo(firstOrderId);
  assertThat(orderCount).isEqualTo(1);
  assertThat(pendingOutboxCount).isEqualTo(1);
  ```
- [ ] Canonicalize item order by product ID before hashing. Atomically claim the
  `(owner_subject, idempotency_key)` using PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`; avoid
  catching a constraint violation and continuing an aborted transaction.
- [ ] Save order, items, original response/fingerprint, and serialized OrderCreated
  outbox entry in one transaction. A conflicting key returns 409; a matching key
  returns the original 202 body and Location even if order status has since changed.
- [ ] Derive ownership from the authenticated JWT subject and persist it on orders.
  Implement GET order with an owner-qualified query (404 for other owners),
  current status, and rejection reason. Test different subjects using the same key. Inject `Clock`
  for timestamps; use generated HTTP clients in actual endpoint tests.
- [ ] Run focused tests and `./mvnw verify`; commit/push:
  `feat: create idempotent orders with transactional outbox`.

## Execution context

- Baseline is reviewed commit `676c1f1`: Boot 4.1.1, Java 25, generated APIs,
  Keycloak security, Flyway/JPA schemas, and 40 passing local tests.
- Java 25 is active through SDKMAN. There is no GitHub Actions pipeline; use local
  `./mvnw -B verify` as the required gate. Docker/PostgreSQL 18.1 is available.
- Implement the generated `OrdersApi` and map explicitly between generated models
  and domain entities. Do not expose JPA entities.
- Preserve the schema established in task 3. If a schema correction is necessary,
  add a new Flyway migration rather than editing V1, unless V1 has not been pushed
  to a released deployment and the report explicitly justifies the correction.
- Owner subject comes only from `CurrentOwner`; every order lookup is qualified by
  owner and another owner's order returns 404.
- Idempotency uniqueness is `(owner_subject, idempotency_key)`. Tests must cover
  identical replay, changed payload conflict, concurrent same-key requests, and
  the same key used independently by Alice and Bob.
- Canonical request fingerprints sort product IDs, preserve quantities, and use a
  stable documented digest. Duplicate product IDs are rejected before persistence.
- Outbox insertion is in the same database transaction as the new order and
  idempotency record. Event IDs/timestamps/order IDs are generated once and replay
  returns the originally persisted HTTP body and Location.
- Event JSON must have explicit event type/schema version and no Java class-name
  metadata. Decode methods reject malformed JSON, wrong event type, and unsupported
  schema version.
- Parent owns ledger, acceptance matrix, briefs, review artifacts, and pushes.
  Do not edit those files. Commit locally only after full local verification.
