# Task 2 report — contract-first HTTP interfaces and clients

Date: 2026-09-24

## Outcome

Orders and Inventory now have authoritative packaged OpenAPI contracts. OpenAPI
Generator 7.25.0 creates Boot 4/Jackson 3 server interfaces in the services and
native Jackson 3 clients in `api-clients`, all under ignored `target/` paths.
Springdoc 3.1.1 serves Swagger UI against the original YAML with service-specific
audience scope, public client `reservation-swagger`, and PKCE S256. The generated
`/v3/api-docs` document remains disabled.

The contracts define UUID identifiers; positive order/stock quantities; nonempty,
unique order item arrays with distinct-product requirements; order status and
rejection reasons; bounded product pagination; problem responses; required
idempotency and Location headers; the required response codes; realm-role rules;
and Keycloak authorization-code URLs and audience-selection scopes.

## Red/green evidence

The tests were added before the resource/UI implementation. After test support
and the contract dependency were present, these commands each failed with two
expected assertions (`404` instead of `200` for YAML and Swagger UI):

```text
./mvnw -B -pl order-service -am verify
./mvnw -B -pl inventory-service -am verify
```

After implementation, the same focused commands each passed 3/3 real random-port
tests: served YAML equals its classpath source byte-for-byte; Swagger UI config
selects the original contract, client, audience scope, and PKCE; and `/v3/api-docs`
returns 404.

Springdoc 3.1.1 conditions its Swagger UI configuration on a
`SpringDocConfiguration` bean, while `springdoc.api-docs.enabled=false` suppresses
that bean. Each service supplies only the three UI prerequisites in its existing
OpenAPI configuration, keeping generated document routes disabled. A diagnostic
run with API docs temporarily enabled confirmed this coupling before the fix.

## Final verification

```text
env JAVA_HOME=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home \
  PATH=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin \
  ./mvnw -B clean verify
```

Result: `BUILD SUCCESS`; all six reactor projects succeeded. Orders and Inventory
each ran 3 tests with zero failures/errors. Artifact inspection found both YAMLs
in the contract JAR and the expected operations in `OrdersApi` and `ProductsApi`
for both server and client packages. Generated Java imports Jackson 3 databind
(`tools.jackson.databind`), no generated source imports Jackson 2 databind, no
generated path is tracked, and `git diff --check` passed.

## Concern for later checkpoints

OpenAPI Generator's native Java template accepts a caller's bearer token through
`ApiClient.setRequestInterceptor(...)` or each operation's additional-header map;
it does not generate the dedicated OAuth helper that some other Java client
libraries provide. No controller, domain behavior, or security implementation was
added in this checkpoint.
