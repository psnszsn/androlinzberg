#!/usr/bin/env bash
# build-fixture-apks.sh <framework-res.apk> <out-dir> — link each manifest in
# testdata/fixture-apks/<package>/ into a resources-only <package>.apk (aapt2
# from the devshell, like build-res.sh) and sign it with the azk-platform test
# key, the key that also signs framework-res.apk. The test packages' code
# lives in framework.jar; these APKs exist so the hosted package manager can
# parse real manifests server-side.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
FRAMEWORK_RES=$(realpath "$1")
OUT=$(realpath -m "$2")
KEY="$APP/tools/signing/azk-platform.pk8"
CERT="$APP/tools/signing/azk-platform.x509.pem"
APKSIGNER="$APP/artzig/vendor/prebuilts/sdk/tools/linux/lib/apksigner.jar"
mkdir -p "$OUT"
for dir in "$APP"/testdata/fixture-apks/*/; do
    pkg=$(basename "$dir")
    apk="$OUT/$pkg.apk"
    aapt2 link -o "$apk.tmp" \
        --manifest "$dir/AndroidManifest.xml" \
        -I "$FRAMEWORK_RES" \
        --min-sdk-version 21 --target-sdk-version 35
    java -jar "$APKSIGNER" sign \
        --key "$KEY" --cert "$CERT" \
        --v1-signing-enabled false --v2-signing-enabled true \
        --in "$apk.tmp" --out "$apk"
    rm -f "$apk.tmp" "$apk.idsig"
done
echo "== $OUT"
ls "$OUT"
