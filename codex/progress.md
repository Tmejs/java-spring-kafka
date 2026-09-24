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
