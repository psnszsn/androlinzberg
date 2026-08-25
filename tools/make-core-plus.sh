#!/usr/bin/env bash
# make-core-plus.sh <core-all.jar> <frameworks-base> <out.jar>
# core-all + the two android.annotation classes (SuppressLint/TargetApi) the
# framework sources need but libcore lacks; this jar feeds the javac
# system-modules image and d8 --lib.
set -euo pipefail
JAR=$(realpath "$1"); FB=$(realpath "$2"); OUT=$(realpath -m "$3")
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
javac -d "$WORK" "$FB/core/java/android/annotation/SuppressLint.java" "$FB/core/java/android/annotation/TargetApi.java"
cp "$JAR" "$OUT"
(cd "$WORK" && jar --update --file "$OUT" android)
