#!/usr/bin/env bash
# extract-apex-etc.sh <foo.apex> <outdir> — copy the /etc tree out of a vendored
# APEX (zip containing an ext4 apex_payload.img). Needs debugfs (e2fsprogs).
set -euo pipefail
APEX=$(realpath "$1"); OUT=$(realpath -m "$2")
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
unzip -q "$APEX" apex_payload.img -d "$WORK"
rm -rf "$OUT"; mkdir -p "$OUT"
debugfs -R "rdump /etc $OUT" "$WORK/apex_payload.img" 2>/dev/null
[ -d "$OUT/etc" ] || { echo "extract-apex-etc: no /etc in $APEX" >&2; exit 1; }
