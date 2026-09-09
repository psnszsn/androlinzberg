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

## Relationship to ATL and Waydroid

Conceptually, androlinzberg is a hybrid between [Android Translation Layer
(ATL)](https://gitlab.com/android_translation_layer/android_translation_layer/)
and [Waydroid](https://github.com/waydroid/waydroid).

androlinzberg is heavily inspired by ATL: an APK runs through a compatibility
layer directly on the Linux host instead of inside a complete Android system.
Where the projects differ is how they provide Android behavior. Rather than
reimplementing the Android API surface wherever possible, androlinzberg builds
and reuses pinned AOSP code for ART and libcore, selected Android framework
classes, Android resources, HWUI, and Skia. Small host adapters are used at the
boundaries that normally depend on unavailable Android services, daemons, or
hardware.

Waydroid takes the other broad approach: it boots a complete Android system in
a Linux container and integrates that system with a Wayland desktop.
androlinzberg shares the direct Wayland presentation goal, but does not start an
Android system image, `init`, `system_server`, or the rest of Android's service
stack. Each APK is instead hosted by the compatibility runtime and connected to
Linux facilities through focused host adapters.

## Current support

- The host system must use x86_64 Linux and Wayland.
- The APK must contain x86_64 native libraries.
- The native-library bridge loads compatible Android libraries from the APK.
- Apps can use mouse, keyboard, touch, tablet, and scroll input.
- HWUI and Skia render through stock Android buffer queues; SurfaceFlinger composes task outputs for Wayland.
- FFmpeg supplies audio and video codecs.
- PipeWire supplies audio output.
- Fontconfig supplies the Android font configuration.
- Conscrypt uses the host certificate-authority bundle.

androlinzberg does not provide a complete Android system. An app can fail when
it needs an Android service that the host does not implement.

## Build

The repository supplies a Nix development shell for x86_64 Linux. The shell
contains Zig, OpenJDK, and the required native libraries.

The Git repository records the selected AOSP projects and files, but does not
contain the large source archives themselves. Follow
[`aosp/README.md`](aosp/README.md) to create the selective Repo checkout and
generate the Artzig and Androlinzberg source tarballs before the first build.

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

APK launches use stock Android window services and one Wayland toplevel per task
by default; no migration flags are needed. Press `F11` to enter or leave
fullscreen mode. Applications can also request fullscreen through their Android
window attributes. The former `--fullscreen`, `--frames` and `--java` presentation
options are unavailable on the task path.

Every launch runs inside a hosted Binder session: the launcher starts an
`azk-binder-broker` router, SurfaceFlinger, system process and task presenter, and prints the
session directory. Add a second app to the same session with `--join`:

```sh
./zig-out/bin/androlinzberg --join /run/user/1000/androlinzberg/binder-<pid>-<id> /absolute/path/to/other.apk
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

## License

Copyright © 2026 Androlinzberg contributors.

Unless a file states otherwise, original Androlinzberg code and documentation
are licensed under the [GNU General Public License version 3 or
later](LICENSE) (`GPL-3.0-or-later`). Third-party material keeps its original
license and copyright notices.
