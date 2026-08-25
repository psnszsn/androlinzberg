{
  description = "artzig: ART from AOSP main, built on a Linux host";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
      # AOSP's prebuilts (clang, ninja, jdk, python, rust, ...) are dynamically
      # linked FHS binaries, so Soong has to run inside an FHS sandbox on NixOS.
      fhs = pkgs.buildFHSEnv {
        name = "aosp-env";
        targetPkgs = p: with p; [
          # what soong_ui/ninja/clang expect from the host
          bash coreutils findutils gnugrep gnused gawk diffutils which file
          git python3 curl unzip zip rsync
          gcc.cc.lib glibc zlib ncurses5 ncurses libxml2 libxcrypt-legacy
          openssl libtool util-linux gnumake pkg-config
          # system deps we want to build against instead of the vendored copies
          expat lz4 zstd icu
          zig
        ];
        multiPkgs = p: with p; [ zlib ncurses5 ];
        profile = ''
          export USE_CCACHE=0
          export ALLOW_NINJA_ENV=true
          # don't let Soong try to use host go/python from PATH over prebuilts
          export ANDROID_BUILD_TOP="$PWD"
        '';
        runScript = "bash";
      };
    in {
      # Normal dev shell: zig + a JDK for javac/d8 (system JDK by decision).
      # Soong itself needs the FHS env: `nix run .#aosp-env` or `nix develop .#aosp`.
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = (with pkgs; [ openjdk21_headless python3 zlib libpng libcap icu pkg-config e2fsprogs unzip libglvnd elfutils libunwind libbsd libmd
            # app subproject: Skia + Wayland platform
            wayland wayland-protocols wayland-scanner libxkbcommon cage aapt grim freetype harfbuzz fontconfig expat libjpeg_turbo libwebp gn harfbuzzFull vulkan-headers openssl sqlite pipewire
            # MediaCodec video: system FFmpeg provides VA-API/DRM hwcontexts;
            # libva exports decoded surfaces and libdrm defines DMA-BUF formats.
            ffmpeg-full libva libdrm libgbm ]); # zig: use the one on PATH (0.17-dev per build.zig.zon)
          JAVA_HOME = "${pkgs.openjdk21_headless}/lib/openjdk";
        };
        aosp = fhs.env;
      };
      packages.${system}.aosp-env = fhs;
    };
}
