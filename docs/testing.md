# Testing

Run [acceptance testing](acceptance.md) for the complete suite and source
provenance checks. The targets below support focused development.

## Native and JVM tests

Run the native tests and the source-boundary check:

```sh
zig build -j10 native-test cpp-audit
zig build -j10 graph-audit
```

`graph-audit` rejects deleted build-script references, install-prefix inputs,
duplicate absolute-root build options, and project/generated `.cwd_relative`
paths. The JVM provenance and native boundary checks receive the same exact
source and artifact sets as their build consumers.

Run the framework and class-loading tests:

```sh
zig build -j10 jvm-test
```

Every test command gets its own `ARTZIG_DATA_DIR`; only `zig build run` uses
the user's data root. A test process that runs as an app package is an
instrumented package, as on a device: its session installs the package first
(`AZK_SESSION_INSTALL`, a colon-separated list of absolute APK paths that the
system server installs through stock PackageInstaller sessions before it
reports ready), and the process then authenticates with the uid
PackageManagerService assigned. Apps cannot install packages through the host
launcher. Android processes work from `/`, so harnesses pass absolute paths.

Run the multi-process hosted Binder tests. `native-test` already covers the
broker with in-process Zig peers; this target starts the broker and three ART
processes that talk through it:

```sh
zig build -j10 binder-test
```

Run the system-service tests (broker + system process + two app peers binding a
service across processes through the activity manager, and resolving each
other's packages through the session's package manager). The test packages are
resources-only fixture APKs built at build time from
`testdata/fixture-apks/*/AndroidManifest.xml` (aapt2, signed with the
`tools/signing` azk-platform test key — the same key that signs
framework-res.apk, which is what makes the signature-protected
`WRITE_SECURE_SETTINGS` grant test real); the peers install them through the
system process and their code loads from framework.jar:

```sh
zig build -j10 system-test
```

These tests do not replace an APK UI test.

## Optional Binder graphics probe

`tools/prototypes/binder-facade/build-production-probe.py` links the production
Binder libraries to a standalone DMA-BUF/Fence transport fixture. It requires
the full pinned AOSP checkout, `/dev/dri/card1` with dumb-buffer/PRIME and
syncobj sync-file export, and the same Zig/libc++ toolchain as the application.
The test mapper carries opaque probe metadata; it does not exercise production
gralloc/EGL import and is outside `acceptance-test`.

```sh
nix develop ./artzig -c zig build -j6
nix develop ./artzig -c python3 tools/prototypes/binder-facade/build-production-probe.py \
  /tmp/facade-production-graphics /absolute/path/to/aosp \
  --binder zig-out/lib/art/libbinder.so --ndk zig-out/lib/art/libbinder_ndk.so
python3 tools/binder-facade-test.py \
  --output /tmp/facade-production-graphics \
  --probe /tmp/facade-production-graphics/probe \
  --library zig-out/lib/art/libbinder.so --library zig-out/lib/art/libbinder_ndk.so \
  --broker zig-out/bin/azk-binder-broker
```

The harness checks remote pixel mutation, buffer metadata, sync-file transfer
and peer death. `run/results.json` records library hashes and results. Use the
normal graphics and APK gates to verify production rendering.

## UI tests on ART (UiAutomator)

`zig build -j10 ui-test` runs JUnit tests written against the pinned androidx
UiAutomator (`tests/ui/kotlin`) on the hosted ART itself. They reach apps the
way instrumentation does on a device: stock `UiAutomation` registered with the
session's real `AccessibilityManagerService`, node queries through each app's
`AccessibilityInteractionController`, and input through
`IInputManager.injectInputEventToTarget` into the stock InputDispatcher.

`tools/ui-test-runner.zig` orchestrates them under the Cage harness. An ART
process authenticates to one session's broker when it starts, so every test
method gets a fresh session running the app and a fresh runner process. The
runner joins with `AZK_BINDER_ROLE=inspector`: the broker admits it as Android's
shell uid (only in a session started for inspection), which holds the
permissions the pinned Shell manifest requests and may register no service.
`dev.artzig.testing.HostTestRunner` loads the test dex (`AZK_TEST_DEX`) and keeps
the main looper running while the test executes.

```sh
zig build -j10 ui-test -- --class WindowInputUiTest --method clickingButtonUpdatesStatus
```

Ordinary tests inject input through `UiAutomation`. The host seam has its own
coverage in the same suite: `dev.artzig.uitest.host` tests ask the task
presenter's inspection methods (`WaylandSeat`) for real Wayland pointer and key
events, so input crosses the compositor, the seat's evdev devices, stock
InputReader and InputDispatcher, and they assert the app's reaction through the
Android APIs; `probe#<check>` tests run `tests/ui/probe`, a native inspector on
the Zig generated from the presenter's AIDL, for what needs no app state
(presented frames advancing with Wayland frame callbacks, resource accounting).
The presenter admits the shell identity for its inspection methods only.

A test passes only with the expected UI state and an empty runtime issue
journal. The runner reads `dev.artzig.testing.ISessionDiagnostics` (typed
`RuntimeIssue`/`RuntimeLog` parcelables: the system server's records merged with
every attached app's) before and after the test and fails on any new issue,
printing each as `UiTest: ISSUE <kind> <owner>.<member> [process:pid] <detail>`.
A test that deliberately inventories a known gap calls
`UiTest.allowRuntimeIssues()`.

Artifacts are retained under `zig-out/test-results/ui/<class>.<method>/`
(`app.log` for the session, `test.log` for the runner). Add a test by listing
its source and `Class#method` in `build/ui_tests.zig`.

### Desktop scenarios

The same runner drives labwc desktop sessions, where the APK is launched
through ATMS as task windows: `stock-desktop-launcher-test` (modal hit-testing,
the custom pointer icon, Wayland keyboard text, two independent tasks, a
foreign shell joining the session), `stock-ime-test` (text-input protocol),
`stock-clipboard-test` and `stock-drag-test` (selection and drag and drop in
both directions, against `wl-copy`/`wl-paste` and the native drag peer;
`AZK_TEST_DRAG_ROUNDS` sets the repeat count, 16 by default) and
`stock-video-test` (MediaCodec output per view, on SurfaceFlinger captures).
The orchestrator starts the keyboard seat, takes the window placement from the
compositor's output, closes the task windows through the seat at teardown and
fails a session whose log lacks `task presenter released all buffers` or
contains fatal output. Artifacts are under `zig-out/test-results/stock-*/`.

The `stock-task-*` steps remain native pixel gates
(`tests/native/stock-wayland-task-test.py`): they synchronize a system-test
driver with compositor screenshots and use no UI automation.

