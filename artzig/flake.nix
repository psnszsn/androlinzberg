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
      host = import ./nix/host-packages.nix { inherit pkgs; zig = zigPinned; hostLibc = hostLibc.out; };
    in {
      nixosModules.hostScheduling = import ./nixos/host-scheduling.nix;
      # Zig + a JDK for javac/d8, Repo for refreshing the AOSP source packages,
      # and the native dependencies used by Artzig and Androlinzberg. The list
      # lives in nix/host-packages.nix, shared with the repository's package.
      devShells.${system} = {
        default = pkgs.mkShell ({ inherit (host) packages; } // host.env);
      };
      packages.${system} = {
        zig = zigPinned;
        host-libc = hostLibc.out;
      };
    };
}
