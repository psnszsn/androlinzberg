# Post-Wayland stock-client completion

Status: implementation in progress; verified checkpoints are recorded below.
Baseline: `3339fdf8`, inspected on 2026-09-09 after fetching `praz/main`.
This continues [stock-client-migration.md](stock-client-migration.md) after the
completed [Wayland task-window cutover](wayland-task-windows.md).

## Implementation checkpoints

### Package layout and baseline (2026-09-09)

Both local archives were synchronized and then rebuilt from the clean isolated
AOSP checkout. A normal local fetch exposed a release-directory prefix that
did not match `dependency.path()` consumers. The packager now emits source
directories at the archive root and normalizes permissions independently of
umask; a build-tools regression covers layout, executable bits, links and
byte-identical repeat output.

The framework archive is now SHA-256
`14acc4fefd37fd41fc45a4cd6ec96eaff5544d122cb7efd35f25a5b9e7e6be34` and
reproduces the existing framework package pin. ART is SHA-256
`461570fe26613cacbb290ae462997e98b70079e4960fa7a1a4534fb9da13f522`, pinned to
`N-V-__8AAJ5Pbgk8FWG4jwLV-PcsGpnyDsw9oKy82vZa1JQO`. No extracted cache was
manually flattened. Both packaging runs restored the checkout.

`zig build -j10 java build-tools-test` passes, including the new archive test.
The exact Kotlin compiler response files identify 7,046 unchanged static AOSP
sources and 71 modified static sources: 40 server files and 31 client files
(the previous 30 frameworks/base entries plus WifiSsid). There are seven owned
client source files, with the ten runtime annotation files compiled separately.
Build enforcement and the rest of the implementation batches remain open.

### Bulk client cleanup (2026-09-09)

Restored TypedArray, Display, Toast, ViewGroup, SyncFence and WindowOrganizer to
verbatim AOSP sources. Imported the original WallpaperService and ten original
runtime annotations. Moved configuration/resource fixtures, window presentation,
logging and root inspection helpers into `dev.artzig`. The source audit now
checks declared packages and rejects repository-owned client namespace classes
outside the explicit stub inventory; generated compile-only SDK headers are
excluded from this runtime rule.

There are now 25 modified static client sources, down from 31. The stub inventory
contains five entries: four pending server classes and the ToneGenerator client
survivor. MessageQueue hooks, remaining client patches, NDK graphics and the other
completion batches remain open. Wallpaper binding and Engine API behavior are
tested; actual live-wallpaper attachment/presentation is still open.

The framework archive was rebuilt and pinned to
`N-V-__8AADQL7k6S_ZSpevB_mw5KFsOSzisPOYMs8xgxiJiP`, SHA-256
`95fc9736edcc0800bd5fca1ad66f53a038e6ded689bd5908b6964db42e8b9bd8`.
ART remains at the package-layout checkpoint above. Both local archives are
current for this checkpoint.

Validation passed with `-j10`: `java build-tools-test cpp-audit graph-audit`,
`jvm-test`, focused NewPipe launch, and the complete `integration-test system-test
webview-test webview-ui-test stock-desktop-launcher-test` gate. The new smoke
checks stock unresolved TypedArray exceptions and original WallpaperService
binding/Engine APIs. All 28 fresh APK cases passed with 88 captured steps and
zero structured runtime issues. WebView and desktop launcher captured another
17 records with zero issues. Their rendered screenshots and the NewPipe
fullscreen frame were inspected. Historical failure artifacts from earlier
runs were excluded by timestamp, not deleted.

### Stock image clients and NDK reader (2026-09-09)

ImageReader, ImageWriter and HardwareRenderer now match the pinned sources
byte-for-byte. ImageWriter uses the already-selected original SurfaceUtils;
its native registration now names that original class. Stock media library
initialization works through the installed `media_jni` module. The duplicate
HostSurfaceInfo helper and two empty native NDK headers were deleted.

The original NdkImageReader/NdkImage implementation replaces the unsupported
image-reader exports over the existing BufferQueue and gralloc implementation.
All 29 functions declared by the pinned public image headers are exported;
the library also exports the deprecated native-handle entry point. Only that
retired HIDL token-manager operation has a host branch, returning the same
`AMEDIA_ERROR_UNKNOWN` that AOSP defines when the token service is absent.
No new graphics, audio or media backend was added.

The new NDK regression checks CPU/GPU pixels, real sync-file acquisition and
release, plane strides, metadata, maxImages exhaustion/recovery, latest-frame
replacement, callbacks and retained buffer/window references after reader
teardown. Expectations follow the pinned implementation: replacement callbacks
may coalesce, and AImage derives RGBA dataspace as UNKNOWN from PublicFormat.
The Java image transfer and ownership tests also pass.

The framework archive is now SHA-256
`02b7a96bde8676ac09316532bdf818f074f624ac45cba97edbdb851cb9145df3`, pinned to
`N-V-__8AAHIY8E4WGFfbsVVJIk3R5gU9MMCvlM97IeutjV9c`. Packaging restored the
isolated checkout; ART is unchanged. The modified static client count is 22.
The stub count remains five. NDK SurfaceControl, WebView overlay wiring and
TunnelModeEnabledListener cleanup are still open within batch 2.

Validation passed with `-j10`: source/build/native audits, `image-queue-jvm-test`,
`jvm-test`, full `integration-test`, `system-test`, `webview-test`,
`webview-ui-test`, `stock-desktop-launcher-test`, `graphics-budget-test`,
`surface-buffer-jvm-test`, `surface-fence-test` and
`surfaceflinger-composition-test`. The first combined command failed only the
Vulkan fixtures because the driver manifest was unset; those targets passed
when rerun with `VK_DRIVER_FILES` selecting Mesa's Radeon ICD. All 28 fresh APK
cases contain 88 steps with zero structured issues. WebView and desktop records
have zero issues, and their screenshots plus fullscreen NewPipe were inspected.

### Stock NDK SurfaceControl (2026-09-09)

The original NDK SurfaceControl and display-LUT implementations replace the
unsupported exports. All 52 functions in the three pinned public headers are
exported. Native tests create children from both Java SurfaceControl and BLAST
native-window parents, submit real GPU buffers and fences, observe commit before
completion and valid transaction statistics, and verify pixels on two independent
task outputs. Borrowed Java transactions, hiding, reparenting and teardown retain
the original ownership contracts.

HWUI now compiles its original SurfaceControl fields, root handoff and callbacks
on the host, and WebViewFunctorManager.cpp is verbatim AOSP. This is not yet proof
of Chromium overlay promotion: the host GL drawable's intermediate framebuffer
still counts as a layer under the original overlay eligibility rule. That root
framebuffer boundary remains open. A new, uncommitted canvas-video investigation
also finds correct playing pixels but black paused frames with Chromium
133.0.6943.137, even with overlays disabled. The provider's pinned
[pause-copy implementation](https://chromium.googlesource.com/chromium/src/+/refs/tags/133.0.6943.137/third_party/blink/renderer/modules/mediastream/web_media_player_ms_compositor.cc)
has explicit black-frame fallbacks; the exact failing branch is still being
investigated. Neither finding is counted as completed video/overlay coverage.

The framework archive is SHA-256
`53a08fd25dad0a2472966893d27598d6fdcb45cdcf5db09bc8f04e8c605d8304`, pinned to
`N-V-__8AAGAX8E6EYk87dZH0AKnglvpO47fCtonki-O-CE6N`. ART is unchanged. No new
Wayland, PipeWire or FFmpeg backend was added. Static client differences remain
22, with five tracked stubs.

Validation passed with `-j10`: Java/build/native audits, task-output and
surface-buffer smokes, JVM and system suites, full APK integration, headless
WebView, desktop launcher, graphics budgets, surface fences and SurfaceFlinger
composition. The existing WebView UI/input test passed before the new video
probe was introduced. All 28 fresh APK cases passed with 88 steps and zero
structured issues; desktop modal and NewPipe fullscreen screenshots were
inspected. The new paused-canvas-video probe is not part of this verified commit.

### Bulk permission, logging and value clients (2026-09-09)

ActivityManager, AppOpsManager, LocalePicker, ProtoLog and WifiSsid now match
their pinned originals. Their dependencies were already selected: the original
permission service, memory reader, backup client, Health Connect client,
PermissionUsageHelper and WifiManager. The generated tracing flags already
select ProtoLog's logcat backend. No replacement service or Java compatibility
method was needed. Removed HostAppOpsOptionalServices and
HostedComponentPermissions; the hosted AMS access check calls the original
ActivityManager operation directly.

The new AppOps regression starts a real camera-use record through the service
and observes its active and recent usage through the original privacy-indicator
client. The resource/client smoke also checks the original memory reader against
Linux total-memory accounting. The exact static compiler source set includes all
five originals. Modified static client sources decreased from 22 to 17; the five
stub entries are unchanged.

The rebuilt framework archive is SHA-256
`c694f1cc2a67030318d806591b3234a82348f4c93c6f21cda24e49b57c5070b8`, pinned to
`N-V-__8AAAUX8E5c351YyLSXtHzb2HxcFqLtBc0jtSDkzNJd`. ART is unchanged and both
local archives match their current pins.

Validation passed with `-j10`: Java/build/native audits, framework-module, full
JVM, system, APK integration, headless WebView and desktop launcher suites.
All 28 fresh APK cases contain 88 steps with zero structured issues; the desktop
launcher contains 16 steps with zero issues. The new AppOps privacy test passes.
The accompanying capture-stream pause probe failed with both canvas and I420
input. The follow-up below distinguishes the canvas black-frame result from
I420 color-metadata loss; these are separate failures.

### Stock device policy and storage (2026-09-09)

DevicePolicyManager, DevicePolicyEventLogger, PrivateDnsConnectivityChecker,
StorageManager and VolumeInfo now match the pinned originals. Their closure
includes ProvisioningIntentHelper, StringParceledListSlice, ObbScanner and
FuseAppLoop. The device-policy and keychain Binder declarations also use their
original parcelable imports. The build reproduces devicepolicyprotosnano's
Soong runtime relocation and supplies its generated package root to Kotlin.

The original OBB scanner, quota ioctl JNI and libappfuse client are compiled and
registered. Invalid OBB files fail in the original scanner before Binder;
missing files and corrupt files retain their distinct stock exception causes.
Valid requests are queued and receive ERROR_COULD_NOT_MOUNT, unknown unmounts
receive ERROR_NOT_MOUNTED, and path/header mismatches are rejected. An absent
AppFuse bridge returns null from the service, letting the original client throw
IOException before creating a callback/descriptor. Actual mounts still need
vold; no new mount daemon or Wayland/PipeWire/FFmpeg backend was added.

The x86_64 native tagging probes follow the pinned Zygote implementation. New
system regressions verify NFC provisioning-intent parsing without NFC hardware,
MTE capability reporting, relocated protobuf serialization, string-list Binder
continuation, original OBB metadata parsing, asynchronous mount/unmount failures,
and path ownership validation. The exact compiler source list now contains
7,071 unchanged originals, 12 modified static client files and 40 modified server
files. The five stub entries are unchanged; boot-library differences and the
remaining runtime/graphics/service-instance work remain open.

The framework archive is SHA-256
`dcba799398842145298720584019343650683410354d8a666d80770759d56280`, pinned to
`N-V-__8AADal8U6clqLFcIsf41wCXKsS9fZYHFILWksaz7Ne`. Packaging restored the
isolated checkout. ART is unchanged and both local archives are current.

Validation passed: Java and system-service tests with `-j24`, then build-tool,
native/source/graph audits, full JVM and APK integration, WebView and desktop
launcher tests with `-j10`. All 28 fresh APK cases contain 89 steps with zero
structured runtime issues; the desktop launcher has 16 steps with zero issues.
Desktop modal and NewPipe fullscreen screenshots were inspected. Capture-stream pause failures were still open at this checkpoint; the follow-up
below adds ordinary encoded playback coverage and retains those reproductions.

### WebView playback regression and capture diagnosis (2026-09-09)

The HTML fixture is now an APK asset, so JavaScript/video iterations do not
recompile the framework. Ordinary playback uses an embedded, locally generated
silent VP8 WebM. The headless regression requires video-frame callbacks and
visible green content, then compares every green baseline pixel after pause
with a two-code-value tolerance. It also retains the original heading and
Android input checks. The encoded video passes this comparison.

The capture probes remain available through `AZK_WEBVIEW_VIDEO_SOURCE=i420` or
`canvas`; both deliberately remain failing reproductions. I420 retains visible
green pixels but loses its BT.709/sRGB ColorSpace on pause, changing all 57,600
video pixels. This corrects the earlier classification of its failure as a
black frame. A readback probe and a second run without readback confirmed the
visible retained frame; `new VideoFrame(video).colorSpace` changes to all-null.
The pinned Chromium CopyFrame code copies frame metadata without ColorSpace.
Canvas capture changes from RGBA to a black I420 frame, also changing all 57,600
pixels. This is consistent with a CopyFrame fallback; selecting its exact
fallback branch still requires provider instrumentation. Neither capture failure
is described as fixed, and successful encoded playback is separate coverage.

Reproductions retained under `zig-out/test-results/webview-capture-i420/` and
`webview-capture-canvas/` contain the playing/paused images, three issue-free
steps, failure record and before/after format metadata. Fixture provenance and
commands are in [its README](../testdata/fixture-apks/dev.artzig.webviewtest/README.md).
WebView root-framebuffer/overlay eligibility remains open. This checkpoint
changes no runtime backend or AOSP package; the archive pins remain current.

Validation passed with `-j10`: `testkit-test build-tools-test graph-audit
webview-test webview-ui-test`. The encoded UI test has three steps with zero
structured issues, and the playing/paused screenshots were inspected. Both
capture-mode runs intentionally fail the same pixel-preservation assertion,
with three issue-free steps each; they are retained as counterexamples.

### Native WebView window-root contract (2026-09-09)

The host GL drawable now distinguishes the actual window device from a saved
layer even though both use nonzero FBOs. Origin conversion retains full window
coordinates, previously drawn content and transparent openings. The original
HWUI functor can therefore enable SurfaceControl underlays on window roots and
disable them on offscreen layers. HardwareBuffer snapshots remain offscreen.

A native fixture uses the original functor callbacks and NDK transactions on
two independent task outputs (80×40 and 64×48). It checks translated/clipped
coordinates, background and foreground pixels, an underlay visible through a
transparent opening, root/layer/root transitions, the original prepare callback
and destruction without retained buffers. Real WebView encoded playback and
pause also pass. This establishes the HWUI contract; actual Chromium overlay
promotion and the previously recorded capture-stream failures remain open.

The framework archive is SHA-256
`8e33f59148b902ae5808cfef11122bc14a2f167c1f63a355251e0dbb6f5fc5f6`, pinned to
`N-V-__8AAG6q8U64rXRsjwHzc7-94mEVWrKnPxp2T38mpbLg`. The isolated checkout was
restored by packaging. ART is unchanged. No Java client source or stub inventory
changed in this checkpoint; the remaining modified static client count is 12.

Validation passed: Surface queues and task-output tests, native/source/graph
audits, full JVM and APK integration, WebView smoke/UI, desktop launcher,
graphics budgets, surface fences and SurfaceFlinger composition. All 28 fresh
APK cases contain 88 steps with zero structured issues; WebView has three and
the desktop launcher 16 issue-free steps. WebView pause, desktop modal and
NewPipe fullscreen screenshots were inspected. The isolated task-output fixture
still logs the stock renderer's caught scheduler warning because it deliberately
starts without AMS; the end-to-end APK tests have no such missing service.

### Stock tunnel listener and native lifetime (2026-09-09)

TunnelModeEnabledListener.java now matches pinned AOSP. JNI keeps opaque handles
with serialized native ownership and uses the original NativeAllocationRegistry
for GC cleanup. Failed unregister/destroy calls preserve stock retry errors;
they no longer consume the reachable Java object's ownership. When that object
becomes unreachable, native cleanup still runs even if its Java finalizer throws.
Concurrent destruction cannot dereference a freed pointer or leave a racing
registration attached.

Regressions observe actual native listener counts in ordinary and authorized
processes. They cover repeated permission failures, initial/executor callbacks,
concurrent register/unregister/destroy, destruction inside a callback, and eight
allocation/cleanup cycles. A separate live-compositor test registers a listener,
terminates SurfaceFlinger, verifies repeated stock destroy errors and requires
GC to return native ownership to baseline. All pass.

The wider run exposed a standalone WebView test shutdown race after its PASS
message: session teardown stopped AMS while Chromium processed renderer death.
The smoke now explicitly terminates its renderer and awaits the original
onRenderProcessGone callback before returning. It passes both alone and alongside
the JVM suite. This completes the smoke's renderer lifecycle; it does not change
general session shutdown ordering or claim isolated-service completion.

The exact static compiler inputs contain 7,072 unchanged sources, 11 modified
client files and 40 modified server files, with no missing reference. The five
stub entries and boot-library differences remain open. The rebuilt framework
archive is SHA-256
`9f2d11e2715e51500bfe053d4b92363401105f373c0101ad62699b898cf734d7`, pinned to
`N-V-__8AABS_8U69Z0saeql8wELwgF-OqAWyXM_uF2P0e0Ar`. Packaging restored the isolated
checkout; ART is unchanged and both local archives are current.

Validation: Java and native listener/task-output tests, source/native/graph
audits, full JVM, all 28 APK integration cases (88 steps, zero issues), WebView
UI (three issue-free steps), and desktop launcher (16 issue-free steps). The
standalone WebView failure described above was followed by passing focused
surface/task-output/WebView tests and the full JVM/WebView/audit rerun. Desktop
modal and NewPipe fullscreen screenshots were inspected. No new desktop, audio
or media backend was added.

### Stock MessageQueue and Looper dispatch (2026-09-09)

Removed the MessageQueue patch and all five host pump methods. Bootstrap,
desktop supervision and test callers now enter the pinned Looper.loopOnce
through a bounded host adapter. Stock Looper owns native polling, barriers,
observer/tracing callbacks, work-source attribution, failure propagation and
recycling. The adapter restores caller identity and loop state when returning
to the host. Deadline messages are asynchronous; a clock check also prevents
front-of-queue traffic from starving the native owner's deadline.

The system server now sets its broker identity and process name before preparing
its main Looper, matching the identity ordering established by Zygote on Android.
The original queue chooses its implementation. Native/input/task-output tests
exercise the concurrent queue under the system identity and the legacy queue in
an ordinary process. No framework flag or queue algorithm is replaced.

New regressions cover delayed delivery, cross-thread enqueueing, barriers,
front-of-queue deadline fairness, observer completion/failure callbacks,
work-source restoration, propagation of the original exception, recovery after
failure, idle transitions and quit. The first framework test exposed an incorrect
test setup: Handler takes attribution from the sending thread at enqueue time.
The corrected test follows that original contract. Input FD callbacks and both
task outputs pass through the new dispatch path.

The exact static inputs now contain 7,073 unchanged AOSP sources, ten modified
client files and 40 modified server files, with no missing reference. The stub
inventory remains five. The framework archive is SHA-256
`b119640dd7a26b3dbdc7a25af3f590a9f56f9fbb44d0231f941d8ece549907c5`, pinned to
`N-V-__8AAMep8U7RpfcAi3NxJe0jqbCqoadxWJuAukDIRSyd`. Packaging restored the isolated
checkout; ART is unchanged. Both local archives are current.

Validation passed: Java, framework-module, input-channel, surface-buffer and
task-output tests, then source/native/graph audits, full JVM and system suites,
full APK integration, WebView smoke/UI, desktop launcher and session-loss gates.
All 28 fresh APK cases contain 88 steps with zero structured issues. WebView's
three and the desktop launcher's 16 steps also have zero issues. WebView pause,
desktop modal and NewPipe fullscreen screenshots were inspected. Runtime/path,
ICU, ToneGenerator, service-instance and package-snapshot work remains open.

### Pinned ICU runtime and stock locale/time clients (2026-09-09)

The old runtime used ICU 68 Java and native prebuilts despite the Android 16
source pin. ART now builds the original ICU 76.1 Java, JNI, common and
internationalization libraries and both stable native shims. It stages their
matching Unicode 16 data and the pinned 2025a time-zone data, including format 9
XML/ICU overlays. The boot image and framework compile classes use that same
module. ICU flags are generated from the pinned declarations and Android 16
release values; compile-only annotations stay outside the runtime jar. The
existing AOSP prebuilt runner is shared with ART. JNI uses C++23 for the host
compiler's C/C++ atomic-header compatibility; no ICU implementation is patched.

InputDevice, InputMethodSubtype and AlarmManager now match their pinned originals.
The new smoke verifies Java/native ICU, Unicode, CLDR and time-zone agreement;
Unicode 16 characters; formatting, collation, native charset and regex behavior;
DST offsets, country/network lookup data, locale canonicalization and the stock
alarm client's validation-before-service-call behavior. Native export and
relocation checks verify ICU 76 symbols and distribution-relative library lookup.

The exact static inputs contain 7,076 unchanged AOSP sources, seven modified
client files and 40 modified server files, with no missing reference. Five stubs
remain. Runtime/path/loading clients, boot-library differences, ToneGenerator,
service isolation, package snapshots and the recorded graphics/provider follow-ups
remain open; this checkpoint does not claim completion of the whole plan.

The framework archive is SHA-256
`253a6f43d0db28dcf816f6dcbad8f38bea340392f0bfc1fde58b55593617cfd8`, pinned to
`N-V-__8AAA2p8U4mxQho7_sN9opa7t--QtNp88mswkbagr7t`. ART is SHA-256
`32f2f7a98477bd4d1322e7aeb2e3c151b80c3d0675efd5afd48ebe41a341363a`, pinned to
`N-V-__8AAH6hSw3qAgNa2tjSaX2unmkZoVNojaTAz9ZVNUZf`. Both packaging runs restored
the isolated checkout. Both local archives and the installed runtime are current.

Validation passed: focused framework-module tests with `-j24`, then `-j10 install
build-tools-test cpp-audit graph-audit jvm-test system-test integration-test
webview-test webview-ui-test stock-desktop-launcher-test stock-session-loss-test`.
All 28 fresh APK cases contain 88 steps with zero structured runtime issues;
WebView's three and the desktop launcher's 16 steps also contain zero issues.
All five session-loss scenarios passed. WebView pause, desktop modal and NewPipe
fullscreen screenshots were inspected.

### Stock trust store and runtime cleanup (2026-09-09)

Conscrypt's Platform and java.lang.Runtime now match their pinned originals.
Native ART startup parses the configured host PEM bundle through pinned
BoringSSL and writes real DER certificates into a private Android system CA
directory before creating the VM. Subject-hash collisions retain distinct
numbered files; malformed or empty bundles fail startup and remove the private
overlay. Removed the patched Conscrypt bundle parser and the framework's
HostedSystemCertificates bootstrap. Standalone ART gets the same stock trust
store by default, without framework initialization.

The new standalone regression exposed an ICU unload defect: C++ destructors
were registered with a null DSO handle and ran after the library was unmapped.
ICU shared libraries now link the host compiler's matching CRT objects, giving
each library its own destructor identity and dlclose finalizer. The regression
destroys the VM, verifies overlay cleanup and exits successfully.

Tests independently decode certificate bytes, exercise collisions and partial
installation failures, load the original AndroidCAStore, and compare stock
Conscrypt and framework trust anchors against the host PEM bundle. Existing
application-anchor and pinning checks remain covered. The full `-j10 install
system-certificates-test build-tools-test cpp-audit graph-audit jvm-test
system-test integration-test webview-test webview-ui-test
stock-desktop-launcher-test stock-session-loss-test` gate passed. All 28 APK
cases contain 88 steps with zero structured issues; WebView and desktop frames
were inspected alongside NewPipe fullscreen playback.

ART is SHA-256
`179620281b0a8e286eff770b4332b3bc943cccb8d3d97625215e8662cc9f6923`, pinned to
`N-V-__8AACSaSw07iGpqry7sLHdyDqVk-GAVw4jHKzFMhkxm`. Packaging restored the
isolated checkout. The framework archive remains at the ICU checkpoint above;
both local archives and the installed runtime are current. Seven modified
static client files and five stubs remain; java.io.File's virtual-library
adaptation is still open with the runtime/path batch.

### Stock ToneGenerator and existing audio output (2026-09-09)

The final client stub, ToneGenerator, is deleted. Its Java source is verbatim
AOSP; the original native descriptors, oscillator, regional tone selection and
sequencer now run through the existing AAudio/stock AudioTrack PCM output.
The native adapter replaces the absent AudioFlinger track, preserves voice-call
to DTMF routing and the original volume conversion, and waits for callback-stop
completion before accepting a subsequent start. Closing the output joins its
callback worker before freeing the synthesizer. No audio backend was added.

AAudio now exposes original session allocation/reuse semantics, including the
default no-session result and rejection of invalid session IDs. Tests observe
generated output samples and submitted/played frame counters: DTMF frequency
content, finite duration, the proprietary double-beep cadence, logarithmic volume
and silence, stream/session identity, tone replacement, rapid restarts, explicit
stop, active-callback release and stock calls-after-release exceptions all pass.
The full framework-module smoke passes with the original tone client.

The exact source inventory contains 7,077 unchanged AOSP files, seven modified
static client files and 40 modified server files, with no missing reference.
The regenerated audit has four pending server stubs and zero client survivors;
the pending budget remains four. Runtime/path/loading differences, service
isolation, package snapshots and recorded graphics/provider follow-ups remain
open.

The framework archive is SHA-256
`a8278fdc73964ec38f6a9587be1283df9873448c4560c647c20c9c79b6c3dcf8`, pinned to
`N-V-__8AAK7p804ZPwp9Y9bnqjR2dDpN_I4WxFrJSrBBaxSt`. Packaging restored the
isolated checkout. ART remains at the trust-store checkpoint above. Both local
archives and the installed runtime are current.

Validation passed: focused framework-module tests with `-j24`; full JVM, system,
APK, WebView, desktop launcher, session-loss and standalone certificate tests
with `-j10`. The first combined gate failed only because ToneOutput.cpp was
missing from the explicit C++ boundary declaration; `install cpp-audit
build-tools-test graph-audit` passed after adding that declaration. All 28 APK
cases contain 89 steps with zero structured issues; WebView's three and the
desktop launcher's 16 steps also contain zero issues. All five session-loss
cases passed. WebView pause, desktop modal and NewPipe fullscreen frames were
inspected.

### Stock resource paths (2026-09-10)

SystemFonts, AssetManager and SQLiteGlobal now match pinned AOSP byte-for-byte.
Their environment-based Java path changes are removed. A shared native resolver
maps the existing configured font files, font directory, framework APK and exact
`/data` partition probe below the original Java file/resource clients. Libcore's
Linux, java.io and NIO pathname boundaries and androidfw's archive opener use the
same resolver as the bionic file adapter. Unchanged paths remain borrowed; mapped
buffers have explicit release and allocation-failure handling. ApkAssets retains
its original Android asset name while opening the real hosted archive.

This resolves named platform resources, not a general virtual filesystem or the
remaining Environment/runtime partition layout. Symlink targets remain original
stored text. The boot File library-existence workaround is still open.

The exact compiler source response contains 7,080 unchanged AOSP files, four
modified client files (ApplicationLoaders, Environment, Process and
ClassLoaderFactory), 40 modified server files and no missing references. The stub
inventory remains zero client stubs and four pending server stubs.

Both local archives were rebuilt from the isolated checkout, which packaging
restored. Framework SHA-256 is
`6f0819795e007bce2d518ead281bb115560d422398523c95620343ae2e12d784`, pinned to
`N-V-__8AAPTm806Q3otq_0qaOAtzGS8xYbCZ-hqVkWLhbcGq`. ART SHA-256 is
`337b8031458cb01f56b78ab3995fcc26ab7e456ce380225e8891615959291b93`, pinned to
`N-V-__8AADqdSw2GL19rcy7pDTYdgUr9zhkaIgGA-fqaYaFn`.

`framework-module-test` passed with `-j24`, including original path constants,
file bytes and metadata through java.io/Os/NIO, APK identity and freshness, XML
parsing, actual font loading and SQLite's partition probe. The complete `-j10`
gate passed: `install system-certificates-test build-tools-test cpp-audit
graph-audit jvm-test system-test integration-test webview-test webview-ui-test
stock-desktop-launcher-test stock-session-loss-test`. All 28 fresh APK cases
contain 88 steps with zero structured issues; WebView's three and the desktop
launcher's 16 steps also contain zero issues. All five session-loss cases passed.
WebView pause, desktop modal and NewPipe fullscreen frames were inspected.

## Objective and boundary

Restore the selected application-facing framework sources to the pinned
`android-16.0.0_r1` implementation, remove repository-owned Android namespace
substitutes and connect the remaining client APIs to the existing hosted
services and native infrastructure. Keep host behavior in `dev.artzig`, Binder
services, JNI/runtime boundaries and the existing graphics/audio/media adapters.

Use the newly landed WMS/ATMS, SurfaceFlinger, BufferQueue, input and Wayland
implementation. Do not rebuild the compositor or add PipeWire/FFmpeg backends.
Connecting an Android client to these existing facilities is in scope. If an
operation needs a new desktop protocol or backend capability, record that
dependency explicitly; do not call the affected functionality complete.

Stock clients and complete Android device capabilities are separate outcomes.
Absent hardware may retain AOSP-defined capability failures. Android software
IME installation, PiP, unrestricted taskless desktop windows, additional drag
formats and HDR conversion are not prerequisites for source identity. Preserve
and accurately document those desktop limits rather than advertising features
merely to satisfy a test.

Hosted servers may remain host-owned. The four package-manager stubs still need
an explicit resolution, but importing every Android system-server daemon is not
the definition of a stock client.

## Verified starting point

- Activity, ActivityThread, Instrumentation, Dialog, ViewRootImpl, SurfaceView,
  TextureView, SurfaceControl, SurfaceSession, SurfaceTexture and InlineContentView
  match the pinned checkout byte-for-byte in the current pinned package.
- The audit lists six stubs: two client survivors, ToneGenerator and
  WallpaperService, and four pending server entries, Computer, PackageArchiver,
  PackageManagerService and UserManagerService.
- Thirty non-server frameworks/base Java files differ from the checkout in the
  pinned package. That count includes documentation/whitespace changes and does
  not count native patches, other AOSP projects, annotations or host additions.
- NDK image-reader and surface-control failure implementations remain registered
  from `src/binder/framework_jni.zig`.
- Hosted AMS receives but does not use `bindServiceInstance.instanceName`.
- Retained final APK evidence has 28 cases, 88 steps, no structured runtime
  issues and no failure files. This inspection did not rerun the suite.
- This checkout's local Androlinzberg tarball is stale: SHA-256
  `fb5d0892c032f5d4f928f87b30be7a03b5990d76557d32d18b9339f14785515b`.
  The Wayland worktree has the expected archive, SHA-256
  `f6612bfa11e91583e5adc132535e73a13f98f3a7b7621a37471a4012c9ff6fec`.
  `build.zig.zon` already pins its package hash
  `N-V-__8AAPwM7k7C3lf_tsjnO7aGYqL37cE5nZCVKR6_5sa5`.

## Execution order

Use bulk closure analysis and shared packaging for related changes. Execute
independent source analyses together, but serialize source edits, packaging and
builds. Do not change build inputs during a running build. Each batch ends with
a verified commit describing observable behavior and validation, with its
manifest, generators, package pin and audit updates together.

| Batch | Work | Depends on |
| --- | --- | --- |
| 0 | Correct local packages; establish exhaustive source/API inventory | Baseline |
| 1 | Remove obsolete compatibility patches and namespace leftovers | 0 |
| 2 | Restore graphics clients and NDK image/surface APIs | 0; coordinate namespace work with 1 |
| 3 | Restore service-backed and runtime client sources | 0; graphics portions of 2 |
| 4 | Import the final ToneGenerator and WallpaperService clients | 0; wallpaper needs 2 |
| 5 | Implement stock isolated/external service binding semantics | 0; coordinate runtime work with 3 |
| 6 | Resolve server stub dependencies without disguising replacements | 0; coordinate service work with 3 and 5 |
| 7 | Reproduce packaging, audit source identity and run final gates | 1–6 |

### Batch 0: reproducible baseline and complete inventory

1. Verify the correct archive in the Wayland worktree, then synchronize the local
   archive without changing the correct pin. Check the ART archive against its
   own current pin as well. A later clean-cache build must not depend on the
   other worktree or a previously fetched package hiding an old local archive.
2. Obtain a live framework classes jar from the current build graph. Record its
   source revision and exact source response files; do not select an arbitrary
   newest cache file from a different configuration.
3. Inventory the exact compiled Java/Kotlin sources and archive inputs against
   the clean pinned checkout. Include packaging patches, generated transformations,
   annotations, non-frameworks/base projects, native registrations and exported
   NDK symbols. Separate stock source differences from additional host classes.
4. Extend the existing source audit to prevent new unaudited Android/internal
   client replacements and track remaining differences downwards. Use the actual
   SourceSet; do not add a second build-consumed source manifest. Generated
   outputs must retain provenance to pinned inputs and their Soong transformation.
5. Refresh closure reports for both client stubs and the four server stubs in
   grouped runs. Record dependency blockers, not just source counts.

Acceptance: a reproducible current baseline and a finite inventory in which
every difference has an owning batch. The inventory includes the 30 known
files below and discovers any additional differences rather than assuming
that list is exhaustive.

### Batch 1: obsolete patches and Android namespace cleanup

- Restore direct WindowOrganizer apply-token calls. Remove Display's synthetic
  null-global accommodation and Toast's absent-service workaround after checking
  the current display/notification paths.
- Restore TypedArray's exact exception/default behavior. Exercise unresolved
  attributes explicitly; fix incorrect callers or resource resolution rather
  than suppressing the framework exception again.
- Remove the whitespace-only ViewGroup and documentation-only SyncFence changes.
- Remove unused HostedResources/HostedConfigurationController code or move
  retained host/test utilities to `dev.artzig`. Move HostedWindowPresentation
  to the server/presentation seam and update its callers.
- Remove HostedWindowLogging and HostedRootFrames from `android.view`. Replace
  inspection with stock accessibility/window interfaces where available. Where
  unavoidable for test-only observation, isolate reflective access in the test
  adapter without adding public framework methods or changing stock callers.
- Replace all ten handwritten annotation sources with the exact pinned original
  annotation inputs, preserving retention, targets and compiler/runtime placement.
- Inspect MessageQueue pump callers. Retire obsolete manual-pump paths and use
  stock Looper/MessageQueue dispatch and the existing native readiness boundary.
  Migrate meaningful ordering tests before removing `$artzig` methods.

Acceptance: no repository-owned Android/internal client source remains from
this group; no tests depend on removed hooks. Verify resource exceptions,
toast behavior, queue barriers/idle handlers, window inspection and stale-target
rejection, followed by the full APK suite.

### Batch 2: graphics clients and native API completion

- Compile pinned SurfaceUtils and its required Java closure; register its original
  JNI under the stock class. Restore ImageWriter's stock queries. Restore
  ImageReader/ImageWriter library initialization by making the existing loader
  resolve and initialize the required native library correctly.
- Replace `image_reader_capability.zig` exports with the original NdkImageReader/
  NdkImage implementation over existing BufferQueue, gralloc and fences. Check
  the full pinned public API/export surface, not only Chromium's imported names.
- Replace `surface_control_capability.zig` with the original NDK SurfaceControl
  implementation over the existing SurfaceComposerClient and SurfaceFlinger.
  Remove duplicate symbols and preserve Binder identity and handle ownership.
- Review HardwareRenderer's remaining source difference and restore it using
  the existing HardwareBuffer/native-window paths. Move TunnelModeEnabledListener
  disconnect cleanup below the Java caller while preserving the tested leak fix.
- Reassess the WebView patch disabling SurfaceFlinger overlays. Connect the
  existing provider to the new native APIs before enabling that path; require
  real callbacks, fences and composited pixels. Keep it explicitly incomplete
  if another concrete provider prerequisite is discovered.

Acceptance: NDK reader/writer round trips produce known pixels; exhaustion,
async acquisition, fence ownership, listener ordering and destruction match
AOSP. Native transactions create visible layers and deliver real completion
callbacks; cancellation, disconnect and producer death leak no handles or FDs.
Run existing graphics/fence/pressure, SurfaceView/TextureView, WebView and APK
tests. Do not treat successful symbol resolution as working rendering.

### Batch 3: service and runtime client restoration

Process each group as a shared source closure. Preserve unavailable-operation
failure behavior at a service/native boundary, not a branch in the Java client.

| Group | Known modified sources | Implementation direction |
| --- | --- | --- |
| Permissions and policy | ActivityManager, AppOpsManager, LocalePicker, DevicePolicyManager, DevicePolicyEventLogger, PrivateDnsConnectivityChecker | Restore stock permission, optional-module, usage and policy collaborators; provide their required Binder/native contracts; reproduce Soong relocations rather than editing imports. |
| Storage and resources | StorageManager, VolumeInfo, AssetManager, Environment, SQLiteGlobal, SystemFonts | Move hosted paths/library locations below callers; restore storage calls and dependencies, including OBB validation and descriptor contracts. Unsupported mount operations must follow the stock failure path. |
| Runtime and loading | Process, ClassLoaderFactory, ApplicationLoaders | Preserve APK/platform class identity through stock loaders and native loading seams; route process identity/start contracts through a supported hosted runtime/service protocol. Do not return fabricated UIDs or skip isolation policy. |
| ICU, time and logging | InputDevice, InputMethodSubtype, AlarmManager, ProtoLog | Supply pinned ICU/time-zone dependencies and configure stock logging through supported initialization, generation or flags. Do not force a different backend by modifying the Java client. |

Coordinate MessageQueue with batch 1 and image/HWUI callers with batch 2.
Trace remaining patches in other projects, such as Conscrypt and WifiSsid,
through the same process; the frameworks/base inventory is not the whole client.

Acceptance: each group matches pinned client source and exact public signatures.
Verify cross-process permission denial, privacy/AppOps callbacks, storage errors
and descriptor lifetimes, resources/fonts, APK child/platform parent class
identity, time-zone validation and stock queue/logging behavior. Preserve TLS
trust behavior when moving the host CA configuration. Run system, JVM and full
APK tests for every completed batch.

### Batch 4: final client stubs

ToneGenerator: import original Java/JNI and the pinned native synthesizer. Reuse
the existing audio output through the smallest necessary native interface;
preserve volume, cadence, stream/session identity, replacement and stop/release
behavior. Do not build a new PipeWire backend. Test actual generated samples,
timing/state transitions, invalid parameters and release during callbacks.

WallpaperService: its previous missing-window-session explanation is obsolete.
Import the original client and closure. Keep platform capability reporting honest:
the hosted wallpaper service currently disables wallpaper, and taskless desktop
windows are restricted. Test real Engine attach/resize/visibility/surface teardown
with an authorized stock WM wallpaper target where the existing stack supports
it. Desktop-wide live-wallpaper presentation is a separate policy/protocol
dependency if no such target exists; client import alone does not implement it.

Acceptance: both stubs are deleted, source provenance is stock and the audit
is regenerated. Tone playback is functional. Wallpaper client availability and
actual presentation coverage are reported separately and precisely.

### Batch 5: service instances and isolated/external processes

- Trace pinned ActiveServices, ProcessList and service-manager behavior for
  named instances, isolated/shared-isolated processes and external services.
  Preserve the original 64-bit bind flags rather than truncating them.
- Include instance identity in service records and process-start deduplication
  where AOSP requires it. Preserve binding counts, callbacks and restart semantics.
- Add broker-authorized isolated UID allocation and credentials, with ownership,
  caller identity, service-access restrictions and lifecycle-bound cleanup.
  Never accept a child-supplied UID or give isolated children the ordinary app
  credential. Validate allowed service access and permission/AppOps attribution.
- Match external-service validation: declaring service flags, exported status,
  calling-package ownership, effective package identity and defining APK paths.
- Coordinate launch, attachment, pending bindings, failure, death, restart and
  credential revocation across AMS, session supervision and Binder. Do not
  silently treat unsupported shared-isolation modes as ordinary services.

Acceptance: two named instances stay distinct; repeated binding to the same
instance follows AOSP reuse; isolated UIDs cannot impersonate app/system peers;
external services preserve caller identity and defining code. Test high-bit
flags, invalid combinations, concurrent launch, failed attach, crash/restart,
unbind and UID reuse. Run the real Chromium renderer alongside independently
signed service fixtures and the Binder/system/APK suites.

### Batch 6: package-manager server stub resolution

Recompute actual compiled references to Computer, PackageArchiver,
PackageManagerService and UserManagerService. For each dependency, prefer its
original AOSP type/helper. Keep hosted package/user authority behind explicit
server operations where the original requires absent Android infrastructure.

Computer must provide the stock query/snapshot semantics its consumers require;
do not equate a mutable service object with an immutable snapshot. PackageArchiver
must report real archive/install state and correct unarchive behavior. Preserve
user isolation, permission checks and package/UID queries for PMS/UMS consumers.

Removing a stub requires either compiling its original class or eliminating all
references through a justified server seam with equivalent observable contracts.
Moving the same partial replacement to another directory/package or marking it
a survivor is not completion. If a stock dependency remains necessary, import
and wire its closure instead of changing the completion criterion.

Acceptance: all four tracked stubs and their references/replacements are resolved;
no generated adapter silently defaults required operations. Test snapshot
isolation, archived versus installed packages, unsupported unarchive requests,
per-user package lookup and activity launch through stock ATMS.

### Batch 7: acceptance and documentation

1. Require zero remaining client stubs, zero unaudited client source differences
   and no repository-owned Android/internal client substitutes. Reused sources
   match pinned originals; generated variants match pinned Soong inputs. Native
   and hosted service adaptations remain explicitly documented at their boundary.
2. Resolve all four server inventory entries under batch 6's criteria. Report
   client identity, server inventory and capability limitations separately.
3. Regenerate the stub audit and migration status. Remove obsolete statements
   about absent SurfaceFlinger/BufferQueue from code and native-boundary docs.
4. Package sequentially, verify checksums and pins, and build using fresh task-local
   caches to prove the local archives work without the other worktree. Do not
   delete shared caches or modify another worktree to make the check pass.
5. Run the complete current Wayland final acceptance command recorded in
   `wayland-task-windows.md`, plus the new NDK, tone, wallpaper, namespace and
   isolation regressions. Pass an explicit Zig job limit appropriate to load
   and memory and no greater than `max(1, nproc - 2)`.
6. Require fresh artifacts for every APK case, expected UI state, empty structured
   runtime issues and inspected screenshots for rendering changes. Run service-loss,
   graphics/Binder resource-pressure and WebView gates without skipping GPU checks.
7. Commit the final verified state with source provenance, implementation summary,
   commands, results and remaining advertised desktop limitations. Do not claim
   full device parity or a completion percentage based on stub count.

Completion means every required batch has evidence, not merely a successful
compile or empty stub directory. Any new backend dependency remains a named
open item until implemented or explicitly removed from scope by the user.
