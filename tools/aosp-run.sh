#!/usr/bin/env bash
# aosp-run.sh <tool> — print the argv prefix for running a vendored AOSP prebuilt.
#
# The vendored generators (aidl, aconfig, stats-log-api-gen, protoc, …) are
# dynamically-linked generic-linux ELFs whose PT_INTERP is /lib64/ld-linux-x86-64.so.2,
# which does not exist on NixOS, so they cannot exec directly. Running them through
# the host ELF loader works regardless (the loader execs the binary itself), so the
# heuristic is safe: if the tool cannot answer --help it is run through the loader —
# and invoking a natively-runnable binary through the loader is also valid.
#
# Prints one argv element per line (the tool, or the loader then the tool). Usage:
#   mapfile -t RUN < <("$(dirname "$0")/aosp-run.sh" "$tool")
#   "${RUN[@]}" <args...>
set -euo pipefail

tool="${1:?usage: aosp-run.sh <tool>}"
[ -x "$tool" ] || { echo "aosp-run: not an executable: $tool" >&2; exit 1; }

if "$tool" --help >/dev/null 2>&1; then
    printf '%s\n' "$tool"
    exit 0
fi

loader=$(cc -print-file-name=ld-linux-x86-64.so.2)
[ -x "$loader" ] || { echo "aosp-run: cannot exec $tool and no host ELF loader found" >&2; exit 1; }
printf '%s\n%s\n' "$loader" "$tool"
