#!/usr/bin/env bash
set -euo pipefail

# Host adapter for Android's installd dexopt transaction. The Java side asks
# DexFile.getDexOptNeeded() first; this script owns the per-APK lock and atomic
# dalvik-cache publication that Android would normally perform out of process.
runtime_root=$1
apk=$(realpath -m "$2")
framework_jar=$(realpath -m "$3")
android_data=$4
framework_compile_jar=$(realpath -m "${5:-$(dirname "$framework_jar")/framework-app-context.jar}")

if [[ ! -f $framework_compile_jar ]]; then
  echo "azk: missing APK compiler context: $framework_compile_jar" >&2
  exit 1
fi

cache_dir="$android_data/dalvik-cache/x86_64"
mkdir -p "$cache_dir"
encoded=${apk#/}
encoded=${encoded//\//@}
oat="$cache_dir/${encoded}@classes.dex"
vdex="${oat%.dex}.vdex"
lock="$oat.lock"
tmp_oat="$oat.tmp.$$"
tmp_vdex="$vdex.tmp.$$"

cleanup() {
  rm -f -- "$tmp_oat" "$tmp_vdex"
}
trap cleanup EXIT INT TERM

exec 9>"$lock"
if ! flock -n 9; then
  echo "azk: dexopt already running for $apk" >&2
  exit 0
fi

bcp="$runtime_root/apex/com.android.art/javalib/core-oj.jar:$runtime_root/apex/com.android.art/javalib/core-libart.jar:$runtime_root/apex/com.android.art/javalib/okhttp.jar:$runtime_root/apex/com.android.art/javalib/bouncycastle.jar:$runtime_root/apex/com.android.art/javalib/apache-xml.jar:$runtime_root/apex/com.android.i18n/javalib/core-icu4j.jar:$runtime_root/apex/com.android.conscrypt/javalib/conscrypt.jar"
bcpl="/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar"

echo "azk: background dexopt started for $apk" >&2
env \
  ANDROID_ROOT="$runtime_root" \
  ANDROID_ART_ROOT="$runtime_root/apex/com.android.art" \
  ANDROID_I18N_ROOT="$runtime_root/com.android.i18n" \
  ANDROID_TZDATA_ROOT="$runtime_root/com.android.tzdata" \
  ANDROID_DATA="$android_data" \
  nice -n 10 "$runtime_root/bin/dex2oat64" \
  --dex-file="$apk" \
  --dex-location="$apk" \
  --oat-file="$tmp_oat" \
  --output-vdex="$tmp_vdex" \
  --instruction-set=x86_64 \
  --instruction-set-features=runtime \
  --compiler-filter=speed \
  --class-loader-context="PCL[];PCL[$framework_compile_jar]" \
  --stored-class-loader-context="PCL[];PCL[$framework_jar]" \
  --boot-image="$runtime_root/apex/com.android.art/javalib/boot.art" \
  --runtime-arg "-Xbootclasspath:$bcp" \
  --runtime-arg "-Xbootclasspath-locations:$bcpl" \
  --no-generate-mini-debug-info \
  -j2

# OAT is the commit marker. Publishing VDEX first makes a crash or failure
# observable as stale rather than accepting a partially installed pair.
mv -f -- "$tmp_vdex" "$vdex"
mv -f -- "$tmp_oat" "$oat"
trap - EXIT INT TERM
echo "azk: background dexopt installed $oat" >&2
