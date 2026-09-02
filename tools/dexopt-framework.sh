#!/usr/bin/env bash
set -euo pipefail

# Build-time AOSP dex2oat adaptation for the hosted framework class path. Android normally
# dexpreopts framework code as part of the system image; the relocatable host runtime builds the
# equivalent adjacent odex/vdex pair after framework.jar has been assembled.
runtime_root=$1
input_jar=$2
dex_location=$(realpath -m "$3")
output_dir=$4
dex2oat_jobs=${ARTZIG_DEX2OAT_JOBS:-10}

mkdir -p "$output_dir"
bcp="$runtime_root/apex/com.android.art/javalib/core-oj.jar:$runtime_root/apex/com.android.art/javalib/core-libart.jar:$runtime_root/apex/com.android.art/javalib/okhttp.jar:$runtime_root/apex/com.android.art/javalib/bouncycastle.jar:$runtime_root/apex/com.android.art/javalib/apache-xml.jar:$runtime_root/apex/com.android.i18n/javalib/core-icu4j.jar:$runtime_root/apex/com.android.conscrypt/javalib/conscrypt.jar"
bcpl="/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar"

# Cold builds can cross dex2oat's per-method timing threshold. Suppress only
# those performance measurements; retain every other warning and error.
env \
  ANDROID_LOG_TAGS="*:w" \
  ANDROID_ROOT="$runtime_root" \
  ANDROID_ART_ROOT="$runtime_root/apex/com.android.art" \
  ANDROID_I18N_ROOT="$runtime_root/com.android.i18n" \
  ANDROID_TZDATA_ROOT="$runtime_root/com.android.tzdata" \
  ANDROID_DATA="$output_dir/data" \
  "$runtime_root/bin/dex2oat64" \
  --dex-file="$input_jar" \
  --dex-location="$dex_location" \
  --oat-file="$output_dir/framework.odex" \
  --output-vdex="$output_dir/framework.vdex" \
  --instruction-set=x86_64 \
  --instruction-set-features=runtime \
  --compiler-filter=speed \
  '--class-loader-context=PCL[]' \
  --boot-image="$runtime_root/apex/com.android.art/javalib/boot.art" \
  --runtime-arg "-Xbootclasspath:$bcp" \
  --runtime-arg "-Xbootclasspath-locations:$bcpl" \
  --no-generate-mini-debug-info \
  "-j$dex2oat_jobs" \
  2> >(grep -E -v ' W dex2oat64: compiler_driver\.cc:[0-9]+ Compilation of .* took [0-9]+(\.[0-9]+)?ms$' >&2)
