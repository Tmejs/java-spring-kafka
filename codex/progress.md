# Progress

## Planning checkpoint — 2026-09-23

- User approved the written version-one design.
- Created the implementation plan in `codex/plan.md`.
- Application implementation has not started.
- Documentation verification: diff whitespace check and plan coverage review.
- Next: execute checkpoint 1, recording actual toolchain versions and build results.

For each implementation checkpoint, record changed behavior, commands executed,
their actual outcomes, and any remaining limitations before committing and pushing.

## Security design checkpoint

- User approved adding Keycloak, JWT validation, roles, ownership enforcement,
  owner-scoped idempotency, protected metrics, and security tests to version one.
- Updated design and affected plan checkpoints; added security checkpoint 2a.
- Removed the earlier exclusion of identity infrastructure from the scope.
- Recorded Kafka authentication/ACLs/TLS as version-two work.
- Application implementation has not started; next remains the Maven build.

## Maven foundation checkpoint — 2026-09-23

- Added a five-module Maven reactor for `api-contracts`, `api-clients`,
  `event-contracts`, `order-service`, and `inventory-service`.
- Pinned Spring Boot 4.1.1, Maven 3.9.16, Maven Wrapper 3.3.4, Maven Compiler
  Plugin 3.16.0, and Maven Surefire/Failsafe 3.6.0. Spring Boot 4.1.1 is a stable
  release whose official system requirements support Java 17 through Java 26.
- Set `maven.compiler.release` to 25. Surefire runs `*Test`; Failsafe runs `*IT`
  during `integration-test` and `verify`.
- Added the Orders and Inventory Spring Boot entry points. Boot repackaging is
  applied only to the two executable service modules.
- Generated the official `only-script` Maven Wrapper and pinned the Maven 3.9.16
  distribution SHA-256 after matching its SHA-512 to Maven Central's published
  checksum.
- Local prerequisites verified: Temurin 25.0.4.1, Docker Engine 28.3.3, and
  Docker Compose 2.39.2. The host default remains Java 21; Java 25 was selected
  explicitly for all acceptance commands.
- `./mvnw -B verify` passed for the parent and all five modules under Java 25.
  Both repackaged service jars started successfully under Java 25, and their
  manifests identify the correct application entry points.
- No behavioral tests were added because this checkpoint is build scaffolding;
  the plan explicitly excludes artificial scaffolding tests.

- Independent review passed after pinning CI runner/actions in `9701950`.
- Work is isolated on `feature/reservation-v1`; next checkpoint is contract generation.

- Foundation pushed and remote SHA confirmed at `7d37c77`. GitHub Actions
  [run 36013388798](https://github.com/Tmejs/java-spring-kafka/actions/runs/36013388798)
  completed successfully on Ubuntu.

## Contract-first API checkpoint — 2026-09-24

- Added complete Orders and Inventory OpenAPI contracts with OAuth2 audience
  scopes, problem responses, idempotency, pagination, and validation constraints.
- OpenAPI Generator 7.25.0 produces Boot 4/Jackson 3 server interfaces and native
  Java clients under ignored `target/` directories.
- Both services expose the original YAML and Swagger UI with PKCE while generated
  `/v3/api-docs` remains disabled.
- Java 25 `./mvnw -B clean verify` passed all six reactor projects and six
  random-port HTTP tests.
- Independent review passed without Critical or Important findings. Three minor
  contract/resource documentation cleanups are recorded in the execution ledger.
- OpenAPI checkpoint pushed and synchronized at `94f77e7`; GitHub Actions
  [run 36016067476](https://github.com/Tmejs/java-spring-kafka/actions/runs/36016067476)
  completed successfully.

## Local verification workflow — 2026-09-24

- User requested removal of the GitHub Actions pipeline.
- Installed Temurin Java 25.0.4 through SDKMAN and made it the active default.
- Future checkpoints use local `./mvnw -B verify`; each result is recorded before
  the checkpoint is pushed.

## Contract-first HTTP checkpoint — 2026-09-24

- Added authoritative Orders and Inventory OpenAPI 3.0 contracts with stable
  operation IDs, validation bounds, problem responses, documented realm-role
  access rules, and service-specific OAuth audience scopes.
- Pinned OpenAPI Generator 7.25.0 and springdoc 3.1.1. Maven now unpacks the
  contract JAR at `initialize` and generates Spring Boot 4/Jackson 3 interfaces
  and native Java clients under each module's ignored `target/` directory.
- Both services serve the original contract resource and Swagger UI with the
  `reservation-swagger` public client, the matching API scope, and PKCE S256.
  The generated OpenAPI document endpoint remains disabled.
- TDD evidence: both random-port suites first failed with 404 for the contract
  and UI, then passed 3/3 after the resource/UI wiring was implemented.
- Java 25 `./mvnw -B clean verify` passed all six reactor projects; both server
  interfaces and both native client APIs compiled from a clean build.
- Native generated clients accept caller tokens through their request interceptor
  or per-call header map; the native template does not emit a dedicated OAuth
  token helper.

## JWT and Keycloak security checkpoint — 2026-09-24

- Both services now validate JWT signature, issuer, audience, expiration, and
  not-before claims using Spring Security's resource-server support and separate
  issuer/JWKS configuration.
- Explicit route policies preserve OAuth scopes, allow only configured realm
  roles, protect metrics with `metrics.read`, keep Swagger/spec and health public,
  and deny unmatched routes.
- Added `CurrentOwner.subject()` for later Orders ownership enforcement; database
  ownership behavior remains checkpoint 4 work.
- Added a Keycloak 26.7.4 realm with PKCE-only browser login for Alice, Bob, and
  admin plus least-privilege Orders, Inventory, and monitoring service accounts.
- TDD covered 12 authorization tests, 14 encoded-token validation tests, the six
  existing OpenAPI HTTP tests, and a real Keycloak realm import/token-claims test.
- SDKMAN Temurin Java 25.0.4 `./mvnw -B verify` passed all six reactor modules:
  33 tests, zero failures, zero errors, and zero skips.

## Persistence and Inventory API checkpoint — 2026-09-25

- Added complete Orders and Inventory V1 Flyway schemas and JPA mappings. Both
  services run Flyway before Hibernate `ddl-auto: validate` against PostgreSQL.
- Implemented the generated Inventory `ProductsApi`: server UUIDs, duplicate
  names, stable UUID pagination, product reads, and pessimistically locked stock
  additions with validation, 404, and overflow problem responses.
- PostgreSQL 18.1 Testcontainers tests verify both schemas plus real generated
  client HTTP calls, signed JWT authorization, and concurrent additions without
  lost updates. The official image digest used locally is
  `sha256:1090bc3a8ccfb0b55f78a494d76f8d603434f7e4553543d6e807bc7bd6bbd17f`.
- Corrected the local Keycloak realm's SSL requirement so its documented HTTP
  development mode works through Docker Desktop's bridge network.
- Temurin Java 25.0.4 `./mvnw -B verify` passed all six reactor modules: 40 tests,
  zero failures, zero errors, and zero skips.

## Atomic order creation checkpoint — 2026-09-26

- Added explicit, versioned event records and typed JSON decoding for order-created
  and reservation-result events without Java class metadata.
- Implemented the generated Orders API with JWT-subject ownership, owner-qualified
  reads, canonical SHA-256 request fingerprints, and PostgreSQL-safe idempotency
  claims for retries and concurrent requests.
- Order, line items, the original replay response, and the OrderCreated outbox row
  commit atomically. Matching retries replay the original `202` response and
  Location; changed requests return `409`.
- Integration coverage verifies Alice/Bob isolation, owner-scoped idempotency,
  concurrent same-key requests, duplicate and empty item rejection, exact duplicate
  JSON lines, deterministic event timestamps, and zero partial persistence.
- Independent review found and then approved the fix for generated request sets
  collapsing identical JSON items before domain validation.
- Fresh controller verification with SDKMAN Temurin 25.0.4 passed the complete
  six-module reactor: 50 tests, zero failures, zero errors, and zero skips.

## Kafka outbox publishing checkpoint — 2026-09-27

- Added Spring Kafka publishers to both services using Boot-managed Spring Kafka
  4.1.1 and Kafka client 4.2.1. Messages use the order UUID string as key and the
  exact stored outbox payload as value with `acks=all` and finite acknowledgement
  waits.
- Each cycle reads a deterministic bounded batch in a short database transaction,
  sends outside the transaction, then conditionally records success or persisted
  capped exponential backoff in a separate transaction. A failed row does not stop
  later eligible rows.
- An in-process nonblocking lock prevents overlapping cycles within one service.
  Version one deliberately runs one instance per service and provides at-least-once
  delivery; consumer deduplication handles a crash after send and before marking.
- Real PostgreSQL 18.1 and `apache/kafka-native:4.1.1` tests in both services cover
  bounded acknowledged publishing, failure/backoff with row continuation, stable
  send-before-mark replay, and actual broker pause/recovery through the same producer.
- Independent review requested the real broker outage evidence, then approved the
  focused fix with no Critical or Important findings. The implementer Java 25 gate
  passed all six modules and 58 tests. Fresh controller verification passed the same
  six modules and 58 tests with zero failures, errors, or skips; Docker Desktop host
  latency extended that run to 23:20.

## Atomic inventory reservation checkpoint — 2026-09-27

- Added the `orders.v1` Inventory listener and transactional reservation service.
  Consumer auto-commit is disabled and record acknowledgement occurs only after the
  proxied database transaction returns.
- A transaction-scoped PostgreSQL advisory lock serializes decisions for one order,
  while conflict-safe event and decision inserts make identical delivery and new
  event IDs for an existing order safe without changing the pushed V1 schema.
- Product rows are pessimistically locked in UUID order. All products and quantities
  are checked before any decrement, so unknown products or shortages leave every
  stock value unchanged. Competing orders cannot oversell.
- Stock changes, processed-event claims, the reservation decision, and one versioned
  StockReserved/StockRejected outbox event commit atomically. Technical failure rolls
  back every effect; changed repeated-order fingerprints fail as contract errors.
- Real PostgreSQL/Kafka coverage verifies multi-item outcomes, duplicates, concurrent
  same-order and competing-order processing, technical rollback, committed consumer
  offsets, and duplicate Kafka delivery with one business effect.
- Independent review requested required `occurredAt` validation, then approved the
  focused fix with no Critical or Important findings. The implementer Java 25 gate
  passed all six modules and 70 tests. Fresh controller verification passed the same
  six modules and 70 tests with zero failures, errors, or skips in 3:29.

## Order outcomes and dead-letter recovery checkpoint — 2026-09-27

- Added the `reservation-results.v1` Orders listener and transactional outcome
  service. It locks the order, validates the persisted OrderCreated causation ID,
  claims result IDs conflict-safely, and permits one PENDING-to-terminal transition.
- Duplicate delivery and new IDs carrying the same outcome are idempotent. Opposite
  outcomes, changed rejection reasons, wrong causation, unknown orders, and invalid
  reason codes remain technical failures and cannot turn an order into REJECTED.
- Both consumers use record acknowledgement, four total delivery attempts separated
  by one-second blocking backoffs, then publish the original key/value to the
  source-specific `.DLT`. Recovery waits for the broker acknowledgement.
- Real Kafka tests prove malformed/version failures, transient recovery, exhaustion,
  exact retry counts/timing, post-commit redelivery, and no source-offset advancement
  while DLT publication fails. Broker-selected DLT partitions support a one-partition
  DLT even when the failed source record came from partition 1.
- Independent review requested enum-tight rejection reasons, real Orders Kafka
  redelivery evidence, and partition-count-safe DLT routing. The focused fix passed
  scoped re-review with no Critical or Important findings. The implementer Java 25
  gate passed all six modules and 91 tests. Fresh controller verification passed
  the same six modules and 91 tests with zero failures, errors, or skips in 4:13.

## Operational observability checkpoint — 2026-09-27

- Added Actuator and Prometheus registries to both services on dedicated management
  ports. Only minimal health/probes and Prometheus are exposed; real signed-token
  tests prove service audience plus `metrics.read` scope enforcement.
- Liveness depends only on application state. Readiness includes PostgreSQL and a
  bounded Kafka Admin check, and remains responsive when Kafka is unavailable.
- Added after-commit counters for created orders and reservation outcomes, duplicate
  counters, reservation timing, outbox publication failures, DLT outcomes, and
  cached outbox backlog/age gauges. Custom labels are bounded and exclude IDs.
- Enabled framework and Kafka observations plus ECS JSON console logs. HTTP,
  listener, and publisher scopes add correlation/order/event fields and restore MDC
  state on success and failure without logging authorization headers or payloads.
- Independent review found missing real structured publisher fields and incomplete
  counter edge-case evidence. The focused fix added both and restored Kafka-health
  thread interruption; scoped re-review passed with no Critical or Important issues.
- The implementer clean Java 25 reactor passed all six modules and 104 tests with
  zero failures, errors, or skips. Fresh controller verification passed the same
  six modules and 104 tests with zero failures, errors, or skips in 1:59.

## Complete Compose runtime checkpoint — 2026-09-28

- Added a root Compose topology for PostgreSQL, a persistent single-node KRaft
  broker, explicit topic initialization, Keycloak, Orders, and Inventory. Only the
  APIs, management ports, and Keycloak bind to localhost.
- Added pinned Java 25 multi-stage images that build from the Maven reactor and run
  as fixed non-root users. PostgreSQL creates isolated service-owned databases, and
  cross-database access is denied. Kafka and PostgreSQL have no host ports.
- Keycloak exposes the localhost issuer while services fetch JWKS over the internal
  network. Real client-credentials tests covered service audiences, roles, and the
  protected Prometheus endpoint.
- A real product/order flow reached CONFIRMED. With Kafka stopped, a second order
  stayed PENDING with one unpublished outbox row, then recovered after broker restart.
  Volume-preserving down/up retained orders, stock, topics, offsets, and realm data.
- Added the root README with architecture, modules, startup, ports, security,
  verification, reliability guarantees, reset behavior, and links to Codex records.
- Independent review passed with no Critical or Important findings. A TCP-only Kafka
  probe was strengthened to a bounded ApiVersions request; topic initialization is
  the functional startup gate. The controller Java 25 reactor passed 104 tests in
  1:56, and a clean isolated Compose smoke run passed all health and non-root checks.

## Reproducible demo and recovery checkpoint — 2026-09-29

- Added a repeatable `scripts/demo.sh` flow using distinct Orders, Inventory, and
  monitoring service accounts. Tokens and secrets stay out of argv, files, child
  environments, verbose/xtrace output, and logs. Each HTTP operation and order poll
  has a finite deadline.
- Consecutive retained-volume runs created unique products, confirmed quantity-3
  orders, rejected quantity-8 orders with `INSUFFICIENT_STOCK`, proved exactly 7
  units remained, and authenticated to both Prometheus endpoints. Controller runs
  passed three more times after implementation and security-review fixes.
- Added a Java 25 raw-byte replay module behind a Compose tools profile and a strict
  wrapper requiring DLT topic, partition, and offset. Five focused tests cover
  newline/NUL, empty and null key/value bytes, exact offset selection, allowlisting,
  exactly one output, and no consumer offset commit.
- Runtime recovery denied new Inventory database connections until one valid event
  reached `orders.v1.DLT[0]@0`. Restoring the database and replaying confirmed the
  order and decremented stock once. Replaying the same DLT record again left stock
  at 6, one reservation, one processed-event marker, and the DLT end offset at 1.
- SDKMAN Temurin 25.0.4 `./mvnw -B verify` passed the parent and six child modules:
  109 tests, zero failures, zero errors, zero skips in 1:52. `bash -n` and
  `docker compose config --quiet` also passed.
- Alice completed the Orders Swagger Authorization Code flow with PKCE S256 and the
  `orders-api` scope; Swagger displayed the OAuth authorization as active. Machine
  client credentials were exercised by the repeatable demo.
- Independent review found three Important shell-safety issues: Bash here-string
  token materialization, inherited verbose mode, and unbounded Docker commands.
  Commit `c576a7c` fixed all three; scoped re-review passed with no remaining
  Critical or Important findings.
