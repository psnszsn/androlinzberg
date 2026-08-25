#!/usr/bin/env bash
# mkbootjar.sh <out.jar> <dex-dir> <vendor-dir> <resfile|->
# Boot jar = classes*.dex + java resources (resfile: alternating "base-dir" / "file" lines,
# both vendor-relative; entry name = file path relative to base-dir).
set -euo pipefail
OUT=$(realpath -m "$1"); DEX=$(realpath "$2"); VENDOR=$(realpath "$3"); RES=${4:--}
rm -f "$OUT"
jar --create --file "$OUT" -C "$DEX" .
if [ "$RES" != "-" ]; then
  while IFS= read -r base && IFS= read -r file; do
    jar --update --file "$OUT" -C "$VENDOR/$base" "${file#"$base"/}"
  done < "$RES"
fi
