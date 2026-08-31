#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

status=0
while IFS= read -r source; do
    [[ -e "$source" ]] || continue
    case "$source" in
        artzig/src/gen/*)
            # Generated repository outputs.
            ;;
        artzig/examples/*)
            # Directly reused Android example source.
            ;;
        src/framework/*|src/framework-stubs/*)
            # AOSP-facing compatibility sources. Migrate these only with a
            # focused ABI and behavior review; never through a bulk rewrite.
            ;;
        testdata/kotlin-shadow/*)
            # Java-compiler fixtures used to test Kotlin runtime shadowing.
            ;;
        src/annotation-runtime/*)
            # android.annotation marker classes compiled in a separate
            # module-free javac pass (the sysmodules image owns the package);
            # codegen'd AOSP classes reference them via .class literals.
            ;;
        *)
            printf 'repository-owned Java is not allowed here: %s\n' "$source" >&2
            status=1
            ;;
    esac
done < <(git ls-files '*.java')

exit "$status"
