# Task 5 independent review

## Initial verdict

Changes requested for one Important acceptance gap. The implementation itself was
sound, but the failure suites used an invalid topic name. That proves retry state and
per-row continuation while failing during client-side topic validation; it does not
exercise broker unavailability, acknowledgement timeout, or producer recovery. The
checkpoint explicitly requires outage/recovery evidence.

The reviewer confirmed by inspection that selection is deterministic and bounded,
Kafka waits occur outside database transactions, acknowledgement waits are finite,
updates are conditional, capped retry state is persisted, failed rows do not stop the
batch, overlapping cycles are guarded within one instance, and replay preserves the
UUID string key and exact stored payload.

## Fix round 1

Commit `aaeb941` added a real Kafka-container pause/resume test to each service. Each
test preserves the bootstrap address, proves the publish cycle returns within its
configured timeout, verifies the row remains pending with incremented attempts and
future eligibility, resumes the broker, and confirms the same publisher instance
sends the original key/payload and marks the row published.

Scoped re-review verdict: approved. No Critical or Important regression was found.
The reviewer did not rerun the full reactor. The implementer reported 58 passing
tests. The controller independently reran the Java 25 reactor before pushing: all
six modules and 58 tests passed with zero failures, errors, or skips.

## Deferred minor notes

- The bounded-batch tests do not force identical creation timestamps, so they do not
  directly exercise the UUID tie-break specified by the production query.
- Publisher/repository code is duplicated across the service modules. This is
  acceptable for explicit service ownership in version one, but future changes must
  keep their behavior aligned.
