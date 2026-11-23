#!/usr/bin/env bash
# End-to-end smoke test for a running TaskHub API (local docker-compose or a public deployment).
#
#   BASE_URL=https://taskhub.onrender.com EMAIL=admin@taskhub.local PASSWORD='Admin@12345' \
#     ./scripts/smoke-test.sh
#
# Exits non-zero on the first failed assertion. Requires curl and jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@taskhub.local}"
PASSWORD="${PASSWORD:-Admin@12345}"
STAMP="$(date +%s)"
PROJECT_CODE="SMOKE$((STAMP % 100000))"

command -v jq >/dev/null || { echo "jq is required"; exit 1; }

# Progress goes to stderr so that callers can capture JSON from stdout.
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1" >&2; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1" >&2; }

api() { # api METHOD PATH [BODY] [TOKEN]
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  local args=(-sS -X "$method" -H "Content-Type: application/json" -H "Accept: application/json")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" -w $'\n%{http_code}' "$BASE_URL$path"
}

expect_status() { # expect_status NAME EXPECTED ACTUAL_BODY_AND_STATUS
  local name="$1" expected="$2" raw="$3"
  local status="${raw##*$'\n'}"
  local body="${raw%$'\n'*}"
  if [[ "$status" != "$expected" ]]; then
    printf '%s\n' "$body" | head -c 500 >&2
    fail "$name (expected HTTP $expected, got $status)"
  fi
  pass "$name"
  printf '%s' "$body"
}

step "1. Health & docs"
status=$(curl -sS -o /tmp/smoke-health.json -w '%{http_code}' "$BASE_URL/actuator/health")
[[ "$status" == "200" ]] || fail "health endpoint returned $status"
grep -q '"status":"UP"' /tmp/smoke-health.json && pass "database health is UP"
curl -sS "$BASE_URL/v3/api-docs" | jq -e '.paths["/api/v1/tasks"]' >/dev/null && pass "OpenAPI document exposes /api/v1/tasks"

step "2. Authentication"
expect_status "unauthenticated request is rejected" 401 "$(api GET /api/v1/tasks)" >/dev/null
TOKEN_JSON=$(expect_status "login with seeded credentials" 200 "$(api POST /api/v1/auth/login \
  "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")")
ACCESS=$(jq -r '.accessToken' <<<"$TOKEN_JSON")
REFRESH=$(jq -r '.refreshToken' <<<"$TOKEN_JSON")
[[ -n "$ACCESS" && "$ACCESS" != "null" ]] || fail "login did not return an access token"
expect_status "GET /auth/me" 200 "$(api GET /api/v1/auth/me "" "$ACCESS")" >/dev/null
expect_status "bad password is rejected" 401 "$(api POST /api/v1/auth/login \
  "{\"email\":\"$EMAIL\",\"password\":\"definitely-wrong\"}")" >/dev/null

step "3. Create project"
PROJECT_JSON=$(expect_status "create project" 201 "$(api POST /api/v1/projects \
  "{\"code\":\"$PROJECT_CODE\",\"name\":\"Smoke Project $STAMP\",\"description\":\"Created by smoke-test.sh\",\"status\":\"ACTIVE\"}" "$ACCESS")")
PROJECT_ID=$(jq -r '.id' <<<"$PROJECT_JSON")
[[ "$PROJECT_ID" != "null" ]] || fail "project id missing"
pass "project id $PROJECT_ID"

step "4. Create tasks"
for i in 1 2 3; do
  expect_status "create task $i" 201 "$(api POST /api/v1/tasks \
    "{\"projectId\":\"$PROJECT_ID\",\"title\":\"Smoke task $i\",\"priority\":\"HIGH\",\"status\":\"TODO\"}" "$ACCESS")" >/dev/null
done
TASK_JSON=$(expect_status "create a task with a due date" 201 "$(api POST /api/v1/tasks \
  "{\"projectId\":\"$PROJECT_ID\",\"title\":\"Smoke overdue task\",\"priority\":\"CRITICAL\",\"dueDate\":\"2020-01-01\"}" "$ACCESS")")
TASK_ID=$(jq -r '.id' <<<"$TASK_JSON")

step "5. Read, paginate, search, filter, sort"
LIST=$(expect_status "list tasks (page 0, size 2)" 200 "$(api GET "/api/v1/tasks?projectId=$PROJECT_ID&page=0&size=2" "" "$ACCESS")")
[[ "$(jq -r '.content | length' <<<"$LIST")" == "2" ]] && pass "page size honoured"
[[ "$(jq -r '.totalElements' <<<"$LIST")" == "4" ]] && pass "totalElements = 4"
[[ "$(jq -r '.totalPages' <<<"$LIST")" == "2" ]] && pass "totalPages = 2"
SEARCH=$(expect_status "search 'overdue'" 200 "$(api GET "/api/v1/tasks?search=overdue" "" "$ACCESS")")
[[ "$(jq -r '.totalElements' <<<"$SEARCH")" == "1" ]] && pass "search returns exactly one task"
FILTER=$(expect_status "filter priority=CRITICAL" 200 "$(api GET "/api/v1/tasks?priority=CRITICAL" "" "$ACCESS")")
[[ "$(jq -r '.totalElements' <<<"$FILTER")" -ge 1 ]] && pass "priority filter works"
SORTED=$(expect_status "sort by title asc" 200 "$(api GET "/api/v1/tasks?sort=title&direction=asc&size=100" "" "$ACCESS")")
FIRST=$(jq -r '.content[0].title' <<<"$SORTED")
[[ "$FIRST" == "Smoke overdue task" ]] && pass "ascending title sort ($FIRST)"
OVERDUE=$(expect_status "overdue filter" 200 "$(api GET "/api/v1/tasks?overdue=true" "" "$ACCESS")")
[[ "$(jq -r '.totalElements' <<<"$OVERDUE")" -ge 1 ]] && pass "overdue filter finds the late task"
expect_status "invalid sort field rejected" 400 "$(api GET "/api/v1/tasks?sort=secret_column" "" "$ACCESS")" >/dev/null

step "6. Update & validation"
UPDATED=$(expect_status "patch task status" 200 "$(api PATCH "/api/v1/tasks/$TASK_ID" '{"status":"DONE"}' "$ACCESS")")
[[ "$(jq -r '.status' <<<"$UPDATED")" == "DONE" ]] && pass "status updated to DONE"
expect_status "blank title rejected with field errors" 400 "$(api POST /api/v1/tasks \
  "{\"projectId\":\"$PROJECT_ID\",\"title\":\" \"}" "$ACCESS")" >/dev/null
expect_status "unknown task returns 404" 404 "$(api GET "/api/v1/tasks/00000000-0000-0000-0000-000000000000" "" "$ACCESS")" >/dev/null

step "7. Delete"
expect_status "delete task" 204 "$(api DELETE "/api/v1/tasks/$TASK_ID" "" "$ACCESS")" >/dev/null
expect_status "deleted task is gone" 404 "$(api GET "/api/v1/tasks/$TASK_ID" "" "$ACCESS")" >/dev/null
expect_status "delete project (cascades)" 204 "$(api DELETE "/api/v1/projects/$PROJECT_ID" "" "$ACCESS")" >/dev/null

step "8. Token rotation"
ROTATED=$(expect_status "refresh token" 200 "$(api POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH\"}")")
NEW_ACCESS=$(jq -r '.accessToken' <<<"$ROTATED")
expect_status "rotated access token works" 200 "$(api GET /api/v1/auth/me "" "$NEW_ACCESS")" >/dev/null
expect_status "replayed refresh token is rejected" 401 "$(api POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH\"}")" >/dev/null

printf '\n\033[32mAll smoke tests passed against %s\033[0m\n' "$BASE_URL"
