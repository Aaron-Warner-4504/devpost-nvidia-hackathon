#!/usr/bin/env bash
# Run on YOUR LAPTOP (not the VM). Builds the Sentinel images locally for linux/amd64 and streams them straight
# into the VM's Docker with no temp files on either side:
#   docker save ... | gzip -1 | ssh user@host 'gunzip | docker load'
# Nothing is built on the VM (its disk is too tight for build caches). Before sending, it reads the VM's free
# disk once and refuses if it is too small. It never prints env files or secrets and does not touch nginx.
#
#   SENTINEL_VM_HOST=203.0.113.10 SENTINEL_VM_USER=ubuntu SENTINEL_SSH_KEY=~/.ssh/id_ed25519 \
#     bash deploy/shared-host/build-images.sh [--api-only] [--tag TAG] [--no-build]
#   or:  bash deploy/shared-host/build-images.sh [flags] user@host [path/to/ssh-key]
#
# Env: SENTINEL_TAG (default local; install.sh uses the same variable)
#      SENTINEL_MIN_FREE_GB     VM must keep this much free after loading, default 2
#      SENTINEL_FORCE_LOW_DISK  1 = send even if the disk check fails (the neighbour app shares that disk: think twice)
#      SENTINEL_SSH_EXTRA       extra ssh options, whitespace separated, e.g. "-p 2222 -o ProxyJump=bastion"
#      ClearAllForwardings=yes is always set so a LocalForward in your ssh config does not warn or bind ports.
set -euo pipefail

api_only=0; do_build=1; tag="${SENTINEL_TAG:-local}"; pos=()
while [ $# -gt 0 ]; do
  case "$1" in
    --api-only) api_only=1 ;;
    --no-build) do_build=0 ;;   # reuse images already built locally under this tag
    --tag) shift; tag="${1:-}" ;;
    -h | --help) sed -n '2,16p' "$0"; exit 0 ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) pos+=("$1") ;;
  esac
  shift
done
case "$tag" in "" | *[!A-Za-z0-9_.-]*) echo "invalid tag: '$tag'" >&2; exit 2 ;; esac

target="${pos[0]:-}"
key="${pos[1]:-${SENTINEL_SSH_KEY:-}}"
if [ -z "$target" ]; then
  host="${SENTINEL_VM_HOST:-}"; user="${SENTINEL_VM_USER:-}"
  [ -n "$host" ] && [ -n "$user" ] || { echo "usage: $0 [--api-only] [--tag TAG] user@host [ssh-key]   (or set SENTINEL_VM_HOST and SENTINEL_VM_USER)" >&2; exit 2; }
  target="${user}@${host}"
fi
case "$target" in *[!A-Za-z0-9@._:-]*) echo "refusing odd characters in target: $target" >&2; exit 2 ;; esac
min_free_gb="${SENTINEL_MIN_FREE_GB:-2}"
case "$min_free_gb" in "" | *[!0-9]*) echo "SENTINEL_MIN_FREE_GB must be a whole number" >&2; exit 2 ;; esac

ssh_opts=(-o BatchMode=no -o StrictHostKeyChecking=ask -o ClearAllForwardings=yes)
[ -n "$key" ] && ssh_opts+=(-i "$key")
if [ -n "${SENTINEL_SSH_EXTRA:-}" ]; then
  read -r -a extra <<<"$SENTINEL_SSH_EXTRA"
  ssh_opts+=("${extra[@]}")
fi

repo="$(git rev-parse --show-toplevel)"
images=("sentinel/api:${tag}")
[ "$api_only" = 1 ] || images+=("sentinel/inspector:${tag}")

command -v docker >/dev/null 2>&1 || { echo "docker not found on this machine" >&2; exit 1; }

if [ "$do_build" = 1 ]; then
  echo "== building (linux/amd64) on this machine =="
  docker build --platform linux/amd64 --target prod -t "sentinel/api:${tag}" "$repo/backend"
  [ "$api_only" = 1 ] || docker build --platform linux/amd64 -t "sentinel/inspector:${tag}" "$repo/inspector"
fi

echo "== image sizes =="
total=0
for img in "${images[@]}"; do
  docker image inspect "$img" >/dev/null 2>&1 || { echo "image $img not found locally (drop --no-build)" >&2; exit 1; }
  # unpacked size = sum of layer sizes (docker image inspect .Size can be the compressed size with the containerd store)
  bytes="$(docker history --human=false --format '{{.Size}}' "$img" | awk '{s+=$1} END {printf "%d", s}')"
  wire="$(docker image inspect -f '{{.Size}}' "$img")"
  arch="$(docker image inspect -f '{{.Architecture}}' "$img")"
  [ "$arch" = amd64 ] || { echo "$img is $arch, the VM needs amd64: rebuild without --no-build" >&2; exit 1; }
  total=$((total + bytes))
  printf '  %-34s %6d MB unpacked (~%d MB compressed)\n' "$img" $((bytes / 1048576)) $((wire / 1048576))
done
est_mb=$((total / 1048576))
need_kb=$(( (total * 3 / 2) / 1024 + min_free_gb * 1048576 ))

echo "== reading free disk on the VM (one ssh call, read-only) =="
out="$(ssh "${ssh_opts[@]}" "$target" 'd=$(docker info -f "{{.DockerRootDir}}" 2>/dev/null); [ -n "$d" ] || d=/var/lib/docker; echo "$d"; df -Pk "$d" | awk "NR==2 {print \$4}"')" \
  || { echo "ssh to the VM failed" >&2; exit 1; }
free_kb="$(printf '%s\n' "$out" | tail -n1)"
root_dir="$(printf '%s\n' "$out" | tail -n2 | head -n1)"
case "$free_kb" in "" | *[!0-9]*) echo "could not read free disk from the VM (got an unexpected reply)" >&2; exit 1 ;; esac
free_mb=$((free_kb / 1024)); need_mb=$((need_kb / 1024))
echo "  docker root ${root_dir}: ${free_mb} MB free; required: ${need_mb} MB (images ${est_mb} MB x 1.5 + ${min_free_gb} GB reserve)"

if [ "$free_kb" -lt "$need_kb" ]; then
  if [ "${SENTINEL_FORCE_LOW_DISK:-0}" = 1 ]; then
    echo "WARN: not enough free disk, continuing because SENTINEL_FORCE_LOW_DISK=1" >&2
  else
    echo "REFUSING: the VM has ${free_mb} MB free but ${need_mb} MB are needed. The neighbour app shares this disk." >&2
    echo "Free space yourself (your own files, logs, apt cache), try --api-only, or set SENTINEL_FORCE_LOW_DISK=1 to override." >&2
    echo "Do not run docker system prune on the shared host." >&2
    exit 1
  fi
fi

cat <<EOM

Plan:
  what:  ${images[*]}  (compressed with gzip -1 while streaming; no temp files)
  where: ${target}  -> docker load into ${root_dir}
  size:  ~${est_mb} MB on disk after loading
  not touched: other images/containers, nginx, /opt/sentinel files
EOM
printf 'Type "yes" to continue: '
read -r answer
[ "$answer" = "yes" ] || { echo "aborted."; exit 1; }

echo "== streaming images =="
docker save "${images[@]}" | gzip -1 | ssh "${ssh_opts[@]}" "$target" 'gunzip | docker load'

echo "== on the VM =="
ssh "${ssh_opts[@]}" "$target" 'docker images --filter "reference=sentinel/*"; echo; df -h "$(docker info -f "{{.DockerRootDir}}")"'
echo "done. Next: push-to-vm.sh (code), then on the VM: cd /opt/sentinel/app && bash deploy/shared-host/install.sh --api-only"
