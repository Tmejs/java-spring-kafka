# Task 3 report — persistence and Inventory HTTP API

## Delivered

- Added complete V1 Flyway schemas for Orders (`orders`, `order_items`,
  `idempotency_keys`, `outbox`, `processed_events`) and Inventory (`products`,
  `reservations`, `outbox`, `processed_events`). Constraints cover positive order
  quantities, nonnegative stock, owner/key and event uniqueness, reservation order
  uniqueness, terminal-state consistency, and pending-outbox indexes. Outbox JSON
  payloads use PostgreSQL `text`.
- Both services use Flyway 12.4.0, PostgreSQL JDBC, and Hibernate
  `ddl-auto: validate`; Spring Boot 4.1.1 also requires the separate
  `spring-boot-flyway` auto-configuration module.
- Implemented Inventory's generated `ProductsApi` with explicit domain/model
  mapping, server-generated UUIDs, duplicate names, stable ascending UUID
  pagination, `Location`, pessimistically locked stock additions, overflow
  rejection, and RFC 9457 problem responses for validation, missing products,
  and conflicts.
- Reused PostgreSQL Testcontainers support for every full application test and
  removed product fixture routes now covered by the real controller.
- Changed the local Keycloak development realm from `sslRequired: external` to
  `none`. The real realm test demonstrated that Docker Desktop bridge traffic was
  rejected with `403 HTTPS required`; the project intentionally uses local HTTP
  development mode.

## TDD and verification evidence

- RED:
  `./mvnw -B -pl order-service,inventory-service -am verify -Dtest=NoUnitTests -Dit.test=MigrationIT,ProductApiIT -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false`
  started PostgreSQL 18.1 and failed on the empty schema (`orders` relation and
  required tables absent).
- GREEN, Orders migration:
  `./mvnw -B -pl order-service -am verify -Dtest=NoUnitTests -Dit.test=MigrationIT -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false`
  passed 2 tests. Flyway applied exactly one migration before Hibernate mapping
  validation; SQL assertions verified all tables, text payloads, the partial
  pending index, and key constraints.
- GREEN, Inventory API:
  `./mvnw -B -pl inventory-service -am verify -Dtest=NoUnitTests -Dit.test=ProductApiIT -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false`
  passed 5 generated-client tests with real signed JWTs and PostgreSQL. The suite
  verifies schema/mappings, duplicate-name creation, UUID-ordered pagination,
  customer/admin/anonymous authorization, validation bounds, 404/409 problems,
  and two simultaneous additions with the exact final quantity
  `before + firstAdd + secondAdd`.
- GREEN, local realm regression:
  `./mvnw -B -pl order-service -am verify -Dtest=NoUnitTests -Dit.test=KeycloakRealmIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
  passed the real Keycloak token and cross-audience checks.
- GREEN, full reactor: `./mvnw -B verify` passed on Temurin 25.0.4 in 43.005 s:
  40 tests, zero failures, zero errors, zero skips. This preserves all 33 baseline
  tests and adds seven PostgreSQL-backed tests.
- `git diff --check` passed.

## Pinned database image

- Tests use the official `postgres:18.1` image.
- Locally pulled digest:
  `postgres@sha256:1090bc3a8ccfb0b55f78a494d76f8d603434f7e4553543d6e807bc7bd6bbd17f`.
- Runtime verification reported PostgreSQL 18.1 and Testcontainers 2.0.5.

## Remaining scope

Orders HTTP behavior, reservation processing, Kafka, and outbox publishing remain
assigned to later checkpoints.
