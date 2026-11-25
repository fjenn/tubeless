#!/usr/bin/env bash
# Runtime smoke test of the debug APK on an emulator (smoke.yml, inside reactivecircus/android-emulator-runner):
#   install → launch → Home (not empty) → search → open a video (player view, HD, 0 ad requests) → download →
#   Shorts (a real swipe) → Downloads + offline play → Settings → 0 ad requests,
#   screenshots (screencap), the screen's text (uiautomator dump), and no FATAL EXCEPTION in logcat.
#   scripts/smoke/android.sh <apk> <out dir>
set -uo pipefail
APK=${1:?usage: android.sh <apk> <out dir>}
export OUT=${2:?usage: android.sh <apk> <out dir>}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PKG=dev.tubeless.app
QUERY=${SMOKE_QUERY:-Rick Astley Never Gonna Give You Up}
D=files/tubehaus
mkdir -p "$OUT"
# shellcheck source=scripts/smoke/lib.sh
. "$here/lib.sh"

read_events() { adb shell run-as $PKG cat $D/e2e.jsonl 2>/dev/null | tr -d '\r'; }
send_file() { adb push "$1" /data/local/tmp/parity-cmd.txt >/dev/null && adb shell "run-as $PKG sh -c 'cat /data/local/tmp/parity-cmd.txt > $D/parity-cmd.tmp && mv $D/parity-cmd.tmp $D/parity-cmd.txt'"; }
cmd_pending() { adb shell run-as $PKG ls $D/parity-cmd.txt >/dev/null 2>&1; }
alive() { [ -n "$(adb shell pidof $PKG | tr -d '\r')" ]; }
dismiss_anr() {
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  adb shell uiautomator dump /sdcard/anr.xml >/dev/null 2>&1 || return 0
  local b
  b=$(adb shell cat /sdcard/anr.xml | tr -d '\r' | grep -oE 'text="Wait"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] || return 0
  read -r x1 y1 x2 y2 <<< "$b"
  say "dismissing a 'not responding' dialog (Wait)"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}
shot() { dismiss_anr; adb exec-out screencap -p > "$OUT/screens/$1.png" && say "screenshot $1"; }
# the text on screen (Compose exports its semantics to the accessibility tree); empty when the dump fails
ui_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  adb shell cat /sdcard/ui.xml | tr -d '\r' > "$OUT/ui-$1.xml"
  grep -oE '(text|content-desc)="[^"]+"' "$OUT/ui-$1.xml" | sed -E 's/^[a-z-]+="//; s/"$//' | sort -u | tr '\n' '|' | cut -c1-400
}

adb wait-for-device
# shellcheck disable=SC2016  # runs on the device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# the emulator's System UI is slow to settle on CI and pops "isn't responding" over the app: no such dialogs,
# and a dialog that still shows is answered with Wait (dismiss_anr before each step)
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
sleep 20
adb logcat -c
adb install -r -g "$APK" || { result install FAIL "adb install"; summary android; exit 1; }
result install PASS "$(basename "$APK")"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# the E2E log is on when the file exists; the driver's first line waits for the app
adb shell run-as $PKG mkdir -p $D
adb shell run-as $PKG touch $D/e2e.jsonl
printf 'setup\n' > "$OUT/.setup.txt"; send_file "$OUT/.setup.txt"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
t0=$(date +%s)
sleep 2

# 1 start
if wait_event app_ready 90; then result start PASS "app_ready after $(( $(date +%s) - t0 )) s"
else result start FAIL "no app_ready in 90 s"; shot 01-start; adb logcat -d > "$OUT/logcat.txt"; summary android; exit 1; fi

# 2 Home
judge_home 45; say "home ui: $(ui_text home)"
sleep 2; shot 01-home

# 3 search
send "search $QUERY"
if wait_screen Search 20 && wait_event search_results 45 '"videos":[1-9]'; then result search PASS "$(last_event search_results)"
else result search FAIL "screen=$(screen_now) $(last_event search_results)"; fi
sleep 3; shot 02-search; ui_text search > /dev/null

# 4 open the first video: the player view
send "play $QUERY"
if wait_screen Watch 45; then result open-video PASS "player view open"
else result open-video FAIL "screen=$(screen_now)"; fi
judge_play 75
sleep 5; shot 03-watch

# 4b download the video that played (720p), in the background while Shorts run
download_start

# 5 Shorts tab, then a real swipe up on the screen
send "pause" "go shorts"
if wait_screen Shorts 20 && wait_event short 45; then
  sleep 4; shot 04-shorts; say "shorts ui: $(ui_text shorts)"
  n=$(count_events short)
  size=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1); read -r w h <<< "${size:-1080 2400}"
  dismiss_anr
  for _ in 1 2; do adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 250; sleep 3; done
  if wait_event short 20 . $((n + 1)); then result shorts PASS "$(count_events short) Shorts shown after swipes, ads: $(count_events short '"ad":true')"
  else result shorts FAIL "swipe didn't move to the next Short ($(count_events short) shown)"; fi
  shot 05-shorts-swiped
else result shorts FAIL "screen=$(screen_now)"; shot 04-shorts; fi

# 5b the download finished: Downloads page, and the saved copy plays
download_finish 300

# 6 Settings
send "go settings"
if wait_screen Settings 20; then result settings PASS "ui: $(ui_text settings)"
else result settings FAIL "screen=$(screen_now)"; fi
sleep 2; shot 09-settings

judge_ads

# 7 Back leaves Settings (the system Back key), the app keeps running
dismiss_anr; adb shell input keyevent KEYCODE_BACK; sleep 3
if alive; then result back PASS "screen after Back: $(screen_now)"; else result back FAIL "app gone after Back"; fi

# crashes
adb logcat -d > "$OUT/logcat.txt"
if grep -A20 'FATAL EXCEPTION' "$OUT/logcat.txt" | grep -q "$PKG\|app.tubehaus"; then
  result crash FAIL "$(grep -A6 'FATAL EXCEPTION' "$OUT/logcat.txt" | head -8 | tr '\n' ' ' | cut -c1-400)"
else result crash PASS "no FATAL EXCEPTION for $PKG in logcat"; fi
read_events > "$OUT/events.jsonl"
summary android
