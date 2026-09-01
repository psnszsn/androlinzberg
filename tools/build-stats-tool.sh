#!/usr/bin/env bash
# build-stats-tool.sh <libprotobuf.a> <out-exe> — build the AOSP stats-log-api-gen
# host generator from the vendored source (frameworks/proto_logging/stats/
# stats_log_api_gen) with zig c++, against the zig-built libprotobuf. The atom
# descriptors are compiled in: aprotoc generates C++ for the whole atoms.proto
# import closure (stats/, minus the tool's own test protos and express/), staged
# under a frameworks/proto_logging include root so the protos' imports resolve.
# The vendored mainline prebuilt is NOT used: its compiled-in atoms predate the
# pinned framework's callers (e.g. RESOURCE_API_INFO, KEYBOARD_SYSTEMS_EVENT_*).
# zig cc's object cache keeps rebuilds cheap.
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
PBLIB=$(realpath "${1:?usage: build-stats-tool.sh <libprotobuf.a> <out-exe>}")
OUTEXE=$(realpath -m "${2:?usage: build-stats-tool.sh <libprotobuf.a> <out-exe>}")
PL="$APP/artzig/extra/proto-logging"
SL="$PL/stats/stats_log_api_gen"
LB="$APP/artzig/vendor/system/libbase"
LL="$APP/artzig/vendor/system/logging/liblog"
FMT="$APP/artzig/vendor/external/fmtlib"
PB="$APP/artzig/vendor/external/protobuf"

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/root/frameworks" "$WORK/gen"
ln -s "$PL" "$WORK/root/frameworks/proto_logging"
mapfile -t PROTOS < <(cd "$WORK/root" && find frameworks/proto_logging/stats -name '*.proto' \
    ! -path '*stats_log_api_gen*' ! -path '*express*' | sort)
mapfile -t RUN < <(bash "$APP/tools/aosp-run.sh" "$APP/artzig/out/host/linux-x86/bin/aprotoc")
"${RUN[@]}" --cpp_out="$WORK/gen" -I"$WORK/root" -I"$PB/src" "${PROTOS[@]}"

INC=(-I"$SL" -I"$WORK/gen" -I"$LB/include" -I"$LL/include"
     -I"$APP/artzig/vendor/system/core/libcutils/include"
     -I"$FMT/include" -I"$PB/src" -I"$PB/config" -D_FILE_OFFSET_BITS=64)
# posix_strerror_r wants the XSI strerror_r, not the _GNU_SOURCE one.
zig c++ -std=c++20 -w -O2 "${INC[@]}" \
    -U_GNU_SOURCE -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE \
    -c "$LB/posix_strerror_r.cpp" -o "$WORK/posix_strerror_r.o"
LBSRC=(); for f in abi_compatibility chrono_utils cmsg file hex logging mapped_file \
    parsebool parsenetaddress process properties result stringprintf strings threads \
    errors_unix; do LBSRC+=("$LB/$f.cpp"); done
LLSRC=(); for f in logger_write logger_name log_event_list log_event_write log_time \
    logprint properties; do LLSRC+=("$LL/$f.cpp"); done
mapfile -t GENCC < <(find "$WORK/gen" -name '*.cc' | sort)
zig c++ -std=c++20 -w -O2 "${INC[@]}" \
    -DWITH_VENDOR '-DJAVA_INCLUDE_SRCS_DIR="java/"' \
    '-DCC_INCLUDE_HDRS_DIR="cc_hdrs/"' '-DCC_INCLUDE_SRCS_DIR="cc_srcs/"' \
    -include "$APP/build/gen/fmt-cstdlib-prefix.h" \
    "$SL/Collation.cpp" "$SL/settings_provider.cpp" "$SL/utils.cpp" \
    "$SL/java_writer.cpp" "$SL/java_writer_q.cpp" "$SL/java_writer_vendor.cpp" \
    "$SL/main.cpp" "$SL/native_writer.cpp" "$SL/native_writer_vendor.cpp" \
    "$SL/rust_writer.cpp" \
    "${GENCC[@]}" "${LBSRC[@]}" "${LLSRC[@]}" \
    "$FMT/src/format.cc" "$FMT/src/os.cc" \
    "$WORK/posix_strerror_r.o" "$PBLIB" \
    -o "$OUTEXE"
echo "== $OUTEXE"
