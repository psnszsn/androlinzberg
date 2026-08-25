#!/usr/bin/env bash
# Assemble a worktree-local ART runtime by overlaying a rebuilt libart.so on
# the installed runtime. Everything except libart.so remains a symlink.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 BASE_RUNTIME LOCAL_LIBART OUTPUT_RUNTIME" >&2
  exit 2
fi

BASE_RUNTIME=$(readlink -f "$1")
LOCAL_LIBART=$(readlink -f "$2")
OUTPUT_RUNTIME=$(realpath -m "$3")

if [ ! -f "$BASE_RUNTIME/runtime.env" ] || [ ! -f "$LOCAL_LIBART" ]; then
  echo "invalid ART runtime or libart.so" >&2
  exit 1
fi

mkdir -p "$OUTPUT_RUNTIME/lib64"

while IFS= read -r -d '' entry; do
  name=${entry##*/}
  [ "$name" = lib64 ] && continue
  ln -sfn "$entry" "$OUTPUT_RUNTIME/$name"
done < <(find "$BASE_RUNTIME" -mindepth 1 -maxdepth 1 -print0)

while IFS= read -r -d '' entry; do
  name=${entry##*/}
  [ "$name" = libart.so ] && continue
  ln -sfn "$entry" "$OUTPUT_RUNTIME/lib64/$name"
done < <(find "$BASE_RUNTIME/lib64" -mindepth 1 -maxdepth 1 -print0)

OUTPUT_LIBART=$OUTPUT_RUNTIME/lib64/libart.so
if [ ! -f "$OUTPUT_LIBART" ] || [ "$LOCAL_LIBART" -nt "$OUTPUT_LIBART" ]; then
  cp --reflink=auto "$LOCAL_LIBART" "$OUTPUT_LIBART.tmp"
  patchelf --set-rpath \
    '$ORIGIN/:$ORIGIN/lib64:$ORIGIN/../lib64:$ORIGIN/../../lib64:$ORIGIN/../../../lib64' \
    "$OUTPUT_LIBART.tmp"
  mv "$OUTPUT_LIBART.tmp" "$OUTPUT_LIBART"
fi
