# Task 8 execution report

## Delivered

- Added Spring Boot Actuator and the Prometheus registry to both services.
- Moved operational endpoints to dedicated management ports (`9080` for orders and
  `9081` for inventory), exposed only health and Prometheus, and kept actuator
  endpoints off the API ports.
- Added separate ordered security chains. Health and probes are anonymous and
  minimal; Prometheus requires a signed JWT with the service audience and
  `metrics.read` scope; other actuator endpoints are denied.
- Configured liveness from application state only. Readiness combines application
  readiness, PostgreSQL, and a Kafka connectivity indicator whose Admin request is
  time-bounded and whose client is closed after every check.
- Added bounded-tag counters for created orders, reservation outcomes, duplicates,
  outbox publication failures, and DLT outcomes, plus the reservation processing
  timer. Transactional business counters register only through `afterCommit`.
- Added scheduled cached outbox count and oldest-age gauges, so Prometheus scrapes
  do not execute database queries.
- Enabled Spring Kafka listener and template observations and retained framework
  HTTP, JVM, connection-pool, and Kafka meters.
- Enabled Spring Boot ECS JSON console logs. HTTP and listener correlation contexts
  add correlation, order, and event identifiers to MDC with scoped cleanup that
  preserves unrelated MDC entries. Kafka producer failure logs omit record values.

## Test-first evidence

The initial focused observability test run failed because metrics, correlation
components, and Actuator dependencies did not exist. The implementation was then
added in small green steps.

- Focused metric and correlation unit tests: 6 tests, 0 failures, 0 errors, 0 skips.
- Real management-socket observability integration tests: 6 tests, 0 failures,
  0 errors, 0 skips. They use random API and management ports plus a real signed
  JWT/JWKS flow. They prove anonymous health/probes, bounded unavailable-Kafka
  readiness, independent liveness, all Prometheus 401/403/200 cases, denial of
  unrelated actuator endpoints, absence of actuator on the API port, framework and
  outbox meters, and parseable ECS correlation fields.
- Existing PostgreSQL integration tests now prove successful mutation, duplicate,
  and rollback metric deltas. Existing DLT tests prove successful and failed
  recovery metrics.
- Java 25.0.4 full reactor: `./mvnw -B clean verify` passed all six modules with
  101 tests, 0 failures, 0 errors, and 0 skips.
- `git diff --check` passed.

## Design decisions

- PostgreSQL remains authoritative. Business counters reflect committed mutations
  through transaction synchronizations; duplicate counters are registered only on
  the successful duplicate branch.
- Metric labels are restricted to bounded event type, outcome, reason, source, and
  result values. Order, event, and product identifiers are log fields only.
- Kafka readiness opens a short-lived Admin client for each check and uses the
  configured two-second bound. This avoids a lingering background Admin client when
  Kafka is unavailable.
- Outbox gauges use scheduled JDBC snapshots held in atomics. A scrape reads only
  cached values.
- DLT metrics use Spring Kafka retry callbacks so recovery success and recovery
  failure are counted at the actual outcome.

## Known limitations

- Micrometer counters are process-local until a Prometheus server scrapes them; a
  restart resets the local series, and there is an unavoidable small gap between a
  database commit and the in-process `afterCommit` callback.
- Kafka readiness creates a short-lived Admin client per health request. The request
  is bounded, but frequent external polling still creates connection work.
- Prometheus server and Grafana dashboards remain version 2 work. Task 8 provides
  the secured scrape endpoints and application instrumentation only.
