# Task 7 independent review

## Initial verdict

Changes requested for three Important findings:

- Orders accepted arbitrary nonblank rejection reason strings even though the public
  API permits only `UNKNOWN_PRODUCT` and `INSUFFICIENT_STOCK`; an unknown stored code
  could later make generated API mapping fail.
- Direct service idempotency tests did not prove real Orders Kafka redelivery after
  the first database commit and before later offset progress.
- DLT recovery targeted the source partition, requiring DLT topics to have every
  source partition although production setup and tests did not enforce that shape.

The reviewer otherwise confirmed typed decoding, locked transactional outcome
updates, persisted causation validation, conflict-safe claims, terminal conflict
rollback, record acknowledgement, four total one-second-spaced deliveries, exact
DLT key/value, synchronous confirmed recovery, transient recovery, and one unique
error handler per service.

## Fix round 1

Commit `6421892` restricts rejection reasons to the two contract values and adds a
zero-persistence regression plus a generated-client read of an accepted rejection.
Orders now publishes the identical result again only after the first database commit
and proves the second source offset advances with one claim and one terminal effect.

Both recoverers now use DLT partition `-1`, allowing Kafka to select a valid target
partition while retaining the original key/value. Both real-broker suites send the
failed record on source partition 1 to a one-partition DLT and prove destination
partition 0, exact payload, and source-offset advancement after recovery.

Scoped re-review verdict: approved. No Critical or Important regression was found.
The global event-ID-only processed-event identity remains a deferred Minor for
illegal producer ID collisions. The implementer reported a clean 91-test Java 25
reactor. Fresh controller-owned verification independently passed the same six
modules and 91 tests with zero failures, errors, or skips in 4:13.
