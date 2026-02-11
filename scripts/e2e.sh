#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
TENANT_PREFIX="${TENANT_PREFIX:-e2e}"
MANAGE_APP="${MANAGE_APP:-0}"
APP_JAR="${APP_JAR:-target/rate-limit-gateway-0.0.1-SNAPSHOT.jar}"
APP_PORT="${APP_PORT:-8081}"
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_APP_DB_URL="jdbc:h2:file:${SCRIPT_DIR}/data/e2e-verify;WRITE_DELAY=0"
APP_DB_URL="${APP_DB_URL:-$DEFAULT_APP_DB_URL}"

PASS() { echo "PASS: $*"; }
FAIL() { echo "FAIL: $*" >&2; exit 1; }
NOTE() { echo "NOTE: $*"; }

need() { command -v "$1" >/dev/null 2>&1 || FAIL "Missing required command: $1"; }
need curl
need grep
need sed
need awk
need tr
need mktemp
need xargs

APP_PID=""

http_code() {
  local method="$1"; shift
  local url="$1"; shift
  curl -sS -o /dev/null -w "%{http_code}" -X "$method" "$url" "$@"
}

http_all() {
  local method="$1"; shift
  local url="$1"; shift
  local out="$1"; shift
  local status
  status="$(curl -sS -D "${out}.hdr" -o "${out}.body" -w "%{http_code}" -X "$method" "$url" "$@")"
  echo "$status"
}

header_value() {
  local hdrfile="$1"
  local name="$2"
  awk -v n="$name" 'BEGIN{IGNORECASE=1}
    $0 ~ ("^" n ":") {sub("\r",""); sub("^[^:]*:[[:space:]]*",""); print; exit}
  ' "$hdrfile"
}

json_ids() {
  grep -o '"id":[0-9]\+' | sed 's/"id"://'
}

expect_code() {
  local got="$1"
  local expected="$2"
  local msg="$3"
  [[ "$got" == "$expected" ]] || FAIL "$msg (expected $expected got $got)"
}

wait_up() {
  local max="${1:-60}"
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
  [[ "$MANAGE_APP" == "1" ]] || return 0
  [[ -f "$APP_JAR" ]] || FAIL "APP_JAR not found: $APP_JAR"
  NOTE "Starting managed app on port $APP_PORT"
  java -jar "$APP_JAR" \
    --server.port="$APP_PORT" \
    --spring.datasource.url="$APP_DB_URL" \
    >/tmp/rate-limit-gateway-e2e.log 2>&1 &
  APP_PID="$!"
  BASE_URL="http://localhost:$APP_PORT"
  wait_up 90 || {
    tail -n 100 /tmp/rate-limit-gateway-e2e.log || true
    FAIL "Managed app failed to start"
  }
}

stop_app() {
  [[ "$MANAGE_APP" == "1" ]] || return 0
  if [[ -n "${APP_PID:-}" ]]; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
    sleep 3
    APP_PID=""
  fi
}

restart_app() {
  [[ "$MANAGE_APP" == "1" ]] || FAIL "Persistence restart tests require MANAGE_APP=1"
  NOTE "Restarting managed app"
  stop_app
  start_app
}

cleanup_tenant_rules() {
  [[ "${SKIP_RULE_CLEANUP:-0}" == "1" ]] && return 0
  local tenant="$1"
  local list ids id
  list="$(curl -sS "$BASE_URL/admin/limits?tenantId=$tenant" || true)"
  ids="$(printf '%s\n' "$list" | json_ids || true)"
  if [[ -n "$ids" ]]; then
    while IFS= read -r id; do
      [[ -z "$id" ]] && continue
      http_code DELETE "$BASE_URL/admin/limits/$id" >/dev/null || true
    done <<< "$ids"
  fi
}

put_rule() {
  local tenant="$1"
  local key="$2"
  local method="$3"
  local path="$4"
  local limit="$5"
  local window="$6"
  local id="${7:-}"
  local payload code
  if [[ -n "$id" ]]; then
    payload="{\"id\":$id,\"tenantId\":\"$tenant\",\"apiKey\":\"$key\",\"method\":\"$method\",\"path\":\"$path\",\"limit\":$limit,\"windowSeconds\":$window}"
  else
    payload="{\"tenantId\":\"$tenant\",\"apiKey\":\"$key\",\"method\":\"$method\",\"path\":\"$path\",\"limit\":$limit,\"windowSeconds\":$window}"
  fi
  code="$(http_code PUT "$BASE_URL/admin/limits" -H "Content-Type: application/json" -d "$payload")"
  expect_code "$code" "200" "PUT /admin/limits failed for $tenant/$key/$method/$path"
}

get_rules() {
  local tenant="$1"
  curl -sS "$BASE_URL/admin/limits?tenantId=$tenant"
}

get_rules_retry() {
  local tenant="$1"
  local tries="${2:-20}"
  local out=""
  local i
  for ((i=1; i<=tries; i++)); do
    out="$(get_rules "$tenant" 2>/dev/null || true)"
    if [[ -n "$out" ]]; then
      echo "$out"
      return 0
    fi
    sleep 1
  done
  echo "$out"
}

request_protected() {
  local tenant="$1"
  local key="$2"
  local method="$3"
  local path="$4"
  http_code "$method" "$BASE_URL$path" \
    -H "X-Tenant-Id: $tenant" \
    -H "X-Api-Key: $key"
}

assert_allow_then_block() {
  local tenant="$1"
  local key="$2"
  local method="$3"
  local path="$4"
  local allowed="$5"
  local i code
  for ((i=1; i<=allowed; i++)); do
    code="$(request_protected "$tenant" "$key" "$method" "$path")"
    expect_code "$code" "200" "$tenant/$key/$method request $i should pass"
  done
  code="$(request_protected "$tenant" "$key" "$method" "$path")"
  expect_code "$code" "429" "$tenant/$key/$method should be blocked after $allowed"
}

tmpdir="$(mktemp -d)"
trap 'stop_app; rm -rf "$tmpdir"' EXIT

if [[ "$MANAGE_APP" == "1" ]]; then
  BASE_URL="http://localhost:$APP_PORT"
  start_app
else
  wait_up 30 || FAIL "Service not reachable at $BASE_URL"
fi

RUN_ID="$(date +%s)-$RANDOM"
T_A="${TENANT_PREFIX}-a-$RUN_ID"
T_B="${TENANT_PREFIX}-b-$RUN_ID"
K_A="k-a-$RUN_ID"
K_B="k-b-$RUN_ID"
P="/api/data"

echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"

# 1) Header validation + basic JSON for GET/POST
s="$(http_code GET "$BASE_URL$P")"
expect_code "$s" "400" "Missing both headers should fail"
s="$(http_code GET "$BASE_URL$P" -H "X-Tenant-Id: $T_A")"
expect_code "$s" "400" "Missing X-Api-Key should fail"
s="$(http_code GET "$BASE_URL$P" -H "X-Api-Key: $K_A")"
expect_code "$s" "400" "Missing X-Tenant-Id should fail"
s="$(http_code GET "$BASE_URL$P" -H "X-Tenant-Id:   " -H "X-Api-Key: $K_A")"
expect_code "$s" "400" "Blank X-Tenant-Id should fail"
PASS "Header validation checks"

out="$tmpdir/basic_get"
s="$(http_all GET "$BASE_URL$P" "$out" -H "X-Tenant-Id: $T_A" -H "X-Api-Key: $K_A")"
expect_code "$s" "200" "Basic GET should pass"
grep -q '"ok":true' "${out}.body" || FAIL "GET body missing ok=true"
grep -q '"method":"GET"' "${out}.body" || FAIL "GET body missing method=GET"

out="$tmpdir/basic_post"
s="$(http_all POST "$BASE_URL$P" "$out" -H "X-Tenant-Id: $T_A" -H "X-Api-Key: $K_A")"
expect_code "$s" "200" "Basic POST should pass"
grep -q '"ok":true' "${out}.body" || FAIL "POST body missing ok=true"
grep -q '"method":"POST"' "${out}.body" || FAIL "POST body missing method=POST"
PASS "Protected GET/POST response body checks"

# 2) Admin CRUD + permission management
cleanup_tenant_rules "$T_A"
put_rule "$T_A" "$K_A" "GET" "$P" 3 60
put_rule "$T_A" "$K_A" "POST" "$P" 2 60
put_rule "$T_A" "$K_B" "GET" "$P" 4 60
put_rule "$T_B" "$K_A" "GET" "$P" 2 60

rules="$(get_rules "$T_A")"
echo "$rules" | grep -q "\"tenantId\":\"$T_A\"" || FAIL "GET /admin/limits missing tenant rules"
RULE_A_GET_ID="$(echo "$rules" | tr '{' '\n' | grep "\"tenantId\":\"$T_A\"" | grep "\"apiKey\":\"$K_A\"" | grep "\"httpMethod\":\"GET\"" | head -n1 | sed -n 's/.*"id":\([0-9]\+\).*/\1/p')"
[[ -n "$RULE_A_GET_ID" ]] || FAIL "Could not extract rule ID for update/delete test"

put_rule "$T_A" "$K_A" "GET" "$P" 4 60 "$RULE_A_GET_ID"
s="$(http_code DELETE "$BASE_URL/admin/limits/$RULE_A_GET_ID")"
expect_code "$s" "204" "DELETE /admin/limits/{id} should return 204"
put_rule "$T_A" "$K_A" "GET" "$P" 3 60
PASS "Admin CRUD checks"

# 3) Rate limiting by unique parameters
T_GET="${TENANT_PREFIX}-get-$RUN_ID"
T_POST="${TENANT_PREFIX}-post-$RUN_ID"
T_K2="${TENANT_PREFIX}-k2-$RUN_ID"
T_T2="${TENANT_PREFIX}-t2-$RUN_ID"
K_GET="k-get-$RUN_ID"
K_POST="k-post-$RUN_ID"
K_K2A="k-k2a-$RUN_ID"
K_K2B="k-k2b-$RUN_ID"

cleanup_tenant_rules "$T_GET"
cleanup_tenant_rules "$T_POST"
cleanup_tenant_rules "$T_K2"
cleanup_tenant_rules "$T_T2"

put_rule "$T_GET" "$K_GET" "GET" "$P" 3 60
put_rule "$T_POST" "$K_POST" "POST" "$P" 2 60
put_rule "$T_K2" "$K_K2A" "GET" "$P" 4 60
put_rule "$T_T2" "$K_K2B" "GET" "$P" 2 60

assert_allow_then_block "$T_GET" "$K_GET" "GET" "$P" 3
assert_allow_then_block "$T_POST" "$K_POST" "POST" "$P" 2
assert_allow_then_block "$T_K2" "$K_K2A" "GET" "$P" 4
assert_allow_then_block "$T_T2" "$K_K2B" "GET" "$P" 2
PASS "Rate limiting for unique tenant/key/method combos"

# 4) Isolation checks
s="$(request_protected "$T_K2" "$K_K2B" "GET" "$P")"
expect_code "$s" "200" "Tenant/key isolation failed for untouched combo"
PASS "Isolation check for untouched combo"

# 5) Wildcard rule semantics + exact rule priority
T_W="${TENANT_PREFIX}-wild-$RUN_ID"
K_W_EXACT="k-w-exact-$RUN_ID"
K_W_OTHER1="k-w-other1-$RUN_ID"
K_W_OTHER2="k-w-other2-$RUN_ID"
cleanup_tenant_rules "$T_W"
put_rule "$T_W" "*" "*" "*" 1 60
put_rule "$T_W" "$K_W_EXACT" "GET" "$P" 3 60

# wildcard key/method/path should share one bucket across matching requests
s="$(request_protected "$T_W" "$K_W_OTHER1" "POST" "$P")"
expect_code "$s" "200" "Wildcard first request should pass"
s="$(request_protected "$T_W" "$K_W_OTHER2" "GET" "$P")"
expect_code "$s" "429" "Wildcard quota should be shared across different key/method"

# exact rule should override wildcard rule for exact tuple
assert_allow_then_block "$T_W" "$K_W_EXACT" "GET" "$P" 3
PASS "Wildcard and exact-priority semantics"

# 6) Concurrent requests respect limits
T_C="${TENANT_PREFIX}-conc-$RUN_ID"
K_C="k-conc-$RUN_ID"
cleanup_tenant_rules "$T_C"
put_rule "$T_C" "$K_C" "GET" "$P" 5 60

concurrent_codes="$(seq 1 30 | xargs -P 15 -I{} sh -c \
  "curl -sS -o /dev/null -w '%{http_code}\n' -X GET '$BASE_URL$P' -H 'X-Tenant-Id: $T_C' -H 'X-Api-Key: $K_C'")"
allowed_count="$(printf '%s\n' "$concurrent_codes" | grep -c '^200$' || true)"
blocked_count="$(printf '%s\n' "$concurrent_codes" | grep -c '^429$' || true)"
[[ "$allowed_count" == "5" ]] || FAIL "Concurrent allowed count mismatch (expected 5 got $allowed_count)"
[[ "$blocked_count" == "25" ]] || FAIL "Concurrent blocked count mismatch (expected 25 got $blocked_count)"
PASS "Concurrent request limit enforcement"

# 7) 429 response JSON + header correctness
out="$tmpdir/blocked_json"
s="$(http_all GET "$BASE_URL$P" "$out" -H "X-Tenant-Id: $T_GET" -H "X-Api-Key: $K_GET")"
expect_code "$s" "429" "Expected blocked response for JSON/header checks"
retry_after="$(header_value "${out}.hdr" "Retry-After" || true)"
[[ -n "$retry_after" ]] || FAIL "429 missing Retry-After header"
echo "$retry_after" | grep -Eq '^[0-9]+$' || FAIL "Retry-After is not numeric: $retry_after"
grep -q '"errorCode":"RATE_LIMITED"' "${out}.body" || FAIL "429 body missing errorCode"
grep -q '"ruleId":"' "${out}.body" || FAIL "429 body missing ruleId"
grep -q '"retryAfterSeconds":' "${out}.body" || FAIL "429 body missing retryAfterSeconds"
PASS "429 body/header checks"

# 8) Audit checks for rejected requests
audit="$(curl -sS "$BASE_URL/admin/audit?tenantId=$T_GET&limit=200")"
blocked_count="$(echo "$audit" | grep -o '"decision":"BLOCKED"' | wc -l | tr -d ' ')"
[[ "${blocked_count:-0}" -ge 1 ]] || FAIL "Expected at least 1 blocked audit event for tenant $T_GET, got $blocked_count"
echo "$audit" | grep -q "\"tenantId\":\"$T_GET\"" || FAIL "Audit events missing tenantId"
echo "$audit" | grep -q '"clientIp":"' || FAIL "Audit events missing clientIp"
echo "$audit" | grep -q '"ruleId":"' || FAIL "Audit events missing ruleId"
PASS "Audit logging checks"

# 9) Persistence checks (admin rules + counters) through restart
if [[ "$MANAGE_APP" == "1" ]]; then
  T_P="${TENANT_PREFIX}-persist-$RUN_ID"
  K_P="k-persist-$RUN_ID"
  cleanup_tenant_rules "$T_P"
  put_rule "$T_P" "$K_P" "GET" "$P" 1 300
  rules_before="$(get_rules_retry "$T_P" 10)"
  echo "$rules_before" | grep -q "\"tenantId\":\"$T_P\"" || FAIL "Persistence setup rule missing before restart (rules: $rules_before)"
  s="$(request_protected "$T_P" "$K_P" "GET" "$P")"
  expect_code "$s" "200" "Persistence setup request should pass"

  restart_app

  rules_after=""
  for i in $(seq 1 30); do
    rules_after="$(get_rules "$T_P" 2>/dev/null || true)"
    if echo "$rules_after" | grep -q "\"tenantId\":\"$T_P\""; then
      break
    fi
    sleep 1
  done
  echo "$rules_after" | grep -q "\"tenantId\":\"$T_P\"" || FAIL "Admin rule did not persist after restart (rules: $rules_after)"
  s="$(request_protected "$T_P" "$K_P" "GET" "$P")"
  expect_code "$s" "429" "Counter state did not persist after restart"
  PASS "Persistence checks across restart"
else
  NOTE "Skipping restart persistence checks (set MANAGE_APP=1 to enable)."
fi

echo "All assignment E2E checks passed."
