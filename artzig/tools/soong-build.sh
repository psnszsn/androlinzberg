#!/usr/bin/env bash
# Build host ART targets with Soong inside the FHS env.
# Usage: tools/soong-build.sh [targets...]   (default: the ART host runtime set)
# Mirrors art/tools/buildbot-build.sh for the reduced master-art manifest.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(dalvikvm-host dex2oat-host libart-host libart-compiler-host libartbase-host libdexfile-host
           libprofile-host libsigchain-host libjavacore-host libopenjdk-host libopenjdkjvm-host libnativehelper-host
           core-oj-hostdex core-libart-hostdex okhttp-hostdex bouncycastle-hostdex apache-xml-hostdex
           conscrypt-host-hostdex libjavacrypto-host d8-host)
fi
exec nix run "$ROOT#aosp-env" -- -c "
  cd '$ROOT/vendor'
  export OUT_DIR=\${OUT_DIR:-$ROOT/out}
  export TARGET_PRODUCT=\${TARGET_PRODUCT:-silvermont}
  export TARGET_RELEASE=\${TARGET_RELEASE:-trunk_staging}
  export TARGET_BUILD_VARIANT=\${TARGET_BUILD_VARIANT:-eng}
  export SOONG_ALLOW_MISSING_DEPENDENCIES=true
  export TARGET_BUILD_UNBUNDLED=true
  export ALLOW_NINJA_ENV=true
  exec build/soong/soong_ui.bash --make-mode ${targets[*]}
"
