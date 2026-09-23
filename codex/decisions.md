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

Detailed implementation choices remain subject to the ongoing design discussion.
