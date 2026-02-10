#!/usr/bin/env bash
set -euo pipefail

APP_JAR="${APP_JAR:-target/rate-limit-gateway-0.0.1-SNAPSHOT.jar}"
APP_PORT="${APP_PORT:-18095}"
APP_DB_URL="${APP_DB_URL:-jdbc:h2:file:./data/e2e-persistence}"
BASE_URL="http://localhost:${APP_PORT}"

PASS() { echo "PASS: $*"; }
FAIL() { echo "FAIL: $*" >&2; exit 1; }
NOTE() { echo "NOTE: $*"; }

need() { command -v "$1" >/dev/null 2>&1 || FAIL "Missing required command: $1"; }
need curl
need grep
need date

[[ -f "$APP_JAR" ]] || FAIL "APP_JAR not found: $APP_JAR"

APP_PID=""

http_code() {
  local method="$1"; shift
  local url="$1"; shift
  curl -sS -o /dev/null -w "%{http_code}" -X "$method" "$url" "$@"
}

wait_up() {
  local max="${1:-90}"
  local i code
  for ((i=1; i<=max; i++)); do
    code="$(http_code GET "$BASE_URL/api/data" || true)"
    if [[ "$code" =~ ^[0-9]{3}$ ]] && [[ "$code" != "000" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

start_app() {
  NOTE "Starting app on port ${APP_PORT}"
  java -jar "$APP_JAR" \
    --server.port="${APP_PORT}" \
    --spring.datasource.url="${APP_DB_URL}" \
    >/tmp/rate-limit-gateway-e2e-persistence.log 2>&1 &
  APP_PID="$!"
  wait_up 120 || {
    tail -n 120 /tmp/rate-limit-gateway-e2e-persistence.log || true
    FAIL "App failed to start"
  }
}

stop_app() {
  if [[ -z "${APP_PID:-}" ]]; then
    return 0
  fi

  NOTE "Stopping app (pid=${APP_PID})"
  kill -INT "$APP_PID" >/dev/null 2>&1 || true

  for _ in $(seq 1 20); do
    if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
      APP_PID=""
      sleep 2
      return 0
    fi
    sleep 1
  done

  kill "$APP_PID" >/dev/null 2>&1 || true
  wait "$APP_PID" >/dev/null 2>&1 || true
  APP_PID=""
  sleep 2
}

cleanup_rule() {
  local tenant="$1"
  local ids
  ids="$(curl -sS "$BASE_URL/admin/limits?tenantId=$tenant" | grep -o '"id":[0-9]\+' | sed 's/"id"://g' || true)"
  if [[ -n "$ids" ]]; then
    while IFS= read -r id; do
      [[ -z "$id" ]] && continue
      http_code DELETE "$BASE_URL/admin/limits/$id" >/dev/null || true
    done <<< "$ids"
  fi
}

trap 'stop_app' EXIT

RUN_ID="$(date +%s)-$RANDOM"
TENANT="persist-${RUN_ID}"
API_KEY="key-${RUN_ID}"
PATH_API="/api/data"

echo "APP_JAR=$APP_JAR"
echo "BASE_URL=$BASE_URL"
echo "APP_DB_URL=$APP_DB_URL"
echo "RUN_ID=$RUN_ID"

# Run 1: create rule + consume one allowed request
start_app
cleanup_rule "$TENANT"

payload="{\"tenantId\":\"$TENANT\",\"apiKey\":\"$API_KEY\",\"method\":\"GET\",\"path\":\"$PATH_API\",\"limit\":1,\"windowSeconds\":300}"
code="$(http_code PUT "$BASE_URL/admin/limits" -H "Content-Type: application/json" -d "$payload")"
[[ "$code" == "200" ]] || FAIL "PUT /admin/limits failed: $code"
PASS "Rule created"

rules="$(curl -sS "$BASE_URL/admin/limits?tenantId=$TENANT")"
echo "$rules" | grep -q "\"tenantId\":\"$TENANT\"" || FAIL "Rule not visible before restart"
PASS "Rule visible before restart"

code="$(http_code GET "$BASE_URL$PATH_API" -H "X-Tenant-Id: $TENANT" -H "X-Api-Key: $API_KEY")"
[[ "$code" == "200" ]] || FAIL "First request should be 200, got $code"
PASS "First request allowed"

stop_app

# Run 2: verify admin rule persisted + counter persisted
start_app

persisted_rules="$(curl -sS "$BASE_URL/admin/limits?tenantId=$TENANT")"
echo "$persisted_rules" | grep -q "\"tenantId\":\"$TENANT\"" || FAIL "Admin rule did not persist after restart"
PASS "Admin rule persisted across restart"

code="$(http_code GET "$BASE_URL$PATH_API" -H "X-Tenant-Id: $TENANT" -H "X-Api-Key: $API_KEY")"
[[ "$code" == "429" ]] || FAIL "Counter state did not persist after restart (expected 429 got $code)"
PASS "Counter state persisted across restart"

echo "Persistence checks passed."
