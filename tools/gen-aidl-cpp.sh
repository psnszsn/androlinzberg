#!/usr/bin/env bash
# gen-aidl-cpp.sh <out-dir> — C++ headers for the libs/input enum AIDL
# interfaces (aidl --lang=cpp from the AOSP prebuilt). Driven by build.zig; the
# island includes <out-dir>/gen. On NixOS, use the host toolchain's ELF loader.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
AZ="$APP/artzig"
OUT=$(realpath -m "${1:-$APP/.native-cache/aidl-cpp}")
mkdir -p "$OUT/gen" "$OUT/src"
AIDL="$AZ/vendor/prebuilts/sdk/tools/linux/bin/aidl"
AIDL_RUN=("$AIDL")
if ! "$AIDL" --help >/dev/null 2>&1; then
  HOST_LOADER=$(cc -print-file-name=ld-linux-x86-64.so.2)
  [ -x "$HOST_LOADER" ] || {
    echo "cannot run AOSP aidl and no host ELF loader was found" >&2
    exit 1
  }
  AIDL_RUN=("$HOST_LOADER" "$AIDL")
fi
for a in android/os/IInputConstants.aidl android/os/InputConfig.aidl \
         android/os/MotionEventFlag.aidl android/os/PointerIconType.aidl \
         android/os/InputEventInjectionSync.aidl android/os/InputEventInjectionResult.aidl; do
  [ -f "$AZ/extra/frameworks-native/libs/input/$a" ] || { echo "no aidl: $a" >&2; exit 1; }
  "${AIDL_RUN[@]}" --lang=cpp -I "$AZ/extra/frameworks-native/libs/input" \
    -h "$OUT/gen" -o "$OUT/src" "$AZ/extra/frameworks-native/libs/input/$a"
done
echo "== $OUT/gen"
