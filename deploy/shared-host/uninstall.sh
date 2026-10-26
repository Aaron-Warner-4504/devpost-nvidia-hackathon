#!/usr/bin/env bash
# Removes ONLY the Sentinel compose project: its containers (sentinel-*) and its networks (sentinel_*).
# The sentinel/api and sentinel/inspector images are removed only with --images (second typed confirmation),
# so a rollback does not force a re-upload. Asks for a typed confirmation. Does not touch nginx (remove the include line yourself,
# README step 7), other containers/networks/images, volumes of other stacks, or files under /opt/sentinel.
# The Supabase database is NOT touched: all Sentinel data stays there (delete it from Supabase if wanted).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
COMPOSE_FILE="deploy/shared-host/docker-compose.yml"
ENV_FILE="${SENTINEL_ENV_FILE:-/opt/sentinel/.env.prod}"
PROJECT="${SENTINEL_PROJECT:-sentinel}"
export SENTINEL_ENV_FILE="$ENV_FILE"

REMOVE_IMAGES=0
for arg in "$@"; do
  case "$arg" in
    --images) REMOVE_IMAGES=1 ;;
    *) echo "unknown flag: $arg (only --images)" >&2; exit 2 ;;
  esac
done
# only repositories sentinel/api and sentinel/inspector, any tag; nothing else
sentinel_images() { docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -E '^sentinel/(api|inspector):' || true; }

echo "This will remove, for compose project '${PROJECT}' only:"
docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" --format '  container {{.Names}} ({{.Status}})'
docker network ls --filter "label=com.docker.compose.project=${PROJECT}" --format '  network   {{.Name}}'
if [ "$REMOVE_IMAGES" = 1 ]; then sentinel_images | sed 's/^/  image     /'; else echo "  (images kept; add --images to remove sentinel/api and sentinel/inspector)"; fi
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
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" -p "$PROJECT" down --remove-orphans
if [ "$REMOVE_IMAGES" = 1 ]; then
  imgs="$(sentinel_images)"
  if [ -z "$imgs" ]; then echo "no sentinel/* images found."; else
    printf '%s\n' "$imgs" | sed 's/^/  will remove image /'
    printf 'Type "delete-images" to remove exactly these images (nothing else): '
    read -r answer2
    if [ "$answer2" = "delete-images" ]; then
      printf '%s\n' "$imgs" | while IFS= read -r img; do docker image rm "$img"; done
    else
      echo "images kept."
    fi
  fi
fi
[ -z "$tmp_env" ] || rm -f "$tmp_env"
echo "removed. No volumes were created by this project; the Supabase database is untouched."
echo "Remember to remove the 'include .../nginx-sentinel.conf;' line from nginx, nginx -t, reload."
