#!/usr/bin/env bash
# regen-skia.sh — regenerate build/gen/skia_sources.zig from skia's GN build
# (needs `gn`; run inside `nix shell nixpkgs#gn` or the dev shell).
set -euo pipefail
APP=$(cd "$(dirname "$0")/.." && pwd)
SKS=${1:?usage: tools/regen-skia.sh /path/to/aosp/external/skia}
OUT=$(mktemp -d)
(cd "$SKS" && gn gen "$OUT" --ide=json --args='
target_os="linux" target_cpu="x64" is_official_build=true is_debug=false is_component_build=false
skia_use_system_freetype2=true skia_use_system_harfbuzz=true skia_use_system_libpng=true skia_use_system_zlib=true
skia_use_system_libjpeg_turbo=true skia_use_system_libwebp=true skia_use_system_expat=true skia_use_system_icu=false
skia_use_freetype=true skia_use_fontconfig=true skia_use_harfbuzz=true skia_use_icu=false skia_use_icu4x=false skia_use_libgrapheme=false
skia_use_libjpeg_turbo_decode=true skia_use_libjpeg_turbo_encode=true skia_use_libwebp_decode=true skia_use_libwebp_encode=true
skia_use_libpng_decode=true skia_use_libpng_encode=true skia_use_wuffs=true skia_use_expat=true
skia_use_vulkan=true skia_use_gl=true skia_use_egl=true skia_use_x11=false
skia_use_jpeg_gainmaps=true
skia_enable_ganesh=true skia_enable_graphite=false skia_enable_pdf=true skia_enable_skshaper=false skia_enable_skparagraph=false skia_enable_skunicode=false skia_enable_svg=false skia_enable_tools=false skia_enable_skottie=false
skia_enable_fontmgr_custom_directory=false skia_enable_fontmgr_custom_embedded=false skia_enable_fontmgr_custom_empty=true skia_enable_fontmgr_fontconfig=true skia_enable_fontmgr_android=true
skia_use_perfetto=false skia_disable_tracing=true skia_use_fontations=false skia_use_crabbyavif=false skia_use_dng_sdk=false skia_use_piex=false skia_use_libavif=false skia_use_libjxl_decode=false skia_use_lua=false
skia_enable_precompile=false skia_enable_optimize_size=false skia_enable_android_utils=true
' >/dev/null)
python3 "$APP/tools/gen-skia-sources.py" "$OUT/project.json" "$APP/build/gen/skia_sources.zig"
rm -rf "$OUT"
