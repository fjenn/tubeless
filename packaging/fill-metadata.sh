#!/usr/bin/env bash
# Fills the placeholders in packaging metadata at release time, so the files in git need no repository
# name while there is none:  @REPO@ (owner/name), @VERSION@, @DATE@ (YYYY-MM-DD).
#   packaging/fill-metadata.sh owner/name 1.9.0 FILE...
set -euo pipefail
repo=${1:?usage: fill-metadata.sh owner/name version FILE...}; version=${2:?}; shift 2
date=$(date -u +%F)
for f in "$@"; do
  sed -i.bak -e "s|@REPO@|$repo|g" -e "s|@VERSION@|$version|g" -e "s|@DATE@|$date|g" "$f" && rm -f "$f.bak"
  if grep -n '@[A-Z]*@' "$f"; then echo "fill-metadata: placeholders left in $f" >&2; exit 1; fi
done
