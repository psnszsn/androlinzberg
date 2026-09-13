# Implementing the hosted libbinder facade

Decision: retain the Zig Parcel, Binder objects and AZKB transport as the shared
implementation. Supply the C++ source API expected by pinned Android 16 consumers,
reusing AOSP RefBase, strings and interface helpers. JNI/NDK and future native Zig
clients share the same runtime and encoding. This records the original facade
implementation. The next migration is [stock JNI over this facade](stock-binder-migration.md).

## Scope and completion gates

Implement a built, installed C++ Binder library and integrate its genuine Parcel
objects into the existing native island. Support the current JNI consumers and
the graphics interface/Flattenable contract exercised by the prototype. Missing
host capabilities must return explicit unsupported errors; the facade must not
claim kernel Binder, arbitrary prebuilt C++ ABI, Android gralloc, SurfaceFlinger,
or full libgui bring-up. Those remain separate graphics work.

1. **Shared ownership and Parcel correctness.** Retain outgoing and received
   Binder entries independently of reads; count broker deliveries once, preserve
   references through copy/reply, release unread objects, and remove overwritten
   entries. Delay local export until send and cover abandoned/failed transactions.
   Unify NDK/Java/C++ object-aware copying, FD ownership, size/cursor/error behavior.
2. **Death and dispatch behavior.** Preserve a broker death subscription while
   any observer needs it. Make unlink/delete safe around callbacks. Expose caller
   identity, actual incoming flags and local/remote dispatch through a small C
   interface. Preserve nested calls and ordered oneway delivery. Unsupported
   polling or scheduling capabilities must not report false success.
3. **C++ source facade.** Introduce genuine independently backed Parcel objects,
   canonical live Binder wrappers, AOSP strong/weak references and Bp/Bn helpers,
   status/exception handling, primitive/container/native handle/Flattenable APIs,
   service lookup and process/thread adapters. Keep OS and transport operations in
   Zig. Provide a reusable build target and installed library with one shared
   implementation of its AOSP utility dependencies.
4. **JNI and build integration.** Replace `AospParcelAbi.cpp`'s fabricated pointer
   and thread-local backing with a per-native-Parcel C++ view and explicit lifetime.
   Compile all consumers against the same facade headers. Extend the pinned source
   package, generator declarations, native boundary audit and installed mappings.
   Package using a separate AOSP checkout if necessary to avoid the ongoing stock
   migration's temporary patches.
5. **Regression gates.** Add permanent interface-level tests for native Parcel
   ownership/copy/reset/overwrite, live and released Binder identity, status and
   error propagation, callbacks, caller identity and death/unlink. Run a generated
   graphics proxy/stub against the production library and retain the real
   GraphicBuffer/Fence transport experiment as an optional hardware test. Run the
   existing native/NDK/Binder/mixed-language/JVM checks and APK integration suite
   after replacing the native island bridge. Inspect any integration failure's
   structured issues and retained artifacts.

## Coordination

Work only in this worktree. Do not reset, merge into, or modify the stock-client
migration worktree. Shared-runtime and native build edits may overlap when these
branches merge; record those paths and the final validation baseline. No changes
to the frozen Java window/media/graphics seam are needed for this Binder work.

## Progress

- Plan recorded before implementation. Prototype results are documented in
  `libbinder-facade-feasibility.md` and `tools/prototypes/binder-facade/README.md`.
- Implemented the shared runtime ownership, death/dispatch changes, production
  C++ library, JNI view and build/package integration in this worktree.
- The production generated-graphics and broker protocol regressions pass. Native,
  NDK ABI, mixed-language Binder and system-service tests pass. The full APK suite
  passes (11 integration tests and 20 testkit tests); no step reports runtime issues.
- `jvm-test` has two pre-existing failures reproduced in the untouched
  `cbd43623f2b0cc60cebd607eec5f17fc26ddf5fb` checkout: `FrameworkModuleSmoke`
  (`testHostedSystemServices`, external files) and `FrameworkCompatSmoke`
  (`testOsTail`, external storage) pass package `android` from uid 10001 to the
  stock storage service. Both receive `SecurityException: uid 10001 does not own
  package android`. The Binder JNI sharing smoke passes.

## Using the implementation

`zig build -j6 binder-cpp` builds `libbinder.so`; normal installation places it
beside `libbinder_ndk.so` in `zig-out/lib/art`, with a sibling-only `$ORIGIN`
runtime search path. The native island links it, and
Java Parcel JNI consumers now receive a persistent per-Parcel C++ view.
Source-built native clients put `src/native/binder/include` before the pinned
AOSP include roots listed in `build/binder_cpp.zig` and link both libraries.
Use Zig's C++ toolchain/libc++ consistently with the island. The API is for
source-built consumers, not arbitrary prebuilt Android binaries.

`zig build -j6 binder-cpp-test binder-broker-test` runs the focused regressions.
The C++ test compiles the real generated `android.gui.IFpsListener` proxy/stub,
uses AOSP `Status` (including NDK/C++ exception-header interoperability), and checks independent Parcel views, raw UTF-16, owned Binder
copy/overwrite/truncation, repeated reads, unread deliveries, rejected exports,
FD duplication/close-on-exec, transport bounds, local and remote identity, nested
calls, oneway ordering, exact errors, and multiple death recipients with unlink.
Death callbacks issue a synchronous service lookup to detect reader-thread
reentrancy deadlocks. Broker tests cover discarded exports and invalid replies
waking their waiting caller.

Retained output: `zig-out/test-results/binder-facade/run/results.json` and the
broker/server/client logs beside it. APK step logs and images live beneath
`zig-out/test-results/integration/`. Validation here required the host's Mesa
EGL, GBM and VAAPI driver paths because this environment has no default driver
search installation:

```sh
nix develop ./artzig -c env \
  __EGL_VENDOR_LIBRARY_FILENAMES=/nix/store/4cvv9wbvhz36a1fhd9px1rvjr8j61ycr-mesa-26.2.1/share/glvnd/egl_vendor.d/50_mesa.json \
  GBM_BACKENDS_PATH=/nix/store/4cvv9wbvhz36a1fhd9px1rvjr8j61ycr-mesa-26.2.1/lib/gbm \
  LIBVA_DRIVERS_PATH=/nix/store/4cvv9wbvhz36a1fhd9px1rvjr8j61ycr-mesa-26.2.1/lib/dri \
  LIBVA_DRIVER_NAME=radeonsi \
  AZK_APK_NEWPIPE_0_29_1=/home/vlad/.cache/azk-apks/NewPipe_v0.29.1.apk \
  zig build -j6 integration-test
```

These are local validation paths, not product defaults. Before setting them,
Cage failed to load EGL/GBM, and video tests later failed to initialize VAAPI.
With all three drivers selected, the same tests pass.

## Remaining graphics work and merge considerations

This implements the facade foundation and the current native consumer closure.
It does not claim the entire libbinder header surface or full libgui support.
Additional libgui consumers may require further source APIs, including AIDL
object attachments/delegators, nullable/container overloads, permission checks
and scheduling queries. VINTF negotiation, kernel Binder/RPC, polling dispatch,
Android gralloc and SurfaceFlinger remain unavailable. Supported hosted endpoints
share one compilation/encoding domain; no VINTF service is advertised. Missing
headers/members fail compilation and unsupported exposed operations return errors.

The optional hardware probe also passed against the installed production
`libbinder.so` and `libbinder_ndk.so`, including real DMA-BUF pixel changes,
sync_file/NO_FENCE transport and buffer metadata. Its invocation is documented
in `tools/prototypes/binder-facade/README.md`.

The hardware experiment uses unmodified AOSP GraphicBuffer/Fence serialization
with an opaque-handle test mapper. It does not prove production GPU import,
Android buffer allocation, BufferQueue scheduling or Wayland task windows.

The stock migration can continue independently. Merge conflicts are most likely
in `src/binder/ndk.zig`, `src/binder/Broker.zig`, `build.zig`, `build/island.zig`,
`build/application.zig`, `build/generators.zig`, `build/generator_sources.zig`,
the AOSP manifest/hash and the native boundary audit. Repackage the combined
manifest using the documented packaging procedure; do not choose one branch's
package hash independently of its source list. No stock-client worktree files
were edited.
