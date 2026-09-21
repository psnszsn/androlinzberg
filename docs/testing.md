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

Run the testkit tests:

```sh
zig build -j10 testkit-test
```

Run the native test-control transport tests without booting ART:

```sh
zig build -j10 test-control-test
```

These exercise fragmented and pipelined Varlink messages, reconnects, blocked
accept cancellation, descriptor cleanup, private socket publication, long Unix
paths and stalled peers. The transport accepts one client at a time through a
bounded `std.Io` accept task. Android actions and observations remain on the ART
main thread. Requests are limited to 1 MiB and queued replies to 16 MiB;
incomplete requests and blocked replies have a five-second transfer deadline.
Complete Android wait requests retain their requested settle timeout. Socket
publication sets mode 0600 before exposing the requested path and never replaces
an existing endpoint.

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

These tests do not replace an APK integration test.

## APK integration tests

`tests/apks.json` identifies each test APK and its SHA-256 value. Set the
environment variable for each APK that the selected test uses.

Run one NewPipe launch test:

```sh
AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j10 integration-test -- \
  --class NewPipeTest --method launchesHomeWithoutRuntimeIssues
```

Run the complete registered suite:

```sh
AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j10 integration-test
```

The testkit checks the APK checksum before it starts the application.

Each test writes artifacts to
`zig-out/test-results/integration/<class>.<method>/`. Inspect `steps.jsonl` and
`app.log` after a failure. A failed test can also contain `screenshot.png`,
`a11y.json`, and `failure.json`.

## Headless UI tests

Use `tools/shot.sh` for a reproducible Wayland test. The script starts the
existing headless Cage environment.

```sh
WLR_RENDERER=gles2 tools/shot.sh result.png --test /path/to/test-script -- \
  ./zig-out/bin/androlinzberg /absolute/path/to/app.apk
```

The test program can use `tools/azk-test` to select Android UI elements. Prefer
resource ID, text, description, or role selectors. Do not use screen
coordinates when a semantic selector is available.

Use `step` for each action that can expose a framework gap:

```sh
tools/azk-test wait ready
tools/azk-test step click --text "Sign in" --settle idle
```

`step` records new logs, accessibility events, framework issues, and the final
accessibility tree. It returns a failure when the action fails or reports a new
framework issue.

Use `--allow-missing` only when you record a known issue. A regression test must
finish with an empty `diagnostics.runtime.issues` list.

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
events, so input crosses the compositor, the presenter's seat translation and
the stock InputDispatcher, and they assert the app's reaction through the
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
its source and `Class#method` in `build/ui_tests.zig`. This path replaces the
Varlink test-control protocol; the legacy `integration-test` suite remains
until its tests have been ported.

