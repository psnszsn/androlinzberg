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

`src/platform/azk_pipewire.c` is a small PipeWire adapter. It contains the C
macros and callback code that Zig cannot import directly.

## C++ boundary

- `ArtzigGpuPipeline.cpp` connects HWUI to EGL and Wayland.
- `HardwareBufferRender.cpp` imports DMA-BUF buffers into EGL and Skia.
- `HwuiRenderProxyBridge.cpp` supplies two C calls for HWUI `RenderProxy`.
- `AospJniBridge.cpp` registers AOSP JNI code and manages Skia bitmap access.
- `AospParcelAbi.cpp` resolves a Java Parcel's `mNativePtr` to its genuine C++
  Parcel view. Each native Parcel owns its view; there is no thread-local backing.
- `binder/Parcel.cpp` and `binder/Binder.cpp` provide the source API expected by
  pinned C++ consumers over the Zig runtime's C boundary. The installed
  `lib/art/libbinder.so` owns one shared copy of AOSP RefBase, strings, native
  handles, logging, IInterface and Status helpers; the native island links it.
  Parcel storage, object references, descriptor ownership, routing, callbacks
  and process identity remain in Zig. This is a source facade for the supported
  hosted API, not a prebuilt Android C++ ABI replacement or a full graphics stack.

Run `zig build -j6 binder-cpp-test` for generated IFpsListener Bp/Bn transactions,
Parcel ownership, status, callbacks, identity and death regressions. The original
optional hardware experiment remains under `tools/prototypes/binder-facade`.

These files can use HWUI, Skia, and AOSP C++ types. Put new platform behavior in
Zig behind a small C interface. Update this document and the build audit when
you add an application-owned C++ file.
