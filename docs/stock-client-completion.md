# Post-Wayland stock-client completion

Status: implementation in progress; verified checkpoints are recorded below.
Baseline: `3339fdf8`, inspected on 2026-09-09 after fetching `praz/main`.
This continues [stock-client-migration.md](stock-client-migration.md) after the
completed [Wayland task-window cutover](wayland-task-windows.md).

Current baseline: `6751dc2f` plus the provider checkpoint below (2026-09-10).
The live audit has zero stubs and
zero modified static client sources: 7,066 selected static AOSP sources match
their reference files, with 62 server adaptations recorded separately. Original
Process.start and Process.startWebView work through hosted exec services.
The complete fresh-cache acceptance run passes at `2a76eac9`; a final fresh run
with the native fixes and independently pinned WebView 140 remains to be done.
The provider upgrade resolves the diagnosed
[frame-rate hint defect](webview-frame-rate-hints.md) through upstream code.

## Implementation checkpoints

### Independently pinned WebView 140 (2026-09-10)

The runtime now installs the unchanged official AOSP WebView 140.0.7339.15 APK
from commit `27d78d3db63b2dbe56d1136b7cefc502ca50548d`. The framework reference
stays Android 16 r1. This provider guards zero frame intervals before converting
them to a frame-rate hint, resolving Chromium 133's intermittent invalid-rate
transaction failure without weakening original SurfaceComposerClient validation.

`aosp/package-webview.py` validates the exact APK size, SHA-256 and Git blob,
then creates a deterministic, atomic third local archive with provenance.
Two runs produced byte-identical archives and the original archived APK; invalid
input preserved the existing output. The archive SHA-256 is
`ff78ad3c60e851a5ff3d217d3a2d16074f453f1814a4c4bbd105b54996e9918d`, pinned to
`N-V-__8AAF1whxNdNKACWB1dSoSDGefcMMlNxw5Ttc9ZdbzL`. Framework and ART archives
remain current at the native checkpoint below. See [packaging](../aosp/README.md#webview-provider).

Upstream production flags remain unchanged. Chromium 140 defaults overlays off;
the overlay regression enables WebViewSurfaceControl through the original
developer UI, service and content-provider readback before initializing WebView.
The existing shot harness now supports temporary Android data/cache directories,
used by both WebView graphical tests. The setting cannot persist into user data.
Native overlay creation, buffer updates, completion acknowledgements and correct
playing/paused pixels remain required.

The two-phase `-j10` gate passed: `install webview-test webview-ui-test
webview-overlay-test stock-cold-launch-test`, then `integration-test jvm-test
system-test native-test egl-test cpp-audit graph-audit build-tools-test`.
This includes original WebView Zygote preload/isolated launch, renderer lifetime,
HTML/JavaScript/cookies/input and graphical media. All 28 APK cases produced 88
clean actions; the graphical WebView cases added 15 clean actions. Screenshots
were inspected. Evidence: `/tmp/stock-provider-140-gate-1.log` (exit 0) and
`/tmp/stock-provider-update/`. Final acceptance from a new worktree remains open.

### Native client queries and Android EGL buffer import (2026-09-10)

AAudioStream_getDeviceId now reports the actual Android AudioTrack route and
retains its last device ID after playback stops. The original bionic
android_get_device_api_level implementation is compiled directly over the
existing immutable system-property service; its API-level query is verified
before and after a rejected read-only property write.

The Android EGL adapter now supplies eglGetNativeClientBufferANDROID and imports
native buffers through both eglCreateImage and eglCreateImageKHR. Conversion
uses the original AHardwareBuffer helper, storage comes from the existing
DMA-BUF backend, and EGL image ownership retains the Android buffer. Native,
EGL14 and legacy callers see the supported extensions. The regression verifies
errors, both image APIs and GPU pixel readback after releasing the application
buffer reference. No Wayland, audio or codec backend was added.

Focused framework-module and EGL tests pass. The complete regression gate also
passes: focused NewPipe launch followed by all 28 APK cases (88 clean actions),
JVM, system, headless and graphical WebView, overlay callbacks/pixels, stock cold
launch, native/graphics-buffer tests and source/native audits. The graphical
WebView tests captured another eight clean actions. Static source identity
remains 7,066 unchanged clients/support sources, zero modified clients and 62
server adaptations. Both local archives are current: framework SHA-256
`186b5431047677671c3eaec0ba89390d9fe4cc450d5910a2dc7fcce227cad7a8`, pinned to
`N-V-__8AAEBS9U7lF2noWWVjw_xsjFjmP8muuObielJwA3_K`; ART remains SHA-256
`aa71ef019f0360bc53c5e795858e58b3ec216d8ccafa6d623d5c6c6e3b9a921c`.

The bundled provider remains 133. A separate 140.0.7339.15 probe passes headless
WebView and graphical H264 playing/paused pixel checks after these native fixes.
Its upstream feature configuration disables WebView overlays, so provider
adoption and validation of that optional path are still in progress. These
native changes do not themselves repair Chromium 133's frame-rate hint defect.

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

### Stock Environment and session data layout (2026-09-10)

Environment is now verbatim AOSP. The runtime creates its Android data layout
before Java initialization: application/user directories link to persistent
storage, system policy directories belong to the session, and dalvik-cache links
to the shared runtime cache. Standalone runtimes own and remove their private
layout; the session supervisor owns shared layouts. Teardown unlinks the layout
without deleting application bytes. No Java data-directory overrides remain.

Session metadata carries canonical application, external-storage, download-cache
and runtime-cache locations. Independently joined launchers adopt those locations,
including when their shell supplies none of the owner's Android storage variables.
The hosted storage services measure the persistent backing filesystem rather than
the session directory's tmpfs. Stock StorageManager resolves both the logical
partition and canonical persistent user paths to the default storage UUID.

The exact compiler source inventory contains 7,081 unchanged AOSP files, three
modified client files (ApplicationLoaders, Process and ClassLoaderFactory), 40
modified server files and no missing references. Boot File's library-existence
workaround, process/class loading, isolated-service semantics, server stubs and
the provider/final acceptance work remain open. The stub inventory is unchanged:
zero client stubs and four pending server entries.

The framework archive was rebuilt from the isolated checkout, which packaging
restored. Its SHA-256 is
`db3f130ad093d94397a51db06edd61535f855e5ed20b80ae7ec5925abcb5d284`, pinned to
`N-V-__8AADfl807g3MCsXpkPSES9KDdrSsa6hq-HWqg9yuNJ`. ART remains at the resource-path
checkpoint (`337b8031458cb01f56b78ab3995fcc26ab7e456ce380225e8891615959291b93`,
`N-V-__8AADqdSw2GL19rcy7pDTYdgUr9zhkaIgGA-fqaYaFn`). Both local archives match.

Native tests cover independent session policy, persistent bytes/cache, idempotent
join, rejection of a changed backing store, partial-construction cleanup, and
runtime startup failure/destruction. The existing desktop harness also launches
the resource smoke in a separate joined process with its storage environment
cleared. Its resource, metadata and storage-capacity assertions pass.

After focused tests, the final `-j10` gate passed: `install data-partition-test
system-certificates-test build-tools-test cpp-audit graph-audit jvm-test system-test
integration-test webview-test webview-ui-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test`. All 28 fresh APK cases
contain 88 steps with zero structured issues; WebView and desktop add 19 steps
with zero issues. All five session-loss cases passed, and the final WebView,
desktop modal and NewPipe fullscreen frames were inspected.

### Stock APK loaders and platform namespace (2026-09-10)

ApplicationLoaders and ClassLoaderFactory now match the pinned originals. Removed
HostedApkClassLoader and the package manager's injected loader name. The runtime
installs an empty stock PathClassLoader above a platform API projection during
ART bootstrap; application PathClassLoader and DelegateLastClassLoader instances
remain original AOSP classes. Private host dependencies stay in the implementation
loader. Native boot-first resolution preserves platform class identity even when
an APK contains its own copy, including optimized const-class resolution.

The compiler context contains unchanged platform API class bytes. ART records the
actual backing archive for context/checksum validation. Tests require the default
APK loader to map its speed-compiled OAT artifact, check parent-first and explicit
delegate-last behavior, shared libraries, private Kotlin version isolation, and
copied original android.net.Uri identity. The projection test compares every
selected class byte and checks excluded classes against the runtime boundary.

The exact static source inventory is 7,083 unchanged sources, one modified client
(Process), 40 modified server sources and no missing reference files. The boot
java.io.File patch remains separately open. The stub inventory remains four
pending server entries and zero client entries. Process launch/isolation, server
stub resolution and the remaining graphics acceptance work are still open.

The framework archive is SHA-256
`f7d12c5b81bc76272aa93ee6f61812072cc6ed29ab75cba7744895873f6afe2e`, pinned to
`N-V-__8AAO3h806jgTmcUEd0vT1G-mD1rLm4jdaWw20fYIxa`. ART is SHA-256
`2dc5df1ef4b4fa7a2c07182b3f986d7a1735a8ced5a17a7a8c6482c42c295564`, pinned to
`N-V-__8AAGy8Sw1BaCW8Ecm_hr2FqhGOFxT00zphHLFBXf4r`. Both archives were rebuilt
sequentially from the isolated checkout, which packaging restored.

Validation: focused `classloader-jvm-test` (-j24), NewPipe launch, 23 testkit unit
tests and the complete -j10 gate (`install data-partition-test
system-certificates-test build-tools-test cpp-audit graph-audit jvm-test
system-test integration-test webview-test webview-ui-test
stock-desktop-launcher-test stock-session-loss-test binder-test
binder-broker-test`). All 28 fresh APK cases, WebView and desktop tests produced
110 captured steps with zero structured runtime issues; the five session-loss
results and joined-launcher storage check passed. WebView's paused frame and the
desktop modal screenshot were inspected. Successful APK cases retain step and
log evidence, without failure screenshots.

A NewPipe control overlay auto-hide race initially rejected a stale Pause target.
The testkit now distinguishes an action rejection with complete clean diagnostics
from runtime/diagnostic failure. The test reacquires controls only for NoMatch or
InputTargetChanged, with bounded retries; rejected steps remain in the journal.
The regression and full suite passed without weakening stale-target validation
or ignoring runtime issues.

### Stock File and real native library paths (2026-09-10)

Removed the last java.io.File patch. The boot class now matches the pinned AOSP
source byte-for-byte. The old exists-only whitelist advertised libraries without
backing files, including an unavailable 32-bit ABI and OpenSLES. Native startup
now creates real library aliases in its owned system layout, targeting installed
compatibility libraries and the actual host math, zlib and GLES ELF files.
Dynamic-loader lookup identifies host files without guessing distribution paths
or exposing linker scripts. Runtime teardown removes the aliases with the owned
system layout, preserving their backing files.

The shared native file resolver and both ART native loaders resolve
/system/lib64 to that directory. The stock APK loader discovers it through
java.library.path. File, Os, NIO, absolute System.load and System.loadLibrary now
agree on the same files. Unavailable libraries retain ordinary missing-file and
UnsatisfiedLinkError behavior. No new graphics, audio or media backend was added.

The resource regression checks all 15 library aliases, directory enumeration,
ELF64 headers, byte digests, inode/size agreement, original PathClassLoader lookup
and actual loading. It also checks missing 32-bit, OpenSLES and unknown libraries
through File, NIO, file opening and native loading. The standalone ART certificate
and cleanup regression still passes with no configured platform library aliases.

ART was rebuilt from the isolated checkout and repinned to
`N-V-__8AAI64Sw04d93e1ikkJiC8r15azJMqL59bhVMVArLS`, archive SHA-256
`01eb26296581af49b0d951f939596b10a81da0fba8ff676ef3e14b1b9a8ec443`. Packaging
restored the checkout. The framework archive and pin remain at the stock-loader
checkpoint above; both local archives are current.

Validation passed: focused framework/resource tests (-j24), NewPipe launch,
and the complete -j10 gate (`install data-partition-test system-certificates-test
build-tools-test cpp-audit graph-audit jvm-test system-test integration-test
webview-test webview-ui-test stock-desktop-launcher-test stock-session-loss-test
binder-test binder-broker-test graphics-budget-test surface-fence-test
surfaceflinger-composition-test`). All 28 fresh APK cases and the WebView/desktop
tests contain 107 captured steps with zero structured runtime issues. All five
session-loss tests and joined-launcher storage passed. The WebView paused frame
and desktop modal screenshot were inspected.

Process remains the one modified static client source. The four server stubs,
isolated/external service implementation and remaining graphics acceptance are
still open; this checkpoint does not mark the completion plan finished.

### Chromium platform video and overlay acceptance (2026-09-10)

The pinned Chromium provider now completes its platform H.264 decoder and
SurfaceControl overlay path. A 720p source is displayed at the same 320×180 size
as the existing VP8 fixture: Chromium 133 prefers software decoding below 360
pixels high, so the smaller source did not exercise this path. The new probe
initially reached the existing VAAPI decoder and DMA-BUF export, then crashed
calling a null dynamically resolved AHardwareBuffer_describe pointer.

The bionic translation bridge now returns the real host process handle for
dlopen(NULL) and searches host platform exports for global lookups. Explicit
library lookup remains confined to that library's scope. A tracked patch is
applied to a generated compilation input; the pinned dependency remains unchanged.
The native regression checks callable export identity, repeated open/close,
global lookup, missing-symbol errors and explicit-library scope.

`webview-overlay-test` uses the original TracingController API and the existing
Cage/azk-test harness. It requires platform decoder frame creation, a WebView
overlay, buffer transactions and both HWUI and buffer-update completion callbacks,
as well as actual playing and retained paused pixels. The checker rejects the
earlier software-only trace. The final trace records 32 decoder frame creations;
the five UI actions contain no structured runtime issues. Trace and screenshots
are retained under `zig-out/test-results/webview-overlay/`.

Validation passed with -j24 for the focused loader/overlay tests and -j10 for the
complete current Wayland acceptance command, plus data-partition and standalone
certificate tests. All 28 APK cases passed; the fresh UI journals contain 322
actions with zero structured runtime issues and no failure artifacts. All five
session-loss cases passed. Overlay paused pixels and the desktop modal screenshot
were inspected. Both AOSP archive checksums still match the previous checkpoint;
this batch changes no AOSP package contents and adds no media/display backend.

This closes the remaining Chromium overlay prerequisite in batch 2. The separately
documented Chromium capture-stream pause bugs remain provider limitations, not
successful regression coverage. Process, isolated/external service semantics,
the four server stubs and final clean-cache acceptance remain open.

### Captured package state and shared user authority (2026-09-10)

Removed the Computer and UserManagerService stubs and all compiled references
to them. The original PackageManagerInternal snapshot entry now returns a
captured package registry and original ComponentResolverSnapshot. Original
PackageSetting supplies the user, transient, permission and keyset copy rules;
parsed-package copies retain AndroidPackageHidden behavior and the session data
paths assigned at installation. Old snapshots remain stable after installation,
UID/signing assignment, enabled/installed state and library-state changes.

Original resolver, badging and permission collaborators now use the existing
UserManagerInternal authority. Binder and local user services share the same
hosted user catalog and lifecycle. A non-default-user fixture verifies that
captured resolvers retain the injected authority instead of consulting a static
singleton. This does not add desktop multi-user provisioning or device policy.

The grouped original Computer/UMS closure reaches 959 sources, 64 AIDL inputs and
244 blockers in the absent full PMS/AMS graph. The explicit server operations
replace those dependencies; no live service is cast to an immutable snapshot.
The current exact source response file contains 7,069 unchanged static AOSP
sources, 54 modified server sources, one modified client (Process), and zero
missing originals. The live jar contains neither deleted class nor references
to either. The regenerated audit contains two pending server stubs,
PackageManagerService and PackageArchiver, zero survivors and zero client stubs.

The framework archive was rebuilt from the isolated checkout and pinned to
`N-V-__8AAG3w807iLdt4VmUnUVP8yQA3ODiycjIn3hiuHFzD`, SHA-256
`bcbdec179c94bf4932f4a916233091a5ca590dac7b662885dbdbe183c9c34673`.
Packaging restored the checkout. ART remains at the stock-File checkpoint;
both local archives are current.

Validation passed: focused framework-module tests and source/build audits
(-j24), then `install jvm-test system-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test build-tools-test cpp-audit
graph-audit` (-j10). All 28 fresh APK cases contain 94 actions; including WebView
and the desktop launcher, 118 captured actions have zero structured runtime
issues and no fresh failure artifacts. All five session-loss cases passed and
removed their owned session directories. The paused-overlay and desktop modal
screenshots were inspected. Process, isolated/external service semantics, the
two remaining server stubs and final fresh-cache acceptance remain open.

### Persistent package-settings diagnostics (2026-09-10)

Removed the PackageManagerService stub and all compiled references to it.
Eleven server consumers now use the exact pinned constant values or an explicit
settings-diagnostic operation. Original package-signature and legacy-permission
parsers retain their control flow. Errors now reach the Android log, generated
binary-event writer and append-only `system/uiderrors.txt` journal, including
AOSP's timestamp, 0774 permissions and IOException handling. The former stub
only wrote to the Android log. The host still has no logd reader; this checkpoint
does not claim binary-event readback coverage.

The new regression passes malformed XML through the original permission parser:
unknown elements and missing names are journaled, a valid sibling permission is
retained, repeated parsing appends without truncation, and file permissions match
AOSP. The live jar contains no PackageManagerService class or references. The
exact source inventory has 7,062 unchanged AOSP sources, 61 modified server
sources, one modified client (Process) and no missing originals. PackageArchiver
is the only pending stub; there are zero survivors and zero client stubs.

The framework archive was rebuilt and pinned to
`N-V-__8AAL_z805naCjy9okMtRAN5gl8XFF2KOAh2ddcm7IP`, SHA-256
`b939d82c992320c20e885d7d4556f0dc9c034e3e23ea7a23b433cf3a84cf7be6`.
Packaging restored the isolated checkout. ART is unchanged and both local
archives are current.

Validation passed: focused framework-module tests and source/build audits
(-j24), followed by `install jvm-test system-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test build-tools-test cpp-audit
graph-audit` (-j10). All 28 fresh APK cases contain 88 actions; including WebView
and the desktop launcher, 112 captured actions have zero structured runtime
issues and no fresh failure artifacts. All five session-loss cases passed and
removed their owned directories. The archiver, Process, isolated/external
service semantics and final fresh-cache acceptance remain open.

### Original package archiver and zero stubs (2026-09-10)

Compiled the pinned PackageArchiver and AppStateHelper and deleted the final
stub. ActivityStarter again calls the original archiver API. Its server patch
connects captured package/resolver state, the shared user authority and atomic
archive-state mutations to the hosted package manager. Original archive
eligibility, caller checks, icon decoding and archived-activity matching remain
in AOSP. Full installer-session creation and uninstall transactions require an
absent installer backend and report a structured unsupported operation; this
checkpoint does not claim working archive/install transactions.

Package snapshots now capture cross-user grants and shell restrictions. Package
flags come from original PackageInfoUtils, and installation records the actual
CE/DE directory inodes, as AOSP's AppDataHelper does after installd creates data.
Stock MATCH_UNINSTALLED_PACKAGES queries consequently distinguish retained data
from complete removal. Archive changes and later permission/flag/data changes
do not alter already-captured snapshots.

The regression covers archived versus installed state, component and user
matching, caller rejection, original PNG icon decoding, atomic store/clear,
retained versus absent data, cross-user permission levels and shell restrictions.
The installer-boundary diagnostic is tested explicitly; a real installer
transaction remains unavailable. The original full closure reaches 1,006
sources, 64 AIDL inputs and 210 blockers in the absent PMS/AMS graph.

The regenerated audit has zero stubs, zero pending entries and zero survivors.
The exact live source inventory contains 7,063 unchanged AOSP sources, 62
modified server sources, one modified client (Process), and no missing originals.
The live jar contains the original archiver with its documented server seams,
and no Computer, PackageManagerService or UserManagerService replacement classes.

The framework archive was rebuilt and pinned to
`N-V-__8AAFca9U75Ixb8tuJrLVJxQqtV0wnE7dxWKuRfDcKM`, SHA-256
`8c4d52c890fea2c97d56e93bae8a8e78878ca5473fae8f8c25728a0125e06ceb`.
Packaging restored the isolated checkout. ART remains SHA-256
`01eb26296581af49b0d951f939596b10a81da0fba8ff676ef3e14b1b9a8ec443`;
both local archives are current.

Validation passed: focused framework-module tests and source/build audits
(-j24), then `install jvm-test system-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test build-tools-test cpp-audit
graph-audit` (-j10). All 28 fresh APK cases contain 88 actions; including WebView
and the desktop launcher, 112 actions have zero structured runtime issues and
no fresh failure artifacts. Process, isolated/external service semantics and
final fresh-cache acceptance remain open; an empty stub inventory is not plan
completion.

### Service binding validation and registry policy (2026-09-10)

Hosted AMS preserves all 64 bind-flag bits and follows pinned AMS/ActiveServices
validation for caller attachment, file descriptors, instance names, privileged
flags, cross-user access and incompatible isolated/external/shared modes.
Component permission checks now use original ActivityManager behavior. Cold
binds and starts check access before spawning or retaining pending work. Service
resolution carries the requested user, resolved type and supported query flags.
Successful named/isolated/external process execution remains the next step;
validation alone does not implement separate instances or isolated identities.

The Binder registry retains allowIsolated and dump priority from both native and
stock IServiceManager registration. Both Java lookup shapes and native lookups,
including pending lookups completed by later publication, check isolated-UID
policy before delivering an object capability. Isolated UIDs cannot register
services or notification callbacks. Replacement updates the registration's policy;
listServices filters by dump mask and returns sorted names. NDK flag translation
and null-argument behavior follow the pinned native client. The internal wire
version is now 6, so old peers cannot interpret the expanded registration frame.

Focused tests pass for bit-62 external flags, malformed requests, cold cross-UID
permission/export denials, native/Java registration and lookup, replacement,
notification rejection, and dump-mask filtering. The isolated registry fixture
sets the authority's UID under its lock; it tests registry enforcement and does
not claim launch-credential authentication, which remains open.

The broad -j10 gate passed: `install jvm-test system-test integration-test
webview-test webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit`. All 28 fresh APK cases passed with 89
captured actions; WebView and desktop journals bring the total to 113 actions,
all without structured runtime issues or fresh failure artifacts. No AOSP package
inputs changed; both archives and their pins remain at the previous checkpoint.

### Supervised process credentials and kernel identities (2026-09-10)

Ordinary APK processes launched by hosted AMS now reserve a one-use broker
credential, enter a Linux user namespace, wait for their supervisor to bind the
credential to the kernel child PID, and authenticate before ART starts. The
broker captures package, process, start sequence and UID; claims check the peer's
kernel PID and process start time. Application peers cannot reserve, bind or
revoke grants. Credentials are not accepted twice or for another package/PID.
The internal wire version is now 7.

The isolated UID allocator uses the original global 99000–99999 range and keeps
revoked identities unavailable until process death. Real subprocess regressions
exercise isolated authentication and UID reuse. They also reject inherited app
and system cookies in a foreign user namespace, an exec descendant, and a nested
namespace. Ordinary supervised APK launches exercise the same handshake in the
system and APK tests. Named/isolated/external service execution and the remaining
stock Process restoration are still open; the subprocess authority test alone
is not an isolated Android service launch. User namespaces here supply kernel
UID/GID identity, not Android filesystem, network or SELinux confinement.

The broad -j10 gate passed: `install jvm-test system-test integration-test
webview-test webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit`. All 28 fresh APK cases passed with 91
captured actions; WebView and desktop journals bring the total to 115 actions,
all without structured runtime issues or fresh failure artifacts. No AOSP package
inputs changed; both archives and pins remain at the archiver checkpoint.

### Real isolated service instances (2026-09-10)

Named isolated services, shared isolated processes and external-service caller
identities now execute separately signed APK code through authenticated process
starts. Attachment checks the actual child PID, UID and start sequence; captured
application metadata retains the defining APK while external services use their
caller's package identity. Isolated children receive the pinned AMS package and
permission-manager cache exemptions, without inheriting their owner's permissions
or shared session credentials. Immutable package snapshots retain isolated-owner
metadata with explicit owner-resolution semantics.

Final unbind waits for stock Service.onDestroy acknowledgement before retiring an
unneeded isolated process. The Binder Java bridge now forwards transaction flags:
one-way callbacks correctly expose caller PID zero and exceptions reach Android
logging. Supervisor disconnect also closes its claimed child peers.

Validation passed with `-j10`: `install jvm-test system-test integration-test
webview-test webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit`. The standalone process-start test passes
with generic captured metadata. The service APK regression covers separate/reused
instances, shared processes, external ownership, APK class loading, kernel UID/GID,
permission and AppOps denial, and final-service process death. All 28 fresh APK
cases and 112 captured UI actions passed with zero structured runtime issues.
WebView's real renderer attached with isolated UID 99000 in both UI suites.

Batch 5 remains open for pending/repeated binding lifetime, restart/start-result
semantics and broader shared-service regressions. The remaining modified static
client is Process; the stub inventory remains zero. Both AOSP archives and pins
are unchanged and current because this checkpoint changes only owned host code.
Linux user namespaces provide kernel identities, not full Android filesystem,
network or SELinux confinement.

### ART stack-output boundary (2026-09-10)

SIGQUIT exposed a native crash while PaletteWriteCrashThreadStacks wrote its
std::ostream across DSOs with separate static libc++ runtimes. libartpalette now
compiles the pinned libbase logging and thread sources locally, following the
existing RenderEngine boundary. Its stock version script keeps implementation
symbols private; no ART, Java or Palette source was changed.

The system regression sends real SIGQUIT, requires a complete ART dump containing
a named Java thread and SignalCatcher's final acknowledgement, then releases the
process through stdin and requires successful exit. The focused system test and
combined JVM/system/APK/WebView/Binder/native gate pass. Both AOSP archives remain
current: this fix changes build wiring, not their source contents.

### Service binding lifetime and restart (2026-09-10)

Service records now exist before process attachment. Non-auto-create bindings
remain dormant until a start or auto-create binding needs the service. Every bind
is retained, including repeated filters and multiple services on one dispatcher;
unbind and client death release all matching connections. Stock application
attachment sends service creation, all pending bindings, then queued starts in
order. An early unbind suppresses delivery and retires an unneeded isolated child
after its pending attachment and destruction complete. Failed attachment releases
pending bindings and process authority.

An unshared isolated service record has its own process-generation key, so a
replacement cannot reuse a predecessor still executing onDestroy. Shared services
retain their shared process key. Restartable process death preserves bindings,
clears stale published Binders, and schedules replacement using the pinned
restart settings/defaults. Start bookkeeping preserves sticky null-intent starts,
non-sticky termination, redelivery/retry flags, stopSelf IDs and task-removal IDs.

The independently signed APK regressions cover dormant and repeated bindings,
create/bind/start ordering, early unbind held at a real Binder startup gate,
deliberate Application.onCreate failure, replacement during blocked onDestroy,
real process death/reconnect and sticky/non-sticky/redelivery results. The original
framework task-removal test caught an incorrect start-ID increment during this
batch; the implementation was corrected to reuse the last ID, as ActiveServices
does. The existing cross-process service test now checks reconnection and the old
Binder remaining dead rather than expecting onBindingDied for restartable death.

Validation passed with `-j10`: `install jvm-test system-test integration-test
webview-test webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit`. All 28 fresh APK cases and 112 captured UI
actions have zero structured runtime issues; overlay callbacks, playing/paused
pixels and the paused screenshot passed. Both archives and pins are unchanged.

One earlier overlay run exposed an intermittent pinned-provider issue: Chromium
133 sent an infinite frame-rate hint, stock SurfaceControl rejected the entire
transaction, and its completion acknowledgement never arrived. The trace test
correctly failed even though the screenshot and structured issues were clean.
The focused and repeated full gates passed without weakening that check. This
frame-rate-hint failure remains open; it is not claimed fixed by this checkpoint.
Batch 5 also retains broader shared-process/client-death coverage to complete.
The stock Process restoration and final fresh-cache acceptance remain open.


### Service ownership and private sharing (2026-09-10)

Start/stop now apply pinned AMS caller, isolated-caller and descriptor checks,
resolve the requested user and MIME type, and enforce access before mutating a
running service. Starting an external service without its required bind mode is
rejected. Crash counts reset after ActivityManagerConstants' stable-run interval,
independently of the restart backoff window.

An independently signed APK service binds an isolated child and is then killed.
The regression requires the child's Binder death and process removal without
relying on the owner's onDestroy. Private isolated-process sharing is exercised
with the original aconfig flag enabled only in SystemTestServer: distinct service
instances share their manifest process, remain separate from caller-named shared
processes, and reject conflicting sharing modes. Production flag defaults are
unchanged. Denied cold starts are also checked before any process attaches.

Validation passed with `-j10`: `install jvm-test system-test integration-test
webview-test webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit`. The focused `system-test process-start-test
cpp-audit graph-audit` command also passed with `-j24`. All 28 fresh APK cases
contain 91 clean actions; WebView, overlay and desktop add 24, for 115 actions
with no structured runtime issues or fresh failure artifacts. The AOSP archives
and pins are unchanged and current. Process restoration and final acceptance
remain open; the earlier intermittent WebView frame-rate-hint finding is not
claimed fixed by this successful run.

### Process UID queries across namespaces (2026-09-10)

The original Process.getUidForPid/readProcLines APIs now observe authenticated
Android UIDs across hosted user namespaces. The native proc reader retains
Linux file parsing and uses the broker for known process identities. The broker
checks both PID and kernel start time, requires child authentication, declines
ambiguous multi-identity PIDs, and never treats an unreadable proc entry as proof
of identity. Revocation preserves the UID until the actual child exits. Unknown
processes retain the native Linux query behavior. AZKB protocol version is 8.

Broker tests cover unclaimed, authenticated and exited children. Independently
signed isolated services verify both lookup directions: framework caller to
isolated child and isolated child to framework caller. The final focused
`-j24 binder-broker-test system-test cpp-audit graph-audit` gate passes. The UID
change also passed `-j10 install jvm-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-cpp-test ndk-test build-tools-test`:
28 APK cases and 115 captured UI actions have empty structured issue lists and
no fresh failure artifacts. The reverse lookup extension was verified by the
final focused gate. Both AOSP archives remain unchanged and current. The Java
Process identity/start adaptation and final acceptance are still open.



### Native process identity and startup (2026-09-10)

Process.myUid and its SDK-sandbox annotation now match the pinned original;
compiled bytecode calls Os.getuid directly. The launcher authenticates its
package and role before creating ART and installs an immutable UID/GID in
libcore. CriticalNative identity getters perform no Binder requests. Stock
LocalSocket and Os.getsockoptUcred queries use the same authenticated native
peer mapping; unknown Linux peers retain their kernel credentials.

Trusted framework VMs use authenticated logical identities. Supervised APK
children retain their real kernel user-namespace UID/GID; the signed service
fixture now verifies those IDs by reading procfs bytes independently of the
mapped APIs. Same-ID setters succeed, attempted identity changes are rejected,
and actual namespace children and standalone ART retain kernel setter behavior.
Negative Process.setUid/setGid arguments return failure instead of trapping in
an unsigned conversion.

APK package discovery uses the original ApkLiteParseUtils in a separate parser
VM with public read-only properties and no Binder credentials. Explicit launch
roles replace late identity selection. The launcher establishes ART's signal
mask before creating native workers, preserving SIGQUIT stack dumps. The
libjavacore export map retains its original JNI exports and exposes only the
additional pre-VM initializer; identity helpers remain private.

The full `-j10 install jvm-test system-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test stock-task-output-test system-certificates-test
binder-test binder-broker-test binder-cpp-test ndk-test build-tools-test
cpp-audit graph-audit` gate passes. All 28 APK cases have fresh artifacts and
89 captured actions with empty structured issue lists; WebView, overlays and
the desktop launcher add 24 clean actions. Native identity initialization and
setter tests, original socket APIs, permission denial, real service processes,
metadata-only startup and SIGQUIT output are covered. No fresh failure artifact
remains. This successful run does not resolve the earlier intermittent Chromium
frame-rate-hint finding.

Both archives were rebuilt sequentially and their local checksums verified.
ART is SHA-256
`aa71ef019f0360bc53c5e795858e58b3ec216d8ccafa6d623d5c6c6e3b9a921c`,
pinned to `N-V-__8AAFy_Sw3fTP2p_29yYUTLwLIfAFVUAabWYBO97HQb`.
The framework is SHA-256
`0e54d08c4b76386ec97b2181b755fa72d43aba498827ff56119276d2c8c61d5b`,
pinned to `N-V-__8AAO0Z9U5E5bBwa_fDt0t3LShts1KKmx4HSZ0eQvcE`.
Normal builds verified both package hashes without modifying extracted caches.
The exact static source response file identifies 7,063 unchanged AOSP sources,
62 modified server sources and one modified client, Process. Its two launch
methods, foreground-service restart notification handling and final acceptance
remain open. The stub inventory remains zero.



### Foreground-service restart and notification ownership (2026-09-10)

The hosted service record now retains its foreground notification and reposts
it after process recreation with the replacement PID. NotificationManager
receives the service owner's UID and actual process PID, and owns a separate
notification copy. Stop-foreground behavior follows the pinned SDK-dependent
rules: removal deletes the notification, detachment leaves it after service
destruction, and ordinary foreground exit retains its association until teardown.
Cancellation and flag removal preserve a notification shared by another active
foreground service, including restart scheduling.

RunningServiceInfo now reports foreground state, the corresponding state flags
and crash count. Its client count follows the original connection-Binder map:
repeated binds through one dispatcher retain separate binding records but count
as one client. Start-service permission failures return the original Binder
ComponentName marker; stock ContextImpl still raises SecurityException. Cold
stop requests validate permissions and external-service flags before looking
for a running instance.

The independently signed APK fixtures verify actual crash/restart and the new
notification PID, remove/detach/retain lifetimes, onDestroy delivery and shared
notification IDs. A same-package observer checks NotificationManager through
its real permission boundary. Existing binding, isolated UID, external identity,
failed-attach, repeated-binding and client-death regressions remain passing.
The complete `-j10 install jvm-test system-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit` gate passes: 28 fresh APK cases, 88 APK
actions and 24 WebView/desktop actions, all with empty structured issue lists
and no fresh failure artifacts.

Both local AOSP archives and their pins remain unchanged and current from the
native-identity checkpoint. Process.start/startWebView implementation and final
acceptance remain open. The earlier intermittent Chromium frame-rate-hint issue
is not claimed fixed by this gate.


### Requested launch UIDs and scoped process supervisors (2026-09-10)

The broker's AZKB v9 launch protocol accepts a requested UID and Android user,
checks ordinary UIDs against its own package registry, and reserves isolated UIDs
without reusing pending or revoked-but-living identities. A process cannot bind
multiple launch identities. Existing ordinary service allocation remains supported.

A separately authenticated WebView controller identity (UID 1053) can supervise
only isolated children for already registered packages. It cannot grant app,
system or further controller identities. Real nested namespace tests verify a
provider controller launching a renderer with a different effective package,
revocation of both Binder connections and UID quarantine until actual exit.

The native control-socket check combines active broker system authority with
SO_PEERPIDFD liveness before and after authorization. Neither the outer Linux
UID nor retained metadata for a revoked process confers authority. Tests cover
live and dead peers, death during authorization, requested UID/user mismatches,
allocation collisions and the JNI boundary from both app and system processes.

Validation: native broker/NDK/C++ tests and focused framework/system tests pass.
The full `-j10 install jvm-test system-test integration-test webview-test
webview-ui-test webview-overlay-test stock-desktop-launcher-test
stock-session-loss-test binder-test binder-broker-test binder-cpp-test ndk-test
build-tools-test cpp-audit graph-audit` gate passes with 28 fresh APK cases,
91 APK actions and 24 WebView/desktop actions, all without structured issues or
fresh failure artifacts. Local AOSP archives and pins are unchanged and current.
This establishes process-launch authority; stock Process.start/startWebView
transport, execution and final acceptance remain unfinished.

### Stock Process.start and ActivityThread.main (2026-09-10)

Process.start now uses the pinned ZygoteProcess client and ZygoteArguments/native
command-buffer parser against a session-owned launch socket. The libcutils
reserved-socket mapping sits below unchanged LocalSocket Java. Native peer-PID
lifetime and active broker authority checks admit only authenticated system
callers; the exec service validates the requested package, user and launch UID.

AMS launches its captured activity/service requests through this stock client.
Prepared start sequences retain defining APK metadata and death callbacks without
re-entering the AMS lock from the socket worker. The supervisor starts a real
child, binds its broker grant to that child, and releases its startup pipe. The
child enters original RuntimeInit.applicationInit and ActivityThread.main, which
own the target SDK, compatibility settings, main Looper, attachment and lifecycle
transactions. Host resource preparation avoids repeating the original module
initializers. The test adapter observes AMS attachment independently of blocked
provider startup; it registers its endpoint off the main thread and leaves UI
operations gated on AMS readiness and dispatched on the stock main Looper.

This is an exec implementation of the launch protocol. There is no preloaded ART
fork, shared preload memory or unspecialized-process pool. Requested wrappers,
resource limits, supplementary Android groups, runtime-specialization flags and
Android mount policies that this runtime cannot enforce fail explicitly. The
dedicated WebView controller and Process.startWebView remain unfinished.

The original parser has bounded framing and modified-UTF-8 allocation checks;
no native command buffer remains allocated while a connection waits for input.
Child setup travels over a bounded pipe before ART startup, preserving a valid
large/Unicode argument payload whose JSON representation exceeds Linux's
per-environment-string exec limit. Tests also cover malformed/duplicate parser
arguments, real returned PID, kernel UID/GID, target SDK and wrong-package UID
rejection. The cold-launch fixture proves starting-state inspection while a
content provider deliberately blocks, then completes the stock lifecycle checks.

The static source comparison records 7,065 unchanged sources, 62 modified server
sources, one modified client (Process.startWebView), and no missing references.
Original ChildZygoteInit and WebViewZygoteInit sources are selected for the next
controller batch. The zero-stub inventory is unchanged. The current framework
archive is SHA-256
`0f6ee1bf08843c13c07f09492e76a7d599e96c05f148de358bc2602dc2640bbd`, pinned to
`N-V-__8AALtC9U70CSztUR9u_B48US9JIMH63wrRg5R4ZWay`. ART remains SHA-256
`aa71ef019f0360bc53c5e795858e58b3ec216d8ccafa6d623d5c6c6e3b9a921c`;
both local archives match their current pins.

Validation passes with `-j24`: install and the cold-launch regression; with
`-j10`: focused NewPipe launch, then install, JVM, system, full APK integration,
WebView API/UI/overlay, desktop launcher, session loss, Binder/broker/C++, NDK,
build-tools and source/graph audits. All 28 fresh APK cases contain 88 actions;
WebView and desktop add 24 actions. Every captured action has zero structured
runtime issues, with no fresh failure artifacts. WebView overlay and desktop
screenshots were inspected. The earlier startup run also passed the framework
module and system tests; its cold-launch inspection failures were fixed and
rerun successfully before this gate.

### Stock Process.startWebView and provider preload (2026-09-10)

Removed the final Process.java patch. There are now 7,066 selected static AOSP
sources matching the pinned checkout; the remaining 62 modified static sources
are server adaptations. There are zero modified client sources, zero missing
reference files and zero tracked stubs. Final acceptance and a fresh-cache build
remain open; source identity alone does not finish this plan.

Stock WebViewZygote now starts a dedicated exec controller with actual kernel
UID 1053. The controller reuses ChildZygoteInit argument handling and executes
the original WebViewZygoteConnection provider/native preload, forwarding its
actual status. Process.startWebView launches real isolated children only after
successful preload and validates the requested UID range. The existing broker
authenticates System control peers, authorizes the controller's limited launch
role, and binds credentials to actual process lifetimes.

The native startup boundary masks session credential/device-encrypted app data
before ART or provider constructors run. It uses a private mount namespace,
drops capabilities and sets no-new-privileges. Public APKs remain readable;
nested isolated children inherit the hidden data roots and cannot unmount them
or recover the original files through the parent's procfs root. Native tests
also verify that private writes do not alter the parent's data.

Original WebView routing exposed missing package ABI metadata. Hosted package
installation now follows the pinned PackageAbiHelperImpl scan using the original
NativeLibraryHelper and publishes primary/secondary ABI through PackageInfoUtils
and package snapshots. Selection preserves multiarch, preferred 32-bit and
RenderScript rules rather than hardcoding the selected provider's ABI.

This is an exec implementation, without preloaded ART fork/shared pages, Android
shared-profile supplementary groups or GWP-ASan protection. Ordinary hosted AMS
service records, including Chromium renderer services, still use the primary
exec launch path. The dedicated Process.startWebView API has separate live
coverage; it is not evidence of inherited provider memory or full Android
SELinux/device sandbox equivalence.

The framework archive is SHA-256
`c9c6d907bb3172ca1a872d523c7c7b2d9393500a781c5800e6daf5ded30fb739`, pinned to
`N-V-__8AAHBE9U4uzlqu6wCrVZWNBKTFG47DJgYgcoRVy5Gi`. Packaging restored the
isolated checkout. ART remains SHA-256
`aa71ef019f0360bc53c5e795858e58b3ec216d8ccafa6d623d5c6c6e3b9a921c`, pinned to
`N-V-__8AAFy_Sw3fTP2p_29yYUTLwLIfAFVUAabWYBO97HQb`. Both local archive copies
are current.

Validation passed: native app-data isolation, stock cold launch, both original
Process start APIs, focused NewPipe launch, full JVM/system/APK/WebView/overlay,
desktop launcher, session-loss, Binder/C++/NDK and build/source audit gates.
The Process tests verify real parent/PID/UID/GID, target SDK 31, large arguments
with control and supplementary Unicode characters, data visibility and invalid
UID rejection. All 28 fresh APK cases passed with 89 captured actions; WebView
and desktop tests added 24 actions, all with empty structured issue lists and
no fresh failure artifacts. WebView playing pixels and desktop modal screenshots
were inspected. An earlier intermittent Chromium infinite frame-rate hint remains
under investigation; this successful overlay run does not establish its repair.

### Fresh-cache complete acceptance matrix (2026-09-10)

Verified code revision `2a76eac9` from a new detached checkout at
`/tmp/stock-client-acceptance-2a76eac9`. Its `.zig-cache`, `zig-pkg` and `zig-out`
did not exist before the run. Copied only the two current local AOSP archives;
normal Zig dependency resolution and compilation succeeded without the previous
worktree's extracted packages or build outputs. No shared cache was deleted or
overridden. Both archive checksums match the WebView Zygote checkpoint above.

The complete command in [wayland-task-windows.md](wayland-task-windows.md), with
`-j10` and the pinned APK/Mesa environment, passes. This includes the complete
JVM, native, system, Binder/NDK, graphics/input/fence/pressure, Wayland desktop,
APK, WebView and service/compositor-loss matrix. Added `image-queue-jvm-test`,
`process-app-data-test` and `data-partition-test` to the same invocation. Tone,
wallpaper API/capability, native namespace and service isolation checks run in
their existing JVM/system/Binder groups. This does not add desktop wallpaper
presentation or other excluded backend capabilities.

All 28 fresh APK cases passed with 88 captured actions. The remaining recorded
UI groups contain 228 actions, including 116 drag-pressure actions; all 316
records have empty structured runtime issue lists and there are no failure
artifacts. WebView overlay playing pixels and the desktop modal screenshot were
inspected. Both stock Process start APIs pass their real process/identity checks.
The NDK audit finds all 135 reference A* exports. The fresh static source audit
again records 7,066 unchanged inputs, 62 server adaptations, zero modified
clients and zero missing reference files.

The source jar is
`.zig-cache/o/501edee2c2b073d25205d4232264fd43/framework-classes.jar` in that
checkout. Development evidence is `/tmp/stock-client-fresh-acceptance-1.log`,
its `.command`, `.manifest.json` and `.ui.json` files, and the checkout's retained
`zig-out/test-results/` directory. The full run took approximately 20 minutes.
The earlier intermittent Chromium frame-rate defect remains open despite this
successful overlay run; a separately pinned provider update is being evaluated.

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
