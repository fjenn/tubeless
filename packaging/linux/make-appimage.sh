#!/usr/bin/env bash
# CI (Linux runner): one-file AppImage from the app Gradle built (createReleaseDistributable). It carries
# the build machine's libmpv and the libraries it needs (linuxdeploy, minus the ones every desktop has), so
# it plays on a fresh system; a system libmpv (libmpv2 / mpv-libs) is used first when there is one
# (Mpv.load). Needs libmpv installed on the build machine. From upstream TubeHaus build.yml.
#   packaging/linux/make-appimage.sh desktop/build/compose/binaries/main-release/app/Tubeless Tubeless-x.y.z-linux-x86_64.AppImage
set -euo pipefail
app=${1:?usage: make-appimage.sh <app dir> <out.AppImage>}
out=${2:?usage: make-appimage.sh <app dir> <out.AppImage>}
here=$(cd "$(dirname "$0")/.." && pwd)
arch=$(uname -m)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
AppDir="$work/AppDir"; mkdir -p "$AppDir"
cp -a "$app" "$AppDir/Tubeless"
find "$AppDir/Tubeless" -path '*/resources/signin-helper' -type f -exec chmod 755 {} +
cp "$here/flatpak/icon.png" "$AppDir/dev.tubeless.app.png"
cp "$here/flatpak/icon.png" "$AppDir/.DirIcon"
sed 's/^Exec=.*/Exec=Tubeless/' "$here/flatpak/dev.tubeless.app.desktop" > "$AppDir/dev.tubeless.app.desktop"
# shellcheck disable=SC2016
printf '%s\n' '#!/bin/sh' 'here="$(dirname "$(readlink -f "$0")")"' 'exec "$here/Tubeless/bin/Tubeless" "$@"' > "$AppDir/AppRun"
chmod +x "$AppDir/AppRun"
# libmpv and what it links to (glibc, X11, GL, ALSA/Pulse... stay the system's: linuxdeploy's excludelist)
mpv=$(ldconfig -p | awk '/libmpv\.so\.[0-9]+ /{print $NF; exit}')
[ -n "$mpv" ] || { echo "make-appimage: no libmpv on this machine (apt install libmpv1 / libmpv2)" >&2; exit 1; }
curl -fsSL -o "$work/linuxdeploy" "https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-$arch.AppImage"
chmod +x "$work/linuxdeploy"
APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1 "$work/linuxdeploy" --appdir "$AppDir" --library "$mpv" >/dev/null
compgen -G "$AppDir/usr/lib/libmpv.so*" >/dev/null || { echo "make-appimage: libmpv was not bundled" >&2; exit 1; }
echo "bundled: $(find "$AppDir/usr/lib" -type f | wc -l) libraries, $(du -sh "$AppDir/usr/lib" | cut -f1)"
curl -fsSL -o "$work/appimagetool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
chmod +x "$work/appimagetool"
ARCH=$arch APPIMAGE_EXTRACT_AND_RUN=1 "$work/appimagetool" --no-appstream "$AppDir" "$out"
ls -la "$out"
