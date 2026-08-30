# androlinzberg

androlinzberg runs Android APK files as Wayland applications on Linux. It uses
Android Runtime (ART), the Android graphics stack, and a native Linux platform
layer.

> [!WARNING]
> androlinzberg is experimental. Application compatibility is incomplete, and
> interfaces can change.

> [!CAUTION]
> androlinzberg does not isolate an APK from your Linux user account. Run only
> APK files that you trust.

## Current support

- The host system must use x86_64 Linux and Wayland.
- The APK must contain x86_64 native libraries.
- The native-library bridge loads compatible Android libraries from the APK.
- Apps can use mouse, keyboard, touch, tablet, and scroll input.
- HWUI and Skia render through EGL to a Wayland surface.
- FFmpeg supplies audio and video codecs.
- PipeWire supplies audio output.
- Fontconfig supplies the Android font configuration.
- Conscrypt uses the host certificate-authority bundle.

androlinzberg does not provide a complete Android system. An app can fail when
it needs an Android service that the host does not implement.

## Build

The repository supplies a Nix development shell for x86_64 Linux. The shell
contains Zig, OpenJDK, and the required native libraries.

The Git repository does not contain all large source dependencies. Use a
prepared source tree that contains the required `vendor/` and `artzig/extra/`
content.

Enter the development shell:

```sh
nix develop ./artzig
```

Build the application:

```sh
zig build -j10 -Doptimize=fast
```

The first build compiles ART, the Android framework, HWUI, and Skia. It can take
a long time and use much disk space.

## Run an APK

Use an absolute path for the APK file:

```sh
zig build -j10 run -- /absolute/path/to/app.apk
```

Press `F11` to enter or leave fullscreen mode. Use `--fullscreen` to start in
fullscreen mode:

```sh
zig build -j10 run -- --fullscreen /absolute/path/to/app.apk
```

Show all launcher options:

```sh
./zig-out/bin/androlinzberg --help
```

## How it works

- The `artzig` component builds ART and libcore from pinned AOSP sources.
- Selected AOSP framework code supplies Android behavior.
- Small host adapters replace Android services that need a full Android system.
- HWUI and Skia keep Android text layout and drawing behavior.
- The Zig platform layer connects Android to Wayland, EGL, PipeWire, and FFmpeg.
- The native-library bridge loads compatible x86_64 Android libraries.

See [Architecture](docs/architecture.md) for more information.

## Development

- [Build and development](docs/development.md)
- [Testing](docs/testing.md)
- [Kotlin source policy](docs/kotlin.md)
- [Native language boundary](docs/native-boundary.md)
- [artzig build instructions](artzig/README.md)
