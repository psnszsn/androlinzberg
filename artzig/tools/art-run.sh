#!/usr/bin/env bash
# Run a dex file on the freshly built host ART, e.g.
#   tools/art-run.sh -cp classes.dex Hello
# Uses ART's own host wrapper (out/host/linux-x86/bin/art), which sets
# ANDROID_ROOT/ANDROID_ART_ROOT/ANDROID_I18N_ROOT/ANDROID_TZDATA_ROOT from its
# own location. Only ANDROID_DATA (dalvik-cache parent) comes from us.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_DATA=${ANDROID_DATA:-${XDG_CACHE_HOME:-$HOME/.cache}/artzig}
mkdir -p "$ANDROID_DATA/dalvik-cache/x86_64"
exec nix run "$ROOT#aosp-env" -- -c "exec '$ROOT/out/host/linux-x86/bin/art' $*"
