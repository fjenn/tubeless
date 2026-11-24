#!/usr/bin/env bash
# Asset lint (PLAN §4 M3): Tubeless ships no YouTube logo, wordmark, icon path or "YouTube Sans".
# "YouTube" as plain text ("Sign in to your YouTube account") is fine and is not checked.
#
#   scripts/asset-lint.sh            # whole tree (tracked + untracked, minus ignored files)
#   scripts/asset-lint.sh FILE...    # only these files
#
# Fails (exit 1) on:
#   1. a file whose NAME looks like a YouTube logo/icon asset (yt_logo.png, youtube-icon.svg, ...)
#   2. the font name "YouTube Sans" / YouTubeSans / youtube_sans in any text file
#   3. a known YouTube logo vector path (Simple Icons, Font Awesome, Material, youtube.com's own
#      masthead SVG): these are the play-button-in-rounded-rectangle shapes, whatever they're called
#   4. an SVG/vector drawable whose id/name says youtube logo/icon
# Allow a line on purpose with the marker  asset-lint: allow  on the same line (e.g. this list).
set -uo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT" || exit 2

if [ $# -gt 0 ]; then
  files=("$@")
else
  files=()
  while IFS= read -r -d '' f; do files+=("$f"); done < <(git ls-files -z --cached --others --exclude-standard)
fi

fail=0
report() { echo "asset-lint: $1"; fail=1; }

# 1. file names
name_re='(^|/)[^/]*(yt[_-]?logo|youtube[_-]?(logo|icon|wordmark|play[_-]?button)|yt[_-]?icon|youtube[_-]?sans)[^/]*$'
for f in "${files[@]}"; do
  [ -e "$f" ] || continue
  if printf '%s\n' "$f" | grep -qiE "$name_re"; then report "$f: file name looks like a YouTube logo/icon/font asset"; fi
done

# 2-4. contents. Fixed strings first (fast), then the regexes.
patterns=$(mktemp); trap 'rm -f "$patterns"' EXIT
cat > "$patterns" <<'PAT'
YouTube Sans
YouTubeSans
YouTube-Sans
youtube_sans
youtube-sans
M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136
M23.5 6.19a3.02 3.02 0 0 0-2.12-2.14
M549.655 124.083c-6.281-23.65-24.787-42.276
M549.7 124.1c-6.3-23.7-24.8-42.3-48.3-48.6
M10 15l5.19-3L10 9v6m11.56-7.83c.13.47.22 1.1.28 1.9
M21.58 7.19c-.23-.86-.91-1.54-1.77-1.77C18.25 5 12 5 12 5
M27.9727 3.12324C27.6435 1.89323 26.6768 0.926623 25.4468 0.597366
M27.9704 3.12324C27.6411 1.89323 26.6745 0.926623 25.4445 0.597366
PAT
# vector ids/names that say what they are
id_re='(android:name|id|name|contentDescription)="[^"]*(yt[_-]?logo|youtube[_-]?(logo|icon|wordmark))'

text_files=()
for f in "${files[@]}"; do
  [ -f "$f" ] || continue
  case "$f" in
    scripts/asset-lint.sh|tests/asset-lint/*) continue ;;   # this list, and its own test inputs
    *.md|docs/*) continue ;;                                 # prose about the rule, never shipped
  esac
  # skip binaries (images are judged by name above), and huge generated files
  if file -b --mime "$f" | grep -q 'charset=binary'; then continue; fi
  text_files+=("$f")
done

if [ ${#text_files[@]} -gt 0 ]; then
  hits=$(printf '%s\0' "${text_files[@]}" | xargs -0 grep -nHF -f "$patterns" 2>/dev/null | grep -v 'asset-lint: allow' || true)
  [ -n "$hits" ] && { while IFS= read -r h; do report "${h:0:220}"; done <<< "$hits"; }
  hits=$(printf '%s\0' "${text_files[@]}" | xargs -0 grep -nHiE "$id_re" 2>/dev/null | grep -v 'asset-lint: allow' || true)
  [ -n "$hits" ] && { while IFS= read -r h; do report "${h:0:220}"; done <<< "$hits"; }
fi

if [ $fail = 0 ]; then echo "asset-lint: OK (${#files[@]} files checked)"; else echo "asset-lint: FAILED"; fi
exit $fail
