#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPLAY_TIMEOUT_SECONDS="${REPLAY_TIMEOUT_SECONDS:-600}"

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
[[ "$REPLAY_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] \
  || fail "REPLAY_TIMEOUT_SECONDS must be a positive integer"
case "$dlt_topic" in
  orders.v1.DLT|reservation-results.v1.DLT) ;;
  *) fail "unsupported DLT topic: $dlt_topic" ;;
esac
[[ -f compose.yaml ]] || fail "run this script from the repository root"

run_bounded() {
  local command_pid watchdog_pid status
  "$@" &
  command_pid=$!
  (
    sleep "$REPLAY_TIMEOUT_SECONDS"
    kill -TERM "$command_pid" 2>/dev/null || exit 0
    sleep 5
    kill -KILL "$command_pid" 2>/dev/null || true
  ) &
  watchdog_pid=$!

  set +e
  wait "$command_pid"
  status=$?
  set -e
  kill "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  if [[ "$status" -eq 137 || "$status" -eq 143 ]]; then
    fail "command exceeded ${REPLAY_TIMEOUT_SECONDS}s timeout"
  fi
  return "$status"
}

run_bounded docker compose ps --status running kafka | grep -q kafka \
  || fail "Kafka is not running; start the Compose stack first"

printf 'Replaying exactly %s[%s]@%s without displaying its key or value.\n' \
  "$dlt_topic" "$partition" "$offset"
run_bounded docker compose --profile tools run --rm --build --no-deps -T dlt-replay \
  kafka:9092 "$dlt_topic" "$partition" "$offset" \
  || fail "the selected record was not replayed"
printf 'Verify the affected order/product state and confirm no new DLT record appeared.\n'
