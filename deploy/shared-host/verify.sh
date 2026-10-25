#!/usr/bin/env bash
# Post-nginx check, runnable from the VM or your laptop. Calls the public URL, registers a throwaway
# device, then DELETEs it (DELETE /v1/me) so no test data remains. Never prints the token.
#   bash deploy/shared-host/verify.sh [base-url]      default: https://interns.enthral.ai/sentinel
set -uo pipefail
BASE="${1:-${SENTINEL_BASE_URL:-https://interns.enthral.ai/sentinel}}"
BASE="${BASE%/}"
fails=0
ok()  { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; fails=$((fails + 1)); }

# response bodies are captured into variables: no temp files at all
resp="$(curl -sS -w '\n%{http_code}' --max-time 10 "$BASE/health" 2>&1)" || resp="curl-error"
code="${resp##*$'\n'}"; body="${resp%$'\n'*}"
if [ "$code" = 200 ] && printf '%s' "$body" | grep -q '"ok"'; then ok "GET /health -> 200 ok"; else bad "GET $BASE/health -> $code (see README troubleshooting)"; fi

code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/openapi.json" 2>&1)" || code="curl-error"
if [ "$code" = 200 ]; then ok "GET /openapi.json -> 200"; else bad "GET /openapi.json -> $code"; fi

code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/dashboard" 2>&1)" || code="curl-error"
if [ "$code" = 404 ]; then ok "GET /dashboard -> 404 (demo off, as intended)"; else bad "GET /dashboard -> $code (expected 404)"; fi

body="$(curl -sS --max-time 15 -X POST "$BASE/v1/devices/register" 2>/dev/null)" || body=""
token="$(printf '%s' "$body" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("token",""))
except Exception: print("")' 2>/dev/null)"
if [ -n "$token" ]; then
  ok "POST /v1/devices/register -> token issued"
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -X DELETE -H "Authorization: Bearer ${token}" "$BASE/v1/me" 2>&1)" || code="curl-error"
  if [ "$code" = 204 ]; then ok "DELETE /v1/me -> 204 (test device and its data removed)"; else bad "DELETE /v1/me -> $code: test device may remain; it is an empty anonymous user"; fi
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer ${token}" "$BASE/v1/me" 2>&1)" || code="curl-error"
  if [ "$code" = 401 ]; then ok "deleted token is rejected (401)"; else bad "deleted token still returns $code"; fi
else
  bad "POST /v1/devices/register returned no token (HTTP body not shown): check docker logs sentinel-api"
fi
unset token

echo
if [ "$fails" -eq 0 ]; then echo "RESULT: all checks passed for $BASE"; exit 0; fi
echo "RESULT: $fails check(s) failed for $BASE"; exit 1
