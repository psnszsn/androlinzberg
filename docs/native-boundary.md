# Native language boundary

Use Zig for application logic and Linux platform logic. Use C++ only where the
AOSP graphics stack or an AOSP C++ interface requires it.

This document describes implementation boundaries, not stock-client
completion. Many native implementations below execute inside app processes.
The [native client audit](native-client-audit.md) distinguishes replacements,
patched AOSP sources, service-side adapters and remaining functionality gaps.

Self-targeting overlays compile the original OverlayManagerImpl JNI and
`libidmap2`, including its original self-targeting entry points and generated
fabricated-overlay protobuf. The separate shared library uses the existing
Android resource and Binder/libutils implementations. No overlay algorithm is
reimplemented in repository-owned C++.

Run the boundary check after a native change:

```sh
zig build -j10 cpp-audit
```

## Zig-owned code

- The hosted Binder runtime owns request and callback admission in Zig. Protocol
  delivery credit follows C++ destructor/death-callback lifetime through the
  existing worker boundary; C++ does not implement a second queue or quota map.
  The native facade regression verifies blocked destructors, unresolved service
  requests and the pinned NDK death-recipient unlink contract through public APIs.

- `src/binder/native_properties.zig` supplies seven bionic-loader property hooks.
  Opaque native handles point to stable slots in the same broker-owned property
  area used by Java `SystemProperties`. Callback reads copy a value and its serial
  together and invoke callers without holding a property lock. Long values use
  the full callback form; legacy reads return the pinned bionic explanatory text.
  Writes go through the existing broker permission policy. These hooks keep the
  bionic adapter's old name-pointer handles out of libbase's unrelated host map.
- `src/platform/message_queue.zig` exposes only a borrowed readiness descriptor.
  The original native MessageQueue and public NDK ALooper share the original
  C++ Looper on the Java thread. A host-only accessor exposes its epoll descriptor;
  only stock `pollOnce` consumes readiness and dispatches callbacks. Native/Java
  integration tests observe the descriptor; APKs use stock Looper.loop.
- `ArrayUtilsJniBridge.cpp` includes the pinned ArrayUtils JNI implementation
  and registers its exact table for both the framework and ConfigInfrastructure
  packages. The original code owns critical-array access, zeroization and CPU
  cache flushing. Original SerialPort JNI supplies Linux descriptor and termios
  operations. These client natives do not advertise unavailable hardware services.
- Stock WebView platform-support JNI loads in the provider class loader through
  `src/platform/webview_support.zig`. The original HWUI functor manager owns
  callback lifetime and the original GL drawable records and draws the functor.
  The unchanged AOSP WebView 140.0.7339.15 APK is independently pinned by the
  `.webview` dependency; framework sources remain Android 16 r1. Production
  retains upstream flags. The overlay regression enables WebViewSurfaceControl
  through the provider's original developer UI and IPC in temporary test data.
  See [provider provenance](../aosp/README.md#webview-provider) and
  [acceptance checks](acceptance.md).
  The original SurfaceControl callbacks and NDK transactions now reach the
  existing compositor. `webview-overlay-test` verifies platform H.264 decoding,
  actual overlay creation and buffer updates, SurfaceControl completion callbacks
  and correct playing/paused pixels through the existing HWUI underlay path.
  The bionic loader resolves public NDK exports through real process handles and
  host/guest global lookup. The original `graphic_buffer_impl.cpp` supplies the legacy provider
  buffer interface over the existing GraphicBuffer and DMA-BUF mapper.
  Original NDK bitmap, input, looper and native-window JNI wrappers are compiled
  with their original bitmap and shared JNI helpers.
- `src/platform/egl_support.zig` supplies the Android EGL library's display
  boundary. Both direct and dynamic `eglGetDisplay` lookup select HWUI's existing
  GBM EGL display. Native, EGL14 and legacy clients share extension reporting.
  Native and app procedure lookup use the same platform dispatch table. Context
  telemetry, begin-frame validation and damage operations are translated below
  callers; normal and damage swaps retain the direct GBM buffer delivery path.
  `eglGetNativeClientBufferANDROID` uses the original AHardwareBuffer conversion;
  core and KHR image creation import its existing DMA-BUF through Mesa. The EGL
  owner retains the Android buffer until its images are destroyed or the final
  display reference is terminated. Other image targets use the host driver.
  The module regression reads GPU pixels after releasing the application's
  buffer reference and checks both image APIs and null-buffer errors.
  HWUI requests the pinned EglManager's OpenGL ES context and wraps the actual
  RGBA8 framebuffer. Chromium's texture EGLImages can consequently be consumed
  by HWUI on the same display. No Wayland presentation backend is added.
- `artzig/src/bionic_paths.zig` uses the compatibility loader's path callback to
  map Android font configuration and font-file reads to the existing hosted font
  inventory. This also covers native clients such as Chromium's Skia; it does not
  create a global `/system` directory or change the vendored compatibility library.
- Original NDK shared-memory wrappers use original libcutils memfd allocation,
  immutable sizes and write seals. A packaging patch removes Android vendor
  properties and ashmem-driver probing from this host-only variant, while retaining
  the real memfd capability probe and failure results. Symbol names are isolated
  from Binder's existing shared-memory implementation. Existing writable mappings
  survive a write seal; subsequent writable mappings and restoring write access
  fail as in the pinned implementation.
- Three fortified libc functions absent from the hosted libc adapter compile
  from pinned bionic `fortify.cpp`: `__memchr_chk`, `__pwrite64_chk`, and
  `__sendto_chk`. A packaging patch selects those entry points, retaining their
  bounds/count checks and libc results. Fatal reporting uses the existing Android
  host logger and aborts; this does not claim bionic logd's async signal safety.
- Original `NdkImageReader.cpp` and `NdkImage.cpp` provide the public NDK image
  API over the stock BufferQueue and hosted gralloc stack. CPU/GPU pixels,
  real sync-file fences, callback delivery, maxImages and independent buffer
  ownership are tested. The retired HIDL native-handle operation returns
  AOSP's `AMEDIA_ERROR_UNKNOWN` through its original service lookup for an absent
  token manager; the former host branch is removed. Public native-window
  access works. All 29 pinned public image functions are exported.
- Original NDK `surface_control.cpp` and `display_luts.cpp` expose all 52 pinned
  public SurfaceControl, JNI interoperability and LUT functions. Transactions
  use the existing SurfaceComposerClient and SurfaceFlinger; tests observe real
  GPU pixels, transferred fences, ordered commit/completion callbacks, latch
  statistics and independent task outputs. The former unsupported exports were
  deleted.
- Audio playback uses the stock client stack: verbatim AudioTrack/AudioSystem/
  AudioRecord/ToneGenerator Java, the original `android_media_Audio*.cpp` JNI and
  the pinned `libaudioclient` (AudioTrack, AudioSystem, the AudioTrackShared
  control block, AIDL conversions) over the hosted Binder. The Android side of
  the seam is `src/native/audio`: `HostAudioFlinger` and `HostAudioPolicyService`
  implement the generated `IAudioFlingerService` / `IAudioPolicyService` for one
  primary output on one speaker device, and `HostAudioTrack` is the per-track
  `IAudioTrack` over the pinned `AudioTrackServerProxy`. Unimplemented service
  operations come from generated `Bn*Unsupported` bases
  (`tools/gen-unsupported-cpp.py`) that fail with `EX_UNSUPPORTED_OPERATION`.
  The Linux side is `azk_pipewire.c`: one PipeWire playback stream per track
  (PipeWire resamples and mixes), or a null sink consuming frames in real time
  when no daemon is reachable, like AOSP's stub audio HAL. Reported latency is
  the output's 40 ms plus the track buffer; time stretching and post-creation
  sample-rate changes are logged as unimplemented and play at the original rate.
- MediaCodec and MediaCodecList use original JNI, native MediaCodec, CCodec and
  Codec2 clients. The session publishes the original AIDL ComponentStore wrappers
  in `azk-media-service`; `src/native/codec2` supplies FFmpeg components beneath
  the original SimpleC2Component lifecycle. Software audio and VAAPI video use
  separate `software` and `default` instances. XML and actual VAAPI profiles
  drive stock capability discovery. VAAPI converts directly into the original
  C2/IGBA allocator's DMA-BUF; stock buffer queues and SurfaceFlinger present it.
  There is no extra GPU copy. The launcher no longer links the FFmpeg decoder.
- MediaPlayer and MediaMetadataRetriever use original JNI and native clients
  across Binder to the original MediaPlayerService/NuPlayer in the same media
  process. The service uses original Codec2 and AudioTrack clients. Explicit
  CPU_READ video requests use shared YV12 storage and hardware-frame readback
  for stock thumbnail conversion; Surface playback keeps the direct DMA-BUF
  path. The registration wrapper exposes AOSP's static MediaPlayer registrar.
- `libaaudio.so` is the stock library, compiled whole into the native island
  (public C ABI, `AudioStreamTrack`/`AudioStreamRecord` legacy path, flow graph,
  and the MMAP client with its service protocol). The builder never selects
  MMAP because the host AudioFlinger reports no MMAP policy, so every stream is
  the legacy path over the stock AudioTrack client above; capture fails the
  way an absent input device does. `src/native/audio/AAudioSmoke.cpp` is the
  test registration that drives that C ABI.
- The libmediandk soname resolves to the platform island's original
  NdkMediaCodec implementation. Native calls go directly to native MediaCodec;
  codec handles and AMediaFormat share the same ELF image. The dev.artzig NDK
  smoke binds those public symbols without interposing or replacing them.
- MediaExtractor uses original AOSP Java/JNI, NuMediaExtractor, the native
  MediaExtractorService and its Binder protocols, data sources, metadata, and
  WAV/MP3/AAC/MP4/Matroska/Ogg/FLAC/AMR/MPEG-2 parsers. The hosted system
  process publishes `media.extractor`.
  A factory patch registers linked original plugin entry points and recognizes
  factory ownership without requiring Android's dedicated extractor daemon UID.
  JNI Binder conversions retain shared object ownership in both directions.
  Native PermissionCache/IPermissionController resolve the hosted `permission`
  service, which delegates to the same permission, package and AppOps services
  as Java clients. No new decoding or audio presentation backend is introduced.
  MediaDescrambler JNI uses the original CAS protocols and service discovery;
  the host does not supply a CAS hardware service.
- HardwareRenderer snapshots use the original AImageReader / RenderProxy
  native flow. Bitmap's host buffer factory imports through the existing native
  render-thread owner; no Java snapshot or RenderNode ownership bridge remains.
- MediaCrypto and MediaDrm use original JNI and native DRM clients, including
  generated AIDL/HIDL factory protocols. The host supplies no DRM plugin.
- `src/platform/graphics_buffer.zig` owns GBM allocation/import, metadata,
  mapping and DMA-BUF descriptors. Stock libui and AHardwareBuffer use it.
  Per-process mapper admission caps registered handles at 1,024 and charged
  storage at 2 GiB, with a 256 MiB per-buffer limit. Aliased imports are charged
  independently. Lower-only AZK_GRAPHICS_MAX_BYTES/MAX_BUFFERS settings can
  reduce these limits. Charges use DMA-BUF size or pitch extent and are not
  total physical VRAM accounting; raw handles after mapper release and driver
  allocations have separate lifetimes.
- `src/platform/host_composer.zig` owns the host display's layer validation,
  power state and software vsync worker through `std.Io`. It does not present
  task windows; Wayland owns desktop presentation.
- `src/native/platform_abi.zig` supplies the remaining opaque native ABI helpers.
  SQLiteConnection uses the original Android SQLite extension: ICU LOCALIZED
  and UNICODE collation, phone-number SQL functions and constrained file deletion.
  It shares the host SQLite and ICU libraries with the original connection JNI.
- `src/main.zig` starts ART and registers native interfaces.
- `src/surfaceflinger_main.zig` owns the separately supervised SF process's
  readiness and stdin shutdown through `std.Io`.
- `src/binder/` implements the hosted Binder wire protocol, the
  `azk-binder-broker` router, and the launcher's broker session.
- `src/binder/ndk.zig` is `libbinder_ndk` — the Android NDK binder C ABI
  (`AIBinder_*`, `AParcel_*`, `AServiceManager_*`, `AStatus_*`,
  `ABinderProcess_*`) implemented in Zig over the same AZKB protocol, so
  unmodified NDK/AIDL-cpp code links against it. Java, C++ and NDK callers share
  one runtime and broker connection per process, including identity, object
  references and death recipients. Installed as
  `lib/art/libbinder_ndk.so` (the NDK surface is a C ABI, so it stays in Zig
  and never touches the C++ allowlist); `src/bionic_translation.cfg` maps the
  soname to it. When loaded into an ART process (`System.load` of its absolute
  path; `JNI_OnLoad` captures the `JavaVM`), the `AIBinder_fromJavaBinder` /
  `AIBinder_toJavaBinder` / `AParcel_fromJavaParcel` bridge uses the original
  Binder/Parcel JNI helpers through `BinderJniBridge.cpp`. Java Parcel stores
  a real C++ facade Parcel pointer; original JNI owns JavaBBinder holders,
  JavaBBinder and BinderProxy native data. C++ Parcel owns or borrows shared
  AParcel storage. The NDK's borrowed view can be deleted without destroying
  Java's owner, and Binder conversion returns a JNI reference valid after the
  temporary native owner is released. `framework_jni.zig` invokes the original
  Binder/Parcel/ServiceManager registrars through an explicitly installed
  callback table. The dependency direction remains native island -> libbinder
  -> libbinder_ndk; standalone native programs do not acquire an ART dependency.
  The embedded runtime installs AOSP's libutils `canCallJava` thread-creation
  contract: attachment covers the full native entry function, including queued
  Binder destruction, and detachment follows native cleanup. A callback's local
  JNI frame does not end that thread's VM attachment.
  One broker connection and calling context serve all three languages. Local
  `AIBinder` operations need no broker connection. `framework_jni.zig` invokes
  the original utility JNI registrars in the native island. SystemProperties
  uses the existing broker-backed Bionic property hooks; SystemClock and
  CharsetUtils use the original implementations. Trace and Java Perfetto data
  sources use the original JNI and native Perfetto SDK, including real
  in-process capture. A system tracing daemon is not installed.
  Original Log, EventLog and SecurityLog JNI share liblog. A liblog output
  callback records emitted Java messages in the diagnostic journal. Only the
  Log JNI call is marked safe for that Java observer; native ART logs cannot
  call Java from runtime critical sections. logd is absent,
  so the original host reader returns no historical events. Original
  ClassLoaderFactory JNI calls the native loader's host namespace seam:
  class loaders search their library paths without Android linker namespaces.
  OverlayConfig also uses its original JNI; external idmap2 availability is
  still determined by the original executable probe.
  Process remains a host adaptation for priorities, signals, procfs and
  authenticated PID/start-time UID translation across hosted user namespaces.
- HIDL uses the pinned Java runtime, JNI, libhidlbase and libhwbinder inside
  `libandroid_platform.so`. Java and C++ base/manager protocols, plus Java CAS/GNSS
  interfaces, are generated from the original HAL declarations. Hardware Binder
  retains its own protocol and AOSP's `HwNoService`/`NoHwServiceManager` fallback;
  no HIDL hardware service manager is installed on the host. Native blob and
  parcel ownership and validation stay in AOSP. The Binder UAPI header is isolated
  with the other Android kernel headers. HIDL now shares the original Binder
  JNI exception-reporting implementation and its registered Java logging state.
- MediaMetrics uses the original Java class, native metrics serialization and
  Binder client, plus the generated `IMediaMetricsService` protocol. The native
  JNI table uses `const` instead of `constexpr` for compatibility with upstream
  Clang's function-pointer rules; the entries are unchanged. Native
  PersistableBundle serialization is reused in `libbinder`. The shared Parcel
  bridge supplies the pinned `Parcel.obtain()` helper, and module tests exercise
  nested native-to-Java bundle conversion and the metrics C ABI. Absence of a
  native `media.metrics` collector retains the original failed-record result.
  CAS native HIDL and Java AIDL protocols and TV input constants are generated
  from the pinned declarations; they do not supply hardware services.
- Original `android_os_Debug.cpp` now owns Debug JNI, with the original memory,
  DMA-BUF, memtrack and debuggerd clients. `RuntimeDiagnostics.cpp` translates
  glibc allocator totals to the Bionic ABI. When the kernel config is unavailable,
  the private Debug query reads the running kernel's BTF `task_struct` metadata
  for `CONFIG_VMAP_STACK`; it does not invent a complete kernel config. Unavailable
  allocation walking is reported explicitly, and absent Android services retain
  their original failure results. `process_memory.zig` remains used by Process JNI.
- Original `libs/nativedisplay/ADisplay.cpp` reads physical display metadata and
  composition preferences through SurfaceComposerClient. The build excludes
  hostgraphics' fixed display implementation. A native API regression compares
  dimensions, refresh rates, type and app-vsync offsets with Java's compositor
  queries and repeats acquisition/release.
- EGLImpl (EGL10), EGL14, EGL15 and EGLExt compile original AOSP JNI. The
  shared display and drawable handles live in `src/native/egl_backend.zig`.
  `egl_window.zig` exports system Mesa's rendered GBM buffers as DMA-BUFs;
  `EglWindow.cpp` attaches those buffers through stock Surface operations.
  There is no intermediate rendering buffer, copy context or presentation blit.
  BufferQueue release fences must signal before GBM can reuse a buffer. Bounded
  backpressure waits run outside the global EGL map lock. Resize and destruction
  retire old pools without invalidating Android's independent storage references.
  The gralloc v2 transport records the actual DRM FourCC separately from Android's
  requested format, while retaining v1 import support. Explicit CPU locks adapt
  BGRA/RGBA channel order and mapping stride when required; ordinary GPU
  presentation performs neither conversion nor readback. Window regressions
  compare consumer DMA-BUF identity to Mesa's rendered storage, check pixels and
  timestamps, and retain images across repeated buffer reuse.
- SharedMemory, MemoryFile, ApplicationSharedMemory and MemoryIntArray compile
  their original JNI over libcutils' sealed-memfd implementation, also used by
  NDK shared memory. The existing Linux adaptation selects memfd without
  Android vendor/property or ashmem-ioctl probes. No Kotlin allocation adapters
  or replacement JNI tables remain. Size seals and `F_SEAL_FUTURE_WRITE` retain
  writable existing mappings while preventing later writes through transferred
  descriptors. Original JNI owns mapping, initialization, atomics and cleanup.

- VintfObject and VintfRuntimeInfo use original JNI, libvintf, libkver and
  generated XSD parsers. Runtime queries read host uname, procfs, properties and
  SELinux state; missing Android manifests retain the original compatibility
  failure. `libvintf_jni.so` maps to the platform library.
- SystemSensorManager and NDK sensor APIs use original JNI/libsensor over
  Binder to original SensorService in the system process. Its constructor
  initializes normal mode when no HAL is present. Discovery is empty; event
  queues use BitTube, and absent hardware retains original enable/channel
  failures. No sensor JNI replacement remains.
- PerformanceHintManager uses original JNI and the native performance-hint
  client over IHintManager to AOSP HintManagerService in the system process.
  A constructor adaptation initializes unsupported capabilities when no Power
  HAL is declared. The native PowerHalController uses its existing host seam
  and original EmptyHalWrapper; no CPU/GPU hint session is fabricated.

- `src/native/legacy_gles.zig` supplies the C ABI expected by pinned GLImpl
  JNI: GLVND extension dispatch and Android client-buffer Bounds entry points.
  The original GLES1 JNI remains C++, with framework patch 32 correcting heap
  array release bases and existing typed-buffer bounds checks. The stock Java
  GLImpl owns client-buffer retention; core calls use GLVND GLESv1_CM.
- Stock GLES20–3.2 Java calls use AOSP's generated C++ JNI bindings and the
  existing Mesa GLES library. Frameworks-base patch 28 releases the original
  acquired JNI array pointer for positioned heap buffers and converts byte
  counts to typed elements before bounds checks. Original AndroidRuntime owns
  `registerNativeMethods` while the embedding adapter binds the existing VM.
  These bindings do not change Wayland presentation.
- USB device-name/ID conversion uses pinned UsbDevice JNI and the
  host-supported libusbhost C implementation. No new USB syscall wrappers
  or USB service are introduced.
- `VerityUtils` uses pinned JNI directly for Linux fs-verity stat/ioctl
  operations. The framework retains stock PKCS#7 verification and certificate
  restrictions. Unsupported filesystems preserve native error results and
  Java exceptions; no filesystem emulation is added.
- Native-library extraction, Strict APK library-name filtering, and IncFs
  queries use the pinned C++ implementations. The C++ IncFs sysprop accessor
  is generated by AOSP's sysprop_cpp tool. `src/native/selinux_backend.zig`
  adapts Android's policy-file backend and restorecon flag values to host
  libselinux, and reads the vendor genfs-label version through `std.Io` with
  the pinned Rust library's parsing and fallback. Android seapp policy and
  incremental installation services are not supplied by this native layer.
- StrictJarFile reuses the pinned ZIP JNI and Java signature verifier. V1
  signature verification, certificate tracking, and signature-stripping checks
  therefore retain the stock behavior without a repository-owned verifier.
- Stock HardwareBuffer and GraphicBuffer use the pinned libui ownership and
  serialization code with `graphics/HostGralloc.cpp` supplying GBM allocation
  and import. Binder transports the native-handle DMA-BUF descriptors; imported
  allocations preserve IDs and survive sender closure. Canvas locking uses
  pinned ACanvas functions with the actual mapping stride and full-buffer origin.
  Skia uses Android RGBA for its default software bitmap layout. The mapper
  describes Mesa buffer storage explicitly and feeds both
  stock BufferQueue and the hosted SurfaceFlinger service.
- OverlayProperties reuses pinned JNI with generated GUI AIDL C++ parcelables.
  Its virtual-display defaults and parcel encoding are stock. Patch 30 marshals
  LUT sampling keys through a real JNI int array and propagates the pinned
  Java constructor's validation exception. The native Parcel interface carries
  nullable parcelable arrays and packed enum arrays without an alternate codec.
- PixelCopy compiles the original HWUI JNI and Readback implementation. Stock
  Surfaces provide real ANativeWindow instances to its buffer/readback path.
- Original `AndroidRuntime.cpp` registers RuntimeInit and owns Java-capable
  native thread attachment. `HostedAndroidRuntime.cpp` is a genuine C++ subclass
  that binds the existing ART VM and connects `onStarted` to the Kotlin entry
  dispatcher. A private friend declaration is the runtime embedding seam; the
  implementation source is unchanged. Common initialization precedes target class
  initialization. Supervised APKs enter a Linux user namespace with their granted
  Android UID/GID before ART starts; the broker authenticates the real child and
  its one-use grant. Immutable libcore identity is installed before ART workers
  start, including the logical system identity used by framework tool processes.
  Socket control authorization checks active broker role and peer process lifetime,
  rather than trusting UID metadata alone. The VM invocation exit hook and Binder disconnect path use the
  original `AndroidRuntime::exit` boundary, terminating
  with `_exit` without libc teardown. `std.Io` has no immediate self-termination
  operation; this Zig version's libc-linked `std.process.exit` runs destructors
  under live ART workers. `jvm-exit-test` installs an atexit trap to verify the
  native boundary skips that cleanup. The DDMS native
  heap query retains the stock null result when malloc_debug is unavailable.
  The Kotlin entry dispatcher sends escaped main exceptions through Thread's
  stock pre-handler and uncaught-handler sequence, replacing the detach-time
  dispatch used by AndroidRuntime. Hosted AM stores the resulting crash report
  before RuntimeInit kills the process and Binder death records its exit.
- `HostedZygoteService` serves stock Process.start/ZygoteProcess requests through
  a session-owned reserved socket mapped by libcutils. It reuses the original
  argument parser, authenticates system peers, and execs real broker-authorized
  children. Bounded setup crosses the startup pipe before ART initialization;
  original RuntimeInit.applicationInit and ActivityThread.main then own SDK and
  compatibility policy, the main Looper and application attachment. This runtime
  does not fork a preloaded ART VM or share preload memory. Unsupported Android
  specialization requests fail explicitly. `HostedWebViewZygote` runs the
  original provider preload in a dedicated UID-1053 exec controller and serves
  stock Process.startWebView requests for broker-authorized isolated children.
  Before ART starts, `native/ProcessAppData.c` masks the session CE/DE roots in
  a private mount namespace, drops capabilities and sets no-new-privileges;
  public provider APKs remain readable. Children inherit that restricted view,
  but initialize their own VM. Shared ART profile groups and GWP-ASan protection
  are not implemented. Hosted AMS currently starts its service processes through
  the primary exec service; it does not route Chromium renderer service records
  through the dedicated controller.


`src/native/audio/azk_pipewire.c` is a small PipeWire adapter used by the host
AudioFlinger tracks. It contains the C macros and callback code around
`pw_stream`; stream ownership stays with the C++ track adapter.

## C++ boundary

- `audio/HostAudioFlinger.cpp`, `audio/HostAudioPolicyService.cpp` and
  `audio/HostAudioTrack.cpp` implement the pinned AudioFlinger / AudioPolicy /
  IAudioTrack AIDL for the host output, following AudioFlinger::createTrack,
  PlaybackThread::Track and AudioPolicyManager::getOutputForAttr, with the
  default engine's product strategy table. They exist because the interfaces
  and the shared control block are AOSP C++; the stock services cannot be
  compiled without audio HALs. `audio/HostSchedPolicy.cpp` is libprocessgroup's
  non-Android branch (no cgroups).
- `graphics/HostGralloc.cpp` implements the pinned AOSP GrallocMapper/Allocator
  interfaces over `platform/graphics_buffer.zig`. AOSP retains GraphicBuffer
  validation and accounting. GBM objects, imports, metadata and descriptor
  ownership live in Zig; the C++ adapter handles AOSP types and Fence objects.
- `graphics/SurfaceQueue.cpp` exposes stock BufferItemConsumer leases through a
  C interface. `platform/surface_queue.zig` runs bounded acquire-fence waits and
  returns leases on a worker; `platform/wayland.zig` owns DMA-BUF protocol objects
  and distinguishes compositor release from queue abandonment on teardown.
- `graphics/HostTrace.cpp` implements the pinned tracing namespace through
  cutils trace calls. This adapter provides the C++ symbol contract used by
  RenderEngine. The separate pinned Perfetto SDK supports in-process tracing;
  the host does not run an external `traced` daemon.
- `graphics/HostComposer.cpp` implements the pinned ComposerHal interface over
  the Zig display state. It retains AOSP GraphicBuffer/Fence objects and reports
  GPU client composition. Its fence is GPU completion, not Wayland presentation.
- `graphics/HostRenderEngineCapabilities.cpp` declares the supported GL backend
  at the AOSP RenderEngine factory seam; Vulkan is unavailable in this build.
- `graphics/SurfaceFlingerHost.cpp` initializes and publishes the pinned
  SurfaceFlinger legacy/AIDL services before entering the stock main loop.

- Original HWUI EglManager, RenderThread and Skia pipelines render through the
  Android EGL window ABI onto system Mesa GBM buffers. The task presenter owns
  every Wayland surface.
- `GraphicsEnvironmentBackend.cpp` connects stock GraphicsEnvironment JNI and
  GraphicsEnv to the hosted library loader. Class loaders have no associated
  Android linker namespace; namespace lookup returns null, and requested
  namespace creation/linking fails with a structured unsupported diagnostic.
  Driver selection, layer paths and GPU-service calls remain in pinned AOSP.
  The graphicsenv ANGLE override flag is generated in force-read-only mode for
  both Java and C++; its default is false. No mutable native flag store or new
  Wayland, PipeWire or FFmpeg implementation is added.
- Original Skia AHardwareBuffer factories and GL imports call the common
  `src/platform/graphics_egl.zig` EGL image owner. It retains Android buffers and
  imports their existing DMA-BUFs through system Mesa.
- `egl_cache.zig` connects stock HWUI cache setup to Mesa's Android blob-cache
  callbacks. Original FileBlobCache supplies persistence, checksums and eviction
  through C++ object accessors in EglWindow. Zig owns callback synchronization
  and flushes dirty entries at frame boundaries or EGL display release.
- `HwuiRenderProxyBridge.cpp` supplies two C calls for HWUI `RenderProxy`.
- `AospJniBridge.cpp` registers AOSP JNI code and manages Skia bitmap access.
  Stock GLUtils/Visibility/ETC1 use pinned OpenGL utility JNI and ETC1 encoding.
  The default Skia bitmap layout matches Android RGBA, so original GLUtils
  uploads bitmap bytes directly. The former upload-copy helper and host
  ImageDecoder color-order workaround have been removed. Mesa hardware
  buffer formats and SurfaceFlinger presentation retain their own metadata.
  BackupDataInput, BackupDataOutput, and FullBackup reuse their pinned JNI
  implementations and androidfw's BackupData/BackupHelpers directly. The one
  fixed read-only backup flag they consume comes from an aconfig-generated C++
  header; no native flag-storage provider is required for that constexpr call.
- Pinned `android_os_Parcel.cpp` owns Java's C++ Parcel objects.
  `BinderJniBridge.cpp` unwraps them for borrowed NDK access to shared storage.
- `binder/Parcel.cpp` and `binder/Binder.cpp` provide the source API expected by
  pinned C++ consumers over the Zig runtime's C boundary. The installed
  `lib/art/libbinder.so` owns one shared copy of AOSP RefBase, strings, native
  handles, timers, tracing, properties, logging, IInterface, IResultReceiver and
  Status helpers; the native island links it. GPU metadata uses stock Parcel
  vector, string, typed Binder and exception-header contracts over shared Zig
  storage. Properties share the session area and broker authorization with Java.
  Parcel storage, object references, descriptor ownership, routing, callbacks
  and process identity remain in Zig. This is a source facade for the supported
  hosted API, not a prebuilt Android C++ ABI replacement or a full graphics stack.
  The pinned ParcelFileDescriptor class uses the facade's framing methods;
  reliable-channel detach writes stay in Zig through `std.Io`.
  Libbase C++ logging stays in each consumer, because the native island and
  native executables currently link separate static C++ runtimes. Passing
  logging streams across that shared-library boundary corrupts locale ownership.

Run `zig build -j6 binder-cpp-test` for generated IFpsListener Bp/Bn transactions,
Parcel ownership, status, callbacks, identity and death regressions.
[Testing](testing.md#optional-binder-graphics-probe) also describes the standalone
DMA-BUF transport probe against production libraries.

The native island uses pinned libui `Fence.cpp` and Linux `libsync` for fence
waits, descriptor ownership and flattening. An isolated copy of stock
`ui/Fence.h` precedes the hostgraphics headers for every translation unit, so
HWUI and JNI share its actual layout. `tests/native/FenceTest.cpp`, invoked by
the Kotlin framework smoke, exercises readiness, timeout, shared ownership,
Parcel descriptor lifetime and invalid flattening input. Its pipe fixture tests
libsync's polling contract; it does not simulate sync-file merge/timestamp
ioctls. Java `SyncFence` and its JNI use pinned AOSP directly.

`Image` and `PublicFormatUtils` compile from pinned AOSP. The latter uses
its original three JNI methods and libui's `PublicFormat.cpp`. The
graphics-common Dataspace enum is generated from its VINTF-stable AIDL with
the NDK backend. `Image` supplies the abstract base contract (crop rectangles,
dataspace and validity checks). Concrete ImageReader/ImageWriter producers
use the stock JNI and shared graphics queues described below.
Run `zig build -j6 gui-test` on a GBM/EGL host for stock remote BufferQueue,
GPU pixels and sync fences, queue pressure, callbacks, resize and peer death.
`graphics-buffer-test` validates malformed handle metadata without a GPU.
`surface-wayland-test` uses the existing Cage harness to verify that GPU frames
from a separate stock Surface producer appear in a compositor screenshot.
`build/graphics.zig` separates libui storage from the libgui window closure;
HWUI uses real GraphicBuffer/Fence types and stock BufferQueue/BLAST clients.

These files can use HWUI, Skia, and AOSP C++ types. Put new platform behavior in
Zig behind a small C interface. Update this document and the build audit when
you add an application-owned C++ file.

The pinned GL/Ganesh RenderEngine is built by `build/renderengine.zig` and checked
by `renderengine-test`. It shares libui, libgui, Skia and the Zig allocator with
the hosted SurfaceFlinger service. Its texture import
uses the unchanged Skia AHardwareBuffer utilities over the shared EGL image
backend. Android image targets become Mesa DMA-BUF imports below those clients.
`src/platform/graphics_egl.zig` owns explicit display references for HWUI,
GLConsumer and RenderEngine, because Mesa does not reference-count repeated
`eglInitialize` calls as AOSP's balanced image/probe lifecycle assumes.
Host EGL context owners must use `azk_graphics_egl_initialize` and
`azk_graphics_egl_terminate` together; direct Mesa termination could invalidate
another owner's images, contexts or fence operations.

`build/surfaceflinger.zig` builds the pinned layer lifecycle, hierarchy, snapshot
and LayerFE preparation code. `surfaceflinger-frontend-test` reuses the 60 AOSP
hierarchy/lifecycle tests; `surfaceflinger-composition-test` checks GPU pixels
from isolated mirrored subtrees through LayerFE and RenderEngine. Both use the
existing Binder test runner with a real authenticated session. The latter is a
standalone composition fixture; the service, scheduler and output integration
have separate live coverage described below.

`perfetto-test` checks real in-process tracing sessions and decoded packets.
`host-composer-test` checks validation, GPU target/fence ownership, unsupported
capabilities and reentrant software-vsync teardown. `surfaceflinger-core` builds
the service, scheduler and composition engine. `surfaceflinger-service-test`
passes service publication, stock client creation and committed transactions,
permission denial and service death. `surfaceflinger-output-test` validates two
private mirrored subtree queues, GPU pixels, resize, stalled-output isolation,
release fences, callback ordering and SF death with a retained frame. Both use
the production `azk-surfaceflinger` executable installed by `surfaceflinger`.
Its Zig supervisor owns readiness/EOF I/O; the C++ service seam ends SF's
process-lifetime runtime with `_Exit` so static destructors cannot race the
compositor workers. The runner requires successful service and broker shutdown,
except for explicitly requested crash tests, and inherits live Mesa paths.
The normal launcher supervises broker, SF and system-server startup in that
order, with exact readiness and bounded shutdown. `session-test` covers failure
and resource cleanup. The session also supervises the task presenter, which
imports private SF task outputs and owns xdg-shell lifecycle, placement and input.

`build/input.zig` builds the pinned input transport closure. `input-channel-test`
reuses ten AOSP channel tests; `input-channel-broker-test` transfers the stock
InputChannelCore parcelable over AZKB and checks token/descriptor ownership,
stock KeyEvent publication/consumption, finished replies and peer close.
`input-publisher-test` passes 15 pinned C++ transport/verifier tests, and
`input-verifier-test` passes all 44 pinned Rust tests. `build/rust_sources.zig`
records the Soong host crate/features/cfg closure; `build/rust.zig` compiles it
with the packaged AOSP Rust 1.83.0 toolchain. The CXX generator is built from
pinned source, and AIDL/bindgen generate the input constants and Binder types.
`tools/input-bindgen.py` supplies Zig's libc++ configuration to bindgen's
libclang, avoiding mixed C++ standard-library headers. Native consumers link
both the C++ transport archive and Rust verifier through `input.Library.link`.
Rust test executables use Zig's C++ linker to preserve the native runtime ABI.
Stock `android_view_InputChannel.cpp` is also compiled into the JNI island.
Pinned Binder JNI supplies the C++ Java/Binder conversion functions.
`BinderJniBridge.cpp` also exposes them to NDK callers, preserving identity and
reference ownership. `input-channel-jvm-test` checks the Java/native Parcel framing,
duplication and disposal boundary.
The application compiles pinned MessageQueue, InputEventReceiver and
InputEventSender JNI. The former Zig MessageQueue implementation is replaced
by a borrowed poll-state adapter (`src/platform/message_queue.zig` and
`src/native/HostLooper.cpp`). Stock libutils Looper owns descriptors, callback
registration, wake draining and native delayed messages; stock MessageQueue
owns callback exception propagation. APK processes run stock Looper.loop;
Wayland runs in the separate task presenter. The borrowed poll-state adapter
remains for native/Java integration tests and transfers no descriptor ownership.
Two native tests, run with `input-channel-test`, verify delayed work, cross-thread
wakeups, fd callbacks and callback unregistration through the borrowed epoll.
`input-channel-jvm-test` also passes stock key/motion delivery, deferred motion
batching, handled/unhandled acknowledgements, cross-thread sender dispatch,
receiver exception rethrow/recovery, fd listener unregistration and disposal.
Stock application/window input-handle JNI and its common Rect/Size and region
helpers are registered in the application island. A test-only JNI library in
`build/input_jni.zig` exercises the production conversion functions from Kotlin:
application/channel/window token identity, exact frame and disjoint touch
regions, mutable metadata updates, clearing stale token/region/focus-transfer
state, and SF-supplied content dimensions/transforms/alpha. It is not installed
in the application tree. Stock Java SurfaceControl and native crop handles
share the same Binder objects. WMS/ATMS boot and APK integration have separate
coverage in [acceptance testing](acceptance.md).
`build/input_dispatcher.zig` builds stock InputDispatcher with the generated
package-manager/StatsD interfaces, KLL, native statistics, window flags and
Perfetto schemas. Six interface tests cover real overlap and transforms,
keyboard focus, gesture cancellation, target UID rejection, channel death and
HMAC tampering. The OpenSSL header adaptation retains stock verification.
The facade's UTF-8 string-vector API uses UTF-16 wire framing over Zig Parcel,
including nullable strings/vectors and stock failure mutation. Binder tests
exercise this framing across AZKB. Production input-service/window-info wiring
and WMS/ATMS boot are covered by the stock task and APK gates. `input-surfaceflinger-test` independently
verifies the cross-process SF window-info/focus boundary using explicit native
test collaborators. It covers container transforms, overlap, visibility versus
removal, cancellation and SF death; it does not substitute for WMS/ATMS boot.
The HWC adapter returns an empty successful LUT-request query, matching pinned
HidlComposer: SF's validation path requires that even without hardware LUTs.

Stock HardwareBuffer/GraphicBuffer/SyncFence and SurfaceControl/SurfaceSession
Java/JNI share native objects with libui/libgui and NDK clients. AZKB owns
Binder objects and Parcel tables, and attaches native callback workers to ART
before C++ listeners can call Java. `buffer-jvm-test` and
`surface-client-jvm-test` verify ownership, fences and SF callbacks.

Stock Surface, SurfaceTexture and BLAST JNI drive the HWUI ANativeWindow path.
Original SkiaOpenGLPipeline and EglManager render through ReliableSurface and
the Android EGL window ABI. The GBM driver queues the rendered buffer through
the stock producer without a presentation blit. Producer fences travel with
queue or cancellation; waits stay on the render thread. Original RenderThread
uses native Choreographer, DisplayManagerGlobal JNI and Looper-driven ThreadBase
for callback scheduling.
`surface-buffer-jvm-test` verifies pixels, resize, two independent consumers,
abandonment recovery, BLAST commits and zero retained allocations. The legacy
direct Wayland target and its RenderProxy/CanvasContext extensions are removed.

### Java policy over the stock input dispatcher

`HostInputDispatcher.cpp` adapts the pinned dispatcher policy to typed Kotlin
callbacks and stock InputChannel/InputEvent JNI. SurfaceFlinger publishes its
actual window metadata through a registered listener; the inputflinger Binder
endpoint forwards focus and channel operations using the same Zig Binder
runtime. Native threads attach to ART for callbacks. Shutdown removes listeners,
stops dispatch, rejects reentrant close and makes late Binder callbacks fail
without retaining the closed dispatcher. Stock WMS/ATMS own Android task/window
policy. The task presenter routes Wayland events through this production input
boundary with task identity and the current coordinate transform.

The Java/SF gate exercises focused keys, transformed overlap, hidden-window
gesture completion, removal cancellation, pointer capture acknowledgement,
channel death and shutdown. The WMS lifetime token and policy in that gate are
fixtures. Separate stock WMS boot, cold-launch, desktop task and APK gates verify
production registration, overlap, focus, transformed motion and window removal.

Stock ImageReader/ImageWriter JNI uses the same GraphicBuffer/BufferQueue
implementation. Its host build enables the real gralloc and HardwareBuffer
branches. Layoutlib's zero CPU usage is inappropriate for this backend.
`ImageWriter` uses the original SurfaceUtils Java class and native registration.
ImageReader and ImageWriter retain stock `media_jni` library initialization. Image queue transfer and transition border luminance pass through the
shared mapper, including IMapper's all-zero full-buffer rectangle contract.

The WM-facing `HostInputManagerBridge` owns pinned filter installation,
uninstallation and fallback-key policy. Its desktop transport calls
`HostInputDispatcher.notifyInputEvent`, which translates Java event values
(including motion history and transforms) to stock NotifyArgs. Injected events
intentionally bypass filtering; only filter reinjection uses that path. Native
InputDispatcher owns key repeat, so the Wayland seat must not also synthesize
repeat events when using this boundary.

Original PDF rendering uses the separately loaded `libpdfclient.so`, with
PDFium globals localized by its pinned export script. Original PdfEditor
continues to own a separate PDFium instance in the platform library. Native
font lookups use the shared host-path mapping; bitmap results retain Android's
RGBA layout. Modern GIF decoding uses original SkWuffsCodec and pinned Wuffs,
with no new rendering or presentation adapter.

Original recorder, media-profile, audio-effect and Visualizer JNI now use the
existing media/audio services. The handwritten audio JNI module and its override
of MediaCodec persistent-input-surface cleanup were removed. This restores client
state and ownership contracts; it does not create a capture device or effect engine.
The combined recording/Debug batch passed full acceptance, including recorder
permission/lifecycle failures, real Debug memory/kernel queries and all 28 APK cases.
