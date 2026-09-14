# Native client audit

Baseline audited 2026-09-13 at `31ac159f`; Binder JNI and package evidence
updated through `b7d78a39`; SQLite and utility JNI through `d42afccd`; media/graphics JNI follow-up recorded below. **The complete client stack is not stock AOSP.**
The JVM source check covers selected framework source files. Android-native
implementations, runtime libraries and process placement still contain substantial
host adaptations.

This is a source and architecture audit, not an exhaustive per-method
compatibility certification. The follow-up validation record distinguishes
exercised behavior from source provenance. It traces production
registration, build selection, native library mappings, host backends and the
pinned reference. Existing passing acceptance tests establish their exercised
behavior; they do not establish native provenance or stock process boundaries.

## JVM source check scope

[`aosp-source-identity.py`](../tools/aosp-source-identity.py) compares the root
framework build's static Java/Kotlin/annotation inputs against its explicitly
supplied AOSP package and checkout. Its last acceptance report found 7,081
unchanged inputs and 62 modified server inputs, with no modified or
repository-owned `android.*` / `com.android.internal.*` client replacements in
that selection.

It does **not** compare JNI, C/C++, ART, bionic, ELF dependencies, native symbol
resolution, or the separately built Artzig/libcore source closure. Generated
sources have separate generator checks. `dev.artzig` sources are recorded as
host sources, even when JNI calls them back to implement an Android operation
inside the app. An unchanged Java declaration of a native method therefore
says nothing about whether its implementation is stock. Zero framework stubs
also does not mean zero unsupported native operations.

## Evidence and counting rules

[`native-client-audit.json`](native-client-audit.json) records archive checksums,
reference revisions, modified native paths and delegated registration modules.
Both archives were read directly. The framework archive was rechecked during
the Binder, utility and media/graphics JNI migrations. The latest refresh adds
original DRM clients/protocols and enables native hardware-bitmap snapshots. Every regular `.c`, `.cc`, `.cpp`, `.cxx`,
`.h`, `.hpp`, `.hxx`, `.S`, `.s` and `.inl` member was compared byte-for-byte
with the corresponding pinned checkout path. Both current archives contain
multiple top-level directories; their paths were retained. Tracked reference
state was checked clean in all 11 projects containing differences/additions.

| Archive | Unchanged native files | Modified native files | Added native files |
|---|---:|---:|---:|
| Framework | 12,414 | 109 | 0 |
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
`azk_register_binder_jni`, `AMediaCodec_createDecoderByType` and
`AAudio_createStreamBuilder` in the hosted `libbinder_ndk.so`. This corroborates
the build selection; it is not a complete dynamic-symbol resolution audit.

[`main.zig`](../src/main.zig) calls the Binder and graphics registrars and then
[`host_natives.zig`](../src/platform/host_natives.zig) when booting APK ART.
[`framework_jni.zig`](../src/binder/framework_jni.zig) has 19 delegated
registration modules, plus its own registrations and the Binder registrar.
That number is not a replaced-class count: it includes host-only cursor helpers
and NDK exports, plus a dev.artzig EGL test helper. Merely registering a helper does not prove an app uses it.

## Audio and media: app-side replacements

### AudioTrack, audio policy and AAudio

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

[`aaudio_ndk.zig`](../src/binder/aaudio_ndk.zig) still implements the public
AAudio C calls through `HostedAAudio` and the (now stock) Java AudioTrack; the
stock `libaaudio` legacy path is the remaining replacement in this family.

**Additional source-confirmed behavior:** when no PipeWire daemon is reachable,
each track renders to a null sink that consumes frames in real time (the
audio.stub HAL model), so playback position advancing does not prove audible
output; verification of actual PipeWire delivery needs a daemon with a
capturable sink.

### MediaCodec and its NDK API

Current decoding path:

```text
app: stock MediaCodec.java
  -> media_codec_jni.zig -> HostedMediaCodecBackend.kt
  -> HostedCodecEngine -> codec/jni.zig -> FFmpeg / VA-API
  -> hosted video output -> stock buffer queues -> SurfaceFlinger
```

[`HostedMediaCodecBackend`](../src/java/dev/artzig/HostedMediaCodecBackend.kt)
owns the codec state, queues and callback coordination inside the app.
[`media_codec_list_jni.zig`](../src/binder/media_codec_list_jni.zig) supplies a
host decoder catalogue rather than the original native codec service catalogue.
[`media_ndk.zig`](../src/binder/media_ndk.zig) routes NDK calls back through Java
MediaCodec; pinned `NdkMediaCodec.cpp` calls native `MediaCodec` directly.

Pinned `frameworks/base/media/jni/android_media_MediaCodec.cpp` creates
`JMediaCodec` and native `MediaCodec`; `libstagefright/MediaCodec.cpp` selects
the CCodec/ACodec machinery. The Codec2 client discovers component stores through
its AIDL/HIDL interfaces. Those native clients, resource-management interactions
and service boundaries are not restored by this implementation. Unsupported
encoding, secure/block and additional surface operations remain limited.

Closing this family requires stock JNI, native MediaCodec and NDK callers,
with FFmpeg/VA-API behind the component/service boundary they use. The existing
decoder and Wayland presentation backends need not be rewritten to do that.

MediaCrypto and MediaDrm now compile original JNI, JCrypto/JDrm, CryptoHal,
DrmHal, metrics and AIDL/HIDL factory clients. The frozen DRM V1 NDK protocol
and DRM 1.0–1.4 HIDL protocols are generated from the pin. The host advertises
no VINTF DRM instances and has no HIDL service manager or DRM plugin, so this
restores client provenance, not protected playback. The resource-manager client
is linked for session bookkeeping; no resource-manager service is added.

### Missing-service clients are also non-stock

| Client | Current implementation and behavior | Stock boundary to restore |
|---|---|---|
| MediaPlayer | [`media_player_jni.zig`](../src/binder/media_player_jni.zig) and `HostedMediaPlayer` model client state and absent-service errors locally; this is not the FFmpeg playback path | Original JNI and native MediaPlayer to `IMediaPlayerService` / `media.player` |
| MediaMetadataRetriever | [`media_metadata_retriever_jni.zig`](../src/binder/media_metadata_retriever_jni.zig) models a null remote retriever; setters fail and retrieval returns no data | Original JNI/libmedia retriever and media-player service |
| Legacy Camera | [`legacy_camera_jni.zig`](../src/binder/legacy_camera_jni.zig) and `HostedLegacyCamera` implement absent-camera failures | Original Camera JNI/CameraBase to `media.camera` |
| SystemSensorManager | [`sensor_manager_jni.zig`](../src/binder/sensor_manager_jni.zig) returns empty discovery and unavailable queues/channels | Original JNI/libsensor to `sensorservice` |
| PerformanceHintManager | [`performance_hint_jni.zig`](../src/binder/performance_hint_jni.zig) returns no manager and rejects sessions | Original JNI/native hint client, IHintManager and associated power-service boundary |

Matching a missing-service result is still a replacement implementation. Adding
a server later will not automatically make these clients use it. Restoring stock
discovery also needs care: some AOSP clients wait for services rather than fail
immediately. Hardware support is separate from native client provenance.

**Already across a service boundary:** MediaExtractor uses original Java/JNI,
NuMediaExtractor and Binder to the native `media.extractor` service published by
the system process. Its linked-plugin factory has a host patch. MediaMetrics
and MediaDescrambler also reuse native client/protocol code, although native
metrics collection and CAS hardware services are not supplied. These must not
be grouped with the in-process MediaCodec replacement.

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

- [`ArtzigGpuPipeline.cpp`](../src/native/ArtzigGpuPipeline.cpp) replaces HWUI's
  GPU pipeline with GBM/EGL/Ganesh context and buffer handling. Packaged HWUI
  CanvasContext, RenderThread-related code, layer updates, bitmap and WebView
  functor paths also differ from the reference. Stock RenderNode/Canvas Java
  and an existing SurfaceFlinger do not remove these modifications.
- EGL10, EGL14, EGL15 and EGLExt compile their original JNI. The host EGL
  driver adapter translates Android windows to Mesa GBM drawables and delivers
  GPU-rendered frames through the original ANativeWindow/BufferQueue producer.
  It owns DMA-BUF imports, acquire/release fences, drawable resizing and deferred
  destruction. Presentation remains in the existing compositor backend.
  Generated GLES bindings remain reused with native fixes for JNI array
  bases/bounds and host pixel ordering.
- HardwareRenderer snapshots use AOSP's AImageReader / RenderProxy algorithm.
  A build guard enables it on the host; Bitmap's native buffer factory delegates
  GPU import to the existing render-thread owner. The Kotlin snapshot and its
  JNI/RenderNode ownership bridges have been deleted.
- Bitmap uses its original JNI registration. The native GraphicsJNI color-type
  mapper recognizes both RGBA and BGRA as ARGB_8888; the JNI override is gone.
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
- libgui/SurfaceComposerClient, GLConsumer, native SurfaceTexture, Skia hardware
  buffer imports and several native Surface/Image wrappers have packaged
  patches. See the exact file inventory; some are portability fixes, others
  change transport, imports, lifetime or failure behavior.
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

Closing graphics means restoring stock HWUI/EGL/native clients and confining
GBM/Wayland translation to driver, allocator and compositor interfaces. Keep
the working separate presenter and SF architecture; do not move app rendering
wholesale into system_server.

## Runtime, core utilities and additional client differences

| Area | Current deviation and source | Required boundary / consequence |
|---|---|---|
| ART and process bootstrap | [ART patches](../artzig/patches/art/), [`runtime_init_jni.zig`](../src/binder/runtime_init_jni.zig), [`activity_jni.zig`](../src/binder/activity_jni.zig); hosted class-loader projection, native-loader callbacks, stack/exit behavior and exec startup | Runtime changes are outside the JVM source identity result. Hosted zygote speaks stock requests but starts a fresh VM, not forked preloaded ART |
| libc and dynamic loading | [`bionic_translation.cfg`](../src/bionic_translation.cfg), `artzig/src/art.zig`, [`GraphicsEnvironmentBackend.cpp`](../src/native/GraphicsEnvironmentBackend.cpp) | Android sonames map to host or owned implementations; Android linker namespaces are absent and extension requests fail. Restoring native clients is distinct from using full stock bionic/linker |
| Libcore and files | [libcore patches](../artzig/patches/libcore/), [`host_paths.zig`](../artzig/src/host_paths.zig), AssetsProvider patch | Native path and authenticated-identity translation runs in the process. Move fixed filesystem/identity differences below libcore or document an explicit runtime exception |
| SystemProperties / SystemClock / Process | Original properties and clock JNI; [`native_properties.zig`](../src/binder/native_properties.zig) supplies broker-backed Bionic hooks | Properties retain hosted state and authorization. Process JNI still adapts Linux process operations and authenticated identities |
| SharedMemory / MemoryFile / ApplicationSharedMemory / MemoryIntArray | Original JNI, compiled against the existing libcutils sealed-memfd backend | Host adaptation is below JNI; original mapping, atomic access and cleanup are retained |
| NetworkUtils / process networking / TrafficStats | Central JNI, [`network_utils_internal_jni.zig`](../src/binder/network_utils_internal_jni.zig), [`traffic_stats_jni.zig`](../src/binder/traffic_stats_jni.zig) | Owned socket/fallback behavior. VPN/netd policy and Android per-UID tagging are not implemented by successful ordinary Linux networking |
| Trace and Java Perfetto data sources | Original JNI and Perfetto SDK | In-process packet capture works; no system traced daemon is installed |
| Log / EventLog / SecurityLog | Original JNI and liblog; diagnostics observe Java-originated liblog output | Original host log reader returns no historical events; Android logd is absent |
| Debug / VINTF | [`debug_jni.zig`](../src/binder/debug_jni.zig), [`vintf_jni.zig`](../src/binder/vintf_jni.zig) | Host procfs/allocator/runtime-info implementations and unavailable Android facilities replace original clients |
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
Every acceptance target passed across the runs, but neither aggregate run was
fully green: the single-job run hit an intermittent desktop pointer assertion
that passed in isolation. The earlier clipboard GPU timeout did not recur.
The fence-acquisition fixture race found during validation was fixed separately
in `1823bb1e`. See the JSON validation record for this limitation.

## Remaining work and acceptance criteria

1. Close remaining Process, Debug, VINTF and networking JNI adaptations against
   their native dependencies and host policy. Preserve explicit missing-service
   behavior where Android infrastructure is absent.
2. Decide whether libbinder and AZKB remain explicit native adaptations or
   whether literal native identity requires replacing them below the restored
   Binder JNI boundary.
3. Restore the stock `libaaudio` legacy path over the now-stock AudioTrack
   client, and verify actual PipeWire delivery of a known PCM sequence against
   a controlled sink in addition to track position, callbacks and
   service-death tests.
4. Restore codec/player/NDK clients and their services; put the existing FFmpeg
   engine behind the selected component interface. Verify app/service process
   placement as well as decoding, buffers, callbacks and failure handling.
5. Restore app graphics clients while retaining host EGL/gralloc/compositor
   implementations at their appropriate boundaries. Then close device,
   networking, loader and runtime gaps against the declared scope.

A future native acceptance check needs the actual compiled source/flags/header
closure, an explicit adaptation inventory, JNI-registration provenance, and
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
they do not certify protected playback. No aggregate acceptance run is claimed.

The refreshed framework archive and its dependency pin are current, and the
build is installed in `zig-out`. Source hashes and validation counts are in
`native-client-audit.json`.

The EGL follow-up also passed the native EGL lifetime test, C++ audit,
framework module and compatibility suites, NewPipe launch, and all 28 APK
integration cases (23 testkit tests). The final APK run followed the display
validation and disconnect fixes. Window tests check EGL10/EGL14 pixels,
timestamps, GL state, SurfaceTexture resize and independent native ownership.
The two original JNI files match the pinned reference byte for byte; the
archive contents and dependency pin are unchanged by this follow-up.

The ADisplay follow-up passed framework module/compatibility tests, the
GraphicBuffer/HardwareBuffer/SyncFence, Surface queue and SurfaceControl/vsync
suites, NewPipe launch and all 28 APK cases. Its native display snapshots match
Java's SurfaceFlinger queries. The final APK run recorded 88 actions with no
structured runtime issues. The installed libraries and their hashes are current;
this source selection requires no archive changes.
