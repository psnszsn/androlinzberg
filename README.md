# androlinzberg

androlinzberg runs Android APK files as Wayland applications on Linux. It uses
Android Runtime (ART), the Android graphics stack, and a native Linux platform
layer.

> [!WARNING]
> androlinzberg is experimental. Application compatibility is incomplete, and
> interfaces can change.

> [!CAUTION]
> androlinzberg does not isolate an APK from your Linux user account. Each
> package gets its own Android uid, but every app process runs with your Linux
> user's permissions. Run only APK files that you trust.

## Relationship to ATL and Waydroid

Conceptually, androlinzberg is a hybrid between [Android Translation Layer
(ATL)](https://gitlab.com/android_translation_layer/android_translation_layer/)
and [Waydroid](https://github.com/waydroid/waydroid).

androlinzberg is heavily inspired by ATL: an APK runs through a compatibility
layer directly on the Linux host instead of inside a complete Android system.
Where the projects differ is how they provide Android behavior. Rather than
reimplementing the Android API surface, androlinzberg builds and reuses pinned
AOSP code for ART and libcore, the Android framework, Android resources, HWUI,
and Skia.

Waydroid takes the other broad approach: it boots a complete Android system in
a Linux container and integrates that system with a Wayland desktop.
androlinzberg shares the direct Wayland presentation goal, but does not start an
Android system image or `init`. A hosted system server starts stock Android
services in `SystemServer` order, including the activity, window, package,
package installer, user, clipboard and accessibility managers. Host adapters
stand in for what Android runs outside `system_server`, such as installd, artd
and vold, and for unavailable hardware.

## Current support

- The host system must use x86_64 Linux and Wayland.
- APK native libraries, when present, must support x86_64.
- The native-library bridge loads compatible Android libraries from the APK.
- Apps can use mouse, keyboard, touch, tablet, and scroll input.
- HWUI and Skia render through stock Android buffer queues; SurfaceFlinger composes task outputs for Wayland.
- Apps can install other apps through the stock PackageInstaller, for example from F-Droid.
- FFmpeg supplies audio and video codecs.
- PipeWire supplies audio output.
- Fontconfig supplies the Android font configuration.
- Conscrypt uses the host certificate-authority bundle.

The UI test suite runs NewPipe 0.29.1 and F-Droid 2.0.1 end to end. See
[desktop behavior and limits](docs/desktop.md) for task windows, input,
media, desktop integration and recovery, and
[native and service boundaries](docs/native-boundary.md) for what is stock and
what the host adapts.

androlinzberg does not provide a complete Android system. An app can fail when
it needs an Android service that the host does not implement.

## Build

The repository supplies a Nix development shell for x86_64 Linux. The shell
contains Zig, OpenJDK, and the required native libraries.

The Git repository records the selected AOSP projects and files, but does not
contain the large source archives themselves. Follow
[`aosp/README.md`](aosp/README.md) to create the selective Repo checkout and
generate the Artzig and Androlinzberg source tarballs and the independently
pinned WebView package before the first build.

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

To build the complete install tree as a Nix package, or to install it on
another computer, see [packaging and installation](docs/packaging.md).

## Run an APK

Applications run with ordinary user permissions. An optional
[host scheduling configuration](docs/host-setup.md) enables Android priority boosts.

Use an absolute path for the APK file:

```sh
zig build -j10 run -- /absolute/path/to/app.apk
```

The launcher also accepts a directory containing a base APK and its splits,
or a flat `.apkm`/`.xapk` archive of those APKs. It installs the original signed
APKs together through Android's PackageInstaller; native libraries must include
x86_64. Expansion-file (OBB) installation is not supported.

Launching an APK installs it through Android's PackageManagerService, then
starts its launcher activity. Installed packages, their Android uids and their
application data persist in the data root, `$XDG_DATA_HOME/artzig` by default
(`ARTZIG_DATA_DIR` selects another). Launching an installed app again installs
it as a package update: its data is kept, and, as on a device, an APK signed
with a different key or an older version is rejected.

Each task gets its own Wayland toplevel. Press `F11` to enter or leave
fullscreen mode. As in Android desktop mode, an application's own immersive
request fills its current window, and a fullscreen entered with `F11` during
that request ends when the application leaves immersive.

Every launch starts a private Android instance and prints its session
directory, `$XDG_RUNTIME_DIR/androlinzberg/binder-<pid>-<id>`. Only one running
instance can use a data root. Add another app to the running instance with
`--join`:

```sh
./zig-out/bin/androlinzberg --join "$XDG_RUNTIME_DIR"/androlinzberg/binder-<pid>-<id> /absolute/path/to/other.apk
```

The session prints Android log entries of warning level and above to
stderr, each cut at logd's entry size like logcat. `AZK_LOG_LEVEL` selects
the level, for example `AZK_LOG_LEVEL=v` for everything the runtime emits or
`AZK_LOG_LEVEL=e` for errors only. `AZK_SURFACE_TRACE=1` adds the task
presenter's per-frame submission and presentation trace.

Show all launcher options:

```sh
./zig-out/bin/androlinzberg --help
```

## How it works

- `androlinzberg` is a small dispatcher without ART. It starts a private
  supervisor, `azk-standalone`, which owns the Binder broker, SurfaceFlinger,
  the system server, the media service and the Wayland task presenter.
- An ART desktop controller asks the activity task manager to launch the app.
  The task manager starts each app process through `azk-app-process`, as zygote
  would on a device.
- `artzig` builds ART and libcore from pinned AOSP sources. The framework, HWUI
  and Skia are compiled from the same AOSP version.
- Processes talk over a hosted Binder broker. Stock Binder and Parcel JNI run on
  top of it, and host interfaces are generated from AIDL with a Zig backend.
- The Zig platform layer connects Android to Wayland, EGL, PipeWire, and FFmpeg.
- The native-library bridge loads compatible x86_64 Android libraries.

A machine-wide mode shared by several Linux users is planned but not
implemented; `--system` fails rather than starting a private instance. See
[Architecture](docs/architecture.md) for the current design and the
[process design](docs/process-design.md) for the planned one.

## Testing

`zig build -j10 jvm-test` runs the framework and class-loading tests. UI tests
are JUnit tests written against androidx UiAutomator and run on the hosted ART
against real APKs. `ui-test` runs them, and `acceptance-test` runs the complete
suite with build and source audits. See [testing](docs/testing.md) and
[acceptance testing](docs/acceptance.md).

## Development

- [Build and development](docs/development.md)
- [Contributor and agent rules](AGENTS.md), including the AOSP code policy
- [Packaging and installation](docs/packaging.md)
- [Testing](docs/testing.md) and [acceptance testing](docs/acceptance.md)
- [Desktop behavior and limits](docs/desktop.md)
- [Process design](docs/process-design.md)
- [Kotlin source policy](docs/kotlin.md)
- [Native language boundary](docs/native-boundary.md)
- [artzig build instructions](artzig/README.md)

## License

Copyright © 2026 Androlinzberg contributors.

Unless a file states otherwise, original Androlinzberg code and documentation
are licensed under the [GNU General Public License version 3 or
later](LICENSE) (`GPL-3.0-or-later`). Third-party material keeps its original
license and copyright notices.
