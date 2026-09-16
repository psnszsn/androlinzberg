# Native client audit

Audit updated 2026-09-16. **The complete client stack is not stock AOSP.**
The JVM source check covers selected framework source files. Android-native
implementations, runtime libraries and process placement still contain substantial
host adaptations.

The complete `acceptance-test` target passed on 2026-09-16 for the original
PDF renderer and modern GIF decoder checkpoint. All 28 APK cases passed, with 88 recorded
actions, zero structured runtime issues and no failure artifacts. Native, JVM,
system, desktop, video, source-provenance and build checks passed. Source
packages, pins and installed outputs are current.

This is a source and architecture audit, not an exhaustive per-method
compatibility certification. The follow-up validation record distinguishes
exercised behavior from source provenance. It traces production
registration, build selection, native library mappings, host backends and the
pinned reference. Existing passing acceptance tests establish their exercised
behavior; they do not establish native provenance or stock process boundaries.

## JVM source check scope

[`aosp-source-identity.py`](../tools/aosp-source-identity.py) compares the root
framework build's static Java/Kotlin/annotation inputs against its explicitly
supplied AOSP package and checkout. Its last acceptance report found 7,119
unchanged inputs and 64 modified server inputs, with no modified or
repository-owned `android.*` / `com.android.internal.*` client replacements in
that selection.

It does **not** compare JNI, C/C++, ART, bionic, ELF dependencies, native symbol
resolution, or the separately built Artzig/libcore source closure. Generated
sources have separate generator checks. `dev.artzig` sources are recorded as
host sources, even when JNI calls them back to implement an Android operation
inside the app. An unchanged Java declaration of a native method therefore
says nothing about whether its implementation is stock. Zero framework stubs
also does not mean zero unsupported native operations.

## Compiled native and Skia check

`native-client-source-test` consumes the actual 2,581 native-island and linked Skia compiler
commands and verifies the linked object files. The current selection contains
2,261 unchanged AOSP units, 22 modified units, 21 owned units and 277 generated
units. Its 101 required client sources must remain original and present. Forty-two
sensitive client/service boundaries are preprocessed again with those commands;
the report records effective macros and the 2,583 headers actually consumed.
This catches disabled Android branches and incompatible Binder header selection.

The check also verifies unique JNI registrar ownership and rejects FFmpeg/VAAPI
dependencies in the platform client ELF. It runs in `acceptance-test` and writes
`zig-out/test-results/native-client-source.json`. The check and its seven failure
regressions passed, along with the build-tool, C++ and graph audits. It does not
yet inspect translation units inside other linked static libraries, ART, or runtime
loader resolution. Those remain necessary before claiming complete native
provenance. These counts are compilation inputs, not a stock-client percentage.

## Skia and EGL dependency follow-up

Skia now uses its original Soong configuration writer with the selected GN
features, Android hardware-buffer support, Vulkan dependencies and JPEG gainmaps.
The native audit checks the Android buffer implementation and matching Vulkan
library configuration. Native and app EGL share one dispatch table for context
creation, frame validation, damage swaps and damage-region translation. All
actual rendering still uses system Mesa. The window regression alternates normal
and damage swaps and verifies pixels, timestamps, GL state and the same DMA-BUF
inode at the consumer, without another presentation copy.

The handwritten input/HWUI flag headers were deleted. The original input flag
generator uses production mode with read-only defaults, preserving exported flags
that force-read-only mode omits. Full acceptance passed. The hosted HWUI renderer
and GL import adaptations have since been removed. Their original sources link
and full acceptance passes, including shader-cache persistence, EGL ownership,
hardware-bitmap geometry, DMA-BUF identity and SurfaceView/TextureView video.
Android Vulkan driver support is not established by compiling that source.

## Evidence and counting rules

[`native-client-audit.json`](native-client-audit.json) records archive checksums,
reference revisions, modified native paths and delegated registration modules.
Both archives were read directly. The framework archive was rechecked during
the Binder, utility and media/graphics JNI migrations. The latest refresh includes
original HWUI and Skia buffer clients and adds the pinned Bionic allocator
declarations used by original Activity, ActivityThread and DDM native-heap JNI.
The image follow-up adds pinned GIF/JPEG-R dependencies and restores original
GLConsumer, SyncFeatures, BufferQueueCore and SurfaceTexture EGLConsumer. Every regular `.c`, `.cc`, `.cpp`, `.cxx`,
`.h`, `.hpp`, `.hxx`, `.S`, `.s` and `.inl` member was compared byte-for-byte
with the corresponding pinned checkout path. Both current archives contain
multiple top-level directories; their paths were retained. Tracked reference
state was checked clean in all 11 projects containing differences/additions.

| Archive | Unchanged native files | Modified native files | Added native files |
|---|---:|---:|---:|
| Framework | 16,052 | 60 | 0 |
| Artzig | 3,460 | 24 | 1 |

These are **packaged source/header counts**, including server, host, compiler
and unused members. They are not counts of compiled client files, and must not
be converted into a stock-client percentage. The added file is
`art/runtime/jni/artzig_native_loader.h`. Repository-owned sources, compile-time
host branches and third-party host libraries are additional adaptations. A file
can be byte-identical to AOSP while compiling its host implementation instead
of its Android implementation.

Installed-DSO spot checks also found `register_localized_collators` and
`register_android_functions` defined in `libandroid_platform.so`, and
`AMediaCodec_createDecoderByType` and `AAudio_createStreamBuilder` in
`libandroid_platform.so`. `azk_register_binder_jni` remains in
`libbinder_ndk.so`. This corroborates
the build selection; it is not a complete dynamic-symbol resolution audit.

[`main.zig`](../src/main.zig) calls the Binder and graphics registrars and then
[`host_natives.zig`](../src/platform/host_natives.zig) when booting APK ART.
[`framework_jni.zig`](../src/binder/framework_jni.zig) has 8 delegated
registration modules, plus its own registrations and the Binder registrar.
That number is not a replaced-class count: it includes host-only cursor helpers
and dev.artzig EGL/NDK regression helpers. Registering a helper does not prove
an app uses it.

## Audio and media: app-side replacements

### AudioTrack, audio policy and AAudio (stock)

Current playback path:

```text
app: stock AudioTrack.java / AudioSystem.java / ToneGenerator.java
  -> original android_media_AudioTrack.cpp, android_media_AudioSystem.cpp, ...
  -> pinned libaudioclient (AudioTrack, AudioSystem, AudioTrackShared, AIDL conversions)
  -> hosted Binder: media.audio_policy, media.audio_flinger, per-track IAudioTrack
system process: src/native/audio (HostAudioPolicyService, HostAudioFlinger, HostAudioTrack)
  -> shared control block (memfd) + azk_pipewire.c stream per track
host desktop: PipeWire daemon -> output device (or a real-time null sink)
```

The client stack is stock down to the Binder transport: the Java classes, the
JNI and `libaudioclient` are the pinned sources, compiled unchanged. The
`AudioTrackShared` control block, timestamps, underrun counters, start
threshold, volume shaper and session ids follow the AOSP protocol because the
pinned client and server proxies are both compiled. The services are host
adaptations (`docs/native-boundary.md`): `HostAudioPolicyService` follows
`AudioPolicyManager::getOutputForAttr` and the default engine's product
strategy table for one speaker device; `HostAudioFlinger` follows
`AudioFlinger::createTrack` and `PlaybackThread::Track` for one primary output
(48 kHz, float, 1024-frame period, 40 ms latency). The stock
`AudioFlinger`/`AudioPolicyManager` remain uncompiled because they require
audio HALs. AudioRecord, effects, MMAP/offload and direct outputs fail with
the AOSP status for an absent input/HAL feature. The AudioSystem JNI's
property listener uses a host `CachedProperty` (libbase patch) that polls the
shared property area.

`libaaudio.so` is the stock library: its legacy `AudioStreamTrack` path runs
over the stock AudioTrack client, and its MMAP client is never selected because
the host AudioFlinger reports no MMAP policy.

**Additional source-confirmed behavior:** when no PipeWire daemon is reachable,
each track renders to a null sink that consumes frames in real time (the
audio.stub HAL model), so playback position advancing does not prove audible
output; verification of actual PipeWire delivery needs a daemon with a
capturable sink.

### MediaCodec and its NDK API

Production selection now uses the original client stack:

```text
app: stock MediaCodec.java -> original MediaCodec JNI -> MediaCodec/CCodec
app NDK: original NdkMediaCodec.cpp ------------------> MediaCodec/CCodec
  -> original Codec2 AIDL client -> hosted Binder
azk-media-service: original AIDL ComponentStore + SimpleC2Component
  -> host FFmpeg/VAAPI component -> original allocator-owned output buffers
app: original Codec2/IGBA -> stock Surface/BufferQueue -> SurfaceFlinger
```

The launcher no longer links the FFmpeg backend. The old codec JNI, Kotlin
state machine, static catalogue, NDK-to-Java facade and hardware-frame bridge
are deleted. Stock MediaCodecList discovers software audio and hardware video
through separate declared stores and the original XML parser. Actual VAAPI
profiles gate hardware discovery. Encoding and protected decoding remain
unavailable host capabilities.

VAAPI performs the existing YUV-to-RGB conversion directly into the buffer
supplied by the original Codec2 allocator, after its acquire fence. Returning
that original block preserves GraphicsTracker identity and Surface migration;
there is no additional GPU copy or software pixel staging. The stock clients
retain codec state, asynchronous callbacks, format changes and buffer lifetime.
Production activation passed the native, JVM, system and full APK regressions.

MediaCrypto and MediaDrm now compile original JNI, JCrypto/JDrm, CryptoHal,
DrmHal, metrics and AIDL/HIDL factory clients. The frozen DRM V1 NDK protocol
and DRM 1.0–1.4 HIDL protocols are generated from the pin. The host advertises
no VINTF DRM instances and has no HIDL service manager or DRM plugin, so this
restores client provenance, not protected playback. The resource-manager client
is linked for session bookkeeping. The production session now also publishes
the original resource-manager service in its native media process.

### MediaPlayer and metadata retrieval

Original MediaPlayer and MediaMetadataRetriever JNI and native clients now
reach the original `MediaPlayerService` in `azk-media-service`. NuPlayer uses
the same stock Codec2 and AudioTrack clients as other applications. The former
local-state JNI adapters and `HostedMediaPlayer` have been removed. A small
registration wrapper exposes the original static MediaPlayer JNI registrar.

The stock extractor service now links WAV, MP3, AAC, MP4, Matroska/WebM, Ogg,
FLAC, AMR and MPEG-2 plugins. Their implementation sources are unchanged; a
factory patch registers these linked plugins instead of discovering Android
APEX shared objects. Plugin-private support symbols remain hidden.

Raw video consumers explicitly request CPU_READ through CCodec. The hosted
codec then reads decoded YUV into shared YV12 planes for original thumbnail
conversion. Surface consumers retain direct RGB DMA-BUF output and system
Mesa presentation without an extra GPU copy.

The framework module suite passes: eight container fixtures, remote FD slices
and callback sources, decoded/scaled thumbnail pixels, and the public player
prepare/play/pause/seek/completion/reset/release lifecycle. Native allocator,
codec and resource tests also pass. RenderEngine, buffer/Surface clients, stock-video
pixel assertions, system services and all 28 APK cases passed (90 recorded
actions, zero structured runtime issues). Source identity, Binder JNI, C++/graph
audits and NDK ABI checks passed.

### Camera clients

Original legacy Camera JNI, camera metadata JNI and native Camera/CameraBase
clients now share the generated native camera service protocol. The hosted JNI,
Kotlin camera adapter and Java vendor-tag Binder bridge have been removed.
The standard `config.disable_cameraservice=true` configuration describes this
host without a capture backend and avoids waiting for an absent cameraserver.
This restores client provenance; it does not add camera capture hardware. Native
Binder, public framework, system and all 28 APK tests passed (88 recorded
actions, zero structured runtime issues). Vendor-tag fixtures run with system
identity in the existing test server; application publication restrictions remain
unchanged. CameraManager now follows the standard disabled-service exception
path.

### Sensors

SystemSensorManager now uses the original JNI and libsensor; the NDK sensor
entry points use the same client. The system process publishes original
SensorService before any service can request a sensor manager. A server
constructor patch initializes normal mode when no physical HAL exists.
The factory uses the same Android ABI flags as the service, including the
Android layout of `utils::Thread`.

Discovery is empty on this host. Event queues use actual BitTube connections;
enabling unavailable sensors fails, and direct-channel creation returns the
original service-backed failure. The old sensor JNI replacement is deleted.
This restores client provenance without claiming physical sensor support.

Framework and image-queue tests passed, including original JNI and NDK sensor
contracts. System, native graphics, stock-video pixel checks, source/Binder
provenance and build/ABI audits passed. All 28 APK cases passed with 88 recorded
actions and zero structured runtime issues. Surface, ImageReader and image
utility JNI now also compile unchanged with their Android branches enabled.
Fresh video screenshots were inspected; installed outputs and the source archive
were refreshed.

**Already across a service boundary:** MediaExtractor uses original Java/JNI,
NuMediaExtractor and Binder to the native `media.extractor` service published by
the system process. Its linked-plugin factory has a host patch. MediaMetrics
and MediaDescrambler also reuse native client/protocol code, although native
metrics collection and CAS hardware services are not supplied. MediaCodec now also uses a separate service through its original native client.

## Binder is a foundational native client replacement

The original `android_util_Binder.cpp`, `android_os_Parcel.cpp`,
`android_os_ServiceManager.cpp` and `android_os_ServiceManagerNative.cpp`
now supply the Java JNI boundary. [`ndk.zig`](../src/binder/ndk.zig) retains the
hosted NDK ABI and AZKB runtime. The C++
[`Binder.cpp`](../src/native/binder/Binder.cpp) and
[`Parcel.cpp`](../src/native/binder/Parcel.cpp) provide its source facade.
They execute in both clients and services; the broker is a separate process.
The directory name `src/binder` does not mean its contents execute server-side.

Pinned `android_util_Binder.cpp`, native libbinder and libbinder_ndk own the
original implementations. Native `ProcessState.cpp` opens the Binder driver;
AZKB replaces that transport, object/parcel storage and process-state machinery.
Generated stock AIDL proxies can work against the facade without making the
facade stock or establishing binary compatibility with arbitrary Android DSOs.

For literal stock native clients, restore these libraries and choose a
compatible kernel Binder path or a narrowly defined driver/OS boundary below
them. Keeping the present facade would be an explicit exception to the goal.
Service-backed audio/media alone cannot resolve this distinction.

### Stock Binder JNI over the existing facade

All four JNI units are compiled unchanged from pinned Android 16 r1. Their
C++ interfaces do not directly open the Binder driver, so restoring them did
not require replacing AZKB. Hosted libbinder and the NDK implementation remain
explicit native adaptations.

Original JNI now owns Java's C++ Parcel, JavaBBinder holder and proxy objects.
[`BinderJniBridge.cpp`](../src/native/BinderJniBridge.cpp) supplies the narrow
Java/NDK conversion and typed service-manager boundary. Parcel wrappers share
AZKB storage; native conversions retain object identity and JNI reference
lifetime. The old Zig JNI registrations, holders/finalizers, raw-AParcel Java
callback path and AospParcelAbi.cpp have been removed. HIDL reuses the original
Binder exception reporter instead of its former duplicate.

Facade additions include range/string validation and accounting, work-source
headers, explicit identity and nested transaction policy restoration, Binder
extensions, proxy UID accounting/watermark callbacks and the existing broker's
context object. Freezer notifications remain unsupported. These changes make
the original JNI callable; they do not certify every hosted libbinder contract
or binary compatibility with arbitrary Android C++ libraries.

`binder-jni-source-test` consumes the actual native-island compile inputs and
include ordering, compares the four files with a clean pinned reference, checks
facade header selection and ELF registrar ownership, and rejects the obsolete
native layout paths. Its report is
`zig-out/test-results/binder-jni-source.json`. It is part of acceptance alongside
`ndk-abi`. See [acceptance testing](acceptance.md#binder-jni-provenance) for
commands and generated reports.

## Graphics, input and WebView

**App-side adaptations remain:**

- HWUI now compiles the original EglManager, RenderThread, CacheManager,
  CanvasContext, Skia GL/Vulkan pipelines, layer updates, Bitmap, Readback and
  WebView functor implementations. Original Skia hardware-buffer factories
  and GL imports use the shared EGL image driver seam. The owned GPU pipeline
  and hardware-buffer rendering helper were deleted. System Mesa still owns
  rendering; compiling the Vulkan client does not establish Android Vulkan
  hardware-buffer driver support.
- EGL10, EGL14, EGL15 and EGLExt compile their original JNI. The host EGL
  driver adapter translates Android windows to Mesa GBM drawables and delivers
  the rendered DMA-BUF itself through the original Surface/BufferQueue producer.
  It uses system Mesa and has no presentation blit or private copy context.
  Zig owns GBM leases, release-fence waits, resizing and deferred destruction.
  Explicit CPU mappings adapt channel order and row stride when Android's
  requested format differs from Mesa's storage. GPU presentation remains in
  the existing compositor backend.
  Generated GLES bindings remain reused with native fixes for JNI array
  bases and buffer bounds.
- HardwareRenderer snapshots use AOSP's AImageReader / RenderProxy algorithm.
  Original Bitmap and Skia hardware-buffer factories own GPU import through
  the shared EGL driver. The Kotlin snapshot and its JNI/RenderNode ownership
  bridges have been deleted.
- Software Bitmap storage now uses Android RGBA consistently across Skia and
  its consumers. Original GLUtils uploads those bytes directly; the host upload
  copy and ImageDecoder layout workaround are removed. NDK raw-pixel and
  MediaMetadataRetriever thumbnail tests exercise the byte order.
- Bitmap and GraphicsJNI use their original sources and JNI registration.
  Host color-type overrides have been removed.
- ADisplay compiles original `libs/nativedisplay/ADisplay.cpp` and queries
  SurfaceFlinger for physical displays, modes, refresh rates, vsync offsets and
  composition preferences. The fixed hostgraphics display implementation is
  excluded. The compositor still owns the hosted display policy and hardware
  description; this does not independently discover additional Wayland outputs.
- libui's allocator/mapper selects
  [`HostGralloc.cpp`](../src/native/graphics/HostGralloc.cpp) and
  [`graphics_buffer.zig`](../src/platform/graphics_buffer.zig). This boundary
  is used inside clients as well as services. Stock allocator/mapper loading
  could retain a host implementation behind its HAL; allocation is not
  inherently all server-side on Android.
- GLConsumer, SyncFeatures, BufferQueueCore and SurfaceTexture EGLConsumer now
  use original sources. Android EGL native-buffer imports and balanced display
  ownership use the shared Mesa driver below these clients. SurfaceComposerClient
  and some native Surface/Image wrappers still retain patches for transport,
  lifetime, portability or failure behavior. See the exact file inventory.
- The original WebView APK is unchanged, but it loads these adapted native
  graphics/media/runtime dependencies. APK provenance is not provenance of
  the full process it runs in.

**Already outside app presentation:** Wayland windows and desktop input live in
[`task_presenter.zig`](../src/platform/task_presenter.zig); SurfaceFlinger and
its host composer run in the supervised SF process. WMS/ATMS and hosted input
policy run in the system process. InputDispatcher has host policy/crypto/drag
patches. App MessageQueue, InputChannel and InputEventReceiver/Sender JNI reuse
stock code; the Looper poll accessor is an additional host/test seam.
[`pointer_icon_jni.zig`](../src/binder/pointer_icon_jni.zig) registers methods on
`dev.artzig.window.WaylandCursor`, not replacement PointerIcon natives.

Remaining graphics adaptations are concentrated in EGL, allocator, libgui,
loader and compositor interfaces. The original HWUI clients render in the app
process, while the separate presenter and SurfaceFlinger own presentation.

## Runtime, core utilities and additional client differences

| Area | Current deviation and source | Required boundary / consequence |
|---|---|---|
| ART and process bootstrap | [ART patches](../artzig/patches/art/), [`HostedAndroidRuntime.cpp`](../src/native/HostedAndroidRuntime.cpp), [`zygote_tagging_jni.zig`](../src/binder/zygote_tagging_jni.zig); hosted class-loader projection, native-loader callbacks, stack/exit behavior and exec startup | Runtime changes are outside the JVM source identity result. Hosted zygote speaks stock requests but starts a fresh VM, not forked preloaded ART |
| libc and dynamic loading | [`bionic_translation.cfg`](../src/bionic_translation.cfg), `artzig/src/art.zig`, [`GraphicsEnvironmentBackend.cpp`](../src/native/GraphicsEnvironmentBackend.cpp) | Android sonames map to host or owned implementations; Android linker namespaces are absent and extension requests fail. Restoring native clients is distinct from using full stock bionic/linker |
| Libcore and files | [libcore patches](../artzig/patches/libcore/), [`host_paths.zig`](../artzig/src/host_paths.zig), AssetsProvider patch | Native path and authenticated-identity translation runs in the process. Move fixed filesystem/identity differences below libcore or document an explicit runtime exception |
| SystemProperties / SystemClock / Process | Original properties and clock JNI; [`native_properties.zig`](../src/binder/native_properties.zig) supplies broker-backed Bionic hooks | Properties retain hosted state and authorization. Process JNI still adapts Linux process operations and authenticated identities |
| SharedMemory / MemoryFile / ApplicationSharedMemory / MemoryIntArray | Original JNI, compiled against the existing libcutils sealed-memfd backend | Host adaptation is below JNI; original mapping, atomic access and cleanup are retained |
| NetworkUtils / process networking / TrafficStats | Central JNI, [`network_utils_internal_jni.zig`](../src/binder/network_utils_internal_jni.zig), [`traffic_stats_jni.zig`](../src/binder/traffic_stats_jni.zig) | Owned socket/fallback behavior. VPN/netd policy and Android per-UID tagging are not implemented by successful ordinary Linux networking |
| Trace and Java Perfetto data sources | Original JNI and Perfetto SDK | In-process packet capture works; no system traced daemon is installed |
| Log / EventLog / SecurityLog | Original JNI and liblog; diagnostics observe Java-originated liblog output | Original host log reader returns no historical events; Android logd is absent |
| Debug | [`debug_jni.zig`](../src/binder/debug_jni.zig) | Host procfs/allocator implementations and unavailable Android facilities replace original clients |
| VINTF | Original VintfObject/VintfRuntimeInfo JNI, libvintf, libkver and generated XSD parser | Original manifest/matrix compatibility and runtime queries; this host supplies no Android manifests or matrices |
| ClassLoaderFactory / CharsetUtils / OverlayConfig | Original JNI | Native loader namespaces remain a host seam; charset conversion is original, and OverlayConfig retains the original idmap2 executable probe |
| SELinux | [`selinux_backend.zig`](../src/native/selinux_backend.zig) | Host policy adapter beneath reused JNI; Android seapp policy is not supplied |
| SQLite | Original `external/sqlite/android` extension over host SQLite/ICU | ICU collation and Android phone-number/file SQL functions are restored; the underlying SQLite library is still a host dependency |
| Test machinery | liblog output callback, graphics namespace diagnostics and C++ smoke registrations | Helpers are outside `android.*`, but production native registration still calls/requires them |

The utility selection lives in `build/gen/island_sources.zig` and
`build/island.zig`. Original SystemProperties JNI resolves Bionic property calls
through `azk/native_properties.h` to the existing broker-backed API. The shared
memory units use the same renamed libcutils memfd exports as NDK shared memory.
Log JNI binds its liblog write call through a scoped observer: only Java log
writes may call the Java diagnostic journal, never arbitrary ART runtime logs.
These source units are unchanged; those compile-time bindings remain explicit
native adaptations.

`StockSqliteSmoke` exercises German/Swedish index ordering after locale changes,
Unicode collation, phone-function overloads and file deletion results.
`StockUtilityJniSmoke` exercises charset buffer boundaries and actual Java
Perfetto packets, lifecycle callbacks, per-thread state and exception recovery.
`StockPerfettoSmoke` also checks sanitized `android.os.Trace` events in a capture.
Shared-memory regressions cover sealed descriptors, retained writable mappings,
reader visibility and cleanup, including cross-process MemoryIntArray transfer.

Utility validation passed both framework suites, 23 testkit tests and all 28 APK
tests (90 recorded actions, zero structured runtime issues in the final run).
## Remaining work and acceptance criteria

1. Close remaining Process, Debug and networking JNI adaptations against
   their native dependencies and host policy. Preserve explicit missing-service
   behavior where Android infrastructure is absent.
2. Decide whether libbinder and AZKB remain explicit native adaptations or
   whether literal native identity requires replacing them below the restored
   Binder JNI boundary.
3. Complete compiled native source/flags/header and ELF provenance checks.
   Sensors, Codec2, MediaPlayer and metadata retrieval now use original service clients.
4. Close remaining libgui, OpenGL binding and native Surface/Image wrapper
   adaptations. HWUI and Skia buffer clients are now original; host EGL, gralloc
   and compositor implementations remain at their platform boundaries. Close
   device, networking, loader and runtime gaps against the declared scope.

The native acceptance check must expand from the island and Skia compilation commands
to the remaining linked library and runtime closure, with the adaptation inventory and
ELF/soname resolution checks. Process tests must prove backend placement and
service death/reconnection. Merely adding servers, retaining the replacement
JNI, and passing the existing UI suite would not complete this work.

## Media and graphics JNI follow-up validation

The native build, C++ audit, NDK ABI checks, framework compatibility and module
suites, source-identity check, NewPipe launch and full APK integration suite
passed for this batch. The APK suite ran 28 cases and the testkit ran 23 tests;
recorded actions contain no structured runtime issues. Hardware snapshot tests
check pixels, dimensions, invalid sizes and RenderNode ownership. DRM tests
check absent-factory results, argument validation and failed-object cleanup;
they do not certify protected playback.

The refreshed framework archive and its dependency pin are current, and the
build is installed in `zig-out`. Source hashes and validation counts are in
`native-client-audit.json`.

The EGL direct-buffer follow-up passed the native EGL lifetime test, C++ audit,
framework module and compatibility suites, graphics buffer and budget tests,
NewPipe launch, and all 28 APK integration cases (23 testkit tests). The 88
recorded actions had zero structured runtime issues. Window regressions verify
that consumers receive Mesa's rendered DMA-BUF, along with colors, timestamps,
GL state, delayed releases, SurfaceTexture resize and retained images surviving
drawable destruction. Explicit CPU readback also covers format and stride
translation. The original JNI sources and framework archive are unchanged by
this follow-up; `zig-out` contains the refreshed build.

The ADisplay follow-up passed framework module/compatibility tests, the
GraphicBuffer/HardwareBuffer/SyncFence, Surface queue and SurfaceControl/vsync
suites, NewPipe launch and all 28 APK cases. Its native display snapshots match
Java's SurfaceFlinger queries. The final APK run recorded 88 actions with no
structured runtime issues. The installed libraries and their hashes are current;
this source selection requires no archive changes.

## Codec2 native client and service

The production island compiles the pinned MediaCodec JNI, MediaCodecList JNI,
NdkMediaCodec, MediaCodec/CCodec, original Codec2 AIDL/HIDL clients and
bufferpool2 V2. Their native GUI/foundation globals share one ELF image. The
session supervises `azk-media-service` alongside its existing SurfaceFlinger
and system process; media startup waits for activity/processinfo/batterystats.
Unexpected media-process exit retires the session.

The broker's immutable AIDL declarations contain both published component
stores: `android.hardware.media.c2.IComponentStore/software` for five software
audio decoders and `/default` for available VAAPI video decoders. Original
AIDL ComponentStore and SimpleC2Component retain transport, configuration,
queue, flush, listener and work-generation semantics. The host implementation
selects FFmpeg instead of Android codec DSOs. FFmpeg opaque references carry
Codec2 frame indices independently of PTS through reordered/multi-output work.

Real VAAPI profile/entrypoint queries gate discovery. AVC and VP9 are available
on this GPU; MPEG-4 Simple/Advanced Simple are unavailable and not advertised.
XML and C2 parameters provide original MediaCodecList capability discovery.
`ARTZIG_PLATFORM_ROOT` relocates the original parser's immutable configuration
paths at the native libc boundary. Render metrics use CCodec's standard
configuration to consume compositor frame times. Encoders, protected decoding
and unsupported HDR conversions remain unavailable host capabilities.

Original resource manager and observer services handle resource bookkeeping,
reclaim policy and death. Host ProcessInfo and battery services read the live
session process/UID state. CPU BLOB gralloc serves original C2 linear allocators;
video uses original IGBA buffers, with VAAPI importing and writing directly into
their DMA-BUF storage. The obsolete separate VPP allocation/export path is gone.

`codec2-service-test` covers native catalogue flags/profiles, public NDK MP3, AAC and
AVC decode, pixel verification, reordered work, duplicate timestamps, EOS,
flush, Surface migration and component-service death. It supervises real
SurfaceFlinger. `codec-video-allocator-test` checks the same output allocation's
identity and pixels in linear/GPU layouts, retry, crop and lifetime after flush
and decoder destruction. Original bufferpool/resource-manager tests and the
five FFmpeg audio component tests cover the remaining native seams.

Production validation passed the native codec/allocator/transport/bufferpool
checks, all five FFmpeg audio families, 54 resource-service tests, framework
module/compatibility and JVM tests, system-service supervision, source identity,
Binder JNI provenance, NDK ABI and C++/graph audits. The full APK suite passed
28 cases (23 testkit tests), with 90 recorded actions and no structured runtime
issues. The video fixture also passed four actions with SurfaceView/TextureView,
resize/release, crop/color metadata, rendered callbacks and pixel checks.

A public NDK AAC regression caught empty work preceding CSD: the component now
completes it without opening FFmpeg prematurely. This ordering is also tested
across all audio families. Normal launcher return unwinds session cleanup and
then uses the existing AndroidRuntime immediate-exit hook, preserving original
bufferpool threads' process lifetime without C++ destructor races.

The installed tree and framework archive are current. ELF checks confirm that
FFmpeg/VAAPI dependencies are in the media executable, while the launcher and
platform DSO have none.
The optional original `codec2-params-test` retains its two previous host-compiler
parameter equality/implicit-bool mismatches (12/14); it is outside acceptance.

## VINTF native follow-up

Original VintfObject and VintfRuntimeInfo JNI now use the pinned libvintf
implementation. The existing xsdc generator emits its C++ apex-info-list parser
from a typed build declaration. The 232-line hosted JNI replacement is deleted;
`libvintf_jni.so` resolves to the existing platform ELF.

All 252 original parser/compatibility tests passed using the production native
object files. Framework tests exercise the actual JNI with valid and missing
manifest/matrix XML through an instrumentation shell fixture, plus real uname,
procfs, AVB-property and SELinux queries. Boot verification correctly reports
the absent Android manifests. The fixture does not provide a production shell
service or claim device compatibility.

System tests, build-tool checks, source/Binder provenance, native ABI and build
audits passed. All 28 APK cases passed with 88 recorded actions and zero
structured runtime issues. Installed outputs and the framework source archive
were refreshed.

## Performance-hint native follow-up

Original PerformanceHintManager JNI and `performance_hint.cpp` now call the
original IHintManager protocol and AOSP HintManagerService in the system process.
The old hosted JNI replacement is deleted. A server constructor adaptation
initializes the stock pre-V6 unsupported-capability result without a Power HAL;
the existing native PowerHalController host seam selects EmptyHalWrapper.
A manager can be acquired and inspected; creating a supported hint session still
returns null. CPU/GPU headroom remains explicitly unsupported.

The native test target links the production client object and original upstream
tests. Its default selection excludes `TestSessionCreationWithNullLayers`:
the pinned client dereferences null SurfaceControls in `layersFromNativeSurfaces`,
although its test expects those entries to be ignored. Enable
`-Dperformance-hint-all-tests=true` to reproduce this known failure. No client
source change conceals it. Framework tests use the actual Binder service and
validate its capabilities, session results and Java argument/value contracts.
Native JNI uses the existing private system-library projection for bare
`libandroid.so` lookup through the hosted loader seam. The test executable also
uses Soong's default zero initialization of locals: its upstream support test
compares a one-byte bitfield through a four-byte union. The production native
client uses that same default, which also initializes its session out-pointer
when the service rejects creation.

Native and framework module tests passed, followed by system tests, source/Binder
provenance checks, build/ABI audits and the complete APK suite: 28 cases,
88 recorded actions and zero structured runtime issues. Installed outputs and
the source archive were refreshed.

## Original hardware-buffer and path JNI

HardwareBufferHelpers, Path and PathIterator now compile the original Android
branches. The hardware-buffer helper uses the existing system-library loader
projection. The path implementations use ART's original critical-native calling
convention; the old fill-type clamp and helper/iterator patches are removed.
Tests cover fill types, path copies, iterator verbs and offsets, invalidated
iterators, and the Java ownership retained by a wrapped hardware bitmap.
This changes neither Mesa selection nor GPU presentation copies.

The aggregate run also exposed two acceptance-fixture problems and a hosted
input-policy mismatch. Delayed GPU events now prefer a separate compute queue;
the cleanup deadline excludes failure-stack formatting. The standalone platform
ABI check imports its graphics types. AOSP's null drop target on cancelled drags
now passes through the Kotlin policy and is covered through the real dispatcher.

## Startup and native heap JNI

Activity, ActivityThread and DDM native-heap JNI now compile unchanged from the
pinned AOSP sources. The allocator boundary maps `M_PURGE` to glibc
`malloc_trim`; Bionic profiling and leak-record operations return false with
`ENOTSUP`. Original DDM JNI therefore reports failure instead of producing a
fabricated heap dump. The hosted loader has no deferred compatibility-warning
journal. RuntimeInit now uses the original embedded AndroidRuntime implementation.
Architecture-specific zygote probes remain explicit runtime adaptations. Full acceptance passed, including the DDM failure-response
protocol regression.

## Audio delivery acceptance

`stock-audio-delivery-test` sends 98,304 deterministic stereo frames through
original AudioTrack JNI, its native client and the hosted audio service. A
private PipeWire server captures the sink monitor. The check verifies every
frame, channel ordering, gain and sample integrity, and confirms the PipeWire
stream belongs to a process other than the application. Silence, swapped
channels and corrupted PCM are rejected by the capture verifier's own checks.

The dedicated target passed with unity gain, zero sample error and zero
structured runtime issues. It is now required by `acceptance-test`; this
dedicated run follows the complete startup-JNI acceptance checkpoint above.
The audio backend is unchanged. Artifacts, including PCM, process ownership,
graph links and diagnostics, are in `zig-out/test-results/stock-audio-delivery/`.

## Image codecs and buffer-consumer follow-up

Original HWUI Movie/GIF and YuvImage JPEG/JPEG-R JNI now register alongside
ByteBuffer stream initialization. GIF decoding uses pinned giflib; JPEG/R uses
pinned libultrahdr, image_io and modp_b64. Original PdfDocument Java/JNI uses the
real Skia PDF backend with HarfBuzz font subsetting. PDF rendering and additional unselected image codec backends are not established
by this batch. PDF editing is covered below.

StockImageCodecSmoke verifies read-only heap and direct ByteBuffer decoding with
offsets, animated GIF pixels and rewind, padded NV21/YUY2 crop encoding, JPEG/R
gain maps, and PDF state errors, pages, embedded font resources and cross-reference
output. Framework compatibility, native BufferQueue and SurfaceTexture tests
passed, including real GPU pixels, fences, resizing and zero retained buffers.
The NewPipe launch passed with zero structured issues. Native provenance and
build audits and full acceptance passed. All 28 APK cases completed with 93 recorded
actions, zero structured runtime issues and no failure artifacts. Fresh SurfaceView,
TextureView and resized-video screenshots were inspected. Source packages, pins
and installed outputs are current.


## PDF editing and graphics registration

Original PdfEditor Java/JNI and PdfUtils now use the pinned PDFium implementation.
The complete original HWUI graphics registrar replaces the copied registration
table. Font discovery uses a bounded directory of links to fontconfig-selected
host fonts; native file and FreeType entry points resolve Android font paths there.
This avoids treating the whole host filesystem as `/system/fonts` and retains the
same font files, face indices and fallback selection. Font configuration is
published atomically through `std.Io`.

The framework regression verifies page deletion, media/crop boxes, transformation,
save/reopen, descriptor ownership, closed-object errors and malformed/nonseekable
input. Framework compatibility, APK launch and the compiled provenance check pass.
The first aggregate run exposed a desktop test race: pointer creation was
acknowledged by the injector before Android had published the input device. The
harness now waits for that publication before pressing; the dedicated test passes.
Full aggregate acceptance then passed, including all 28 APK cases, 88 recorded
actions with zero runtime issues, and inspected SurfaceView/TextureView video frames.

## Embedded runtime and process naming

Original `AndroidRuntime.cpp` now owns RuntimeInit registration, Java-capable
thread attachment and process exit. `HostedAndroidRuntime` supplies the existing
ART VM and hosted Java entry; a private friend declaration is the only header
adaptation. The launcher copies its arguments before giving AndroidRuntime the
real writable exec argument block.

Original Process `setArgV0` and Bionic `setprogname` replace the thread-name-only
implementation. The regression checks real procfs argument and thread names,
UTF-8, suffix truncation, empty and null names. Other Process operations remain
adapted. The session-loss harness recognizes the resulting `system_server`
process name, while retaining its early-start exec-flag lookup.

NdkImageReader is also original again. Its deprecated HIDL-token API retains
AOSP's failure result when the token service is absent; normal window/buffer
ownership remains covered by the native suite.

Full aggregate acceptance passed, including all six session-loss cases, both
framework suites, native provenance, and 28 APK cases with 88 actions and no
structured runtime issues. The source archive, dependency pin and installed
platform ELF match this checkpoint.

## PDF renderer and modern GIF decoding

The framework now includes original PdfRenderer and its 30 Java collaborators,
with generated PDF flags and PdfStatsLog. Original libpdfclient loads as a
separate JNI library. Its original export script keeps PDFium globals private,
so closing a PdfEditor cannot destroy the renderer's library state. Both use
the existing native font-path boundary and host FreeType/ICU dependencies.

Skia now includes its original Wuffs GIF decoder and the separately pinned
Wuffs C dependency. GN's implementation flags remain local to that dependency.
ImageDecoder can decode GIF bitmaps and animated drawables; legacy Movie still
uses its original giflib path. These changes do not alter GPU presentation.

Module checks passed for actual PDF pixels, text extraction and search,
editor/renderer coexistence, descriptor ownership, malformed/nonseekable input,
and closed-page operations. The native audit also checks libpdfclient's ELF
and restricted JNI exports. Full aggregate acceptance passed, including all 28 APK
cases, 88 recorded actions, zero structured issues or failed actions, all six
service-loss cases, and the desktop/video checks. All three fresh video screenshots
were inspected. Installed platform and PDF-client hashes match the native audit.
