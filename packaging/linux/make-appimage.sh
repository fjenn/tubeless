#!/usr/bin/env bash
# CI (Linux runner): one-file AppImage from the app Gradle built (createReleaseDistributable). Like the
# .deb/.rpm it uses the system's libmpv (libmpv2 / mpv-libs). From upstream TubeHaus build.yml.
#   packaging/linux/make-appimage.sh desktop/build/compose/binaries/main-release/app/Tubeless Tubeless-x.y.z-linux-x86_64.AppImage
set -euo pipefail
app=${1:?usage: make-appimage.sh <app dir> <out.AppImage>}
out=${2:?usage: make-appimage.sh <app dir> <out.AppImage>}
here=$(cd "$(dirname "$0")/.." && pwd)
arch=$(uname -m)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
AppDir="$work/AppDir"; mkdir -p "$AppDir"
cp -a "$app" "$AppDir/Tubeless"
cp "$here/flatpak/icon.png" "$AppDir/dev.tubeless.app.png"
cp "$here/flatpak/icon.png" "$AppDir/.DirIcon"
sed 's/^Exec=.*/Exec=Tubeless/' "$here/flatpak/dev.tubeless.app.desktop" > "$AppDir/dev.tubeless.app.desktop"
# shellcheck disable=SC2016
printf '%s\n' '#!/bin/sh' 'here="$(dirname "$(readlink -f "$0")")"' 'exec "$here/Tubeless/bin/Tubeless" "$@"' > "$AppDir/AppRun"
chmod +x "$AppDir/AppRun"
curl -fsSL -o "$work/appimagetool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
chmod +x "$work/appimagetool"
ARCH=$arch APPIMAGE_EXTRACT_AND_RUN=1 "$work/appimagetool" --no-appstream "$AppDir" "$out"
ls -la "$out"
