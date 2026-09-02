# artzig

artzig builds Android Runtime (ART) and libcore for an x86_64 Linux host. The
main androlinzberg build uses artzig as an embedded Zig dependency.

artzig uses pinned AOSP sources. The normal build uses Zig and the system JDK.
It does not use Soong output.

## Source package

The build consumes its pinned AOSP subset through the `.aosp` dependency in
`build.zig.zon`. From the parent Androlinzberg repository, follow
[`aosp/README.md`](../aosp/README.md) to initialize the selective Repo checkout
and regenerate `aosp/artzig-aosp-android-16.0.0_r1.tar.gz`.

The committed files in `build/gen/` record the native and Java build graphs.

## Development shell

Enter the Nix shell from this directory:

```sh
nix develop
```

The shell supplies Zig, OpenJDK 21, Python, and the required system libraries.

## Build the runtime

Build the relocatable runtime tree:

```sh
zig build -j10 -Doptimize=fast dist
```

The output is in `zig-out/dist/art/`. The tree contains these files:

- ART executables and native libraries
- boot class-path JAR files
- the ART boot image
- ICU and time-zone data
- the Bionic compatibility libraries

Build and run the Java example:

```sh
zig build -j10 -Doptimize=fast run
```

## Useful build steps

- `zig build -j10 gen-art` builds the native ART runtime.
- `zig build -j10 java` builds the boot class-path JAR files.
- `zig build -j10 bionic` builds the Bionic compatibility libraries.
- `zig build -j10 dist` assembles the complete runtime tree.

Use `zig build -j10 --help` to show all build steps.

## Use artzig from Zig

Add the ART module and install the runtime beside the application:

```zig
const dep = b.dependency("artzig", .{
    .target = target,
    .optimize = optimize,
});

exe.root_module.addImport("art", dep.module("art"));
@import("artzig").installRuntime(b, dep, b.getInstallStep());
```

`Runtime.create` starts ART from the installed runtime tree. `Runtime.runMain`
runs a Java entry point. The `Env` API supplies the common JNI operations.
