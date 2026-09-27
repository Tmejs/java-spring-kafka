# Order Reservation System

A compact portfolio project that demonstrates a contract-first, event-driven Java
system. Customers submit an order to the Orders service. A Kafka event asks the
Inventory service to reserve every line atomically, and a result event confirms or
rejects the order.

```text
HTTP -> Orders -> orders DB + outbox -> orders.v1
                                         |
                                         v
                              Inventory -> inventory DB + outbox
                                         |
                                         v
                              reservation-results.v1 -> Orders
```

The repository is a Maven multi-module build:

| Module | Responsibility |
| --- | --- |
| `api-contracts` | Source OpenAPI contracts and generated server interfaces |
| `api-clients` | Java clients generated from the same contracts |
| `event-contracts` | Versioned Kafka event records and JSON codec |
| `order-service` | Order API, idempotency, order outbox, and result consumer |
| `inventory-service` | Product API, atomic reservation, and result outbox |

The stack uses Java 25, Spring Boot 4.1, Maven, Kafka in KRaft mode, PostgreSQL,
Flyway, Keycloak, springdoc OpenAPI, Actuator, and Micrometer's Prometheus format.
Docker Compose builds both services from source and starts the complete local system.

## Run the stack

You need Docker with the Compose plugin. Host Java is optional for Compose; local
verification uses a Java 25 JDK.

Create the ignored local environment file:

```bash
cp .env.example .env
```

The supplied values are deliberately weak demo credentials. Keep `.env` local and
replace every password and client secret before running anywhere except an isolated
development machine.

Build, start, and wait for every long-running service to become healthy:

```bash
docker compose up --build --wait
```

Stop containers while retaining PostgreSQL and Kafka data:

```bash
docker compose down
```

Reset the demo completely, including named volumes:

```bash
docker compose down --volumes
```

| Component | Local address | Access |
| --- | --- | --- |
| Orders API | `http://localhost:8080` | Bearer token with `orders-api` audience and `CUSTOMER` role |
| Orders management | `http://localhost:9080` | Health public; Prometheus secured |
| Inventory API | `http://localhost:8081` | Read or admin bearer token depending on operation |
| Inventory management | `http://localhost:9081` | Health public; Prometheus secured |
| Keycloak | `http://localhost:8180` | Reservation realm and local administration |

Kafka has no published host port. It is reachable only by containers on the Compose
network.

The Swagger UIs are at `http://localhost:8080/swagger-ui.html` and
`http://localhost:8081/swagger-ui.html`. Their source contracts are available at
`/openapi/orders.yaml` and `/openapi/inventory.yaml`. Keycloak provides Authorization
Code with PKCE for browser use. Minimal health and probe endpoints are anonymous;
`/actuator/prometheus` requires a signed token with the service audience and
`metrics.read` scope. Other management endpoints are not exposed.

## Verify locally

With Java 25 selected and Docker running:

```bash
./mvnw -B verify
docker compose config --quiet
```

The Maven gate runs unit tests plus PostgreSQL, Kafka, and Keycloak integration
tests. Compose health checks cover the databases, broker, identity provider, and
both application readiness endpoints.

## Reliability model

Each business change and its outgoing event are committed in one PostgreSQL
transaction. Scheduled outbox publishers retry delivery after transient Kafka
failures. Consumers use database idempotency records, and Inventory reserves all
order lines or none. Poison records receive bounded retries before the original key
and value are written to a matching dead-letter topic. API order creation is also
idempotent per authenticated owner and `Idempotency-Key`.

This is a single-broker local topology with one instance of each service. Blocking
consumer retries pause the affected partition, DLT replay is manual, and metrics are
process-local until an external Prometheus server scrapes them. Prometheus, Grafana,
multiple brokers, and production secret management are intentionally deferred.

## Project record

The [`codex/`](codex/) directory records the approved design, checkpoint plan,
reviews, and verification evidence produced while developing the project with Codex.
Ideas intentionally deferred beyond version one are tracked in
[`codex/version-2.md`](codex/version-2.md). Scripted demo and DLT replay documentation
belong to the next checkpoint and are not claimed here yet.
