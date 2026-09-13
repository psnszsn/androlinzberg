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
  only stock `pollOnce` consumes readiness and dispatches callbacks. The host
  renderer observes that descriptor through its existing event loop.
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
  This provider fixes the previously diagnosed zero-cadence frame-rate hint;
  [provider provenance](../aosp/README.md#webview-provider) and
  [acceptance checks](acceptance.md) are recorded separately.
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
  AOSP's `AMEDIA_ERROR_UNKNOWN` for an absent token manager; public native-window
  access works. All 29 pinned public image functions are exported.
- Original NDK `surface_control.cpp` and `display_luts.cpp` expose all 52 pinned
  public SurfaceControl, JNI interoperability and LUT functions. Transactions
  use the existing SurfaceComposerClient and SurfaceFlinger; tests observe real
  GPU pixels, transferred fences, ordered commit/completion callbacks, latch
  statistics and independent task outputs. The former unsupported exports were
  deleted.
- `src/platform/audio.zig` retains the PCM ring, PipeWire state and timing operations,
  registered on `dev.artzig.HostedPcmStream`. Verbatim AudioTrack Java calls the
  pinned 54-method interface in `src/binder/audio_track_jni.zig`, which checks all
  Kotlin bridge descriptors at startup. `HostedAudioTrackBackend` owns stream
  lifetime, client queue allocation, blocking/nonblocking writes, PCM conversion,
  drain/flush and position accounting. The Java client owns public validation and
  PlayerBase registration. Reported latency is allocated frames divided by sample
  rate; downstream host-device latency is not measured. Native features outside the
  existing stream's capabilities retain explicit unsupported diagnostics or pinned
  failure results. The PipeWire backend implementation is unchanged.
- `src/codec/jni.zig` registers the eight existing decoder operations on
  `dev.artzig.HostedCodecEngine`. Verbatim MediaCodec Java enters the pinned JNI
  interface in `src/binder/media_codec_jni.zig`. `HostedMediaCodecBackend` owns
  codec lifetime, bounded queues, synchronous/asynchronous state, format changes
  and timestamped video release. JNI returns independent direct-buffer views;
  original Java controls their accessibility and callback delivery. Unsupported
  secure/block/encoder and additional surface/resource operations report explicit
  diagnostics or pinned failure results. The FFmpeg and presentation engines are
  unchanged.
- `src/binder/aaudio_ndk.zig` exports 42 AAudio entry points over stock AudioTrack.
  HostedAAudio owns callback submission and stream lifecycle; original native
  AAudioStreamParameters, AAudioUtilities and AudioContainers validate parameters
  and convert channel/format values. The shared JNI scope preserves calling-thread
  attachment. Callback storage is allocated before publishing a stream and remains
  owned until close drains delivery. PCM16/float playback uses the existing stream;
  capture/MMAP/offload and unavailable formats/devices retain failure results.
  Performance is NONE, sharing is SHARED, and the 10ms submission quantum is not a
  measurement of the PipeWire device period. The PipeWire implementation is unchanged.
- `src/binder/media_ndk.zig` exports 24 NDK codec entry points. Its Kotlin module
  `HostedNdkMediaCodec` shares the stock Java MediaCodec client, preserving codec
  lifecycle and callback ownership across native calls. The C++ helpers in
  `AospJniBridge.cpp` only convert original AMessage/AMediaFormat objects through
  the pinned key/value converters. The libmediandk soname maps to the platform
  island and its Binder dependency so formats and codecs resolve together.
  Native window/secure configuration remains explicitly unavailable; this does
  not claim the complete NDK media ABI.
- MediaExtractor uses original AOSP Java/JNI, NuMediaExtractor, the native
  MediaExtractorService and its Binder protocols, data sources, metadata, and
  WAV/MP3/AAC parsers. The hosted system process publishes `media.extractor`.
  A factory patch registers linked original plugin entry points and recognizes
  factory ownership without requiring Android's dedicated extractor daemon UID.
  JNI Binder conversions retain shared object ownership in both directions.
  Native PermissionCache/IPermissionController resolve the hosted `permission`
  service, which delegates to the same permission, package and AppOps services
  as Java clients. No new decoding or audio presentation backend is introduced.
  MediaDescrambler JNI uses the original CAS protocols and service discovery;
  the host does not supply a CAS hardware service.
- `src/platform/hardware_snapshot.zig` registers HardwareRenderer snapshot JNI
  through HardwareBufferRenderer. `RenderNodeReferenceBridge.cpp` transfers
  native RenderNode ownership to the adopted Java wrapper; it owns no window.
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
- `src/native/platform_abi.zig` supplies opaque HardwareBuffer JNI and SQLite
  compatibility interfaces.
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
  One broker connection and calling context serve all three languages. Local
  `AIBinder` operations need no broker connection. `framework_jni.zig` is also
  the registrar for the other real `android.os` classes: SystemProperties
  (property area reader, broker set), SharedMemory/MemoryFile (memfd),
  Process (priorities, signals, /proc; `std.Io` proc-file ownership and pinned
  field/numeric parsing; authenticated PID/start-time UID translation across
  hosted user namespaces), SystemClock (`clock_gettime`; all
  `@CriticalNative`, so the Zig functions take no `JNIEnv`), Trace (the
  Ravenwood no-op shape: no tag enabled, emitters empty), and
  `ClassLoaderFactory.createClassloaderNamespace` (null: the bionic bridge has
  no linker namespaces, loaders search their library path directly).
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
- `src/binder/debug_jni.zig` registers the stock `android.os.Debug` natives.
  `process_memory.zig` retains pinned Android heap classification and reads
  Linux procfs/sysfs through `std.Io`. Allocator reports use the host allocator's
  memory-stream API, then write through an `Io.File` duplicate; caller descriptors
  remain owned by their Java streams. Binder object counts come from the hosted
  Binder runtime. Android-only debuggerd, malloc_debug, memtrack, and heap-tracing
  facilities retain failure results or explicit unsupported diagnostics.
- `src/binder/legacy_egl_jni.zig` registers the 28 pinned EGLImpl methods. Its
  Java objects and EGL 1.0 reporting remain stock; Android display reference
  counts are shared with EGL14 in `egl_backend.zig`, including concurrent
  initialization and final-owner cleanup. GLSurfaceView uses its stock GLThread;
  the regression renders through its public pbuffer surface factory while
  native-window presentation remains at the existing compositor boundary.
- `src/binder/egl_jni.zig` registers the stock EGL14 native methods and handle
  classes. `egl_backend.zig` uses the existing GBM render device and supplies
  pbuffer drawables over GBM buffers, including pixel preservation on swaps and
  deferred destruction while current. A surfaceless display remains available
  when the render device is absent. JNI retains handle identity, array
  validation, and native error results.
  The Binder JNI library links EGL explicitly because ART also loads its
  connectivity alias independently of the launcher. Window surfaces still need
  a presentation adapter compatible with the host EGL driver; this JNI seam
  does not change the existing Wayland backend.
- `src/binder/application_shared_memory_jni.zig` replaces only the allocation
  and read-only-duplication ports of stock ApplicationSharedMemory JNI.
  The Kotlin `ApplicationSharedMemoryBackend` reuses libcore's existing Posix
  descriptor API to create and seal memfds; it does not add a second native
  implementation of those syscalls. The compiled AOSP factory supplies the
  layout size during registration, then its mapping, initialization, feature
  cache, and cross-process atomics remain in the pinned C++ implementation.
  Size seals and `F_SEAL_FUTURE_WRITE` retain writable existing mappings while
  preventing later write access through transferred descriptors.
- `src/binder/memory_int_array_jni.zig` replaces MemoryIntArray's creation and
  mapping ports with `MemoryIntArrayBackend`'s sealed memfds through libcore
  Posix. The stock JNI retains atomic reads/writes, size queries, unmapping,
  and closure. Failed mapping closes the transferred descriptor and disarms
  the partially constructed object's finalizer. The pinned ashmem UAPI header
  is isolated from bionic's other headers in the host C++ include tree.

- `src/binder/media_crypto_jni.zig` supplies stock MediaCrypto's seven native
  methods at the absent DRM-factory boundary. The original JNI requires
  ICrypto and Android AIDL/HIDL DRM plugins. Capability queries return false,
  construction reports the pinned NO_INIT MediaCryptoException, and released
  or uninitialized objects retain the stock argument failures and safe cleanup.
  No decryption implementation is supplied. The installed libmedia_jni alias
  exposes the registered framework JNI library under the stock load name.

- `src/binder/vintf_jni.zig` implements VintfRuntimeInfo's independently cached
  runtime-info groups. Uname reuses libcore Posix JNI; CPU and SELinux policy
  files use `std.Io`, and AVB versions come from stock system properties. The
  full libvintf build still needs Android HAL metadata and kernel-config
  generators. Null and zero query failures match the pinned target behavior.

- `src/native/legacy_gles.zig` supplies the C ABI expected by pinned GLImpl
  JNI: GLVND extension dispatch and Android client-buffer Bounds entry points.
  The original GLES1 JNI remains C++, with framework patch 32 correcting heap
  array release bases and existing typed-buffer bounds checks. The stock Java
  GLImpl owns client-buffer retention; core calls use GLVND GLESv1_CM.
- Stock GLES20–3.2 Java calls use AOSP's generated C++ JNI bindings and the
  existing Mesa GLES library. Frameworks-base patch 28 releases the original
  acquired JNI array pointer for positioned heap buffers and converts byte
  counts to typed elements before bounds checks. `AospJniBridge.cpp` supplies
  the pinned `AndroidRuntime::registerNativeMethods` body without constructing
  a second VM. These bindings do not change Wayland presentation.
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
  Patch 29 selects RGBA explicitly for Android RGBA buffers on the BGRA-native
  Skia build. The mapper supports the documented host formats and feeds both
  stock BufferQueue and the hosted SurfaceFlinger service.
- OverlayProperties reuses pinned JNI with generated GUI AIDL C++ parcelables.
  Its virtual-display defaults and parcel encoding are stock. Patch 30 marshals
  LUT sampling keys through a real JNI int array and propagates the pinned
  Java constructor's validation exception. The native Parcel interface carries
  nullable parcelable arrays and packed enum arrays without an alternate codec.
- PixelCopy's HWUI JNI retains a null-native-window guard (patch 31), reporting
  SourceInvalid through the original callback. Valid stock Surfaces provide
  real ANativeWindow instances and use the existing HWUI readback path.
- `src/binder/runtime_init_jni.zig` connects stock RuntimeInit to the launcher's
  Kotlin entry dispatcher. Common initialization precedes target class
  initialization. Supervised APKs enter a Linux user namespace with their granted
  Android UID/GID before ART starts; the broker authenticates the real child and
  its one-use grant. Immutable libcore identity is installed before ART workers
  start, including the logical system identity used by framework tool processes.
  Socket control authorization checks active broker role and peer process lifetime,
  rather than trusting UID metadata alone. The VM invocation exit hook and Binder disconnect path use the
  native `AndroidRuntime::exit` boundary in `AospJniBridge.cpp`, terminating
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


`src/platform/azk_pipewire.c` is a small PipeWire adapter. It contains the C
macros and callback code that Zig cannot import directly.

## C++ boundary

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

- `ArtzigGpuPipeline.cpp` connects HWUI to stock ANativeWindow queues and shared
  GBM/Ganesh rendering. The task presenter owns every Wayland surface.
- `GraphicsEnvironmentBackend.cpp` connects stock GraphicsEnvironment JNI and
  GraphicsEnv to the hosted library loader. Class loaders have no associated
  Android linker namespace; namespace lookup returns null, and requested
  namespace creation/linking fails with a structured unsupported diagnostic.
  Driver selection, layer paths and GPU-service calls remain in pinned AOSP.
  The graphicsenv ANGLE override flag is generated in force-read-only mode for
  both Java and C++; its default is false. No mutable native flag store or new
  Wayland, PipeWire or FFmpeg implementation is added.
- `HardwareBufferRender.cpp` imports DMA-BUF buffers into EGL and Skia.
- `HwuiRenderProxyBridge.cpp` supplies two C calls for HWUI `RenderProxy`.
- `AospJniBridge.cpp` registers AOSP JNI code and manages Skia bitmap access.
  Stock GLUtils/Visibility/ETC1 use pinned OpenGL utility JNI and ETC1 encoding.
  `HostBitmapUpload.h` exposes the bitmap upload boundary: host BGRA software
  pixels are copied to RGBA for GLUtils, retaining alpha/color-space metadata
  and leaving the original Java bitmap unchanged. Other layouts retain their
  stock pixel representation. This conversion is confined to bitmap uploads;
  SurfaceFlinger task presentation continues through GPU buffer queues.
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
Parcel ownership, status, callbacks, identity and death regressions. The original
optional hardware experiment remains under `tools/prototypes/binder-facade`.

The native island uses pinned libui `Fence.cpp` and Linux `libsync` for fence
waits, descriptor ownership and flattening. An isolated copy of stock
`ui/Fence.h` precedes the hostgraphics headers for every translation unit, so
HWUI and JNI share its actual layout. `tests/native/FenceTest.cpp`, invoked by
the Kotlin framework smoke, exercises readiness, timeout, shared ownership,
Parcel descriptor lifetime and invalid flattening input. Its pipe fixture tests
libsync's polling contract; it does not simulate sync-file merge/timestamp
ioctls. Java `SyncFence` and its JNI use pinned AOSP directly.

`Image` and `PublicFormatUtils` now compile from pinned AOSP. The latter uses
its original three JNI methods and libui's real `PublicFormat.cpp`, replacing
hostgraphics' identity format mapping and always-unknown dataspace. The
graphics-common Dataspace enum is generated from its VINTF-stable AIDL with
the NDK backend. `Image` supplies the abstract base contract (crop rectangles,
dataspace and validity checks). Concrete ImageReader/ImageWriter producers now
use the stock JNI and shared graphics queues described below; their runtime
sources replace the previous documentation-only ImageWriter header.
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
uses the pinned Skia AHardwareBuffer utilities with a small DMA-BUF adaptation.
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
The normal launcher now supervises broker, SF and system-server startup in that
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
duplication and disposal boundary; its follow-up JVM and full APK suite pass.
The application now compiles pinned MessageQueue, InputEventReceiver and
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
The full queue-replacement regression passed native/Binder/GUI/input/SF checks,
build audits/tools, JVM checks and all 14 APK tests (41 steps, zero runtime
issues), recorded in `/tmp/input-event-full-regression-1.log`.
Stock application/window input-handle JNI and its common Rect/Size and region
helpers are registered in the application island. A test-only JNI library in
`build/input_jni.zig` exercises the production conversion functions from Kotlin:
application/channel/window token identity, exact frame and disjoint touch
regions, mutable metadata updates, clearing stale token/region/focus-transfer
state, and SF-supplied content dimensions/transforms/alpha. It is not installed
in the application tree. Stock Java SurfaceControl and native crop handles now
share the same Binder objects; these isolated tests do not claim WMS/ATMS boot. The
follow-up JVM, build audits and full 14-test APK suite pass with 41 recorded
steps and zero runtime issues (`/tmp/input-handle-full-regression-1.log`).
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
Java/JNI now share native objects with libui/libgui and NDK clients. AZKB owns
Binder objects and Parcel tables, and attaches native callback workers to ART
before C++ listeners can call Java. `buffer-jvm-test` and
`surface-client-jvm-test` verify ownership, fences and SF callbacks.

Stock Surface, SurfaceTexture and BLAST JNI drive the HWUI ANativeWindow path.
`ArtzigGpuPipeline` connects/dequeues/imports/queues buffers through stock
ReliableSurface, using a shared RenderThread GBM/Ganesh context and surfaceless
EGL. Producer fences travel with queue or cancellation; waits stay on the
render thread. Native Choreographer, DisplayManagerGlobal JNI and stock
Looper-driven ThreadBase restore pinned RenderThread callback scheduling.
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
