# Native language boundary

Application and Linux platform logic is implemented in Zig. C++ is restricted
to the real AOSP HWUI/Skia implementation and ABI calls that cannot be
expressed with a C ABI at their source. `zig build cpp-audit` enforces the
application-owned C++ allowlist and deliberately ignores dependencies such as
`zig-pkg`, `extra`, `vendor`, Skia, and AOSP.

## Zig-owned platform code

- `src/platform/audio.zig`: AudioTrack JNI, buffering, playback state, volume,
  and the headless clock. `azk_pipewire.c` is a mechanical adapter containing
  only PipeWire object/callback plumbing and SPA macros that Zig's C translator
  cannot consume.
- `src/codec/jni.zig` and `azk_codec.zig`: MediaCodec JNI and FFmpeg decoder.
- `src/platform/host_renderer.zig`: Wayland render-target state and renderer
  JNI.
- `src/native/platform_abi.zig`: AHardwareBuffer/GBM, HardwareBuffer JNI,
  SQLite Android collators, and the POSIX strerror compatibility export.
- `src/main.zig`: ART and native registration orchestration.

## Residual C++ island

- `ArtzigGpuPipeline.cpp`: HWUI render pipeline and EGL/Wayland presentation.
- `HardwareBufferRender.cpp`: DMA-BUF import into EGL and Skia Ganesh.
- `HwuiRenderProxyBridge.cpp`: two C ABI calls into HWUI `RenderProxy`.
- `AospJniBridge.cpp`: registration of AOSP C++ JNI functions, GraphicsJNI VM
  state, and Skia bitmap pixel access.
- `AospParcelAbi.cpp`: link-time C++ member stubs required by AOSP input JNI.

These files may depend on HWUI, Skia, or AOSP C++ types. New platform features
belong in Zig behind a narrow C ABI; adding another application C++ file
requires updating both this decision record and the build audit.
