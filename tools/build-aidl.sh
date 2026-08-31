#!/usr/bin/env bash
# build-aidl.sh <out-dir> — generate java from the AIDL interfaces listed in
# tools/aidl-list.txt using the vendored aidl tool (one aosp-env FHS run).
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
ROOT="$APP/artzig"
OUT=$(realpath -m "${1:-$APP/.cache/aidl-java}")
LIST="$APP/tools/aidl-list.txt"
FB="$ROOT/extra/frameworks-base"
FN_INPUT="$ROOT/extra/frameworks-native/libs/input"
mkdir -p "$OUT"
AIDL="$ROOT/vendor/prebuilts/sdk/tools/linux/bin/aidl"
mapfile -t AIDL_RUN < <(bash "$APP/tools/aosp-run.sh" "$AIDL")
COMPAT="$OUT/compat"
rm -rf "$COMPAT"
while IFS= read -r compat; do
  rel="${compat#"$APP/aidl-compat/"}"
  if [ ! -f "$FB/core/java/$rel" ] && [ ! -f "$FB/graphics/java/$rel" ] &&
     [ ! -f "$FB/media/java/$rel" ]; then
    mkdir -p "$COMPAT/$(dirname "$rel")"
    cp "$compat" "$COMPAT/$rel"
  fi
done < <(find "$APP/aidl-compat" -type f -name '*.aidl' -print)
while IFS= read -r rel; do
  [ -z "$rel" ] && continue; case "$rel" in \#*) continue;; esac
  src="$FB/core/java/$rel"
  if [ ! -f "$src" ]; then
    src="$FN_INPUT/$rel"
  fi
  if [ ! -f "$src" ]; then
    # Repository-owned interfaces (hosted test and host-seam AIDL).
    src="$COMPAT/$rel"
  fi
  [ -f "$src" ] || { echo "missing aidl: $rel" >&2; exit 1; }
  "${AIDL_RUN[@]}" -I"$FB/core/java" -I"$FB/graphics/java" -I"$COMPAT" \
    -I"$FB/media/java" -I"$FN_INPUT" -o "$OUT" "$src"
done < "$LIST"
echo "== $OUT"
