# The pinned AOSP source packages consumed through the Zig package manager.
# They are generated locally (aosp/README.md) and are not part of the Git
# tree, so the package build takes them from the Nix store as fixed-output
# files. Register each archive once per machine, or copy the store paths:
#
#   nix store add --mode flat aosp/<archive>.tar.gz
#
# The SHA-256 values below are the flat file hashes; the Zig N-V- hashes in
# build.zig.zon and artzig/build.zig.zon pin the same contents. Update both
# when the packagers regenerate an archive.
{ lib, requireFile }:
let
  # requireFile defaults to an unfree license; the archives are pinned AOSP
  # and Chromium sources under their own permissive licenses.
  archive = name: sha256: requireFile {
    inherit name sha256;
    meta.license = with lib.licenses; [ asl20 bsd3 mit ];
    message = ''
      The AOSP source package ${name} is not in the Nix store.
      Generate it with the packagers described in aosp/README.md, then run
      from the repository root:

        nix store add --mode flat aosp/${name}
    '';
  };
in {
  androlinzberg = archive "androlinzberg-aosp-android-16.0.0_r1.tar.gz" "sha256-FadeGqEVgbMK4MbRVPMgSRomFkM7Ykovd0ifGoDDamI=";
  webview = archive "androlinzberg-webview-140.0.7339.15.tar.gz" "sha256-/3itPGDoUaX/PSF9Oi0WB09FPxgUpMS70QW1SZbpkY0=";
  artzig = archive "artzig-aosp-android-16.0.0_r1.tar.gz" "sha256-qnHvAZ8DYLxTxeeVhY5Ys+whbYzK+m1iPVxsbjuakhw=";
}
