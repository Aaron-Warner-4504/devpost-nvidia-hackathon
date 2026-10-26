#!/usr/bin/env bash
# Run on YOUR LAPTOP from anywhere inside the repo. Ships `git archive HEAD` (committed files only: no .git,
# no untracked files, no secrets) to the VM and extracts it into /opt/sentinel/app. It never touches
# /opt/sentinel/.env.prod and never prints env contents. It asks for confirmation before doing anything.
#
#   SENTINEL_VM_HOST=203.0.113.10 SENTINEL_VM_USER=ubuntu SENTINEL_SSH_KEY=~/.ssh/id_ed25519 \
#     bash deploy/shared-host/push-to-vm.sh
#   or:  bash deploy/shared-host/push-to-vm.sh user@host [path/to/ssh-key]
#
# Prerequisite on the VM (once):  sudo mkdir -p /opt/sentinel && sudo chown "$USER" /opt/sentinel
#
# The env file is copied SEPARATELY and on purpose (run it yourself; see README step 2):
#   scp -i "$SENTINEL_SSH_KEY" ./.env.prod user@host:/opt/sentinel/.env.prod \
#     && ssh -i "$SENTINEL_SSH_KEY" user@host 'chmod 600 /opt/sentinel/.env.prod'
set -euo pipefail

target="${1:-}"
key="${2:-${SENTINEL_SSH_KEY:-}}"
if [ -z "$target" ]; then
  host="${SENTINEL_VM_HOST:-}"; user="${SENTINEL_VM_USER:-}"
  [ -n "$host" ] && [ -n "$user" ] || { echo "usage: $0 user@host [ssh-key]   (or set SENTINEL_VM_HOST and SENTINEL_VM_USER)" >&2; exit 2; }
  target="${user}@${host}"
fi
case "$target" in *[!A-Za-z0-9@._:-]*) echo "refusing odd characters in target: $target" >&2; exit 2 ;; esac

ssh_opts=(-o BatchMode=no -o StrictHostKeyChecking=ask)
[ -n "$key" ] && ssh_opts+=(-i "$key")

repo="$(git rev-parse --show-toplevel)"
commit="$(git -C "$repo" rev-parse --short HEAD)"
dirty=""
git -C "$repo" diff --quiet HEAD -- 2>/dev/null || dirty=" (WARNING: uncommitted changes are NOT included)"

cat <<EOM
About to:
  1. git archive HEAD (${commit})${dirty}
  2. ssh ${target} and extract into /opt/sentinel/app  (replaces the CONTENTS of that directory only)
Not touched: /opt/sentinel/.env.prod, nginx, other containers, anything outside /opt/sentinel/app.
EOM
printf 'Type "yes" to continue: '
read -r answer
[ "$answer" = "yes" ] || { echo "aborted."; exit 1; }

# Extract into a temp dir next to the target, then swap, so a failed transfer leaves the old version intact.
git -C "$repo" archive --format=tar HEAD | ssh "${ssh_opts[@]}" "$target" '
  set -e
  [ -d /opt/sentinel ] || { echo "/opt/sentinel missing: sudo mkdir -p /opt/sentinel && sudo chown $USER /opt/sentinel" >&2; exit 1; }
  new=/opt/sentinel/.app.new.$$
  rm -rf "$new" && mkdir "$new"
  tar -x -C "$new"
  rm -rf /opt/sentinel/app.prev
  [ -d /opt/sentinel/app ] && mv /opt/sentinel/app /opt/sentinel/app.prev
  mv "$new" /opt/sentinel/app
  echo "extracted to /opt/sentinel/app (previous version kept at /opt/sentinel/app.prev)"
  if [ -f /opt/sentinel/.env.prod ]; then echo ".env.prod present (untouched)"; else echo "NOTE: /opt/sentinel/.env.prod not found yet: copy it (README step 2)"; fi
'
echo "done. Next, on the VM:  cd /opt/sentinel/app && bash deploy/shared-host/install.sh"
