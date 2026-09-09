# Architecture

androlinzberg runs Android applications in a shared desktop session. The launcher
supervises a Binder broker, SurfaceFlinger, a hosted system process and a Wayland
task presenter. ATMS starts separate ART application processes. This is a host
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
Environment, …) over natives in Zig; `src/framework/android/os/` holds no
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

FFmpeg implements the hosted MediaCodec path. The decoder can use VA-API when a
suitable device is available. PipeWire implements audio output.

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
`ServiceManager` reaches it. The JVM side is the real AOSP `Parcel`, `Binder`,
`BinderProxy` and `BinderInternal` over Zig natives in `libbinder_ndk`
(`src/binder/ndk.zig`); `Parcel` keeps Binder objects and descriptors in a
side table that the broker translates. Java `IBinder`, generated AIDL, callbacks, calling
identity, death notification, and Unix descriptor passing work across
processes. The system process runs the AOSP `SystemServiceManager`/`LocalServices`
lifecycle and hosts the activity manager (`dev.artzig.system.HostedActivityManagerService`,
following AOSP `ActiveServices`): `Context.bindService`/`startService` work
across processes with AOSP connection semantics (`onServiceConnected` on the
main looper, `onServiceDisconnected`/`onBindingDied` on host death), Bundles
stay lazily parcelled until first access, and `androlinzberg --app-process
<apk>` runs a headless service host the activity manager can spawn. The system
process also hosts the session-wide package manager
(`dev.artzig.system.HostedPackageManagerService`, registered as `package` on
the real `android.content.pm.IPackageManager` surface): APKs are parsed only
in the system process by the AOSP parsing stack (`PackageParser2` ->
`ParsedPackage`, signatures verified through `ApkSignatureVerifier`),
components resolve through the real `ComponentResolver`/`IntentResolver`, and
every `PackageInfo`/`ApplicationInfo`/component Info comes from AOSP
`PackageInfoUtils` over the per-package `HostedPackageState`. framework-res.apk
is installed as package `android` at boot — its manifest is the session's
platform-permission source — and install-time grants follow AOSP protection
levels (normal granted outright, signature granted on a `SigningDetails`
match; framework-res.apk and the test fixture APKs are signed by the
`tools/signing` azk-platform test key). A package binds to its broker uid when
its first process attaches, so package info, intent resolution through real
`IntentFilter` matching, uid↔package mapping, and cross-uid permission checks
agree across the session; any method the hosted service does not implement
fails loudly (`PackageManagerServiceBase`). The client is the unchanged AOSP
`PackageManager`/`ApplicationPackageManager` pair, and the real
`PropertyInvalidatedCache` runs over the session property area (the service
bumps the `cache_key.system_server.*` nonces on every mutation, so
client-side caches invalidate across processes exactly as on Android). Dynamically registered broadcasts route through the activity
manager's broadcast queue (`HostedBroadcastQueue`, following AOSP
`BroadcastQueueImpl` semantics): unordered broadcasts fan out to every
matching receiver in the session, ordered broadcasts chain result state
through receivers by descending filter priority, sticky broadcasts are
retained, and delivery lands on the registering process's chosen `Handler`.
The clipboard is the real AOSP `ClipboardService` running in the system
process (with the unchanged `ClipboardManager` client). Its host monitor is
deliberately inert, so clipboard state is shared within the Android session
but is not read from or written to the Wayland clipboard. The notification,
alarm and job-scheduler managers are the unchanged AOSP clients over hosted
services on the real AIDL surfaces: `HostedNotificationManagerService`
(`INotificationManager`: per-package channel and group tables, the posted
notification table, toasts, and the AOSP "nothing configured" answers for zen,
listeners and bubbles; there is no shade — a posted notification is a log line
and the testkit's `notifications` observation), `HostedAlarmManagerService`
(`IAlarmManager`: RTC and elapsed alarms on one scheduled executor, delivery
through the PendingIntent or the `IAlarmListener` protocol, alarm clocks, exact
alarms always permitted) and `HostedJobSchedulerService` (`IJobScheduler`:
per-uid job tables with the AOSP run-time window, work queues and backoff,
constraints reduced to what the host knows, execution by binding the app's
`JobService` from the system context and driving `IJobService`/`IJobCallback`
exactly as `JobServiceContext` does). Foreground-service notifications flow
from the activity manager into the same notification table. The device-state
managers are real as well: `PowerManager` over `HostedPowerManagerService`
(`IPowerManager`: wake-lock accounting with death cleanup and a dumpsys-style
dump, always interactive, no power save or idle mode) and `HostedThermalService`
(`IThermalService`: status NONE, no temperatures), the `Vibrator` family over
`HostedVibratorManagerService` (`IVibratorManagerService`: no vibrators, requests
validated then dropped as AOSP does without hardware), `BatteryManager` over
`HostedBatteryService` (the stock `BatteryManager`, `BatteryStatsManager` and
`BatteryStats` model over generated `IBatteryPropertiesRegistrar` and
`IBatteryStats` transports; the service reads `/sys/class/power_supply` and
sends the sticky `ACTION_BATTERY_CHANGED` broadcast on the real queue, with
"not present, 100%, AC" reported without a battery), `UsageStatsManager`
over `HostedUsageStatsService` (`IUsageStatsManager`: foreground spans from the
activity manager's process attach/death, app standby disabled),
`StorageStatsManager` over `HostedStorageStatsService` (`IStorageStatsManager`:
APK and per-package data directory sizes on the host filesystem) and
`DeviceIdleManager`/`PowerExemptionManager` over `HostedDeviceIdleController`
(`IDeviceIdleController`: nobody is idle, allowlists empty). The HAL types these
clients re-export (`android.hardware.{health,vibrator,thermal}`) are generated
from the pinned `hardware/interfaces` stable AIDL and the dependency-free
health/vibrator HIDL declarations. The hosted services still report no thermal
sensors or vibrators; importing the transport types does not add hardware.
Media sessions are real: `MediaSession`, `MediaController` and `MediaSessionManager` over
`HostedMediaSessionService` (`ISessionManager`: session records with the AOSP
`ISession`/`ISessionController` binders, controller callback fan-out, the
media-button session stack and media-key dispatch, the MEDIA_CONTENT_CONTROL
gate on reading other packages' sessions) and `MediaMetricsManager` over
`HostedMediaMetricsService` (`IMediaMetricsManager`: session ids, metrics as
log lines). `AudioManager`, the audio value types and `MediaRouter2` remain
pending stubs: their closure runs through `AudioSystem`, `AudioRecord` and
`AudioPortEventHandler` natives and the audio-policy AIDL of `frameworks/av`
and `hardware/interfaces`, so they need an AudioSystem host seam and those AIDL
roots first (docs/stubs.md records the chain). Location, sensors, lights and
color display are real too: `LocationManager` and the GNSS value types over
`HostedLocationManagerService` (`ILocationManager`: only the passive provider,
location off, null locations, listeners kept and never delivered, the
MOCK_LOCATION gate), the real abstract `SensorManager` under the hosted
`SystemSensorManager` seam (`src/framework`, libsensor JNI replaced by a device
with no sensors), `SystemLightsManager` over `HostedLightsService`
(`ILightsManager`: no lights, CONTROL_DEVICE_LIGHTS enforced),
`ColorDisplayManager` over `HostedColorDisplayService` (`IColorDisplayManager`:
no transforms, night display off) and the input-device lights, sensor and
vibrator managers over the hosted input service. Selected Connectivity client
and value classes compile from the imported `packages/modules/Connectivity`
framework trees, including stock `NetworkUtils`. Its socket-to-network native
validates Android network handles and live sockets, then uses the host's single
physical route; there is no netd routing namespace to switch. `Network` carries
the exact stock handle and bind contracts until its remaining proxy closure is
ready. `ConnectivityManager` remains a stub because there is no hosted
`IConnectivityManager`/`ConnectivityService`. The Wi-Fi projects are synced too, but their
managers remain stubs because the host has no `IWifiManager`, Wi-Fi P2P, or
supplicant-backed service and their stock closure crosses the same connectivity
native boundary. Telephony and telecom are real: the
`frameworks/base/telephony/java`, `telephony/common`, `telecomm/java` and
`mms/java` trees compile verbatim (only the MMS PDU persister, which needs the
`android.drm` JNI client, and the satellite HAL service base, which needs the
radio HAL AIDL, are left out), so `TelephonyManager`, `SubscriptionManager`,
`CarrierConfigManager`, `TelephonyRegistryManager`/`TelephonyCallback`,
`SmsManager`, `EuiccManager`, `ImsManager`, `MmsManager` and `TelecomManager`
are the AOSP classes, registered through `TelephonyFrameworkInitializer` with a
`TelephonyServiceManager` as `ActivityThread` does. They talk to six hosted
services that answer as a device without a radio: `HostedTelephonyService`
(`ITelephony`: SIM absent, phone type none, network type unknown, data
disconnected, radio unavailable, no carrier privileges, no emergency numbers;
device identifiers keep the privileged-caller gate), `HostedSubscriptionService`
(`ISub`: no subscriptions, INVALID ids, the READ_PHONE_STATE checks),
`HostedPhoneSubInfoService` (`IPhoneSubInfo`: null identities behind the AOSP
permission gates), `HostedCarrierConfigLoader` (`ICarrierConfigLoader`:
`CarrierConfigManager.getDefaultConfig()` for every subscription),
`HostedTelephonyRegistry` (`ITelephonyRegistry`: TelephonyRegistry's per-phone
state at its constructor values with the same initial `notifyNow` callbacks,
listener permission checks per event, `notify*` gated by MODIFY_PHONE_STATE)
and `HostedTelecomService` (`ITelecomService`: a real phone-account registry
with `PhoneAccountRegistrar`'s validation, no dialer, no calls; placing a call
keeps the CALL_PHONE gate and logs that no account can carry it). The phone
count is `TelephonyManager.getActiveModemCount()`, as in AOSP. Surviving
stubs around this family: the radio HAL enums (`android.hardware.radio.*`),
libphonenumber (`external/libphonenumber` is not vendored, so parsing reports
"not a number" and the formatting helpers pass input through), the telecom
aconfig flags, `RoleManager`, and the Connectivity types telephony parcels
(`LinkAddress`, `LinkProperties`, `NetworkCapabilities`, `NetworkStats`). The policy
long tail is real where its closure allowed: `KeyguardManager` over
`HostedTrustManagerService` (`ITrustManager`: never locked or secure, listeners told
so on registration) and `HostedLockSettingsService` (`ILockSettings`: credential
type none, a per-user key/value store behind the AOSP write gate, empty credential
verifies), `SearchManager` and `SearchView` over `HostedSearchManagerService`
(`ISearchManager`: searchable metadata from an activity's meta-data, no global
search), `ShortcutManager` over `HostedShortcutService` (`IShortcutService`:
ShortcutService's per-package table with the dynamic/pinned/disabled transitions,
limits and incoming checks, no launcher to pin), `LauncherApps` over
`HostedLauncherAppsService` (`ILauncherApps`: launcher activities from the hosted
package manager's MAIN/LAUNCHER query, launches through the system context, the
shortcut surface behind `ensureShortcutPermission`), `DropBoxManager` over
`HostedDropBoxManagerService` (`IDropBoxManagerService`: timestamped entries,
READ_LOGS on reads), `OverlayManager` over `HostedOverlayManagerService`
(`IOverlayManager`: no overlays; fabricated overlays fail without idmap2),
`DomainVerificationManager` over `HostedDomainVerificationService`
(`IDomainVerificationManager`: no verified domains, name-not-found for unknown
packages), `WallpaperManager` over `HostedWallpaperManagerService`
(`IWallpaperManager`: wallpaper unsupported, the framework default drawable),
`CredentialManager` over `HostedCredentialManagerService` (`ICredentialManager`:
no providers, so requests end with the AOSP no-credential errors), the autofill
service classes, `UiModeManager` over `HostedUiModeManagerService`
(`IUiModeManager`: type NORMAL, night mode in `Settings.Secure` with the
`config_defaultNightMode` default, per-package night modes; changes reach app
processes as configuration deltas through the hosted activity manager's
`ConfigurationChangeItem` transactions, which the hosted `ActivityThread` now
applies), and `AppWidgetManager`/`AppWidgetHost` over `HostedAppWidgetService`
(`IAppWidgetService`: host ids and options, no providers, no pinning).
`UserManager` stays a stub: its `UserManagerCache` is emitted by the
CachedProperty annotation processor, which needs guava (not vendored).
`DevicePolicyManager`, `StatusBarManager`, `StorageManager`, `IncidentManager` and
`VirtualDeviceManager` stay stubs behind the biometrics/keymint HAL, vold/installd
AIDL and camera/audio chains docs/stubs.md records. `docs/service-registry.md`
(from `tools/registry-compare.py`) lists every service name in either registry
with what the hosted registry provides for it; `docs/stubs.md` (from
`tools/aosp-closure.py audit`) lists every remaining stub with its family or
survivor reason, and `tools/stub-audit.txt` ratchets the pending count. System properties
are session-wide, following Android's model: the broker owns a shared property
area file in the session directory (`src/binder/prop_area.zig`, the
`__system_property_area__` analogue) that every process — the JVM
`SystemProperties` and NDK code through the bionic shim alike — maps read-only
and reads lock-free with the bionic torn-read serial protocol;
`SystemProperties.set` is a broker message checked
against a property-service-shaped policy (only the system process may set
non-`debug.*`/`log.*` names, `ro.*` is write-once), and the broker pushes a
`property_changed` notification that drives `addChangeCallback`.
Content providers work across processes: the real `ContentResolver` acquires
providers by authority through the hosted `ActivityThread` and the activity
manager (`getContentProvider`/`publishContentProviders`, following AOSP
`getContentProviderImpl`; one connection per authority for the process
lifetime), the transport is the verbatim `ContentProviderNative`/
`IContentProvider` pair with real `AttributionSource` identity, permission
gates follow `ContentProvider.enforceReadPermissionInner`, and `query()`
results ride real `CursorWindow` shared memory through the unchanged AOSP
BulkCursor machinery (the `android::Parcel` members CursorWindow needs are a
JNI bridge onto the hosted Java Parcel). Persistable URI grants are served by
`HostedUriGrantsService` on the real `IUriGrantsManager` AIDL. Settings are real: the verbatim AOSP
`android.provider.Settings` client talks to a hosted `SettingsProvider` in the
system process (authority `settings`, write-through XML in the session
directory, `WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS` enforced), and
`ContentObserver` registration routes through a session-wide observer registry
(`HostedContentService`, published as `content` on the real
`IContentService` AIDL, following AOSP `ContentService.ObserverNode`
semantics; the sync surface is unsupported).
The identity/policy services speak their real AOSP AIDL surfaces:
`HostedUserManagerService` implements `android.os.IUserManager` (single
always-on user 0) as `Context.USER_SERVICE`, `HostedAppOpsService` implements
`com.android.internal.app.IAppOpsService` (per-(op, uid) mode table defaulting
to allowed, mutable only by the system) as `"appops"`, and the package manager
carries a runtime grant/revoke overlay on the install-time permission grants —
so reused `com.android.server` code linking against those interfaces works
unchanged.
`ContextImpl.getSystemService` resolves through the hosted
`SystemServiceRegistry` (the AOSP static API and fetcher kinds over the
managers this host backs), and the per-process display and input services
are Binder objects (`IDisplayManager`/`IInputManager`) registered under
process-local names, so the unchanged AOSP manager clients work. See
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
