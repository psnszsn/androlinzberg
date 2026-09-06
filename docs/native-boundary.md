# Native language boundary

Use Zig for application logic and Linux platform logic. Use C++ only where the
AOSP graphics stack or an AOSP C++ interface requires it.

Run the boundary check after a native change:

```sh
zig build -j10 cpp-audit
```

## Zig-owned code

- `src/platform/audio.zig` implements AudioTrack behavior and PipeWire state.
- `src/codec/jni.zig` connects MediaCodec JNI to the FFmpeg decoder.
- `src/platform/host_renderer.zig` manages the Wayland render target.
- `src/native/platform_abi.zig` supplies HardwareBuffer, GBM, SQLite, and POSIX
  compatibility interfaces.
- `src/main.zig` starts ART and registers native interfaces.
- `src/binder/` implements the hosted Binder wire protocol, the
  `azk-binder-broker` router, and the launcher's broker session.
- `src/binder/ndk.zig` is `libbinder_ndk` — the Android NDK binder C ABI
  (`AIBinder_*`, `AParcel_*`, `AServiceManager_*`, `AStatus_*`,
  `ABinderProcess_*`) implemented in Zig over the same AZKB protocol, so
  unmodified NDK/AIDL-cpp code links against it. It opens its own broker
  connection (a second peer of the process's package, sharing the JVM
  runtime's uid), so a native service a Java peer looks up and a Java service a
  native caller resolves both route through the broker. Installed as
  `lib/art/libbinder_ndk.so` (the NDK surface is a C ABI, so it stays in Zig
  and never touches the C++ allowlist); `src/bionic_translation.cfg` maps the
  soname to it. When loaded into an ART process (`System.load` of its absolute
  path; `JNI_OnLoad` captures the `JavaVM`), the `AIBinder_fromJavaBinder` /
  `AIBinder_toJavaBinder` / `AParcel_fromJavaParcel` bridge converts binders
  across the language boundary in-process with true sharing: the real AOSP
  `android.os.Parcel`'s `mNativePtr` *is* an `AParcel`, a Java `Binder`'s
  `mObject` is a `JavaBBinderHolder` whose `AIBinder` calls back
  `Binder.execTransact` with the native parcels, and a `BinderProxy`'s
  `mNativeData` owns a remote (or native-local) `AIBinder`. The same library
  registers the Parcel/Binder/BinderProxy/BinderInternal/ServiceManager
  natives (`framework_jni.zig` → `registerBinderFrameworkNatives`), so one
  broker connection per process serves Java and native code alike. Local
  `AIBinder` operations need no broker connection. `framework_jni.zig` is also
  the registrar for the other real `android.os` classes: SystemProperties
  (property area reader, broker set), SharedMemory/MemoryFile (memfd),
  Process (priorities, signals, /proc), SystemClock (`clock_gettime`; all
  `@CriticalNative`, so the Zig functions take no `JNIEnv`), Trace (the
  Ravenwood no-op shape: no tag enabled, emitters empty), and
  `ClassLoaderFactory.createClassloaderNamespace` (null: the bionic bridge has
  no linker namespaces, loaders search their library path directly).
- `src/binder/debug_jni.zig` registers the stock `android.os.Debug` natives.
  `process_memory.zig` retains pinned Android heap classification and reads
  Linux procfs/sysfs through `std.Io`. Allocator reports use the host allocator's
  memory-stream API, then write through an `Io.File` duplicate; caller descriptors
  remain owned by their Java streams. Binder object counts come from the hosted
  Binder runtime. Android-only debuggerd, malloc_debug, memtrack, and heap-tracing
  facilities retain failure results or explicit unsupported diagnostics.
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

- `src/binder/vintf_jni.zig` implements VintfRuntimeInfo's independently cached
  runtime-info groups. Uname reuses libcore Posix JNI; CPU and SELinux policy
  files use `std.Io`, and AVB versions come from stock system properties. The
  full libvintf build still needs Android HAL metadata and kernel-config
  generators. Null and zero query failures match the pinned target behavior.

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
- Stock HardwareBuffer and GraphicBuffer use the GBM-backed AHardwareBuffer
  ownership in `platform_abi.zig`. `HardwareBufferParcel.kt` writes the pinned
  Flattenable/GraphicBuffer envelope with a host mapper payload; Binder carries
  the DMA-BUF descriptor and recipients import it into GBM. Imported allocations
  preserve IDs and survive sender closure. Canvas locking uses pinned ACanvas
  functions with the actual mapping stride and full-buffer origin. Patch 29
  selects RGBA explicitly for Android RGBA buffers on the BGRA-native Skia
  build. This layer supports the existing single-plane, single-layer formats;
  it does not implement an Android gralloc service or SurfaceFlinger.
- OverlayProperties reuses pinned JNI with generated GUI AIDL C++ parcelables.
  Its virtual-display defaults and parcel encoding are stock. Patch 30 marshals
  LUT sampling keys through a real JNI int array and propagates the pinned
  Java constructor's validation exception. The native Parcel interface carries
  nullable parcelable arrays and packed enum arrays without an alternate codec.
- PixelCopy's pinned HWUI JNI reports SourceInvalid when the host's Java Surface
  adapter has no ANativeWindow (patch 31). This prevents a null dereference in
  RenderProxy and preserves stock Java callback handling; compositor readback
  remains unavailable.
- `src/binder/runtime_init_jni.zig` connects stock RuntimeInit to the launcher's
  Kotlin entry dispatcher. Common initialization precedes target class
  initialization. Hosted Binder workers start after the target claims its
  virtual process identity, since the host cannot use Android OS UIDs for that
  identity. The VM invocation exit hook and Binder disconnect path use the
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


`src/platform/azk_pipewire.c` is a small PipeWire adapter. It contains the C
macros and callback code that Zig cannot import directly.

## C++ boundary

- `ArtzigGpuPipeline.cpp` connects HWUI to EGL and Wayland.
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
  BackupDataInput, BackupDataOutput, and FullBackup reuse their pinned JNI
  implementations and androidfw's BackupData/BackupHelpers directly. The one
  fixed read-only backup flag they consume comes from an aconfig-generated C++
  header; no native flag-storage provider is required for that constexpr call.
- `AospParcelAbi.cpp` resolves a Java Parcel's `mNativePtr` to its genuine C++
  Parcel view. Each native Parcel owns its view; there is no thread-local backing.
- `binder/Parcel.cpp` and `binder/Binder.cpp` provide the source API expected by
  pinned C++ consumers over the Zig runtime's C boundary. The installed
  `lib/art/libbinder.so` owns one shared copy of AOSP RefBase, strings, native
  handles, logging, IInterface, IResultReceiver and Status helpers; the native
  island links it. GPU metadata uses stock Parcel vector count, nullable UTF-16
  string-vector, typed Binder and exception-header contracts over this storage.
  Parcel storage, object references, descriptor ownership, routing, callbacks
  and process identity remain in Zig. This is a source facade for the supported
  hosted API, not a prebuilt Android C++ ABI replacement or a full graphics stack.

Run `zig build -j6 binder-cpp-test` for generated IFpsListener Bp/Bn transactions,
Parcel ownership, status, callbacks, identity and death regressions. The original
optional hardware experiment remains under `tools/prototypes/binder-facade`.

These files can use HWUI, Skia, and AOSP C++ types. Put new platform behavior in
Zig behind a small C interface. Update this document and the build audit when
you add an application-owned C++ file.
