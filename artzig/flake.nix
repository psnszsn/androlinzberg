{
  description = "artzig: ART from pinned AOSP sources, built on a Linux host";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    zig-overlay.url = "github:mitchellh/zig-overlay";
  };

  outputs = { self, nixpkgs, zig-overlay }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
      hostLibc = import ./host-libc.nix { glibc = pkgs.glibc; };
      zigPinned = zig-overlay.packages.${system}."master-2026-08-25";
    in {
      # Zig + a JDK for javac/d8, Repo for refreshing the AOSP source packages,
      # and the native dependencies used by Artzig and Androlinzberg.
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = [ zigPinned ] ++ (with pkgs; [ openjdk21_headless python3 git-repo rust-bindgen zlib libpng libcap libselinux icu pkg-config e2fsprogs unzip libglvnd elfutils libunwind libbsd libmd
            # app subproject: Skia + Wayland platform
            wayland wayland-protocols wayland-scanner libxkbcommon cage labwc aapt grim wlr-randr wl-clipboard xdg-utils freetype harfbuzz fontconfig expat libjpeg_turbo libwebp gn harfbuzzFull vulkan-headers vulkan-loader openssl sqlite pipewire
            # MediaCodec video: system FFmpeg provides VA-API/DRM hwcontexts;
            # libva exports decoded surfaces and libdrm defines DMA-BUF formats.
            ffmpeg-full libva libdrm libgbm ]);
          # ART's stack-only query comes from the libc that owns pthreads.
          AZK_HOST_LIBC = "${hostLibc.out}";
          JAVA_HOME = "${pkgs.openjdk21_headless}/lib/openjdk";
          # Pinned AOSP rustc uses libz; pkg-config may select zlib's static output.
          AZK_AOSP_TOOL_LIBRARY_PATH = "${pkgs.zlib}/lib";
        };
      };
      packages.${system} = {
        zig = zigPinned;
        host-libc = hostLibc.out;
      };
    };
}
