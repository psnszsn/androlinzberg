# Native client audit

Audited 2026-09-13 at `31ac159f`. **The complete client stack is not stock
AOSP.** The earlier completion claim covered selected framework JVM source
files. Android-native implementations, runtime libraries and process placement
still contain substantial host adaptations.

This is a source and architecture audit, not a new runtime regression run or
an exhaustive per-method compatibility certification. It traces production
registration, build selection, native library mappings, host backends and the
pinned reference. Existing passing acceptance tests establish their exercised
behavior; they do not establish native provenance or stock process boundaries.

## What the previous check establishes

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
Both archives were read directly. Every regular `.c`, `.cc`, `.cpp`, `.cxx`,
`.h`, `.hpp`, `.hxx`, `.S`, `.s` and `.inl` member was compared byte-for-byte
with the corresponding pinned checkout path. Both current archives contain
multiple top-level directories; their paths were retained. Tracked reference
state was checked clean in all 11 projects containing differences/additions.

| Archive | Unchanged native files | Modified native files | Added native files |
|---|---:|---:|---:|
| Framework | 12,300 | 108 | 0 |
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
[`framework_jni.zig`](../src/binder/framework_jni.zig) has 25 delegated
registration modules, plus its own registrations and the Binder registrar.
That number is not a replaced-class count: it includes host-only cursor helpers
and NDK exports. Merely registering a helper does not prove an app uses it.

## Audio and media: app-side replacements

### AudioTrack, audio policy and AAudio

Current playback path:

```text
app: stock AudioTrack.java
  -> audio_track_jni.zig
  -> HostedAudioTrackBackend.kt
  -> HostedPcmStream -> platform/audio.zig -> PipeWire client library
host desktop: PipeWire daemon -> output device
```

The native track lifecycle, buffering and playback state live in the app.
[`HostedAudioTrackBackend`](../src/java/dev/artzig/HostedAudioTrackBackend.kt)
creates the PCM stream directly. In pinned AOSP,
`frameworks/base/core/jni/android_media_AudioTrack.cpp` creates native
`AudioTrack`; `frameworks/av/media/libaudioclient/AudioTrack.cpp::createTrack_l`
uses `AudioSystem::get_audio_flinger()` and `IAudioFlinger::createTrack`.
That native client and shared-memory track protocol are replaced here.

[`audio_system_jni.zig`](../src/binder/audio_system_jni.zig) also implements
AudioSystem, AudioRecord, MediaRecorder, AudioEffect, port events, policy
strategies/groups and CamcorderProfile native boundaries. It describes a
logical output, reports unsupported policy operations and retains absent
recording/effect failures. It is not native AudioSystem talking to AudioFlinger
and AudioPolicyService. The real hosted `audio` Java Binder service and
`artzig.audio_ids` authority in
[`SystemServices.kt`](../src/java/dev/artzig/system/SystemServices.kt) handle
policy/session operations; they do not constitute an AudioFlinger PCM server.

[`aaudio_ndk.zig`](../src/binder/aaudio_ndk.zig) implements the public C calls
through `HostedAAudio` and Java AudioTrack. Stock AAudio has both legacy
AudioTrack and internal service/MMAP paths; reusing its parameter utilities
does not make this replacement stock. ToneGenerator's synthesizer is reused,
but its native AudioTrack output is patched to
[`ToneOutput.cpp`](../src/native/ToneOutput.cpp), which calls hosted AAudio.

**Additional source-confirmed behavior:**
[`audio.zig`](../src/platform/audio.zig) retains a stream if `azk_pw_open` fails
and advances a fallback playback clock while consuming queued PCM. Playback
position advancing therefore does not prove audible PipeWire output. This
audit did not reproduce an output-device failure.

To close this family, reuse stock audio JNI/libaudioclient/AAudio and provide
their audio policy, track and service interfaces. Keep PipeWire behind the
audio service/HAL boundary. Reusing the existing PipeWire backend is possible;
moving that backend alone does not restore the omitted client protocols.

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

### Missing-service clients are also non-stock

| Client | Current implementation and behavior | Stock boundary to restore |
|---|---|---|
| MediaPlayer | [`media_player_jni.zig`](../src/binder/media_player_jni.zig) and `HostedMediaPlayer` model client state and absent-service errors locally; this is not the FFmpeg playback path | Original JNI and native MediaPlayer to `IMediaPlayerService` / `media.player` |
| MediaMetadataRetriever | [`media_metadata_retriever_jni.zig`](../src/binder/media_metadata_retriever_jni.zig) models a null remote retriever; setters fail and retrieval returns no data | Original JNI/libmedia retriever and media-player service |
| MediaCrypto / MediaDrm | [`media_crypto_jni.zig`](../src/binder/media_crypto_jni.zig), [`media_drm_jni.zig`](../src/binder/media_drm_jni.zig) implement absent-plugin results and cleanup | Original JCrypto/JDrm, ICrypto/IDrm and DRM factory discovery |
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

[`ndk.zig`](../src/binder/ndk.zig) supplies the Binder/Parcel/BinderProxy/
ServiceManager JNI and the NDK Binder ABI over AZKB. The C++
[`Binder.cpp`](../src/native/binder/Binder.cpp) and
[`Parcel.cpp`](../src/native/binder/Parcel.cpp) provide a source facade over it.
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

## Graphics, input and WebView

**App-side adaptations remain:**

- [`ArtzigGpuPipeline.cpp`](../src/native/ArtzigGpuPipeline.cpp) replaces HWUI's
  GPU pipeline with GBM/EGL/Ganesh context and buffer handling. Packaged HWUI
  CanvasContext, RenderThread-related code, layer updates, bitmap and WebView
  functor paths also differ from the reference. Stock RenderNode/Canvas Java
  and an existing SurfaceFlinger do not remove these modifications.
- [`egl_jni.zig`](../src/binder/egl_jni.zig),
  [`legacy_egl_jni.zig`](../src/binder/legacy_egl_jni.zig) and
  [`egl_extensions_jni.zig`](../src/binder/egl_extensions_jni.zig) replace JNI;
  [`egl_support.zig`](../src/platform/egl_support.zig) and the native EGL
  backend translate Android display, buffer, image and pbuffer operations to
  the host driver. Generated GLES bindings are reused, with native fixes for
  JNI array bases/bounds and host pixel ordering.
- [`hardware_snapshot.zig`](../src/platform/hardware_snapshot.zig) replaces
  HardwareRenderer's `nCreateHardwareBitmap` with a host Kotlin snapshot path.
  [`AospJniBridge.cpp`](../src/native/AospJniBridge.cpp) also overrides
  Bitmap's `nativeConfig` after registering stock Bitmap JNI, to account for
  host pixel layouts.
- The selected, unchanged AOSP `libs/hostgraphics/ADisplay.cpp` synthesizes one
  1080-by-1920, 60 Hz display. This is a host implementation, not the Android
  physical-display client, and does not establish actual Wayland output
  properties. Stock native source identity alone would miss this distinction.
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
| SystemProperties, Process, SystemClock | [`framework_jni.zig`](../src/binder/framework_jni.zig), [`native_properties.zig`](../src/binder/native_properties.zig) | Owned JNI, broker property state and Linux process/clock operations replace original native implementations |
| SharedMemory / MemoryFile / ApplicationSharedMemory / MemoryIntArray | Central JNI plus [`application_shared_memory_jni.zig`](../src/binder/application_shared_memory_jni.zig), [`memory_int_array_jni.zig`](../src/binder/memory_int_array_jni.zig) | Memfd ports, some delegated to Kotlin; stock mapping/atomic portions do not imply all JNI is stock. Keep host allocation under stock shared-memory APIs |
| NetworkUtils / process networking / TrafficStats | Central JNI, [`network_utils_internal_jni.zig`](../src/binder/network_utils_internal_jni.zig), [`traffic_stats_jni.zig`](../src/binder/traffic_stats_jni.zig) | Owned socket/fallback behavior. VPN/netd policy and Android per-UID tagging are not implemented by successful ordinary Linux networking |
| Trace and Java Perfetto data sources | `registerTrace` / `registerPerfetto` in central JNI | Trace tags are always disabled; data-source creation/iteration/packet writes use zero/false/no-op implementations. Separate working native Perfetto tracing does not replace this Java path |
| Log / EventLog / SecurityLog | Central JNI | Log forwarding and diagnostics callbacks are owned code; event/security log read methods return no events. Full Android logd read behavior is not present |
| Debug / VINTF | [`debug_jni.zig`](../src/binder/debug_jni.zig), [`vintf_jni.zig`](../src/binder/vintf_jni.zig) | Host procfs/allocator/runtime-info implementations and unavailable Android facilities replace original clients |
| ClassLoaderFactory / CharsetUtils / OverlayConfig | Central JNI | Namespace creation returns null; charset and overlay entry points also have owned registrations. Restore corresponding JNI where its closure is available |
| SELinux | [`selinux_backend.zig`](../src/native/selinux_backend.zig) | Host policy adapter beneath reused JNI; Android seapp policy is not supplied |
| SQLite | [`platform_abi.zig`](../src/native/platform_abi.zig) | `LOCALIZED` and `UNICODE` use byte comparison and ignore requested locale. `register_android_functions` only installs those collations, omitting Android SQL functions |
| Test machinery | Central Log JNI, graphics namespace diagnostics and C++ smoke registrations | Helpers were moved out of `android.*`, but production native registration still calls/requires them. Namespace cleanup did not remove client instrumentation |

The SQLite difference is an independently actionable correctness finding.
Pinned `external/sqlite/android/sqlite3_android.cpp` uses ICU collators and
registers functions including `PHONE_NUMBERS_EQUAL` and `_DELETE_FILE`;
stock SQLiteConnection JNI calls those exports. The current replacements can
change locale-sensitive ordering and omit SQL functions without raising a
structured unsupported diagnostic. This is confirmed in source, not a newly
executed SQL regression. It needs original Android SQLite extensions, not a
new Binder service.

## Closure order and acceptance criteria

1. Fix the status wording and retain separate JVM/native/runtime evidence.
   Do not use namespace or zero-stub counts as native completion criteria.
2. Restore isolated stock native implementations first: SQLite extensions,
   tracing/logging and available utility JNI closures. Classify missing-service
   behavior explicitly instead of treating local failure models as stock.
3. Decide the Binder driver/transport and runtime exceptions. These affect
   nearly every stock native service client and determine what “100%” means.
4. Restore audio JNI/libaudioclient/AAudio and service protocols; place the
   existing PipeWire backend behind them. Require real audio output in addition
   to track position, callbacks and service-death tests.
5. Restore codec/player/NDK clients and their services; put the existing FFmpeg
   engine behind the selected component interface. Verify app/service process
   placement as well as decoding, buffers, callbacks and failure handling.
6. Restore app graphics clients while retaining host EGL/gralloc/compositor
   implementations at their appropriate boundaries. Then close device,
   networking, loader and runtime gaps against the declared scope.

A future native acceptance check needs the actual compiled source/flags/header
closure, an explicit adaptation inventory, JNI-registration provenance, and
ELF/soname resolution checks. Process tests must prove backend placement and
service death/reconnection. Merely adding servers, retaining the replacement
JNI, and passing the existing UI suite would not complete this work.
