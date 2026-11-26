#!/usr/bin/env bash
# Shared helpers of the runtime smoke tests (desktop.sh, android.sh; .github/workflows/smoke.yml).
# The app is driven with the QA driver (common/src/app/tubeless/ui/ParityDriver.kt: parity-cmd.txt in its
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
      PLAYED_ID=$(sed -n 's/.*"videoId":"\([^"]*\)".*/\1/p' <<< "$line")
      if [ "${h:-0}" -ge 720 ] && [ "${ads:-0}" = 0 ]; then result playback PASS "first frame ${ms} ms, ${h}p (HD), 0 ad requests ($line)"
      elif [ "${h:-0}" -gt 0 ] && [ "${ads:-0}" = 0 ]; then result playback FAIL "started below HD: ${h}p ($line)"
      else result playback FAIL "$line"; fi ;;
    *'"botWall":true'*|*"sign in to confirm"*|*"Sign in to confirm"*|*"not a bot"*|*"HTTP 403"*|*" 403"*|*"LOGIN_REQUIRED"*)
      result playback BLOCKED "blocked by YouTube (datacenter IP): $line" ;;
    *) result playback FAIL "$line" ;;
  esac
}

# Home signed out: never empty (YouTube's own Home, or the popular fallback when that is empty)
judge_home() { # <seconds>
  local line items
  if ! wait_screen Home 20 || ! wait_event feed_loaded "$1"; then result home FAIL "screen=$(screen_now), no feed_loaded in $1 s"; return; fi
  line=$(last_event feed_loaded); items=$(sed -n 's/.*"items":\([0-9]*\).*/\1/p' <<< "$line")
  if [ "${items:-0}" -gt 0 ]; then
    result home PASS "${items} items$( [ "$(count_events feed_popular)" -gt 0 ] && echo ' (popular fallback)') $line"
  else result home FAIL "Home is empty signed out: $line"; fi
}

# a download of the video that played ($PLAYED_ID), at 720p, while the rest of the test goes on
download_start() {
  if [ -z "${PLAYED_ID:-}" ]; then PLAYED_ID=$(last_event play | sed -n 's/.*"videoId":"\([^"]*\)".*/\1/p'); fi
  if [ -z "${PLAYED_ID:-}" ]; then
    if grep -q '^| playback | BLOCKED' "$RESULTS"; then result download BLOCKED "playback was blocked, nothing to download"
    else result download FAIL "no video id to download (nothing played)"; fi
    return 1
  fi
  send "download $PLAYED_ID@720"
}

# waits for it, shows Downloads and plays the saved copy: download PASS / BLOCKED / FAIL, offline-play PASS / FAIL
download_finish() { # <seconds>
  [ -n "${PLAYED_ID:-}" ] || return 0
  local line
  line=$(wait_any "$1" download_done download_failed) || { result download FAIL "no download_done in $1 s ($(last_event download_queued))"; return; }
  case "$line" in
    *'"event":"download_done"'*)
      local bytes h
      bytes=$(sed -n 's/.*"bytes":\([0-9]*\).*/\1/p' <<< "$line"); h=$(sed -n 's/.*"height":\([0-9]*\).*/\1/p' <<< "$line")
      if [ "${bytes:-0}" -gt 100000 ]; then result download PASS "${h}p, $((bytes / 1048576)) MB ($line)"
      else result download FAIL "finished but tiny: $line"; fi ;;
    *"not a bot"*|*"Sign in to confirm"*|*"403"*|*"LOGIN_REQUIRED"*) result download BLOCKED "blocked by YouTube (datacenter IP): $line"; return ;;
    *) result download FAIL "$line"; return ;;
  esac
  send "go downloads"
  if wait_screen Downloads 20; then sleep 3; shot 07-downloads; else result downloads-page FAIL "screen=$(screen_now)"; fi
  local n; n=$(count_events play_offline)
  send "offline $PLAYED_ID"
  if wait_event play_offline 60 . $((n + 1)); then result offline-play PASS "$(last_event play_offline)"
  else result offline-play FAIL "the saved copy didn't play (screen=$(screen_now)) $(last_event player_error)"; fi
  sleep 4; shot 08-offline-watch
  send "pause"
}

# no ad request left the app all run (the HTTP client refuses and counts them), none shown among Shorts
judge_ads() {
  local n line req
  n=$(count_events report)
  send "report"
  if ! wait_event report 20 . $((n + 1)); then result ads FAIL "no report event"; return; fi
  line=$(last_event report); req=$(sed -n 's/.*"adRequests":\([0-9]*\).*/\1/p' <<< "$line")
  if [ "${req:-x}" = 0 ] && [ "$(count_events short '"ad":true')" = 0 ]; then
    result ads PASS "0 ad requests, 0 ads among Shorts; ads left out of YouTube's answers: $(sed -n 's/.*"adsLeftOut":\([0-9]*\).*/\1/p' <<< "$line")"
  else result ads FAIL "ad requests: ${req:-?}, ads among Shorts: $(count_events short '"ad":true') ($line)"; fi
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
