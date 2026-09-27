# Task 7 report: order outcomes and Kafka recovery

## Delivered behavior

- The Orders service consumes string payloads from `reservation-results.v1`, decodes
  `StockReserved` and `StockRejected` inside the listener, and applies the result in
  one database transaction.
- Outcome processing locks the order row, validates required metadata and the
  persisted `OrderCreated` causation ID, and claims `processed_events.event_id` with
  `INSERT ... ON CONFLICT DO NOTHING`.
- `PENDING` transitions once. Exact redelivery is a no-op, a new event carrying the
  same terminal result is recorded, and an opposite result or changed rejection
  reason throws and rolls back its event claim. Concurrent opposite results leave
  one terminal decision and one processed-event claim.
- Unknown orders, malformed contracts, unsupported versions, wrong causation, and
  terminal conflicts remain technical failures. They never create a `REJECTED`
  business result.
- Each service owns one `CommonErrorHandler`. It uses `FixedBackOff(1000, 3)` for
  four total deliveries and publishes the original record to `<source>.DLT` on the
  source partition. DLT sends use `setFailIfSendResultIsError(true)` and a bounded
  acknowledgement wait. Record acknowledgement remains enabled and recovered
  offsets are not committed when DLT publication fails.

## TDD evidence

- RED: the contract test did not compile before `decodeReservationResult` existed;
  a missing event type later exposed a `NullPointerException` instead of the
  required technical `IllegalArgumentException`. GREEN: explicit event-type
  dispatch handles both result records and rejects unsupported or missing types.
- RED: outcome integration tests did not compile before `OrderOutcomeService`
  existed. Later focused tests showed that a changed rejection reason and an
  opposite result reusing an event ID could bypass terminal consistency. GREEN:
  pre-claim terminal validation plus the locked transactional update makes both
  cases fail without another claim.
- RED: the first real Kafka result left its Orders row `PENDING`; malformed and
  unsupported records had no bounded recovery path. GREEN: the result listener and
  service-local error handlers now cover successful consumption, four timed
  deliveries, transient recovery, exhausted DLT publication, and failed DLT send.
- Real Kafka tests record delivery timestamps and assert each retry gap is at least
  900 ms. Successful DLT tests filter and compare the exact key/value and wait for
  the committed source offset to reach the produced offset plus one. Failure tests
  disable broker auto-creation, delete the DLT, observe the acknowledged producer
  failure and another delivery cycle, and assert the source offset is still below
  the produced offset plus one before restoring the DLT.

## Verification

- Focused `EventCodecTest`: 5 tests, all passing.
- Focused `OrderOutcomeIT`: 10 tests, all passing, including concurrent opposite
  outcomes.
- Focused real-broker `DeadLetterIT`: Orders 5 tests and Inventory 4 tests, all
  passing.
- Final gate: SDKMAN Temurin `25.0.4`, `./mvnw -B verify`, 90 tests, zero failures,
  zero errors, zero skipped; reactor `BUILD SUCCESS` in 1 minute 46 seconds.

## Deliberate limits

- Blocking retries pause the affected consumer partition for the bounded retry
  window.
- DLT inspection and replay remain manual; task 10 owns replay tooling.
- Task 8 owns Kafka and business metrics, so this checkpoint adds no metric series.
