# Version 2 ideas

These ideas are intentionally deferred to keep version one small. They are not
part of the current implementation scope.

## Order cancellation and stock release

Allow a customer to cancel an order and return its reserved quantities to available
inventory through Kafka events.

Before implementing, decide:

- Which order states permit cancellation.
- How cancellation interacts with a reservation that is still being processed.
- How repeated requests and duplicate events avoid releasing stock twice.
- Which intermediate and terminal states are exposed through the OpenAPI contract.

Include integration tests for cancellation racing with reservation, duplicate
events, and recovery after a service restart.
