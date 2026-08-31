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

### Hosted Binder

Local `Binder.transact` calls `onTransact` directly, as on Android. Every
launch is a session: the launcher starts one `azk-binder-broker` process and
one headless system process (`androlinzberg --system-server`, uid 1000), and
`ServiceManager` names live in the broker. Every ART process launched within
that session (or added with `--join <session-dir>`) connects to the broker
over a private Unix socket and joins one object graph; each package gets its
own Android uid for the session, and only the system process may register
system service names. The broker
assigns global object ids, translates object references between peers, routes
synchronous and oneway transactions, delivers nested callbacks to the waiting
thread, tracks remote references and death links, forwards file descriptors,
and holds the service registry. `HostedBinderRuntime` and `BinderProxy` are the
JVM side; `Parcel` keeps Binder objects and descriptors in a side table that
the broker translates. Java `IBinder`, generated AIDL, callbacks, calling
identity, death notification, and Unix descriptor passing work across
processes. The system process runs the AOSP `SystemServiceManager`/`LocalServices`
lifecycle and hosts the activity manager (`dev.artzig.system.HostedActivityManagerService`,
following AOSP `ActiveServices`): `Context.bindService`/`startService` work
across processes with AOSP connection semantics (`onServiceConnected` on the
main looper, `onServiceDisconnected`/`onBindingDied` on host death), Bundles
stay lazily parcelled until first access, and `androlinzberg --app-process
<apk>` runs a headless service host the activity manager can spawn. The system
process also hosts the session-wide package manager
(`dev.artzig.system.HostedPackageManagerService`, registered as `package`):
each process registers the package it parsed, and `PackageManager` is a client
that answers every query from the service, so package info, intent-service
resolution, uid↔package mapping, and cross-uid permission checks agree across
the session. Dynamically registered broadcasts route through the activity
manager's broadcast queue (`HostedBroadcastQueue`, following AOSP
`BroadcastQueueImpl` semantics): unordered broadcasts fan out to every
matching receiver in the session, ordered broadcasts chain result state
through receivers by descending filter priority, sticky broadcasts are
retained, and delivery lands on the registering process's chosen `Handler`.
The clipboard is the real AOSP `ClipboardService` running in the system
process (with the unchanged `ClipboardManager` client), and its emulator
clipboard monitor is a hosted replacement that syncs with the Wayland
clipboard through `wl-copy`/`wl-paste`. System properties are session-wide,
following Android's model: the broker owns a shared property area file in the
session directory (`src/binder/prop_area.zig`, the `__system_property_area__`
analogue) that every process — the JVM `SystemProperties` and NDK code through
the bionic shim alike — maps read-only and reads lock-free with the bionic
torn-read serial protocol; `SystemProperties.set` is a broker message checked
against a property-service-shaped policy (only the system process may set
non-`debug.*`/`log.*` names, `ro.*` is write-once), and the broker pushes a
`property_changed` notification that drives `addChangeCallback`.
Content providers work across processes: `ContentResolver` acquires
providers by authority through the activity manager
(`getContentProvider`/`publishContentProviders`, following AOSP
`getContentProviderImpl`), the transport is the verbatim AOSP
`android.content.IContentProvider` interface over a hosted stub, permission
gates follow `ContentProvider.enforceReadPermissionInner`, and `query()`
results ride real `CursorWindow` shared memory through the unchanged AOSP
BulkCursor machinery (the `android::Parcel` members CursorWindow needs are a
JNI bridge onto the hosted Java Parcel). Settings are real: the verbatim AOSP
`android.provider.Settings` client talks to a hosted `SettingsProvider` in the
system process (authority `settings`, write-through XML in the session
directory, `WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS` enforced), and
`ContentObserver` registration routes through a session-wide observer registry
(`HostedContentService`, published as `content`, following AOSP
`ContentService.ObserverNode` semantics).
The identity/policy services speak their real AOSP AIDL surfaces:
`HostedUserManagerService` implements `android.os.IUserManager` (single
always-on user 0) as `Context.USER_SERVICE`, `HostedAppOpsService` implements
`com.android.internal.app.IAppOpsService` (per-(op, uid) mode table defaulting
to allowed, mutable only by the system) as `"appops"`, and the package manager
carries a runtime grant/revoke overlay on the install-time permission grants —
so reused `com.android.server` code linking against those interfaces works
unchanged.
`ContextImpl.getSystemService` resolves through a
`SystemServiceRegistry`-shaped table, and the per-process display and input
services are Binder objects (`IDisplayManager`/`IInputManager`) registered
under process-local names, so the unchanged AOSP manager clients work. See
`src/binder/` for the Zig broker and protocol and `src/java/dev/artzig/system/`
for the system process and its services.

## Framework seams

The host does not implement every Android service. A framework seam has two
parts:

1. AOSP-compatible code owns Android state and callback order.
2. A small host adapter owns the Linux operation.

This rule keeps host details out of Android callers. It also makes each seam
testable without a full Android system.

See [Kotlin source policy](kotlin.md) and [Native language boundary](native-boundary.md)
for source ownership rules.
