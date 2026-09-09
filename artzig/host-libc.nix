# An isolated runtime libc, built by the pinned host toolchain. This does not
# override nixpkgs.glibc or rebuild unrelated development dependencies.
{ glibc }:
glibc.overrideAttrs (old: {
  pname = old.pname + "-artzig";
  patches = (old.patches or []) ++ [
    ./patches/glibc/0001-expose-host-stack-query-without-affinity.patch
  ];
})
