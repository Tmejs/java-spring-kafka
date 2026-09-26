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
