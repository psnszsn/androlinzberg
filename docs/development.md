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

The build also needs the prepared `vendor/` and `artzig/extra/` source trees.
These trees contain pinned AOSP and Skia sources.

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
```

Run the APK integration suite after a change to APK startup, Android lifecycle,
resources, graphics, input, media, or class loading. See [Testing](testing.md).
