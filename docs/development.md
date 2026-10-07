# Build and development

## Requirements

Development currently requires x86_64 Linux. The application runs on a Wayland
session and uses EGL for graphics.

The repository provides a Nix shell. Enter it from the repository root:

```sh
nix develop ./artzig
```

The shell contains the supported Zig compiler, OpenJDK 21, build tools, and
native library headers. The list is `artzig/nix/host-packages.nix`, which the
Nix package in [packaging](packaging.md) shares.

It also builds the isolated glibc runtime used by hosted ART. Chromium's Android
sandbox denies CPU-affinity queries, so ART uses a private stack-only query
inside that pinned libc. The launcher selects it at build time through
`AZK_HOST_LIBC`; an explicit `-Dhost-libc=/absolute/runtime/path` overrides the
shell value. The normal pthread attribute API and sandbox policy are unchanged.
Run `zig build -j10 host-stack-test` to verify stack and guard bounds under
affinity denial. The glibc patch is in `artzig/patches/glibc/`, and its isolated
Nix derivation is `artzig/host-libc.nix`.

Executables linked against that runtime also boot through its dynamic loader.
Zig embeds the host system's loader, and glibc aborts at startup ("stack
smashing detected") when the system's glibc version differs from the pinned
one, as it does after a host upgrade. The shell exposes the pinned loader under
a short store path as `AZK_HOST_LOADER` (`-Dhost-loader=` overrides it), and
`tools/set-interpreter.py` writes it into each emitted executable in place;
`patchelf` cannot relocate the interpreter in Zig-linked executables.

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

### AIDL compiler and the Zig backend

`build/aidl.zig` compiles the pinned AOSP AIDL compiler (`system/tools/aidl`)
from source; flex and bison come from the development shell. Every backend the
build uses (Java, cpp, ndk, rust) runs this executable, so generated code
follows the pinned Android 16 generators rather than the older generic-linux
SDK prebuilt. `zig build -j10 aidl-tool` installs it as `zig-out/bin/aidl`.

The same executable accepts `--lang=zig`. That backend is repository-owned
(`tools/aidl-zig`) and is linked beside the unmodified compiler rather than
patched into it: `main.cpp` selects it, the pinned front end parses and
validates, and `generate_zig.cpp` walks the resulting AST. It emits data types
and codec tables only. The runtime generated code imports as `aidl` implements
parcelable framing, nullability, status headers, proxy calls and server
dispatch once, through comptime reflection. `src/binder/aidl_core.zig` holds
the transport-independent half; two transports instantiate it:

- `src/binder/aidl.zig`, over the `libbinder_ndk` C ABI, for ordinary clients
  and services. Generated Zig is wire-compatible with the other backends by
  construction.
- `src/binder/broker_aidl.zig`, over raw AZKB parcel frames, for the broker,
  which serves `android.os.IServiceManager` without being a libbinder client.
  Its Binder values are per-peer object references.

The build chooses the transport by the module it names `aidl`, so one
generated interface serves both.

`aidl.zigModule` in `build/aidl.zig` turns a list of AIDL files into a module
that exposes each type under its canonical name:

```zig
const IProbe = @import("probe").@"dev.artzig.probe.IProbe";

var server: IProbe.Server(Implementation) = undefined;
server.init(gpa, io, &implementation);          // serve
const client = try IProbe.Client.fromBinder(binder); // call
const sum = try client.add(40, 2);
```

A server method is `fn name(self, call: *aidl.Call, <in arguments>...) !Result`;
`Result` is the return value, or `method.<name>.Reply` when the method also has
`out` parameters. Reply allocations use `call.arena`. Decoded Binder and
descriptor arguments are released when the method returns, so a method that
keeps one clones or duplicates it. `aidl.decodeArgs`/`encodeReply` serve a
transaction that must hop threads before it is answered: the Wayland task
presenter (`src/platform/task_presenter.zig`) admits requests on Binder
threads, serves `IWaylandTaskPresenter` on its Wayland thread through a
`aidl.LocalObject`, and can answer `startNativeDrag` after later protocol
dispatch. A versioned interface (`--version`/`--hash`) gets `version`, `hash`
and the `getInterfaceVersion`/`getInterfaceHash` transactions, which
`aidl.Server` answers itself. A generic parcelable is a function of its
arguments' codecs, e.g. `Pair(c.String, c.Parcelable(Point))`. An unstructured
parcelable such as `android.view.Surface` is supplied by an `aidl_custom`
module as `@"<canonical name>"` with `writeToParcel`/`readFromParcel`.
`ParcelableHolder` and the Java-only types are not generated.

`zig build -j10 aidl-zig-test` compiles `tests/aidl-zig` with both the Zig and
the pinned NDK backend and drives Zig→Zig, Zig→NDK and NDK→Zig across a real
broker.

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
zig build -j10 cpp-audit
zig build -j10 graph-audit
```

Run the APK integration suite after a change to APK startup, Android lifecycle,
resources, graphics, input, media, or class loading. See [Testing](testing.md).
