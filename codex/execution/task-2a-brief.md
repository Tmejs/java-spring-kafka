# Task 2a requirements

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
