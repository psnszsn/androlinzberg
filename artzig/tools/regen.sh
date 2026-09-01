#!/usr/bin/env bash
# Regenerate build/gen/*.zig from the current Soong build (run after tools/soong-build.sh).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
I=$ROOT/out/soong/.intermediates
N=out/combined-silvermont.ninja
python3 tools/ninja2zig.py --name libartbase --ninja $N $I/art/libartbase/libartbase/linux_glibc_x86_64_shared/libartbase.so
python3 tools/ninja2zig.py --name art --ninja $N \
  $I/art/runtime/libart/linux_glibc_x86_64_shared/libart.so \
  $I/art/dalvikvm/dalvikvm/linux_glibc_x86_64/dalvikvm64 \
  $I/art/dex2oat/dex2oat/linux_glibc_x86_64/dex2oat64 \
  $(find $I/art/compiler -maxdepth 3 -name libart-compiler.so | head -1) \
  $I/libcore/libjavacore/linux_glibc_x86_64_shared/libjavacore.so \
  $I/libcore/libopenjdk/linux_glibc_x86_64_shared/libopenjdk.so \
  $I/libcore/libandroidio/linux_glibc_x86_64_shared/libandroidio.so \
  $I/art/openjdkjvm/libopenjdkjvm/linux_glibc_x86_64_shared/libopenjdkjvm.so \
  $I/external/conscrypt/libjavacrypto/linux_glibc_x86_64_shared/libjavacrypto.so \
  $I/libnativehelper/libnativehelper/linux_glibc_x86_64_shared/libnativehelper.so \
  $I/art/sigchainlib/libsigchain/linux_glibc_x86_64_shared/libsigchain.so \
  $I/art/test/generate-boot-image/generate-boot-image/linux_glibc_x86_64/generate-boot-image64
python3 tools/ninja2java.py
