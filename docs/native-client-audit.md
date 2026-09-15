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
supplied AOSP package and checkout. Its last acceptance report found 7,086
unchanged inputs and 63 modified server inputs, with no modified or
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
original VINTF JNI, runtime/XML/compatibility clients and their parser dependencies. Every regular `.c`, `.cc`, `.cpp`, `.cxx`,
`.h`, `.hpp`, `.hxx`, `.S`, `.s` and `.inl` member was compared byte-for-byte
with the corresponding pinned checkout path. Both current archives contain
multiple top-level directories; their paths were retained. Tracked reference
state was checked clean in all 11 projects containing differences/additions.

| Archive | Unchanged native files | Modified native files | Added native files |
|---|---:|---:|---:|
| Framework | 13,995 | 99 | 0 |
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
[`framework_jni.zig`](../src/binder/framework_jni.zig) has 11 delegated
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
audits and NDK ABI checks passed. No full aggregate acceptance run is claimed.

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

### Missing-service clients are also non-stock

| Client | Current implementation and behavior | Stock boundary to restore |
|---|---|---|
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

- [`ArtzigGpuPipeline.cpp`](../src/native/ArtzigGpuPipeline.cpp) replaces HWUI's
  GPU pipeline with GBM/EGL/Ganesh context and buffer handling. Packaged HWUI
  CanvasContext, RenderThread-related code, layer updates, bitmap and WebView
  functor paths also differ from the reference. Stock RenderNode/Canvas Java
  and an existing SurfaceFlinger do not remove these modifications.
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
  A build guard enables it on the host; Bitmap's native buffer factory delegates
  GPU import to the existing render-thread owner. The Kotlin snapshot and its
  JNI/RenderNode ownership bridges have been deleted.
- Software Bitmap storage now uses Android RGBA consistently across Skia and
  its consumers. Original GLUtils uploads those bytes directly; the host upload
  copy and ImageDecoder layout workaround are removed. NDK raw-pixel and
  MediaMetadataRetriever thumbnail tests exercise the byte order.
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
Every acceptance target passed across the runs, but neither aggregate run was
fully green: the single-job run hit an intermittent desktop pointer assertion
that passed in isolation. The earlier clipboard GPU timeout did not recur.
The fence-acquisition fixture race found during validation was fixed separately
in `1823bb1e`. See the JSON validation record for this limitation.

## Remaining work and acceptance criteria

1. Close remaining Process, Debug and networking JNI adaptations against
   their native dependencies and host policy. Preserve explicit missing-service
   behavior where Android infrastructure is absent.
2. Decide whether libbinder and AZKB remain explicit native adaptations or
   whether literal native identity requires replacing them below the restored
   Binder JNI boundary.
3. Verify actual PipeWire delivery of a known PCM sequence against a
   controlled sink in addition to track position, callbacks and
   service-death tests.
4. Restore SensorManager and PerformanceHint native clients and their
   required service discovery. Codec2 owns FFmpeg decoding in the separate media
   service; MediaPlayer and metadata retrieval now use original service clients.
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
platform DSO have none. The aggregate acceptance target has not been rerun.
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
were refreshed; the aggregate acceptance target was not run.
