#!/usr/bin/env bash
# Signs a built Tubeless.app, packs it into a DMG and, when credentials are there, notarizes and staples it.
#
#   packaging/macos/sign-dmg.sh <Tubeless.app> <out.dmg>
#
# Developer ID (notarize-ready) when SIGN_IDENTITY is set (a "Developer ID Application: ..." name or its
# SHA-1, in an unlocked keychain); every Mach-O inside is signed inside-out with the hardened runtime,
# then the bundle with packaging/macos/entitlements.plist. Notarized when one of these is set too:
#   NOTARY_PROFILE                                   a `xcrun notarytool store-credentials` profile, or
#   NOTARY_KEY_PATH + NOTARY_KEY_ID + NOTARY_ISSUER  an App Store Connect API key (.p8)
# Without SIGN_IDENTITY: ad hoc (`codesign -s -`), the M3 fallback. Users then right-click → Open once
# (or `xattr -dr com.apple.quarantine /Applications/Tubeless.app`).
#
# Native code inside the jars (skiko, JNA, Javet's Node.js, the Swift helpers) is signed here too (Apple's
# notary service looks inside jars), so an app built without Compose's signing options can be signed later,
# e.g. a CI-built .app on a Mac that holds the Developer ID.
set -euo pipefail
APP=${1:?usage: sign-dmg.sh <app> <out.dmg>}
DMG=${2:?usage: sign-dmg.sh <app> <out.dmg>}
HERE=$(cd "$(dirname "$0")" && pwd)
ENT=${ENTITLEMENTS:-$HERE/entitlements.plist}
step() { printf '\n== %s\n' "$*"; }
die() { echo "sign-dmg: $*" >&2; exit 1; }
[ -d "$APP/Contents/MacOS" ] || die "$APP is not an app bundle"
APP=$(cd "$APP" && pwd)

# every Mach-O file in the bundle, deepest first (inside-out signing)
machos() {
  # every file, not only those with an exec bit (resources such as signin-helper can arrive without one)
  find "$APP/Contents" -type f -print0 |
    while IFS= read -r -d '' f; do file -b "$f" | grep -q 'Mach-O' && printf '%s\n' "$f"; done |
    awk -F/ '{ print NF "\t" $0 }' | sort -rn | cut -f2-
}

# Mach-O executables that lost their exec bit on the way (artifact zips) get it back
while IFS= read -r f; do file -b "$f" | grep -q 'executable' && chmod +x "$f"; done < <(machos)

step "signing ($( [ -n "${SIGN_IDENTITY:-}" ] && echo "Developer ID" || echo "ad hoc" ))"
if [ -n "${SIGN_IDENTITY:-}" ]; then
  [ -f "$ENT" ] || die "no entitlements at $ENT"
  # Mach-O files inside jars: unpacked, signed, put back (the jar is updated in place)
  jn=0
  while IFS= read -r -d '' jar; do
    unzip -Z1 "$jar" 2>/dev/null | grep -q -E '\.(dylib|jnilib)$|(^|/)[^./]+$' || continue
    jt=$(mktemp -d)
    unzip -q -o "$jar" -d "$jt" -x 'META-INF/*' 2>/dev/null || true
    signed=()
    while IFS= read -r -d '' f; do
      file -b "$f" | grep -q 'Mach-O' || continue
      if file -b "$f" | grep -q 'executable'; then
        codesign --force --timestamp --options runtime --entitlements "$ENT" -s "$SIGN_IDENTITY" "$f"
      else
        codesign --force --timestamp --options runtime -s "$SIGN_IDENTITY" "$f"
      fi
      signed+=("${f#"$jt"/}")
    done < <(find "$jt" -type f -print0)
    if [ ${#signed[@]} -gt 0 ]; then
      (cd "$jt" && zip -q "$jar" "${signed[@]}")
      jn=$((jn + ${#signed[@]}))
    fi
    rm -rf "$jt"
  done < <(find "$APP/Contents" -name '*.jar' -print0)
  echo "$jn Mach-O files inside jars signed"
  n=0
  while IFS= read -r f; do
    # executables (java, jspawnhelper, the launcher) need the entitlements too, plain libraries don't
    if file -b "$f" | grep -q 'executable'; then
      codesign --force --timestamp --options runtime --entitlements "$ENT" -s "$SIGN_IDENTITY" "$f"
    else
      codesign --force --timestamp --options runtime -s "$SIGN_IDENTITY" "$f"
    fi
    n=$((n + 1))
  done < <(machos)
  echo "$n Mach-O files signed"
  # the bundled JDK is a bundle of its own in Contents/runtime
  [ -d "$APP/Contents/runtime" ] && codesign --force --timestamp --options runtime -s "$SIGN_IDENTITY" "$APP/Contents/runtime"
  codesign --force --timestamp --options runtime --entitlements "$ENT" -s "$SIGN_IDENTITY" "$APP"
else
  codesign --force --deep -s - "$APP"
fi
codesign --verify --deep --strict --verbose=1 "$APP"

step "dmg"
rm -f "$DMG"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -R "$APP" "$stage/"
ln -s /Applications "$stage/Applications"
name=$(basename "$APP" .app)
hdiutil create -volname "$name" -srcfolder "$stage" -fs HFS+ -format UDZO -ov "$DMG" >/dev/null
[ -n "${SIGN_IDENTITY:-}" ] && codesign --force --timestamp -s "$SIGN_IDENTITY" "$DMG"

auth=()
if [ -n "${NOTARY_PROFILE:-}" ]; then
  auth=(--keychain-profile "$NOTARY_PROFILE")
elif [ -n "${NOTARY_KEY_PATH:-}${NOTARY_KEY_ID:-}${NOTARY_ISSUER:-}" ]; then
  [ -f "${NOTARY_KEY_PATH:-}" ] && [ -n "${NOTARY_KEY_ID:-}" ] && [ -n "${NOTARY_ISSUER:-}" ] \
    || die "notarizing by API key needs NOTARY_KEY_PATH (an existing .p8), NOTARY_KEY_ID and NOTARY_ISSUER"
  auth=(--key "$NOTARY_KEY_PATH" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER")
fi
if [ -n "${SIGN_IDENTITY:-}" ] && [ ${#auth[@]} -gt 0 ]; then
  step "notarizing (usually 2-15 minutes)"
  out=$(xcrun notarytool submit "$DMG" "${auth[@]}" --wait --output-format json) || true
  status=$(/usr/bin/python3 -I -c 'import json,sys
try: print(json.loads(sys.stdin.read()).get("status",""))
except Exception: print("")' <<< "$out")
  id=$(/usr/bin/python3 -I -c 'import json,sys
try: print(json.loads(sys.stdin.read()).get("id",""))
except Exception: print("")' <<< "$out")
  echo "notarization $id: ${status:-no answer}"
  if [ "$status" != Accepted ]; then
    echo "$out"
    [ -n "$id" ] && xcrun notarytool log "$id" "${auth[@]}" || true
    die "notarization not accepted"
  fi
  xcrun stapler staple "$DMG"
  spctl -a -t open --context context:primary-signature -v "$DMG" || true
elif [ -n "${SIGN_IDENTITY:-}" ]; then
  echo "signed but not notarized (no NOTARY_* credentials)"
fi
hdiutil verify -quiet "$DMG"
ls -la "$DMG"
