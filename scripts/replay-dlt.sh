#!/usr/bin/env bash
set -Eeuo pipefail

fail() {
  printf 'replay-dlt: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/replay-dlt.sh <dlt-topic> <partition> <offset>

Allowed mappings:
  orders.v1.DLT                -> orders.v1
  reservation-results.v1.DLT   -> reservation-results.v1

Example:
  scripts/replay-dlt.sh orders.v1.DLT 0 12

Fix the underlying failure and select one exact DLT record first. This command
never scans, edits, removes, or bulk replays DLT records. After replay, verify the
affected domain state and ensure the record did not return to the DLT.
USAGE
  exit 2
}

[[ $# -eq 3 ]] || usage
readonly dlt_topic="$1"
readonly partition="$2"
readonly offset="$3"

[[ "$partition" =~ ^[0-9]+$ ]] || fail "partition must be a non-negative integer"
[[ "$offset" =~ ^[0-9]+$ ]] || fail "offset must be a non-negative integer"
case "$dlt_topic" in
  orders.v1.DLT|reservation-results.v1.DLT) ;;
  *) fail "unsupported DLT topic: $dlt_topic" ;;
esac
[[ -f compose.yaml ]] || fail "run this script from the repository root"
docker compose ps --status running kafka | grep -q kafka \
  || fail "Kafka is not running; start the Compose stack first"

printf 'Replaying exactly %s[%s]@%s without displaying its key or value.\n' \
  "$dlt_topic" "$partition" "$offset"
docker compose --profile tools run --rm --build --no-deps -T dlt-replay \
  kafka:9092 "$dlt_topic" "$partition" "$offset" \
  || fail "the selected record was not replayed"
printf 'Verify the affected order/product state and confirm no new DLT record appeared.\n'
