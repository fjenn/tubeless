#!/usr/bin/env bash
# A fresh Ubuntu 24.04 (the ubuntu:24.04 image, as root, from smoke.yml): what a user who downloads Tubeless gets.
#   1 the AppImage on a system without libmpv (it carries its own): full smoke flow under Xvfb
#   2 the .deb installed with apt (its dependencies resolved, libmpv among them); the menu entry passes
#     desktop-file-validate, the AppStream data validates, the icon is there; full smoke flow under Xvfb
#   scripts/smoke/ubuntu24.sh <file.deb> <file.AppImage> <out dir>
set -uo pipefail
deb=$(readlink -f "${1:?usage: ubuntu24.sh <deb> <AppImage> <out>}")
appimage=$(readlink -f "${2:?}")
out=$(readlink -f -m "${3:?}")
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$out"
fail=0
check() { if "$@"; then echo "PASS: $*"; else echo "FAIL: $*"; fail=1; fi; }
export DEBIAN_FRONTEND=noninteractive
apt-get update -q >/dev/null
# the test's own tools (an X server, screenshots, validators), none of the app's libraries
apt-get install -y -q --no-install-recommends xvfb xauth imagemagick desktop-file-utils appstream ca-certificates >/dev/null
# what every Ubuntu desktop already has and the plain image lacks (X11 / GTK / sound / GL client libraries)
apt-get install -y -q --no-install-recommends libgtk-3-0t64 libxtst6 libxi6 libxrender1 libasound2t64 libgl1 libpulse0 fonts-dejavu-core >/dev/null
grep PRETTY_NAME /etc/os-release

echo "== 1 AppImage, no libmpv on the system"
check test -z "$(ldconfig -p | grep libmpv)"
cp "$appimage" /tmp/Tubeless.AppImage && chmod +x /tmp/Tubeless.AppImage
# no FUSE inside a container: the AppImage unpacks itself to /tmp instead (on a desktop it mounts)
APPIMAGE_EXTRACT_AND_RUN=1 xvfb-run -a -s "-screen 0 1440x960x24" "$here/desktop.sh" /tmp/Tubeless.AppImage "$out/appimage" || fail=1
grep -q 'mpv is missing' "$out/appimage/app-stdout.log" && { echo "FAIL: AppImage found no libmpv"; fail=1; }
rm -rf ~/.config/Tubeless ~/.cache/Tubeless

echo "== 2 .deb"
apt-get install -y -q "$deb" > "$out/apt-install.log" 2>&1; rc=$?
cat "$out/apt-install.log" | tail -5
check test "$rc" = 0
# (the image leaves out man pages, so update-alternatives notes it skipped their links: the image's, not ours)
if grep -i -E '^(W|E):|warning|error' "$out/apt-install.log" | grep -v -i -E 'libgpg-error|liberror|skip creation of /usr/share/man'; then echo "FAIL: apt printed warnings"; fail=1; fi
check dpkg -s tubeless
check test -n "$(ldconfig -p | grep libmpv)"
check desktop-file-validate /usr/share/applications/dev.tubeless.app.desktop
check appstreamcli validate --no-net --explain /usr/share/metainfo/dev.tubeless.app.metainfo.xml
check test -s /usr/share/icons/hicolor/512x512/apps/dev.tubeless.app.png
check test -x "$(readlink -f /usr/bin/tubeless)"
exe=$(dpkg -L tubeless | grep -E '/bin/Tubeless$' | head -1)
xvfb-run -a -s "-screen 0 1440x960x24" "$here/desktop.sh" "$exe" "$out/deb" || fail=1
apt-get remove -y -q tubeless >/dev/null 2>&1; check test ! -e /usr/share/applications/dev.tubeless.app.desktop
[ $fail = 0 ] && echo "ubuntu 24.04: ALL PASS" || echo "ubuntu 24.04: FAILURES above"
exit $fail
