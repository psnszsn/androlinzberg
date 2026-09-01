#!/usr/bin/env bash
# build-nano.sh <protoc-gen-javanano-exe> <out-dir> — generate the javanano
# message classes listed in tools/nano-list.txt (repo-relative .proto paths;
# each is compiled with -I set to its own directory, matching the Soong
# java-nano proto rule for metrics_constants). Wired into build/java.zig via
# NANO=; the nano runtime (com.google.protobuf.nano.*) is the reused vendored
# source under external/protobuf/javanano/src/main/java.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
PLUGIN=$(realpath "${1:?usage: build-nano.sh <plugin-exe> <out-dir>}")
OUT=$(realpath -m "${2:?usage: build-nano.sh <plugin-exe> <out-dir>}")
LIST="$APP/tools/nano-list.txt"
rm -rf "$OUT"; mkdir -p "$OUT"
mapfile -t RUN < <(bash "$APP/tools/aosp-run.sh" "$APP/artzig/out/host/linux-x86/bin/aprotoc")
while IFS= read -r rel; do
    case "$rel" in \#*|'') continue ;; esac
    src="$APP/$rel"
    [ -f "$src" ] || { echo "missing proto: $rel" >&2; exit 1; }
    "${RUN[@]}" --plugin=protoc-gen-javanano="$PLUGIN" \
        --javanano_out="$OUT" -I"$(dirname "$src")" "$src"
done < "$LIST"
echo "== $OUT"
