# Order reservation system — version-one design

Date: 2026-09-23

Status: approved by the user in chat on 2026-09-23.

## Purpose and scope

Build a small portfolio project demonstrating two Java 25 Spring Boot services
communicating reliably through Kafka. Use Maven and start the complete system with
`docker compose up --build`. An order moves from `PENDING` to `CONFIRMED` or
`REJECTED`. Reservation is all-or-nothing. Cancellation, release of stock, and
monitoring infrastructure are deferred to [version 2](version-2.md).

There is no payment processing, shipping, frontend, or customer identity system in
this local demonstration. API and management port mappings bind to localhost.

## Repository and module boundaries

- Root Maven parent: Java version, dependency/plugin management, module aggregation,
  and Maven Wrapper. Use a stable Spring Boot 4 release compatible with Java 25;
  pin exact dependency and container versions during build setup.
- `api-contracts`: Maven module containing versioned OpenAPI YAML for Orders and
  Inventory, packaged for reuse during generation.
- `api-clients`: generate Java clients for both APIs into distinct packages during
  the Maven build. Use clients in integration/demo tooling, not for business
  communication between services.
- `event-contracts`: versioned Kafka event records and serialization contracts.
  No shared JPA entities or business logic.
- `order-service`: REST entry point, order state, idempotency records, processed
  events, and an outbox. Generate Spring API interfaces/models from Orders OpenAPI.
- `inventory-service`: products, stock, reservations, processed events, and an
  outbox. Generate Spring API interfaces/models from Inventory OpenAPI.
- `codex/`: working design, decisions, plans, verification records, and roadmap.

Generated Java belongs under `target/`, excluded from Git. Commit the source
contracts and generator configuration. Each service exposes its specification and
Swagger UI. Controllers implement generated interfaces.

## HTTP contracts

Orders exposes `POST /orders` and `GET /orders/{id}`. An order contains product IDs
and positive integer quantities. Reject empty orders and repeated product IDs as
invalid input. Prices and monetary totals are outside this reservation demo.

Creation requires an `Idempotency-Key` and returns `202 Accepted`, an order ID,
`PENDING` status, and a Location header. Persist a normalized request fingerprint
and the original creation response with a unique key in the same transaction.
The same key and same request return that response; a different request returns
`409 Conflict`. Concurrent requests with the same key create only one order.
Retain keys for the lifetime of the demo data.

Inventory exposes product creation, paginated list/get, and stock addition.
Products have immutable IDs, names, and nonnegative available quantities. Stock
addition accepts a positive integer quantity and is an explicit mutation: unlike
order creation it is not automatically safe to repeat after an uncertain response.
Unknown products and insufficient stock are asynchronous reservation rejections.
Invalid request bodies return `400`; missing resources return `404`. Describe
errors consistently in the OpenAPI contracts using problem detail responses.

## Ownership and persistence

Use PostgreSQL with separate service-owned databases and credentials; one
PostgreSQL container is sufficient locally. Neither service reads the other's
tables. Flyway migrations live in each service's
`src/main/resources/db/migration/`. Validate JPA mappings against migrated schemas;
do not use Hibernate to create or update schemas.

Inventory keeps a reservation decision unique by order ID. Orders keeps order
items, status, and rejection reason where applicable. Both services persist unique
processed event IDs and outbox rows with event payload, routing information,
creation time, attempt information, and publication state.

## Event flow and reliability

1. Orders saves the order and `OrderCreated` outbox row atomically.
2. Its scheduled publisher sends the event to `orders.v1`, keyed by order ID.
3. Inventory handles the event within a database transaction. Lock product rows in
   sorted ID order, check all items before changing stock, and reserve all or none.
   All stock mutations use the same locking discipline to prevent lost updates.
4. In that transaction, Inventory records the processed event and reservation
   decision, and writes `StockReserved` or `StockRejected` to its outbox.
5. Its publisher sends the result to `reservation-results.v1`, keyed by order ID.
6. Orders atomically records the processed event and changes `PENDING` to the
   corresponding terminal state. Terminal outcomes cannot be overwritten by a
   conflicting later result; such a conflict is treated as a processing error.

Events carry `eventId`, `eventType`, `schemaVersion`, `occurredAt`, and `orderId`.
Use JSON with explicit types and version validation, without Java class-name
headers as the cross-service contract. Results reference the triggering event ID.

Outbox publishers mark rows published only after broker acknowledgement. A crash
between send and marking can publish twice: delivery is at least once, not an
exactly-once distributed transaction. Consumers commit the database transaction
before committing Kafka offsets. Unique processed-event constraints make retries
safe; Inventory's unique order decision also prevents reservation twice under
different event IDs. A repeated order with inconsistent items is a contract error.

Use one service instance and one publisher per service in the Compose demo. Multi-
instance outbox coordination is not claimed by this version. Poll in bounded
batches; failed publishing remains pending for retry with backoff.

Consumer failures receive bounded retries, then publish to a source-specific
dead-letter topic. Only acknowledge recovery after that publication succeeds.
Malformed events must also reach the dead-letter path. Technical failures leave
orders `PENDING`; they are not business rejections. Document inspection and manual
replay preserving event IDs. Do not include an automatic replay service in v1.

## Runtime

Compose runs the two applications, a single Kafka broker in KRaft mode, and
PostgreSQL with persistent volumes. Include explicit topic initialization,
health checks, and startup dependency conditions. Applications must tolerate
dependency outages after startup as well. Docker builds run Maven with Java 25,
so a host JDK/Maven installation is not needed to launch the demo.

Expose each service API and its separate management port on distinct localhost
ports. Readiness reflects the service's ability to handle work; liveness does not
depend on PostgreSQL or Kafka, avoiding restarts caused by dependency outages.

## Metrics and logs

Include Actuator, Micrometer, and `micrometer-registry-prometheus` in both services.
Explicitly expose health and `/actuator/prometheus`, enable readiness/liveness
probes, and avoid exposing unrelated management endpoints.

Collect HTTP latency/errors, JVM, database pool, and Kafka listener metrics.
Instrument business counters for created orders and reservation outcomes, plus
reservation processing duration. Count business outcomes after successful commit
and avoid incrementing them for duplicate events.

Instrument pending outbox count, oldest unpublished age, publish failures,
duplicate-event counts, and dead-letter publication outcomes. Refresh database-
backed gauges periodically, not with an expensive query on every scrape. Keep
labels bounded: service, event type, outcome, and known reason codes. Never use
order IDs, event IDs, product IDs, or raw exception messages as metric labels.

Emit structured JSON logs with service, level, message, order ID, and event ID
where available. Clear correlation context after each request/message. Metrics
are operational indicators: process restarts and commit-to-instrumentation gaps
mean counters are not an accounting ledger. PostgreSQL remains authoritative.

Prometheus scraping, Grafana dashboards, and alert rules are deferred. The v1
demo must show that each Prometheus endpoint exposes useful metrics after a flow.

References:
- https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- https://docs.spring.io/spring-boot/api/rest/actuator/prometheus.html

## Verification and portfolio demo

Use focused unit tests and Testcontainers integration tests with actual Kafka
and PostgreSQL. `./mvnw verify` must include the integration tests. Verify:

- Fresh Flyway migrations and application startup.
- OpenAPI generation and generated clients exercising actual endpoints.
- Successful multi-item reservation and insufficient/unknown-product rejection.
- Concurrent orders cannot oversell; a rejected order reserves no partial stock.
- Identical HTTP retries and conflicting idempotency-key requests.
- Repeated events and repeated order IDs cannot reserve stock twice.
- Outbox recovery after broker interruption and duplicate publication.
- Consumer redelivery after database commit and before offset commit.
- Bounded retries, malformed messages, dead-letter failure, and manual replay.
- Health probes, metrics exposition, bounded labels, and duplicate metric behavior.

Provide a repeatable Compose smoke/demo script that creates products, adds stock,
submits successful and unsuccessful orders, polls with a timeout, and inspects
metrics. The root README documents startup, APIs, architecture, failure semantics,
demo commands, and cleanup, distinguishing volume-preserving shutdown from reset.

## Delivery workflow

Follow [git-workflow.md](git-workflow.md): small coherent checkpoints, relevant
checks, then commit and push. Keep progress and verification evidence in `codex/`.
Review this consolidated specification before writing the implementation plan.
