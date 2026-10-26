#!/usr/bin/env bash
# Start the sentinel_pixel AVD. Usage: emulator.sh [headless]   (default: with a window)
# Waits for sys.boot_completed (5 min timeout).
set -euo pipefail
# shellcheck disable=SC1091
source "$(dirname "$0")/env.sh"
args=(-avd sentinel_pixel -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot-save)
[ "${1:-}" = "headless" ] && args+=(-no-window)
log="$(mktemp "${TMPDIR:-/tmp}/sentinel-emulator.XXXXXX.log")"  # unpredictable name (no symlink/clobber on shared /tmp)
nohup emulator "${args[@]}" >"$log" 2>&1 &
echo "emulator pid $! (log: $log)"
timeout 300 adb wait-for-device
for _ in $(seq 1 150); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "booted"; exit 0; }
  sleep 2
done
echo "boot timeout" >&2; exit 1
