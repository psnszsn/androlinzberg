# Stock Client Migration

> ANDROLINZBERG · CLIENT FRAMEWORK
>
> Source: [Claude artifact](https://claude.ai/code/artifact/d079bfe1-1d37-4c69-b548-e31ce6df48fc)

Replace every hand-written or patched client-side framework class with the verbatim AOSP class, one family per commit, while the PipeWire, Wayland, FFmpeg and EGL seam stays untouched until everything else is stock.

- `main @ d57e214`
- AOSP `android-16.0.0_r1`
- stub budget 142 · 3 Sep 2026

| Count | Scope |
| ---: | --- |
| 45 | hand-written replacements in `src/framework` outside the media seam |
| 117 | pending stubs in non-media families (72 replaceable today, 44 blocked, 1 no source) |
| 17 | `frameworks/base` patches that change stock client behaviour |
| ≈95 | survivor stubs waiting on an unsynced AOSP module tree |
| 25 + 48 | seam classes and stubs kept in place (media, window, opengl) |

“Client” means the framework jar an application process runs on: `src/framework`, `src/framework-stubs`, and the AOSP patches applied at package time. The hosted services in `dev.artzig.system` are the server side and stay host-owned; each phase below ends with a stock client talking to one of them over the real AIDL, the pattern the managers milestone established.

## The fence

**STAYS UNTIL LAST.** Anything wired to PipeWire, Wayland, FFmpeg or EGL. These files are not touched by any phase below, and a phase that would have to change one of them stops and reports instead.

- AudioTrack, AudioFormat, AudioTimestamp, PlaybackParams
- MediaCodec, MediaCodecInfo, MediaCodecList, MediaFormat
- SurfaceTexture, HardwareBuffer, SurfaceView, ArtzigSurface, ArtzigVideoLayer
- ViewRootImpl, WindowManagerGlobal, WindowManagerImpl, ArtzigWindowStack, ArtzigDisplay
- Choreographer, ThreadedRenderer, HostedWindowPresentation, HostedSystemUiController
- HostedInputDispatcher, HostedConfigurationController, HostedResources
- stub families `audio` (13), `opengl` (8), `media` (3), 24 window-seam survivors
- patches 7, 8, 9, 13, 16 (Display, Surface, Wayland EGL presentation, HWUI, message pump)

## Order and dependencies

Phases are ordered by what unblocks what. Phase 1 is cheap and independent. Phase 2 is the largest single deviation and everything androidx does with lifecycle sits on it, so it goes before the remaining managers. Phase 5 is mostly a manifest change, and it also removes blockers that phases 3 and 4 would otherwise have to cut around, so it can run in parallel with them on a second branch.

| Phase | Moves | Stubs closed | Needs synced first |
| --- | --- | ---: | --- |
| 1 Small classes and patches | 28 pure-Java replacements, 17 patches reviewed | 0 | `external/tagsoup` |
| 2 Activity core | Activity, Dialog, Instrumentation, ActivityThread activity half, activity + ui families | 18 | — |
| 3 Client managers | PermissionManager, Nsd, DownloadManager, MediaStore, sensors, 13 manager families | 39 | `packages/modules/Permission` |
| 4 Framework internals | webkit, runtime, logging, systemserver, textclassifier, inputmethod, contentcapture, accessibility, autofill | 55 | — |
| 5 Module trees | Connectivity, radio HAL enums, libphonenumber, Wifi, Bluetooth, Media, and the rest of the survivors | ≈95 | see table in phase 5 |
| 6 Test seams | ArtzigAccessibilityBridge, ArtzigTestDiagnostics out of `android.*` | 0 | — |

## Phase 1: Small classes and the patch review

Each item is a few dozen lines, independent of the others, and has a stock class that compiles once its closure is present. Work through them in the order below; each replacement is a closure run, a source-list edit, a deleted file and a lowered budget.

### Pure Java, compile as is

- The six `android.text.style` spans, `Browser`, `FontRequest`, `EpicenterTranslateClipReveal`, `AnnotationValidations`, `DataClass`, `KeyGenParameterSpec` (with the rest of `android.security.keystore`).
- `android.util.proto`: ProtoOutputStream, ProtoInputStream, ProtoUtils, WireTypeMismatchException. The real ones are pure Java and unlock the proto dump paths the stubs currently swallow.
- `OverlayConfig`; keep `OverlayManagerImpl` as the idmap2 adapter it already is, with a source note.
- `MimeTypeMap`: the real class is libcore `MimeMap` behind a WebView-neutral API. Verify with the closure tool; if it drags `WebViewFactory`, defer to phase 4.

### Need one dependency

- `Html`: real `fromHtml` needs TagSoup. **SYNC** `platform/external/tagsoup`, add it to the manifest and the framework source set.
- `SslCertificate`, `SslError`: the stock closure reaches the repackaged BouncyCastle X509Name. `external/bouncycastle` is already synced; add the `com.android.org.bouncycastle.asn1.x509` sources it needs instead of the Kotlin adaptation.
- `Xml`: the real class needs libcore's KXml plus `BinaryXmlPullParser`/`BinaryXmlSerializer` from `com.android.internal.util`. Both are pure Java; the ART binary-XML chain the note mentions is optional.
- `DateFormat`, `Time`, `TimeUtils`: check what the vendored libcore ICU exposes. If `getBestPattern` is missing, keep them as survivors with that reason; do not extend the shims.
- `androidx.annotation`, `CompileTimeConstant`: vendor the annotation sources from `prebuilts/sdk` and `external/error_prone` (both synced) rather than keeping copies.

### Need a hosted service or generator

- `CompatChanges`: real class calls `IPlatformCompat`. Add a hosted PlatformCompat service (all changes enabled) and drop the stub together with the runtime-family `IPlatformCompat` stub.
- `GrammaticalInflectionManager`: real manager over a hosted `IGrammaticalInflectionManager`.
- `AppProtoEnums`: belongs to the proto enum generator; wire `frameworks/proto_logging/stats/enums/app/enums.proto` and delete the hand copy.
- `StatsLog`, `Log`: both are JNI-backed. Register the two native sinks in the launcher (liblog is synced under `system/logging`) and compile the real classes.

### Patch review

Seventeen of the twenty-two package-time patches change stock client behaviour. For each, decide: drop (a hosted service now covers it), keep as host seam (document why), or move into a build-time patch. Start with 15 (ServiceManager process-local overlay) and 10 (no-IPC InputMethodManager), which the broker and hosted services already make unnecessary. Patches 5, 12, 19 (env-overridable paths) and 17, 20, 21 (broker uid, class loaders) are host seams and stay, with the reason recorded in the patch header.

#### Verify

- unit chain: `native-test binder-test system-test jvm-test`
- `build-tools-test`, `graph-audit`, jvm-source audit through the java step
- NewPipe launch test, then the full suite
- budget lowered in `tools/stub-audit.txt`, `docs/stubs.md` regenerated

#### Exit

- `src/framework` holds only the fence, the activity/registry seams and the manager adaptations phase 3 removes
- every remaining patch has a written reason

## Phase 2: Activity core

The Activity shim is a hand-written ContextWrapper, Dialog is hand-written, Instrumentation carries only the application half, and the lifecycle runs in HostedActivityRuntime behind the ActivityThread host adapter. The hosted ActivityThread already implements the ClientTransactionHandler contract, so the stock path is within reach: real `Activity`, `Dialog`, `Instrumentation` and the AOSP `performLaunchActivity` chain, driven by `LaunchActivityItem`/`ResumeActivityItem` transactions from the hosted activity manager.

### Steps

1. Hosted `IActivityTaskManager` and `IActivityClientController` in the system process: start, finish, result delivery, config-change relaunch decisions, task info for the single desktop task. Closes the activity-family stubs (ActivityManager, ActivityOptions, ActivityTaskManager, TaskInfo, AppGlobals, LocalActivityManager, ClientTransactionListenerController).
2. Fill the `notHosted` handlers in ActivityThread with the AOSP bodies: handleLaunchActivity, handleStartActivity, handleResumeActivity, handlePauseActivity, handleStopActivity, handleDestroyActivity, handleSendResult, handleNewIntent, handleActivityConfigurationChanged, prepareRelaunchActivity.
3. Real `Activity`. It attaches its PhoneWindow through the hosted WindowManagerImpl and ViewRootImpl, so the fence is untouched; the shim's request-window-feature and result plumbing go away with it.
4. Real `Instrumentation` (the execStartActivity half) and real `Dialog` over the same window manager.
5. Delete HostedActivityRuntime; ApkHost drives the first launch through the activity manager like a launcher would.
6. ui family: AlertDialog, Fragment, FragmentManager, FragmentTransaction, ActionBar, Presentation, DatePicker, TimePicker, FloatingToolbar. All replaceable today.

Tests must cover callback ordering, handled versus unhandled configuration changes, saved-state restore and result delivery, as the AOSP reuse policy requires. The full APK suite is mandatory for every commit in this phase.

#### Stubs closed

- activity 9
- ui 9

#### Risk

- The real Activity expects `WindowManagerGlobal.getWindowSession()`; today it returns `IWindowSession.Default()`, which is enough for attach but not for relayout. Keep that inside the fenced WindowManagerGlobal.
- NewPipe's fragments and the desktop fullscreen test are the regression surface.

## Phase 3: Client managers

Same recipe as the managers milestone: closure run, real manager, `Unsupported*` base from `tools/gen-unsupported-stub.py`, a hosted service serving the subset, an AOSP fetcher in the hosted SystemServiceRegistry.

### Hand-written managers still in `src/framework`

- `PermissionManager`: vendor the `android.permission` package from frameworks/base and back it with the hosted permission checker. The `PermissionControllerManager` stub goes with it. **SYNC** `platform/packages/modules/Permission` for the framework-s classes the closure reaches.
- `NsdManager`, `NsdServiceInfo`: real classes over a hosted NsdService on the nsd AIDL; the current `HostedNsdBackend` becomes that service's host adapter.
- `DownloadManager`: real manager over a hosted DownloadProvider; the Kotlin adaptation's transfer code moves into the provider.
- `MediaStore`: real contract over a hosted MediaProvider that serves the session's data root.
- `SystemSensorManager`: the real class is JNI over libsensor. Register the native seam in Zig with zero sensors and compile the real class.

### Pending manager families

| Family | Stubs | Blocker today | Approach |
| --- | ---: | --- | --- |
| packagemanager | 17 | installd/vold AIDL, ApexInfo, PackageProto, aconfigd | real PackageInstaller and IPackageInstaller now; server-side classes (Computer, PackageManagerService, Settings) become survivors named as server code, not client |
| storage | 4 | vold AIDL, ObbScanner JNI | hosted IStorageManager over the emulated volume; ObbScanner native seam |
| devicepolicy | 3 | biometrics HAL, SecurityLog JNI | after phase 5 syncs hardware/interfaces; SecurityLog native seam |
| virtualdevice | 4 | camera/audio AIDL | after phase 5; VirtualDeviceParams is replaceable now |
| user | 1 | property_cache annotation processor | guava is synced: wire the processor into the javac step, real UserManager |
| statusbar, shortcut, appops, companion, biometrics, battery, incident, preference, provider, connectivity | 12 | mixed | replaceable ones first (PreferenceManager, Downloads, NetworkSecurityConfigProvider, X509TrustManagerExtensions), the rest after phase 5 |

#### Stubs closed

- 39 across the families listed
- plus 4 hand-written manager files

#### Known gotchas

- The closure tool counts jar classes as present: seed it with every stub being replaced.
- AIDL roots go into `addAidlIncludes` and `aidlOutputPath` in `build/generators.zig` and the tool's `AIDL_ROOTS`.
- Generator inputs live in `build/generator_sources.zig`; the tool prints entries for it.

## Phase 4: Framework internals

- **webkit (16).** Twelve are one-line replaceable interfaces. CookieManager, WebSettings and WebChromeClient are blocked on the WebView provider closure: add a hosted `WebViewFactory` seam that reports no provider, then compile the real abstract classes.
- **runtime (10).** CancellationSignalBeamer, PermissionEnforcer, SynchronousResultReceiver, Immutable, BrightnessSynchronizer are replaceable now. BinderCallsStats needs the BinderLatencyProto output listed in `proto_outputs`; INetworkManagementService waits for Connectivity (phase 5); SystemServerClassLoaderFactory and UnboundedSdkLevel become server-side survivors.
- **logging (9).** Jank, Cuj, InteractionJankMonitor, MetricsLogger, Counter and ProtoLogConfigurationService are replaceable. ProtoLog needs the perfetto proto Java (perfetto is synced; add the protos to the generator). LatencyTracker needs the internal logging EventLogTags in the logtags list.
- **systemserver (6), textclassifier (4), inputmethod (4), contentcapture (4), accessibility (2), autofill (1).** Replaceable except InputMethodService (InputMethodServiceProto in `proto_outputs`), ImeTracingPerfettoImpl (perfetto protos again) and AccessibilityShortcutController (audio family, stays behind the fence).

#### Stubs closed

- 55, of which 41 are replaceable today

## Phase 5: Module trees

About 95 survivors exist only because their module tree is not in `aosp/aosp-projects.txt`. This phase is manifest work: sync the project, select its framework sources in `aosp/androlinzberg-files.txt`, repackage with `python3 aosp/package-androlinzberg-aosp.py`, bump the hash in `build.zig.zon` and extract the new tree under `zig-pkg/`. The HAL enums are AIDL and come out of the existing generator once `hardware/interfaces` is an include root.

| Project to sync | Survivors freed | Notes |
| --- | ---: | --- |
| `platform/packages/modules/Connectivity` | 13 | framework/src and framework-t; also unblocks INetworkManagementService, NetworkPolicyManager, NetworkScoreManager |
| `platform/hardware/interfaces` | 27 | radio, thermal, vibrator, usb, health, gnss, graphics: AIDL enums generated, HIDL constants compiled from the checked-in Java |
| `platform/external/libphonenumber` | 6 | replaces the phonenumbers stubs telephony carries |
| `platform/packages/modules/Wifi` | 3 | framework/java value types only |
| `platform/packages/modules/Media` | 3 | MediaSession2, Session2Token, MediaCommunicationManager |
| `platform/packages/modules/Permission` | 2 | also needed by phase 3 |
| `platform/packages/modules/Bluetooth` | 1 + audio closure | BluetoothDevice now; the codec-config types the audio family needs later |
| Tethering, SdkExtensions, IPsec, ConfigInfrastructure, AppSearch | 5 | one class each |
| `platform/packages/services/Telecomm` | 3 | telecom aconfig declarations, generated instead of hand-written |

Keep the packaged archive selective: add the framework source directories, not whole module trees, and let `tools/aosp-closure.py` name the files. The ART archive is out of scope here; its hash question is a separate upstream fix.

#### Stays a survivor

- JNI-only classes with no host meaning: StrictJarFile, incfs, fs-verity, ashmem, GraphicsEnvironment, PerformanceHintManager, Debug
- Chromium WebView provider
- camera stack, DRM (MediaCrypto), ToneGenerator

#### Sync command

```sh
cd aosp
repo sync -c -j5 platform/packages/modules/Connectivity
```

## Phase 6: Test seams out of `android.*`

- `ArtzigAccessibilityBridge` becomes the real `AccessibilityManager` over a hosted AccessibilityManagerService; the headless test interface reads the tree through `AccessibilityNodeInfo` like an accessibility service would. The accessibility spans from phase 1 then carry real ids.
- `ArtzigTestDiagnostics` moves to `dev.artzig`; the framework logs through the real `Log` and the launcher's liblog sink feeds the diagnostics journal.
- The ravenwood annotation copies and `RavenwoodEnvironment` are replaced by the vendored `frameworks/base/ravenwood` sources.

#### Exit criteria for the whole plan

- `src/framework` contains only the fence and the hosted SystemServiceRegistry/ActivityThread seams
- every stub in `tools/stub-audit.txt` is a survivor with a reason; the pending budget is 0 outside audio, opengl, media and window
- every remaining patch is documented as a host seam
- the full APK suite passes on the GPU harness after each phase

## Per-phase workflow

1. `tools/aosp-closure.py --classes <framework-classes.jar> closure --assume-present <cuts> <seeds>`; seed with every stub the phase deletes.
2. Apply its manifest lines, `build/java_sources.zig` lines and `build/generator_sources.zig` entries; repackage and bump the hash when the manifest changed.
3. `zig build --system zig-pkg -j16 gen-aidl`, generate `Unsupported*` bases, write the hosted service, register it.
4. Delete the stubs and their audit lines, lower `pending-budget`, drop them from `build/project_jvm_sources.zig`.
5. Unit chain, audits, launch test, full suite; regenerate `docs/stubs.md`; one commit, no attribution lines.

Gates: `build-tools-test` · `graph-audit` · `jvm-source` audit (java step) · `cpp-audit` · `integration-test`
