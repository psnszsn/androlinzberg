# Pre-fetched Zig package store for the sandboxed build. The pinned Zig uses a
# `zig-pkg` directory at the build root as its package directory, so every
# dependency of build.zig.zon and artzig/build.zig.zon is linked there under
# its Zig package hash and `zig build` never fetches anything.
#
# zon2nix cannot express the local `file:` archives or the `path` dependency,
# so this list is maintained by hand. When a hash in either build.zig.zon
# changes, rename the matching entry here; when a URL changes, refresh the
# Nix hash of that entry as well.
{ lib, linkFarm, runCommand, fetchzip, fetchgit, aospArchives }:
let
  # Zig extracts the local archives without stripping a leading directory
  # (they contain source roots such as frameworks/ directly).
  unpack = name: archive: runCommand name { } ''
    mkdir -p $out
    tar -xzf ${archive} -C $out
  '';
  # The build runs the Kotlin compiler's launcher script directly; its
  # `#!/usr/bin/env bash` line cannot resolve inside the sandbox. Every other
  # packaged script is started through an explicit interpreter.
  unpackWithKotlinc = name: archive: runCommand name { } ''
    mkdir -p $out
    tar -xzf ${archive} -C $out
    patchShebangs --build $out/external/kotlinc/bin
  '';
in
linkFarm "androlinzberg-zig-packages" [
  # build.zig.zon: .aosp
  {
    name = "N-V-__8AAHPqFktVdRQVve9YEToxf0uAUdfkQbGzLzthA_GJ";
    path = unpackWithKotlinc "androlinzberg-aosp" aospArchives.androlinzberg;
  }
  # build.zig.zon: .webview
  {
    name = "N-V-__8AAF1whxNdNKACWB1dSoSDGefcMMlNxw5Ttc9ZdbzL";
    path = unpack "androlinzberg-webview" aospArchives.webview;
  }
  # build.zig.zon: .wayland
  {
    name = "wayland-0.7.0-dev-lQa1kvr8AQA1T2WbWSm95e1hfefbeHJJ_-5ApPLYx8ni";
    path = fetchzip {
      url = "https://codeberg.org/ifreund/zig-wayland/archive/3b5004a124b399839865f28ae99425a2dd4815f3.tar.gz";
      hash = "sha256-slCuUfBpXLblGVz34Ju41U/ZVir+c94CzdkCgZMJgak=";
    };
  }
  # artzig/build.zig.zon: .aosp
  {
    name = "N-V-__8AAK0OTQ2sZhxuVUU7_TVV1M-Xs_Ts9CxOXSf3sB3j";
    path = unpack "artzig-aosp" aospArchives.artzig;
  }
  # artzig/build.zig.zon: .bionic_translation
  {
    name = "N-V-__8AAGCkBQDzuN_HnnSdm_vKSf4WCvHYVQsAS-PGUTuJ";
    path = fetchgit {
      url = "https://gitlab.com/android_translation_layer/bionic_translation.git";
      rev = "ef588129f1b505e62de8750793e44aba98817a98";
      hash = "sha256-K3Id0cqD5xPKbZfgb9yDDTM4513K7SYzrrVoSIGs4qc=";
    };
  }
]
