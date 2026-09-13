# Stock client and hosted runtime

The selected application-facing Java/Kotlin framework sources use pinned
Android 16 r1 AOSP code. The audited root framework source set has no tracked
framework stubs or modified static client sources. **The complete client stack
is not stock:** JNI, native libraries and ART/libcore still contain host
adaptations. Some JNI implementations delegate back to repository-owned JVM
code inside the app process. See the [native client audit](native-client-audit.md)
for the remaining replacements and process boundaries.

The hosted runtime supplies the services and native facilities needed by those
clients. It does not boot an Android system image or implement every device
capability. Selected server sources retain documented adaptations; see
[native and service boundaries](native-boundary.md).

## Working paths

- Original Activity/ActivityThread, Instrumentation, Dialog and window clients
  use the hosted activity/task/window services and their stock lifecycle paths.
- Stock Looper/MessageQueue, resource and APK loaders, storage, permission,
  locale, logging and runtime clients use service or native host boundaries.
- Original image APIs, NDK SurfaceControl and Android EGL buffer imports connect
  to the existing BufferQueue, gralloc, fences, HWUI and SurfaceFlinger stack.
  Wayland supplies real task windows, presentation and desktop input/integration.
- Hosted services implement named, shared-isolated and external instances,
  authenticated process identities, binding lifetime, failure/restart handling,
  notification ownership and package/user query and snapshot operations.
- AOSP ToneGenerator synthesis uses a patched native output through hosted AAudio.
  WallpaperService is available, while desktop-wide live-wallpaper presentation
  remains unsupported.
- The unchanged official AOSP WebView 140.0.7339.15 provider supports HTML,
  JavaScript, input and video. The optional overlay path is tested through the
  provider's supported developer setting in temporary data. Production retains
  upstream defaults. Provider provenance is in [AOSP packaging](../aosp/README.md#webview-provider).

## Process model and limits

Stock Process.start and Process.startWebView speak their original socket
protocols to hosted exec launchers. Children initialize their own ART VM;
RuntimeInit.applicationInit and ActivityThread.main own framework initialization.
The WebView controller executes original provider preload under UID 1053 and
launches authorized isolated children with restricted application-data visibility.

This is not Android's fork-based sharing of preloaded ART state. Ordinary AMS
service records, including Chromium renderers, use the primary exec launcher;
the dedicated WebView controller has separate preload and isolated-launch tests.
Full Android SELinux/device sandbox equivalence is not provided.

Wayland presentation runs in the separate task presenter. The PipeWire adapter
and FFmpeg codec backend run inside app processes; PipeWire connects to the
desktop's external audio daemon. These are not native Android audio/codec
service clients. Specific unsupported desktop operations and recovery behavior
are documented in [desktop behavior and limits](desktop.md).

## Verification

[Acceptance testing](acceptance.md) describes the maintained build target,
source comparison and fresh-worktree procedure. The source comparison consumes
the exact compiler response files and classes jar, reports server adaptations
separately, and rejects modified or repository-owned Android/internal JVM client
replacements in that source set. It does not audit native code or prove full
client-stack identity.
Generated sources are checked through the build's source/generator audits.
The zero-stub inventory remains enforced by [the stub audit](stubs.md).
