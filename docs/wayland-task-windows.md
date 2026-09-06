# Stock graphics and Wayland task windows: implementation plan

Status: M3/M4 graphics gates, M5 native/Java input boundaries, supervised SF
startup, stock Java SurfaceControl/Choreographer and Surface/HWUI queue gates pass. Stock WMS/ATMS
boot and full task windows remain in progress/planned through M11.
Code baseline: `2a2d199`, the
hosted libbinder facade merged into main. This document expands the original
“Wayland task windows with synthetic Android coordinates” proposal into a
dependency-ordered implementation plan. It includes the BufferQueue milestone
and the work needed to make task windows the normal APK presentation path.

## Outcome and scope

Run stock Android window clients, WMS and ATMS on one hosted Android display.
Present each application task as a separate Wayland `xdg_toplevel`. Multiple
activities in one task share that toplevel; attached dialogs and application
popups participate in that task's composition. Preserve Android task, window,
configuration, input and activity lifecycle contracts.

Keep Zig Binder objects, Parcel storage and AZKB transport. Source-built AOSP
C++ consumers use the existing libbinder facade. Pure Zig clients must continue
to work without adopting C++ objects. Reuse pinned Android 16 r1 libgui, libui,
SurfaceFlinger composition and framework sources wherever their dependencies
are available. Linux graphics and Wayland behavior belong in host adapters.

“Complete” includes buffer allocation/import, GPU rendering, per-task
composition, synchronization, stock window clients and services, task geometry,
input/focus, resize, scale/output changes, IME, clipboard/drag-and-drop,
system-window policy, accessibility/test routing and process teardown. The
milestones below are intermediate deliverables, not substitutes for that scope.

Completion does not mean implementing every optional Wayland protocol or
pretending that core Wayland exposes desktop positions, stacking, minimization
or occlusion. Such capabilities need an explicit supported policy and tests.
Protected playback is unavailable until the complete allocation, rendering and
presentation chain can enforce protection; it must fail closed in the meantime.
No arbitrary prebuilt Android C++ ABI compatibility is promised.

This work deliberately replaces the window/graphics portions of the fence in
`stock-client-migration.md` as their replacement milestones pass. The fence
remains useful for unrelated migration work. Audio/PipeWire and codec behavior
stay in their existing host modules; their window/buffer integration is in scope.

## Baseline inventory

The table records the pre-implementation baseline. Current progress is recorded
below it; in particular, HardwareBuffer storage has since moved out of
`platform_abi.zig` into the shared graphics module.

| Area | Evidence in the current tree | Work still required |
| --- | --- | --- |
| Binder | `build/binder_cpp.zig`, `src/native/binder`, `src/binder/ndk.zig`; generated IFpsListener and ownership/death regressions | Compile and exercise the actual libgui/SF interface closure; extend supported APIs as needed |
| Buffer transport experiment | `tools/prototypes/binder-facade`; real GraphicBuffer/Fence serialization over AZKB | Production allocation/import and real BufferQueue scheduling; the prototype mapper only clones opaque handles |
| Native source package | libgui headers and libui sources are selected; only the probe's GUI AIDL is explicitly added | Curate libgui implementation, generated interfaces and SF dependency closure; headers being present is not a built library |
| Hardware buffers | GBM-backed AHardwareBuffer allocation/mapping in `src/native/platform_abi.zig`; EGL/Skia import in `src/native/HardwareBufferRender.cpp` | Shared importable buffer ownership for GraphicBuffer and AHardwareBuffer, metadata validation and fence contracts |
| Rendering | `ArtzigGpuPipeline.cpp` and `host_renderer.zig` render HWUI to a host target | Render through a stock Surface/ANativeWindow buffer lifecycle |
| Wayland | `src/platform/wayland.zig` owns one connection/window, input, fractional scale and video DMA-BUF subsurfaces | Session-level connection, many task surfaces, output/seat tracking, task output buffers and explicit synchronization |
| Window clients | `build/java_sources.zig` excludes stock ViewRootImpl, WindowManagerGlobal, SurfaceControl, TaskOrganizer and related classes | Native/JVM closure, JNI integration and removal of the corresponding replacements |
| Services | Hosted ATMS, display and input services in `src/java/dev/artzig/system`; hosted system-server bootstrap | Stock WMS/ATMS and input/display collaborators with real internal service contracts |
| APK execution | `src/java/dev/artzig/ApkHost.kt`, ActivityThread's host adapter, `src/main.zig` | Session process supervision and stock window/input lifecycle; remove single-target globals |
| Tests | `tools/shot.sh`, `tools/azk-test`, Kotlin integration testkit and pinned APKs | Task-addressed tests and graphics fixtures; multiwindow compositor coverage |

The rebase validation of this baseline passed native/Binder/system/JVM tests and
all 11 APK integration tests (38 recorded steps with no runtime issues). The
older baseline JVM failures recorded in `libbinder-facade-implementation.md`
were absent after rebase. Establish fresh results when implementation begins.

### Additional verified service and output work (2026-09-06)

Rebased again through committed main `fa575e3`, preserving the shared graphics,
input and Binder work. This adds the main worktree's stock performance-hint
clients, activity launch/configuration/affinity fixes and permission-usage values.
The union archive also initializes HWUI for hardware bitmaps before the first
window. The merged framework-module, Binder facade, build-tool, C++ and graph
checks pass (`/tmp/wayland-stock-readback-21.log`). The first-use hardware bitmap,
lazy hardware image and queued PixelCopy paths all verify their pixels. Full JVM, all 14 APK tests, RenderEngine, graphics-buffer and real-SF task/input
output regression gates pass (`/tmp/wayland-main-2-regressions-1.log`). The APK
artifacts contain 44 steps, no runtime issues and no failure records.

`Binder.blockUntilThreadAvailable` now waits on AZKB's actual dispatch capacity,
shared with the C++ facade. A real cross-process regression fills all 32 dispatch
workers, observes the monitor remain blocked, then releases one worker and
requires the monitor to resume. This is the stock Watchdog Binder-monitor
contract; Watchdog itself has not yet joined the running service graph.

The stock Watchdog, WatchdogDiagnostics, ResourcePressureUtil, TraceErrorLogger,
DropboxRateLimiter and SurfaceAnimationThread now compile in the live framework.
The host adapter retains stock checker/timeout/controller/debugger decisions,
uses session DropBox and paths, discovers native service owners through the
broker, and captures local process state instead of issuing global kernel reset
commands. Framework-module and build/audit gates pass
(`/tmp/wayland-watchdog-integration-1.log`), including monitor ordering, timeout
stages, nested/timed pauses, report truncation and dropped-count retention.
The full JVM and system-service suites also pass
(`/tmp/wayland-watchdog-regressions-1.log`). Watchdog is not started in service
boot yet. The current WMS/ATMS compile probe
reports 63 errors (`/tmp/wm-compile-probe-16.log`), down from 78.

The next source package adds stock SDK-sandbox clients and their six AIDL
interfaces, stock ColorDisplayService and its saturation controller, and stock
instant-app resolution helpers. SDK activity validation uses a host capability
boundary: ordinary launches do not query an absent sandbox package; explicit
sandbox hosting fails when no local manager exists. The optional verifier's
shrinker annotation retains its target by name. ColorDisplayService queries the
published UserManager client instead of requiring the full Android user service.
Neither new service is started by this checkpoint.

The Java, build-tool, C++ and graph gates pass
(`/tmp/wayland-wm-closures-java-1.log`). The framework-module runtime gate also
passes (`/tmp/wayland-wm-closures-runtime-1.log`), including SDK Parcel/Binder
identity, authority denials, per-app saturation arbitration and weak-controller
lifetime, and instant-app intent sanitization/installer handoff.
Full JVM and system-service regressions pass as well
(`/tmp/wayland-wm-closures-regressions-1.log`). The WMS/ATMS
compile probe now reports 33 errors (`/tmp/wm-compile-probe-17.log`). Remaining
families include shutdown, intent/receiver policy, wallpaper data, assist,
launcher shortcuts, camera callbacks, archiving and splash-screen transfer.

The stock window-client probe now has one compile error: the hosted AudioManager
lacks `areNavigationRepeatSoundEffectsEnabled`
(`/tmp/window-clients-compile-2.log`). This follows adding the declared view-root
proto, view sysprops, HWUI Java stats, sensitive-content AIDL and view/secure-surface
flags. ViewRootImpl, ThreadedRenderer, SurfaceView, TextureView,
SurfaceControlViewHost, WindowlessWindowManager, accessibility interaction and
SplashScreenView remain a separate probe; their runtime cutover is still pending.

Prior checkpoint validation against committed main `8d17396`:
- The combined framework builds, including stock AppOps, window-context
  transactions and resolver/chooser clients. Main supplies stock window clients,
  SurfaceSyncGroup, Debug, SystemConfig and the Linux Perfetto SDK.
- The merged Binder facade passes both branches' tests, including nullable
  output vectors, Unicode framing, generated interfaces and cross-process
  ownership. `cpp-audit` and `graph-audit` pass.
- The post-rebase WMS/ATMS compile probe reports 78 errors, down from 127
  before the rebase, after compiling SensorPrivacyManager and restoring
  ActivityThread display-specific system UI contexts and resource configuration. Remaining dependencies include watchdog/ANR reporting, shutdown,
  assist, sandbox services and desktop policy. Stock WMS/ATMS still do not boot.
- The rebased native Perfetto test and real-SF input/task-output gate pass.
  Runtime registration is ordered as stock memory factories, Binder/host memory
  overrides, then graphics JNI. EGL clients use the shared graphics allocator.
- The system-service suite and NewPipe launch integration pass after restoring
  SF to the cross-process SurfaceSyncGroup fixture. The launch step records no
  structured runtime issues (`/tmp/wayland-rebase-launch-1.log`).
- PixelCopy exposed an empty `platform/host/Readback.cpp` implementation. The
  native library now links pinned AOSP readback, texture-layer helpers and Skia
  AHardwareBuffer image factories through the host EGL context/fence seam. The
  framework regression checks asynchronous ERROR_SOURCE_NO_DATA for an empty
  queue and exact copied pixels after posting a frame. The framework-module
  gate passes (`/tmp/wayland-stock-readback-19.log`), including direct and lazy
  AHardwareBuffer readback. The lazy Skia TUs require the same `SK_GANESH` and
  `SK_GL` backend definitions as the base Skia library. Earlier passing results below apply to their recorded checkpoints.
- The diagnostic dependency probe also compiles stock CriticalEventLog and
  StackTracesDumpHelper with session data paths and the existing nano runtime.
  Their persistence, retention and app-data redaction regression passes in the
  framework-module gate. AssistDataRequester compiles
  alongside WM but still requires ActivityTaskManagerInternal before it can join
  the standalone framework source set.

- `HostInputManagerBridge` now passes the real-SF input gate with the WM callback
  contract, no-focused-window ANR, input monitoring, pointer capture, display
  removal, filter reinjection and rejection of a disconnected filter host.
  Compositor events enter `notifyKey`/`notifyMotion`; Android's injection API
  bypasses filters and is reserved for reinjection. The adapter preserves the
  pinned same-process permission shortcut. Stock WMS imports this boundary in
  place of the hardware-owning InputManagerService; WMS itself is not booted.
- The generated stock `TouchOcclusionMode` replaces the old placeholder enum,
  restoring its actual wire values. Additional stock WM policy values and
  activity clients compile. PendingIntent user/alarm adapters and WM/SF property
  alignment are staged for the remaining service compile and boot work.

- `input-dispatcher-jvm-test` now runs the production `HostInputDispatcher`
  policy/JNI adapter against real SF publication and stock Java InputEventReceiver.
  It passes focused keys, overlap and coordinate transforms, completion of a
  hidden window's gesture, removal cancellation, focus transfer, pointer-capture
  acknowledgement, channel death, rejection of reentrant close and idempotent
  teardown. Its WM lifetime endpoint and policy are test fixtures; stock WMS
  has not booted and Wayland injection is not wired to this adapter yet.
- Stock ImageReader/ImageWriter now pass pixel access, zero-copy queue transfer
  by HardwareBuffer identity, timestamps, acquisition limits, latest-image
  behavior, transition border luminance and zero retained imports. The JNI
  adapter selects real gralloc/HardwareBuffer paths instead of Layoutlib's
  zero-usage paths. The Zig mapper now accepts the all-zero full-buffer access
  rectangle required by the pinned IMapper contract.
- ProtoLog uses the pinned host logging backend instead of a no-op replacement.
  Bootstrap initializes both view and WM groups before use. WM local-service
  values, animation/display threads, snapshot/animation protos and SF sysprops
  are compiled; these types do not yet constitute registered stock services.
  The pre-rebase WM compile inventory reported 127 errors (down from 233 after
  adding the stock AppOps client and generated dependencies). Remaining gaps
  include watchdog/shutdown and assist collaborators, window-client contracts
  and desktop policy. Stock WM is still a separate compile probe; it does not
  yet form part of the running system service graph.
- The complete post-image/input regression run passes `jvm-test`,
  `input-dispatcher-test`, `graphics-buffer-test` and all 14 APK integration
  tests. Its 44 retained APK steps contain no structured runtime issues.

- `task-output-jvm-test` passes two Java-owned private SF outputs, relocation in
  synthetic Android coordinates, scaling, secure-buffer blackout, independent
  teardown and zero retained graphics imports. Java, C++ and NDK resolve the
  same installed Binder libraries and report the same caller identity.
- ActivityManager, TaskSnapshot and ActivityManagerInternal now use pinned
  sources. The local AM adapter routes implemented operations to the existing
  session process/user authority and reports unsupported operations explicitly.
  Stock WMS/ATMS still do not boot; this is dependency and contract work for M5.
- The stock ProcessCpuTracker now completes `init`/`update` using the Zig procfs
  natives. Tests cover sorted/reused PID arrays, command matching, numeric proc
  parsing, raw-kB RSS (five fields), byte-valued PSS and missing-process failures.
- AppTask queries and UID-bound operations now live on the existing task
  authority. The real client exposed the previously missing query during APK
  launch. All 14 APK integration tests pass after that fix; retained steps have
  no structured runtime issues. The stock AM local-interface change also passes
  `framework-module-test`, `activity-options-test` and `system-test`.

### Implementation evidence (2026-09-05)

- `build/graphics.zig` now builds pinned BufferQueue and libui with generated
  GUI AIDL, HIDL graphics enums, aconfig and C++ sysprop outputs. The host patch
  removes optional hwbinder producer wrappers and selects the GBM gralloc seam.
- `src/platform/graphics_buffer.zig` owns validated, versioned DMA-BUF handles,
  independent imports, CPU mapping and allocation metadata. Stock GraphicBuffer
  and stock AHardwareBuffer share the same native object and lifetime. HWUI's
  libui dependencies use this implementation; its GUI cutover is still pending.
- `gui-test` passed CPU and GPU pixels across processes, real sync_file fences,
  slot reuse, resize, bounded dequeue timeout and recovery, cancellation,
  authenticated oneway release callbacks, producer crash cleanup and consumer
  death. Repeated imports and AHardwareBuffer parcel round trips restore FD and
  allocation counts. Stock Surface/ANativeWindow also queues GPU frames into
  the remote queue.
- `binder-cpp-test` passed typed Binder references, nullable enum vectors,
  concurrent weak delegate caching and attachment cleanup, in addition to the
  existing object/FD ownership, generated interface, identity and death tests.
  Native libbase properties now read the same session property area as Java.
- A fresh regression run passed `build-tools-test`, `graph-audit`, `cpp-audit`,
  `native-test`, `binder-broker-test`, `binder-test`, `system-test`, `jvm-test`
  and all 11 APK integration tests: 38 steps, zero structured runtime issues.
  This run validated the initial shared-libui cutover; rerun affected checks as
  the composer closure and presentation integration evolve.
- Stock Surface links through the real composer, transaction, BLAST and window-info
  source closure. `surface-wayland-test` presents 12 GPU frames through separate
  producer and consumer processes using the existing Wayland importer. It checks
  resize, queue backpressure/resume, producer crash, consumer crash and broker
  loss. Each scenario verifies the screenshot and frame journal; graceful
  consumer teardown restores zero live graphics allocations. Fence waits and
  buffer returns run off the Wayland event thread. This is the implicit-sync
  fallback; explicit-sync negotiation and device/modifier selection are pending.
- `window-fixture` builds a Kotlin APK with task/activity, dialog, text editor,
  SurfaceView and lifecycle controls. Three integration tests currently prove
  launch/counter, attached dialog and saved counter across recreation. The
  fixture SHA is generated into the existing testkit registry at build time.
  Managed-only APKs now launch; APKs with only incompatible native libraries
  still fail ABI selection, following NativeLibraryHelper's distinction.
- The complete APK run after RenderEngine integration and the C++ logging
  ownership fix passed all 14 tests (41 retained steps, zero runtime issues).
  `surface-wayland-test` (five
  scenarios), `gui-test`, `binder-cpp-test`, `graphics-buffer-test`, `jvm-test`,
  `graph-audit`, `cpp-audit` and `build-tools-test` also passed. This validates
  the current buffer presentation path, not SurfaceFlinger or task windows.
- The stock GLConsumer regression now passes GPU texture acquisition, acquire
  fences, repeated slot use and resize. The host EGL seam imports registered
  DMA-BUF metadata and explicitly counts display owners: Mesa does not provide
  the initialization reference counting assumed by AOSP's image/probe code.
  HWUI context owners use the same balanced display references.
- `renderengine-test` passes the pinned threaded GL/Ganesh renderer: changing
  GPU input buffers, alpha, translation, six frames alternating between two
  persistent outputs of different sizes, real completion fences and zero live
  allocations after teardown. Updating either output preserves the other's pixels.
  Pinned Skia AHardwareBuffer utilities own imported textures and retain the
  Android object explicitly. Vulkan/Graphite/protected-context creation is
  rejected until the host supports those backends. This is not a running SF
  service; subtree composition is checked separately below.
- `surfaceflinger-frontend-test` passes 60 pinned AOSP hierarchy and lifecycle
  tests in an authenticated AZKB session, including mirrors, relative parents,
  reparenting, handle death and visibility changes. The build generates the real
  HIDL/NDK types and Perfetto protobuf/protozero/C++ headers used by SF's headers.
- `surfaceflinger-composition-test` passes the pinned hierarchy → snapshot →
  LayerFE → RenderEngine path with real GPU inputs and two persistent outputs.
  Private mirror roots isolate the two original subtrees on a shared layer stack;
  synthetic translation, crop, scale, alpha, changing frames and secure-buffer
  blackout are checked with pixels. Updates preserve the other output, and all
  allocations are released. This fixture fixes the legacy active-display rotation
  global to zero and uses resolved transactions directly. It does not yet prove
  SF service publication, transaction scheduling, private virtual-display IDs,
  output BufferQueues, release callbacks or restart behavior.
- The Binder facade now supports the pinned dump transaction, including remote
  UTF-16 arguments, caller identity, status propagation and borrowed caller FD
  lifetime. The corresponding cross-process regression passes.
- The standard `AIBinder_toPlatformBinder` / `AIBinder_fromPlatformBinder`
  conversions use the same Zig Binder object registry. Local and remote
  round-trip tests preserve identity and borrowed-input/owned-result references.
- The pinned C++ ParcelFileDescriptor implementation now links against the Zig
  parcel facade. Tests pass for cross-process transfer after source/reply
  destruction, reliable-channel detach framing, failed detach preserving the
  caller's descriptor, and invalid-descriptor rejection before parcel mutation.
- `perfetto-test` builds the pinned client SDK and its actual Soong generator
  closure, then verifies 24 ordered packets across three independent tracing
  sessions without requiring a tracing daemon.
- `host-composer-test` passes the real ComposerHal interface over the Zig layer
  state and `std.Io` software clock. It checks client-composition validation,
  bounded/stale layer handles, GPU completion fences, cached GraphicBuffer slots,
  reentrant vsync callbacks, power transitions and teardown without leaked
  allocations. The logical display has no physical scanout and explicitly
  advertises unreliable presentation fences. Private outputs still need the
  running SF/BufferQueue/Wayland integration gate; this test does not prove it.
- The full native SF core and generated statistics/power dependencies build in
  `surfaceflinger-core`. `surfaceflinger-service-test` passes publication from a
  separate process, stock application-UID client creation, a committed
  transaction callback, exact privileged-display denial, and service death.
  The retained result is `zig-out/test-results/surfaceflinger-service/run/results.json`.
  The bring-up fixed variable-size LightFlattenable framing in the Parcel
  facade; stock Region/Rect and full LayerState round-trip regressions pass.
- `surfaceflinger-output-test` now passes against the production
  `azk-surfaceflinger` executable. Two mirrored subtrees on one Android layer
  stack compose into separate private GPU virtual-display queues. It checks
  alternating GPU content, crop/scale/alpha, secure blackout, transactional
  resize, callback order, release fences, stalled-consumer isolation, independent
  output destruction and SF death with a retained frame. All local graphics
  allocations return to baseline; the physical display list stays unchanged.
  `surfaceflinger` installs the executable, whose Zig entry point owns readiness
  and stdin shutdown through `std.Io`. The normal launcher now starts it before
  the system process, as covered by the startup gate below.
- The full regression after the SF frontend integration passed the build/native
  audits, Binder/system/JVM tests, GUI/Wayland/RenderEngine checks, all 60 SF
  frontend tests, and all 14 APK integration tests (42 steps, zero runtime issues).
  The GPU subtree check passed separately after its final fixture changes.
- The full regression after running-SF integration also passed
  (`/tmp/sf-running-full-regression.log`): build/native audits, Binder/system/JVM,
  GUI/Wayland/RenderEngine, the SF frontend and both service/output gates,
  Perfetto, host composer, and all 14 APK integration tests. The APK suite
  recorded 43 steps with no structured runtime issues.
- Remaining native work includes compositor device/modifier negotiation,
  explicit-sync negotiation, display/input event integration and connecting the
  SF outputs to task-managed Wayland surfaces. M5–M11 are not complete.
- The M5 input-channel foundation now builds in `build/input.zig` with generated
  input flags and the pinned CXX header. `input-channel-test` passes all ten
  pinned InputChannel tests. `input-channel-broker-test` passes stock
  InputChannelCore transfer over AZKB, token identity, duplication after source
  destruction, input acknowledgements and peer close.
- The pinned InputPublisher/InputConsumer and unmodified Rust verifier now build
  through `build/input.zig`, `build/rust.zig` and the typed Soong closure in
  `build/rust_sources.zig`. The package contains AOSP's selected Rust 1.83.0
  compiler and host runtime. CXX 1.0.119 is built from pinned source; input
  constants and Binder NDK bindings use the actual AIDL/bindgen inputs.
  `input-publisher-test` passes 15 pinned C++ tests and `input-verifier-test`
  passes all 44 pinned Rust tests, covering gesture validation, keyboard
  classification and persisted-data compatibility. The AZKB channel test now
  uses the real publisher/consumer, checks KeyEvent fields and finished timing,
  and verifies peer death. All four input gates, `cpp-audit`, `graph-audit` and
  `build-tools-test` passed together (`/tmp/input-production-4.log`). The stock
  transport is complete; service integration and WMS/ATMS boot remain pending.
- `build/input_dispatcher.zig` now builds the pinned dispatcher, key maps,
  HMAC verification, input tracing, latency aggregation and StatsD host clients
  from the curated package. Package-manager/StatsD AIDL, KLL protobuf, native
  statistics and window/StatsD flags are generated by stock tools. The only
  dispatcher source patch selects OpenSSL's declaration of `CRYPTO_memcmp` on
  the host; signing and verification remain stock. `input-dispatcher-test`
  passes six interface tests: overlapping windows and coordinate transforms,
  gesture target retention, focus/key ordering, cancellation on window removal,
  UID mismatch rejection, broken-channel isolation and signed-event tampering
  (`/tmp/input-dispatcher-production-2.log`). This gate supplies window metadata
  directly and uses AOSP's fake WMS policy. It does not prove WMS boot or the
  production input-service connection to SF. Perfetto and statistics are linked;
  their full dispatcher-specific runtime behavior is not yet covered.
- `input-surfaceflinger-test` passes the native SF/dispatcher integration gate
  (`/tmp/input-surfaceflinger-8.log`): SF publishes window/display metadata over
  AZKB, native tokens preserve identity, scaled parent containers produce the
  expected frames/local coordinates, overlap selects the top window, SF focus
  requests reach the dispatcher, moving one window changes routing, and removing
  a touched window cancels its gesture while the other remains usable. All
  three supervised processes exit zero. The test supplies a WMS lifetime Binder,
  a bounded focus-service fixture and a DMS projection; it exercises real SF
  boot completion but **does not boot WMS/ATMS or provide a production IMS**.
  SF's input publication is gated on resolving inputflinger in `bootFinished`.
  Production bootstrap must satisfy that ordering with the real service graph.
- That integration uncovered an HWC adapter bug: SF unconditionally queries
  requested LUTs after validation, so a backend requesting none must return
  success with empty lists, as pinned HidlComposer does. Returning UNSUPPORTED
  aborted physical-display validation. The adapter and regression now implement
  that contract and accept observationally neutral unit layer brightness.
  No physical scanout/presentation completion is claimed.
- The generated package-manager interface needed Parcel's UTF-8 string-vector
  methods with UTF-16 wire storage. The facade now provides the pinned string
  and vector overloads (including optional/owned nullable values), rejects
  invalid UTF-8 before writing and preserves partial-read mutation. Binder
  tests check independent UTF-16 framing, nulls, malformed input and a Unicode
  vector round trip across AZKB. Zig still owns the Parcel storage/transport.
- `input-channel-jvm-test` passes stock InputChannel JNI over the shared Zig
  Binder/Parcel: channel pairs, native/Java token identity, nullable/uninitialized
  channels, Unicode names, duplication, Parcel framing, ownership transfer and
  disposal. The follow-up JVM and full 14-test APK regression passed
  (`/tmp/input-channel-full-regression-1.log`).
- Stock MessageQueue, InputEventReceiver and InputEventSender JNI replace the
  custom queue's native pointer boundary. A small host adapter borrows stock
  Looper poll state and combines native/Java deadlines. Two native outer-loop
  tests pass alongside the ten pinned InputChannel tests. The Java JNI gate
  passes key/motion delivery, explicit motion batching, handled/unhandled
  replies, cross-thread sends, receiver exception rethrow/recovery, fd listener
  unregistration and disposal (`/tmp/input-event-jni-4.log`). The follow-up
  native/Binder/GUI/input/SF, build audit/tool, JVM and full APK regression passes:
  14 APK tests, 41 steps, zero runtime issues
  (`/tmp/input-event-full-regression-1.log`). The retained NewPipe drawer frame
  was inspected as well.
- Stock InputApplicationHandle/InputWindowHandle JNI now registers in the
  application island, with pinned common JNI and region helpers. The JNI gate
  passes application/channel/window identity, frame/region conversion, cached
  metadata updates, token/region/focus-transfer clearing, and SF output fields
  (`/tmp/input-handle-jni-2.log`). The native test adapter is a separate test-only
  library. Real Java SurfaceControl crop handles and stock service boot remain
  pending. The APK launch check and full JVM/APK follow-up regression pass:
  14 APK tests, 41 recorded steps, zero runtime issues, with build audits passing
  (`/tmp/input-handle-full-regression-1.log`).
- The full post-dispatcher regression passed: native unit checks, Binder/GUI,
  HWC, both running-SF gates, six input gates, build audits/tools, JVM and all
  14 APK tests with 43 recorded steps and zero runtime issues
  (`/tmp/input-dispatcher-full-regression-1.log`). The updated production SF
  executable is installed by that run.
- The graphics rerun exposed two lifecycle/test issues. The shared runner now
  uses `build/command.zig` to inherit the invocation's Mesa paths across cached
  configurations, and it rejects unexpected service/broker exit codes even if
  the client succeeds. SF supervision now terminates its C++ runtime with
  `_Exit`, matching stock SF's process lifetime, rather than racing global
  destructors against live workers. Binder, BufferQueue, both running-SF gates,
  all four input gates and native audits pass with the stricter runner
  (`/tmp/input-native-regression-3.log`). Both SF processes exit zero; only the
  explicitly requested BufferQueue crash producer exits by signal. Runner
  regression tests verify that a failed service cannot be masked by its client.
- The broad JVM closure was rerun against this worktree's freshly built
  `framework-classes.jar` (`6e8abe6fdd98f0dbd6374662632bacb7` cache directory).
  It still reports 2,596 sources, 587 AIDL entries and 981 blocker entries.
  Existing replacements count as present, so the report is an inventory for
  narrowing the service seams, not evidence that those services can boot.

Local artifacts: `zig-out/test-results/bufferqueue/run/results.json`,
`zig-out/test-results/binder-facade/run/results.json`,
`zig-out/test-results/wayland-surface/`, `zig-out/test-results/renderengine/`,
`zig-out/test-results/surfaceflinger-frontend/run/results.json`,
`zig-out/test-results/surfaceflinger-composition/run/results.json`,
`zig-out/test-results/integration/`,
`/tmp/wayland-full-current.log`, `/tmp/wayland-regression.log`, and
`/tmp/wayland-implementation-closure.log`. The full post-RenderEngine regression
is `/tmp/wayland-graphics-full-regression.log`; the strengthened independent
output test is `/tmp/renderengine-independent-test.log`.
Current SF integration evidence is `/tmp/sf-foundation-full-regression.log` and
`/tmp/sf-composition-4.log`. Running-service evidence is retained in
`zig-out/test-results/surfaceflinger-service/run/results.json` and
`zig-out/test-results/surfaceflinger-output/run/results.json`; the first
production-process run is `/tmp/sf-process-1.log`.

A preliminary closure run during planning seeded ViewRootImpl,
WindowManagerGlobal, SurfaceControl, TaskOrganizer, WMS, ATMS, InputManagerService
and DisplayManagerService together. Against an available classes jar in the main
worktree it reported 2,596 source nodes, 587 AIDL entries and 981 blocker entries,
including generated sources, unselected modules and host-replacement linkage.
This is a broad static traversal, not 981 distinct implementation tasks or a
verified minimal closure: it reaches unrelated service branches and treats many
existing replacements as present. The jar was not rebuilt for this planning
pass. M0 must rerun against the implementation baseline and reduce the closure
at the documented host boundaries before adding sources. The raw planning report
is retained locally at `/tmp/wayland-plan-closure.log`.

Reproduce against a freshly built classes jar with:

```sh
python3 tools/aosp-closure.py --classes /absolute/path/to/framework-classes.jar \
  --aosp /absolute/path/to/pinned-aosp closure \
  android.view.ViewRootImpl android.view.WindowManagerGlobal \
  android.view.SurfaceControl android.window.TaskOrganizer \
  com.android.server.wm.WindowManagerService \
  com.android.server.wm.ActivityTaskManagerService \
  com.android.server.input.InputManagerService \
  com.android.server.display.DisplayManagerService
```

- The launcher now supervises broker → SurfaceFlinger → system-server startup
  and reverses that order on shutdown. `session-test` passes exact readiness,
  startup/shutdown timeouts, missing/malformed executables, EOF and descriptor,
  child and runtime-directory cleanup. The `--service-exec` trampoline transfers
  child ownership before exec failure, avoiding the pinned Zig process-spawn
  error path's leaked pipes and unreaped child.
- Stock HardwareBuffer, GraphicBuffer and SyncFence Java/JNI own the same libui
  objects as NDK clients. `buffer-jvm-test` passes independent Java/NDK/Parcel
  owners, CPU Canvas colors, GPU writes, real sync_file duplication and Parcel
  transfer, invalid/closed handles and zero live allocations. Host Skia uses
  explicit RGBA for Android RGBA/RGBX buffers; host-native N32 is BGRA and swapped
  red/blue channels when reused as the Android format.
- Stock SurfaceControl, SurfaceSession and Choreographer Java/JNI are enabled.
  `surface-client-jvm-test` passes creation, alias identity, Parcel transfer,
  transaction ownership, committed callbacks, per-surface vsync phase order and
  removal against the launcher's separate SF process. AZKB attaches every native
  dispatch worker before invoking C++ listeners in an ART process. Zig still
  owns Binder objects, Parcel object tables and transport.
- The full regression after this cutover passed `jvm-test`, `integration-test`,
  `native-test`, `cpp-audit` and `graph-audit`
  (`/tmp/stock-surface-full-regression-1.log`): all 14 APK tests, 40 steps,
  zero structured runtime issues. This validates the stock frame scheduler on
  the existing hosted window path; stock WMS/ATMS and HWUI Surface presentation
  are still required before M5/M6 can be marked complete.
- `surface-buffer-jvm-test` now passes stock Surface, SurfaceTexture and BLAST
  Java/JNI, CPU and hardware Canvas frames, Java/NDK Parcel ownership, resize,
  twelve HWUI frames across two independent consumers, sibling pixel isolation,
  consumer abandonment through ReliableSurface and BLAST transaction commits
  through SF. All local buffer allocations return to baseline. The production
  GPU adapter dequeues/imports/queues stock ANativeWindow buffers with completion
  fences and shares one GBM/Ganesh context across RenderThread window pipelines.
  It uses surfaceless EGL because GBM does not advertise pbuffer configurations.
- HWUI's host RenderThread now reuses pinned native Choreographer scheduling
  and the stock Looper-driven ThreadBase. This restores deferred-frame retries
  and callback cancellation/deduplication. Properties/Binder JNI registration
  precedes display JNI, matching AndroidRuntime's initialization dependency.
  The targeted graphics gates and NewPipe launch pass after this change
  (`/tmp/stock-window-launch-1.log`). The full regression exposed a legacy
  synchronized-child stall: autonomous RT frames exhaust Mesa buffers while
  the UI owner waits in `syncAndDraw`, preventing its parent commit. The host
  `CanvasContext::doFrame` adaptation leaves legacy targets/explicit offscreen
  jobs owner-driven and retains stock RT scheduling for native Surface targets.
  The correction passes the desktop APK and queue gates, followed by the full
  `jvm-test`, `integration-test`, `native-test`, `binder-cpp-test`, `system-test`,
  `cpp-audit` and `graph-audit` run (`/tmp/stock-window-full-regression-2.log`):
  14 APK tests, 41 steps, zero structured runtime issues.
  These gates do not yet replace the hosted window clients or WMS/ATMS.
- The stock UriGrantsManagerService, local interface and grant/owner records now
  replace the permissive hosted URI service. Hosted process attachment and PM
  permissions supply its collaborators; acquisition through an authority grant
  still leaves exact URI/mode checks to the stock provider Transport. The A/B
  process tests verify private-provider access, read/write separation, prefix
  grants, persistable grants and revocation (`/tmp/stock-uri-system-1.log`).
  The complete APK/JVM/RenderEngine/audit regression passes afterward
  (`/tmp/stock-uri-full-regression-1.log`). Cross-boot grant restoration still
  depends on restoring the package catalog before the stock service reads it.
- Stock IntentFirewall and FileObserver Java/JNI now compile with generated
  system-server event tags. `intent-policy-test` verifies activity/service/
  broadcast isolation, sender permission/signature checks, atomic replacement,
  close-write updates and deletion through the stock inotify/Handler reload
  path (`/tmp/stock-intent-policy-2.log`). Its PM collaborators use the hosted
  package catalog. Connecting this policy object to stock ATMS remains part of
  the service boot gate; the current hosted task manager is still authoritative.
- `TaskGeometry` implements the M7 bounded synthetic allocator independently of
  desktop coordinates. It preserves live origins when possible, reserves both
  old/new bounds until a transaction is acknowledged, and rejects capacity,
  oversized, stale-generation and overlapping requests without changing live
  bounds. Cancellation and task disappearance invalidate old acknowledgements.
  `task-geometry-test` passes deterministic placement/restore/relocation checks
  and 2,000 mixed operations (`/tmp/task-geometry-test.log`). The shell must
  commit only after its matching Android bounds transaction, and remove only
  after Android task removal. Organizer/presentation wiring is still pending.
  Non-resizable tasks retain their Android size and will be letterboxed by the
  presentation adapter; oversized/capacity failures leave an existing window
  at its last accepted size and reject creation of a new output.
- Stock ActivityOptions, ActivityTransitionState, enter/exit coordinators and
  SplashScreen now replace the option/splash interface stubs. The Activity and
  ActivityThread adaptations retain transition data, callback lifecycle and
  splash-listener cleanup. The transitional ViewRoot adapter drops nonterminal
  input while a transition is paused, preserves Back and cancels terminal
  events. Splash theme updates use the stock package user state and enforce
  package ownership; a pinned setter annotation is corrected to admit the
  documented null reset. `activity-options-test`, cross-process service checks
  and the APK launch gate pass (`/tmp/stock-activity-options-4.log`).
- Stock UserState, ProgressReporter, AppTimeTracker and HostingRecord compile
  with their generated proto outputs. The single hosted user's boot walks
  BOOTING → RUNNING_LOCKED → RUNNING_UNLOCKING → RUNNING_UNLOCKED and delivers
  SystemServiceManager callbacks before PHASE_BOOT_COMPLETED. Local/Binder user
  queries share that state. The complete APK/JVM/audit regression passes
  (`/tmp/stock-options-full-regression-1.log`): 14 APK tests, 42 steps and zero
  structured runtime issues. The stub audit has 63 pending replacements.

## Architecture and ownership

```text
APK processes
  stock Activity/ViewRootImpl/WindowManagerGlobal/SurfaceControl/HWUI
     | Binder transactions             | Surface/BufferQueue buffers
     v                                 v
hosted system-server                 hosted SurfaceFlinger process
  stock WMS/ATMS                    stock layer/transaction/composition logic
  display/input collaborators       RenderEngine + host graphics backend
  Kotlin WaylandTaskShell              |
     | task operations                | per-task output BufferQueues
     +-------------------+------------+
                         v
                Zig Wayland session process
                connection, seats, outputs,
                per-task xdg_toplevels and buffer presentation
```

Use the launcher as session supervisor. Define startup readiness, identity and
shutdown contracts for broker, graphics service, system server and APK peers.
Keep the Wayland connection and protocol objects in one session owner rather
than opening a connection for every task. Kotlin owns organizer callbacks and
Android task decisions; Zig owns Wayland objects, Linux resources and GPU buffer
allocation/import. A narrow source-compatible C++ adapter connects AOSP graphics
types to those operations. Do not send pointers or invented C++ layouts across
processes.

The control endpoint between shell and presentation must be session-authorized,
carry stable task IDs plus a generation, and express create/configure/focus/
present/destroy operations. Frame identity, buffer lifetime, configuration
generation and acknowledgements must be explicit. Use existing AZKB/FD transport
where appropriate; keep frame traffic and blocking waits off the Wayland event
thread and Android global lock. New Zig I/O, descriptor ownership and
synchronization use `std.Io`, including code extracted from older raw-FD paths.

## Decisions to resolve with executable gates

1. **Linux EGL versus Android native windows.** Host Mesa cannot be assumed to
   accept an Android ANativeWindow as a Wayland EGL native window. Keep stock
   Surface/BufferQueue and adapt the existing HWUI host GPU target to dequeue,
   import a DMA-BUF as an EGLImage/render target, render, and queue with a fence.
   Reuse stock rendering logic above that boundary. Do not build another fake
   Surface or a second queue scheduler to hide the platform mismatch.
2. **SurfaceFlinger host backend.** Port the pinned RenderEngine/composition and
   output interfaces onto GBM/EGL with GPU composition as the first backend.
   Trace the hardware composer, display scheduling, properties and service
   dependencies. Supply a bounded host backend with real timing/capabilities;
   do not implement a broad fake composer service whose methods silently succeed.
3. **Task subtree output.** Try the pinned `mirrorSurface`, display surface and
   layer-stack transaction path before introducing an SF extension. A private
   SurfaceFlinger virtual output is not necessarily an Android logical display
   published by DisplayManager. Keep per-task outputs out of the application's
   display list. Validate this distinction rather than ruling out useful SF
   output machinery because its name contains “virtual display”.
4. **Synthetic display size.** Stable non-overlapping task rectangles need a
   bounded allocation policy, not an enormous automatically growing display.
   Test candidate bounds against stock WMS clipping, app-visible display metrics,
   maximum window metrics and non-resizable activities before fixing constants.
   Allocation failure must produce a defined launch/resize outcome; never wrap,
   silently overlap, or resize every app's display on each new task.
5. **Internal service closure.** WMS takes concrete InputManagerService and ATMS
   objects and shares ATMS's global lock. ATMS initialization also needs
   IntentFirewall, PendingIntentController, ActivityManagerInternal, URI grants,
   package and other LocalServices. An existing remote Binder facade does not
   satisfy these in-process contracts. Reuse stock collaborators where feasible;
   integrate the hosted process/package infrastructure through narrow adapters.

If the preferred route fails a gate, record the observed dependency or behavior
and the smallest required adaptation. Do not quietly replace stock WMS, ATMS or
SurfaceFlinger with another host implementation and call the plan complete.

## Implementation milestones

### M0 — Baseline, source closure and test fixtures

- Record the source/hash baseline and run the existing checks. Inventory all
  window/graphics replacements, source patches, singleton targets and service
  registrations that will be retired.
- Trace native sources starting at `IGraphicBufferProducer.cpp`,
  the local `IGraphicBufferConsumer`/`IConsumerListener` headers, BufferQueue producer/consumer/core, `Surface.cpp`,
  GraphicBuffer, Fence, SurfaceComposerClient and LayerState. Use the pinned
  `libs/gui/Android.bp` lists to find AIDL, aconfig, sysprop, libui and utility
  dependencies. The `NO_BINDER` bufferqueue target is not the required transport.
- Run `tools/aosp-closure.py` on a live classes jar, seeding window clients and
  WMS/ATMS/input/display services together. Keep compile closure separate from
  runtime startup and LocalServices requirements.
- Add Kotlin fixture APK activities for two independent tasks, two activities
  sharing a task, handled/unhandled resize, saved state, dialog, SurfaceView,
  text editing and lifecycle journaling. Declare fixtures in the existing build
  and testkit, without application-specific framework branches.

Gate: reviewable dependency inventory, explicit host adapters and fresh baseline
artifacts. Future targets listed here are proposed until their milestone lands.

### M1 — Stock libgui source build over the existing Binder runtime

- Add a reusable native graphics build module, initially covering actual Bp/Bn
  producer/producer-listener interfaces and local consumer interfaces, BufferQueue, Surface and their libui
  dependencies. Keep a single shared copy of Binder utilities and native handles.
- Generate the real GUI AIDL interfaces and flags through typed declarations in
  `build/generator_sources.zig` and `build/generators.zig`. Add native flag/sysprop
  generation where missing; do not check in invented generated headers.
- Extend the Binder source facade only for contracts required by this closure:
  parcelables and containers, object attachment/lifetime, listener/delegator
  identity, caller/thread context, death and errors. Preserve bounds and FD
  limits; buffer batches must fail cleanly before partial export if too large.
- Adapt `BufferQueueThreadState` at the host boundary: AZKB has no HIDL Binder
  dispatch domain. Preserve the actual AZKB caller instead of returning the
  service process's Linux UID. Do not introduce a fake hwbinder runtime.
- Trace Surface's composer queries. Compile their real interfaces now; execute
  the independent BufferQueue operations first and defer composer-dependent
  behavior to M4. Unavailable queries return documented errors, not fabricated
  display statistics.
- Add producer/consumer cross-process tests, including a pure Zig Binder client
  regression sharing the runtime with generated C++ and Java clients.

Gate: the selected unmodified AOSP graphics sources compile and link against
production libraries; actual remote Bp/Bn calls, callbacks, identity, malformed
parcels and process death pass. Test output identifies source and library hashes.

### M2 — Shared host graphics allocation, import and synchronization

- Extract and extend the GBM-backed buffer functionality already in
  `platform_abi.zig`. GraphicBuffer allocator/mapper and AHardwareBuffer must
  converge on shared storage/lifetime without assuming their current pointer
  representations are interchangeable. Audit stock conversion/cast assumptions
  and update the C++/C boundary before enabling native consumers.
- Define a versioned host native-handle payload carrying dimensions, format,
  usage, layer count, plane count, strides, offsets and modifiers. Keep AOSP's
  GraphicBuffer/Fence outer serialization. Validate imported payloads, integer
  ranges, FD counts and backing sizes where queryable; never transport a GBM
  object pointer. Own imported resources independently of sender lifetimes.
- Implement allocation, import, free, map/unmap and capability queries. Start
  with the RGB/RGBA formats needed for UI; add the actual video/YUV formats for
  M10. Preserve usage, dataspace and layout metadata. Unsupported formats and
  protected usage fail explicitly.
- Negotiate render-device and compositor DMA-BUF format/modifier compatibility.
  Use linux-dmabuf feedback; account for device differences. A GPU conversion
  into a compatible output buffer is an explicit fallback, not CPU readback.
- Specify ownership for acquire/release fence FDs, duplicates and NO_FENCE.
  Import producer fences before GPU reads/writes and export completion fences.
  Cover import failure, cancellation, timeout, allocation exhaustion and teardown.

Gate: a second process imports an allocated buffer, sees verified GPU-written
pixels and waits on a real synchronization fence. Exercise supported CPU mapping
separately. Repeated allocation/import/destruction returns live handles and FDs
to baseline; malformed imports and unsupported requests leave no resources behind.

### M3 — One stock Surface/BufferQueue rendered to Wayland

- Run a native producer process using stock Surface/ANativeWindow and a separate
  consumer/presentation process using stock BufferQueue. Exercise the full
  dequeue/request/queue/acquire/release cycle and cache buffers by queue slot and
  generation according to AOSP semantics.
- Connect the host EGL/Skia render target to the acquired GraphicBuffer. Make
  producer completion visible through real fences; validate pixel content and
  changing frame identity. Buffer transport alone is not this milestone.
- Extend the existing Wayland DMA-BUF importer for task output rather than
  creating a separate demo Wayland client. Negotiate explicit synchronization
  when supported. Provide a correct fallback: wait for producer completion off
  the event thread and reuse only after compositor release according to the
  selected synchronization protocol. Frame callbacks do not release buffers.
- Keep queue pressure bounded. A blocked producer must not block Binder callback
  dispatch or Wayland event processing. Test withheld consumer release, dequeue
  timeout, resumed release, size changes with buffers in flight, producer crash,
  consumer crash and broker loss.
- Use `tools/shot.sh --runner` for the multiprocess fixture and retain compositor
  logs, queue/fence journals, resource counts and screenshots.

Gate: GPU-rendered frames cross process boundaries through stock BufferQueue and
AZKB and appear in one Wayland window. Resize and backpressure work, and every
failure path terminates or recovers without a leaked queue or hung peer. This
completes the immediate buffer-path milestone, not the full task-window project.

### M4 — Hosted SurfaceFlinger and per-task composition output

- Bring up a separately supervised SF process using the pinned transaction,
  layer-tree, scheduling and composition implementation and a host display/GPU
  backend. Publish the actual composer service interfaces over AZKB. Provide
  one hosted display and the display-event path needed by clients.
- Extend the libgui/Binder closure for SurfaceComposerClient, SurfaceControl,
  transaction completion, buffer release callbacks, permissions and frame events.
  Missing service identity or permission enforcement is a failure, not a reason
  to authorize every Binder peer as system.
- Compose a fixture layer subtree containing independently updating parent,
  child, crop, transform and alpha layers into a supplied output BufferQueue.
  Try mirror + private output/layer stack first. Prove no unrelated task layers,
  recursive mirrors or extra app-visible display IDs are introduced. If this
  fails, implement the smallest documented SF subtree-output extension.
- Preserve secure-layer exclusion, damage, dataspace, buffer retention and
  release synchronization. GPU composition is acceptable; continuous
  captureLayers/screenshot readback is not a production path.
- Drive scheduling from a real clock and Wayland presentation feedback where
  available. Keep compositor pacing, Android vsync requests, actual presentation
  timestamps and buffer-release events distinct. One stalled task output must
  not freeze other tasks or falsify timing reports.

Gate: two independent subtrees update through SF into two output queues, with
correct crop/alpha/transform and independent lifetime. Test transactional resize,
callback order, secure-layer handling and SF death before attempting WMS boot.

### M5 — Input/display infrastructure and stock WMS/ATMS boot

- Extend the existing hosted system-server bootstrap in pinned AOSP startup
  order. Bring up the concrete input/display and LocalServices dependencies of
  WMS/ATMS, including process attachment/launch, package state, URI grants,
  pending intents, policy, settings/resources, power and window animation threads.
  Reuse stock service implementations where possible; document each host adapter.
- Use the pinned WMS/ATMS container graph, global lock, TaskOrganizerController,
  transaction/lifecycle machinery and freeform policy. Remove duplicate hosted
  activity/task authority when registering stock ATMS. Retain host process
  spawning instead of requiring zygote, but connect it to real process records,
  IApplicationThread attachment, client transactions and death handling.
- Bring up stock input channels/dispatcher and window-info publication. Wayland
  supplies input at the host boundary; an evdev device reader is not required
  to invent a second path to the same input stream.
- Publish exactly one Android logical display with explicit metrics, density,
  refresh policy and capabilities. Ensure DisplayManager discovery does not
  publish the private SF task outputs. Enable pinned freeform/multiwindow
  resource/feature policy without enabling a second set of desktop decorations.

Closure note: the pinned InputManagerService seed is not an isolated Java
service import. The live-build inventory reaches WMS/AMS through Watchdog;
assuming Watchdog present still reaches WMS via WindowManagerPolicy →
DisplayRotation, and reaches AMS through Task. The inventory counts existing
host replacements as present, so it understates the source/contract work when
those replacements are removed. Restore the service graph and its collaborators
in the boot order above; an input-only compile does not prove WMS/ATMS readiness.

Gate: stock WMS/ATMS boots, accepts a real client window, manages task/activity
records and dispatches transactions through AZKB. No dummy successful startup
calls, competing service registrations or second task database remain active.

### M6 — Stock application window clients and HWUI cutover

- Seed closure for ViewRootImpl, WindowManagerGlobal/Impl, Surface/SurfaceControl,
  SurfaceSession, Choreographer, ThreadedRenderer, SurfaceView, TextureView and
  organizer/transaction value classes. Reuse AOSP JNI where it requires AOSP C++
  objects; implement host-specific JNI/state in Zig as the native policy requires.
- Replace each current client shim with its stock source only when its service,
  JNI and native object contracts are ready. Migrate ActivityThread's host window
  adapter and ApkHost launch path to the stock session/window lifecycle while
  preserving existing launch, result, relaunch and saved-state behavior.
- Route HWUI output through the M3 buffer backend and M4 composition, replacing
  the direct global Wayland render target. Keep exact AOSP native signatures,
  exception behavior, Surface validity and release semantics.
- Update source sets, generators, stub audit/budget and package hash in the same
  changes. Never compile a replacement and stock class under the same FQN.

Gate: one real APK runs on the stock window client/service/graphics path. Window
attach/detach, draw reporting, dialogs, input and handled/unhandled resize pass;
the existing full APK suite remains free of runtime issues.

### M7 — Task shell, synthetic geometry and multiple toplevels

- Add Kotlin `WaylandTaskShell` with production and in-memory presentation
  adapters. It owns organizer registration and initial task list, appearance,
  changes, disappearance, output creation and cleanup. Deduplicate callbacks by
  task token/generation and preserve leash ownership through transitions.
- Refactor `wayland.zig` into session connection/seat/output ownership and
  per-task surface state. Refactor `src/main.zig` and renderer singleton state so
  presentation is not tied to whichever APK happened to start the session.
- Implement a deterministic bounded synthetic rectangle allocator with stable
  origins, checked arithmetic and non-overlap. Prefer in-place resize; relocate
  only when necessary, using a coordinated task transaction. Define exhaustion,
  reuse, task restoration and oversized/non-resizable task policy. Synthetic
  changes must not be inferred from desktop movement. Preserve WMS's window
  container/buffer-child hierarchy: SF uses parent transforms for buffer-layer
  input geometry. Applying task position/scale to the buffer itself is not an
  equivalent input transform; the native SF input gate covers this distinction.
- Assign task bounds through WindowContainerTransaction. Map each organized
  application task to one toplevel and an M4 output. Keep home/system roots from
  accidentally becoming ordinary app windows. Map fullscreen/maximize/restore
  to Android task bounds/windowing policy; closing a toplevel requests task
  removal and lets Android run lifecycle callbacks before final destruction.
- Use compositor decorations when available; define a minimal host decoration
  fallback without duplicating Android desktop chrome. Keep task title/app ID
  and activation requests tied to authoritative task state and valid serials.

Gate: two tasks on the same display have separate rendered toplevels, stable
non-overlapping synthetic bounds, correct multi-activity grouping and independent
close/restore. Geometry tests cover capacity, adversarial resize and removal.

### M8 — Input, focus and visibility

- Route each Wayland event through its surface identity to the current task
  generation and then through stock Android input channels/window hit testing.
  Map surface-local coordinates through scale, viewport/crop and any letterbox
  transform before adding the synthetic origin. The simple origin-plus-local
  formula is valid only when those spaces have unit scale and no translation.
- Keyboard seat focus determines Android keyboard/task focus. Pointer enter
  selects a pointer target and must not steal keyboard focus on hover. Activated
  toplevel state is presentation information, not proof of input focus. Track
  touch targets through their gesture and cancel on target loss. Pinned
  InputDispatcher retains an active touch stream when its window becomes
  invisible; removal cancels it. Suspension/unmap actions that require gesture
  cancellation must explicitly end the input stream and cannot rely on hiding
  the SF surface. Match the input-window UID to its buffer producer so stock
  untrusted-overlay checks remain effective. Handle key
  release/repeat, capture, relative motion, stylus, wheel and seat removal.
- Update WMS focus and stock input window metadata coherently. Validate hit
  testing when synthetic rectangles are far apart and desktop windows overlap;
  do not bypass modal windows, touchable regions or trusted-input checks merely
  because Wayland identified the task.
- Keep mapped tasks conservatively visible when occlusion/minimization is
  unknown; apply pinned multi-resume policy, rather than forcing every activity
  resumed. Define suspension/unmap handling and optional compositor state inputs.

Gate: events reach the intended window with correct local and raw coordinates;
hover does not change keyboard focus; focus/cancel/lifecycle ordering matches
AOSP. Closing or crashing a focused task leaves no stuck keys or captured seat.

### M9 — Configure, frame pacing, scale and output changes

- Track configure serials and a separate content/configuration generation per
  task. Apply the latest selected compositor size via Android task configuration,
  respect handled versus relaunch behavior, and commit compatible content after
  acknowledging the applicable configure. Coalesce obsolete requests without
  losing terminal acknowledgements. Buffer dimensions alone are not proof that
  Android handled the corresponding configuration.
- Define what is shown while a new frame is pending and how stalled/dead clients
  are handled. Ensure stale frames cannot overwrite the new generation and old
  buffers remain owned until released. Task relocation and destruction must
  invalidate pending input/configuration work safely.
- Track all outputs and per-surface preferred scale/transform. Start with stable
  Android logical density and presentation scaling per task; test whether pinned
  task overrides justify optional per-task density changes later. Never mutate
  the shared display density whenever one toplevel moves between outputs.
- Negotiate fractional scale/viewport/presentation capabilities and fallback
  behavior. Distinguish requested frame rate from actual output scheduling;
  throttle inactive outputs without deadlocking Android's draw lifecycle.

Gate: resize storms, fullscreen/maximize/restore, fractional scales, mixed-scale
outputs and output unplug preserve Android configuration, input transforms,
frame ordering and bounded resource usage. Test delayed frames and fences.

### M10 — Media, IME and desktop interoperability

- Move SurfaceView/TextureView and FFmpeg decoder output into Android's surface
  hierarchy and the shared buffer path. Preserve crop, transform, color metadata,
  video/UI ordering and synchronization. Retire the independent per-app Wayland
  video subsurface shortcut after parity tests pass.
- Connect stock input-method client/service contracts to a hosted text-input
  bridge. Negotiate Wayland text-input support; implement preedit, commit,
  surrounding text, selection/deletion and focus loss with the proper UTF index
  conversions. Preserve Android editor actions and composition. Define a
  keyboard-only fallback and the insets policy where desktop IME geometry is
  unavailable; do not fabricate a screen-space IME rectangle.
- Connect the hosted clipboard service to Wayland data offers with MIME
  negotiation, seat serial rules, bounded asynchronous FD transfer, cancellation
  and ownership loss. Handle permissions and avoid clipboard echo loops.
- Implement Android-to-Android and Android/native drag-and-drop using Wayland
  surface-local targeting and data offers, including drag icons, URI grants,
  accepted actions, cancellation and process death. Do not infer movement through
  global desktop coordinates.
- Define and implement task-bound system-window/tooltip/popup policy and
  explicit behavior for task-spanning overlays, PiP, transitions and IME
  surfaces. Preserve secure-window rules and permission checks. Features that
  core Wayland cannot represent need a documented failure or constrained host
  presentation policy; they must not silently paint into an unrelated task.
- Scope accessibility trees, actions and bounds by task/window. Keep Android
  synthetic coordinates as the Android contract and label test/host local
  coordinates explicitly. Never present them as real global desktop positions.

Gate: text composition and editing, clipboard, cross-task/native drag-and-drop,
dialogs/popups, video and accessibility work through task-addressed integration
tests. Unsupported protocol combinations have verified, observable fallback
behavior. Existing NewPipe fullscreen, navigation and playback tests pass.

### M11 — Recovery, default cutover and removal of legacy paths

- Test startup failure and shutdown at each process boundary, app/producer/
  consumer death, organizer death, graphics-service restart, compositor loss,
  broker loss and GPU allocation/import failure. Recover the session when the
  contracts allow it; otherwise terminate affected clients with explicit Android
  errors and clean resources rather than reconnecting stale Binder/surface IDs.
- Bound queue depth, outstanding frames, callbacks, retries and cached imports.
  Run repeated launch/resize/destroy and pressure tests with FD/GPU-memory
  accounting, starvation/deadlock checks and delayed release fences.
- Make the task path the default. Remove the old window stack, fake surface
  implementations, global render target and superseded source patches only after
  their replacements pass all gates. Temporary development selection must select
  one complete service stack, not mix incompatible old/new object lifetimes.
- Update architecture, native boundary, source audit and user-facing capability
  documentation. Keep reproducible test commands and retained artifacts.

Gate: the default launcher completes the entire acceptance matrix below with no
required legacy graphics/window path and no unexplained runtime issues.

## Ordering and review boundaries

The critical path is M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9 → M10 → M11.
Source-closure work for M5, pure geometry tests for M7 and testkit task addressing
can be prepared while native graphics is being brought up. Actual service/window
cutover waits for its dependencies. M4 must prove composition before WMS/ATMS
integration commits to a particular per-task output mechanism.

Split milestones into reviewable source/build, host implementation, integration
and regression commits as needed. Each AOSP manifest change carries a regenerated
archive hash in the same commit; use an isolated packaging checkout if another
session is modifying the full checkout. Run audits at each native/JVM boundary
change. Do not expand every Binder or HAL API speculatively ahead of its consumer.

## Verification and definition of done

Add focused build targets as milestones land, for example `gui-test`,
`graphics-buffer-test`, `wayland-buffer-test` and `task-shell-test`. `gui-test`, `graphics-buffer-test`, `surface-wayland-test` and
`window-fixture` now exist; other names here remain proposed. Pure queue/geometry/state tests must run
without a display or GPU; hardware tests must report required capabilities and
must not count a skipped GPU path as a passing hardware gate.

Use the existing Cage harness for single-output DMA-BUF/HWUI regressions. Cage's
kiosk behavior cannot prove independent desktop placement, overlapping toplevels
or mixed-output behavior. Extend `tools/shot.sh` with an explicit compositor
selection for a headless desktop compositor for those cases, retaining the same
artifact and test-control conventions. Do not add a second headless runner.
Also run a desktop protocol smoke against supported real compositors.

Extend `azk-test`/the testkit with stable task/window selectors and session-level
diagnostic collection. Cross-process service/native failures must enter the
structured journal, not disappear into a separate stderr file. Every UI action
under test uses a step with its own mark/settle boundary. Add bounded settle
conditions for configuration applied, frame presented and task removed.

| Contract | Required evidence |
| --- | --- |
| Shared Binder runtime | Zig, generated C++, NDK and Java clients coexist; references/FDs and errors survive cross-process calls |
| Graphics | Verified changing GPU pixels through stock BufferQueue and SF; no continuous CPU capture; format/modifier and synchronization tests |
| Tasks | Two independent tasks, same display ID, stable bounds; several activities share one toplevel; proper launch modes and results |
| Windows | Dialog, popup, SurfaceView/video, alpha/crop/transform and secure-layer policy |
| Input | Real overlap, focus changes, touch cancellation, keyboard repeat/capture, scale/letterbox transforms and trusted-target checks |
| Configuration | Handled/unhandled resize, saved-state relaunch, stale frames, resize storms and callback ordering |
| Outputs | Fullscreen/restore, fractional scale, mixed outputs, output unplug and no accidental global density changes |
| Desktop bridges | IME preedit/commit, clipboard, DnD/URI grants, accessibility and explicit unavailable-capability behavior |
| Resources/recovery | Slow peers, queue exhaustion, delayed fences, task/service/compositor death; bounded resources and no hung peers |

Run the smallest affected check first, then the existing regression gates:

```sh
nix develop ./artzig -c zig build -j6 build-tools-test graph-audit cpp-audit
nix develop ./artzig -c zig build -j6 native-test binder-cpp-test binder-broker-test binder-test system-test jvm-test
nix develop ./artzig -c env AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j6 integration-test
```

Supply the host Mesa EGL/GBM/VAAPI paths when required by this environment and use
`WLR_RENDERER=gles2`. Keep the pinned APK SHA check. Inspect `steps.jsonl` and
`app.log` on every integration failure, plus failure/a11y/screenshot artifacts.
Passing requires expected state and empty `diagnostics.runtime.issues` for every
step; `--allow-missing` is not a regression pass. Retain native queue/fence logs
and source/library hashes alongside UI artifacts.

## Pinned source anchors

Paths below are relative to the full checkout identified by `aosp/README.md`.
They are behavioral references even when not yet in the curated package:

- `frameworks/native/libs/gui/Android.bp`: bufferqueue source lists, Binder and
  HIDL dependencies, generated interfaces and flags.
- `frameworks/native/libs/gui/{BufferQueueProducer,BufferQueueConsumer,BufferQueueCore,BufferQueueThreadState,Surface}.cpp`:
  queue state, ownership, caller identity and composer-dependent Surface methods.
- `frameworks/native/libs/ui/{GraphicBuffer,Fence}.cpp`: native-handle and fence
  transport, import and release contracts.
- `frameworks/native/libs/gui/SurfaceComposerClient.cpp`: `mirrorSurface`,
  `Transaction::setDisplaySurface` and `Transaction::setDisplayLayerStack`.
- `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`: `mirrorLayer`,
  `createVirtualDisplay`, display state and composition lifecycle; continue into
  the pinned CompositionEngine/RenderEngine backends during M4.
- `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`:
  `main`, construction, policy initialization and the shared ATMS lock.
- `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java`:
  `initialize`, `onActivityManagerInternalAdded`, `setWindowManager`, process and
  LocalServices collaborators.
- `frameworks/base/core/java/android/window/{TaskOrganizer,WindowContainerTransaction}.java`:
  registration, task callbacks, bounds/reparent transactions and leash ownership.
- Wayland protocol XML selected by `build.zig`, plus the protocol XML for newly
  negotiated extensions from the development environment's pinned protocol
  package. Validate protocol requests, serials and fallback behavior against
  those definitions, not assumptions from an unrelated Android or compositor version.
