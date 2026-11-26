#!/usr/bin/env bash
# M3 end-to-end run (PLAN §4 M3), one platform at a time, from this Mac (home IP: GitHub runners get
# bot walls, so this never runs in CI):
#   cold start → sign in → personal Home → play (≤3 s, ≥1080p, no ads) → SponsorBlock skip → 3x →
#   jump ahead → like + Watch later → background → PiP → download → offline play → sign out →
#   signed-out Home (local recommender)
#
#   scripts/e2e-m3.sh desktop [--app /path/Tubeless.app] [--cookies cookies.txt] [--auto]
#   scripts/e2e-m3.sh android|ios|web [--auto]
#
# SKELETON. Each step is checked the best way available today, in this order:
#   auto    a check this script runs itself (homeCheck, engineCheck, process/window probes)
#   events  the app's own E2E event log: when TUBELESS_E2E_LOG=<file> is set, the app appends one JSON
#           object per line, e.g. {"event":"play","videoId":"…","firstFrameMs":2100,"height":1080,"ads":0}.
#           Contract for S2/S3/S5/S6 (event names below in STEPS); until an event exists the step
#           falls back to manual.
#   manual  the script says what to do and asks pass/fail (y/n/s); with --auto it records "skip".
# Results: docs/parity/e2e/<platform>-<date>.md (a table, committed with the M3 report) and the
# event log next to it.
set -uo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
platform=${1:-}; shift || true
case "$platform" in desktop|android|ios|web) ;; *) sed -n '2,13p' "$0"; exit 2 ;; esac
APP="" ; COOKIES="$HOME/youtube-app/eval/cookies.txt" ; AUTO=0
while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP=$2; shift ;;
    --cookies) COOKIES=$2; shift ;;
    --auto) AUTO=1 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done

stamp=$(date +%F-%H%M)
OUT="$ROOT/docs/parity/e2e"; mkdir -p "$OUT"
REPORT="$OUT/$platform-$stamp.md"
# an existing event log (an app session already running with it) is read as is; else a fresh one
export TUBELESS_E2E_LOG="${TUBELESS_E2E_LOG:-}"
if [ -z "${TUBELESS_E2E_LOG:-}" ]; then export TUBELESS_E2E_LOG="$OUT/$platform-$stamp.events.jsonl"; : > "$TUBELESS_E2E_LOG"; fi
# test video: long enough for SponsorBlock segments, chapters and 1080p+ (override with E2E_VIDEO)
VIDEO=${E2E_VIDEO:-dQw4w9WgXcQ}

# step id | event the app logs (or -) | what to do / what passes
STEPS=$(cat <<'S'
cold-start|app_ready|Start the app cold (quit it first). Passes when Home is drawn.
sign-in|signed_in|Sign in to a YouTube account (Settings → Account). Passes when the avatar shows.
home-personal|feed_loaded|Home shows the account's own feed (loggedOut=false, 20+ items).
play|play|Open the test video. First frame ≤3 s, ≥1080p, no ad before or during it.
sponsorblock|sb_skip|Let it reach a sponsor segment. It is skipped automatically.
speed-3x|speed|Set speed 3x. Audio and video keep up.
jump-ahead|jump|Use Jump ahead. Playback moves to the next key moment.
like-wl|action|Like the video and add it to Watch later; both show on youtube.com within 1 min.
background|background|Minimise / lock screen. Audio keeps playing.
pip|pip|Open picture-in-picture. Video plays in the floating window.
download|download_done|Download the video at the highest quality. It finishes.
offline-play|play_offline|Turn the network off (airplane mode / Wi-Fi off). The download plays.
sign-out|signed_out|Sign out. The account is gone from the app.
home-local|feed_loaded_local|Home shows the local recommender's feed (signed out).
S
)

results=()
record() { results+=("| $1 | $2 | $3 | ${4//|//} |"); printf '%-14s %-6s %-7s %s\n' "$1" "$2" "$3" "$4"; }

has_event() { # event → prints the newest matching line
  [ -s "$TUBELESS_E2E_LOG" ] && grep "\"event\":\"$1\"" "$TUBELESS_E2E_LOG" | tail -1
}

check_event() { # event, step → 0 pass, 1 fail, 2 no event
  local line; line=$(has_event "$1") || return 2
  [ -n "$line" ] || return 2
  /usr/bin/python3 -I - "$2" "$line" <<'PY'
import json, sys
step, e = sys.argv[1], json.loads(sys.argv[2])
bad = []
if step == "play":
    if e.get("firstFrameMs", 1e9) > 3000: bad.append(f"first frame {e.get('firstFrameMs')} ms > 3000")
    if e.get("height", 0) < 1080: bad.append(f"height {e.get('height')} < 1080")
    if e.get("ads", 0): bad.append(f"{e['ads']} ad(s)")
if step in ("home-personal",) and (e.get("loggedOut", True) or e.get("items", 0) < 20):
    bad.append(f"loggedOut={e.get('loggedOut')} items={e.get('items')}")
print("; ".join(bad) or json.dumps(e)[:120])
sys.exit(1 if bad else 0)
PY
}

auto_check() { # step → 0 pass, 1 fail, 2 no auto check here
  case "$platform:$1" in
    desktop:cold-start)
      [ -n "$APP" ] || return 2
      osascript -e "quit app \"$(basename "$APP" .app)\"" 2>/dev/null; sleep 2
      local t0; t0=$(date +%s)
      open -n "$APP" || return 1
      for _ in $(seq 1 60); do
        if osascript -e 'tell application "System Events" to get name of every window of (every process whose name is "Tubeless")' 2>/dev/null | grep -q .; then
          echo "window after $(( $(date +%s) - t0 )) s"; return 0
        fi
        sleep 1
      done
      echo "no Tubeless window after 60 s"; return 1 ;;
    desktop:home-personal)
      [ -f "$COOKIES" ] || { echo "no cookies at $COOKIES"; return 2; }
      (cd "$ROOT" && ./gradlew -q :desktop:homeCheck -Pcookies="$COOKIES") > "$OUT/$platform-$stamp.homecheck.txt" 2>&1
      local rc=$?; tail -3 "$OUT/$platform-$stamp.homecheck.txt"; return $rc ;;
    *) return 2 ;;
  esac
}

echo "E2E $platform  (events: $TUBELESS_E2E_LOG)"
[ "$platform" = desktop ] && [ -z "$APP" ] && echo "no --app: start the app yourself with TUBELESS_E2E_LOG=$TUBELESS_E2E_LOG"
while IFS='|' read -r step event what; do
  [ -n "$step" ] || continue
  msg=$(auto_check "$step"); rc=$?
  if [ $rc -ne 2 ]; then record "$step" auto "$([ $rc = 0 ] && echo PASS || echo FAIL)" "$msg"; continue; fi
  # the signed-in steps read only events from a signed-in session (a signed-out Home is not "personal")
  if [ "$step" = home-personal ] && [ -z "$(has_event signed_in)" ]; then rc=2; else
  msg=$(check_event "$event" "$step"); rc=$?; fi
  if [ $rc -ne 2 ]; then record "$step" events "$([ $rc = 0 ] && echo PASS || echo FAIL)" "$msg"; continue; fi
  if [ $AUTO = 1 ]; then record "$step" manual SKIP "$what"; continue; fi
  echo; echo ">> $step: $what"
  [ "$step" = play ] && echo "   (test video: $VIDEO)"
  read -r -p "   pass? [y]es / [n]o / [s]kip, then a note: " ans note < /dev/tty
  case "$ans" in y*) r=PASS ;; n*) r=FAIL ;; *) r=SKIP ;; esac
  record "$step" manual "$r" "${note:-$what}"
done <<< "$STEPS"

pass=0; fail=0; skip=0
for r in "${results[@]}"; do case "$r" in *"| PASS |"*) pass=$((pass+1)) ;; *"| FAIL |"*) fail=$((fail+1)) ;; *) skip=$((skip+1)) ;; esac; done
{
  echo "# E2E $platform, $stamp"
  echo
  echo "Commit $(git -C "$ROOT" rev-parse --short HEAD). $pass pass, $fail fail, $skip skip."
  echo
  echo "| Step | How | Result | Note |"
  echo "|---|---|---|---|"
  printf '%s\n' "${results[@]}"
} > "$REPORT"
echo; echo "$pass pass, $fail fail, $skip skip → $REPORT"
[ $fail = 0 ]
