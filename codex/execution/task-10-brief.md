# Task 10 requirements

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
