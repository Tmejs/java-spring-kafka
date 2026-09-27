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

## Execution context

- Baseline is reviewed commit `215746b`: Boot 4.1.1, Java 25, generated APIs,
  Keycloak JWT security, and 33 passing tests.
- Java 25 is installed and active through SDKMAN. Use local `./mvnw -B verify`;
  there is intentionally no GitHub Actions pipeline.
- Docker Desktop is running. Use pinned PostgreSQL 18.1 for Testcontainers unless
  current official compatibility evidence requires another exact patch version.
- Implement generated `ProductsApi`; do not introduce hand-written public request
  or response DTOs. Use explicit mapper methods between generated models and domain.
- Tests call actual HTTP endpoints with generated clients and real signed JWTs or
  the supported test token mechanism. Cover CUSTOMER reads, admin mutations, and
  denied CUSTOMER mutations. Keep owner isolation tests assigned to task 4 because
  Orders business endpoints do not exist in this task.
- Product creation must define deterministic duplicate behavior: product IDs are
  server-generated; names are not unique. Stock addition rejects integer overflow.
- Pagination uses stable product-ID ordering and the exact bounds from OpenAPI.
- Both services receive complete V1 Flyway schemas even though Orders behavior is
  implemented in task 4. Schema constraints must match the approved design.
- Parent owns the execution ledger, acceptance matrix, review artifacts, pushes,
  and the pipeline-removal documentation. Do not edit those files.
