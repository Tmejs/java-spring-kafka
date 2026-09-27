# Task 2 requirements

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

## 2. Contract-first HTTP interfaces and clients

**Files:** both YAML files above; `api-contracts/pom.xml`, `api-clients/pom.xml`,
both service POMs; `O/config/OpenApiConfiguration.java`,
`I/config/OpenApiConfiguration.java`; API specification resources in each jar.

**Produces:** `OrdersApi` operations `createOrder` and `getOrder`; `ProductsApi`
operations `createProduct`, `listProducts`, `getProduct`, `addStock`.

- [ ] Define UUID IDs, positive integer quantities, nonempty distinct order items,
  order status/rejection reason, bounded pagination, and problem detail errors.
  Define POST orders as 202 with Location and required Idempotency-Key; define
  GET orders as 200/404 and conflict as 409. Product creation returns 201.
- [ ] Use these stable paths and operation names:
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
- [ ] Define OAuth2 authorization-code security, token/authorization URLs, 401/403
  problem responses, operation-level access rules, and PKCE Swagger settings.
  Generated clients accept access tokens supplied by callers.
- [ ] Package contracts, unpack them during dependent modules' initialize phase,
  and generate Spring interfaces and Java clients at generate-sources. Select a
  generator/library combination verified compatible with Boot 4; keep generated
  dependencies explicit. Do not hand-edit generated sources.
- [ ] Serve the original YAML and configure Swagger UI to use it. Do not regenerate
  the public specification from annotations.
- [ ] Run `./mvnw clean verify`; confirm both clients and server interfaces compile,
  generated output stays under target, and the reactor works from a clean checkout.
- [ ] Commit/push: `feat: generate APIs and clients from OpenAPI contracts`.


## Integration decisions from checkpoint 1 and compatibility research

- Existing foundation: Boot 4.1.1; Maven 3.9.16; Java25. Temporary verification JDK:
  `/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home`.
- Read `contract-compatibility.md` for exact checked generator options and primary
  sources. Pin OpenAPI Generator 7.25.0, Jackson3 server/native clients.
- Tests for HTTP spec/UI behavior belong in this checkpoint; use real Spring Boot
  random-port tests and compare served YAML with source. No invented domain routes
  or placeholder controller implementations just to exercise generated interfaces.
- OpenAPI roles are realm roles, not OAuth scopes: describe role requirements
  explicitly and use authorizationCode with `orders-api` or `inventory-api`
  audience-selection scopes for the corresponding service business routes. Monitoring uses metrics.read later; no Actuator path in these
  business contracts. Include `orders-api`/`inventory-api` audience documentation.
- Demo issuer is `http://localhost:8180/realms/reservation`; auth/token paths follow
  Keycloak `/protocol/openid-connect/auth` and `/token`. Swagger public client
  `reservation-swagger`, PKCE S256. No client secret.
- Application service names and HTTP port defaults 8080 Orders, 8081 Inventory.
- No source consumers of generated Java yet; next checkpoint adds security.
