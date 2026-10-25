#!/usr/bin/env bash
# Removes ONLY the Sentinel compose project: its containers (sentinel-*), its networks (sentinel_*) and the
# images built for it. Asks for a typed confirmation. Does not touch nginx (remove the include line yourself,
# README step 7), other containers/networks/images, volumes of other stacks, or files under /opt/sentinel.
# The Supabase database is NOT touched: all Sentinel data stays there (delete it from Supabase if wanted).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
COMPOSE_FILE="deploy/shared-host/docker-compose.yml"
ENV_FILE="${SENTINEL_ENV_FILE:-/opt/sentinel/.env.prod}"
PROJECT="${SENTINEL_PROJECT:-sentinel}"
export SENTINEL_ENV_FILE="$ENV_FILE"

echo "This will remove, for compose project '${PROJECT}' only:"
docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" --format '  container {{.Names}} ({{.Status}})'
docker network ls --filter "label=com.docker.compose.project=${PROJECT}" --format '  network   {{.Name}}'
docker image ls --filter "label=com.docker.compose.project=${PROJECT}" --format '  image     {{.Repository}}:{{.Tag}}'
echo "Everything else on this host (the other app, nginx, its containers/networks) is left alone."
printf 'Type the project name (%s) to confirm: ' "$PROJECT"
read -r answer
[ "$answer" = "$PROJECT" ] || { echo "aborted."; exit 1; }

# env file may be absent at this point: give compose a dummy only for interpolation of required vars
tmp_env=""
if [ ! -f "$ENV_FILE" ]; then
  tmp_env="$(mktemp)"   # mode 600, unpredictable name
  trap 'rm -f "$tmp_env"' EXIT
  printf 'DATABASE_URL=x\nFAMILY_CODE_PEPPER=x\nINSPECTOR_SECRET=x\n' >"$tmp_env"
  ENV_FILE="$tmp_env"; export SENTINEL_ENV_FILE="$tmp_env"
fi
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" -p "$PROJECT" down --remove-orphans --rmi local
[ -z "$tmp_env" ] || rm -f "$tmp_env"
echo "removed. No volumes were created by this project; the Supabase database is untouched."
echo "Remember to remove the 'include .../nginx-sentinel.conf;' line from nginx, nginx -t, reload."
