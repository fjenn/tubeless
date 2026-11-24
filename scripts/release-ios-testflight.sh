#!/usr/bin/env bash
# Build the iPhone app (ios/) for the App Store and upload it to TestFlight, from this Mac.
#
#   scripts/release-ios-testflight.sh            # next build number, archive, upload, wait
#   scripts/release-ios-testflight.sh --no-wait  # don't wait for Apple's processing
#   scripts/release-ios-testflight.sh --build 7  # a given build number
#   scripts/release-ios-testflight.sh --reuse    # upload build/testflight/Tubeless-<v>-<n>.xcarchive
#                                                # if it exists (no Kotlin/Xcode rebuild)
#
# Signing: Xcode automatic signing driven by the App Store Connect API key
# (-allowProvisioningUpdates -authenticationKey*), cloud-managed Apple Distribution certificate,
# nothing stored in the repo. Same flow as Spotless (music-app .github/workflows/release-ios.yml).
# Key: ASC_KEY_PATH / ASC_KEY_ID / ASC_ISSUER (defaults: the team's Admin key on this Mac).
# Needs the App Store Connect app record for dev.tubeless.app (Apple's API cannot create one:
# appstoreconnect.apple.com > Apps > + > New App, once).
#
# Incremental: the Kotlin framework is linked by Gradle (its caches are reused), Xcode's derived
# data lives in build/xcode-release.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
: "${ASC_KEY_ID:?set ASC_KEY_ID (App Store Connect API key id)}"
: "${ASC_KEY_PATH:?set ASC_KEY_PATH (path to AuthKey_<id>.p8)}"
: "${ASC_ISSUER:?set ASC_ISSUER (App Store Connect issuer id)}"
export ASC_KEY_ID ASC_KEY_PATH ASC_ISSUER
TEAM_ID="${APPLE_TEAM_ID:?set APPLE_TEAM_ID}"
BUNDLE_ID=dev.tubeless.app
YML=ios/app/project.yml
OUT="$ROOT/build/testflight"
WAIT=1
REUSE=0
BUILD=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-wait) WAIT=0 ;;
    --reuse) REUSE=1 ;;
    --build) BUILD="$2"; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
[ -f "$ASC_KEY_PATH" ] || { echo "no API key at $ASC_KEY_PATH" >&2; exit 1; }
asc() { ruby "$ROOT/scripts/asc-api.rb" "$@"; }
jq_r() { ruby -rjson -e "d=JSON.parse(STDIN.read); $1"; }

APP_ID="$(asc GET "/v1/apps?filter%5BbundleId%5D=$BUNDLE_ID" | jq_r 'puts d["data"].find{|a| a["attributes"]["bundleId"]=="'"$BUNDLE_ID"'"}&.dig("id").to_s')"
[ -n "$APP_ID" ] || { echo "No App Store Connect app for $BUNDLE_ID yet: create it once at appstoreconnect.apple.com (Apps > + > New App, name Tubeless, bundle $BUNDLE_ID)." >&2; exit 1; }

VERSION="$(sed -n -E 's/^ *MARKETING_VERSION: *"?([^"]*)"? *$/\1/p' "$YML" | head -1)"
if [ -z "$BUILD" ]; then
  # one more than the highest build App Store Connect has seen, or project.yml's if higher
  LAST="$(asc GET "/v1/builds?filter%5Bapp%5D=$APP_ID&sort=-uploadedDate&limit=50&fields%5Bbuilds%5D=version" \
    | jq_r 'puts (d["data"].map{|b| b["attributes"]["version"].to_i}.max || 0)')"
  CUR="$(sed -n -E 's/^ *CURRENT_PROJECT_VERSION: *"?([^"]*)"? *$/\1/p' "$YML" | head -1)"
  BUILD=$(( LAST + 1 > CUR ? LAST + 1 : CUR ))
fi
sed -i '' -E "s/^( *CURRENT_PROJECT_VERSION: *)\"[0-9]*\"/\1\"$BUILD\"/" "$YML"
echo "== Tubeless $VERSION ($BUILD), app $APP_ID"

mkdir -p "$OUT"
ARCHIVE="$OUT/Tubeless-$VERSION-$BUILD.xcarchive"
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH"
      -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER")
if [ "$REUSE" = 1 ] && [ -d "$ARCHIVE" ]; then
  echo "== Reusing $ARCHIVE"
else
echo "== Kotlin framework (release, iosArm64)"
./gradlew -q :ios:linkReleaseFrameworkIosArm64
rm -rf ios/build/kit && mkdir -p ios/build/kit
cp -R ios/build/bin/iosArm64/releaseFramework/TubeHausKit.framework ios/build/kit/

echo "== Xcode project"
(cd ios/app && xcodegen generate --quiet)

echo "== Archive -> $ARCHIVE"
rm -rf "$ARCHIVE"
xcodebuild archive -project ios/app/Tubeless.xcodeproj -scheme Tubeless -configuration Release \
  -destination generic/platform=iOS -archivePath "$ARCHIVE" -derivedDataPath build/xcode-release \
  "${AUTH[@]}" DEVELOPMENT_TEAM="$TEAM_ID" CURRENT_PROJECT_VERSION="$BUILD" ARCHS=arm64 \
  > "$OUT/archive.log" 2>&1 || { grep -E 'error:' "$OUT/archive.log" | sort -u | head -40; tail -30 "$OUT/archive.log"; exit 1; }
fi
INFO="$ARCHIVE/Products/Applications/Tubeless.app/Info.plist"
echo "archived $(plutil -extract CFBundleShortVersionString raw "$INFO") ($(plutil -extract CFBundleVersion raw "$INFO"))"

echo "== Export + upload to App Store Connect"
OPTS="$OUT/ExportOptions.plist"
cat > "$OPTS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>$TEAM_ID</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
EOF
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTS" -exportPath "$OUT/export" \
  "${AUTH[@]}" > "$OUT/export.log" 2>&1 || { tail -40 "$OUT/export.log"; exit 1; }
grep -iE 'upload|succeeded|EXPORT' "$OUT/export.log" | tail -5

# Internal testing group (team members, no Beta App Review) with every build, and the account
# holder in it. Idempotent.
GROUP_ID="$(asc GET "/v1/apps/$APP_ID/betaGroups?limit=50" | jq_r 'g=d["data"].find{|x| x["attributes"]["isInternalGroup"]}; puts g ? g["id"] : ""')"
if [ -z "$GROUP_ID" ]; then
  GROUP_ID="$(asc POST /v1/betaGroups '{"data":{"type":"betaGroups","attributes":{"name":"Team","isInternalGroup":true,"hasAccessToAllBuilds":true},"relationships":{"app":{"data":{"type":"apps","id":"'"$APP_ID"'"}}}}}' | jq_r 'puts d["data"]["id"]')"
  echo "created internal group Team ($GROUP_ID)"
fi
OWNER="$(asc GET '/v1/users?limit=50&filter%5Broles%5D=ACCOUNT_HOLDER' | jq_r 'u=d["data"].first; puts [u["attributes"]["username"],u["attributes"]["firstName"],u["attributes"]["lastName"]].join("\t")')"
IFS=$'\t' read -r OWNER_EMAIL OWNER_FIRST OWNER_LAST <<<"$OWNER"
if [ "$(asc GET "/v1/betaGroups/$GROUP_ID/betaTesters?limit=200" | jq_r 'puts d["data"].any?{|t| t["attributes"]["email"].to_s.casecmp?("'"$OWNER_EMAIL"'")}')" != true ]; then
  asc POST /v1/betaTesters '{"data":{"type":"betaTesters","attributes":{"email":"'"$OWNER_EMAIL"'","firstName":"'"$OWNER_FIRST"'","lastName":"'"$OWNER_LAST"'"},"relationships":{"betaGroups":{"data":[{"type":"betaGroups","id":"'"$GROUP_ID"'"}]}}}}' >/dev/null \
    && echo "account holder added to the internal group"
fi

[ "$WAIT" = 1 ] || { echo "uploaded $VERSION ($BUILD); processing at Apple"; exit 0; }
echo "== Waiting for Apple's processing"
for _ in $(seq 1 90); do
  STATE="$(asc GET "/v1/builds?filter%5Bapp%5D=$APP_ID&filter%5Bversion%5D=$BUILD&fields%5Bbuilds%5D=processingState" \
    | jq_r 'b=d["data"].first; puts b ? b["attributes"]["processingState"] : "NOT_YET_VISIBLE"')"
  echo "  $(date +%H:%M) $STATE"
  case "$STATE" in VALID|FAILED|INVALID) break ;; esac
  sleep 30
done
echo "Tubeless $VERSION ($BUILD): $STATE"
[ "$STATE" = VALID ]
