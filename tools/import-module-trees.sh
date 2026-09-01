#!/usr/bin/env bash
# import-module-trees.sh — provision the module source trees under artzig/extra
# that the code generators need beyond the master-art manifest, pinned to the
# android-16.0.0_r1 tag (the release cut of the ~Mar 2025 main window the other
# vendored trees are pinned to). Also widens the sparse cones of the existing
# frameworks-base/-native clones to the declaration dirs the generators read.
# Idempotent; provenance/pins.lock records each tree's shallow-root sha.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
EXTRA="$APP/artzig/extra"
TAG=android-16.0.0_r1
AOSP=https://android.googlesource.com

import() { # <name> <repo> <sparse-dir...>
    local name="$1" repo="$2"; shift 2
    local dest="$EXTRA/$name"
    if [ ! -d "$dest/.git" ]; then
        git clone --filter=blob:none --depth 1 --no-checkout \
            --single-branch --branch "$TAG" "$AOSP/$repo" "$dest"
    fi
    git -C "$dest" sparse-checkout set --cone "$@"
    git -C "$dest" checkout -q
}

import frameworks-av  platform/frameworks/av          camera media/aconfig media/audio/aconfig
import opt-telephony  platform/frameworks/opt/telephony flags
import modules-uwb    platform/packages/modules/Uwb   ranging/flags
import modules-statsd platform/packages/modules/StatsD framework/java
import proto-logging  platform/frameworks/proto_logging stats

# Declaration dirs of the sparse-hidden platform aconfig packages + the
# generator tool sources (streaming_proto, sysprop include/).
git -C "$EXTRA/frameworks-base" sparse-checkout add \
    android-sdk-flags nfc-non-updatable/flags \
    core/java/android/companion/virtual core/java/android/credentials \
    core/java/android/permission core/java/android/security \
    core/java/android/tracing services/autofill services/backup \
    proto/src/metrics_constants \
    tools/streaming_proto
git -C "$EXTRA/frameworks-native" sparse-checkout add services/surfaceflinger libs/permission/aidl
git -C "$APP/artzig/vendor/system/tools/sysprop" checkout HEAD -- include 2>/dev/null || true
echo "module trees provisioned"
