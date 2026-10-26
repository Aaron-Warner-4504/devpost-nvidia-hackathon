#!/usr/bin/env bash
# Run ON THE VM inside /opt/sentinel/app (the repo root). Starts ONLY the `sentinel` compose project from images
# that are already loaded (build-images.sh on your laptop), waits for health, smoke-tests the loopback port and
# prints the nginx steps. It does NOT edit nginx, the firewall, or any other container, deletes nothing and
# uses no sudo.
#
#   bash deploy/shared-host/install.sh --api-only    api only (inspector not started; link checks degrade gracefully)
#   bash deploy/shared-host/install.sh               api + inspector
#   --no-build   (default) never build on the VM; missing images -> prints the build-images.sh command, exits 1
#   --build      build on the VM; only allowed with >= 12 GB free on the docker filesystem
# Env: SENTINEL_TAG (default local), SENTINEL_MIN_FREE_GB (default 3, free space required after images are loaded)
set -euo pipefail

API_ONLY=0; BUILD=0
for arg in "$@"; do
  case "$arg" in
    --api-only) API_ONLY=1 ;;
    --no-build) BUILD=0 ;;
    --build) BUILD=1 ;;
    -h | --help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown flag: $arg (use --api-only, --no-build, --build)" >&2; exit 2 ;;
  esac
done

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

# bind address guard: the api must stay on loopback unless you explicitly opt in (nginx inside Docker needs
# SENTINEL_BIND_ADDR=<docker0 gateway> AND SENTINEL_ALLOW_NON_LOOPBACK=1; either may live in the env file)
envval() { # value of KEY from the environment, else from the env file (never printed unless it is the bind addr)
  if [ -n "${!1:-}" ]; then printf '%s' "${!1}"; else { grep -E "^$1=" "$ENV_FILE" || true; } | tail -n1 | cut -d= -f2- | tr -d "\"' "; fi
}
BIND="$(envval SENTINEL_BIND_ADDR)"; BIND="${BIND:-127.0.0.1}"
ALLOW="$(envval SENTINEL_ALLOW_NON_LOOPBACK)"
case "$BIND" in
  127.* | ::1 | localhost) ;;
  *)
    if [ "$ALLOW" != 1 ]; then
      echo "REFUSING: SENTINEL_BIND_ADDR=${BIND} is not loopback. Binding the api to a non-loopback address exposes it" >&2
      echo "without TLS and lets anyone on that network reach it directly. If your nginx runs inside Docker and you" >&2
      echo "really need this, set BOTH SENTINEL_BIND_ADDR=<docker0 gateway> and SENTINEL_ALLOW_NON_LOOPBACK=1 (README step 5)," >&2
      echo "and never use 0.0.0.0. Otherwise unset SENTINEL_BIND_ADDR." >&2
      exit 1
    fi
    case "$BIND" in 0.0.0.0 | :: ) echo "REFUSING: ${BIND} listens on every interface, even with SENTINEL_ALLOW_NON_LOOPBACK=1" >&2; exit 1 ;; esac
    echo "WARN: api bound to non-loopback ${BIND} (opted in). Make sure the host firewall only allows the nginx container's subnet to port ${PORT}." >&2 ;;
esac
export SENTINEL_BIND_ADDR="$BIND"
TAG="$(envval SENTINEL_TAG)"; TAG="${TAG:-local}"
export SENTINEL_TAG="$TAG"
MIN_FREE_GB="$(envval SENTINEL_MIN_FREE_GB)"; MIN_FREE_GB="${MIN_FREE_GB:-3}"
case "$MIN_FREE_GB" in "" | *[!0-9]*) echo "SENTINEL_MIN_FREE_GB must be a whole number" >&2; exit 1 ;; esac

# docker / compose / port checks (same intent as preflight.sh, read-only)
docker info >/dev/null 2>&1 || { echo "docker daemon not reachable by this user" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose v2 plugin missing" >&2; exit 1; }
if docker ps --format '{{.Names}}' | grep -qx 'sentinel-api'; then
  echo "sentinel-api already running: this is an update, port ${PORT} is ours."
elif command -v ss >/dev/null 2>&1 && [ -n "$(ss -H -ltn "sport = :${PORT}" 2>/dev/null)" ]; then
  echo "port ${PORT} is already in use by something else; set SENTINEL_HOST_PORT to a free port" >&2
  exit 1
fi

# --- images + DISK GUARD (read-only; this script never deletes anything) ---
IMAGES=("sentinel/api:${TAG}")
[ "$API_ONLY" = 1 ] || IMAGES+=("sentinel/inspector:${TAG}")
docker_free_gb() {
  d="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || true)"; d="${d:-/var/lib/docker}"
  [ -d "$d" ] || d="$(dirname "$d")"
  df -Pk "$d" 2>/dev/null | awk 'NR==2 {printf "%d", $4/1048576}'
}
DOCKER_ROOT="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)"
FREE_GB="$(docker_free_gb)"
[ -n "$FREE_GB" ] || { echo "cannot read free disk space of ${DOCKER_ROOT}" >&2; exit 1; }

if [ "$BUILD" = 1 ]; then
  if [ "$FREE_GB" -lt 12 ]; then
    echo "REFUSING --build: ${FREE_GB} GB free on ${DOCKER_ROOT}, a build needs >= 12 GB (cache + downloads)." >&2
    echo "Build on your laptop instead: SENTINEL_VM_HOST=... SENTINEL_VM_USER=... bash deploy/shared-host/build-images.sh" >&2
    exit 1
  fi
else
  missing=()
  for img in "${IMAGES[@]}"; do docker image inspect "$img" >/dev/null 2>&1 || missing+=("$img"); done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "missing images on this host: ${missing[*]}" >&2
    echo "Nothing is built here. On your laptop (repo root) run:" >&2
    echo "  SENTINEL_VM_HOST=<host> SENTINEL_VM_USER=<user> SENTINEL_SSH_KEY=<key> bash deploy/shared-host/build-images.sh$([ "$API_ONLY" = 1 ] && echo ' --api-only')$([ "$TAG" != local ] && echo " --tag ${TAG}")" >&2
    exit 1
  fi
fi
if [ "$FREE_GB" -lt "$MIN_FREE_GB" ]; then
  echo "REFUSING: only ${FREE_GB} GB free on ${DOCKER_ROOT} (need >= ${MIN_FREE_GB} GB, SENTINEL_MIN_FREE_GB)." >&2
  echo "The neighbour app shares this disk. Free space yourself (your own files, logs, apt cache, old /opt/sentinel/app.prev);" >&2
  echo "this script deletes nothing, and do not run docker system prune on a shared host. Check: docker system df, df -h" >&2
  exit 1
fi
echo "disk: ${FREE_GB} GB free on ${DOCKER_ROOT} (min ${MIN_FREE_GB}); images present: ${IMAGES[*]}"

if [ "$API_ONLY" = 1 ]; then
  export SENTINEL_INSPECTOR_URL=""   # inspector not started: backend skips link inspection gracefully
  PROFILE_ARGS=()
else
  PROFILE_ARGS=(--profile inspector)
fi

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" -p "$PROJECT" "${PROFILE_ARGS[@]}" "$@"; }

echo "== validating compose file =="
compose config -q
if [ "$BUILD" = 1 ]; then
  echo "== building and starting project '${PROJECT}' (the neighbour app is not touched) =="
  compose up -d --build
else
  echo "== starting project '${PROJECT}' from loaded images, no build (the neighbour app is not touched) =="
  compose up -d --no-build
fi

echo "== waiting for health (up to 180 s) =="
deadline=$((SECONDS + 180))
while :; do
  api="$(docker inspect -f '{{.State.Health.Status}}' sentinel-api 2>/dev/null || echo missing)"
  if [ "$API_ONLY" = 1 ]; then insp="not-started"; else insp="$(docker inspect -f '{{.State.Health.Status}}' sentinel-inspector 2>/dev/null || echo missing)"; fi
  echo "api=${api} inspector=${insp}"
  [ "$api" = healthy ] && { [ "$API_ONLY" = 1 ] || [ "$insp" = healthy ]; } && break
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "not healthy in time. Inspect with: docker logs --tail 80 sentinel-api" >&2
    exit 1
  fi
  sleep 5
done

echo "== loopback smoke test =="
curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/health"; echo
echo "OK: Sentinel answers on 127.0.0.1:${PORT}."
[ "$API_ONLY" = 1 ] && echo "api-only: link inspection is off. Add the inspector later with: bash deploy/shared-host/install.sh (after loading sentinel/inspector:${TAG})."

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
