# Order Reservation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline execution,
> or superpowers:subagent-driven-development if the user selects delegated
> execution. Track completed steps with the checkboxes below.

**Goal:** Deliver the approved two-service reservation demo with reliable Kafka
processing, generated HTTP contracts/clients, migrations, and operational metrics.

**Architecture:** Orders and Inventory own separate PostgreSQL databases. Kafka
events connect transactional outboxes and idempotent consumers. Maven generates
HTTP interfaces and clients from OpenAPI; Docker Compose runs the entire demo.

**Tech Stack:** Java 25, Spring Boot 4, Maven Wrapper, Spring Kafka, Spring Data JPA,
PostgreSQL, Flyway, OpenAPI Generator, Actuator, Micrometer, Testcontainers,
Spring Security OAuth2 Resource Server, Keycloak.

**Spec:** [Approved design](design.md).

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

## 1. Reproducible Maven build

**Files:** root `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`,
`.gitignore`; `pom.xml` in all five modules; `O/OrderApplication.java`,
`I/InventoryApplication.java`; `.github/workflows/verify.yml`.

**Produces:** five-module reactor compiling with Java 25; executable service jars.

- [x] Inspect local Java and Docker availability. Verify compatible stable versions
  using official dependency documentation, pin them, and record choices in progress.
- [x] Create the parent and children; manage common versions centrally. Set
  `<maven.compiler.release>25</maven.compiler.release>`. Configure Surefire for
  `*Test` and Failsafe `integration-test`/`verify` for `*IT`.
- [x] Add the two `@SpringBootApplication` entry points; apply Boot repackaging only
  to executable services. Ignore `**/target/`, IDE output, and local secret files.
- [x] Add GitHub Actions using Java 25 and `./mvnw -B verify` on push/pull request.
- [x] Run `./mvnw -B verify`; expect all five modules successful. Record environment
  prerequisites honestly; never silently disable integration tests in later steps.
- [x] Commit/push: `build: initialize Java 25 Maven reactor`.

## 2. Contract-first HTTP interfaces and clients

**Files:** both YAML files above; `api-contracts/pom.xml`, `api-clients/pom.xml`,
both service POMs; `O/config/OpenApiConfiguration.java`,
`I/config/OpenApiConfiguration.java`; API specification resources in each jar.

**Produces:** `OrdersApi` operations `createOrder` and `getOrder`; `ProductsApi`
operations `createProduct`, `listProducts`, `getProduct`, `addStock`.

- [x] Define UUID IDs, positive integer quantities, nonempty distinct order items,
  order status/rejection reason, bounded pagination, and problem detail errors.
  Define POST orders as 202 with Location and required Idempotency-Key; define
  GET orders as 200/404 and conflict as 409. Product creation returns 201.
- [x] Use these stable paths and operation names:
  ```yaml
  /orders:
    post:
      operationId: createOrder
  /orders/{id}:
    get:
      operationId: getOrder
  /products:
    post:
      operationId: createProduct
    get:
      operationId: listProducts
  /products/{id}:
    get:
      operationId: getProduct
  /products/{id}/stock:
    post:
      operationId: addStock
  ```
- [x] Define OAuth2 authorization-code security, token/authorization URLs, 401/403
  problem responses, operation-level access rules, and PKCE Swagger settings.
  Generated clients accept access tokens supplied by callers.
- [x] Package contracts, unpack them during dependent modules' initialize phase,
  and generate Spring interfaces and Java clients at generate-sources. Select a
  generator/library combination verified compatible with Boot 4; keep generated
  dependencies explicit. Do not hand-edit generated sources.
- [x] Serve the original YAML and configure Swagger UI to use it. Do not regenerate
  the public specification from annotations.
- [x] Run `./mvnw clean verify`; confirm both clients and server interfaces compile,
  generated output stays under target, and the reactor works from a clean checkout.
- [x] Commit/push: `feat: generate APIs and clients from OpenAPI contracts`.

## 2a. Identity provider and resource-server security

Execute after checkpoint 2 and before business API implementation.

**Files:** `infra/keycloak/reservation-realm.json`, `.env.example`; both service
POMs and application YAML; both services `security/{SecurityConfiguration,
RealmRoleConverter}.java`; both services `security/JwtValidationIT.java` and
`security/AuthorizationTest.java`; `O/security/CurrentOwner.java`.

**Produces:** validated JWT principals, explicit role/scope authorization, and
`CurrentOwner.subject()` derived only from the authenticated JWT `sub` claim.

- [ ] Import a realm with CUSTOMER and INVENTORY_ADMIN roles, Alice/Bob/admin demo
  users, a public Swagger client with exact redirect URIs and mandatory PKCE S256,
  narrowly scoped demo service accounts, and a monitoring client. Disable password
  grants. Audience mappers emit orders-api/inventory-api only where needed.
- [ ] Write token tests using a test signing key and served JWKS: valid token passes;
  wrong signature, issuer, audience, expired token, and future not-before return 401.
  Add authorization tests using mock JWTs for each route/role and a real Keycloak
  Testcontainer test proving imported realm client credentials produce valid tokens.
  ```java
  assertThat(missingTokenStatus).isEqualTo(401);
  assertThat(customerStockMutationStatus).isEqualTo(403);
  assertThat(wrongAudienceStatus).isEqualTo(401);
  ```
- [ ] Implement stateless bearer-only security chains and explicit audience/issuer
  validation. Map configured realm roles; deny unmatched routes. Permit local
  Swagger/spec resources and minimal health; protect metrics with metrics.read.
  Disable CSRF only on the stateless bearer API/management chains; configure
  specific origins if needed. Never log Authorization or raw tokens.
- [ ] Add owner isolation assertions to checkpoints 3/4 when controllers exist:
  Alice creates/reads her order; Bob receives 404; each can use the same idempotency
  key independently. No role grants implicit access to another customer's order.
- [ ] Run focused security tests and `./mvnw verify`; record the imported realm and
  JWT checks. Commit/push: `feat: secure APIs with Keycloak JWT authentication`.

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

## 9. Complete Docker Compose runtime

**Files:** `compose.yaml`, `.dockerignore`, each service `Dockerfile`,
`infra/postgres/init-databases.sh`, `infra/kafka/init-topics.sh`,
`infra/keycloak/reservation-realm.json`, `.env.example`.

**Produces:** two Java 25 images, service-owned databases/roles, one KRaft broker,
four explicit topics, Keycloak with its own database, persistent volumes,
localhost API/management/identity mappings. Do not publish Kafka host ports.

- [ ] Build service jars with a Java 25 Maven build stage, copy executable jars
  into Java 25 runtime images running as non-root. Pin real image tags verified
  available for the host architecture. Keep health-check tools available in images.
- [ ] Configure separate database owners without cross-database table grants.
  Initialize `orders.v1`, `reservation-results.v1`, and both `.DLT` topics.
- [ ] Add pinned Keycloak in local development mode with realm import, a separate
  database/role, and localhost port 8180. Configure external issuer/internal JWKS
  routing consistently; verify tokens issued through localhost work in containers.
- [ ] Map Orders API/management to localhost 8080/9080; Inventory to 8081/9081.
  Add named data volumes, health checks, and dependency readiness conditions.
  Use explicit demo-only database credentials; no real secrets in Git.
- [ ] Run `docker compose config --quiet`, `./mvnw verify`, and
  `docker compose up --build --wait`; assert Swagger/specs and probe endpoints work.
- [ ] Restart services and confirm data survives. Stop/start the broker and confirm
  outbox delivery recovers. Leave volume deletion as an explicitly documented reset.
- [ ] Commit/push: `build: run reservation services and infrastructure with Compose`.

## 10. Reproducible demo and portfolio documentation

**Files:** `scripts/demo.sh`, `scripts/replay-dlt.sh`, `README.md`,
`codex/progress.md`; both services' integration tests as needed for cross-flow coverage.

**Produces:** a bounded, repeatable success/rejection demo and manual recovery guide.

- [ ] Obtain short-lived tokens using scoped demo service-account clients; use a
  separate metrics client. Never print tokens or store them in generated artifacts.
  Implement demo with unique products/keys each run: create product, add stock,
  place a satisfiable order, poll to CONFIRMED, place an excessive order, poll to
  REJECTED, verify remaining stock, and fetch both metrics endpoints. Fail on HTTP
  errors/unexpected states/timeouts. Document required curl/jq tools.
- [ ] Provide a manual replay script that accepts a chosen DLT record/offset,
  republishes its original key and payload to the source topic, and preserves IDs.
  Test replay after fixing the underlying failure; never replay the entire DLT
  automatically or rewrite invalid events while claiming their old identity.
- [ ] Document architecture, event schemas, API examples, generated-client usage,
  migration ownership, reliability guarantees/limits, metrics, troubleshooting,
  Alice/Bob browser login with PKCE, roles/ownership, demo-only credentials,
  checks, retained-volume shutdown, and destructive reset as separate commands.
- [ ] Run clean `./mvnw verify`, Compose smoke/demo, duplicate replay, and recovery
  scenarios. Record actual results/limitations in progress; reconcile every design
  verification bullet with a passing test or documented manual check.
- [ ] Commit/push: `docs: add reproducible reservation demo and recovery guide`.

## Completion criteria

All steps checked, relevant tests passing, demo reproducible, and repository clean
with checkpoint commits on GitHub. Check the actual remote SHA after pushes.
If a dependency, Docker access, or permissions block validation, record the exact
failure and resolve it before calling the affected checkpoint complete.
