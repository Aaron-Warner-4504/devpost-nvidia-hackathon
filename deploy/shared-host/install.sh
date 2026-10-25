#!/usr/bin/env bash
# Run ON THE VM inside /opt/sentinel/app (the repo root). Builds and starts ONLY the `sentinel` compose project,
# waits for health, smoke-tests the loopback port and prints the nginx steps. It does NOT edit nginx, the
# firewall, or any other container. No sudo is used.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
COMPOSE_FILE="deploy/shared-host/docker-compose.yml"
ENV_FILE="${SENTINEL_ENV_FILE:-/opt/sentinel/.env.prod}"
PROJECT="${SENTINEL_PROJECT:-sentinel}"
PORT="${SENTINEL_HOST_PORT:-8100}"
export SENTINEL_ENV_FILE="$ENV_FILE"

[ -f "$COMPOSE_FILE" ] || { echo "run this from the repo root (/opt/sentinel/app)" >&2; exit 1; }
[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE (README step 2)" >&2; exit 1; }

# env file hygiene (values are never printed)
perm="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo '?')"
case "$perm" in 600 | 400) ;; *) echo "WARN: $ENV_FILE permissions are $perm; run: chmod 600 $ENV_FILE" >&2 ;; esac
for v in DATABASE_URL FAMILY_CODE_PEPPER INSPECTOR_SECRET; do
  grep -Eq "^${v}=.+" "$ENV_FILE" || { echo "$ENV_FILE: $v is missing or empty" >&2; exit 1; }
done

# docker / compose / port checks (same intent as preflight.sh, read-only)
docker info >/dev/null 2>&1 || { echo "docker daemon not reachable by this user" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose v2 plugin missing" >&2; exit 1; }
if docker ps --format '{{.Names}}' | grep -qx 'sentinel-api'; then
  echo "sentinel-api already running: this is an update, port ${PORT} is ours."
elif command -v ss >/dev/null 2>&1 && [ -n "$(ss -H -ltn "sport = :${PORT}" 2>/dev/null)" ]; then
  echo "port ${PORT} is already in use by something else; set SENTINEL_HOST_PORT to a free port" >&2
  exit 1
fi

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" -p "$PROJECT" "$@"; }

echo "== validating compose file =="
compose config -q
echo "== building and starting project '${PROJECT}' (the neighbour app is not touched) =="
compose up -d --build

echo "== waiting for health (up to 180 s) =="
deadline=$((SECONDS + 180))
while :; do
  api="$(docker inspect -f '{{.State.Health.Status}}' sentinel-api 2>/dev/null || echo missing)"
  insp="$(docker inspect -f '{{.State.Health.Status}}' sentinel-inspector 2>/dev/null || echo missing)"
  echo "api=${api} inspector=${insp}"
  [ "$api" = healthy ] && [ "$insp" = healthy ] && break
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "not healthy in time. Inspect with: docker logs --tail 80 sentinel-api" >&2
    exit 1
  fi
  sleep 5
done

echo "== loopback smoke test =="
curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/health"; echo
echo "OK: Sentinel answers on 127.0.0.1:${PORT}."

cat <<EOM

NEXT (nginx is NOT edited by this script). Manual, ~2 minutes (README step 5):
  1. Find the nginx file holding the server block for interns.enthral.ai (preflight.sh prints it).
  2. sudo cp -a <that file> <that file>.bak.\$(date +%F-%H%M)
  3. Inside that server { ... } block add ONE line:
       include /opt/sentinel/app/deploy/shared-host/nginx-sentinel.conf;
  4. sudo nginx -t          # only if it says "syntax is ok" and "test is successful":
  5. sudo systemctl reload nginx
  6. bash deploy/shared-host/verify.sh
EOM
