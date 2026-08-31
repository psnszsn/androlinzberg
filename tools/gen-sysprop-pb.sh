#!/usr/bin/env bash
# gen-sysprop-pb.sh <out-dir> — generate sysprop.pb.{cc,h} from the sysprop tool's
# own sysprop.proto, using the vendored aprotoc prebuilt (via the ELF-loader shim).
# The sysprop_java host tool (build/sysprop.zig) compiles the generated .cc.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(realpath -m "${1:?usage: gen-sysprop-pb.sh <out-dir>}")
PROTOC="$APP/artzig/out/host/linux-x86/bin/aprotoc"
ST="$APP/artzig/vendor/system/tools/sysprop"
rm -rf "$OUT"; mkdir -p "$OUT"
mapfile -t RUN < <(bash "$APP/tools/aosp-run.sh" "$PROTOC")
"${RUN[@]}" -I"$ST" --cpp_out="$OUT" "$ST/sysprop.proto"
echo "== $OUT"
