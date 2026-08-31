#!/usr/bin/env bash
# build-logtags.sh <out-dir> — generate EventLogTags Java from the .logtags files
# listed in tools/logtags-list.txt, using the AOSP java-event-log-tags.py tool.
# Pure Python; no ELF-loader shim needed.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
ROOT="$APP/artzig"
OUT=$(realpath -m "${1:-$APP/.cache/logtags-java}")
FB="$ROOT/extra/frameworks-base"
TOOL="$ROOT/vendor/build/make/tools/java-event-log-tags.py"
LIST="$APP/tools/logtags-list.txt"

rm -rf "$OUT"
mkdir -p "$OUT"
while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    case "$rel" in \#*) continue ;; esac
    src="$FB/$rel"
    [ -f "$src" ] || { echo "missing logtags: $rel" >&2; exit 1; }
    # The generated class lands in the package named by the file's java_package.
    pkg=$(sed -n 's/^option java_package \([A-Za-z0-9_.]*\).*/\1/p' "$src" | head -1)
    [ -n "$pkg" ] || { echo "no java_package in $rel" >&2; exit 1; }
    base=$(basename "$src" .logtags)
    dir="$OUT/${pkg//.//}"
    mkdir -p "$dir"
    python3 "$TOOL" -o "$dir/$base.java" "$src"
done < "$LIST"
echo "== $OUT"
