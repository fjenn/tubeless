#!/usr/bin/env bash
# Shared helpers of the runtime smoke tests (desktop.sh, android.sh; .github/workflows/smoke.yml).
# The app is driven with the QA driver (common/src/app/tubehaus/ui/ParityDriver.kt: parity-cmd.txt in its
# data folder) and judged by its E2E event log (TUBELESS_E2E_LOG, one JSON object a line).
# The caller defines: read_events (prints the log), send_file <file> (puts parity-cmd.txt in place),
# cmd_pending (0 while the last parity-cmd.txt is not taken yet), shot <name>, alive (0 while the app runs).

OUT=${OUT:?OUT not set}
mkdir -p "$OUT/screens"
RESULTS="$OUT/results.md"
: > "$RESULTS"
FAILS=0

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }

# result <step> PASS|FAIL|BLOCKED|INFO <note>
result() {
  printf '| %s | %s | %s |\n' "$1" "$2" "${3//|//}" >> "$RESULTS"
  say "== $1: $2  $3"
  [ "$2" = FAIL ] && FAILS=$((FAILS + 1))
  return 0
}

# count of events named $1 (optionally also matching the regex $2)
count_events() {
  read_events | grep "\"event\":\"$1\"" | grep -cE "${2:-.}" || true
}

# last event named $1 (optionally matching regex $2)
last_event() { read_events | grep "\"event\":\"$1\"" | grep -E "${2:-.}" | tail -1; }

# wait_event <event> <seconds> [regex] [min count]: 0 once there, 1 on timeout or when the app died
wait_event() {
  local ev=$1 secs=$2 re=${3:-.} min=${4:-1} i
  for ((i = 0; i < secs; i++)); do
    [ "$(count_events "$ev" "$re")" -ge "$min" ] && return 0
    alive || { say "app is gone while waiting for $ev"; return 1; }
    sleep 1
  done
  return 1
}

# wait_any <seconds> <event>...: prints the first event line found among them
wait_any() {
  local secs=$1 i e line; shift
  for ((i = 0; i < secs; i++)); do
    for e in "$@"; do line=$(last_event "$e"); [ -n "$line" ] && { echo "$line"; return 0; }; done
    alive || return 1
    sleep 1
  done
  return 1
}

# send <driver lines...>: waits until the driver took the previous file, then hands it the new one
send() {
  local i f="$OUT/.cmd.txt"
  for ((i = 0; i < 30; i++)); do cmd_pending || break; sleep 1; done
  printf '%s\n' "$@" > "$f"
  send_file "$f"
  say "driver: $*"
}

# the screen in front, from the newest "screen" event
screen_now() { last_event screen | sed -n 's/.*"name":"\([^"]*\)".*/\1/p'; }

wait_screen() { # <name> <seconds>
  local i
  for ((i = 0; i < $2; i++)); do [ "$(screen_now)" = "$1" ] && return 0; alive || return 1; sleep 1; done
  return 1
}

# play verdict from the event log: PASS / BLOCKED (YouTube's bot wall on a datacenter IP) / FAIL
judge_play() { # <seconds>
  local line
  line=$(wait_any "$1" play play_error player_error) || { result playback FAIL "no play / play_error event in $1 s"; return; }
  case "$line" in
    *'"event":"play"'*)
      local h ads ms
      h=$(sed -n 's/.*"height":\([0-9]*\).*/\1/p' <<< "$line"); ads=$(sed -n 's/.*"ads":\([0-9]*\).*/\1/p' <<< "$line")
      ms=$(sed -n 's/.*"firstFrameMs":\([0-9]*\).*/\1/p' <<< "$line")
      if [ "${h:-0}" -gt 0 ] && [ "${ads:-0}" = 0 ]; then result playback PASS "first frame ${ms} ms, ${h}p, 0 ads ($line)"
      else result playback FAIL "$line"; fi ;;
    *'"botWall":true'*|*"sign in to confirm"*|*"Sign in to confirm"*|*"not a bot"*|*"HTTP 403"*|*" 403"*|*"LOGIN_REQUIRED"*)
      result playback BLOCKED "blocked by YouTube (datacenter IP): $line" ;;
    *) result playback FAIL "$line" ;;
  esac
}

summary() { # <platform>
  {
    echo "### Smoke: $1"
    echo
    echo "| Step | Result | Note |"
    echo "|---|---|---|"
    cat "$RESULTS"
    echo
  } > "$OUT/summary.md"
  cat "$OUT/summary.md"
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$OUT/summary.md" >> "$GITHUB_STEP_SUMMARY"
  say "$1: $FAILS failing step(s)"
  [ "$FAILS" = 0 ]
}
