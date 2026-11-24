#!/usr/bin/env bash
# Runtime smoke test of the packaged desktop app (Windows, Linux under Xvfb, macOS), from smoke.yml:
#   start → Home → search → open a video → playback (first frame, height, no ads) → Shorts swipe →
#   Settings → quit, with a screenshot of each step.
#   scripts/smoke/desktop.sh <app launcher> <out dir>
# Playback blocked by YouTube's bot wall (datacenter IPs) counts as BLOCKED, not FAIL; the UI steps must pass.
set -uo pipefail
EXE=${1:?usage: desktop.sh <app launcher> <out dir>}
export OUT=${2:?usage: desktop.sh <app launcher> <out dir>}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
QUERY=${SMOKE_QUERY:-Rick Astley Never Gonna Give You Up}

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) os=windows; DATA="$(cygpath -u "$APPDATA")/Tubeless" ;;
  Darwin) os=mac; DATA="$HOME/Library/Application Support/Tubeless" ;;
  *) os=linux; DATA="${XDG_CONFIG_HOME:-$HOME/.config}/Tubeless" ;;
esac
mkdir -p "$OUT" "$DATA"
LOG="$OUT/events.jsonl"; : > "$LOG"
# Windows: the app gets a Windows path for the log
TUBELESS_E2E_LOG=$LOG; [ $os = windows ] && TUBELESS_E2E_LOG=$(cygpath -w "$LOG")
export TUBELESS_E2E_LOG
# shellcheck source=scripts/smoke/lib.sh
. "$here/lib.sh"

read_events() { cat "$LOG" 2>/dev/null; }
send_file() { mv -f "$1" "$DATA/parity-cmd.txt"; }
cmd_pending() { [ -f "$DATA/parity-cmd.txt" ]; }
alive() { kill -0 "$PID" 2>/dev/null; }

shot() {
  local f="$OUT/screens/$1.png"
  case $os in
    linux) import -window root "$f" 2>/dev/null ;;
    mac) screencapture -x "$f" ;;
    windows) powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; \
      \$b=[System.Windows.Forms.SystemInformation]::VirtualScreen; \$i=New-Object System.Drawing.Bitmap \$b.Width,\$b.Height; \
      \$g=[System.Drawing.Graphics]::FromImage(\$i); \$g.CopyFromScreen(\$b.Left,\$b.Top,0,0,\$i.Size); \
      \$i.Save('$(cygpath -w "$f")',[System.Drawing.Imaging.ImageFormat]::Png)" ;;
  esac
  if [ -s "$f" ]; then say "screenshot $1"; else say "screenshot $1 failed"; fi
}

# the driver's first lines are waiting when the app starts: English, no welcome page, a fixed window size
printf 'setup\n' > "$DATA/parity-cmd.txt"
say "starting $EXE ($os), data $DATA"
"$EXE" > "$OUT/app-stdout.log" 2>&1 &
PID=$!
t0=$(date +%s)

# 1 start
if wait_event app_ready 120; then result start PASS "app_ready after $(( $(date +%s) - t0 )) s"
else result start FAIL "no app_ready in 120 s"; shot 01-start; tail -50 "$OUT/app-stdout.log"; summary "desktop-$os"; exit 1; fi

# SMOKE_ONLY_START=1: only that it starts and draws (the AppImage beside the .deb), then out
if [ -n "${SMOKE_ONLY_START:-}" ]; then
  sleep 8; shot 01-start
  send "quit"; for _ in $(seq 1 30); do alive || break; sleep 1; done
  if alive; then result quit FAIL "still running 30 s after quit"; kill "$PID" 2>/dev/null; else result quit PASS exited; fi
  summary "desktop-$os-start"; exit $?
fi

# 2 Home: the signed-out Home is YouTube's own (often empty for a fresh visitor: "Try searching to get started")
if wait_screen Home 20 && wait_event feed_loaded 45; then result home PASS "$(last_event feed_loaded)"
else result home FAIL "screen=$(screen_now), no feed_loaded in 45 s"; fi
sleep 3; shot 01-home

# 3 search
send "search $QUERY"
if wait_screen Search 20 && wait_event search_results 45 '"videos":[1-9]'; then result search PASS "$(last_event search_results)"
else result search FAIL "screen=$(screen_now) $(last_event search_results)"; fi
sleep 3; shot 02-search

# 4 open the first video the search found (the driver's `play`, as a click on the first result)
send "play $QUERY"
if wait_screen Watch 45; then result open-video PASS "watch page open"
else result open-video FAIL "screen=$(screen_now)"; fi
judge_play 75
sleep 6; shot 03-watch

# 5 Shorts, then three swipes (↓, as on YouTube's website)
send "pause" "go shorts"
if wait_screen Shorts 20 && wait_event short 45; then
  sleep 4; shot 04-shorts
  n=$(count_events short)
  send "shorts 3"
  if wait_event short 40 . $((n + 2)); then result shorts PASS "$(count_events short) Shorts shown, ads among them: $(count_events short '"ad":true')"
  else result shorts FAIL "only $(count_events short) Short(s) after swiping"; fi
  [ "$(count_events short '"ad":true')" = 0 ] || result shorts-ads FAIL "an ad was shown among Shorts"
  sleep 3; shot 05-shorts-swiped
else result shorts FAIL "screen=$(screen_now), no Short shown"; shot 04-shorts; fi

# 6 Settings
send "go settings"
if wait_screen Settings 20; then result settings PASS "Settings open"
else result settings FAIL "screen=$(screen_now)"; fi
sleep 3; shot 06-settings

# 7 quit the normal way (as closing the window)
send "quit"
for _ in $(seq 1 30); do alive || break; sleep 1; done
if alive; then result quit FAIL "still running 30 s after quit"; kill "$PID" 2>/dev/null
else result quit PASS "exited$( [ "$(count_events quit)" -gt 0 ] && echo ' after the quit event')"; fi

# crash traces in the app's output
if grep -E 'Exception in thread|hs_err_pid|FATAL|UnsatisfiedLinkError' "$OUT/app-stdout.log" >/dev/null; then
  result log INFO "$(grep -m3 -E 'Exception in thread|hs_err_pid|FATAL|UnsatisfiedLinkError' "$OUT/app-stdout.log" | tr '\n' ' ' | cut -c1-300)"
fi
summary "desktop-$os"
