#!/usr/bin/env bash
# Build a pruned source tree containing only what `zig build dist` needs, per
# build/gen/vendor-manifest.txt (from tools/prune-vendor.py). A consumer unpacks
# this next to the artzig repo instead of the 36 GB AOSP checkout.
#
#   tools/make-dist-tarball.sh <out-dir>       # populate a pruned tree
#   tools/make-dist-tarball.sh <out-dir> --tar # also write <out-dir>.tar.zst
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
OUT=$(realpath -m "${1:?usage: make-dist-tarball.sh <out-dir> [--tar]}")
MANIFEST=build/gen/vendor-manifest.txt
[ -f "$MANIFEST" ] || python3 tools/prune-vendor.py
rm -rf "$OUT"; mkdir -p "$OUT"

# repo files (everything tracked by git: build.zig, tools/, src/, patches/, build/gen)
git ls-files > "$OUT/.repo-files"
rsync -a --files-from="$OUT/.repo-files" . "$OUT/"
rm -f "$OUT/.repo-files"

# vendor/extra per the manifest
while read -r kind path; do
  case "$kind" in
    '#'*|'') continue;;
    F) mkdir -p "$OUT/$(dirname "$path")"; cp -a "$path" "$OUT/$path";;
    D) mkdir -p "$OUT/$path"; rsync -a --exclude=.git "$path/" "$OUT/$path/" 2>/dev/null || true;;
    H) mkdir -p "$OUT/$path"; rsync -a -m --copy-unsafe-links --exclude='.git' \
         --include='*/' --include='*.h' --include='*.hpp' --include='*.hh' \
         --include='*.inc' --include='*.inl' --include='*.def' --include='*.S' --include='*.td' --include='*.c' --include='*.cc' --include='*.cpp' \
         --include='*.modulemap' --exclude='*' "$path/" "$OUT/$path/" 2>/dev/null || true;;
  esac
done < "$MANIFEST"

echo "pruned tree: $(du -sh "$OUT" | cut -f1) at $OUT"
if [ "${2:-}" = "--tar" ]; then
  tar -C "$(dirname "$OUT")" -cf - "$(basename "$OUT")" | zstd -19 -T0 -o "$OUT.tar.zst"
  echo "tarball: $(du -h "$OUT.tar.zst" | cut -f1) at $OUT.tar.zst"
fi
