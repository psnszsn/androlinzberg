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
  soname to it.

`src/platform/azk_pipewire.c` is a small PipeWire adapter. It contains the C
macros and callback code that Zig cannot import directly.

## C++ boundary

- `ArtzigGpuPipeline.cpp` connects HWUI to EGL and Wayland.
- `HardwareBufferRender.cpp` imports DMA-BUF buffers into EGL and Skia.
- `HwuiRenderProxyBridge.cpp` supplies two C calls for HWUI `RenderProxy`.
- `AospJniBridge.cpp` registers AOSP JNI code and manages Skia bitmap access.
- `AospParcelAbi.cpp` supplies required AOSP `android::Parcel` symbols. Most
  members abort (their paths are never taken in-process); the subset
  CursorWindow's parcel path uses is a real JNI bridge onto the hosted Java
  `android.os.Parcel`, so cross-process provider cursors ship genuine
  CursorWindows (memfd over SCM_RIGHTS).

These files can use HWUI, Skia, and AOSP C++ types. Put new platform behavior in
Zig behind a small C interface. Update this document and the build audit when
you add an application-owned C++ file.
