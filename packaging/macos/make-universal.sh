#!/usr/bin/env bash
# Merges the Apple silicon and the Intel Tubeless.app into one universal Tubeless.app (unsigned: sign it
# with sign-dmg.sh afterwards, which re-signs every Mach-O file, inside jars too).
#
#   packaging/macos/make-universal.sh <arm64 Tubeless.app> <x64 Tubeless.app> <out Tubeless.app>
#   packaging/macos/make-universal.sh --check <Tubeless.app>      only the architecture check (step 4)
#
# The "unify" approach of Mozilla's build/macosx/universal and @electron/universal, made for a
# Compose Desktop (jpackage) app:
#   1. both apps must come from the same sources and the same JDK build (Info.plist, runtime/release equal);
#   2. libmpv + its Homebrew libraries (Contents/app/resources) are built on two runners whose Homebrew
#      versions differ (libavcodec.63.1.102 vs .101, libcrypto.4 vs .3, ...): each Intel library takes the
#      name of its Apple silicon counterpart (same name up to the first ".<digit>"), with its id and every
#      reference rewritten (install_name_tool), so each pair can be joined by lipo; every slice still
#      loads its own versions;
#   3. every file in either app: identical → kept; Mach-O in both → lipo -create; jars → merged entry by
#      entry (Mach-O entries joined by lipo, entries only in one added); arch-specific jars (skiko,
#      Javet's Node.js) and libskiko-macos-<arch>.dylib → both kept, Tubeless.cfg's classpath gets the
#      union. Allowed to differ beyond that: lib/modules (the jlink image) only in
#      jdk/internal/util/PlatformProps (checked; in JDK 21 only jdk.internal.util.Architecture reads it,
#      and nothing reads that), lib/shaders.metallib (GPU code, the same for both), the signatures.
#      Anything else that differs stops the merge;
#   4. check: every Mach-O file (and every one inside a jar) holds arm64 + x86_64, unless its path names
#      its one architecture (skiko, JNA, Javet: per-arch files with the other arch's twin next to it) or
#      it is a libmpv library only one arch's Homebrew build uses (listed; only that arch loads it).
set -euo pipefail
die() { echo "make-universal: $*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
is_macho() { file -b "$1" | grep -q 'Mach-O'; }
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}
# arch named in a path: per-arch natives (libskiko-macos-arm64.dylib, darwin-aarch64/, javet ...-x86_64)
arch_named() { printf '%s' "$1" | grep -qiE '(arm64|aarch64|x86[-_]64|[-_/]x64)'; }
# "libavcodec.63.1.102.dylib" → "libavcodec", "libluajit-5.1.2.1.123.dylib" → "libluajit-5"
family() { printf '%s' "$1" | sed -E 's/\.[0-9].*$//; s/\.dylib$//'; }
# jpackage's jar names end in -<hash>.jar; the hash changes when the jar's content does
lookup() { awk -F'\t' -v k="$2" '$1 == k { print $2; exit }' "$1"; }
jar_base() { printf '%s' "$1" | sed -E 's/-[0-9a-f]{20,40}\.jar$//'; }

check() {
  local app=$1 bad=0 n=0 named=0 tmp
  # the libmpv libraries only one arch uses, recorded by the merge
  local only=""; [ -f "$app/Contents/app/resources/.single-arch" ] && only=$(cat "$app/Contents/app/resources/.single-arch")
  check_one() { # <file> <label>
    local a; a=$(lipo -archs "$1" 2>/dev/null || echo "?")
    n=$((n + 1))
    case " $a " in *" arm64 "*) case " $a " in *" x86_64 "*) return ;; esac ;; esac
    if arch_named "$2"; then named=$((named + 1)); return; fi
    if printf '%s\n' "$only" | grep -qxF "$(basename "$2")"; then say "  one arch by design ($a): $2"; return; fi
    say "  SINGLE-ARCH ($a): $2"; bad=1
  }
  while IFS= read -r -d '' f; do
    if is_macho "$f"; then check_one "$f" "${f#"$app"/}"; fi
  done < <(find "$app/Contents" -type f -print0)
  while IFS= read -r -d '' jar; do
    unzip -Z1 "$jar" 2>/dev/null | grep -q -E '\.(dylib|jnilib)$|(^|/)[^./]+$' || continue
    tmp=$(mktemp -d)
    unzip -q -o "$jar" -d "$tmp" -x 'META-INF/*' 2>/dev/null || true
    while IFS= read -r -d '' f; do
      if is_macho "$f"; then check_one "$f" "$(basename "$jar")!${f#"$tmp"/}"; fi
    done < <(find "$tmp" -type f -print0)
    rm -rf "$tmp"
  done < <(find "$app/Contents" -name '*.jar' -print0)
  say "$n Mach-O files: $((n - named)) universal or listed, $named per-arch by name"
  [ $bad = 0 ] || die "single-arch Mach-O files left"
}

if [ "${1:-}" = --check ]; then check "${2:?usage: make-universal.sh --check <app>}"; exit 0; fi
A=${1:?usage: make-universal.sh <arm64.app> <x64.app> <out.app>}
X=${2:?usage: make-universal.sh <arm64.app> <x64.app> <out.app>}
OUT=${3:?usage: make-universal.sh <arm64.app> <x64.app> <out.app>}
for d in "$A" "$X"; do [ -d "$d/Contents/MacOS" ] || die "$d is not an app bundle"; done
[ -e "$OUT" ] && die "$OUT exists"
A=$(cd "$A" && pwd); X=$(cd "$X" && pwd)
lipo -archs "$A/Contents/MacOS/"* | grep -qw arm64 || die "$A is not the Apple silicon app"
lipo -archs "$X/Contents/MacOS/"* | grep -qw x86_64 || die "$X is not the Intel app"

# 1. the same app and the same JDK
cmp -s "$A/Contents/Info.plist" "$X/Contents/Info.plist" || die "Info.plist differs (not the same version?)"
RA="$A/Contents/runtime/Contents/Home"; RX="$X/Contents/runtime/Contents/Home"
cmp -s "$RA/release" "$RX/release" || die "the bundled JDKs differ: $(grep JAVA_VERSION= "$RA/release") vs $(grep JAVA_VERSION= "$RX/release")"

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
ditto "$A" "$OUT"
OUT=$(cd "$OUT" && pwd)
ditto "$X" "$WORK/x.app"; X="$WORK/x.app"   # a copy: the Intel libraries are renamed in place
find "$OUT" "$X" -name _CodeSignature -type d -prune -exec rm -rf {} +
rm -f "$OUT/Contents/CodeResources" "$X/Contents/CodeResources" "$OUT/Contents/runtime/Contents/CodeResources" "$X/Contents/runtime/Contents/CodeResources"

# 2. libmpv's libraries: Intel names → Apple silicon names
RES=Contents/app/resources
single=()
if [ -d "$X/$RES" ]; then
  : > "$WORK/amap"; : > "$WORK/xren"
  for f in "$OUT/$RES"/*.dylib; do [ -e "$f" ] || continue; b=$(basename "$f"); printf '%s\t%s\n' "$(family "$b")" "$b" >> "$WORK/amap"; done
  for f in "$X/$RES"/*.dylib; do
    [ -e "$f" ] || continue; b=$(basename "$f")
    [ -e "$OUT/$RES/$b" ] && continue
    t=$(lookup "$WORK/amap" "$(family "$b")")
    if [ -n "$t" ] && [ ! -e "$X/$RES/$t" ]; then printf '%s\t%s\n' "$b" "$t" >> "$WORK/xren"; fi
  done
  if [ -s "$WORK/xren" ]; then
    say "renaming $(wc -l < "$WORK/xren" | tr -d ' ') Intel libraries to their Apple silicon names"
    for f in "$X/$RES"/*.dylib; do
      is_macho "$f" || continue
      chmod u+w "$f"
      args=()
      while read -r ref; do
        rb=$(basename "$ref")
        nb=$(lookup "$WORK/xren" "$rb"); [ -n "$nb" ] && args+=(-change "$ref" "$(dirname "$ref")/$nb")
      done < <(otool -L "$f" | tail -n +2 | awk '{print $1}')
      id=$(otool -D "$f" | tail -n +2 | head -1)
      ib=$(basename "${id:-x}")
      nb=$(lookup "$WORK/xren" "$ib"); [ -n "$id" ] && [ -n "$nb" ] && args+=(-id "$(dirname "$id")/$nb")
      # a changed library needs a valid signature again (ad hoc; sign-dmg.sh puts the Developer ID on)
      if [ ${#args[@]} -gt 0 ]; then install_name_tool "${args[@]}" "$f" 2>/dev/null; codesign --force -s - "$f" 2>/dev/null; fi
    done
    while IFS=$'\t' read -r b nb; do mv "$X/$RES/$b" "$X/$RES/$nb"; done < "$WORK/xren"
  fi
  # libraries one arch has and the other has no counterpart for: only that arch's libmpv loads them
  for f in "$OUT/$RES"/*.dylib "$X/$RES"/*.dylib; do
    [ -e "$f" ] || continue; b=$(basename "$f")
    [ -e "$OUT/$RES/$b" ] && [ -e "$X/$RES/$b" ] && continue
    single+=("$b")
  done
fi

# 3. every file
merge_jar() { # <arm64 jar (== out)> <x64 jar>
  local a=$1 x=$2 ja jx changed=() e
  if diff -q <(unzip -v "$a" | awk 'NF>=8 && $7 ~ /^[0-9a-f]{8}$/ {print $7, $8}' | sort) \
             <(unzip -v "$x" | awk 'NF>=8 && $7 ~ /^[0-9a-f]{8}$/ {print $7, $8}' | sort) >/dev/null; then return; fi
  ja=$(mktemp -d "$WORK/ja.XXXX"); jx=$(mktemp -d "$WORK/jx.XXXX")
  unzip -q -o "$a" -d "$ja"; unzip -q -o "$x" -d "$jx"
  while IFS= read -r -d '' f; do
    e=${f#"$jx"/}
    if [ ! -e "$ja/$e" ]; then mkdir -p "$(dirname "$ja/$e")"; cp -p "$f" "$ja/$e"; changed+=("$e"); continue; fi
    cmp -s "$f" "$ja/$e" && continue
    if is_macho "$f" && is_macho "$ja/$e"; then
      # the same archs (e.g. JNA's per-arch natives, only their signatures differ): keep one
      [ "$(lipo -archs "$f")" = "$(lipo -archs "$ja/$e")" ] && continue
      lipo -create "$ja/$e" "$f" -output "$ja/$e.u" && mv "$ja/$e.u" "$ja/$e"; changed+=("$e"); continue
    fi
    case "$e" in META-INF/MANIFEST.MF) continue ;; esac
    die "$(basename "$a"): $e differs between the arches"
  done < <(find "$jx" -type f -print0)
  if [ ${#changed[@]} -gt 0 ]; then
    unzip -Z1 "$a" | grep -qE '^META-INF/[^/]+\.(SF|RSA|EC|DSA)$' && die "$(basename "$a") is a signed jar and would change"
    (cd "$ja" && zip -q "$a" "${changed[@]}")
    say "  jar $(basename "$a"): ${#changed[@]} entries merged"
  fi
  rm -rf "$ja" "$jx"
}

cfg_extra=()  # classpath lines only the Intel app has
lipos=0; jars=0
APPDIR=Contents/app
# Intel jars whose name differs only by the hash: merged into the Apple silicon one
: > "$WORK/jpair"
for f in "$X/$APPDIR"/*.jar; do
  b=$(basename "$f"); [ -e "$OUT/$APPDIR/$b" ] && continue
  for g in "$OUT/$APPDIR"/*.jar; do
    gb=$(basename "$g")
    [ "$(jar_base "$gb")" = "$(jar_base "$b")" ] && [ ! -e "$X/$APPDIR/$gb" ] && { printf '%s\t%s\n' "$b" "$gb" >> "$WORK/jpair"; break; }
  done
done
while IFS= read -r -d '' f; do
  rel=${f#"$X"/}
  o="$OUT/$rel"
  b=$(basename "$rel")
  pb=""; [ "$(dirname "$rel")" = "$APPDIR" ] && pb=$(lookup "$WORK/jpair" "$b")
  if [ -n "$pb" ]; then
    merge_jar "$OUT/$APPDIR/$pb" "$f"; jars=$((jars + 1)); continue
  fi
  if [ ! -e "$o" ]; then
    mkdir -p "$(dirname "$o")"; cp -p "$f" "$o"
    case "$rel" in "$APPDIR"/*.jar) cfg_extra+=("app.classpath=\$APPDIR/$b") ;; esac
    continue
  fi
  cmp -s "$f" "$o" && continue
  if is_macho "$f" && is_macho "$o"; then
    lipo -create "$o" "$f" -output "$o.u"; chmod "$(stat -f %Lp "$o")" "$o.u"
    mv "$o.u" "$o"; lipos=$((lipos + 1)); continue
  fi
  case "$rel" in
    *.jar) merge_jar "$o" "$f"; jars=$((jars + 1)) ;;
    "$APPDIR"/*.cfg) ;;  # below
    Contents/runtime/Contents/Home/lib/shaders.metallib) ;;
    Contents/runtime/Contents/Home/lib/modules)
      ma="$WORK/ma"; mx="$WORK/mx"
      "${JAVA}jimage" extract --dir "$ma" "$o"; "${JAVA}jimage" extract --dir "$mx" "$f"
      d=$(diff -rq "$ma" "$mx" | sed "s#$WORK/##g" || true)
      [ "$d" = "Files ma/java.base/jdk/internal/util/PlatformProps.class and mx/java.base/jdk/internal/util/PlatformProps.class differ" ] \
        || die "lib/modules differs beyond PlatformProps: $(printf '%s\n' "$d" | head -5)"
      users=$(cd "$ma" && grep -rlaF -e 'jdk/internal/util/Architecture' -e 'jdk/internal/util/PlatformProps' . | grep -vE '^\./java\.base/jdk/internal/util/(Architecture|PlatformProps)\.class$' || true)
      [ -z "$users" ] || die "lib/modules: these read the build's CPU from PlatformProps: $users"
      rm -rf "$ma" "$mx" ;;
    *) die "$rel differs between the arches and is not Mach-O" ;;
  esac
done < <(find "$X/Contents" -type f -print0)

# Tubeless.cfg: the Apple silicon one plus the Intel-only jars, after the last classpath line
for cfg in "$OUT/$APPDIR"/*.cfg; do
  xc="$X/$APPDIR/$(basename "$cfg")"
  diff <(grep -v '^app.classpath=' "$cfg") <(grep -v '^app.classpath=' "$xc") >/dev/null || die "$(basename "$cfg") differs beyond the classpath"
  if [ ${#cfg_extra[@]} -gt 0 ]; then
    printf '%s\n' "${cfg_extra[@]}" > "$WORK/cfg-extra"
    /usr/bin/awk -v extra="$WORK/cfg-extra" '
      { lines[NR] = $0; if ($0 ~ /^app.classpath=/) last = NR }
      END { for (i = 1; i <= NR; i++) { print lines[i]; if (i == last) while ((getline l < extra) > 0) print l } }' "$cfg" > "$cfg.new"
    mv "$cfg.new" "$cfg"
  fi
done
[ ${#single[@]} -gt 0 ] && printf '%s\n' "${single[@]}" > "$OUT/$RES/.single-arch"
say "merged: $lipos Mach-O files joined by lipo, $jars jars compared, ${#cfg_extra[@]} Intel-only jars added to the classpath"
[ ${#single[@]} -gt 0 ] && say "libmpv libraries only one arch uses: ${single[*]}"

# 4. nothing single-arch left by accident
check "$OUT"
