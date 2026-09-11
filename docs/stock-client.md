# Stock client and hosted runtime

The selected application-facing framework sources use pinned Android 16 r1
AOSP code. There are no tracked framework stubs or modified static client
sources. AOSP owns the client API signatures, validation, exceptions, lifecycle
callbacks, resource handling and service protocols. Repository-owned JVM
adapters and test helpers live outside the Android/internal client namespaces.

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
- Original ToneGenerator synthesizes audio through the existing output.
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

Existing Wayland, PipeWire and FFmpeg facilities are used at their native
boundaries. Specific unsupported desktop operations and recovery behavior are
documented in [desktop behavior and limits](desktop.md).

## Verification

[Acceptance testing](acceptance.md) describes the maintained build target,
source comparison and fresh-worktree procedure. The source comparison consumes
the exact compiler response files and classes jar, reports server adaptations
separately, and rejects modified or repository-owned client replacements.
Generated sources are checked through the build's source/generator audits.
The zero-stub inventory remains enforced by [the stub audit](stubs.md).
