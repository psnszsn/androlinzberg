#!/usr/bin/env bash
# build-proto.sh <protoc-gen-javastream-exe> <out-dir> — generate the framework
# *Proto stream constant classes by running the vendored aprotoc with our
# protoc-gen-javastream plugin (build/javastream.zig) over the .proto files in
# tools/proto-list.txt. Wired into build/java.zig via PROTO=; replaces the
# hand-written *Proto stubs. The generated classes are pure field-id constants
# consumed by the reused AOSP android.util.proto.ProtoOutputStream runtime.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
PLUGIN=$(realpath "${1:?usage: build-proto.sh <plugin-exe> <out-dir>}")
OUT=$(realpath -m "${2:?usage: build-proto.sh <plugin-exe> <out-dir>}")
FB="$APP/artzig/extra/frameworks-base"
PB="$APP/artzig/vendor/external/protobuf/src"
PROTOC="$APP/artzig/out/host/linux-x86/bin/aprotoc"
LIST="$APP/tools/proto-list.txt"

rm -rf "$OUT"; mkdir -p "$OUT"
# Stage an AOSP-checkout-shaped include root: frameworks/base -> the vendored tree,
# so the protos' `import "frameworks/base/..."` statements resolve.
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/frameworks"
ln -s "$FB" "$STAGE/frameworks/base"

mapfile -t PROTOS < <(grep -vE '^\s*#|^\s*$' "$LIST")
mapfile -t RUN < <(bash "$APP/tools/aosp-run.sh" "$PROTOC")
"${RUN[@]}" --plugin=protoc-gen-javastream="$PLUGIN" \
    --javastream_out="$OUT" -I"$STAGE" -I"$PB" "${PROTOS[@]}"
echo "== $OUT"
