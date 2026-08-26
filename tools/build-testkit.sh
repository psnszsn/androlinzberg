#!/usr/bin/env bash
# Build the host-JDK Kotlin test-control client and its JUnit tests.
#
# build-testkit.sh <kotlin-home> <junit-root> <hamcrest-root> <gson.jar>
#                  <testkit-kotlin> <unit-test-kotlin>
#                  <integration-test-kotlin> <out-dir>
set -euo pipefail

KOTLIN_HOME=$(realpath "$1")
JUNIT_ROOT=$(realpath "$2")
HAMCREST_ROOT=$(realpath "$3")
GSON=$(realpath "$4")
TESTKIT_SRC=$(realpath "$5")
UNIT_SRC=$(realpath "$6")
INTEGRATION_SRC=$(realpath "$7")
OUT=$(realpath -m "$8")

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
DEPS_CLASSES="$WORK/deps"
TESTKIT_CLASSES="$WORK/testkit"
TEST_CLASSES="$WORK/tests"
mkdir -p "$DEPS_CLASSES" "$TESTKIT_CLASSES" "$TEST_CLASSES"
rm -rf "$OUT"
mkdir -p "$OUT"

find "$HAMCREST_ROOT/hamcrest-core/src/main/java" -name '*.java' -print | sort \
    > "$WORK/hamcrest-sources.txt"
find "$JUNIT_ROOT/src/main/java" -name '*.java' -print | sort \
    > "$WORK/junit-sources.txt"
if ! javac --release 11 -nowarn -Xlint:none -encoding UTF-8 -d "$DEPS_CLASSES" \
    @"$WORK/hamcrest-sources.txt" 2>"$WORK/hamcrest-javac.log"; then
    sed -n '1,240p' "$WORK/hamcrest-javac.log" >&2
    exit 1
fi
if ! javac --release 11 -nowarn -Xlint:none -encoding UTF-8 -classpath "$DEPS_CLASSES" \
    -d "$DEPS_CLASSES" @"$WORK/junit-sources.txt" 2>"$WORK/junit-javac.log"; then
    sed -n '1,240p' "$WORK/junit-javac.log" >&2
    exit 1
fi
jar --create --file "$OUT/junit-hamcrest.jar" -C "$DEPS_CLASSES" .

STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
STDLIB_JDK7="$KOTLIN_HOME/lib/kotlin-stdlib-jdk7.jar"
STDLIB_JDK8="$KOTLIN_HOME/lib/kotlin-stdlib-jdk8.jar"
KOTLIN_TEST="$KOTLIN_HOME/lib/kotlin-test.jar"
KOTLIN_TEST_JUNIT="$KOTLIN_HOME/lib/kotlin-test-junit.jar"
COMPILE_CP="$STDLIB:$STDLIB_JDK7:$STDLIB_JDK8:$GSON:$OUT/junit-hamcrest.jar:$KOTLIN_TEST:$KOTLIN_TEST_JUNIT"

find "$TESTKIT_SRC" -name '*.kt' -print | sort > "$WORK/testkit-sources.txt"
JAVA_OPTS="-Xmx1024M -Xms128M" "$KOTLIN_HOME/bin/kotlinc" \
    -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 21 \
    -java-parameters -classpath "$COMPILE_CP" -d "$TESTKIT_CLASSES" \
    @"$WORK/testkit-sources.txt"
jar --create --file "$OUT/testkit.jar" -C "$TESTKIT_CLASSES" .

find "$UNIT_SRC" "$INTEGRATION_SRC" -name '*.kt' -print | sort \
    > "$WORK/test-sources.txt"
JAVA_OPTS="-Xmx1024M -Xms128M" "$KOTLIN_HOME/bin/kotlinc" \
    -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 21 \
    -java-parameters -Xfriend-paths="$OUT/testkit.jar" \
    -classpath "$COMPILE_CP:$OUT/testkit.jar" -d "$TEST_CLASSES" \
    @"$WORK/test-sources.txt"
jar --create --file "$OUT/tests.jar" -C "$TEST_CLASSES" .

cp "$GSON" "$OUT/gson.jar"
cp "$STDLIB" "$OUT/kotlin-stdlib.jar"
cp "$STDLIB_JDK7" "$OUT/kotlin-stdlib-jdk7.jar"
cp "$STDLIB_JDK8" "$OUT/kotlin-stdlib-jdk8.jar"
cp "$KOTLIN_TEST" "$OUT/kotlin-test.jar"
cp "$KOTLIN_TEST_JUNIT" "$OUT/kotlin-test-junit.jar"
