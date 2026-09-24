# Task 9 requirements

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
