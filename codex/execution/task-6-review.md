# Task 6 independent review

## Initial verdict

Changes requested for one Important contract gap. `ReservationService` accepted an
OrderCreated event whose required `metadata.occurredAt` was null, allowing malformed
input to reserve stock and create a decision/result.

The reviewer confirmed the transaction-scoped advisory lock safely serializes
same-order races; fingerprint mismatch rolls back the new processed-event claim;
matching new event IDs record delivery without repeating stock, decision, or outbox;
product locks are ordered and every availability check precedes mutation; unknown
product takes precedence; result metadata and causation are correct; record
acknowledgement follows the proxied transaction; and no task-7 retry/DLT behavior
was introduced. The Inventory outbox recovery polling change was judged a valid
bounded allowance for producer recovery after broker resume.

## Fix round 1

Commit `ceb6e83` added `occurredAt` to required metadata validation and an integration
regression. Validation runs before fingerprinting, advisory locking, event claiming,
product locking, or writes. The regression asserts an argument error, unchanged
stock, and zero reservation, processed-event, and outbox rows.

Scoped re-review verdict: approved. No Critical or Important regression was found.
The implementer reported a clean Java 25 reactor with 70 tests; the controller
independently reran the complete reactor before pushing: all six modules and 70
tests passed with zero failures, errors, or skips.
