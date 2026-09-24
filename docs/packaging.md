# Packaging and installation

The repository root is a Nix flake whose default package is the complete
install tree that `zig build` publishes to `zig-out/`: the launcher, the ART
runtime, the hosted framework, resources and the bundled apps. The package
follows the same shape as other Zig projects packaged with Nix: the pinned Zig
from `zig-overlay`, a pre-fetched Zig package store linked to `zig-pkg`, and
`zig build --release=fast -p $out` inside the sandbox.

The launcher resolves its tree relative to its own executable, so the result
is one relocatable store path. Every binary carries a Nix store RUNPATH,
including the patched glibc from `artzig/host-libc.nix`, so a plain copy of
`zig-out/` to another machine does not run; the Nix closure does.

## Requirements

- x86_64 Linux with Nix and flakes enabled. NixOS is not required on the
  target, but the target needs a Wayland session, working EGL (on NixOS through
  `/run/opengl-driver`; elsewhere through `nixGL` or an equivalent) and a
  PipeWire socket.
- The pinned AOSP source packages (`aosp/README.md`). They are not part of
  the Git tree, so the flake takes them from the Nix store as fixed-output
  files. Register each archive once on the building machine:

  ```sh
  nix store add --mode flat aosp/androlinzberg-aosp-android-16.0.0_r1.tar.gz
  nix store add --mode flat aosp/androlinzberg-webview-140.0.7339.15.tar.gz
  nix store add --mode flat aosp/artzig-aosp-android-16.0.0_r1.tar.gz
  ```

  Their flat SHA-256 hashes are declared in `nix/aosp-archives.nix`; the
  corresponding Zig hashes are the `N-V-` entries in `nix/zig-deps.nix`. When
  the packagers regenerate an archive, update both.

## Build

```sh
nix build
```

The sandbox cannot reuse `.zig-cache`, so each package build compiles ART,
Skia, HWUI and the framework from scratch. The build uses at most
`NIX_BUILD_CORES - 2` jobs and targets the `x86_64_v3` CPU baseline instead
of the build host's CPU, so the result runs on other machines.
`./result/bin/androlinzberg` is the launcher.

The package compiles with `nix/zig-sandbox.nix`, a copy of the pinned Zig
whose native-ABI probe reads `/build/e/env` instead of `/usr/bin/env`. The
official compiler falls back to musl when that file is missing, which is the
case inside the sandbox, and every C++ compile then fails on libc++'s musl
headers. The build directory link makes detection produce the same glibc
target, native include paths and runpaths as the development shell.

The package keeps the binaries as Zig produced them, with two exceptions.
Runpath entries that point at the build cache or at build-only tools (Zig,
the JDK, Python, flex, bison) are removed, and remaining string references
to those tools and to the AOSP source packages are mangled with
`remove-references-to`, so they stay out of the runtime closure. The rest of
the runpath is not shrunk because the launcher dlopens EGL, PipeWire, libva
and FFmpeg from it, and symbol tables are kept for ART's stack
symbolization. The launcher is wrapped only to put `bash`, `coreutils` and
`util-linux` on `PATH` for the dexopt adapter script.

Other outputs:

- `nix build .#zig-deps` builds only the Zig package store, which is the
  quickest way to check the archive registration and the dependency hashes.
- `nix build .#zig-sandbox` builds the sandbox compiler alone.
- `nix run . -- /absolute/path/to/app.apk` builds and launches.
- `nix develop` at the repository root is the same shell as
  `nix develop ./artzig`.

## Install on another computer

Copy the closure with `nix copy`; nothing is rebuilt on the target:

```sh
nix build
nix copy --to ssh://user@target ./result
ssh user@target 'nix profile install /nix/store/<hash>-androlinzberg-0.0.0'
```

`nix path-info ./result` prints the store path to install. Alternatively add
this repository as an input of the target's NixOS configuration and put
`packages.x86_64-linux.default` in `environment.systemPackages`. Import
`nixosModules.hostScheduling` and set `programs.androlinzberg.schedulingUsers`
to enable Android's thread-priority range, as described in
[host setup](host-setup.md).

Building on the target instead of copying requires registering the three
AOSP archives there as well.

## Maintaining the dependency list

`zon2nix` cannot express the local `file:` archives or the `path` dependency
on `artzig`, so `nix/zig-deps.nix` is maintained by hand. When
`build.zig.zon` or `artzig/build.zig.zon` changes a dependency hash, rename the
matching entry; when a URL changes, refresh the entry's Nix hash (`nix build
.#zig-deps` reports the expected value on mismatch).

The build inputs live in `artzig/nix/host-packages.nix`, shared between the
package and the development shell. Add a library or generator that a build
step needs to its `build` list; tools that only tests or refresh scripts use
go to `development`.
