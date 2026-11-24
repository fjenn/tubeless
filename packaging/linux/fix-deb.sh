#!/usr/bin/env bash
# CI (Linux runner): two fixes to the .deb jpackage makes (release.yml, smoke.yml), in place:
#  - its postinst runs `xdg-desktop-menu install` under `set -e`, so the whole install fails ("No writable
#    system menu directory found", exit 3) on systems without /etc/xdg/menus/applications-merged (servers with a
#    desktop added later, minimal images, the GitHub runner): the menu entry is now best effort
#  - the player needs the system's libmpv, which jpackage can't see (JNA loads it): Depends on libmpv2 | libmpv1
#   packaging/linux/fix-deb.sh Tubeless-x.y.z-linux-amd64.deb
set -euo pipefail
deb=$(cd "$(dirname "${1:?usage: fix-deb.sh <file.deb>}")" && pwd)/$(basename "$1")
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
dpkg-deb -R "$deb" "$work/p"
for f in postinst prerm; do
  [ -f "$work/p/DEBIAN/$f" ] && sed -i -E 's/^([[:space:]]*xdg-(desktop-menu|mime|icon-resource) [^|]*[^|[:space:]])[[:space:]]*$/\1 || true/' "$work/p/DEBIAN/$f"
done
ctl="$work/p/DEBIAN/control"
if grep -q '^Depends:' "$ctl"; then sed -i -E '/^Depends:/ s/[[:space:]]*$/, libmpv2 | libmpv1/' "$ctl"
else echo 'Depends: libmpv2 | libmpv1' >> "$ctl"; fi
grep -E '^(Package|Version|Depends):' "$ctl"; grep -h 'xdg-' "$work/p/DEBIAN/postinst" "$work/p/DEBIAN/prerm" 2>/dev/null || true
dpkg-deb --root-owner-group -Zxz -b "$work/p" "$deb" >/dev/null
