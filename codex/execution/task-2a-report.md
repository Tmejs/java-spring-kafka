# Task 2a report — Keycloak JWT resource-server security

Date: 2026-09-24

## Outcome

Orders and Inventory are stateless JWT resource servers. Spring validates the
configured external issuer, separately configured JWKS location, service audience,
signature, expiration, and not-before time. Each service preserves OAuth scopes and
maps only `CUSTOMER` and `INVENTORY_ADMIN` from `realm_access.roles`.

Swagger/spec and health routes are public. Prometheus requires
`SCOPE_metrics.read`. Orders routes require `CUSTOMER`; Inventory reads accept
`CUSTOMER` or `INVENTORY_ADMIN`, while mutations require `INVENTORY_ADMIN`.
All unmatched routes are denied. `CurrentOwner.subject()` reads only the validated
JWT subject.

The Keycloak 26.7.4 realm defines Alice, Bob, and admin for browser authorization
code with mandatory PKCE S256; all project clients disable direct grants. Demo
service accounts receive one business role and one API audience each. Monitoring
receives both API audiences and `metrics.read`, with no business role. Machine
client secrets remain environment placeholders in the realm import.

## Red/green evidence

- Orders authorization RED: 5/6 assertions failed against default security
  (`403` instead of missing-token `401`, protected routes returned `200`, and the
  public spec returned `401`). GREEN: 6/6 policy tests passed.
- Inventory authorization RED: 4/6 assertions failed because mutations, metrics,
  and unmatched routes were accessible and the public spec required a token.
  GREEN: 6/6 policy tests passed.
- Each encoded-JWT suite RED: wrong and missing audience returned `200` instead of
  `401`. GREEN after Boot audience configuration: 7/7 tests passed in each service,
  covering valid token, signature, issuer, audience, expiration, and future `nbf`.
- Realm-import RED: Keycloak rejected the absent import artifact as empty JSON.
  GREEN: the pinned Keycloak container imported the real root realm and the one
  integration test verified exact audiences, scopes, allowed roles, and cross-API
  audience rejection for Orders, Inventory, and monitoring credentials.

MockMvc failure printing is disabled for encoded-token tests so bearer values are
not written to test logs.

## Verification

Focused authorization, encoded-JWT, and Keycloak realm-import tests passed under
SDKMAN Temurin Java 25.0.4. The final local
`JAVA_HOME=/Users/matrzad/.sdkman/candidates/java/25.0.4-tem ./mvnw -B verify`
completed with `BUILD SUCCESS`: all six reactor modules passed, with 33 tests,
zero failures, zero errors, and zero skips. This includes the six preserved
OpenAPI HTTP tests and the real Keycloak 26.7.4 container test.
