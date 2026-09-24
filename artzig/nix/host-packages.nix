# Build-time toolchain, generators and native libraries shared by the Artzig
# development shell (artzig/flake.nix) and the Nix package (nix/package.nix).
# Keep the two consumers on this one list so a dependency added for a build
# step cannot be missing from the sandboxed package build.
{ pkgs, zig, hostLibc }:
rec {
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
    JAVA_HOME = "${pkgs.openjdk21_headless}/lib/openjdk";
    # Pinned AOSP rustc uses libz; pkg-config may select zlib's static output.
    AZK_AOSP_TOOL_LIBRARY_PATH = "${pkgs.zlib}/lib";
  };
}
