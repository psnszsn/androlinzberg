#!/usr/bin/env bash
# compile-jvm.sh <core-plus.jar> <system-modules> <sources.txt> <kotlin-home> <out-dir>
#
# Kotlin sees all Java and Kotlin sources so it can resolve mixed-language
# references, but emits only Kotlin classes. javac then sees those classes and
# emits the Java classes into the same output directory. This follows AOSP
# Soong's Kotlin-before-Java ordering without exposing that ordering to Zig.
set -euo pipefail

CORE_PLUS=$(realpath "$1")
SYSTEM_MODULES=$(realpath "$2")
SOURCES=$(realpath "$3")
KOTLIN_HOME=$(realpath "$4")
OUT=$(realpath -m "$5")
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
JAVA_SOURCES="$WORK/java-sources.txt"
KOTLIN_SOURCES="$WORK/kotlin-sources.txt"
awk '/[.]java$/' "$SOURCES" > "$JAVA_SOURCES"
awk '/[.]kt$/' "$SOURCES" > "$KOTLIN_SOURCES"

rm -rf "$OUT"
mkdir -p "$OUT"

if [[ -s "$KOTLIN_SOURCES" ]]; then
    # Keep kotlinc off C2. OpenJDK 21.0.12 has crashed reproducibly while C2
    # compiles Kotlin FIR data-flow analysis for this mixed source set.
    # C1-only code fills the code cache faster: size it for the whole tree
    # ("Out of space in CodeCache for method handle intrinsic" at the default).
    JAVA_OPTS="-Xmx2048M -Xms256M -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=512m" "$KOTLIN_HOME/bin/kotlinc" \
        -no-jdk -no-stdlib -no-reflect \
        -language-version 2.0 -api-version 2.0 -jvm-target 21 \
        -java-parameters -Xsam-conversions=class -Xstring-concat=inline \
        -classpath "$CORE_PLUS:$STDLIB" \
        -d "$OUT" \
        "@$JAVA_SOURCES" "@$KOTLIN_SOURCES"
fi

# Runtime annotation classes (android.annotation): the sysmodules image owns
# that package, so they compile in a separate module-free pass.
APP_ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ -d "$APP_ROOT/src/annotation-runtime" ]]; then
    find "$APP_ROOT/src/annotation-runtime" -name '*.java' > "$WORK/annotation-sources.txt"
    javac -source 21 -target 21 -encoding UTF-8 -nowarn -d "$OUT" "@$WORK/annotation-sources.txt"
fi

javac \
    --system="$SYSTEM_MODULES" \
    -source 21 -target 21 \
    -encoding UTF-8 -g -parameters -XDstringConcat=inline \
    -Xmaxerrs 2000 -nowarn \
    -classpath "$OUT:$STDLIB" \
    -d "$OUT" \
    "@$JAVA_SOURCES"
