#!/usr/bin/env bash
# build-res.sh [out-dir] — compile AOSP core/res into framework-res.apk with
# aapt2 and emit the android.R / com.android.internal.R java sources.
# Driven by build/java.zig (default out dir kept for manual use).
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
ROOT="$APP/artzig"
RES="$ROOT/extra/frameworks-base/core/res"
OUT=$(realpath -m "${1:-$APP/.cache/res}")
mkdir -p "$OUT"

FLAGS=$(grep -rhoE 'featureFlag\s*=\s*"!?[A-Za-z0-9_.]+"' "$RES/res" "$RES/AndroidManifest.xml" 2>/dev/null \
  | sed -E 's/.*"(!?)([A-Za-z0-9_.]+)"/\2:ro=true/' | sort -u | paste -sd,)
if true; then
  echo "== aapt2 compile"
  aapt2 compile --dir "$RES/res" --feature-flags "$FLAGS" -o "$OUT/compiled.zip.tmp"
  mv "$OUT/compiled.zip.tmp" "$OUT/compiled.zip"
fi
echo "== aapt2 link"
aapt2 link -o "$OUT/framework-res.apk" \
  --feature-flags "$FLAGS" \
  --manifest "$RES/AndroidManifest.xml" \
  -A "$RES/assets" \
  --private-symbols com.android.internal \
  --java "$OUT/java" \
  --min-sdk-version 35 --target-sdk-version 35 \
  --no-auto-version --auto-add-overlay --no-compress \
  --enable-sparse-encoding -x \
  "$OUT/compiled.zip"
# strip @FlaggedApi metadata: it references dozens of aconfig flag packages we don't ship
sed -i -E 's/@android\.annotation\.FlaggedApi\([^)]*\)//g' \
  "$OUT/java/android/R.java" "$OUT/java/android/Manifest.java" "$OUT/java/com/android/internal/R.java"
echo "== $OUT/framework-res.apk"
ls -la "$OUT/framework-res.apk"; ls "$OUT/java/android" | head -3
