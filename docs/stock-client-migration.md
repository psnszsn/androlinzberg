# Stock client migration: bulk instructions

Goal: every class an application process runs on is the verbatim AOSP class,
except the seam that talks to PipeWire, Wayland, FFmpeg and EGL. That seam
stays exactly as it is until everything else is stock. The hosted services in
`src/java/dev/artzig/system` are the server side and remain host-owned.

Read `AGENTS.md` first; every rule there applies. This document only says what
to do, in what order, and where the traps are. State of the tree when this was
written: 225 stubs, pending budget 105, `docs/stubs.md` regenerated.

## Follow-up scope (2026-09-05)

The original batches reached the fence below; that is not completion of the
stock-client goal. The follow-up authorizes replacing the remaining activity
core, service-backed clients, isolated pending classes, and test machinery in
`android.*`. Java callers previously covered by the fence may now be changed
or replaced. Move necessary host behavior to service or native seams and keep
the pinned AOSP client behavior.

Wayland, PipeWire, and FFmpeg backend implementation work is out of scope.
Preserve those existing backends. A replacement that requires new behavior in
one of them remains a documented dependency, not a completed stock migration.
EGL was not excluded from the follow-up. The audit ratchet, AOSP closure check,
reproducible packaging, and module and full APK regression requirements remain
in force.

Follow-up changes in the current tree:

- The audio transport foundation now generates 66 pinned AIDL inputs from
  frameworks/av and the shared audio HAL schema. AudioHalVersionInfo,
  AudioRoutesInfo and AudioPresentation compile verbatim. Module tests cover
  nested configuration and address unions, invalid Parcel tags and sizes,
  presentation metadata and validation, and ordered player commands through the
  generated proxy/Stub protocol. JVM/system suites, native/Binder/build checks
  and all 11 APK tests pass (37 steps without runtime issues). All 69 selected
  source files already match the pinned AOSP archive; both tarballs remain
  current without repackaging. The inventory remains 44 stubs (26 pending,
  18 survivors). This supplies transport and metadata, not an AudioService or
  new playback, recording or HAL backend.

- UsbManager, UsbDeviceConnection, UsbRequest and the port/accessory closure now
  compile verbatim, with generated USB Binder interfaces, HAL enums, HIDL constants
  and release flags. The original connection/request JNI and usbhost JNI helper
  use the existing AOSP Linux USB library. The stock registry preserves missing-
  service behavior. Module tests exercise proxy/Stub device transport, descriptor
  duplication and reads through JNI, rejected ioctls on a regular file, closed
  connections, request lifecycle, port role bits and Parcel values. JVM/system,
  native/Binder/build checks and all 11 APK tests pass (39 steps without runtime
  issues), including the launcher fullscreen fix below. All 35 new manifest inputs
  match pinned AOSP; AOSP is rebuilt and pinned, ART is unchanged. The inventory
  is 44 stubs: 26 pending and 18 survivors. No USB discovery/permission service is
  hosted yet, and this container has no USB device nodes; the descriptor fixture
  does not claim a physical USB transfer.

- The launcher now synchronizes fullscreen requests after input/Handler work even
  when it submits no Java frame. A window-state change after the last rendered
  frame previously left the compositor unchanged, causing intermittent desktop
  video fullscreen timeouts. The desktop regression now explicitly pauses playback
  before entering fullscreen. The focused test and all 11 APK tests pass (39 steps
  without runtime issues); temporary state tracing is removed. This changes the
  launcher boundary, not the existing Wayland backend.

- TextToSpeech, TtsEngines, UtteranceProgressListener and Voice now compile
  verbatim, with five generated Binder interfaces and the stock TTS manager,
  per-user session service and shared server infrastructure. SystemServices
  starts the AOSP manager. Virtual-device playback uses the pinned absent-service
  session value; VirtualDevice itself is a Javadoc-only SDK header and is barred
  from runtime references. A separate-process protocol fixture verifies engine
  discovery and binding, missing-engine failure, voice transport, ordered progress
  and error callbacks, rate/pitch parameter merging, file-descriptor ownership and
  shutdown. JVM/system suites, native/Binder/build checks and all 11 APK tests pass
  (37 steps without runtime issues). The inventory is 45 stubs: 26 pending and
  19 survivors. All six new archive inputs match pinned AOSP; AOSP is rebuilt and
  pinned, and ART is unchanged. This supplies the client/session protocol, not a
  synthesizer: TextToSpeechService still needs the media playback closure, and no
  production speech engine is installed.

- NetworkPolicyManager and NetworkPolicy now compile verbatim, with their
  generated Binder interfaces and platform flags. The hosted policy service
  exposes the default unrestricted state, preserves permission and UID validation
  ordering, and rejects changes that need Android firewall or carrier enforcement.
  Applications obtain a cached stock manager through SystemServiceRegistry.
  Module tests cover policy thresholds, snoozes, Parcel round trips, empty policy
  state and rejected mutations; separate-process tests cover service lookup and
  denied privileged queries, mutations and factory reset. The inventory is
  46 stubs: 27 pending and 19 survivors. The six new archive inputs match pinned
  AOSP; the AOSP tarball is rebuilt and pinned, and ART is unchanged.
  JVM/system suites, native/Binder/build checks and the final full APK suite pass.
  Two earlier desktop fullscreen runs timed out without runtime issues; three
  traced retries and the final suite passed. The timeout is not claimed fixed;
  temporary tracing was removed and no presentation code changed in this batch.

- TrafficStats, NetworkStatsManager and the statistics/template/history closure
  now compile verbatim, with generated statistics Binder interfaces and proto
  constants. A hosted netstats service supplies Linux interface/total byte and
  packet counters; missing interfaces and unavailable UID accounting follow the
  stock UNSUPPORTED result. Activity startup initializes the stock client before
  application code. The stock socket tagger replaces HostedTrafficSocketTagger;
  its JNI boundary preserves descriptor errors and the absent-netd result while
  retaining the explicit missing-accounting diagnostic. Historical queries and
  provider/UID accounting remain unsupported backend work. Module tests cover
  snapshots, overflow, unavailable counters, Parcel transport, thread tag/UID
  isolation and JNI errors; a separate-process test observes a real 4 KiB UDP
  transfer through Binder. JVM/system suites, NewPipe launch, native/Binder/build
  checks and all 11 APK tests pass (38 steps without runtime issues). All 18 newly
  selected Java sources match the existing archive; both tarballs remain current
  and unchanged. The inventory is 47 stubs: 27 pending and 20 survivors.

- AppOps prerequisites now include verbatim PermissionGroupUsage and generated
  AppOpEnums and SettingsLib flags. The latter uses the pinned bp2a read-only,
  enabled WRITE_SYSTEM_PREFERENCE permission value. Parcel tests preserve UID,
  timestamps, permission/device identity, state and optional attribution labels;
  the release flag is checked as well. JVM tests and build/C++/graph audits pass.
  All four inputs match pinned AOSP. The AOSP archive is rebuilt and pinned;
  ART remains current and unchanged. This is dependency preparation: AppOpsManager
  itself remains pending, and the inventory stays at 48 stubs.

- Activity.finishAffinity now uses the pinned ActivityClient call and result/
  embedded-activity guards. The hosted controller closes only the contiguous
  matching-affinity range at and below the caller, preserving a different
  affinity boundary and activities above it; equal null affinities also match.
  Group finishing queues canceled results for surviving recipients before their
  next resume. Module and separate-process tests cover covered and foreground
  callers, task preservation, null affinities, rejected results and exactly-once
  result ordering. JVM/system suites, the NewPipe launch regression, native,
  Binder and build checks, and all 11 APK tests pass (37 steps without runtime
  issues). Both tarballs are unchanged and current; the inventory stays at 48.

- Activity now inherits the verbatim ContextThemeWrapper, replacing its private
  theme/inflater implementation and empty applyOverrideConfiguration method.
  Stock resource isolation, one-time override checks, layered theme identity,
  explicit theme replacement and inflater context now apply to activities.
  ActivityThread combines the app override with server configuration before
  resource updates and callbacks, including the host configuration entry point;
  caller-owned configurations remain unchanged. Module tests cover themes and
  resources, both configuration paths and null initial server overrides. JVM
  and separate-process system suites, the NewPipe launch regression, native,
  Binder and build checks, and all 11 APK tests pass (37 steps without runtime
  issues). Both tarballs
  remain current and unchanged; the inventory stays at 48 stubs. Activity and
  ActivityThread still require the remaining stock activity/service closure.

- PerformanceHintManager and WorkDuration now compile verbatim from pinned AOSP.
  The eleven-method native adapter follows APerformanceHintManager::create's
  unavailable-service path: no Android hint manager, Power HAL or FMQ is hosted,
  so Java throws ServiceNotFoundException and no manager/session handles are
  issued. Invalid hidden handles fail explicitly instead of accepting fake hints.
  The service registry uses the stock registration and app-versus-system logging
  policy for missing services. Tests cover acquisition failure, argument checks,
  work-duration values/equality, rejected setter state and closed sessions.
  JVM/system suites, the NewPipe launch regression, native/Binder/build checks
  and all 11 APK tests pass (39 steps without runtime issues). The AOSP archive is rebuilt
  and pinned; the ART archive is unchanged and current. The inventory is now
  48 stubs: 27 pending and 21 survivors. This does not provide performance or
  power management, and the broader activity/service migration remains open.

- Activity attachment now captures the pinned launch component and UI thread.
  getComponentName/getLocalClassName use that component, including aliases,
  instead of a hard-coded package and runtime class; setIntent does not change
  activity identity. runOnUiThread uses AOSP's immediate-on-UI/post-from-worker
  behavior and final signature. Direct hosted attachment supplies its missing
  resolved component before calling the standard entry point. Relaunch now
  follows ActivityThread.performLaunchActivity: attach with the original launch
  intent, then restore the application's custom intent. This preserves both
  identity and getIntent even when the custom intent has no component.
  Module and separate-process tests cover alias/name boundaries, dispatch
  threading/order and recreation with a custom intent. JVM and system suites,
  the NewPipe launch regression, native/Binder/build checks and all 11 APK tests
  pass (37 steps without runtime issues). Both tarballs remain unchanged and
  current; the stub inventory stays at 49.

- The hosted activity controller now follows ActivityStarter/Task task reuse:
  component and document matches precede affinity candidates; repeated launcher
  intents preserve the existing top screen; changed intent filters add an
  activity; MULTIPLE_TASK creates a distinct task. SINGLE_TOP, CLEAR_TOP,
  CLEAR_TASK, REORDER_TO_FRONT and document policies select creation versus
  new-intent delivery while preserving task identity. Pending canceled results
  precede new intents and resume, and removed activities are destroyed after
  the replacement resumes. Explicit NEW_TASK result requests are canceled
  immediately. Activity.getTaskId, isTaskRoot and moveTaskToBack now use the
  stock ActivityClient transport instead of placeholder values. The task URI
  fixture supplies the real hosted package-manager dependency for document
  intents. Module and separate-process regressions pass, covering identity,
  alias normalization, affinity precedence, task/root/back queries, new-intent
  ordering, clearing and canceled results. The NewPipe launch regression and
  all 11 APK tests pass (37 steps without runtime issues), along with native,
  Binder and build checks. Both tarballs remain unchanged and current, and the
  inventory stays at 49 stubs. This closes common task reuse; it does not replace
  Activity/ActivityThread wholesale or implement the full WM task policy.

- Image and PublicFormatUtils now use verbatim AOSP Java, with the original
  format JNI and libui PublicFormat.cpp. Image's former compile-only SDK header
  is removed; ImageWriter remains Javadoc-only and barred from runtime use.
  Native public/HAL conversions now preserve JPEG, HEIC, depth and raw format
  distinctions instead of returning identity formats and unknown dataspaces.
  The Dataspace header comes from the pinned VINTF-stable AIDL's NDK backend.
  Tests cover crop clipping/copies, dataspace, closed-image behavior, fence
  defaults, format round trips and unknown-format fallbacks. JVM, system,
  native, Binder and build checks pass, together with all 11 APK tests
  (37 steps without runtime issues). This supplies the abstract Image base
  contract, not ImageReader/ImageWriter producers or codecs. All selected
  sources were already packaged and match pinned AOSP; both tarballs remain
  current and the inventory stays at 49 stubs.

- Activity component callback registration now follows the pinned compatibility
  rule and uses stock ComponentCallbacksController. Modern applications own
  callbacks on the activity, including registration before attachment; older
  applications retain application-level delivery. Configuration callbacks
  follow activity lifecycle notifications, and low/trim-memory callbacks follow
  fragment dispatch. Empty registration methods are removed. Regressions cover
  both compatibility modes, ownership, ordering, memory callbacks and removal.
  JVM, system, native, Binder and build checks pass, with all 11 APK tests
  (37 steps without runtime issues). The inventory stays at 49 stubs and both
  tarballs are unchanged and current.

- Process configuration dispatch now uses verbatim AOSP ConfigurationController
  and ActivityThreadInternal. The controller owns pending configurations,
  resource updates, locale selection, density, theme rebasing and component
  callbacks. The hosted thread supplies the application/service/provider list,
  deduplicating providers by identity, and retains its existing activity resize
  and recreation boundary. Activity now declares its stock ComponentCallbacks2
  interface. Regressions cover resource-before-callback ordering, service
  delivery, equivalent-update suppression and pending updates; existing stale
  sequence, handled/unhandled configuration and saved-state/lifecycle checks
  also pass. Lifecycle fixtures now supply real configuration changes instead
  of expecting callbacks from a change mask alone. JVM, separate-process,
  native, Binder and build checks pass, as do all 11 APK tests (38 steps with
  no runtime issues). Both sources were already packaged and match pinned AOSP;
  both tarballs remain current. The stub inventory remains 49.

- MediaCrypto now uses verbatim AOSP Java. Its seven-method Zig JNI adapter
  follows JCrypto's no-plugin path because the host has no Android AIDL/HIDL
  DRM factories: capability queries return false and construction throws the
  pinned NO_INIT MediaCryptoException. UUID validation, null-argument failures,
  released-object errors and failed-constructor cleanup retain the stock
  contract. This provides no decryption implementation and changes none of the
  excluded media backends. Java, JVM, system, native, Binder and build checks
  pass, together with all 11 APK tests (39 steps without runtime issues).
  The AOSP archive is rebuilt and pinned; the ART archive remains unchanged
  and current. The inventory is 49 stubs: 27 pending and 22 survivors,
  with 42 outside com.android.server (20 pending, 22 survivors).

- Finishing an activity now queues its result on the recipient independently
  of the activity that becomes visible. A covered recipient keeps its results
  until its own next resume, following ActivityRecord.finishActivityResults;
  the controller reveals the actual top activity instead of trying to resume
  the covered recipient and dropping the result. The cross-process regression
  finishes two children while their recipient remains covered and verifies
  exactly-once, ordered callbacks before onResume. JVM and system tests pass,
  as do the launch regression, native/Binder/build checks and all 11 APK tests
  (37 steps without runtime issues). Both tarballs remain current and unchanged;
  the inventory stays at 50 stubs. Task reuse is covered by the later batch above.

- Activity launch/calling identity queries now use the pinned ActivityClient
  transport. The controller records each delivery's share-identity choice and
  applies the stock compatibility, same-UID, system-UID and platform-signature
  checks to Activity and ComponentCaller getters. New intents retain their own
  sharing decisions; result callers cannot opt into sharing. Calling activity
  and package are exposed only for starts that requested a result. Parsed
  package metadata now carries the platform-signature flag following
  ScanPackageUtils, including packages installed before the platform.
  Two new fixture apps use a separate public test key so cross-process tests
  exercise restricted callers, alongside the platform-signed fixture exception.
  Tests cover hidden/shared identity, same-UID access, new intents, recreation,
  result callers and removed activity records. Java, JVM and system tests pass,
  along with native, Binder, build checks and all 11 APK tests (37 steps
  without runtime issues). The inventory stays at 50 stubs; both tarballs are
  unchanged and current. The later batches above
  cover task reuse and deferred results for covered recipients.

- GLSurfaceView now uses verbatim AOSP Java, including GLThread, EGL helpers,
  debug wrappers, GLU and Matrix. Legacy EGLImpl and its Java handles/interfaces
  are also stock. Its 28-method Zig JNI adapter follows the pinned native
  contract over the existing EGL backend, including EGL 1.0 version reporting,
  handle/configuration arrays, errors and unsupported pixmap behavior. Display
  initialization is reference-counted across legacy EGL and EGL14 callers,
  preserving resources until the last owner terminates the display.
  Tests cover concurrent owners, pbuffer pixels, renderer callback ordering,
  queued work, dirty-mode redraws, pause/resume with and without preserved
  contexts, surface replacement, and render-thread cleanup. The GLSurfaceView
  test uses its public EGLWindowSurfaceFactory to create real pbuffers;
  default native-window presentation still needs an excluded host compositor
  adapter. Java, JVM, separate-process system, native, Binder and build checks
  pass, as do all 11 APK tests (37 steps without runtime issues). All 20 new
  Java sources were already packaged and match AOSP byte for byte; both
  tarballs remain current. The inventory falls to 50 stubs: 27 pending and
  23 survivors, with 43 outside com.android.server (20 pending, 23 survivors).

- The pinned GLImpl and four remaining Khronos GLES1 interfaces now compile
  directly with AOSP's GLImpl JNI. Native patch 32 retains the original JNI
  array bases for positioned heap buffers, guards failed acquisitions, and
  converts existing typed-buffer bounds checks from bytes to elements. The
  Zig native ABI supplies the Android Bounds exports and resolves GLES
  extensions through GLVND; core GLES1 calls use its GLESv1_CM library.
  Regression coverage exercises fixed-function pixels, direct and heap buffer
  positions/slices, texture and viewport bounds, OES framebuffer pixels, and
  OES matrix queries with independently positioned outputs. Java, JVM,
  separate-process system, native, Binder and build checks pass, along with
  all 11 APK tests (38 steps without runtime issues). The first APK run hit a
  transient NewPipe live-feed parse failure with no framework issues; the
  complete rerun passed. The Androlinzberg AOSP
  archive was rebuilt and its content hash pinned; the ART archive is
  unchanged and current. The inventory remains 51 stubs: this closes GLImpl's
  native prerequisite, while stock GLSurfaceView still needs legacy EGLImpl.

- EGL15, EGLExt and EGLImage now use pinned AOSP Java, with the original EGL15
  JNI. EGLExt's native adapter follows the pinned JNI handle/exception contract
  and resolves Android extensions through Mesa/GLVND; the original JNI requires
  unavailable Android GLConsumer definitions and libEGL extension exports.
  Tests cover real sync waits, output-array offsets, error returns, texture-image
  lifetime, and an exported GPU sync_file that survives destruction of its
  EGLSync and original SyncFence owners. Java, JVM, separate-process system,
  native, Binder and build checks pass, along with all 11 APK integration tests
  (38 steps without runtime issues). The stock sources were already packaged;
  both tarballs remain current and the inventory stays at 51 stubs. Native-window
  presentation remains at the existing backend boundary.

- The closure audit now resolves wildcard imports and treats legacy Khronos
  APIs as framework sources instead of assuming they are in libcore. Static
  wildcard imports retain their declaring class. GLSurfaceView's live closure
  now includes the EGLImpl/GLImpl Java dependencies and reports their JNI
  blockers. The refreshed inventory remains 51 stubs; build-tool regressions
  pass. This corrects migration accounting without changing runtime behavior.

- SyncFence and its JNI now use verbatim pinned AOSP sources, backed by the
  stock native Fence/libsync implementation. The stock EGLSync handle is also
  selected. Two Javadoc-only camera imports use exact headers from the pinned
  SDK; a mandatory bytecode audit rejects packaging those headers or any runtime
  reference to them. No camera implementation enters the runtime. Tests exercise
  invalid/empty fences, descriptor transfer and duplication, shared copies,
  close idempotence, poll readiness, and Parcel descriptor lifetime through JNI.
  Java, JVM, separate-process system, native, Binder and build checks pass, along
  with all 11 APK integration tests (38 steps without runtime issues) and the
  hardware-buffer Canvas/pixel-readback probe. The rebuilt AOSP tarball includes
  the SDK header source archive and notice; the ART tarball is unchanged.

- ComponentCaller now checks immutable delivery-time URI access snapshots for
  launches, new intents, and activity results. The hosted controller follows
  stock ActivityCallerState, including nested ClipData/EXTRA_STREAM, separate
  read/write access, denied versus absent URIs, and the receiver's current-access
  requirement. ActivityThread retains the initial caller token through launch
  and recreation. Starting a child before the parent's resume acknowledgement
  now preserves the parent and its result delivery. JVM and separate-process
  regressions pass, along with all 11 APK integration tests (38 steps without
  runtime issues). The inventory remains 52 stubs; package inputs are unchanged
  and both tarballs remain current. Task reuse and
  identity-sharing gates are covered by the later batches above.

- PerfettoTrace and PerfettoTrackEventExtra now use their verbatim pinned AOSP
  sources, backed by the original JNI, libtracing_perfetto, and Linux Perfetto C
  SDK. Zig builds the SDK and its three protoc plugins; all 648 generated native
  message files match the original GN/Ninja output. A small native adapter fixes
  the pinned register method's FastNative calling convention. OS flags use the
  stock generator's read-only default permissions, preserving all 50 constants
  and their defaults while avoiding a DeviceConfig lookup before SystemServer
  publishes the activity service. Runtime DeviceConfig overrides for this flag
  package are consequently disabled. The in-process trace regression decodes
  typed arguments, slices, counters, flow IDs, and category filtering. JVM,
  system, native, Binder and build checks pass, along with all 11 APK integration
  tests (38 steps without runtime issues). The rebuilt AOSP tarball includes the
  native SDK and generators. The inventory is 52 stubs: 28 pending, 24 survivors.
  No system tracing daemon is added; the SDK's system IPC backend remains
  dependent on an external Perfetto service.

- Activity launch, new-intent and result URI permissions now flow through the
  stock `UriGrantsManagerService`. Grants are checked before activity state
  changes and applied before client delivery. Each activity record has a stock
  `UriPermissionOwner`; recreation preserves it, while final removal, canceled
  launch cleanup and process-record removal release it. Persisted grants keep
  their independent lifetime. `Activity.finishAndRemoveTask` now dispatches the
  pinned root-task finish mode through the shared finish path, including its
  stock autofill finish callback. Separate-process tests cover data and nested
  ClipData, unauthorized grants, read/write isolation, recreation, new intents,
  shared owners, results, persistence, and root versus non-root task removal.
  JVM, system, native, Binder and build checks pass, along with all 11 APK
  integration tests (38 steps without runtime issues). The inventory remains
  53 stubs at that checkpoint. Delivery-time caller snapshots are now implemented
  as described above.

- R8 keep annotations now come from AOSP's pinned `keepanno-annotations` Java
  import. The three handwritten KeepTarget, TypePattern and UsesReflection
  stubs are deleted; all 22 original annotation classes are included and match
  the pinned artifact byte for byte. The compiler itself stays outside the
  framework classpath. The rebuilt AOSP package includes the jar and its license.
  Java, JVM, system, native, Binder and build checks pass, along with all 11
  integration tests (38 steps without runtime issues). The inventory is now
  53 stubs: 28 pending and 25 survivors.

- EmulatorClipboardMonitor now uses its pinned AOSP source. ClipboardService
  already selects it only for an emulator, and hosted SystemServer verifies
  `Build.IS_EMULATOR` is false. The replacement therefore needs no QEMU/vsock
  endpoint or desktop clipboard integration. Existing cross-process clipboard
  reads, writes and listeners pass, along with JVM, system, native, Binder and
  build checks and all 11 integration tests (38 steps without runtime issues).
  The inventory is 56 stubs: 28 pending and 28 survivors.

- HWUI now uses stock native libui Fence and Linux libsync, replacing the
  hostgraphics fence placeholder. Every native translation unit sees the same
  stock layout. Tests cover timeouts, readiness, shared/duplicate ownership,
  Parcel descriptor lifetime and malformed flattening input. The JVM regression
  also exposed a missing ActionBarView; its pinned source is now selected, with
  explicit Holo PhoneWindow inflation and title-update coverage. JVM, system,
  native, Binder and build checks pass, as do all 11 integration tests (38 steps
  without runtime issues) and the hardware-buffer Canvas/pixel-readback probe.
  That native prerequisite left the inventory at 57 stubs. Java SyncFence is
  now stock as described above; its camera imports require only Javadoc headers.

- The latest validated inventory is 50 stubs: 27 pending and 23 documented
  survivors, down from 134 at the start of this follow-up. Of these, 43 are
  outside `com.android.server` (20 pending, 23 survivors). The inventory does
  not count the adapted Activity and ActivityThread implementations and is not
  an overall completion percentage. Those classes and the remaining service
  and native dependencies still prevent completion of the stock-client goal.

- `GraphicsEnvironment`, its JNI, GraphicsEnv and GPU-service client metadata
  now use pinned AOSP sources. The host seam supplies the library loader's
  absence of Android linker namespaces and explicitly reports unsupported
  namespace creation/linking. The ANGLE override flag comes from pinned
  aconfig in force-read-only mode for both Java and C++. The Binder facade now
  supports the stock vector-count, nullable UTF-16 vector and typed result
  receiver contracts required by this closure, including malformed UTF errors.
  Seven selected Java/JNI/C++ files and six generated flag outputs match the
  pinned sources/generator. JVM, separate-process, native, Binder and build
  checks pass, along with all 11 integration tests (38 steps, no runtime issues).
  This adds no GPU service or new Wayland, PipeWire or FFmpeg implementation.

- Nine Activity support classes are stock: CursorLoader/AsyncTaskLoader,
  DirectAction, FullscreenRequestHandler, ScreenCaptureCallbackHandler,
  AutofillClientController, ToolbarActionBar, and the Dumpable container pair.
  All nine selected Java files match pinned AOSP. Importing the autofill
  controller exposed the missing caller-aware IntentSender overload; Activity's
  sender entry points now use the existing task-manager service with stock
  cancellation, fill-in preparation and child/fragment result routing. Activity
  and Window are marked destroyed before `onDestroy`. Tests cover cursor worker
  versus main-thread delivery and closure, action parceling, dumpable identity,
  canceled senders, embedded result delivery, and destruction during recreation.
  JVM, system, native, Binder, C++ and build checks pass; all 11 integration tests
  pass with no structured runtime issues. These support imports do not complete
  autofill, screen capture or fullscreen services. A direct compile of stock
  Activity now has ten errors: nine voice-interaction references and
  `ViewRootImpl.dump`; its JNI and ActivityThread integration also remain.

- Explicit activity recreation now follows AOSP's queued relaunch protocol,
  coalesces requests, ignores local embedded tokens, checks the main thread, and
  uses stock transactions to restore the prior lifecycle state. The synchronous
  hosted recreation helper is deleted. Separate-process coverage verifies real
  Binder tokens, callback ordering, saved and retained state, and normal finish
  after recreation. JVM, system, native, Binder, C++ and build checks pass;
  all 11 integration tests pass with no structured runtime issues. Window
  preservation remains disabled at the existing hosted presentation boundary.

- `HostedActivityRuntime.kt` and the three remaining Activity result-target hooks
  are deleted. `ApkHost` launches through stock `Context.startActivity`, and
  ActivityThread uses the package context's class loader and package manager.
  Its host adapter now covers window presentation only. Lifecycle fixtures use
  the production task manager and stock client transactions; this exposed and
  fixed a missing start callback when returning from the stopped state. Tests
  cover embedded activities, fragments, saved state, results and configuration
  changes. JVM, system, native, Binder, C++ and build checks pass; all 11
  integration tests pass with no structured runtime issues. Activity and
  ActivityThread remain adapted; explicit recreation is now queued as above.

- Activity monitor results now use `ActivityThread.sendActivityResult` and stock
  `ActivityResultItem` transactions instead of being dropped by the adapted
  Activity. Regression coverage checks asynchronous pause/result/resume ordering,
  result preparation and the negative request-code case. Five `$artzig` lifecycle
  accessors are removed in favor of stock fields/methods; top-resumed callbacks
  now emit generated AOSP activity event tags. The generated Java matches the
  pinned logtags generator. JVM, system, native, Binder, C++ and build checks pass,
  as do all 11 integration tests with no structured runtime issues. The three
  legacy result-target hooks and `HostedActivityRuntime` are now removed as
  described above.

- Application startup now crosses `IActivityManager.attachApplication` and
  `IApplicationThread.bindApplication` before activity transactions or pending
  service work. The launcher and service entry points no longer call
  `handleBindApplication$artzig`. Stock `AppCompatCallbacks` and
  `SafeZipPathValidatorCallback` install compatibility decisions before application
  code. The hosted package manager queries enabled providers by process/UID and
  sorts by `initOrder`; binding installs them before `Application.onCreate`.
  The process announcement carries a copied window configuration override.
  Tests cover callback ordering, provider access during Application startup,
  disabled/other-process provider exclusion, compatibility decisions, and
  LoadedApk construction versus binding. JVM, system, native, Binder, C++ and
  build checks pass; all 11 integration tests (nine NewPipe, two ViewHost) pass
  with no structured runtime issues. Both newly selected Java files match pinned
  AOSP. Instrumentation/sandbox binding modes and the complete stock
  ActivityThread remain unfinished.

- `PlatformCompat` and `PlatformCompatNative` now serve compatibility queries;
  the hosted always-enabled service is deleted. Pinned ChangeIdProcessor and
  Soong's merger generate 162 change definitions from the selected framework
  sources, installed under the ART root's `etc/compatconfig`. Generated output
  matches an independent processor/merger run. Stock package and settings
  observers are registered after their services start. Tests verify target-SDK
  gates, disabled/unknown changes, application configuration sets, and permission
  rejection through Java and native Binder interfaces. JVM, system, native,
  Binder, C++ and build checks pass; all 11 integration tests pass with no structured
  runtime issues. App-process callback installation is now part of application
  binding, described above; ActivityThread itself remains adapted.

- `SharedUserSetting` and its eight-class compatibility closure are stock.
  The config and override XML models are generated by pinned xsdc (nine Java
  files), and both compatibility flag packages use aconfig. Tests cover shared
  UID flag aggregation, package snapshots, target-SDK gates, version-bounded
  overrides and XML save/restore. These are the stock compatibility service's
  prerequisites; the running stock service is described below.
  All eight selected Java files and nine generated models are byte-identical to
  their pinned source/generator output. The inventory is 58 stubs, 28 pending.
- Runtime shutdown now follows `AndroidRuntime::exit` through the native `_exit`
  boundary, including Binder disconnect termination. Zig's libc-linked
  `std.process.exit` ran destructors under live ART workers and produced a
  system-server shutdown crash. The focused `jvm-exit-test` installs an atexit
  trap: it failed with code 99 before the fix and passes with code 23 afterward.
  The final JVM, system, native, Binder, C++ and build checks pass without that
  crash; all 11 integration tests pass with empty structured runtime issue lists.

- `ApexManager` and `ApexSystemServiceInfo` are stock, with seven generated
  apexd Binder declarations. The hosted service exposes the empty APEX mount
  and session inventory with stock root/system/keystore caller checks; package
  scanning initializes stock mappings before consumers query them. Installation
  and mount mutations remain explicitly unsupported. Tests cover scan ordering,
  cache isolation, module/package mappings, system-service order, unknown
  sessions, duplicate scans, and remote permission rejection. JVM, system,
  native, Binder, build checks and all 11 integration tests pass (39 steps, no runtime
  issues). Both Java files match the pinned checkout byte for byte.

- `WifiManager` and its 35-class closure are stock, with generated service
  interfaces and explicitly staged Connectivity parcelable declarations.
  The hosted service reports disabled Wi-Fi and preserves permission checks,
  Binder lock ownership, work attribution, and death cleanup. The stock client
  owns reference counts and its 50-lock limit. No radio, scanning, power mode,
  or multicast filter backend is implemented. Module, separate-process, full
  APK, native, and both Binder suites pass; the 11 integration tests have no structured
  runtime issues. All 35 Java sources match the pinned checkout byte for byte.

- `HandwritingInitiator` is compiled from the pinned AOSP source; the stub is
  deleted. The hosted root supplies `ViewConfiguration` and `InputMethodManager`
  and follows the AOSP IME-window input guard. The no-IME service reports that
  neither handwriting mode is available, with the AOSP cross-user permission
  check. Current counts are maintained in `docs/stubs.md`.
- `ArtzigTestDiagnostics` is Kotlin in `dev.artzig`; the Zig liblog sink,
  launcher, hosted services, and generated package-manager adapter use that
  class. Its cursor protocol and JNI method descriptors are retained. The
  framework integrations are removed: `ThreadedRenderer.SimpleRenderer` now
  reuses the AOSP nested renderer, and `RavenwoodEnvironment` is compiled from
  AOSP with its normal device-side exceptions.

- `BinderCallsStats` and its latency observer, bucket, device-state, and package
  helpers are stock. The latency proto is generated from the pinned definition.
  Module tests exercise charging exclusion, call and exception counts, sizes,
  transaction names, and reset.
- `AconfigFlags` and its Java storage readers are stock. `DeviceProtos` and the
  aconfig nano message are generated from pinned inputs. The manifest flag uses
  the pinned BP2A release value, so package parsing applies the same feature
  gates as the platform manifest expects. Module tests and the full APK suite
  pass with these replacements.

- `ProtoLog` and its implementation closure, `AudioDeviceCallback`,
  `AudioRouting`, `MediaCryptoException`, `RouteDiscoveryPreference`,
  `MediaProjection`, `PictureProfileHandle`, and the legacy `EGLConfig`, `GL`,
  and `GL10` interfaces are compiled from AOSP. The hosted root follows the
  stock ProtoLog initialization order. No graphics or media backend behavior
  is added by this source replacement.

- `ArtzigAccessibilityBridge` is Kotlin in `dev.artzig`. It observes the stock
  accessibility manager through `AccessibilityPolicy`, assigns window identities
  outside the framework, and rejects stale actions after comparing node and
  window snapshots. Ordinary `OnDrawListener` notifications keep the idle
  cursor sensitive to draws without node changes. The root and window stack no
  longer call test machinery.
  The hosted root reuses AOSP accessibility event ordering, content-change
  throttling, and focus bookkeeping; it still invalidates the whole hosted root
  and has no WindowManager keep-clear regions.

- `HealthPermissions` and its framework dependency closure are stock, from the
  pinned HealthFitness module. Its AIDL interfaces and flags are generated from
  the module's inputs, including its own aconfig container. Permission-category
  mappings and failure behavior pass module tests and the full APK suite.

- `ActivityOptions`, `SplashScreen`, and the activity transition coordinators
  and state are stock. The adapted activity core now carries scene metadata,
  saves transition state, and releases splash listeners on destruction. The
  hosted package service implements splash-theme lookup, owner checks, and
  clearing. Module tests cover parcel round-trips, transition-state restoration,
  listener cleanup, and theme clearing. This does not provide splash-window
  rendering or complete task/window animation behavior.

- `IWindowManager` is generated from the pinned AIDL. Window-context tokens,
  controllers, providers, and configuration/removal transaction items are stock.
  The process-local Kotlin window service supplies display-area attachment,
  ownership checks, configuration delivery, and detachment. The adapted window
  manager follows AOSP token and window-type validation. Module tests cover
  resource-before-callback ordering, parcel copies, duplicate attachment, and
  removal cleanup; the full APK suite passes.

- `AbstractInputMethodService`, `InputMethodService`, their Binder wrappers,
  navigation and extracted-text views are stock; diagnostic protos are generated.
  A declared fixture IME passes creation, binding, and destruction tests. The
  hosted root queues injected input in arrival order and recycles pending events
  on detachment. Window sessions now expose unserved operations explicitly.
  Hosted windows still lack server WindowStates and input channels: IME visibility
  tracking follows AOSP's missing-window failure path. Selecting and presenting a
  third-party IME remains service/backend work; replacing these Java classes does
  not complete that capability.

- `Debug` and `TypedProperties` are stock. Zig JNI supplies Linux memory and
  allocator observations, AOSP heap categories and parcel fields, and hosted
  Binder object counts. Module tests cover output-array mutation, failed PID
  queries, parcel round-trips, live reference counts, and allocator reports that
  preserve descriptor ownership. Android debuggerd, malloc_debug, memtrack/BPF,
  and heap-tracing facilities remain unavailable; kernel configuration queries
  require the running kernel's exposed configuration.

- `Instrumentation`, `UiAutomation`, `PerformanceCollector`, and
  `TestLooperManager` are stock. The adapted Activity now supplies the attachment
  signature and persistable lifecycle callbacks they require. Tests cover
  lifecycle ordering, state restoration, application/token identity, activity
  monitor results, and referrer propagation. Legacy lifecycle fixtures install
  their own task-service adapter; production Instrumentation uses the stock
  activity-task-manager path. The JVM and full APK suites pass. UiAutomation
  surface screenshots still require a compositor transaction service; the hosted
  service reports that missing operation explicitly.

- `EGL14`, its four handle classes, and `EGLObjectHandle` are stock. Zig JNI
  exposes real Mesa EGL contexts, configurations, pbuffers, current handles,
  queries, and errors. The EGL backend provides offscreen GBM drawables on the
  existing render device, so default window configurations also support Android
  clients that create pbuffers. Native tests read rendered pixels, check that
  swaps retain them, and destroy a surface while its context is still current.
  JVM tests require a real context and 16x8 pbuffer when
  `AZK_TEST_REQUIRE_EGL=1`; they also cover array bounds, handle equality, and
  AOSP argument failures. Window surfaces still require a compatible host
  presentation adapter; no Wayland implementation changes are included.
  The JVM/native checks and all 11 integration integration tests now pass, including the
  two fullscreen regressions exposed by the initial surfaceless implementation.
  Hosts without an available GBM device retain the more limited surfaceless
  display. EGL texture binding and Android window presentation remain separate
  capabilities; replacing these Java classes does not provide either backend.

- `AudioFocusRequest`, `MediaRoute2Info`, and `MediaRouter2Utils` are stock.
  Focus request validation, listener/handler identity, flags, force-ducking
  metadata, route visibility, and parcel copies pass module tests and the full
  APK suite. The adapted `AudioAttributes` retains AOSP bundle-copy semantics and
  parcel layout, and the hosted output descriptor is constructed outside the
  framework. Audio-focus arbitration and dynamic routing still need hosted
  services; this batch does not change PipeWire.

- `AppGlobals` is stock. Application and package identity now come from the bound
  ActivityThread application, with the stock core-setting defaults and cached
  permission-service lookup. The hosted permission service supplies package/UID
  permission reads over installed grants; the outer PermissionManager and its
  remaining operations are still adapted. Module and full APK suites pass.

- `ActivityManager` and its backup/process-statistics dependency closure are
  stock. Process-statistics protos and localization sysprops are generated, and
  the backup streams and tar transport use the pinned AOSP JNI/C++ code.
  Hosted AM answers process identity and Linux memory queries and retains
  session-local exit records (unknown reason when Binder death supplies no
  wait status). The stock client's AppTask handles now refer to owned hosted
  tasks, with package/caller checks and lifecycle operations. Wire-format,
  parcel, separate-process, JVM, and full APK tests pass. Backup transport
  services, persistent exit history/tombstones, and the full AMS policy remain
  separate capabilities.

- `ActivityManagerInternal` and its nine-source closure are stock. A generated
  adapter in `dev.artzig.system` preserves every abstract signature; Kotlin owns
  boot readiness, process queries, and incoming-user permission checks. Other
  server operations report structured unsupported diagnostics. AppTask tests
  cover caller ownership, exclusion from recents, starts within an existing
  task, pause-before-launch/destroy ordering, immediate removal from recents,
  and stale handles. Moving the resumed task to front no longer sends a second
  resume. JVM, Binder, native, separate-process service, and full APK checks pass;
  the APK suite captured 38 steps with no runtime issues.

- `NetworkScoreManager`, the Wifi score/model closure, `NetworkScoreService`,
  and `NetworkScorerAppManager` are stock. The existing boot manager starts the
  stock service and its settings observers. A Kotlin legacy-permission adapter
  connects default open-Wifi location grants to the hosted package store.
  Stock cache registration/invalidation, score ranking/parcel behavior, and
  cross-process permission denial pass. A fixture now verifies default location
  grants, scorer discovery, provider binding, score delivery, and invalidation
  when its service component is disabled. The stock PackageMonitor dispatcher
  receives hosted installs and enabled-state changes. No Wifi scanner or
  associated Android Wifi interface is advertised. The closure tool now excludes
  historical AIDL API dumps when a live declaration exists.

- `FingerprintManager` and its HAL interface/parcelable closure are stock.
  The hosted Binder service models the absence of fingerprint providers, with
  permission checks, empty sensor-registration callbacks, absent authenticator
  IDs, and the stock absent-provider authentication result. Separate-process
  tests cover allowed/denied queries and cross-user enrollment checks. Sensor
  enrollment/management, biometric prompts, and actual biometric hardware remain
  separate work.

- `JankTracker`, `JankDataProcessor`, and `StateTracker` are stock. The adapted
  SurfaceControl contains the verbatim jank data/listener contracts and the
  root exposes its own Choreographer. AOSP's AttachedSurfaceControl default
  reports no compositor subscription; no frame samples are invented. Module
  tests verify app/compositor jank classification and aggregation.
  `SoftApConfiguration` is stock, with its Wifi environment closure and generated
  VNDK properties; `AppBackgroundRestrictionsInfo` is generated from the pinned
  framework proto. These changes pass JVM, separate-process service, Binder,
  native, and full APK checks (11 integration tests, 37 steps, no runtime issues).

- `ApplicationSharedMemory` is stock, including its mapping, feature-cache, and
  atomic-access JNI. A Kotlin allocation adapter uses libcore's stock POSIX JNI
  to supply sealed Linux memfds in place of the host ashmem implementation.
  Tests verify read-only duplication, size seals, shared updates, descriptor
  ownership, and mapping lifetime. JVM, separate-process service, Binder, native,
  and all 11 integration tests pass.

- `CompanionDeviceManager`, its association/request models, the Bluetooth
  client closure, `TetheringManager`, and local sockets are stock. Local sockets
  use the pinned JNI implementation, with credential, descriptor-transfer, and
  EOF tests. The hosted companion service reuses AOSP's association store,
  persistence, ownership checks, and listener dispatcher. Immediate self-managed
  associations without a device profile pass separate-process creation, query,
  callback, sync-flag, removal, and permission tests. Consent/discovery, role
  grants, presence binding, transports, and a tethering service remain unfinished.
  No Bluetooth adapter is advertised. The closure tool now recognizes an AIDL
  interface following a nested parcelable forward declaration.

- Hosted root attachment and destruction now maintain the WindowManagerGlobal
  registry. Its window, token, child-window, debug-name, and listener queries
  follow the pinned methods, including listener replay and snapshot copies.
  These changes pass JVM, separate-process service, Binder, native, launch, and
  full APK checks (11 tests, 37 steps, no runtime issues).

- `DefaultMimeMapFactory` is stock. Debian, Android, and vendor MIME tables are
  generated from the pinned inputs using Soong's minimization rules. The runtime
  jar packages these resources alongside D8's output, since D8 discards them.
  Module tests verify Android overrides, vendor fallback, and malformed-input
  failures; the JVM, service, Binder, native, and full APK suites pass.

- `SystemConfig`, `PermissionAllowlist`, and `VintfRuntimeInfo` are stock.
  `RoSystemFeatures` comes from the pinned Kotlin generator and BP2A feature
  arguments. The native runtime-info seam reads the host kernel and system
  properties, preserving independently cached query groups and failure values.
  The host reports no incremental filesystem. Module tests cover permission XML,
  feature version merging, existing versus missing libraries, and runtime queries.

- `InteractionJankMonitor`, `FrameTracker`, display classification, and the
  debug overlay are stock; flags and event log tags are generated. The hosted
  root supplies AOSP's surface callback registry and draw notifications. Its
  unallocated SurfaceControls retain the stock missing-layer and empty-listener
  behavior. Module tests cover configuration rejection, listener cleanup, and
  draw callback ordering. Actual compositor jank data remains unavailable.

- `AudioTimestamp`, `PlaybackParams`, and `MediaFormat` are stock. Module tests
  cover 64-bit parcel data, unset/default playback parameters, invalid pitch,
  typed format getters, and live key/feature views. The existing AudioTrack
  adapter marks its returned default playback parameters as set, following
  AOSP JNI. No PipeWire or FFmpeg implementation is changed.

- The closure tool now retains every static import owner when multiple classes
  share the name `Flags`, and indexes logtag package declarations. Regression
  tests cover both cases. Source prerequisites for the remaining client stacks
  and the pinned x86_64 Chromium WebView APK are curated, but staging them does
  not select those clients for compilation or provide a working WebView provider.
  The combined batch passes JVM and service tests, Binder/native checks, and all
  11 integration tests (37 steps with no runtime issues).

- `RuntimeInit`, Android logging/print streams, and DDMS initialization are stock.
  The native launcher enters non-zygote Java mains after common initialization
  and dispatches escaped main exceptions through Android's uncaught handlers.
  Hosted AM stores crash/WTF reports in DropBox and retains reported crash
  reasons on process death. Registered broadcasts use stock application-thread
  delivery, preserving sender identity and initial/final ordered result state.
  Socket tagging installs the stock SocketTagger contract; absent per-tag kernel
  accounting reports an explicit unsupported diagnostic and counters return -1.
  The full TrafficStats client and graphical ActivityThread bootstrap remain
  unfinished. Deliberate process-crash, broadcast, JVM, service, Binder/native,
  launch, and all 11 integration tests pass (38 steps without runtime issues).

- `InputEventInjectionSync`, `PointerIconType`, and `ViewProtoEnums` are generated
  from pinned AIDL/proto declarations. The closure tool now indexes explicit
  proto Java outer classes and emits their generator declarations; seven tool
  regression tests pass.

- `GLES20`, `GLES30`, `GLES31`, and `GLES32` are stock Java APIs backed by
  the pinned AOSP JNI bindings and the existing Mesa GLES library. Native patch
  28 fixes heap-buffer array releases and typed-buffer bounds checks in those
  bindings. Required-GPU module tests pass pixel readback, texture lifetime,
  shader compilation, position/limit preservation, invalid arguments, and GL
  errors. `TouchOcclusionMode` is generated from the pinned GUI AIDL.

- `SystemFeaturesMetadata` is generated by AOSP's Kotlin annotation processor
  from the pinned `PackageManager` declarations. Its 186 SDK features replace
  the placeholder count and unknown-only lookups. Module tests cover distinct
  feature indices, version thresholds, unavailable features, custom-feature
  fallback, and cache reconstruction. The GLES/metadata batch passes JVM,
  system-service, Binder/native, launch, and all 11 integration tests (35 recorded
  steps without runtime issues). The audit is 80 stubs: 34 pending, 46 survivors.
  Build configuration explicitly tracks both package manifests so changing
  an AOSP archive hash cannot reuse source paths from the previous package.

- `NativeLibraryHelper`, `SELinux`, and the incremental-install clients are
  stock, with pinned native extraction and IncFs implementations. The IncFs
  C++ sysprop accessor is generated from its pinned declaration. SELinux JNI
  uses the host's libselinux policy backend; Android seapp policy is not
  installed. IncFs reports disabled on this host and ordinary files have no
  incremental signature. The launcher delegates ABI selection and extraction
  to NativeLibraryHelper, retaining extracted x86_64 files for the host linker
  and accepting APKs with no native code. Tests cover CRC-based replacement,
  executable permissions, ABI mismatch, Java-only archives, failed handle
  creation, and native/SELinux queries. JVM, system, Binder/native, launch, and
  all 11 integration tests pass (36 steps without runtime issues). The audit is
  78 stubs: 34 pending, 44 survivors.

- `StrictJarFile`, its manifest readers, and its verifier are stock, with the
  pinned ZIP JNI. The permissions fixture is signed with both v1 and v2
  signatures. Tests verify certificate availability only after reading a full
  entry, tampered-content rejection, signature-stripping protection, and owned
  descriptor closure.
- `MemoryIntArray` is stock Java with stock native atomic access and cleanup.
  Its allocation and mapping ports use sealed Linux memfds through libcore
  Posix. Size seals prevent resizing; recipients cannot write through a file
  descriptor or acquire a writable mapping. Tests cover parcel ownership,
  bounds, closed-state failures, and separate-process updates that remain
  readable after the owner closes its mapping. SettingsProvider still does not
  publish cache-generation trackers; this replacement supplies that dependency.
- `WebViewClient` is stock. Unhandled events enter the hosted root's asynchronous
  synthesis queue; trackball, joystick, touch-navigation, and keyboard handlers
  are reused from pinned ViewRootImpl. Detachment cancels joystick repeats and
  pending input. Tests cover motion-event ownership, joystick cancellation,
  exclusion of relative mouse and already-synthesized keys, and default client
  certificate rejection. A working Chromium provider remains unfinished.
  The archive/shared-memory/WebViewClient batch passes JVM, separate-process,
  Binder/native, launch, and all 11 integration tests (36 steps without runtime issues).
  The audit is 75 stubs: 33 pending, 42 survivors.

- `HardwareBuffer`, `GraphicBuffer`, and `TaskSnapshot` are stock Java. Their
  native buffer ports retain GBM storage, carry DMA-BUF descriptors through
  Binder with the stock GraphicBuffer envelope, and reuse pinned ACanvas code
  for software Canvas access. Imported buffers retain their identity and
  storage after the sender closes. Dirty rectangles clip drawing without
  shifting the buffer origin; patch 29 preserves RGBA byte order when host
  Skia uses BGRA for ordinary bitmaps. The TaskStackListener reflection patch
  is removed, restoring stock snapshot release behavior. Module tests cover
  local versus remote listener ownership, snapshot parcel metadata, closed
  buffers, and Canvas clipping. Separate-process buffer transfer and exact
  red/blue pixel readback pass, as do JVM, native, Binder, build audits, launch,
  and all 11 integration tests (37 steps without runtime issues). The audit for this
  batch is 73 stubs: 33 pending, 40 survivors.

- `OverlayProperties` and `LutProperties` are stock Java, backed by pinned JNI
  and C++ parcelables generated from the pinned GUI AIDL. The default object
  exposes AOSP's RGBA/sRGB virtual-display combination. Native Parcel now
  supports the enum and nullable-parcelable vectors used by those generated
  sources. Patch 30 replaces an unsafe native-pointer-to-Java-array conversion
  with JNI array marshalling. The pinned Java LUT constructor currently rejects
  every dimension; that observable failure is preserved and tested, so this
  change does not advertise working display LUTs. Default capability queries,
  parcel round trips, malformed data, and native enum wire bytes pass. JVM,
  separate-process, native, C++ Binder, build audits, launch, and all 11 integration
  tests pass (36 steps without runtime issues). The overlay batch audit is
  72 stubs: 33 pending, 39 survivors.

- `SurfaceSyncGroup`, `PixelCopy`, and `SurfaceControlInputReceiver` are stock;
  remote-transition and embedded-surface callbacks are generated from pinned
  AIDL. The nested `SurfaceControlViewHost.SurfacePackage` is reused verbatim.
  AOSP's SurfaceSyncGroupController runs in the shared system process, with
  the process-local window service forwarding its sync operations over the
  stock IWindowManager protocol. Tests verify parent/child readiness ordering,
  completion across processes, caller-UID ownership, and remote-transition
  finish callbacks and token identity. PixelCopy retains stock validation and
  callback delivery; patch 31 returns SourceInvalid when the hosted renderer
  cannot translate a Java Surface into an Android native window. It does not
  implement compositor readback or video-layer BLAST synchronization.
  JVM, system, native, Binder/C++ Binder, and build audits pass. All 11 integration tests
  pass (36 steps without runtime issues), after correcting the testkit's missing
  cursor for relative action waits. Playback checks wait for frame presentation
  because a live stream need not become idle; controls and screenshot checks
  remain in place. All 21 testkit tests pass. The window batch audit is
  68 stubs: 33 pending, 35 survivors.

- `VerityUtils` is stock Java with its pinned Bouncy Castle CMS dependency
  closure and unmodified fs-verity JNI. AOSP's external signature/certificate
  fixture verifies successfully; altered signatures and digests, wrong digest
  lengths, and embedded certificates are rejected. Filesystem queries and
  setup retain Linux fs-verity error behavior. This host filesystem does not
  support enabling fs-verity; the tests exercise that failure without claiming
  verity-protected storage. JVM, separate-process, native, Binder/C++ Binder,
  and build audits pass.

- `UsbDevice`, `UsbConfiguration`, `UsbInterface`, `UsbEndpoint`, and
  `UsbConstants` are stock. Device IDs use pinned JNI and the host-supported
  libusbhost source. Serial readers and display connection constants are
  generated from pinned AIDL; USB enum constants come from the proto generator.
  Tests verify descriptor parcel data, endpoint attributes, serial-reader
  permission failures, and usbfs name/ID conversion. This does not add a USB
  device service or open physical devices. The combined fs-verity/USB build
  passes JVM, separate-process, native, Binder/C++ Binder, build audits, launch,
  and all 11 integration tests (37 steps without runtime issues). Its audit is
  65 stubs: 33 pending, 32 survivors.

- `WindowManagerGlobal` and `WindowlessWindowLayout` are stock. Hosted
  activity roots and overlay windows enter its add/update/remove path;
  `HostedWindowRoots` supplies desktop geometry. Deferred root destruction
  preserves the stock registry iteration order, and overlay removal now
  destroys its root rather than merely detaching its view. Tests cover token
  lookup, parent/child stop propagation, unrelated roots continuing to draw,
  deferred close callbacks, layout-parameter preservation, and configuration
  callback ordering, queued copies, stale sequences, and RTL/LTR changes.
  HWUI profile dumping and root hierarchy accounting reuse pinned code.
  The roots still share the hosted renderer and main-loop presentation;
  windowless SurfaceControl presentation reports unsupported. This does not
  replace ViewRootImpl, ThreadedRenderer, or the activity core. JVM, system,
  native, Binder/C++ Binder, build audits, launch, and all 11 integration tests pass
  (38 steps without runtime issues). The stub audit remains 65: 33 pending,
  32 survivors; this batch removes a framework adaptation outside that inventory.

- `WindowManagerImpl` is stock. The Kotlin `HostedWindowStack` in `dev.artzig`
  observes the stock registry to track overlay composition and input, and the
  launcher identifies its activity root separately. Stock add/update/remove
  calls preserve overlay entries and refresh layout parameters without framework
  hooks in the client manager. Host configuration now includes window bounds,
  app bounds, and maximum bounds; stock window metrics use the context resources
  and window service, including density changes and immutable snapshots.
  Module tests cover overlay focus, update/removal, and metric behavior. JVM,
  separate-process, native, Binder/C++ Binder, build audits, launch, and the full
  APK suite pass. The packaged manager matches the pinned source verbatim.
  Surface mirroring reports unsupported at the existing SurfaceControl seam.
  The stub inventory remains 65 (33 pending, 32 survivors); the removed manager
  and Android-package overlay stack were framework adaptations.

- `WifiP2pManager` and 26 supporting classes are stock, including configuration,
  peer/group models, DNS-SD records, and AsyncChannel. Both Wi-Fi Direct Binder
  interfaces are generated from pinned AIDL. Tests cover configuration failures,
  peer parsing, parcel copies, DNS-SD wire encoding, asynchronous error callbacks,
  listener removal, channel cleanup, permission errors, and feature queries over
  generated Proxy/Stub transactions. JVM, separate-process, native, Binder, build
  checks, and all 11 integration tests pass (39 steps without runtime issues). All 27
  packaged Java files match the pinned checkout. No Wi-Fi Direct hardware or
  supplicant service is added; the client tests use an in-memory service fixture.

- URI grants now use stock `UriGrantsManagerService`, its local interface,
  owners, persistence and metrics helpers. The permissive hosted grant service
  is deleted. The hosted activity and package services delegate explicit grant,
  revoke, provider-access and permission checks to the stock implementation.
  Separate-process tests cover a private provider, prefix and read-only grants,
  denied writes and unoffered persistence, persistence release, and revocation.
  Activity-launch Intent grants and activity-record lifetime are now connected
  to this service, as described in the later follow-up above.
- `StatsManager`, its query models, initializer, and `StatsManagerService` are
  stock, with generated StatsD Binder interfaces. No statsd daemon is added;
  the stock service caches pull-atom registrations while it is absent. Module
  tests cover permission rejection, offline registration, replacement, removal,
  reconnect replay, and URI-owner lifetime. JVM, separate-process, native,
  Binder, C++ and build checks pass; all 11 integration tests pass with 38 steps and no
  runtime issues. All 18 packaged Java files match the pinned checkout. The
  inventory is 63 stubs: 32 pending and 31 survivors.

- The audio-policy V7_0 XML model and enums are generated from the pinned
  schema using AOSP xsdc. Typed XSD declarations now drive both audio-policy
  and APEX generation; the source audit rejects handwritten replacements of
  these outputs. All 31 audio-policy sources match direct xsdc generation.
  Parser tests cover nested modules, device routes, profiles, enum values,
  defaults, unknown extensions, and invalid input. JVM, separate-process,
  native, Binder, build checks and all 11 integration tests pass (39 steps with no
  runtime issues). This supplies an audio-client prerequisite; it adds no
  PipeWire behavior and does not yet replace `AudioAttributes` or `AudioManager`.

- `Installer`, `PackageSetting`, `SettingBase`, and the legacy permission-data
  interface are stock. The installd interface and parcelables, package dump
  protos, and server logtags are generated from pinned declarations. Hosted
  package records now use stock mutable user and transient state; the helper
  exposing package-private defaults is deleted. The hosted install service
  remains responsible for installation; no installd daemon is added.
  Tests cover separate users, snapshot invalidation and isolation, MIME-group
  changes, isolated-installer results, batch boundaries and per-request errors.
  JVM, separate-process, native, Binder, C++ and build checks pass, along with
  all 11 integration tests (39 steps without runtime issues). All four packaged Java
  files match the checkout. The inventory is 61 stubs: 30 pending, 31 survivors.

`Dialog` was already compiled verbatim from the pinned AOSP source before this
follow-up; its packaged source hash matches the checkout. The remaining activity
core (`Activity` and `ActivityThread`) and service-backed
stacks are still unfinished.
The initial activity-family closure against the follow-up build reported 495 sources,
273 AIDL interfaces, and 229 blockers or missing members. These are closure-tool
findings, not 229 independently verified runtime gaps. They include window
lifecycle and splash-screen operations, runtime diagnostics, and application
shared-memory support; they must be resolved before replacing the core.

## Original fence (superseded by the follow-up scope)

The original batches did not modify, delete, or replace the following classes
and patches. This list records their previous boundary, not a prohibition on
the follow-up work.

- `src/framework/android/media/*`, `android/view/*`, `android/graphics/*`,
  `android/hardware/HardwareBuffer.java`,
  `android/content/res/Hosted*.kt`.
- stub families `audio`, `opengl`, `media`, and every survivor whose reason
  starts with `window seam`.
- patches 7, 8, 9, 13, 16 in `aosp/package-androlinzberg-aosp.py`.

## Rules that are easy to get wrong

- A hosted service never delegates to `IFoo.Default()`. Unserved methods must
  fail loudly so the integration tests record them as `unimplemented`. Use the
  generated `Unsupported*` base, or after batch 0 the `unsupported<T>()`
  delegate. `HostedInputMethodManagerService` currently violates this; batch 0
  fixes it.
- Framework natives are written in Zig (`src/binder/framework_jni.zig` is the
  pattern), never in `src/native/*.cpp`. C++ is only for HWUI, Skia and AOSP
  C++ interfaces (`docs/native-boundary.md`). The `android.util.Log` sink was
  added in C++; batch 0 moves it.
- Never raise `pending-budget`. Delete a stub and its audit line together.
  A stub that cannot go yet gets a `survivor: <reason>` the closure tool can
  confirm, not a family.
- Every repository JVM file is Kotlin unless it is a directly reused AOSP or
  generated source.
- No attribution lines in commit messages.
- A commit that changes `aosp/androlinzberg-files.txt` must repackage and bump
  the `.aosp` hash in `build.zig.zon` in the same commit. The archive is
  reproducible: anyone can run `python3 aosp/package-androlinzberg-aosp.py`
  and get the committed hash, so never commit a hash you did not obtain from
  the packager on the committed manifest.
- The APK integration suite is mandatory for every batch, not only
  `jvm-test`.

## Per-batch workflow

1. Build once so a current classes jar exists:
   `zig build -j<n> java` (add `--system zig-pkg` on the machine described in
   the section at the end). The jar is the newest
   `.zig-cache/o/*/framework-classes.jar`.
2. Run the closure tool with every stub the batch deletes as a seed:

   ```sh
   tools/aosp-closure.py --classes <jar> closure \
     --assume-present <known false positives> <fqn> <fqn> ...
   ```

   Seed with all of them at once: the tool counts classes in the jar as
   present, so a stub you forget to seed hides the closure of the ones you
   did seed. It prints the `aosp/androlinzberg-files.txt` lines,
   `build/java_sources.zig` lines, and `build/generator_sources.zig` entries
   (AIDL, aconfig) the real classes need, and names every blocker.
3. Apply those lines. New AIDL include roots go in both `addAidlIncludes` and
   `aidlOutputPath` in `build/generators.zig` and in the tool's `AIDL_ROOTS`.
   Proto outputs go in `proto_outputs`.
4. Delete the stubs, remove them from `build/project_jvm_sources.zig`, delete
   their lines from `tools/stub-audit.txt`, lower `pending-budget` by the
   number of pending stubs deleted.
5. Repackage, bump the hash, `zig build -j<n> gen-aidl`.
6. Write or extend hosted services where a real class needs one.
7. Verify, in this order, and fix before moving on:

   ```sh
   zig build -j<n> build-tools-test graph-audit cpp-audit
   zig build -j<n> native-test binder-test system-test jvm-test
   zig build -j<n> integration-test -- --class NewPipeTest --method launchesHomeWithoutRuntimeIssues
   zig build -j<n> integration-test
   ```

   `jvm-test` runs the JVM-source audit (stub ratchet, generated-class
   shadowing) through the java step.
8. Regenerate the audit and the doc:
   `tools/aosp-closure.py --classes <jar> audit --update tools/stub-audit.txt --markdown docs/stubs.md`.
9. One commit per batch, subject `framework: ...`, body stating what moved and
   what was cut.

## Batch 0: pay down the two review findings

Small, independent, do it first so the later batches are cleaner.

1. Add a reflective delegate and drop the generated bases:

   ```kotlin
   inline fun <reified T : IInterface> unsupported(): T = Proxy.newProxyInstance(
       T::class.java.classLoader, arrayOf(T::class.java),
   ) { _, method, _ -> throw ArtzigTestDiagnostics.unsupported(T::class.java.simpleName, method.name, "not hosted") } as T

   class HostedPowerManagerService : IPowerManager.Stub(), IPowerManager by unsupported<IPowerManager>() {
       override fun asBinder(): IBinder = this
       // served subset only
   }
   ```

   Convert all 37 services, delete `src/java/dev/artzig/system/Unsupported*.kt`
   and `tools/gen-unsupported-stub.py`, and fix the comments in the services
   that say they were generated. `asBinder` must be overridden in every
   service.
2. Rewrite `HostedInputMethodManagerService` on the delegate instead of
   `IInputMethodManager.Default()`, keeping only the empty-IME-list methods it
   serves.
3. Move the `android.util.Log` natives (`isLoggable`, `println_native`,
   `logger_entry_max_payload_native`) from `src/native/AospJniBridge.cpp` to
   Zig next to the other framework natives, keeping the liblog write and the
   `ArtzigTestDiagnostics.recordLog` call. `std.Io` policy applies.

## Batch 1: every replaceable stub outside the fence and the activity family

The audit lists 59 pending stubs and 18 survivors as `replaceable`. Take all
of them except:

- families `audio`, `opengl`, `media`, window-seam survivors (fence);
- families `activity` and `ui` (batch 3: the real `Fragment`, `AlertDialog`,
  `ActivityOptions`, `TaskInfo`, `IActivityTaskManager` expect the real
  `Activity`).

That leaves roughly 50 classes: webkit leaves and `WebViewClient`, runtime
utilities (`CancellationSignalBeamer`, `PermissionEnforcer`,
`SynchronousResultReceiver`, `Immutable`, `BrightnessSynchronizer`,
`IPlatformCompat`), jank and metrics logging, `UiThread`, clipboard monitors,
textclassifier and voice contracts, `AbstractInputMethodService`,
`InlineContentView`, `HandwritingInitiator`, `PackageInstaller` and
`IPackageInstaller`, `PackageManagerInternal`, `UserManager` and
`UserManagerInternal`, `PermissionControllerManager`, `PreferenceManager`,
`TextToSpeech`, `VirtualDeviceParams`, the `com.android.server.pm` value
classes, `Counter`, `UnboundedSdkLevel`, and the hand-written
`src/framework` replacements that are pure Java: `Browser`, `FontsContract`,
`KeyGenParameterSpec`, `MimeTypeMap`, `OverlayConfig`, `Xml`, `ProtoUtils`,
`SslCertificate`, `SslError` (add the BouncyCastle X509Name sources; the
project is synced).

Expect these to need more than deletion:

- `UserManager`: the real class depends on `UserManagerCache` from the
  `property_cache` annotation processor. `external/guava` is synced; wire the
  processor into the javac step in `build/java.zig` instead of stubbing.
- `IPlatformCompat` is served by `HostedPlatformCompatService` already; just
  delete the stub.
- `DateFormat`, `Time`, `TimeUtils`: replace only if the vendored libcore ICU
  provides what they need; otherwise leave them and record the reason as a
  survivor. Do not extend the shims.
- `TextToSpeech`, `PreferenceManager`, `PermissionControllerManager` may
  reach a service at runtime that nobody hosts. The integration suite decides:
  a `ServiceNotFoundException` or an `unimplemented` issue in `steps.jsonl` on
  a NewPipe path means a hosted service is needed in this batch.

If the first compile shows a class whose closure the tool did not predict,
seed it and rerun the tool rather than hand-fixing the source list.

## Batch 2: module trees

68 survivors exist only because their project is not in
`aosp/aosp-projects.txt`. Sync, in `aosp/`:

```sh
for p in platform/packages/modules/Connectivity platform/hardware/interfaces \
         platform/external/libphonenumber platform/packages/modules/Wifi \
         platform/packages/modules/Media platform/packages/modules/Permission \
         platform/packages/modules/Bluetooth \
         platform/packages/modules/SdkExtensions platform/packages/modules/IPsec \
         platform/packages/modules/ConfigInfrastructure platform/packages/modules/AppSearch \
         platform/packages/services/Telecomm; do repo sync -c -j5 $p; done
```

The pinned Android 16 manifest keeps the Tethering sources under the
`platform/packages/modules/Connectivity` project; there is no separate
`platform/packages/modules/Tethering` Repo project to sync or inventory.

Add each to `aosp-projects.txt`, keep it sorted. Then:

1. Run the closure tool with all 68 survivors as seeds. Add only the framework
   source directories it names (`framework/src`, `framework/java`,
   `framework-t/src`), never a whole module tree; the packager prunes by the
   manifest and the archive should stay a few hundred megabytes.
2. HAL enums (`android.hardware.radio.*`, `thermal`, `vibrator`, `usb`,
   `health`, `gnss`, `graphics.common`) are AIDL under
   `hardware/interfaces/<hal>/aidl/...`. Add those directories as AIDL include
   roots and list the `.aidl` files in `generator_sources.aidl`; the existing
   generator produces the Java. The `V1_0`/`V2_1` HIDL constant classes are
   checked-in Java under the same tree.
3. Telecom `Flags`/`FeatureFlags`: add the aconfig declaration from
   `packages/services/Telecomm` to `generator_sources.aconfig` and delete the
   three hand-written flag classes.
4. Delete the survivors, change nothing else in their audit lines except
   removing them, repackage, bump the hash.
5. The classes that stay survivors after this batch: JNI-only classes with no
   host meaning, the WebView provider, camera, DRM, `ToneGenerator`, and the
   window seam. Their reasons already say so.

This batch is manifest work and can run on a second branch in parallel with
batch 1; merge conflicts are confined to the source lists and the audit file.

## Batch 3: activity core

The largest deviation and real code, not deletion. Order matters.

1. Hosted `IActivityTaskManager` and `IActivityClientController` in
   `SystemServices.kt`: start, finish, result delivery, relaunch decisions on
   configuration changes, task info for the single desktop task. Serve them
   on the delegate; override only what the client path exercises.
2. Fill the handlers in `src/framework/android/app/ActivityThread.java` that
   throw `notHosted` with the AOSP bodies: `handleLaunchActivity`,
   `handleStartActivity`, `handleResumeActivity`, `handlePauseActivity`,
   `handleStopActivity`, `handleDestroyActivity`, `handleSendResult`,
   `handleNewIntent`, `handleActivityConfigurationChanged`,
   `prepareRelaunchActivity`. Keep the source notes naming the AOSP method.
3. Real `Activity`. It attaches its `PhoneWindow` through the hosted
   `WindowManagerImpl` and `ViewRootImpl`; both are fenced and stay. The
   shim's `requestWindowFeature` and result plumbing disappear with it.
4. Real `Instrumentation` (the `execStartActivity` half) and real `Dialog`.
5. Delete `HostedActivityRuntime.kt`; `ApkHost` starts the main activity
   through the activity manager like a launcher.
6. Delete the `activity` and `ui` families: `ActivityManager`,
   `ActivityOptions`, `ActivityTaskManager`, `IActivityTaskManager`,
   `TaskInfo`, `AppGlobals`, `LocalActivityManager`,
   `ClientTransactionListenerController`, `AlertDialog`, `Fragment`,
   `FragmentManager`, `FragmentTransaction`, `ActionBar`, `Presentation`,
   `DatePicker`, `TimePicker`, `FloatingToolbar`.

Tests for this batch must cover callback ordering, handled versus unhandled
configuration changes, saved-state restore and result delivery
(`src/java/dev/artzig/FrameworkModuleSmoke.kt` is where the module tests
live). Run the full APK suite after every commit here; the NewPipe fragment
navigation and desktop fullscreen tests are the regression surface.

## After the batches

What remains pending is per-seam work, one commit each: `StorageManager`
(hosted `IStorageManager` over the emulated volume), `DevicePolicyManager`
(needs the biometrics HAL from batch 2 and a `SecurityLog` native seam),
`StatusBarManager`, `IncidentManager`, `VirtualDeviceManager`, `BatteryStats`
(proto outputs), `ProtoLog` and `ImeTracingPerfettoImpl` (perfetto protos;
`external/perfetto` is synced), and the remaining patches, each of which
needs a written reason in its header or a deletion.

Then the test seams leave `android.*`: `ArtzigAccessibilityBridge` becomes
the real `AccessibilityManager` over a hosted service, and
`ArtzigTestDiagnostics` moves to `dev.artzig` with the launcher's liblog sink
feeding it.

## Machine notes

- Build steps that must see the invoking shell's environment (the APK
  harness) pass their variables through `Command.setEnvironment`, never
  `Step.Run.setEnvironmentVariable`: this zig caches the build configuration
  and a step that owns an environment map replays the environment captured
  at cache time.
- On the LXC host with the Radeon render node, the suite needs Mesa from the
  nix store exported before `zig build`:
  `__EGL_VENDOR_LIBRARY_DIRS=$M/share/glvnd/egl_vendor.d LIBGL_DRIVERS_PATH=$M/lib/dri GBM_BACKENDS_PATH=$M/lib/gbm LIBVA_DRIVERS_PATH=$M/lib/dri LD_LIBRARY_PATH=$M/lib XDG_RUNTIME_DIR=/run/user/1000`
  with `M` the mesa store path, plus `AZK_APK_NEWPIPE_0_29_1`. Builds there
  use `--system zig-pkg` and need the archive extracted under
  `zig-pkg/<hash>` (`tar xzf <archive> --strip-components=1 -C zig-pkg/<hash>`).
- Every archive bump leaves a full application tree in `.zig-cache/o`, which
  zig never collects. Check `df` before a batch; delete `zig-pkg` trees whose
  hash no zon file references, and remove `.zig-cache` entirely when the
  disk passes 90%.
- The ART archive hash in `artzig/build.zig.zon` does not reproduce from the
  committed patch series (patch 0008 overlaps the build-time
  `art-x86_64-memory-optimization.patch`). Leave it alone; it is a separate
  fix.
