# Protocol fixture: labwc 0.20.1 advertises xdg-shell v6 but its minimize
# implementation sends no suspended state. Exercise the optional wire event
# through the existing shot.sh harness using the same pinned compositor. The
# tablet fixture adds a bounded synthetic protocol source for APK input gates.
let
  runtime = builtins.getFlake ("path:" + toString ../../artzig);
  pkgs = runtime.inputs.nixpkgs.legacyPackages.x86_64-linux;
in pkgs.labwc.overrideAttrs (old: {
  patches = (old.patches or []) ++ [ ./labwc-suspension.patch ./labwc-tablet.patch ];
})
