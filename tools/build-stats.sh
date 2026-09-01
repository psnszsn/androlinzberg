#!/usr/bin/env bash
# build-stats.sh <stats-log-api-gen-exe> <out-dir> — generate
# com.android.internal.util.FrameworkStatsLog with the source-built
# stats-log-api-gen (tools/build-stats-tool.sh; atoms compiled in from the pinned
# frameworks/proto_logging), per frameworks-base core/java/Android.bp. Wired into
# build/java.zig via STATS=; replaces the hand-written FrameworkStatsLog stubs.
# The generated code encodes real StatsEvent buffers (reused StatsD module
# source); android.util.StatsLog is the host seam whose sink is a no-op.
# --worksource omitted: android.os.WorkSource is not in the hosted framework's
# curated source set and no compiled caller uses the WorkSource overloads.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
TOOL=$(realpath "${1:?usage: build-stats.sh <exe> <out-dir>}")
OUT=$(realpath -m "${2:?usage: build-stats.sh <exe> <out-dir>}")
SL="$APP/artzig/extra/proto-logging/stats/stats_log_api_gen"
rm -rf "$OUT"; mkdir -p "$OUT/com/android/internal/util"
# The tool reads its include-src helpers from Soong's install layout relative to
# the cwd (settings_provider.cpp: tools/out/etc/stats-log-api-gen/<dir>).
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/tools/out/etc/stats-log-api-gen"
ln -s "$SL/include_java" "$WORK/tools/out/etc/stats-log-api-gen/java"
ln -s "$SL/include_cc_hdrs" "$WORK/tools/out/etc/stats-log-api-gen/cc_hdrs"
ln -s "$SL/include_cc_srcs" "$WORK/tools/out/etc/stats-log-api-gen/cc_srcs"
( cd "$WORK" && "$TOOL" \
    --java "$OUT/com/android/internal/util/FrameworkStatsLog.java" \
    --module framework --javaPackage com.android.internal.util \
    --javaClass FrameworkStatsLog )
echo "== $OUT"
