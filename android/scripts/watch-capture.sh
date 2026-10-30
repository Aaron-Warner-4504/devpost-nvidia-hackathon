#!/usr/bin/env bash
# Live, read-only view of Sentinel capture on a phone running the DEBUG build.
#
#   android/scripts/watch-capture.sh            # follow live (Ctrl+C to stop, prints totals)
#   android/scripts/watch-capture.sh --dump     # print what is already in the log buffer and exit
#   ANDROID_SERIAL=<serial> android/scripts/watch-capture.sh
#
# Shows ONLY counters and event types: captured (source, length, number of masked items, is_contact),
# dropped:<reason>, sent (tier), send_failed (status), queue size, notified (tier), follow_up, call_log rows.
# It reads only the app's own tag `SentinelCapture` (debug builds; release builds log nothing) and, as a second
# guard, prints a line only if it is made of a known event word and key=value pairs of plain tokens. Message text,
# titles, senders and numbers are never logged by the app, and anything that does not match is suppressed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
[ -f "$here/env.sh" ] && source "$here/env.sh" >/dev/null 2>&1 || true
command -v adb >/dev/null || { echo "adb not found (source android/scripts/env.sh)"; exit 1; }

mode=(-v time -s SentinelCapture:I)
[ "${1:-}" = "--dump" ] && mode=(-d "${mode[@]}")

declare -A total=()
summary() {
  echo
  echo "---- totals ----"
  for k in "${!total[@]}"; do printf '%-28s %s\n' "$k" "${total[$k]}"; done | sort
}
trap summary EXIT

safe='^(captured|dropped|sent|send_failed|queue|notified|follow_up|call_log)( [a-z_]+=[A-Za-z0-9_.-]+)*$'
while IFS= read -r line; do
  msg="${line#*SentinelCapture*: }"
  msg="${msg%$'\r'}"
  [[ "$msg" =~ $safe ]] || continue
  ts="${line:6:12}"
  event="${msg%% *}"
  key="$event"
  case "$event" in
    dropped)  key="dropped:$(sed -n 's/.*reason=\([^ ]*\).*/\1/p' <<<"$msg")" ;;
    sent)     key="sent:$(sed -n 's/.*tier=\([^ ]*\).*/\1/p' <<<"$msg")" ;;
    notified) key="notified:$(sed -n 's/.*tier=\([^ ]*\).*/\1/p' <<<"$msg")" ;;
    captured) key="captured:$(sed -n 's/.*source=\([^ ]*\).*/\1/p' <<<"$msg")" ;;
  esac
  total[$key]=$(( ${total[$key]:-0} + 1 ))
  printf '%s  %s\n' "$ts" "$msg"
done < <(adb logcat "${mode[@]}")
