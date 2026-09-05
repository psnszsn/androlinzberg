# Stock client migration: bulk instructions

Goal: every class an application process runs on is the verbatim AOSP class,
except the seam that talks to PipeWire, Wayland, FFmpeg and EGL. That seam
stays exactly as it is until everything else is stock. The hosted services in
`src/java/dev/artzig/system` are the server side and remain host-owned.

Read `AGENTS.md` first; every rule there applies. This document only says what
to do, in what order, and where the traps are. State of the tree when this was
written: 225 stubs, pending budget 105, `docs/stubs.md` regenerated.

## The fence

Do not modify, delete, or replace any of these. A batch that would need to
touch one of them stops at that class and records it instead.

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
