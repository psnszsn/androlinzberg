#!/usr/bin/env bash
# ABI-conformance check for libbinder_ndk: assert the hosted Zig implementation
# exports (at least) every A* symbol the x86_64 prebuilt libbinder_ndk.so does.
# A generated ndk-cpp/AIDL client linked against the prebuilt's soname must find
# every symbol it references at load time, so a missing export is a hard error.
#
# Usage: check-ndk-abi.sh <installed-libbinder_ndk.so> <reference-libbinder_ndk.so>
set -euo pipefail

MINE=${1:?usage: check-ndk-abi.sh <installed-libbinder_ndk.so> <reference-libbinder_ndk.so>}
PRE=${2:?usage: check-ndk-abi.sh <installed-libbinder_ndk.so> <reference-libbinder_ndk.so>}

if [[ ! -f "$PRE" ]]; then
    echo "check-ndk-abi: prebuilt not found: $PRE" >&2
    exit 1
fi
if [[ ! -f "$MINE" ]]; then
    echo "check-ndk-abi: installed library not found: $MINE" >&2
    exit 1
fi

# Strip @@LIBBINDER_NDK* version suffixes before comparing; the hosted build
# resolves by bare name (the bionic host-dlopen path uses no version script).
syms() { nm -D --defined-only "$1" 2>/dev/null | awk '{print $3}' | sed 's/@@.*//' | grep -E '^A' | sort -u; }

pre=$(mktemp) mine=$(mktemp)
trap 'rm -f "$pre" "$mine"' EXIT
syms "$PRE" > "$pre"
syms "$MINE" > "$mine"

missing=$(comm -23 "$pre" "$mine" || true)
if [[ -n "$missing" ]]; then
    echo "check-ndk-abi: FAIL — $(wc -l <<<"$missing") prebuilt symbol(s) not exported by $MINE:" >&2
    sed 's/^/  /' <<<"$missing" >&2
    exit 1
fi

echo "check-ndk-abi: ok — all $(wc -l < "$pre") prebuilt A* symbols are exported ($(wc -l < "$mine") total)"
