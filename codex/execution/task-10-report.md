# Task 10 implementation report

## Scope

Checkpoint 10 adds a repeatable portfolio walkthrough and a byte-preserving,
single-record dead-letter recovery path. It also expands the root README into the
operator and architecture guide for version one.

## Implementation

- Added `scripts/demo.sh` with bounded HTTP calls and polling, unique data per run,
  least-privilege service accounts, exact success/rejection assertions, final stock
  validation, and authenticated metric scrapes.
- Added the `dlt-replay-tool` Maven module and its `tools` Compose profile. The tool
  directly assigns one DLT partition, seeks one exact offset, preserves nullable
  key/value byte arrays, validates the allowlisted original topic and partition
  headers, synchronously publishes one record, and never commits or deletes the DLT
  record.
- Added `scripts/replay-dlt.sh` as a strict three-argument wrapper for the two
  supported DLT mappings.
- Expanded `README.md` with architecture, module and data ownership, API and event
  examples, generated-client usage, OAuth2 roles and PKCE, metrics, reliability
  guarantees, DLT inspection/replay, reset behavior, and troubleshooting.

Bearer tokens and client secrets are neither printed nor stored in files. The demo
removes any export attribute inherited from `.env`, URL-encodes token request data
through standard input, and supplies Authorization headers to curl through standard
input configuration.

## Verification

- `bash -n scripts/demo.sh scripts/replay-dlt.sh`: passed.
- `docker compose config --quiet`: passed.
- SDKMAN Temurin 25.0.4, `./mvnw -B verify`: six child modules plus the parent,
  109 tests, zero
  failures, zero errors, zero skips, `BUILD SUCCESS` in 1:52.
- `./scripts/demo.sh && ./scripts/demo.sh`: passed twice consecutively against
  retained volumes. Each run confirmed quantity 3, rejected quantity 8 with
  `INSUFFICIENT_STOCK`, left quantity 7, and authenticated to both Prometheus
  endpoints.
- Replay unit coverage passed for embedded newline/NUL bytes, empty key/value,
  null key/value, exact offset selection, allowlist rejection, one output record,
  and no committed consumer offset.

## Runtime recovery proof

A valid quantity-1 order was allowed to reach `orders.v1.DLT[0]@0` while new
Inventory database connections were denied. After restoring connectivity, exact
replay confirmed order `f9b2148d-e5a3-460e-861b-de0643aa91fc` and reduced product
`9236edf8-15bc-479b-aeee-0e385ab6f5c4` from 7 to 6 once.

Replaying the same DLT partition and offset a second time published one record to
`orders.v1[0]@10`. After consumer processing:

- available stock remained 6;
- the recovery order still had exactly one reservation;
- the event still had exactly one processed-event marker; and
- `orders.v1.DLT` still ended at offset 1, so replay neither removed nor recreated
  the DLT record.

This proves the manual tool replays exactly one selected raw record and the
Inventory consumer prevents a second business effect.
