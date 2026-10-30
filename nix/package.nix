# The complete androlinzberg install tree (the zig-out/ layout) as one Nix
# store path: launcher, ART runtime, framework, resources and bundled apps.
# Mirrors bkadmin's package: the pinned Zig from zig-overlay (through
# nix/zig-sandbox.nix), a pre-fetched package store linked to zig-pkg, and
# `zig build -p $out`.
{ lib, stdenv, makeWrapper, patchelf, removeReferencesTo, bash, coreutils, util-linux, python3, flex, bison, openjdk21_headless, src, host, zig, zigDeps }:
stdenv.mkDerivation {
  pname = "androlinzberg";
  version = "0.0.0";
  inherit src;

  nativeBuildInputs = host.build ++ [ makeWrapper patchelf removeReferencesTo ];
  env = host.env;

  # Zig emits the final RUNPATH itself and the launcher dlopens EGL, PipeWire,
  # libva and FFmpeg from it; shrinking it to DT_NEEDED entries would break
  # those loads. ART keeps its symbol tables for stack symbolization.
  dontPatchELF = true;
  dontStrip = true;
  dontInstall = true;

  postPatch = ''
    ln -s ${zigDeps} zig-pkg
  '';

  buildPhase = ''
    runHook preBuild
    export ZIG_GLOBAL_CACHE_DIR=$TMPDIR/zig-global-cache
    export ZIG_LOCAL_CACHE_DIR=$TMPDIR/zig-local-cache
    # The sandbox Zig (nix/zig-sandbox.nix) reads this ELF to detect the
    # native glibc target; it must be a dynamically linked host executable.
    [ "$NIX_BUILD_TOP" = /build ] || { echo "expected NIX_BUILD_TOP=/build, got $NIX_BUILD_TOP" >&2; exit 1; }
    mkdir -p /build/e
    ln -s "$(command -v env)" /build/e/env
    # Repository policy: at most nproc - 2 jobs, at least one.
    jobs=$(( NIX_BUILD_CORES > 2 ? NIX_BUILD_CORES - 2 : 1 ))
    # x86_64_v3 (AVX2, 2013 and later) instead of the build host's CPU so the
    # store path runs on other machines; Skia and ART dispatch newer
    # instructions at runtime.
    zig build -j"$jobs" --release=fast -Dcpu=x86_64_v3 -p "$out"
    runHook postBuild
  '';

  # Zig records the build cache directory and every NIX_LDFLAGS library
  # directory of a linked artifact as a runpath, and it embeds compiler
  # invocations and source paths as strings; the ART runtime inventory lists
  # the pinned source packages and crt objects it was built from. None of the
  # build-only paths below is loaded at runtime, so drop them from runpaths and
  # mangle the remaining string references the way nixpkgs does, which keeps
  # the compiler, JDK, Python, the AOSP source packages and the libraries'
  # header/pkg-config outputs out of the runtime closure. Every other runpath
  # stays as Zig emitted it: the launcher dlopens EGL, PipeWire, libva and
  # FFmpeg from it.
  preFixup = ''
    buildOnly=( ${zig} ${python3} ${flex} ${bison} ${openjdk21_headless} ${stdenv.cc.cc}
      ${lib.concatStringsSep " " (lib.concatMap (p: lib.optional (p ? dev) p.dev.outPath) host.build)} )
    for link in ${zigDeps}/*; do buildOnly+=( "$(readlink -f "$link")" ); done
    prune='^/build/'
    for p in "''${buildOnly[@]}"; do prune="$prune|^$p"; done
    find $out/bin $out/lib $out/libexec -type f | while read -r f; do
      rpath=$(patchelf --print-rpath "$f" 2>/dev/null) || continue
      kept=$(printf '%s' "$rpath" | tr ':' '\n' | { grep -Ev "$prune" || true; } | paste -sd: -)
      [ "$kept" = "$rpath" ] || patchelf --set-rpath "$kept" "$f"
    done
    # Only the ELF files and ART's runtime inventory carry such references;
    # the APKs, jars and resources are compressed archives.
    args=()
    for p in "''${buildOnly[@]}"; do args+=( -t "$p" ); done
    find $out/bin $out/lib $out/libexec -type f -print0 \
      | xargs -0 -n 8 -P "$NIX_BUILD_CORES" remove-references-to "''${args[@]}"
  '';

  # The launcher resolves its tree relative to the executable, so the wrapper
  # only supplies the host tools the dexopt adapter shells out to.
  postFixup = ''
    wrapProgram $out/bin/androlinzberg \
      --prefix PATH : ${lib.makeBinPath [ bash coreutils util-linux ]}
  '';

  doInstallCheck = true;
  installCheckPhase = ''
    for f in bin/.androlinzberg-wrapped bin/azk-binder-broker bin/azk-surfaceflinger bin/azk-task-presenter \
        libexec/androlinzberg/azk-standalone libexec/androlinzberg/azk-system-server \
        libexec/androlinzberg/azk-desktop-controller libexec/androlinzberg/azk-app-process libexec/androlinzberg/azk-metadata libexec/androlinzberg/azk-jvm-test \
        bin/azk-media-service bin/azk-ndk-system-client bin/art/bin/dalvikvm64 bin/art/lib64/libart.so \
        lib/libandroid_platform.so framework/framework.jar res/framework-res.apk fonts/Roboto-Regular.ttf share/androlinzberg/dexopt-apk.sh; do
      [ -e "$out/$f" ] || { echo "missing $f in the install tree" >&2; exit 1; }
    done
  '';

  meta = {
    description = "Run Android APK files as Wayland applications on Linux";
    license = lib.licenses.gpl3Plus;
    platforms = [ "x86_64-linux" ];
    mainProgram = "androlinzberg";
  };
}
