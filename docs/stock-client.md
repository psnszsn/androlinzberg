# Stock client and hosted runtime

The selected application-facing Java/Kotlin framework sources use pinned
Android 16 r1 AOSP code. The audited root framework source set has no tracked
framework stubs or modified static client sources. **The complete client stack
is not stock:** JNI, native libraries and ART/libcore still contain host
adaptations. Framework JNI now uses the pinned clients with the exact corrections
and host boundaries recorded in the [native client audit](native-client-audit.md).
The agreed exceptions are the hosted libbinder/AZKB transport and ART/libcore
runtime, plus native host backends such as EGL, gralloc and Linux scheduling.

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
- Stock AudioTrack, AudioSystem and ToneGenerator (Java, JNI and libaudioclient)
  reach the hosted `media.audio_flinger` / `media.audio_policy` services; the
  shared control block, timestamps and underrun accounting are the AOSP ones.
  WallpaperService is available, while desktop-wide live-wallpaper presentation
  remains unsupported.
- Original networking JNI and NetdClient share process policy across Java and
  Android native sockets/DNS. The inherited Linux network namespace supplies
  routing; unavailable Android network domains, VPN exemptions and BPF tagging
  return errors.
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

Wayland presentation runs in the separate task presenter. The PipeWire audio
sink runs in the system process behind the native AudioFlinger adapter and
connects to the desktop's external audio daemon (a real-time null sink stands
in when none is reachable). Stock MediaCodec JNI and native CCodec clients
reach the separately supervised Codec2 media service over Binder. FFmpeg and
VAAPI decoding run in that service; system Mesa supplies GPU presentation. Specific
unsupported desktop operations and recovery behavior are documented in
[desktop behavior and limits](desktop.md).

## Verification

[Acceptance testing](acceptance.md) describes the maintained build target,
source comparison and fresh-worktree procedure. The source comparison consumes
the exact compiler response files and classes jar, reports server adaptations
separately, and rejects modified or repository-owned Android/internal JVM client
replacements in that source set. It does not audit native code or prove full
client-stack identity.
Generated sources are checked through the build's source/generator audits.
The zero-stub inventory remains enforced by [the stub audit](stubs.md).
