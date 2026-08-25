# androlinzberg

androlinzberg runs an Android APK on a Linux desktop. It uses the artzig ART
runtime, the Android framework from AOSP, Skia, and Wayland.

The repository root is the application project. The embedded ART runtime and
its build tooling live in `artzig/`.

## How it is different from ATL

ATL (Android Translation Layer) and androlinzberg have the same goal. They use
different methods.

**The seam.** ATL replaces the Android graphics stack. It converts each Canvas
operation into a GTK/GSK operation, and it uses Pango for text. androlinzberg
keeps the Android graphics stack. It runs the real AOSP code below the Canvas
API: hwui, Skia, and minikin. Text layout, line breaking, and drawing give the
same result as on a phone.

**The framework.** ATL writes its own versions of many `android.*` classes.
androlinzberg compiles the AOSP framework Java without changes. It replaces only
the classes that need system services: `ViewRootImpl`, `WindowManager`,
`Activity`, `ContextImpl`, `Dialog`, and a small number of others. Binder
services run in the process, as in ATL.

**The window.** ATL shows the app in a GTK widget. androlinzberg talks to the
Wayland compositor directly. It uses an `xdg_toplevel` for the app, a
subsurface for video, and EGL for hardware rendering.

**The runtime.** ATL uses the ART build from nixpkgs, with wolfSSL for TLS.
androlinzberg uses ART built from AOSP main (the artzig project), with the real
conscrypt TLS stack. The launcher locates the host CA bundle and conscrypt
reads it directly; certificates are not copied into the ART runtime.

**Fonts.** ATL redirects `/system/fonts` file names to fontconfig. androlinzberg
asks fontconfig for the system fonts at start. It writes an Android
`font_fallback.xml`, and minikin loads it. Emoji and non-Latin scripts work.

**Media.** androlinzberg implements `MediaCodec` on FFmpeg, with VA-API and
DMA-BUF when the compositor supports them. It implements `AudioTrack` on
PipeWire.

**The build.** One command, `zig build`, builds all parts: the launcher, a
static Skia, the native island (hwui and JNI), the framework dex, the
resources, and the AIDL code. ATL uses meson and the system Skia.

## Run

```
zig build
zig build run -- path/to/app.apk
```

Pass `--fullscreen` to request xdg-shell fullscreen at startup, or press F11
to toggle it while the app is running. Android fullscreen/immersive requests
from hosted APKs are forwarded to the same Wayland protocol state.

Use `zig build run -- --help` to show the options and the environment
variables.

For a headless test, use `tools/shot.sh out.png ./zig-out/bin/androlinzberg
path/to/app.apk`. It runs the app in a headless compositor and makes a
screenshot.
