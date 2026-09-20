# Architecture

androlinzberg runs Android applications in a shared desktop session. The launcher
supervises a Binder broker, SurfaceFlinger, a hosted system process, a native
media service and a Wayland task presenter. ATMS starts separate ART application processes. This is a host
adaptation of selected Android services, without booting an Android device.

## Main components

### Runtime

`artzig` builds ART, libcore, Conscrypt, and the boot class path from pinned AOSP
sources. The launcher starts ART in the application process.

### Android framework

The framework JAR uses AOSP source when its dependencies are available. A host
adapter replaces behavior that needs an Android daemon or system service.

Each adapter keeps the Android interface at its boundary. Framework callers do
not need Linux-specific branches.

`android.os` is the real AOSP package (Parcel, Binder, ServiceManager, Looper,
MessageQueue, Process, SystemProperties, SystemClock, Build, StrictMode,
Environment, …) over the native runtime; `src/framework/android/os/` holds no
replacements. The application context core is real as well: `Context`,
`ContextWrapper`, `ContextImpl`, `Application`, `Service`, `ContentResolver`,
`ContentProvider`, `LoadedApk`, `ResourcesManager`, `SharedPreferencesImpl`,
`Uri`, `Intent`, and `BroadcastReceiver` are the verbatim AOSP classes, so
receivers, service connections and provider connections are the AOSP
`LoadedApk` dispatchers talking to the session's activity manager over the
real `IActivityManager`/`IApplicationThread` AIDL. Activity, ActivityThread,
Instrumentation and the client-transaction lifecycle
use the pinned AOSP implementation. Stock ActivityTaskManagerService and
WindowManagerService own task/window state, lifecycle scheduling and resource
configuration; host adapters supply process startup, package lookup, display,
input and desktop policy collaborators.

A process is bound to its package the way `handleBindApplication` does it:
the package's `LoadedApk` builds the class loader (`ApplicationLoaders` →
`ClassLoaderFactory` → the hosted APK loader, over `sourceDir` and the
`nativeLibraryDir` the installing process extracted `lib/x86_64` into), the
`Application` is created through the package's `AppComponentFactory`,
providers are installed and published, then `Application.onCreate` runs.
Every package has the AOSP data layout (`Environment.getDataUserCePackage
Directory`, created at install), and each Activity gets its own
`ContextImpl.createActivityContext` base context. `dev.artzig.HostContexts`
is the host bootstrap that replaces the zygote/SystemServer handshake: system
assets, the "android" ApplicationInfo and the font map. The session display
registry supplies display metrics; each task receives its own configuration and
synthetic bounds without becoming another Android display.

The launcher owns the session's Android data root and exports it to every
process it starts: `ARTZIG_DATA_DIR` (default `$XDG_DATA_HOME/artzig`, else
`~/.local/share/artzig`) is `Environment.getDataDirectory()`, with
`DOWNLOAD_CACHE` and `EXTERNAL_STORAGE` (the one emulated volume) beneath it.
`ANDROID_DATA` remains ART's dalvik-cache root (an XDG cache directory) and is
not the data directory; vendored patch 0019 gives `Environment` that
precedence. `Build` values are session properties the broker seeds
(`ro.build.*`, `ro.product.*`); `ro.boot.qemu` is never set.

### Graphics and windows

APK presentation uses stock ViewRootImpl, SurfaceView, HWUI, BLAST and
BufferQueue. The host GBM allocator/mapper supplies DMA-BUF storage to libui;
HWUI draws through the stock ANativeWindow queue. SurfaceFlinger composes the
Android layers. A task organizer owns each task leash and a private SF output;
the Zig presenter imports its buffers into one Wayland xdg_toplevel per task.
Activities in the same task share that toplevel. Task-local dialogs, popups,
SurfaceView and video remain Android layers.

The primary compositor output supplies the session's Android display. Independent
task bounds occupy synthetic coordinates; compositor geometry, fractional scale
and output changes flow through task configurations and synchronized surface
transactions. GPU completion, queue release and compositor presentation remain
distinct events. Retired generations cannot supply input or replace new frames.

The mapper caps each process at 1,024 registered handles and 2 GiB of charged
storage, with a 256 MiB per-buffer ceiling. Imports consume their own charges;
freeing returns capacity after GBM destruction. These estimates bound mapper
retention rather than total physical VRAM. Task imports, Binder traffic and
Wayland transfer streams have separate limits.

### Input

Wayland pointer, keyboard, touch, tablet and scroll events enter the stock native
InputDispatcher and InputChannel path. The task shell translates synthetic
coordinates and owns focus/capture generations; WMS supplies Android input-window
metadata. App input reaches stock ViewRootImpl input stages. IME, clipboard,
drag-and-drop and accessibility cross host adapters with task/window identity.

### Media

FFmpeg implements the hosted MediaCodec path inside each app process. The
decoder can use VA-API when a suitable device is available. Audio uses the
stock AudioTrack/AudioSystem clients and `libaudioclient` over Binder to the
system process's `media.audio_flinger` and `media.audio_policy` services, which
render each track through a PipeWire stream (`src/native/audio`); PCM travels
through the AOSP shared control block, not through Binder. MediaExtractor uses
original native clients over Binder to the system process's `media.extractor`
service. These boundaries are detailed in the [native client audit](native-client-audit.md).

`azk-media-service` publishes the original AOSP media resource manager and
resource observer. Their native clients use the system process's activity,
process-information and battery services. The launcher starts this process
after those dependencies and stops it before them; its death retires the
desktop session. The Codec2 component store and stock MediaCodec activation
remain separate migration work.

### Android native libraries

The launcher extracts `lib/x86_64` files from the APK. The Bionic compatibility
layer supplies the Android C-library interface and loads these files.

### Hosted Binder

AZKB protocol v5 admits remote calls against per-recipient and session-wide
byte, descriptor and call-count allowances. One-way calls receive an immediate
admission reply; exhausted transaction admission returns `FAILED_TRANSACTION`
without waiting for the handler or retiring a healthy service. The receiver
returns `delivery_done` after handler and parcel cleanup, including cleanup
after a synchronous reply. Property, service-registration, object-release and
object-death callbacks also consume delivery credit. Reference callbacks use the
reserved allowance and return it only after destructor or death-handler cleanup;
a release acknowledgement alone cannot recycle that credit. An unresponsive notification recipient is retired
explicitly; notifications are not coalesced. Half the allowance remains available
for synchronous work when asynchronous admission is full.

The default recipient allowance is 32 MiB, 4,096 deliveries and 1,024 descriptor
charges; its asynchronous half is 16 MiB, 2,048 deliveries and 512 charges.
Session-wide delivery allowances are eight times these values. Accounting charges
wire descriptors and every decoded descriptor entry independently, plus payload
and conservative entry metadata. All outgoing requests, including service lookups, are capped at 128
through reply consumption. Each runtime retains at most 8,192 death links,
including links awaiting their final unlink callback. Each socket outbox is capped at 64 MiB, 8,192 frames
and 2,048 descriptors. The broker-wide outbox limit is 256 MiB, 65,536 frames
and 8,192 descriptors, charged through the actual write and descriptor close;
work deliveries can use half, preserving room for replies and reference control.
A session admits at most 64 peers and retains at most 65,536 Binder objects
(16,384 per owner), 1,024 service names (256 per owner), 8,192 waiting lookups
(256 per peer), and 8,192 service callback registrations across 1,024 names.
Registry and waiter exhaustion return explicit errors; exhausted object owners
or socket recipients are retired. Failed death-link registration preserves the NDK unlink-callback contract, and
request admission precedes publication of a new service export.

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
and holds the service registry; it also serves the stable `IServiceManager`
AIDL at the reserved context object, which is how the real AOSP
`ServiceManager` reaches it. That interface, like the task presenter's, is
generated from its AIDL by the repository's Zig backend rather than decoded by
hand (see [development](development.md#aidl-compiler-and-the-zig-backend)). The original Binder, Parcel, ServiceManager and
ServiceManagerNative JNI units run over the hosted C++ libbinder facade and
Zig `libbinder_ndk` runtime. Original JNI owns Java Binder holders/proxies and
C++ Parcel objects; the facade shares AZKB object and descriptor storage with
NDK callers. Java, generated AIDL, native C++ and NDK callers share identity,
callbacks, death notification and descriptor passing across processes. This
restores the JNI boundary while libbinder and AZKB remain host adaptations;
see [native boundaries](native-boundary.md).

System properties are session-wide. The broker owns a shared property area
that Java and native clients map read-only. Reads use the bionic serial protocol;
writes go through broker authorization, and change notifications trigger client
callbacks. Read-only properties are write-once.

### Hosted services

The system process uses AOSP's `SystemServiceManager` and `LocalServices`
lifecycle. [`SystemServices.kt`](../src/java/dev/artzig/system/SystemServices.kt)
defines the services it publishes and their startup order;
[`StockWindowServices.kt`](../src/java/dev/artzig/system/StockWindowServices.kt)
boots the window/task collaborators. App `Context.getSystemService` uses the
original AOSP `SystemServiceRegistry` and module initializers. Client-class
availability does not imply that every device service or operation is supported.

The hosted activity manager supplies process attachment, service binding,
broadcasts and provider connections over original AIDL. Stock ATMS/WMS own
activity and window lifecycle. The hosted package manager uses AOSP parsing,
signature verification, component resolution and package-info construction.
It publishes package identities and permissions shared by Java and native
callers; property-cache invalidation propagates mutations across the session.

`framework-res.apk` is installed as package `android`. Its manifest supplies
platform permissions, and signature grants use AOSP signing identities.
The public [fixture signing keys](../tools/signing/README.md) also sign the
test APKs used to exercise platform and untrusted-caller permission paths.

Content providers use stock transports, attribution checks and CursorWindow /
BulkCursor machinery over the shared native Binder facade. The hosted Settings
provider stores settings and enforces write permissions; the content service
routes observer notifications. Stock URI-grant and AppOps services integrate
with hosted package, process and permission authorities.

Stock ClipboardService shares Android clipboard state through the system
process. `HostedClipboardMonitor` connects it to the task presenter for
Wayland text, HTML and supported URI transfers. Desktop input, IME, drag and
clipboard limits are described in [desktop behavior](desktop.md).

Other services cover notifications, alarms, jobs, users, storage, networking,
media policy and device capabilities. Their implementations live in
[`src/java/dev/artzig/system/`](../src/java/dev/artzig/system/); some reuse AOSP
services and others implement hosted policy on stock interfaces. Missing
hardware and unsupported operations retain explicit capability or failure
results. Audio tracks are served by the system process's native AudioFlinger
and policy adapters; codec execution still runs in app processes, as explained
in the [native client audit](native-client-audit.md).

## Framework seams

The host does not implement every Android service. A framework seam has two
parts:

1. AOSP-compatible code owns Android state and callback order.
2. A small host adapter owns the Linux operation.

This rule keeps host details out of Android callers. It also makes each seam
testable without a full Android system.

See [Kotlin source policy](kotlin.md) and [Native language boundary](native-boundary.md)
for source ownership rules.
