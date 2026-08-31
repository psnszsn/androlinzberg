#!/usr/bin/env bash
# build-aconfig.sh <out-dir> — generate aconfig flag Java (Flags/FeatureFlags/…)
# for each package in tools/aconfig-packages.txt, from its .aconfig declarations
# in frameworks-base, using the AOSP aconfig tool. Two stages per package:
#   create-cache   (all .aconfig for the package -> a parsed cache .pb)
#   create-java-lib (cache -> Java, mode=production; flags take their declared
#                    default state, so an all-disabled tree reads all-off).
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
ROOT="$APP/artzig"
OUT=$(realpath -m "${1:-$APP/.cache/aconfig-java}")
FB="$ROOT/extra/frameworks-base"
ACONFIG="$ROOT/out/host/linux-x86/bin/aconfig"
LIST="$APP/tools/aconfig-packages.txt"

mapfile -t RUN < <(bash "$APP/tools/aosp-run.sh" "$ACONFIG")
rm -rf "$OUT"
mkdir -p "$OUT/cache" "$OUT/java"

while IFS= read -r pkg; do
    [ -z "$pkg" ] && continue
    case "$pkg" in \#*) continue ;; esac
    # Every .aconfig file declaring this exact package (a package may span files).
    mapfile -t files < <(grep -rl "package: \"$pkg\"" "$FB" --include='*.aconfig' 2>/dev/null | sort)
    [ "${#files[@]}" -gt 0 ] || { echo "no .aconfig for package $pkg" >&2; exit 1; }
    container=$(sed -n 's/^container: "\([^"]*\)".*/\1/p' "${files[0]}" | head -1)
    container="${container:-system}"
    decls=()
    for f in "${files[@]}"; do decls+=(--declarations "$f"); done
    "${RUN[@]}" create-cache --package "$pkg" --container "$container" \
        "${decls[@]}" --cache "$OUT/cache/$pkg.pb"
    "${RUN[@]}" create-java-lib --cache "$OUT/cache/$pkg.pb" --out "$OUT/java" --mode production
done < "$LIST"
echo "== $OUT/java"
