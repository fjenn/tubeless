#!/usr/bin/env bash
# Builds the Tubeless website (Instant mode, the install page with the extension download, the bookmark's yt.js) and
# puts it into the opena.tech repo, where it is served as static Workers assets at https://opena.tech/tubeless/web/
# (install page: .../web/install). One step per release; then commit + deploy opena.tech as usual.
#
#   scripts/publish-site-opena.sh [OPENA_DIR]        (default: $OPENA_DIR, else ~/Projects/opena)
#
# env: SITE_URL     the site's own address, baked into the bookmark (default https://opena.tech/tubeless/web/;
#                   a local preview for tests: http://127.0.0.1:8787/tubeless/web/)
#      SKIP_WASM=1  reuse the current wasm + extension build (only re-run site/build.mjs), e.g. for a test SITE_URL
#      FIREFOX_XPI  a signed Firefox add-on to serve (default: the newest release's, when it has one)
#      DEST         override the target folder (default $OPENA_DIR/public/tubeless/web)
# The opena repo's public/_headers gives these files CORS (the bookmark loads them into youtube.com) and caching.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OPENA_DIR="${1:-${OPENA_DIR:-$HOME/Projects/opena}}"
DEST="${DEST:-$OPENA_DIR/public/tubeless/web}"
export SITE_URL="${SITE_URL:-https://opena.tech/tubeless/web/}"
[ -d "$OPENA_DIR/public" ] || { echo "ERROR: no opena repo at $OPENA_DIR" >&2; exit 1; }
VERSION="$(sed -n 's/^app\.version=//p' gradle.properties)"

# 1. the release wasm (incremental) and the extension from it (its Chrome zip is the install page's download)
if [ "${SKIP_WASM:-}" != 1 ]; then
  GRADLE_ARGS="${GRADLE_ARGS:-}" sh extension/build.sh
fi
[ -f "extension/dist/tubeless-$VERSION-chrome.zip" ] || { echo "ERROR: no extension zip for $VERSION (run without SKIP_WASM)" >&2; exit 1; }

# 2. Firefox: the signed add-on of the newest release, when it has one (release.yml signs it unlisted on AMO)
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
if [ -z "${FIREFOX_XPI:-}" ] && command -v gh >/dev/null && gh release download -R fjenn/tubeless --pattern 'Tubeless-*-firefox.xpi' --dir "$TMP/xpi" 2>/dev/null; then
  FIREFOX_XPI="$(find "$TMP/xpi" -name '*.xpi' | head -1)"
fi
export FIREFOX_XPI="${FIREFOX_XPI:-}"

# 3. the site, then into the opena repo (Cloudflare's per-file limit is 25 MiB)
OUT="$TMP/site" node site/build.mjs
rm -f "$TMP/site/.nojekyll"
cp "$TMP/site/youtube.html" "$TMP/site/install.html"   # the install page's clean address (.../web/install)
big="$(find "$TMP/site" -type f -size +24M)"
[ -z "$big" ] || { echo "ERROR: over Cloudflare's 25 MiB per-file limit: $big" >&2; exit 1; }
mkdir -p "$DEST"
rsync -a --delete "$TMP/site/" "$DEST/"
echo "published Tubeless $VERSION site ($SITE_URL) to $DEST"
echo "next: cd $OPENA_DIR && git add public/tubeless/web && git commit -m 'Tubeless web $VERSION' && npm run cf:build && npm run cf:deploy"
