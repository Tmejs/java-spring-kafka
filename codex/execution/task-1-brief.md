# Task 1 requirements

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

- [ ] Inspect local Java and Docker availability. Verify compatible stable versions
  using official dependency documentation, pin them, and record choices in progress.
- [ ] Create the parent and children; manage common versions centrally. Set
  `<maven.compiler.release>25</maven.compiler.release>`. Configure Surefire for
  `*Test` and Failsafe `integration-test`/`verify` for `*IT`.
- [ ] Add the two `@SpringBootApplication` entry points; apply Boot repackaging only
  to executable services. Ignore `**/target/`, IDE output, and local secret files.
- [ ] Add GitHub Actions using Java 25 and `./mvnw -B verify` on push/pull request.
- [ ] Run `./mvnw -B verify`; expect all five modules successful. Record environment
  prerequisites honestly; never silently disable integration tests in later steps.
- [ ] Commit/push: `build: initialize Java 25 Maven reactor`.
