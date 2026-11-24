#!/usr/bin/env bash
# CI (macOS runner): copies Homebrew's libmpv and every library it needs into <dir> (the app's
# resources/macos), rewired to load from there, so users need no mpv of their own. Ad-hoc signed here;
# sign-dmg.sh re-signs them with the Developer ID when there is one. From upstream TubeHaus build.yml.
#   packaging/macos/bundle-libmpv.sh desktop/resources/macos
set -euo pipefail
d=${1:?usage: bundle-libmpv.sh <dir>}
brew install mpv dylibbundler
rm -rf "$d" && mkdir -p "$d"
cp "$(brew --prefix)/lib/libmpv.2.dylib" "$d/" && chmod u+w "$d/libmpv.2.dylib"
dylibbundler -of -b -x "$d/libmpv.2.dylib" -d "$d/" -p @loader_path/
install_name_tool -id @loader_path/libmpv.2.dylib "$d/libmpv.2.dylib"
chmod u+w "$d"/*.dylib
# one search path each: macOS refuses a library that lists the same one twice
rpaths() { otool -l "$1" | awk '/cmd LC_RPATH/ { getline; getline; print $2 }'; }
for f in "$d"/*.dylib; do
  for p in $(rpaths "$f" | sort -u); do
    while rpaths "$f" | grep -qxF "$p"; do install_name_tool -delete_rpath "$p" "$f" || break; done
  done
  if otool -L "$f" | grep -q "@rpath/"; then install_name_tool -add_rpath @loader_path/ "$f"; fi
done
for f in "$d"/*.dylib; do codesign --force --sign - "$f"; done
# nothing may still point at Homebrew
bad=0
for f in "$d"/*.dylib; do
  if otool -L "$f" | tail -n +2 | awk '{print $1}' | grep -vE '^(@loader_path|@rpath|/usr/lib|/System)'; then echo "still outside: $f"; bad=1; fi
done
test $bad = 0
# and it loads, every library from inside and none from Homebrew (Apple's Python, not Homebrew's)
/usr/bin/python3 - "$d/libmpv.2.dylib" <<'PY'
import ctypes, sys
m = ctypes.CDLL(sys.argv[1]); m.mpv_client_api_version.restype = ctypes.c_ulong
print("libmpv api", hex(m.mpv_client_api_version()))
dyld = ctypes.CDLL("/usr/lib/libSystem.B.dylib"); dyld._dyld_get_image_name.restype = ctypes.c_char_p
names = [dyld._dyld_get_image_name(i).decode() for i in range(dyld._dyld_image_count())]
brew = [n for n in names if n.startswith(("/opt/homebrew", "/usr/local"))]
for n in brew: print("from Homebrew:", n)
sys.exit(1 if brew else 0)
PY
find "$d" -name '*.dylib' | wc -l; du -sh "$d"
