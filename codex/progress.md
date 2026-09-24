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
