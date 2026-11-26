#!/usr/bin/env bash
# CI (Linux runner): makes the .deb jpackage builds install cleanly on a desktop (release.yml, smoke.yml), in place:
#  - its postinst/prerm run `xdg-desktop-menu` under `set -e`, which fails the whole install ("No writable system
#    menu directory found", exit 3) on systems without /etc/xdg/menus/applications-merged; those calls go, and the
#    package carries its menu entry itself: /usr/share/applications/dev.tubeless.app.desktop (the same id as the
#    AppStream data in /usr/share/metainfo), its icon in /usr/share/icons/hicolor, and /usr/bin/tubeless
#  - the player needs the system's libmpv, which jpackage can't see (JNA loads it): Depends on libmpv2 | libmpv1
#  - the sign-in window (resources/signin-helper) is a WebKitGTK web view: Depends on libwebkit2gtk-4.1-0
#    (it brings libjavascriptcoregtk-4.1 and the WebKit helper processes)
#   packaging/linux/fix-deb.sh Tubeless-x.y.z-linux-amd64.deb [owner/repo]
set -euo pipefail
deb=$(cd "$(dirname "${1:?usage: fix-deb.sh <file.deb> [owner/repo]}")" && pwd)/$(basename "$1")
repo=${2:-${GITHUB_REPOSITORY:-fjenn/tubeless}}
here=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
dpkg-deb -R "$deb" "$work/p"
p="$work/p"
exe=$(cd "$p" && find . -path '*/bin/Tubeless' -type f | head -1 | sed 's|^\.||')
[ -n "$exe" ] || { echo "fix-deb: no bin/Tubeless in the package" >&2; exit 1; }
for f in postinst prerm; do
  [ -f "$p/DEBIAN/$f" ] && sed -i -E '/^[[:space:]]*xdg-(desktop-menu|mime|icon-resource) /d' "$p/DEBIAN/$f"
done
# jpackage's own entry (lib/tubeless-Tubeless.desktop) is no longer installed anywhere; ours replaces it
mkdir -p "$p/usr/share/applications" "$p/usr/share/icons/hicolor/512x512/apps" "$p/usr/share/metainfo" "$p/usr/bin"
sed "s|^Exec=.*|Exec=$exe|" "$here/flatpak/dev.tubeless.app.desktop" > "$p/usr/share/applications/dev.tubeless.app.desktop"
install -m 644 "$here/flatpak/icon.png" "$p/usr/share/icons/hicolor/512x512/apps/dev.tubeless.app.png"
version=$(sed -n 's/^Version: //p' "$p/DEBIAN/control")
cp "$here/flatpak/dev.tubeless.app.metainfo.xml" "$p/usr/share/metainfo/dev.tubeless.app.metainfo.xml"
"$here/fill-metadata.sh" "$repo" "$version" "$p/usr/share/metainfo/dev.tubeless.app.metainfo.xml"
ln -sf "$exe" "$p/usr/bin/tubeless"
# the sign-in window's program comes out of jpackage without its exec bit
find "$p" -path '*/resources/signin-helper' -type f -exec chmod 755 {} +
ctl="$p/DEBIAN/control"
deps='libmpv2 | libmpv1, libwebkit2gtk-4.1-0'
if grep -q '^Depends:' "$ctl"; then sed -i -E "/^Depends:/ s/[[:space:]]*\$/, $deps/" "$ctl"
else echo "Depends: $deps" >> "$ctl"; fi
grep -E '^(Package|Version|Depends):' "$ctl"
grep -h 'xdg-' "$p/DEBIAN/postinst" "$p/DEBIAN/prerm" 2>/dev/null && { echo "fix-deb: xdg calls left" >&2; exit 1; }
dpkg-deb --root-owner-group -Zxz -b "$p" "$deb" >/dev/null
