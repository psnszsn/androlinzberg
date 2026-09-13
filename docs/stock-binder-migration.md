# Stock Binder JNI over the hosted libbinder

Status: implementing. Parcel/state and Binder facade foundations are committed;
stock JNI is selected and focused regression checks pass. Full acceptance
and fresh-worktree verification remain. Baseline: repository `08a54c91`, pinned AOSP
`android-16.0.0_r1`. The [native audit](native-client-audit.md#stock-binder-jni-over-the-existing-facade)
records the compile probes and ownership gaps.

This replaces the obsolete plan to import AOSP Parcel storage and native
Binder transport machinery. The existing hosted facade is the chosen boundary.

## Outcome and scope

Compile these pinned AOSP units unchanged:

- `frameworks/base/core/jni/android_os_Parcel.cpp`
- `frameworks/base/core/jni/android_util_Binder.cpp`
- `frameworks/base/core/jni/android_os_ServiceManager.cpp`
- `frameworks/base/core/jni/android_os_ServiceManagerNative.cpp`

Java uses their JNI implementations over the existing C++ facade. AZKB retains
transport, broker routing, service registry and underlying AParcel storage.
The NDK ABI and hosted process identity/bootstrap facilities remain available.
This work restores stock Binder JNI; libbinder and libbinder_ndk remain hosted
adaptations. Audio, codecs, Wayland and kernel Binder migration are separate work.

Estimated custom production-code change: delete approximately 1,200–1,300
physical lines and add 400–700 facade/bridge lines. These are planning estimates,
exclude tests, and are not a limit on implementing correct behavior.

## Ownership and library boundary

```text
stock Java Binder / Parcel / ServiceManager
                    |
             unchanged AOSP JNI
                    |
       hosted C++ IBinder / Parcel facade
                    |
          shared Zig AIBinder / AParcel
                    |
                AZKB broker
```

Stock JNI owns JavaBBinderHolder, JavaBBinder, BinderProxyNativeData and Java's
C++ Parcel objects. A facade Parcel owns or borrows its underlying AParcel.
The native Java fields must never mix old Zig pointers with new C++ pointers.

Place original JNI in the existing native island, sharing libbinder's existing
RefBase/C++ runtime. Keep the dependency direction island -> libbinder ->
libbinder_ndk. Use a small explicitly installed C callback interface for the
NDK's three Java conversions if needed to avoid a reverse library dependency.
Resolve its initialization order in stage 1, including headless/service boot.
Do not depend on accidental symbol interposition or introduce a second runtime,
proxy cache or connection. Native-only NDK programs must still run without ART.

All generated interfaces come from pinned AIDL and existing build generators.
New JVM tests use Kotlin; repository-owned Zig I/O uses explicit std.Io.
Keep host behavior below JNI and compile all native consumers against the same
facade headers.

## Verified checkpoints

- Plan committed as `2f5cedb1`.
- Baseline native facade, NDK, ABI and broker checks passed. The JVM/Binder and
  focused NewPipe launch passed with explicit host Mesa paths (see
  [acceptance testing](acceptance.md)). The first JVM run failed before launch
  because `/run/opengl-driver` was absent; explicit installed Mesa paths fixed
  the environment. Logs and archive hashes are in
  `/tmp/azk-binder-jni-implementation/baseline*`.
- Parcel/state foundation: native facade, NDK, ABI, broker, JVM, Binder and
  focused NewPipe launch checks passed. New public-interface tests cover range
  and string validation, allocation accounting, first work-source header
  mutation, nested identity tokens and state restoration through an actual
  remote nested callback. Logs are `parcel-tests.log`, `parcel-contracts.log`
  and `parcel-jvm.log` under the same artifact directory. Committed as `e3e53e18`.
- Binder facade interfaces, proxy accounting, extensions and context-object
  discovery are committed as `ffe951c8`; native facade and NDK checks passed.
- The four original JNI units now compile and link unchanged. Direct Java/NDK
  conversion and GC lifetime tests and the production-input provenance audit
  pass (`stock-jni-reference-fix.log`). Native facade, Binder, focused NewPipe
  launch and JVM checks pass (`stock-jni-launch-fixed.log`,
  `stock-jni-jvm-fixed.log`). Regressions exposed the previously accepted null
  application shared-memory FD, input callbacks deleting borrowed global JNI
  references and the old unlink-after-death expectation; these now follow AOSP.
- Production switch: `84dc6e75`. Full acceptance exposed an alphabetic allowlist
  error, fixed and verified by `cpp-audit` in `7dadd078`. All 28 APK cases passed.
  The desktop stress sequence additionally exposed JavaBBinder destruction on
  a detached libutils input thread. The embedded runtime now installs the
  pinned AndroidRuntime `canCallJava` thread hook; the 16-action desktop
  regression passes with zero issues (`desktop-thread-fix.log`). JVM and Binder
  regressions pass (`thread-lifecycle-regressions.log`). Fresh-worktree
  acceptance is pending.

## Stage 1: establish the build and ownership contract

- Record the current revision, archive hashes and baseline results for
  `binder-cpp-test`, `ndk-test`, `ndk-abi`, `binder-broker-test`,
  `binder-test` and `jvm-test`; run the focused NewPipe launch test.
  Record any existing failure with artifacts before editing runtime behavior.
- Turn the four-source compile probe into a build-owned check using the exact
  selected headers, flags and generator outputs. Initially use it to inventory
  missing APIs/helper symbols; it is not a passing runtime gate.
- Inventory all reads/writes of the three Java native fields, JNI registrations,
  native finalizers and conversions, including graphics/NDK consumers outside
  ndk.zig. Record which library owns each replacement symbol.
- Trace pinned Parcel/IPCThreadState identity tokens, interface headers,
  StrictMode/work-source state and JavaBBinder callback/lifetime behavior.
  Specify owned versus borrowed references for each conversion and dispatch.
- Finalize callback installation and JNI registration order for app, system,
  headless test and child-process boot. Installation precedes first use; a
  missing bridge must fail explicitly rather than interpreting the old layout.

Exit: the dependency/ownership contract is recorded here, baseline evidence is
retained, and all known compile/link gaps are assigned to stages below.
Commit the baseline/build-check work without switching production JNI.

## Stage 2: complete Parcel and transaction-state behavior

Primary files: `src/native/binder/Parcel.cpp`, facade `Parcel.h` and
`IPCThreadState.h`, `src/binder/ndk.zig`, `src/native/binder/include/azk/`.

- Implement the missing string8, comparison, Binder/FD range, allocation/blob
  accounting and interface-token/work-source methods following the pin.
  Preserve bounds, errors, cursor position and object/FD ownership.
- Unify calling identity, explicit-identity state, StrictMode and work-source
  operations with the shared runtime. Save and restore the appropriate state
  across incoming and nested calls; preserve oneway PID/flag semantics.
- Reuse existing storage operations where correct. Move behavior needed by both
  JNI and C++ into the shared boundary; avoid leaving duplicate state machines.
- Verify token mutation, malformed input, range edges, append/reset/overwrite,
  unread Binder references, borrowed Parcels and FD cleanup at public interfaces.

Exit: relevant native/NDK/broker tests pass, with new contract tests for the
implemented behavior. Existing JNI still works. Commit this independently;
split Parcel and thread-state work into separate tested commits if substantial.

## Stage 3: complete Binder and service-manager interfaces

Primary files: `src/native/binder/Binder.cpp`, facade Binder/IBinder/BpBinder,
ProcessState, IPCThreadState, Stability and service-manager headers.

- Extract the private hosted BpBinder into its own matching header and
  implementation. Prevent fallback to AOSP's incompatible class definition.
- Implement subclass checks, extension lifetime/transactions, stability
  operations and proxy count tracking, UID counts, watermarks and callbacks.
  Follow pinned semantics and use broker-authenticated identity where needed.
- Expose thread-pool/scheduling and feature-query contracts. Frozen-state
  subscriptions report the pinned unsupported result when the host lacks that
  capability; do not advertise subscription support without delivery.
- Implement ProcessState's context object over existing AZKB object ID 1.
  Generate native IServiceManager through existing generator declarations and
  provide the typed IServiceManagerFFI entry point expected by original JNI.
- Test local/remote extensions, canonical wrapper identity, last-reference
  cleanup, death/unlink behavior, accounting thresholds and context discovery.

Exit: all four original JNI units compile, and their required facade/helper
symbols are accounted for. Native, NDK and broker regressions pass. Commit
Binder and service-manager work at tested boundaries.

## Stage 4: switch JNI ownership and registration together

Primary files: `src/binder/ndk.zig`, `framework_jni.zig`,
`src/native/AospParcelAbi.cpp`, native-island registration,
`src/main.zig`, `build/island.zig` and the source/generator declarations.

- Import and select the four original JNI units and their actual helper closure.
  Preserve native annotations, signatures and original registration behavior.
- Install the Java conversion bridge. AParcel_fromJavaParcel unwraps the C++
  facade Parcel; Binder conversions use the stock JNI helpers and the facade's
  acquireNative/fromNative ownership contract without recursion.
- Route Java callbacks through stock JavaBBinder::onTransact using borrowed
  facade Parcels. Retire javaForwardTransact's raw-AParcel callback convention.
  Preserve worker attachment/detachment, Java exceptions and oneway handling.
- Remove old Binder/Parcel/ServiceManager native registrations, Zig holders,
  proxy finalizers and conflicting AospParcelAbi helpers in the same switch.
  Retain host session bootstrap, shared utilities and unrelated registrations.
- Verify one owner per JNI/helper symbol and one shared Binder/runtime instance
  in the installed library graph. Check app, system and headless load order.
- Regenerate the framework archive when changing its manifest, serialize
  packager access, and update the actual Zig package pin in the same commit.
  Follow [packaging](../aosp/README.md); no direct edits to extracted caches.
  Refresh ART/WebView packages only if their inputs actually change.

Exit: mixed Java/C++/NDK ownership tests, Binder tests, JVM smokes, graphics
Parcel consumers and the focused APK launch pass against installed artifacts.
The production switch is one coherent commit. Reverting it restores the old
JNI as a unit; no mixed-layout runtime toggle is retained.

## Stage 5: complete regression and provenance acceptance

Extend the existing NdkBridgeTest, Binder client/server tests and native facade
tests, rather than building a separate process or headless test runner.

| Contract | Required observations |
|---|---|
| Java/C++/NDK conversions | Both directions, repeated conversions, local Binder returned to its owner, same live identity |
| Lifetime | GC and native last-release, borrowed/owned Parcel destruction, unread/copy/reset Binder and FD entries, no leaks or double frees |
| Dispatch | Nested callbacks, concurrent callers, oneway FIFO, worker reuse and correct attachment |
| Thread state | Caller UID/PID, nested clear/restore tokens, explicit identity, StrictMode and work-source restored correctly |
| Errors and death | Wrong token, service exception, unknown transaction, dead peer, link/unlink races and disconnect while blocked |
| Service lookup | Existing/missing service, wait then publish, notifications, removal/death and typed context proxy |
| Graphics consumers | Java Parcels used by stock native InputChannel, Surface/SurfaceControl, GraphicBuffer and fence clients |

Run the smallest affected target after each change. NdkBridgeTest currently
runs inside `jvm-test`; a focused alias may expose the same Run step if useful.

Run the complete `acceptance-test` once the installed candidate passes focused
checks. It includes the full APK suite; do not substitute a filtered launch or
a ViewHost test. Use [the documented command](acceptance.md), the pinned APK,
a clean pinned reference and at most max(1, nproc - 2) Zig jobs. Require expected
UI states, empty structured runtime issues and correct retained screenshots.
Run `ndk-abi` explicitly as well. On failures inspect steps.jsonl and app.log,
then the failure/tree/screenshot artifacts, and rerun the affected test.

Add a narrowly scoped native provenance check to the existing build audits:
the four compiled inputs match the clean pin, expected registrars resolve to
them, obsolete implementations are absent, and all consumers select matching
facade headers. The existing JVM source-identity check does not prove this.

Exit: full acceptance, ABI and targeted native provenance checks pass with
retained results tied to the revision, package hashes and installed artifacts.
Commit any remaining regression fixes incrementally.

## Stage 6: packaging verification and cleanup

- Remove temporary probe/build selection and dead helper declarations/imports.
  Keep useful regressions integrated into maintained targets.
- Update native-client-audit.md and native-boundary.md with the final JNI
  provenance and remaining hosted boundary. Replace obsolete documentation;
  do not leave two competing migration plans.
- Record actual custom lines removed/added separately from vendored AOSP and
  tests. Update each stage's completion status with its commits and checks.
- Verify current package checksums/pins and repeat acceptance in a fresh
  worktree using the documented archive-only procedure. Do not remove shared
  caches. Fix any clean-build or installed-library dependency failure.
- Commit final cleanup and verification results. Keep every implementation
  checkpoint buildable; include source selection and package pins together.

Done means all four JNI units are unchanged AOSP in the production build,
obsolete JNI and pointer-layout paths are deleted, Java/native callers share
one hosted Binder runtime, packages reproduce the build, and the complete
behavior/provenance gates pass. The final status explicitly retains hosted
libbinder/NDK/AZKB as adaptations.
