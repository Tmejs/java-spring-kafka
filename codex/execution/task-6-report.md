# Task 6 implementation report

## Result

Implemented atomic Inventory reservation processing and the `orders.v1` Kafka
listener without changing the pushed V1 schema.

- `ReservationService.reserve(OrderCreated)` is the transaction boundary.
- A transaction-scoped PostgreSQL advisory lock derived deterministically from the
  order UUID serializes decisions for one order. Event and final decision rows use
  `INSERT ... ON CONFLICT DO NOTHING`, so duplicate races never continue from a
  caught constraint violation.
- Existing products are locked in UUID order. Every line is validated and every
  stock condition is checked before any quantity is decremented.
- The decision, stock changes, processed-event claim, and exactly one result outbox
  row commit together. Technical outbox failure rolls all of them back.
- Identical event redelivery is a no-op. A new event ID for the same order and
  normalized fingerprint records the delivery without another decision or stock
  change. A changed fingerprint rolls back the new event claim.
- Result timestamps come from the injected `Clock`; result causation IDs reference
  the incoming `OrderCreated` event. Rejection reasons are bounded to
  `UNKNOWN_PRODUCT` and `INSUFFICIENT_STOCK`.
- The Kafka listener decodes with `EventCodec` and delegates to the proxied service.
  Consumer auto-commit is disabled and acknowledgement mode is `record`.

No V2 migration was required. The V1 uniqueness constraints remain the durable
claims, while the advisory lock serializes the interval before the final reservation
row exists. This avoids adding a transient outcome that the V1 reservation check
constraint does not permit.

## TDD evidence

RED:

- The test-first focused build failed compilation because the desired
  `ReservationService` API did not exist.
- After adding the minimal implementation surface, the first executable
  `ReservationIT` run produced 8 errors from PostgreSQL refusing an untyped
  `Instant` parameter. This verified that the tests exercised real database writes.

GREEN:

- Explicit JDBC `Timestamp` binding fixed the database boundary.
- Focused `ReservationIT`: 11 tests, 0 failures, 0 errors, 0 skipped.
- The suite covers multi-item success, unknown product, insufficient stock and no
  partial mutation, identical duplicate delivery, a new event for the same order,
  normalized item order, duplicate item rejection, changed-fingerprint rollback,
  competing orders, concurrent same-order delivery, technical failure rollback,
  and a real Kafka/PostgreSQL path.
- The Kafka path publishes the same record twice, observes consumer-group committed
  offset at least 2, and retains one stock mutation, decision, processed-event row,
  and result outbox row.

The first full reactor run exposed an existing timing race in Inventory's outbox
recovery test: a delayed pre-recovery Kafka send could be consumed while the second
publish attempt was still pending. The test now retries the same publisher within a
bounded Awaitility window until PostgreSQL records publication. Focused
`OutboxPublisherIT`: 4 tests, 0 failures, 0 errors, 0 skipped.

Final verification:

```text
./mvnw -B verify
BUILD SUCCESS
69 tests, 0 failures, 0 errors, 0 skipped
```

## Constraints and limitations

- Event IDs are globally unique by contract. V1 `processed_events` stores only the
  event ID and timestamp, so it cannot identify the original order if a producer
  illegally reuses one event ID across different orders; processing still fails
  before business mutation in that case.
- The advisory key folds a 128-bit UUID into a stable 64-bit PostgreSQL lock key.
  A collision can serialize unrelated orders but cannot corrupt their decisions.
- Retry and dead-letter behavior remain intentionally deferred to task 7.
- Docker Desktop's credential helper repeatedly added startup latency during local
  verification; it did not change test outcomes.
