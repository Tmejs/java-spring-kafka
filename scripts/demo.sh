#!/usr/bin/env bash
set -Eeuo pipefail
set +x
set +v
umask 077

readonly KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
readonly ORDERS_URL="${ORDERS_URL:-http://localhost:8080}"
readonly INVENTORY_URL="${INVENTORY_URL:-http://localhost:8081}"
readonly ORDERS_MANAGEMENT_URL="${ORDERS_MANAGEMENT_URL:-http://localhost:9080}"
readonly INVENTORY_MANAGEMENT_URL="${INVENTORY_MANAGEMENT_URL:-http://localhost:9081}"
readonly POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-45}"
readonly CURL_CONNECT_TIMEOUT_SECONDS="${CURL_CONNECT_TIMEOUT_SECONDS:-5}"
readonly CURL_MAX_TIME_SECONDS="${CURL_MAX_TIME_SECONDS:-15}"

fail() {
  printf 'demo: %s\n' "$*" >&2
  exit 1
}

for command in curl jq; do
  command -v "$command" >/dev/null 2>&1 || fail "required command not found: $command"
done

[[ "$POLL_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "POLL_TIMEOUT_SECONDS must be a positive integer"
[[ -f .env ]] || fail "missing .env; run: cp .env.example .env"

# shellcheck disable=SC1091
source .env

# Keep credentials out of child-process environments even when a local .env uses
# `export`. They are supplied to curl only through standard input below.
export -n ORDERS_DEMO_CLIENT_SECRET INVENTORY_DEMO_CLIENT_SECRET MONITORING_CLIENT_SECRET 2>/dev/null || true

: "${ORDERS_DEMO_CLIENT_SECRET:?ORDERS_DEMO_CLIENT_SECRET is required in .env}"
: "${INVENTORY_DEMO_CLIENT_SECRET:?INVENTORY_DEMO_CLIENT_SECRET is required in .env}"
: "${MONITORING_CLIENT_SECRET:?MONITORING_CLIENT_SECRET is required in .env}"

urlencode() {
  jq -sRr @uri
}

token_for() {
  local client_id="$1"
  local client_secret="$2"
  local encoded_id encoded_secret request response
  encoded_id="$(printf '%s' "$client_id" | urlencode)"
  encoded_secret="$(printf '%s' "$client_secret" | urlencode)"
  request="grant_type=client_credentials&client_id=${encoded_id}&client_secret=${encoded_secret}"
  response="$(printf '%s' "$request" | curl \
    --fail-with-body --silent --show-error \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_MAX_TIME_SECONDS" \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-binary @- \
    "$KEYCLOAK_URL/realms/reservation/protocol/openid-connect/token")" \
    || fail "could not obtain token for $client_id"
  printf '%s' "$response" | jq -er '.access_token | select(type == "string" and length > 0)' \
    || fail "Keycloak returned no access token for $client_id"
}

HTTP_BODY=''
authenticated_request() {
  local token="$1"
  local expected_status="$2"
  local raw actual_status
  shift 2
  raw="$(printf 'header = "Authorization: Bearer %s"\n' "$token" | curl \
    --config - \
    --silent --show-error \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_MAX_TIME_SECONDS" \
    --write-out $'\n%{http_code}' \
    "$@")" || fail "HTTP request failed before receiving a response"
  actual_status="${raw##*$'\n'}"
  HTTP_BODY="${raw%$'\n'*}"
  [[ "$actual_status" == "$expected_status" ]] \
    || fail "HTTP request returned $actual_status, expected $expected_status"
}

poll_order() {
  local token="$1"
  local order_id="$2"
  local expected_status="$3"
  local expected_reason="${4:-}"
  local deadline status reason response
  deadline="$(( $(date +%s) + POLL_TIMEOUT_SECONDS ))"

  while (( $(date +%s) <= deadline )); do
    authenticated_request "$token" 200 "$ORDERS_URL/orders/$order_id"
    response="$HTTP_BODY"
    status="$(jq -er '.status' <<<"$response")" || fail "order $order_id response has no status"
    if [[ "$status" == "$expected_status" ]]; then
      if [[ -n "$expected_reason" ]]; then
        reason="$(jq -er '.rejectionReason' <<<"$response")" \
          || fail "rejected order $order_id has no rejection reason"
        [[ "$reason" == "$expected_reason" ]] \
          || fail "order $order_id reason is $reason, expected $expected_reason"
      fi
      printf '%s' "$response"
      return 0
    fi
    [[ "$status" == "PENDING" ]] \
      || fail "order $order_id reached unexpected status $status while waiting for $expected_status"
    sleep 1
  done

  fail "timed out after ${POLL_TIMEOUT_SECONDS}s waiting for order $order_id to become $expected_status"
}

run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
printf 'Running reservation demo %s\n' "$run_id"

# Tokens stay in this process and are sent to curl over stdin configuration. They are
# never printed, written to disk, or placed in a child process argument.
orders_token="$(token_for orders-demo "$ORDERS_DEMO_CLIENT_SECRET")"
inventory_token="$(token_for inventory-demo "$INVENTORY_DEMO_CLIENT_SECRET")"
monitoring_token="$(token_for monitoring "$MONITORING_CLIENT_SECRET")"
cleanup_secrets() {
  unset orders_token inventory_token monitoring_token
  unset ORDERS_DEMO_CLIENT_SECRET INVENTORY_DEMO_CLIENT_SECRET MONITORING_CLIENT_SECRET
}
trap cleanup_secrets EXIT INT TERM

product_payload="$(jq -cn --arg name "Demo product $run_id" '{name:$name,initialQuantity:0}')"
authenticated_request "$inventory_token" 201 \
  --header 'Content-Type: application/json' \
  --request POST --data "$product_payload" "$INVENTORY_URL/products"
product="$HTTP_BODY"
product_id="$(jq -er '.id' <<<"$product")" || fail "product response has no id"

authenticated_request "$inventory_token" 200 \
  --header 'Content-Type: application/json' \
  --request POST --data '{"quantity":10}' "$INVENTORY_URL/products/$product_id/stock"
stock="$HTTP_BODY"
[[ "$(jq -er '.availableQuantity' <<<"$stock")" == "10" ]] \
  || fail "stock after addition is not 10"

successful_order_payload="$(jq -cn --arg id "$product_id" '{items:[{productId:$id,quantity:3}]}')"
authenticated_request "$orders_token" 202 \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: demo-success-$run_id" \
  --request POST --data "$successful_order_payload" "$ORDERS_URL/orders"
successful_order="$HTTP_BODY"
successful_order_id="$(jq -er '.id' <<<"$successful_order")" || fail "successful order response has no id"
poll_order "$orders_token" "$successful_order_id" CONFIRMED >/dev/null
printf 'Confirmed order %s\n' "$successful_order_id"

excessive_order_payload="$(jq -cn --arg id "$product_id" '{items:[{productId:$id,quantity:8}]}')"
authenticated_request "$orders_token" 202 \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: demo-rejected-$run_id" \
  --request POST --data "$excessive_order_payload" "$ORDERS_URL/orders"
excessive_order="$HTTP_BODY"
excessive_order_id="$(jq -er '.id' <<<"$excessive_order")" || fail "excessive order response has no id"
poll_order "$orders_token" "$excessive_order_id" REJECTED INSUFFICIENT_STOCK >/dev/null
printf 'Rejected excessive order %s with INSUFFICIENT_STOCK\n' "$excessive_order_id"

authenticated_request "$inventory_token" 200 "$INVENTORY_URL/products/$product_id"
product_after="$HTTP_BODY"
remaining="$(jq -er '.availableQuantity' <<<"$product_after")" || fail "product response has no quantity"
[[ "$remaining" == "7" ]] || fail "remaining stock is $remaining, expected 7"
printf 'Verified product %s has exactly 7 units remaining\n' "$product_id"

authenticated_request "$monitoring_token" 200 "$ORDERS_MANAGEMENT_URL/actuator/prometheus"
orders_metrics="$HTTP_BODY"
authenticated_request "$monitoring_token" 200 "$INVENTORY_MANAGEMENT_URL/actuator/prometheus"
inventory_metrics="$HTTP_BODY"
grep -q '^reservation_outbox_pending' <<<"$orders_metrics" \
  || fail "Orders metrics do not contain reservation_outbox_pending"
grep -q '^reservation_outcomes_total' <<<"$inventory_metrics" \
  || fail "Inventory metrics do not contain reservation_outcomes_total"
printf 'Verified authenticated Prometheus scrapes for both services\n'
printf 'Demo completed successfully\n'
