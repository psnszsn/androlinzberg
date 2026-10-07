# Build-time toolchain, generators and native libraries shared by the Artzig
# development shell (artzig/flake.nix) and the Nix package (nix/package.nix).
# Keep the two consumers on this one list so a dependency added for a build
# step cannot be missing from the sandboxed package build.
{ pkgs, zig, hostLibc }:
rec {
  # The pinned runtime's dynamic loader under a store path short enough to be
  # written into the PT_INTERP segment Zig emits (tools/set-interpreter.py).
  hostLoader = pkgs.runCommand "ld" { } "ln -s ${hostLibc}/lib/ld-linux-x86-64.so.2 $out";

  # Everything `zig build` (the default install step) invokes or links
  # (aapt supplies aapt2 for the framework resources).
  build = [ zig ] ++ (with pkgs; [
    openjdk21_headless python3 rust-bindgen flex bison aapt zlib libpng libcap libselinux icu pkg-config e2fsprogs unzip libglvnd elfutils libunwind libbsd libmd
    # app subproject: Skia + Wayland platform
    wayland wayland-protocols wayland-scanner libxkbcommon freetype harfbuzz fontconfig expat libjpeg_turbo libwebp harfbuzzFull vulkan-headers vulkan-loader openssl sqlite pipewire
    # MediaCodec video: system FFmpeg provides VA-API/DRM hwcontexts;
    # libva exports decoded surfaces and libdrm defines DMA-BUF formats.
    ffmpeg-full libva libdrm libgbm
  ]);

  # Test harnesses (Cage/labwc, screenshots, clipboard, desktop tools), Repo
  # for refreshing the AOSP checkout and GN for the Perfetto refresh script.
  # GN's setup hook runs a configure step, so it must stay out of `build`.
  development = with pkgs; [ git-repo cage labwc grim wlr-randr wl-clipboard xdg-utils gn ];

  packages = build ++ development;

  env = {
    # ART's stack-only query comes from the libc that owns pthreads.
    AZK_HOST_LIBC = "${hostLibc}";
    # Executables linked against it must also boot through its loader: glibc
    # aborts at startup when the system loader's version differs from libc's.
    AZK_HOST_LOADER = "${hostLoader}";
    JAVA_HOME = "${pkgs.openjdk21_headless}/lib/openjdk";
    # Pinned AOSP rustc uses libz; pkg-config may select zlib's static output.
    AZK_AOSP_TOOL_LIBRARY_PATH = "${pkgs.zlib}/lib";
  };
}
