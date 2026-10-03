# Repository agent instructions

- Always pass an explicit job limit to Zig build/run commands. Use at most
  `nproc - 2` jobs (for example, use `-j30` when `nproc` reports 32), with a
  minimum of `-j1` on systems where `nproc` reports two or fewer processors.
  Choose a lower value when system load or build memory usage requires it.

## Zig I/O policy

Use `std.Io` for Zig I/O and descriptor ownership. Pass or store the active
`std.Io` explicitly, wrap descriptors in `std.Io.File`, and perform reads,
writes, process operations, synchronization, and close through `std.Io`. Do
not use `std.c` in repository-owned Zig code. When `std.Io` lacks a Linux
object constructor such as `eventfd`, limit `std.os.linux` to creation and
immediately transfer the descriptor to a `std.Io.File`; all subsequent
operations and ownership remain in `std.Io`.

## JVM language policy

Use Kotlin for new repository-owned JVM code. Keep directly reused AOSP and
generated sources in Java, and do not copy or translate an AOSP class merely to
make it Kotlin. Follow `docs/kotlin.md` for compiler, interoperability, and
migration requirements.

## AOSP reuse policy

Treat the pinned AOSP checkout described by `aosp/README.md` and selected by
`aosp/androlinzberg-files.txt` as the reference implementation for Android
framework behavior. For work in `android.*` or `com.android.internal.*`, do not
begin with a new shim method.

1. Locate the corresponding class and method in the pinned AOSP version and
   trace the behavior far enough to identify its ordering, state, collaborators,
   and observable failure modes. Use the vendored version, not an arbitrary
   newer Android branch.
2. Check `build/java_sources.zig` to determine whether the AOSP class is already
   compiled, deliberately excluded, or replaced by a file in `src/framework`
   or `src/framework-stubs`. Never compile both the AOSP class and a replacement
   with the same fully-qualified name.
3. Reuse the AOSP source directly when its dependency closure is already
   available or can be added without importing an unavailable Android process,
   daemon, or hardware stack. Prefer changing the curated source list and
   deleting the replacement over copying AOSP code into a shim.
4. When direct reuse would require absent infrastructure such as
   `system_server`, AMS, PackageManagerService, Binder services, SurfaceFlinger,
   or a native daemon, adapt the AOSP control flow behind a small host seam.
   Preserve AOSP data types, lifecycle ordering, configuration masks, state
   transitions, return values, and exceptions. Keep the unavailable interaction
   in a host adapter; do not spread host checks through framework callers.
5. Write new behavior only when it is genuinely host-specific, such as the
   Wayland/EGL presentation adapter, desktop input translation, bionic bridging,
   or an in-process substitute for a service that does not exist in this host.
   Host-specific implementation belongs at that seam, not in application-aware
   branches inside Android framework classes.

For an AOSP adaptation, add a short source note naming the AOSP class/method
being followed and why the source cannot currently be compiled directly. Do
not special-case an application package, class, or workflow when an Android
framework contract explains the behavior.

Before replacing a stub with the real AOSP class, run
`tools/aosp-closure.py closure --classes <framework-classes.jar> <fqn>` against
a live build: it prints the manifest, `build/java_sources.zig` and
`build/generator_sources.zig` entries the real class needs and names any blocker (JNI
natives, an unvendored module tree, an AIDL file outside the include roots, an
unwired generator). Every stub is listed in `tools/stub-audit.txt` with the
family it is pending replacement in or a `survivor:` reason; regenerate it with
`tools/aosp-closure.py audit --update tools/stub-audit.txt --markdown docs/stubs.md`
after a replacement, delete the stub's line together with the stub, and lower
the `pending-budget` line. The `jvm-source` mode of `tools/build-audit.zig`
rejects an unlisted stub and a pending count above the budget.

Do not hand-write a class that Soong generates. aconfig `Flags`/`FeatureFlags`,
logtags `EventLogTags`, `*Proto` message classes, sysprop accessors, and
`FrameworkStatsLog` come from the Run steps wired through `build/generators.zig`
and `build/java.zig`, driven by the declarations in `build/generator_sources.zig`.
Add the package to the generator's input list there rather than writing a
placeholder;
The `jvm-source` mode of `tools/build-audit.zig`, fed by the exact Zig
`SourceSet`, rejects a repo-owned class whose package matches a generated
artifact (`.java` and `.kt`). A package whose declaration is not yet in a
vendored tree may stay hand-stubbed until its module tree is imported. The
`.aosp` dependency hash in `build.zig.zon` pins the extracted AOSP package
contents. Generator declarations and expected outputs live in
`build/generator_sources.zig`; do not add build-consumed text manifests.

Prefer deep modules at the AOSP/host seam: callers should provide an Android
operation such as “apply this configuration” or “launch this activity,” while
the module owns parsing, lifecycle decisions, saved-state handling, and callback
ordering. A production host adapter and an in-memory test adapter justify the
seam; avoid layers that merely rename individual AOSP calls.

When closing an API linkage gap such as `NoSuchMethodError`, match the exact
AOSP signature and semantics, including overloads and mutation/return behavior.
A no-op is acceptable only when AOSP defines the operation as observationally
irrelevant in the hosted environment; otherwise implement the behavior or emit
a structured unsupported diagnostic.

Verify changes at the module interface and, when UI-visible, through the
existing headless harness. Tests should cover AOSP-observable behavior rather
than private shim state. For adapted lifecycle code, explicitly test callback
ordering, handled versus unhandled configuration changes, saved-state restore,
and the absence of application-specific branches.

## APK UI tests

`zig build -j10 jvm-test` exercises framework and class-loading smokes, but it
does not launch an APK through `ApkHost`. Changes to `ApkHost`, activity or
application startup, input services, class loading, resources, windows, HWUI,
or lifecycle behavior must also run the UI tests. Do not treat a passing
`jvm-test` or built-in `ViewHost` test as a substitute.

The UI tests are JUnit tests written against androidx UiAutomator
(`tests/ui/kotlin`) and run on the hosted ART. `tools/ui-test-runner.zig` starts
a fresh session and a fresh runner process for every test method, under the
existing `tools/shot.sh` harness with `WLR_RENDERER=gles2`. The runner joins the
session as the broker's inspector (Android's shell uid) and reaches apps the way
instrumentation does on a device: stock `UiAutomation`, the session's real
`AccessibilityManagerService`, and `IInputManager` injection. There is no
private test-control protocol; do not add one.

NewPipe and F-Droid are pinned by SHA-256 in `build/ui_tests.zig`; provide
them by absolute path (F-Droid 2.0.1 is
`https://f-droid.org/repo/org.fdroid.fdroid_2000051.apk`). From outside the
development shell, run the complete suite with:

```sh
nix develop ./artzig -c env \
  AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  AZK_APK_FDROID_2_0_1=/absolute/path/to/org.fdroid.fdroid_2000051.apk \
  zig build -j10 ui-test
```

During development, run the smallest relevant test first. The launch test is
the minimum regression for changes that can affect `ApkHost.init`:

```sh
nix develop ./artzig -c env \
  AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j10 ui-test -- \
  --class NewPipeUiTest --method launchesHomeWithoutRuntimeIssues
```

Before merging such changes, run the complete suite without `--class` or
`--method`. Changes to the Wayland presenter, seat translation, clipboard, drag
and drop, text input or task windows must also run the desktop scenarios, which
run the same way under labwc: `stock-desktop-launcher-test`, `stock-ime-test`,
`stock-clipboard-test`, `stock-drag-test` and `stock-video-test`.

Per-test artifacts are retained under
`zig-out/test-results/ui/<class>.<method>/` (`zig-out/test-results/stock-*/` for
the desktop scenarios): `app.log` is the session's complete stdout/stderr and
`test.log` is the runner's, including the JUnit failure trace and one
`UiTest: ISSUE <kind> <owner>.<member> [process:pid] <detail>` line per runtime
issue. Inspect both on every failure. A regression is fixed only when the
expected UI state is reached and the test ends with `issues=0`.

## Writing UI tests

- Add a test by listing its source and `Class#method[@WxH[@apk]]` in
  `build/ui_tests.zig`. Do not create a second runner or a second
  Cage/labwc harness; extend `tools/ui-test-runner.zig` and `tools/shot.sh`.
- Write tests in the verbs of `dev.artzig.uitest.UiApp` over UiAutomator.
  Prefer semantic selectors (`By.res`, `By.text`, `By.desc`) over coordinates so
  a finding identifies the affected Android UI element.
- Assert through Android APIs: accessibility nodes, `UiApp.screenshot()`
  (SurfaceFlinger's composition, cropped to a window or view) and
  `UiApp.assertLog`. Only `dev.artzig.uitest.host` tests touch the desktop:
  `WaylandSeat` asks the task presenter for real Wayland pointer and key events,
  and `HostSession` runs desktop tools (`wl-copy`, `grim`, the native drag peer)
  for what Android cannot observe, such as the compositor's drag icon.
- Every test fails on any new runtime issue. Call
  `UiTest.allowRuntimeIssues()` only when deliberately inventorying an already
  known gap; never in a regression test intended to prove the gap is fixed.

Example:

```kotlin
class SignInUiTest {
    @Test
    fun signingInShowsTheWelcomeScreen() {
        click("Sign in")
        waitFor(By.res("com.example:id/email")).text = "user@example.com"
        expectVisible("Welcome")
    }
}
```

## Discovering missing Android functionality

1. Reproduce the behavior with the smallest possible UI test: one action and
   the expectation that follows it.
2. Read the `UiTest: ISSUE` lines in that test's `test.log` first. They come
   from the session's typed journal (`dev.artzig.testing.ISessionDiagnostics`:
   the system server's records merged with every attached app's), which is the
   authoritative missing-functionality journal:
   - `kind: "unimplemented"` identifies an explicit hosted-framework stub;
     use its `owner`, `member`, and `detail` fields to locate the implementation
     seam.
   - `kind: "runtime_exception"` identifies linkage, class-loading, verification,
     native-link, or generated `Stub!` failures observed in Android logging.
3. Use `app.log` and the accessibility state at the failure to explain what led
   to the issue. Raw stderr is supporting evidence, not the source of truth for
   classifying a framework method as unimplemented.
4. Do not classify the launcher's optional host capability probes as missing
   functionality merely because the process log contains `NoSuchMethodError`.
   These probes are expected for hosts that omit optional methods and are
   deliberately excluded from structured issues. Report them only when there
   is separate evidence of broken requested behavior.
5. After implementing a missing method, rerun the same test and require all of:
   the action succeeds, the test ends with `issues=0`, the expected UI state is
   present, and the retained screenshot or capture is correct.

When reporting a discovered gap, include the exact action, issue `owner` and
`member`, relevant log lines, the UI state before and after, and the paths to
the retained `app.log` and `test.log`.
