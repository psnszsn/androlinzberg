# Post-Wayland stock-client completion

Status: implementation in progress; batch 0 packaging and baseline checks pass.
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
