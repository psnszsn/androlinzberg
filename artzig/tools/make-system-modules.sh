#!/usr/bin/env bash
# make-system-modules.sh <core-all-classes.jar> <output-system-dir> <vendor-dir>
# Replicates Soong's java_system_modules: wrap the boot classpath classes into a
# java.base system module image that `javac --system=` accepts. Needs a JDK on PATH.
set -euo pipefail
JAR=$(realpath "$1"); OUT=$(realpath -m "$2"); VENDOR=$(realpath "$3")
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
bash "$VENDOR/build/soong/scripts/jars-to-module-info-java.sh" java.base "$JAR" > "$WORK/module-info.java"
javac --system=none --patch-module=java.base="$JAR" "$WORK/module-info.java" -d "$WORK/classes"
cp "$JAR" "$WORK/module.jar"
(cd "$WORK/classes" && jar --update --file "$WORK/module.jar" module-info.class)
mkdir -p "$WORK/jmod"
jmod create --module-version "$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')" \
     --target-platform LINUX-OTHER --class-path "$WORK/module.jar" "$WORK/jmod/java.base.jmod"
rm -rf "$OUT"
jlink --module-path "$WORK/jmod" --add-modules java.base --output "$OUT" --disable-plugin system-modules
# javac reads the image through jrt-fs
cp "$JAVA_HOME/lib/jrt-fs.jar" "$OUT/lib/" 2>/dev/null || cp "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")/lib/jrt-fs.jar" "$OUT/lib/"
