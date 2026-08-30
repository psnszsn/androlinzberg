# Architecture

androlinzberg runs one Android application in a Linux process. It does not start
a complete Android device or a system server.

## Main components

### Runtime

`artzig` builds ART, libcore, Conscrypt, and the boot class path from pinned AOSP
sources. The launcher starts ART in the application process.

### Android framework

The framework JAR uses AOSP source when its dependencies are available. A host
adapter replaces behavior that needs an Android daemon or system service.

Each adapter keeps the Android interface at its boundary. Framework callers do
not need Linux-specific branches.

### Graphics and windows

The application uses AOSP HWUI, Skia, and Minikin. The platform layer gives
HWUI an EGL surface on a Wayland `xdg_toplevel`.

Video can use a Wayland subsurface. The direct path can pass a DMA-BUF from
FFmpeg to the compositor when the graphics stack supports that path.

### Input

The Wayland layer converts pointer, keyboard, touch, tablet, and scroll events
to Android input events.

### Media

FFmpeg implements the hosted MediaCodec path. The decoder can use VA-API when a
suitable device is available. PipeWire implements audio output.

### Android native libraries

The launcher extracts `lib/x86_64` files from the APK. The Bionic compatibility
layer supplies the Android C-library interface and loads these files.

## Framework seams

The host does not implement every Android service. A framework seam has two
parts:

1. AOSP-compatible code owns Android state and callback order.
2. A small host adapter owns the Linux operation.

This rule keeps host details out of Android callers. It also makes each seam
testable without a full Android system.

See [Kotlin source policy](kotlin.md) and [Native language boundary](native-boundary.md)
for source ownership rules.
