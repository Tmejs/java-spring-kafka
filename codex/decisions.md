# Agreed decisions

## 2026-09-23 — Project foundation and version-one scope

- Build a small portfolio order reservation system using Java 25 and Spring.
- Use a Maven multi-module repository with two microservices communicating through Kafka.
- Run the complete application and its infrastructure through Docker Compose.
- Use contract-first OpenAPI specifications and Maven-generated Java clients.
- Keep database migrations in each service's `src/main/resources/db/migration/` directory.
- Work in small, meaningful commits that act as project checkpoints.
- Keep Codex collaboration documents in `codex/` and reference them during work.
- Target repository: https://github.com/Tmejs/java-spring-kafka.

Version one covers order creation and the reservation outcome: an order starts
`PENDING` and becomes `CONFIRMED` or `REJECTED`. Keep cancellation and releasing
reserved stock out of version one; retain them as [version-two ideas](version-2.md).

The consolidated design is recorded in [design.md](design.md).

## Version-one reliability requirements

The user explicitly confirmed that all three belong in version one:

- All-or-nothing inventory reservation: reserve every order item in one database
  transaction, or reserve none when any item lacks sufficient stock.
- Duplicate-event protection: repeated delivery must not reserve stock twice or
  apply an order outcome more than once.
- Transactional outbox: persist each business change and its outgoing event in
  the same database transaction, then publish the event asynchronously to Kafka.

The verified commit-and-push checkpoint workflow has also been approved.

## Version-one observability

Include Spring Boot Actuator, Micrometer, and Prometheus-format metrics in both
services, plus structured logs with order and event correlation identifiers.
Expose health, readiness/liveness, and metrics on separate management ports bound
to localhost by Compose. Keep identifiers out of metric labels.

Prometheus infrastructure, Grafana dashboards, and alerting remain version-two ideas.
