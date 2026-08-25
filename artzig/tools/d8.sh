#!/usr/bin/env bash
# d8 wrapper: tools/d8.sh <d8.jar> <out-dir> <input>...   (inputs: .class files, .jar, or directories)
# Uses the system `java`. Produces <out-dir>/classes.dex (+classes2.dex... if huge).
set -euo pipefail
d8jar=$1; out=$2; shift 2
inputs=()
for i in "$@"; do
  if [ -d "$i" ]; then mapfile -t -O ${#inputs[@]} inputs < <(find "$i" -name '*.class' | sort)
  else inputs+=("$i"); fi
done
mkdir -p "$out"
exec java -cp "$d8jar" com.android.tools.r8.D8 --min-api ${D8_MIN_API:-26} ${D8_LIB:+--lib "$D8_LIB"} --output "$out" "${inputs[@]}"
