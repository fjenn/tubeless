#!/usr/bin/env bash
# The home-IP half of the nightly stream-health.yml: runs :desktop:engineCheck (every engine call against
# live YouTube) from this machine, where YouTube answers the way it answers users. GitHub runners are
# datacenter addresses that get "Sign in to confirm you're not a bot"; a home connection doesn't.
#
#   scripts/stream-health-local.sh             # run once; notify only when something broke
#   scripts/stream-health-local.sh --here      # test this checkout instead of a clean tree of trunk
#   scripts/stream-health-local.sh --install   # daily at 09:00 (launchd on macOS, systemd timer on Linux)
#   scripts/stream-health-local.sh --uninstall
#
# By default it tests TUBELESS_HEALTH_REF (default: origin/trunk if there is a remote, else trunk) in a
# worktree of its own, never the checkout this script sits in: that one may be half-edited, and a red
# from it says nothing about YouTube. The tree rebuilds incrementally, only when trunk moves.
#
# State (macOS ~/Library/Logs/tubeless, Linux ${XDG_STATE_HOME:-~/.local/state}/tubeless):
#   stream-health.log            full output of the last run
#   stream-health-<when>.log     every run that was not green, kept
#   stream-health.history        one line per run: when, commit, verdict, failed calls
#
# Verdicts: ok | red (YouTube changed something: fix the code or run :desktop:engineRepair) |
# flagged (YouTube is bot-challenging this IP: not a code break, it lifts on its own) |
# build (the tree did not compile: not about YouTube) | skipped (YouTube unreachable: no answer today).
set -uo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
REPO=$(cd "$(dirname "$SELF")/.." && pwd)
if [[ "$(uname)" == Darwin ]]; then
  STATE="$HOME/Library/Logs/tubeless"; TREE="$HOME/Library/Caches/tubeless/stream-health-tree"
else
  STATE="${XDG_STATE_HOME:-$HOME/.local/state}/tubeless"; TREE="${XDG_CACHE_HOME:-$HOME/.cache}/tubeless/stream-health-tree"
fi
LOG="$STATE/stream-health.log"
HISTORY="$STATE/stream-health.history"
LABEL=dev.tubeless.stream-health

notify() { # title, text
  if [[ "$(uname)" == Darwin ]]; then
    osascript -e "display notification \"${2//\"/\'}\" with title \"${1//\"/\'}\"" 2>/dev/null || true
  else
    notify-send -u critical "$1" "$2" 2>/dev/null || true
  fi
}

case "${1:-}" in
  --install)
    if [[ "$(uname)" == Darwin ]]; then
      plist="$HOME/Library/LaunchAgents/$LABEL.plist"
      mkdir -p "$(dirname "$plist")" "$STATE"
      cat > "$plist" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$SELF</string></array>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>9</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key><string>$STATE/launchd.out</string>
  <key>StandardErrorPath</key><string>$STATE/launchd.out</string>
</dict></plist>
PL
      launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
      launchctl bootstrap "gui/$(id -u)" "$plist" && echo "installed: $plist (daily 09:00)"
    else
      units="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"; mkdir -p "$units"
      printf '[Unit]\nDescription=Tubeless stream health\nAfter=network-online.target\n\n[Service]\nType=oneshot\nExecStart=%s\n' "$SELF" > "$units/tubeless-stream-health.service"
      printf '[Unit]\nDescription=Daily Tubeless stream health\n\n[Timer]\nOnCalendar=*-*-* 09:00\nPersistent=true\nRandomizedDelaySec=15m\n\n[Install]\nWantedBy=timers.target\n' > "$units/tubeless-stream-health.timer"
      systemctl --user daemon-reload && systemctl --user enable --now tubeless-stream-health.timer
    fi
    exit 0 ;;
  --uninstall)
    if [[ "$(uname)" == Darwin ]]; then
      launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true; rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
    else
      systemctl --user disable --now tubeless-stream-health.timer 2>/dev/null || true
    fi
    echo uninstalled; exit 0 ;;
esac

mkdir -p "$STATE"
# a captive portal or no network would look like a YouTube break: that's "no answer today", not red
if ! curl -sf -o /dev/null --max-time 20 https://www.youtube.com/generate_204; then
  echo "youtube unreachable, skipping" >&2
  echo "$(date -u +%FT%TZ) - skipped: youtube unreachable" >> "$HISTORY"
  exit 0
fi

# a JDK 21 (Homebrew's is fine for engineCheck; packaging is what needs Temurin/JBR)
if [[ -z "${JAVA_HOME:-}" ]]; then
  for j in "$(/usr/libexec/java_home -v 21 2>/dev/null)" /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/temurin-21-jdk-amd64; do
    [[ -n "$j" && -x "$j/bin/java" ]] && { export JAVA_HOME="$j"; break; }
  done
fi

if [[ "${1:-}" == "--here" ]]; then
  cd "$REPO" || exit 1
  sha="$(git rev-parse --short HEAD)$(git diff --quiet || echo '+dirty')"
else
  ref="${TUBELESS_HEALTH_REF:-}"
  if [[ -z "$ref" ]]; then
    if git -C "$REPO" remote get-url origin >/dev/null 2>&1; then
      git -C "$REPO" fetch -q origin trunk || echo "fetch failed, testing the last fetched origin/trunk" >&2
      ref=origin/trunk
    else
      ref=trunk
    fi
  fi
  if [[ ! -e "$TREE/.git" ]]; then
    mkdir -p "$(dirname "$TREE")"
    git -C "$REPO" worktree prune
    git -C "$REPO" worktree add -q --detach "$TREE" "$ref" || exit 1
  fi
  git -C "$TREE" checkout -qf --detach "$ref" || exit 1
  cd "$TREE" || exit 1
  sha="$(git rev-parse --short HEAD)"
fi

{ date -u +%FT%TZ; echo "commit $sha"; } > "$LOG"
REPORT="$STATE/failed.txt"; rm -f "$REPORT"
TH_REPORT="$REPORT" ./gradlew -q :desktop:engineCheck >> "$LOG" 2>&1
status=$?

if [[ $status -eq 0 ]] && grep -q '^all good' "$LOG"; then verdict=ok
elif grep -qE "Compilation error|e: file:|BUILD FAILED" "$LOG" && ! grep -qE '^(ok|FAIL) ' "$LOG"; then verdict=build
elif grep -q 'not a bot' "$LOG"; then verdict=flagged
else verdict=red; [[ $status -eq 0 ]] && status=1
fi
fails=$(grep -c '^FAIL' "$LOG" || true)
failed=$(grep '^FAIL' "$LOG" | awk '{print $2}' | paste -sd, - || true)
echo "$(date -u +%FT%TZ) $sha $verdict fails:$fails ${failed}" >> "$HISTORY"
tail -n 25 "$LOG"

[[ $verdict == ok ]] && exit 0
kept="$STATE/stream-health-$(date +%F-%H%M%S).log"; cp "$LOG" "$kept"
case $verdict in
  build)   notify "Tubeless: stream check did not build" "$sha does not compile, nothing was asked of YouTube. $kept" ;;
  flagged) notify "Tubeless: YouTube is bot-challenging this IP" "Not a code break; it lifts on its own. $kept" ;;
  red)     notify "Tubeless: stream check failed" "$fails call(s) failed on $sha: ${failed:0:120}. Try :desktop:engineRepair. $kept" ;;
esac
exit "$status"
