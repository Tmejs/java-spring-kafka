# Task 4 report — event contracts and atomic order creation

Implemented explicit versioned JSON records and typed decoders for `OrderCreated`,
`StockReserved`, and `StockRejected`. The codec rejects malformed JSON, wrong event
types, and unsupported versions without Java type metadata.

Orders now implements the generated `OrdersApi`. Creation derives ownership only
from `CurrentOwner`, canonicalizes lines by product UUID, fingerprints the canonical
UTF-8 representation with SHA-256, and claims `(owner_subject, idempotency_key)`
using PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`. A single transaction writes
the order and items, original response and Location, and `OrderCreated` outbox row.
Matching retries replay the stored `202` response; changed payloads return `409`.
GET uses an owner-qualified repository query and returns current status/reason.

The task 3 V1 schema already supported the required nullable claim and atomic
completion flow, so no migration was changed or added.

## TDD and verification

- RED: `EventCodecTest` failed compilation because the six event contract/codec
  types did not exist. GREEN: 4 codec tests passed.
- RED: `OrderCreationIT` reached the real secured HTTP service and returned `404`
  for the five missing endpoint behaviors. GREEN: 5 generated-client tests passed
  with PostgreSQL 18.1 and RSA-signed JWTs.
- Review RED: a raw request containing the same `{productId, quantity}` object twice
  returned `202` because request `uniqueItems` generated a `Set` and collapsed the
  duplicate before domain validation. GREEN: request items now generate as a `List`;
  the unchanged product-id validation returns `400` and persists no order,
  idempotency key, or outbox row. Focused `OrderCreationIT`: 6 tests passed.
- HTTP coverage includes original-response replay after status mutation, changed
  payload conflict, simultaneous same-key requests, Alice/Bob using the same key,
  owner-isolated GET, empty/duplicate items, injected-clock event timestamps, and
  one pending outbox row.
- Full local gate: SDKMAN Temurin Java `25.0.4`, `./mvnw -B verify` — PASS,
  50 tests, 0 failures/errors/skips (the prior 40 plus 10 task 4 tests).
