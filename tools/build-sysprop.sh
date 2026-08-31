#!/usr/bin/env bash
# build-sysprop.sh <sysprop_java-exe> <out-dir> — run the sysprop_java host tool
# (built by build/sysprop.zig) over the framework .sysprop declarations, emitting
# the generated accessor classes (android.sysprop.*) into <out-dir>. Wired into
# build/java.zig via SYSPROP=; replaces the hand-written sysprop stubs.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
TOOL=$(realpath "${1:?usage: build-sysprop.sh <exe> <out-dir>}")
OUT=$(realpath -m "${2:?usage: build-sysprop.sh <exe> <out-dir>}")
LIST="$APP/tools/sysprop-list.txt"
rm -rf "$OUT"; mkdir -p "$OUT"
while IFS='|' read -r scope rel; do
    [ -z "$scope" ] && continue
    case "$scope" in \#*) continue ;; esac
    src="$APP/$rel"
    [ -f "$src" ] || { echo "missing sysprop: $rel" >&2; exit 1; }
    "$TOOL" --scope "$scope" --java-output-dir "$OUT" "$src"
done < "$LIST"
echo "== $OUT"
