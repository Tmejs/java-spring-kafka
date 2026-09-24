# Checkpoint 2a security compatibility preflight

Date checked: 2026-09-24. This is implementation guidance for the approved
checkpoint; no application, POM, realm, or runtime configuration was changed by
this preflight.

## Version and dependency choice

- Keep Spring Boot `4.1.1`. Its resolved BOM in the local Maven repository manages
  Spring Security `7.1.1` and Testcontainers `2.0.5`; do not override either.
- Use Boot 4's preferred production starter
  `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server`
  in each service. The older `spring-boot-starter-oauth2-resource-server` name is
  deprecated in Boot 4.
- Keep `spring-boot-starter-test`, and add Boot 4's
  `spring-boot-starter-security-oauth2-resource-server-test` in test scope for
  Spring Security MockMvc JWT support. Add `org.testcontainers:testcontainers` in
  test scope for `GenericContainer`; Boot's BOM supplies `2.0.5`. A JUnit
  Testcontainers module is optional if the test starts/stops the container itself.
- Pin `quay.io/keycloak/keycloak:26.7.4`. It is the current stable Keycloak server
  release and includes security fixes. The Keycloak JVM is inside its image, so no
  Keycloak Java client or adapter needs to run on the project's Java 25 JVM.

Primary references: [Boot 4 starter list](https://docs.spring.io/spring-boot/reference/using/build-systems.html),
[Boot 4.1.1 managed coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html),
[Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Testcontainers core dependency](https://github.com/testcontainers/testcontainers-java/blob/main/docs/index.md),
[Testcontainers 2.0.5 releases](https://github.com/testcontainers/testcontainers-java/releases),
[Keycloak 26.7.4 release](https://www.keycloak.org/2026/09/keycloak-2674-released).

## Resource-server configuration

Use all three Boot JWT properties per service:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SECURITY_ISSUER_URI}
          jwk-set-uri: ${SECURITY_JWK_SET_URI}
          audiences: orders-api # inventory-api in Inventory
```

Supplying `issuer-uri` and `jwk-set-uri` together is intentional: Spring fetches
keys directly from the latter while retaining `iss` validation against the former.
Boot's `audiences` property adds `aud` validation. The decoder also performs
signature and timestamp validation, including `exp` and `nbf`. This avoids a
custom decoder/validator unless tests prove a missing requirement.

For Compose, tokens acquired by a browser at localhost must carry the external
issuer, for example `http://localhost:8180/realms/reservation`. Configure Keycloak
with that fixed frontend hostname. Containers cannot fetch JWKS through their own
`localhost`, so configure the services' separate JWKS URL as
`http://keycloak:8080/realms/reservation/protocol/openid-connect/certs`. Do not set
the issuer to the Docker hostname; those tokens would fail when issued through the
browser URL. Keycloak explains that its frontend URL affects issued tokens and
discovery, while backchannel endpoints can use private routing:
[hostname v2](https://www.keycloak.org/server/hostname).

Use a `JwtAuthenticationConverter` whose authorities converter combines:

1. the standard `JwtGrantedAuthoritiesConverter`, preserving `scope`/`scp` as
   `SCOPE_*` authorities (required for `SCOPE_metrics.read`); and
2. only the allow-listed values from `realm_access.roles`, mapped to
   `ROLE_CUSTOMER` and/or `ROLE_INVENTORY_ADMIN`.

Replacing the default scope converter with a realm-role-only converter would
silently break metrics authorization. Ignore every other Keycloak realm role.

Use ordered, stateless bearer chains with explicit matchers. Permit only the
published spec/Swagger resources and minimal health paths; require
`SCOPE_metrics.read` for Prometheus; use the route-specific realm roles; finish
each chain with `denyAll()`. Disable CSRF inside these bearer-only chains. Swagger
is served by each API on the same origin, so application CORS is unnecessary unless
a later deployment actually puts the UI on another origin. Keycloak still needs
the two exact Swagger origins for its browser token call.

## Realm import shape and audience traps

- Swagger client: public, standard flow enabled, implicit/service-account/direct
  grants disabled, `attributes["pkce.code.challenge.method"] = "S256"`, and exact
  redirects only:
  `http://localhost:8080/swagger-ui/oauth2-redirect.html` and
  `http://localhost:8081/swagger-ui/oauth2-redirect.html`. Set `webOrigins` to the
  two origins, with no wildcard. Keycloak's export model confirms the attribute
  name and value: [official example realm](https://github.com/keycloak/keycloak/blob/main/operator/src/test/resources/example-realm.yaml).
- Set `directAccessGrantsEnabled: false` on every project client. Human Alice, Bob,
  and admin log in only through authorization code plus PKCE. Scripted tests and
  demos use client credentials, never user passwords.
- Define optional client scopes `orders-api` and `inventory-api`, each with an
  `oidc-audience-mapper` whose `included.client.audience` is the corresponding API
  and whose access-token flag is true. Link both as optional scopes to Swagger so
  the selected OpenAPI scope determines the one API audience. Do not attach both
  as Swagger defaults, which would issue every browser token for both services.
- Link only `orders-api` as a default scope to the Orders demo service-account
  client, and only `inventory-api` to the Inventory demo client. Assign only
  `CUSTOMER` to the former service account and only `INVENTORY_ADMIN` to the latter.
- Define a `metrics.read` client scope so that the literal scope appears in the
  access token. Link it only to the monitoring client. The one monitoring client
  may carry both API audiences because it intentionally scrapes both management
  endpoints; it must have no business realm roles. Do not rely on a realm role
  named `metrics.read`, because Spring's standard scope mapping expects the OAuth
  `scope` claim.
- Prefer hardcoded audience mappers here. Keycloak's Audience Resolve mapper adds
  audiences according to effective *client roles*, while this design authorizes
  with realm roles; it can therefore omit the audience unexpectedly. The official
  guide explicitly recommends hardcoded audiences when a service relies on realm
  roles: [Keycloak audience support](https://www.keycloak.org/docs/latest/server_admin/#_audience_support).
- With full-scope disabled, remember that service-account token roles are the
  intersection of service-account roles and client/client-scope role mappings.
  Either declare those role-scope mappings explicitly or validate the imported
  tokens before assuming `realm_access.roles` is present. See
  [Keycloak service accounts and token role mappings](https://www.keycloak.org/docs/latest/server_admin/#_service_accounts).
- Put demo client secrets in the realm JSON as `${ENV_VAR}` placeholders and set
  fixed test values on `GenericContainer`. Keycloak supports environment variable
  substitution in any realm-import value:
  [realm import placeholders](https://www.keycloak.org/server/importExport#_using_environment_variables_within_the_realm_configuration_files).

## Test split

`AuthorizationTest` should use Spring Security's MockMvc `jwt()` request post
processor. It bypasses decoding, which is desirable for fast route-policy tests.
Populate `realm_access.roles` and `scope`, and run the application's real combined
authority converter rather than passing prebuilt authorities; otherwise the test
would not cover role-claim parsing. Boot 4 moved `@WebMvcTest` and
`@AutoConfigureMockMvc` to
`org.springframework.boot.webmvc.test.autoconfigure`. The supported `jwt()` API and
claim customization are documented in
[Spring Security MockMvc OAuth2 tests](https://docs.spring.io/spring-security/reference/7.0/servlet/test/mockmvc/oauth2.html),
and the Boot 4 package is documented in
[AutoConfigureMockMvc 4.1.1](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/webmvc/test/autoconfigure/AutoConfigureMockMvc.html).

`JwtValidationIT` must send encoded bearer strings through MockMvc and the actual
decoder. Start a JDK `HttpServer` on loopback before the Spring context, generate
an RSA test key, serve its public JWK as `{"keys":[...]}`, and register dynamic
issuer/JWKS/audience properties. Sign tokens with `NimbusJwtEncoder` or Nimbus
JOSE. Cover valid, different private key, wrong `iss`, wrong/missing `aud`, expired,
and future `nbf`; make `nbf` at least five minutes ahead because Spring's timestamp
validator allows clock skew. Do not use `jwt()` for these cases because it skips
signature and claim validation.

The smallest real realm-import test is one Failsafe method (one service module is
enough to verify the shared realm artifact) using plain `GenericContainer`:

```java
new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.7.4"))
    .withExposedPorts(8080)
    .withEnv("ORDERS_DEMO_CLIENT_SECRET", "test-orders-secret")
    .withEnv("INVENTORY_DEMO_CLIENT_SECRET", "test-inventory-secret")
    .withEnv("MONITORING_CLIENT_SECRET", "test-monitoring-secret")
    .withCopyFileToContainer(
        MountableFile.forHostPath(realmJson),
        "/opt/keycloak/data/import/reservation-realm.json")
    .withCommand("start-dev", "--import-realm", "--hostname-strict=false")
    .waitingFor(Wait.forHttp(
        "/realms/reservation/.well-known/openid-configuration")
        .forStatusCode(200));
```

Resolve `realmJson` from a root path passed by Maven rather than copying a second
realm into test resources; the test must exercise `infra/keycloak/reservation-realm.json`.
After start, construct the issuer from `getHost()` and `getMappedPort(8080)`, obtain
client-credentials tokens from that same base URL, and validate them with Spring's
decoder against the container JWKS. Assert the exact audience, scope, and allowed
realm roles for Orders, Inventory, and monitoring tokens. This also catches a realm
that imports successfully but emits incorrect claims. Do not hardcode `localhost`
in this test because remote Docker environments may return another host.

Keycloak imports startup files from `/opt/keycloak/data/import` only when
`--import-realm` is supplied, and waits for import before becoming ready:
[Keycloak startup import](https://www.keycloak.org/server/importExport#_importing_a_realm_during_startup) and
[container import](https://www.keycloak.org/server/containers). `GenericContainer`
is sufficient; adding a third-party Keycloak Testcontainers adapter would increase
the dependency surface without covering any requirement here.

## Acceptance checks most likely to catch mistakes

- A token issued through the host URL validates inside each service while JWKS is
  fetched over the Docker-network URL.
- Orders-only credentials fail Inventory audience validation, and Inventory-only
  credentials fail Orders audience validation, both with 401.
- A valid CUSTOMER token reaches Inventory reads but receives 403 on mutations;
  INVENTORY_ADMIN does not gain Orders access.
- A monitoring token reaches Prometheus, has no business role, and fails business
  endpoints even though it has the corresponding audiences.
- Missing/invalid bearer tokens return 401; authenticated insufficient authority
  returns 403. Unmatched application routes are denied.
- Raw bearer strings and Authorization headers are absent from application and test
  logs.
