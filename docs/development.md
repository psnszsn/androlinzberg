# Build and development

## Requirements

Development currently requires x86_64 Linux. The application runs on a Wayland
session and uses EGL for graphics.

The repository provides a Nix shell. Enter it from the repository root:

```sh
nix develop ./artzig
```

The shell contains the supported Zig compiler, OpenJDK 21, build tools, and
native library headers.

It also builds the isolated glibc runtime used by hosted ART. Chromium's Android
sandbox denies CPU-affinity queries, so ART uses a private stack-only query
inside that pinned libc. The launcher selects it at build time through
`AZK_HOST_LIBC`; an explicit `-Dhost-libc=/absolute/runtime/path` overrides the
shell value. The normal pthread attribute API and sandbox policy are unchanged.
Run `zig build -j10 host-stack-test` to verify stack and guard bounds under
affinity denial. The glibc patch is in `artzig/patches/glibc/`, and its isolated
Nix derivation is `artzig/host-libc.nix`.

The build consumes pinned AOSP source tarballs through the Zig package manager.
Follow [the AOSP package instructions](../aosp/README.md) to initialize the
selective Repo checkout and regenerate those archives.

## Build

Build and install all components in `zig-out/`:

```sh
zig build -j10
```

Use an optimized build for normal application use:

```sh
zig build -j10 -Doptimize=fast
```

List the available build steps:

```sh
zig build -j10 --help
```

## Main outputs

- `zig-out/bin/androlinzberg` is the APK launcher.
- `zig-out/bin/art/` is the installed ART runtime.
- `zig-out/framework/framework.jar` is the hosted Android framework.
- `zig-out/res/framework-res.apk` contains Android framework resources.
- `zig-out/lib/libandroid_platform.so` contains the native Android platform
  interface.

`zig-out` is only the publication layout. Build, run, and test steps consume
the cache artifacts that produced it; they do not read the install prefix or
depend on the global install step.

## Build graph conventions

The Zig declarations are the source of truth for build inputs and outputs:

- `build/generator_sources.zig` declares generator inputs and expected Java
  outputs.
- `build/java_sources.zig` and `artzig/build/java_sources.zig` declare the
  curated JVM source sets.
- `artzig/build/gen/art.zig` declares the native ART source graph and typed
  generated-artifact groups.
- `build/application.zig` owns the cacheable application layout; installation
  publishes that layout without becoming an input to another step.

Cross-module paths are `std.Build.LazyPath` values. Known generated files stay
individual files; directory outputs are reserved for artifacts that are
actually trees, such as class directories, include roots, system modules, and
runtime layouts. Response files are generated from the same exact `SourceSet`
inputs and are only command-line transport.

## Source changes

Use the pinned AOSP tree as the reference for Android behavior. Check the AOSP
implementation before you change an `android.*` or `com.android.internal.*`
class.

Reuse AOSP source when the required dependencies are available. Use a small
host adapter when an Android process, daemon, or hardware service is absent.

Use Kotlin for new repository-owned JVM code. Keep AOSP and generated source in
its original language. See [Kotlin source policy](kotlin.md).

Use Zig for new platform logic. Keep C++ at the AOSP graphics and interface
boundary. See [Native language boundary](native-boundary.md).

## Checks

Run the smallest applicable check first:

```sh
zig build -j10 native-test
zig build -j10 jvm-test
zig build -j10 binder-test
zig build -j10 system-test
zig build -j10 testkit-test
zig build -j10 cpp-audit
zig build -j10 graph-audit
```

Run the APK integration suite after a change to APK startup, Android lifecycle,
resources, graphics, input, media, or class loading. See [Testing](testing.md).
