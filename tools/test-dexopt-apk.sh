#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf -- "$root"' EXIT
runtime="$root/runtime"
data="$root/data"
apk="$root/app.apk"
framework="$root/framework.jar"
framework_compile="$root/framework-app-context.jar"
mkdir -p "$runtime/bin"
cp testdata/dexopt/fake-dex2oat64 "$runtime/bin/dex2oat64"
chmod +x "$runtime/bin/dex2oat64"
printf 'apk\n' >"$apk"
printf 'framework\n' >"$framework"
printf 'framework without kotlin\n' >"$framework_compile"
export AZK_FAKE_DEX2OAT_LOG="$root/invocations"

bash tools/dexopt-apk.sh "$runtime" "$apk" "$framework" "$data"
encoded=${apk#/}
encoded=${encoded//\//@}
oat="$data/dalvik-cache/x86_64/${encoded}@classes.dex"
vdex="${oat%.dex}.vdex"
test "$(cat "$oat")" = new-oat
test "$(cat "$vdex")" = new-vdex
grep -F -- '--compiler-filter=speed' "$AZK_FAKE_DEX2OAT_LOG" >/dev/null
grep -F -- '--instruction-set-features=runtime' "$AZK_FAKE_DEX2OAT_LOG" >/dev/null
grep -F -- "--class-loader-context=PCL[];PCL[$framework_compile]" "$AZK_FAKE_DEX2OAT_LOG" >/dev/null
grep -F -- "--stored-class-loader-context=PCL[];PCL[$framework]" "$AZK_FAKE_DEX2OAT_LOG" >/dev/null
grep -F -- '-j2' "$AZK_FAKE_DEX2OAT_LOG" >/dev/null

rm -f -- "$oat" "$vdex" "$AZK_FAKE_DEX2OAT_LOG"
export AZK_FAKE_DEX2OAT_SLEEP=0.25
bash tools/dexopt-apk.sh "$runtime" "$apk" "$framework" "$data" &
first=$!
while ! test -s "$AZK_FAKE_DEX2OAT_LOG"; do sleep 0.01; done
bash tools/dexopt-apk.sh "$runtime" "$apk" "$framework" "$data"
wait "$first"
test "$(wc -l <"$AZK_FAKE_DEX2OAT_LOG")" -eq 1
unset AZK_FAKE_DEX2OAT_SLEEP

printf 'old-oat\n' >"$oat"
printf 'old-vdex\n' >"$vdex"
export AZK_FAKE_DEX2OAT_FAIL=1
if bash tools/dexopt-apk.sh "$runtime" "$apk" "$framework" "$data"; then
  echo 'expected fake dex2oat failure' >&2
  exit 1
fi
unset AZK_FAKE_DEX2OAT_FAIL
test "$(cat "$oat")" = old-oat
test "$(cat "$vdex")" = old-vdex
if test -n "$(find "$(dirname "$oat")" -maxdepth 1 \
    \( -name "$(basename "$oat").tmp.*" -o -name "$(basename "$vdex").tmp.*" \) \
    -print -quit)"; then
  echo 'temporary dexopt artifacts leaked after failure' >&2
  exit 1
fi

echo 'APK dexopt transaction tests: PASS'
