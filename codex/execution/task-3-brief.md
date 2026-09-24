# Task 3 requirements

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

## 3. Service-owned databases and product API

**Files:** each service `src/main/resources/application.yaml` and
`db/migration/V1__initial_schema.sql`; `O/order/OrderEntity.java`,
`O/order/OrderItemEntity.java`; `I/product/{ProductEntity,ProductRepository,
ProductService,ProductsController}.java`; each service
`messaging/{OutboxEntity,ProcessedEventEntity}.java`; `I/reservation/ReservationEntity.java`;
`I/product/ProductApiIT.java`; `O/MigrationIT.java`.

**Produces:** migrated schemas and product operations. `ProductService.addStock(UUID,
int)` and reservation processing later use identical pessimistic row locks.

- [ ] Write PostgreSQL-backed migration/startup tests and generated-client product
  tests. Assert creation, list pagination, stock addition, invalid quantities, 404,
  and concurrent additions without lost updates.
  ```java
  assertThat(after.getAvailableQuantity()).isEqualTo(before + firstAdd + secondAdd);
  ```
- [ ] Create order/items, idempotency, outbox, processed-event tables in Orders;
  products, reservations, outbox, processed-event tables in Inventory. Include
  unique event IDs, unique reservation order IDs, unique owner/key pairs,
  nonnegative stock checks, and pending-outbox indexes. Store outbox payload as text.
- [ ] Set Hibernate `ddl-auto: validate`; include Flyway PostgreSQL support. Create
  repositories and explicit generated-model mapping in thin controllers.
- [ ] Lock stock updates, reject overflow, and implement problem detail responses
  for validation, missing product, and conflicting requests. Test the real HTTP API.
- [ ] Run focused ITs via Failsafe and `./mvnw verify`; expect migrations and APIs pass.
- [ ] Commit/push: `feat: migrate service databases and expose inventory API`.
