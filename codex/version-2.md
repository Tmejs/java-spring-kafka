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

## Monitoring infrastructure

Add a Prometheus server to scrape the version-one Actuator endpoints, Grafana
dashboards for service health and reservation processing, and alerts for growing
outbox backlogs and repeated processing failures. The metric instrumentation and
Prometheus exposition endpoints themselves belong to version one.

## Kafka security hardening

Add broker authentication, topic ACLs with distinct service principals, and TLS.
Version one uses an internal Compose network without host-published Kafka ports;
this is local demo isolation, not an authenticated production broker deployment.
