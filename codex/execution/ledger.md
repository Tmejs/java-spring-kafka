# SDD ledger — plan: codex/plan.md

Worktree: `/Users/matrzad/Documents/work/java-spring-kafka-v1`
Branch: `feature/reservation-v1`; base: `f5edba3`.
User authorized isolated worktree and subagents; checkpoints push to this branch.
Keep execution documents under codex/ as requested, including review records.

## Preflight

| Tasks | Shared files/interfaces | Finding |
|---|---|---|
| 1 → all | Maven POMs, Java 25, module graph | Build first; later edits sequential. |
| 2 → 2a,3,4 | OpenAPI interfaces and clients | Security schemas precede controllers. |
| 2a → 3,4,8,9,10 | JWT principal and realm | Controller authorization tests complete when endpoints exist. |
| 3 → 4,5,6,7 | migrations/entities | Unique owner/key and reservation order constraints agree. |
| 4 → 5,6,7 | event records and codec | Explicit event type/version; stable IDs. |
| 5 → 6,7,8 | outbox and Kafka configuration | Add consumers/metrics without changing at-least-once guarantee. |
| 6 → 7 | reservation results | Causation references original order event. |
| 7 → 8,10 | DLT handling | Confirm DLT send before offset advancement. |
| 8 → 9,10 | probes/metrics ports and auth | Metrics token required in demo. |
| 9 → 10 | Compose and realm | Demo uses imported machine clients. |
| 1 | build tasks | Scaffold has no behavioral tests yet; actual compile/package verification. |
| 2 | contracts | YAML skeleton illustrative; complete schemas required. |
| 2a | security | Real JWT and realm tests, owner tests wait for endpoints. |
| 3 | persistence/API | Actual PostgreSQL migrations and concurrency checks. |
| 4 | orders | owner-subject key scope supersedes earlier globally unique key language. |
| 5 | publishing | Failure replay intentionally permits duplicates. |
| 6 | reservation | Consistent locks; unique order decision. |
| 7 | outcomes | Stable terminal state; conflict reaches recovery. |
| 8 | observability | Counter assertions measure committed effects only. |
| 9 | runtime | Internal Kafka, separate Keycloak database. |
| 10 | demo | Browser PKCE manual check plus scripted client credentials. |

Task 1: in progress; base f5edba3.

## Environment

- Docker Desktop started and daemon 28.3.3 responds.
- Host Java remains unchanged at 21; temporary official Temurin Java 25.0.4.1
  is `/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home`.
- Archive SHA256 verified against Adoptium metadata:
  `61979887f7506a24a57439ff99adb8b3a7fc89977d9cfe3b8984f58a981b7b9d`.
- Foundation implementer owns task 1 source; compatibility research is read-only.

Task 1 review: build/spec structure accepted; fix CI action SHAs and runner label.
Push/sync is pending by design until review passes; controller owns that step.
Task 1: fix round 1/5 underway (CI pins, precise completion wording).

## Resume — 2026-09-24

Prior fix agent stopped at the account usage limit before any fix edits. User
requested continuation. Verified HEAD03567c1, unchanged CI, available temporary
Java25, Docker daemon 28.3.3, and SSH origin. Resumed original fix implementer.

Task 1: fix round 1/5 (2 addressed, 0 open; commit 9701950).
Task 1: code review clean; local verification complete (f5edba3..9701950).
Controller pushing code and execution records as the foundation checkpoint.
