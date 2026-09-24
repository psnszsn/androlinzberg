# The pinned Zig for sandboxed Nix builds.
#
# Zig detects the native ABI, dynamic linker and glibc version by reading the
# ELF header of /usr/bin/env. That file does not exist in the Nix build
# sandbox, so the compiler falls back to musl and every C++ compile fails on
# libc++'s musl headers. zig-overlay ships the official prebuilt compiler,
# whose probe path is compiled in; only the copy in lib/std was rewritten to a
# coreutils store path, and that copy serves programs built by Zig, not the
# compiler itself.
#
# This derivation replaces the compiled-in path with /build/e/env, a path of
# the same length inside the sandbox build directory ($NIX_BUILD_TOP).
# nix/package.nix links the sandbox's dynamically linked coreutils env there
# before running `zig build`, so detection yields the same glibc target,
# native include paths and NIX_LDFLAGS runpaths as the development shell.
# Nothing else in the compiler or its lib/ changes.
{ runCommand, perl, zig }:
runCommand "${zig.name}-sandbox" { } ''
  mkdir -p $out/bin
  ln -s ${zig}/lib $out/lib
  [ -e ${zig}/doc ] && ln -s ${zig}/doc $out/doc
  cp ${zig}/bin/zig $out/bin/zig
  chmod u+w $out/bin/zig
  count=$(grep -a -c '/usr/bin/env' $out/bin/zig)
  [ "$count" = 1 ] || { echo "expected one native ABI probe path in zig, found $count" >&2; exit 1; }
  ${perl}/bin/perl -pi -e 's|/usr/bin/env|/build/e/env|g' $out/bin/zig
  grep -a -q '/build/e/env' $out/bin/zig
  chmod u-w $out/bin/zig
''
