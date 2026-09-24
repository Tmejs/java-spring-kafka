# Version-one acceptance matrix

This is the integration checklist for the approved design; unchecked rows are not
claims of delivered functionality. Test names may evolve with implementation.

| Requirement | Checkpoint | Required evidence | Status |
|---|---|---|---|
| Java25 five-module reactor | 1 | clean Maven verify and executable jar smoke | Locally verified; review passed |
| Generated interfaces/clients | 2 | clean generation/compilation, no tracked generated Java | Verified |
| Original OpenAPI/Swagger | 2 | HTTP spec and UI retrieval | Verified: 6 HTTP tests |
| Real JWT validation | 2a | wrong signature/issuer/audience/time failures | Verified: 14 tests |
| Realm import | 2a,9 | real Keycloak-issued token accepted | Realm verified; Compose pending |
| Customer/admin permissions | 2a,3,4 | positive and negative endpoint tests | Policy verified; APIs pending |
| Alice/Bob isolation | 4 | other owner's order returns 404 | Pending |
| Owner-scoped idempotency | 4 | same owner replay/conflict/concurrency and different owners | Pending |
| Flyway ownership | 3 | fresh PostgreSQL schemas and restricted credentials | Pending |
| All-or-nothing reservation | 6 | multi-item failure leaves every stock count unchanged | Pending |
| No overselling | 6 | competing transactions and exact final stock | Pending |
| Duplicate protection | 6,7 | same event and different event ID for same order | Pending |
| Outbox recovery | 5 | broker unavailable/recovered and send-before-mark replay | Pending |
| Consumer DB/offset gap | 6,7 | redelivery cannot repeat business effect | Pending |
| Terminal consistency | 7 | conflicting result cannot overwrite state | Pending |
| Retry and DLT | 7 | malformed/exhausted events, unavailable DLT producer | Pending |
| Safe manual replay | 10 | exact key/payload/ID preserved | Pending |
| Actuator protection | 8 | public minimal health, scoped metrics access | Policy verified; Actuator pending |
| Observability | 8 | bounded labels, committed-only counters, MDC cleanup | Pending |
| One-command launch | 9 | Compose build and health readiness | Pending |
| Persistent data | 9 | restart retains stock/orders | Pending |
| Portfolio demo | 10 | repeatable successful/rejected orders and metric scrape | Pending |
| Security demo | 10 | browser PKCE plus machine token script | Pending |
