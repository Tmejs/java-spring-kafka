# Task 4 independent review

## Initial verdict

Changes requested. One Important finding: the request contract's `uniqueItems`
generated a `Set`, so two identical JSON order items were collapsed before domain
validation and an invalid order could be accepted. The existing duplicate test used
different quantities and did not cover that serialization case.

No other findings were reported. The reviewer confirmed the versioned event codec,
generated API implementation, JWT ownership, owner-qualified reads, deterministic
fingerprints, conflict-safe idempotency claim, original-response replay, atomic
outbox transaction, injected clock, and absence of a Kafka publisher at this stage.

## Fix round 1

Commit `7438dbc` removed request-only `uniqueItems` so generated request models keep
a `List`. The existing product-ID uniqueness rule now receives every JSON item and
rejects duplicates. A raw HTTP regression proves an identical pair returns `400`
and leaves orders, idempotency keys, and outbox rows empty.

Scoped re-review verdict: approved. The original Important finding is addressed,
and no Critical or Important regression was found. The reviewer did not rerun the
full reactor; the controller performs that gate before pushing the checkpoint.
