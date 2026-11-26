#!/usr/bin/env bash
# CI (Linux runner): one-file AppImage from the app Gradle built (createReleaseDistributable). It carries
# the build machine's libmpv and the libraries it needs (linuxdeploy, minus the ones every desktop has), so
# it plays on a fresh system; a system libmpv (libmpv2 / mpv-libs) is used first when there is one
# (Mpv.load). From upstream TubeHaus build.yml.
#
# The sign-in window (resources/signin-helper, a wry web view) needs WebKitGTK 4.1, which few desktops have
# installed: it is carried too, the way Tauri's AppImages carry it (tauri-bundler, linux/appimage):
#  - the helper goes to usr/bin and linuxdeploy brings its libraries (libwebkit2gtk-4.1, libjavascriptcoregtk-4.1,
#    GTK...); linuxdeploy-plugin-gtk (Tauri's copy) adds GTK's modules, pixbuf loaders, GSettings schemas and the
#    GIO modules (TLS), with an apprun hook that points GTK at them
#  - WebKit's helper processes (WebKitNetworkProcess, WebKitWebProcess, the injected bundle) go to the same path
#    under usr/ as on the build machine, and libwebkit2gtk's built-in /usr paths are patched to ././ (same
#    length), so it finds them relative to the working directory
#  - usr/bin/signin-helper-run sources the hooks, changes to usr/ and starts the helper; AppRun hands that
#    script to the app (TUBELESS_SIGNIN_HELPER). The app itself (Java) keeps the system's environment.
# The AppImage runtime is the current static type2 runtime, with its "Error: No suitable fusermount binary found"
# line blanked: on a system without fusermount (as root, it then mounts by itself) that line was all a user saw.
#
# Needs on the build machine: libmpv, libwebkit2gtk-4.1-dev, libgtk-3-dev, librsvg2-dev, pkg-config, dpkg-dev.
#   packaging/linux/make-appimage.sh desktop/build/compose/binaries/main-release/app/Tubeless Tubeless-x.y.z-linux-x86_64.AppImage
set -euo pipefail
app=${1:?usage: make-appimage.sh <app dir> <out.AppImage>}
out=${2:?usage: make-appimage.sh <app dir> <out.AppImage>}
here=$(cd "$(dirname "$0")/.." && pwd)
arch=$(uname -m)
triplet=$(dpkg-architecture -qDEB_HOST_MULTIARCH 2>/dev/null || echo "$arch-linux-gnu")
# pinned: Tauri's linuxdeploy-plugin-gtk (its GIO modules part is what TLS in the web view needs), the AppImage runtime
GTK_PLUGIN=https://raw.githubusercontent.com/tauri-apps/tauri/8e7028331ad37ac2db74d4ec20e66be5cacf2c40/crates/tauri-bundler/src/bundle/linux/appimage/linuxdeploy-plugin-gtk.sh
RUNTIME=https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-$arch
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
AppDir="$work/AppDir"; mkdir -p "$AppDir"
cp -a "$app" "$AppDir/Tubeless"
find "$AppDir/Tubeless" -path '*/resources/signin-helper' -type f -exec chmod 755 {} +
cp "$here/flatpak/icon.png" "$AppDir/dev.tubeless.app.png"
cp "$here/flatpak/icon.png" "$AppDir/.DirIcon"
sed 's/^Exec=.*/Exec=Tubeless/' "$here/flatpak/dev.tubeless.app.desktop" > "$AppDir/dev.tubeless.app.desktop"

# libmpv and what it links to (glibc, X11, GL, ALSA/Pulse... stay the system's: linuxdeploy's excludelist)
mpv=$(ldconfig -p | awk '/libmpv\.so\.[0-9]+ /{print $NF; exit}')
[ -n "$mpv" ] || { echo "make-appimage: no libmpv on this machine (apt install libmpv1 / libmpv2)" >&2; exit 1; }

# the sign-in window and WebKitGTK's helper processes
helper=$(find "$AppDir/Tubeless" -path '*/resources/signin-helper' -type f | head -1)
[ -n "$helper" ] || { echo "make-appimage: no resources/signin-helper in the app" >&2; exit 1; }
wk=/usr/lib/$triplet/webkit2gtk-4.1
[ -x "$wk/WebKitWebProcess" ] || { echo "make-appimage: no WebKitGTK 4.1 in $wk (apt install libwebkit2gtk-4.1-dev)" >&2; exit 1; }
install -D -m 755 "$helper" "$AppDir/usr/bin/signin-helper"
for f in WebKitNetworkProcess WebKitWebProcess injected-bundle/libwebkit2gtkinjectedbundle.so; do
  install -D "$wk/$f" "$AppDir/usr/lib/$triplet/webkit2gtk-4.1/$f"
done

curl -fsSL -o "$work/linuxdeploy" "https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-$arch.AppImage"
curl -fsSL -o "$work/linuxdeploy-plugin-gtk.sh" "$GTK_PLUGIN"
chmod +x "$work/linuxdeploy" "$work/linuxdeploy-plugin-gtk.sh"
PATH="$work:$PATH" APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1 DEPLOY_GTK_VERSION=3 \
  "$work/linuxdeploy" --appdir "$AppDir" --executable "$AppDir/usr/bin/signin-helper" --library "$mpv" --plugin gtk >"$work/linuxdeploy.log" 2>&1 ||
  { tail -40 "$work/linuxdeploy.log" >&2; echo "make-appimage: linuxdeploy failed" >&2; exit 1; }
# kept out by linuxdeploy's excludelist but missing on a stock Ubuntu desktop (FFmpeg's libavdevice wants them;
# WebKit opens libGLESv2 itself, the GLVND front that hands over to the system's GL, and aborts without it)
for want in libjack.so.0 libdb-5.3.so libGLESv2.so.2; do
  p=$(ldconfig -p | awk -v w="$want" '$1 == w { print $NF; exit }')
  if [ -n "$p" ] && [ ! -e "$AppDir/usr/lib/$want" ]; then cp -L "$p" "$AppDir/usr/lib/$want"; fi
done
compgen -G "$AppDir/usr/lib/libmpv.so*" >/dev/null || { echo "make-appimage: libmpv was not bundled" >&2; exit 1; }
compgen -G "$AppDir/usr/lib/libwebkit2gtk-4.1.so*" >/dev/null || { echo "make-appimage: WebKitGTK was not bundled" >&2; exit 1; }
[ -f "$AppDir/apprun-hooks/linuxdeploy-plugin-gtk.sh" ] || { echo "make-appimage: no GTK apprun hook" >&2; exit 1; }

# WebKit looks for its helper processes (and injected bundle) at the build machine's /usr/lib/<triplet>/webkit2gtk-4.1:
# "/usr" becomes "././", relative to usr/ inside the AppImage, where signin-helper-run starts the helper
find "$AppDir/usr/lib" -name 'libwebkit*' -type f -exec sed -i -e 's|/usr|././|g' {} +
grep -q -a "././/lib/$triplet/webkit2gtk-4.1" "$AppDir"/usr/lib/libwebkit2gtk-4.1.so* ||
  { echo "make-appimage: libwebkit2gtk has no helper process path to patch" >&2; exit 1; }

# the app's own start: linuxdeploy's hooks (GTK's environment) stay with the sign-in window, not the Java app
rm -f "$AppDir/AppRun" "$AppDir/AppRun.wrapped"
cat > "$AppDir/AppRun" <<'EOF'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
# the sign-in window runs with the WebKitGTK inside this AppImage (usr/bin/signin-helper-run)
TUBELESS_SIGNIN_HELPER="$here/usr/bin/signin-helper-run"; export TUBELESS_SIGNIN_HELPER
exec "$here/Tubeless/bin/Tubeless" "$@"
EOF
cat > "$AppDir/usr/bin/signin-helper-run" <<'EOF'
#!/usr/bin/env bash
# The sign-in window inside the AppImage: GTK's settings from linuxdeploy-plugin-gtk's hook, then the helper started
# from usr/, where the bundled libwebkit2gtk finds its helper processes (./lib/<triplet>/webkit2gtk-4.1, see make-appimage.sh)
APPDIR="${APPDIR:-$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)}"; export APPDIR
for h in "$APPDIR"/apprun-hooks/*.sh; do [ -f "$h" ] && . "$h"; done
# WebKit's helper processes (usr/lib/<triplet>/webkit2gtk-4.1) find the carried libraries here; only the sign-in
# window and the processes it starts see this
LD_LIBRARY_PATH="$APPDIR/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; export LD_LIBRARY_PATH
# no GPU compositing: the carried GLES front and the system's GL dispatch needn't match (a sign-in page needs no GPU)
WEBKIT_DISABLE_COMPOSITING_MODE=1; export WEBKIT_DISABLE_COMPOSITING_MODE
cd "$APPDIR/usr" || exit 1
exec "$APPDIR/usr/bin/signin-helper" "$@"
EOF
chmod +x "$AppDir/AppRun" "$AppDir/usr/bin/signin-helper-run"
echo "bundled: $(find "$AppDir/usr/lib" -type f | wc -l) libraries, $(du -sh "$AppDir/usr/lib" | cut -f1)"

# the static type2 runtime, with the fusermount "Error:" line blanked (the string must be there exactly once)
curl -fsSL -o "$work/runtime" "$RUNTIME"
python3 - "$work/runtime" <<'EOF'
import sys
p = sys.argv[1]
b = open(p, 'rb').read()
m = b'Error: No suitable fusermount binary found on the $PATH'
assert b.count(m) == 1, 'runtime: the fusermount message is not there exactly once'
open(p, 'wb').write(b.replace(m, b'\0' + m[1:]))
EOF
curl -fsSL -o "$work/appimagetool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
chmod +x "$work/appimagetool"
ARCH=$arch APPIMAGE_EXTRACT_AND_RUN=1 "$work/appimagetool" --no-appstream --runtime-file "$work/runtime" "$AppDir" "$out"
ls -la "$out"
