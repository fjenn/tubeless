#!/usr/bin/env bash
# The Linux checks a new user's first minutes hit (LINUX-FIXES, from a real Ubuntu 24.04 test of 1.0.0), under a
# window manager that draws frames (openbox), as on a real desktop:
#   size    the window keeps its size over 3 starts (it shrank by the frame at each start)
#   signin  "Sign in" opens Google's sign-in page in the sign-in window (the AppImage carries WebKitGTK)
#   cancel  Cancel in the "Sign in to YouTube" dialog closes that window, and the log says cancelled, not failed
#   scripts/smoke/linux-extra.sh <app launcher> <out dir>   (inside an X session: xvfb-run -a ...)
set -uo pipefail
EXE=${1:?usage: linux-extra.sh <app launcher> <out dir>}
export OUT=${2:?usage: linux-extra.sh <app launcher> <out dir>}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DATA="${XDG_CONFIG_HOME:-$HOME/.config}/Tubeless"
mkdir -p "$OUT" "$DATA"
LOG="$OUT/events.jsonl"; : > "$LOG"
export TUBELESS_E2E_LOG=$LOG
# shellcheck source=scripts/smoke/lib.sh
. "$here/lib.sh"
read_events() { cat "$LOG" 2>/dev/null; }
send_file() { mv -f "$1" "$DATA/parity-cmd.txt"; }
cmd_pending() { [ -f "$DATA/parity-cmd.txt" ]; }
alive() { kill -0 "$PID" 2>/dev/null; }
shot() { import -window root "$OUT/screens/$1.png" 2>/dev/null; }
helper_alive() { pgrep -f 'signin-helper( |$)' >/dev/null; }

command -v openbox >/dev/null && { openbox >/dev/null 2>&1 & WM=$!; sleep 1; }

start() { # <name>: the app, until app_ready
  "$EXE" > "$OUT/app-$1.log" 2>&1 &
  PID=$!
  wait_event app_ready 120 . "$2"
}
quit() {
  send "quit"; for _ in $(seq 1 30); do alive || break; sleep 1; done
  alive && { kill "$PID" 2>/dev/null; sleep 2; }
}

# 1 the window's size over 3 starts (the first one with the driver's fixed size, then as the app saved it)
printf 'setup\n' > "$DATA/parity-cmd.txt"
sizes=()
for n in 1 2 3; do
  if ! start "start$n" "$n"; then result "size start $n" FAIL "no app_ready"; break; fi
  sleep 6
  send "report-window"
  wait_event window 15 . "$n" || true
  sizes+=("$(last_event window | sed -n 's/.*inside=\([0-9x]*\).*/\1/p')")
  say "start $n: $(last_event window)"
  shot "size-$n"
  quit
done
if [ ${#sizes[@]} = 3 ] && [ -n "${sizes[1]}" ] && [ "${sizes[1]}" = "${sizes[2]}" ] && [ "${sizes[0]}" = "${sizes[1]}" ]; then
  result size PASS "inside ${sizes[*]}"
else result size FAIL "inside ${sizes[*]:-none}"; fi
# the AppImage's runtime: no "Error: No suitable fusermount" line before the app starts
if grep -h 'fusermount' "$OUT"/app-start*.log 2>/dev/null | grep -q 'Error'; then result fusermount FAIL "$(grep -h fusermount "$OUT"/app-start*.log | head -1)"
else result fusermount PASS "no fusermount error printed"; fi

# 2 sign-in: Google's page in the sign-in window
slog="$DATA/logs/signin.log"; rm -f "$slog"
if start signin 4; then
  send "signin"
  ok=""
  for _ in $(seq 1 60); do grep -q 'helper: page .*accounts\.google\.com' "$slog" 2>/dev/null && { ok=1; break; }; sleep 1; done
  sleep 4; shot signin-window
  if [ -n "$ok" ]; then result signin PASS "$(grep -m1 'helper: page .*accounts\.google\.com' "$slog" | cut -c1-140)"
  else result signin FAIL "$(tail -5 "$slog" 2>/dev/null | tr '\n' ' ' | cut -c1-300)"; fi
  # 3 Cancel closes Google's window and the dialog; the log says cancelled
  send "signin-cancel"
  gone=""
  for _ in $(seq 1 10); do helper_alive || { gone=1; break; }; sleep 1; done
  sleep 1; shot signin-cancelled
  if [ -n "$gone" ] && grep -q 'sign-in: cancelled' "$slog" && ! grep -q 'sign-in: failed' "$slog"; then result cancel PASS "window closed, logged as cancelled"
  else result cancel FAIL "helper still running: $([ -z "$gone" ] && echo yes || echo no); $(grep 'sign-in:' "$slog" | tail -2 | tr '\n' ' ')"; fi
  quit
else result signin FAIL "no app_ready"; fi
cp "$slog" "$OUT/" 2>/dev/null
[ -n "${WM:-}" ] && kill "$WM" 2>/dev/null
summary linux-extra
