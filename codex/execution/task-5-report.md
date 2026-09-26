# Task 5 report: Kafka outbox publishing

## Delivered behavior

- Added Spring Boot's managed Kafka starter to both services. Boot 4.1.1 resolves
  Spring Kafka 4.1.1 and Kafka clients 4.2.1.
- Added one scheduled publisher per service with a deterministic, bounded pending-row
  query. The database query completes before any broker send. Each acknowledged send
  is followed by a conditional success update in its own transaction; each failure is
  followed by a conditional retry update in its own transaction.
- Configured string keys and values, `acks=all`, idempotent producers, a finite
  acknowledgement timeout, fixed scheduling delay, and environment-overridable broker
  addresses. The Kafka key is the order UUID and the value is the exact stored payload.
- Persisted retry count, next attempt time, and a bounded error detail. Retry delay uses
  capped, overflow-safe exponential backoff. A failed row does not stop later eligible
  rows in the same batch, and interruption restores the thread interrupt flag.
- Added an in-process lock so scheduled cycles cannot overlap within one service
  instance. V1 assumes one instance of each service. Cross-instance leasing is deferred;
  at-least-once delivery and consumer idempotency remain the reliability contract.
- Reused the V1 retry columns and pending index. No Flyway V2 migration was needed.

## Test-driven evidence

RED was captured with the focused Order publisher integration test before production
implementation. Compilation failed because `OutboxPublisher` did not exist. The first
GREEN attempt exposed test isolation rather than publisher behavior: consumers using
`auto.offset.reset=earliest` read prior test records from the shared topic. Assertions
were changed to wait for the current random event payloads only. A JDBC assertion also
revealed PostgreSQL returning `TIMESTAMPTZ` as `java.sql.Timestamp` in a map; the test
now requests `Instant` directly from `JdbcTemplate`.

Both `OutboxPublisherIT` suites use real PostgreSQL 18.1 and Kafka. They prove:

- an acknowledged bounded batch publishes the exact payload with the order UUID key,
  marks only that batch published, and leaves the next row pending;
- an invalid-topic producer failure persists attempt/backoff/error state, leaves the
  row pending, permits the next row to publish, and publishes the failed row after its
  topic and eligibility are recovered;
- a record sent before its database row is marked is sent again on the next cycle with
  the same key, event ID, and payload, after which `published_at` is populated.
- a real broker outage returns within the configured finite acknowledgement timeout,
  leaves the row pending, and persists its incremented attempt count, future retry time,
  and error detail. The tests pause and resume the same Kafka container, preserving its
  bootstrap address, then use the same publisher instance to publish the exact key and
  payload after recovery and mark the row published.

The outage/recovery review fix did not require a production change: the new tests were
GREEN immediately because the publisher already applied a finite acknowledgement
timeout and persisted retry state. This was a missing integration-evidence case, so no
artificial RED was introduced. The existing invalid-topic scenario remains the focused
evidence for per-row continuation within a batch.

Focused GREEN:

- Order `OutboxPublisherIT`: 4 tests passed.
- Inventory `OutboxPublisherIT`: 4 tests passed.

Full Java 25 gate:

```text
./mvnw -B verify
BUILD SUCCESS
Tests: 58, failures: 0, errors: 0, skipped: 0
```

## Version choice and limitations

Tests use Testcontainers 2.0.5 (managed by Boot) with
`org.testcontainers.kafka.KafkaContainer` and the pinned
`apache/kafka-native:4.1.1` image. This uses the current Testcontainers package rather
than the legacy container class and remains wire-compatible with Kafka client 4.2.1.

Delivery is intentionally at least once. A process failure after Kafka acknowledgement
and before `published_at` is committed produces a duplicate with the original event ID
and payload. Task 6/7 consumers must retain processed-event deduplication. Multi-instance
publisher leasing is outside the V1 single-instance deployment assumption.
