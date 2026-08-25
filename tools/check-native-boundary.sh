#!/usr/bin/env bash
set -euo pipefail

expected=$(printf '%s\n' \
    src/native/AospJniBridge.cpp \
    src/native/AospParcelAbi.cpp \
    src/native/ArtzigGpuPipeline.cpp \
    src/native/HardwareBufferRender.cpp \
    src/native/HwuiRenderProxyBridge.cpp)
actual=$(rg --files src -g '*.cpp' -g '*.cc' -g '*.cxx' | sort)

if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected application C++ source. Keep C++ limited to the documented HWUI/Skia/AOSP ABI island." >&2
    diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
    exit 1
fi

if rg -n 'azk_audio\.cpp|azk_codec_jni\.cpp|azk_entry\.cpp|skia_shim' \
        build.zig build src README.md >/dev/null; then
    echo "A removed native implementation is still referenced." >&2
    exit 1
fi
