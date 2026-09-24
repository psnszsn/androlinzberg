{
  description = "androlinzberg: run Android APK files as Wayland applications";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    zig-overlay.url = "github:mitchellh/zig-overlay";
  };

  outputs = { self, nixpkgs, zig-overlay }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
      hostLibc = import ./artzig/host-libc.nix { glibc = pkgs.glibc; };
      zigPinned = zig-overlay.packages.${system}."master-2026-08-25";
      host = import ./artzig/nix/host-packages.nix { inherit pkgs; zig = zigPinned; hostLibc = hostLibc.out; };
      aospArchives = import ./nix/aosp-archives.nix { inherit (pkgs) lib requireFile; };
      zigDeps = pkgs.callPackage ./nix/zig-deps.nix { inherit aospArchives; };
      zigSandbox = pkgs.callPackage ./nix/zig-sandbox.nix { zig = zigPinned; };
      # The build graph resolves its source directories eagerly, so the package
      # takes the complete Git tree (a few megabytes without the AOSP archives).
      src = self;
      # The package builds with the sandbox-detecting Zig and the same
      # native libraries as the development shell.
      hostSandbox = import ./artzig/nix/host-packages.nix { inherit pkgs; zig = zigSandbox; hostLibc = hostLibc.out; };
      androlinzberg = pkgs.callPackage ./nix/package.nix { inherit src zigDeps; host = hostSandbox; zig = zigSandbox; };
    in {
      packages.${system} = {
        default = androlinzberg;
        inherit androlinzberg;
        zig-deps = zigDeps;
        zig-sandbox = zigSandbox;
        zig = zigPinned;
        host-libc = hostLibc.out;
      };

      apps.${system}.default = {
        type = "app";
        program = "${androlinzberg}/bin/androlinzberg";
      };

      nixosModules.hostScheduling = import ./artzig/nixos/host-scheduling.nix;

      # Same shell as `nix develop ./artzig`.
      devShells.${system}.default = pkgs.mkShell ({ inherit (host) packages; } // host.env);
    };
}
