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
`tools/aidl-list.txt` lines the real class needs and names any blocker (JNI
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
and `build/java.zig`, driven by the declaration files and explicit input lists
under `tools/`. Add the package to the generator's input list rather than
writing a placeholder;
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

## APK integration tests

`zig build -j10 jvm-test` exercises framework and class-loading smokes, but it
does not launch an APK through `ApkHost`. Changes to `ApkHost`, activity or
application startup, input services, class loading, resources, windows, HWUI,
or lifecycle behavior must also run the APK integration tests. Do not treat a
passing `jvm-test` or built-in `ViewHost` test as a substitute.

The pinned APKs and their environment variables are declared in
`tests/apks.json`. For NewPipe, provide the APK by absolute path; the testkit
checks its SHA-256 before launch. From outside the development shell, run the
complete suite with:

```sh
nix develop ./artzig -c env \
  AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j10 integration-test
```

During development, run the smallest relevant test first. The launch test is
the minimum regression for changes that can affect `ApkHost.init`:

```sh
nix develop ./artzig -c env \
  AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  zig build -j10 integration-test -- \
  --class NewPipeTest --method launchesHomeWithoutRuntimeIssues
```

Before merging such changes, run the complete suite without `--class` or
`--method`. The integration target already uses `WLR_RENDERER=gles2` and the
existing `tools/shot.sh` Cage harness. It also runs the testkit unit tests; run
them alone with `zig build -j10 testkit-test` when changing the testkit.

Per-test artifacts are retained under
`zig-out/test-results/integration/<class>.<method>/`. Inspect `steps.jsonl` and
`app.log` on every failure; `screenshot.png`, `a11y.json`, and `failure.json`
are additionally retained for failed tests. A regression is fixed only when
the expected UI state is reached and every step has an empty
`diagnostics.runtime.issues` list.

## Headless Wayland UI testing

- Use the existing `tools/shot.sh` headless Cage harness for Wayland UI work.
  Extend it when necessary; do not create a second Cage/headless runner.
- Use `WLR_RENDERER=gles2` for the hosted Android/HWUI application.
- Put UI interactions that are meant to discover framework gaps behind
  `tools/azk-test step`. A step captures a mark, performs one action, waits for
  its requested settle condition, and collects only the logs, issues,
  accessibility events, and tree state produced after that mark.

Example test executable:

```sh
#!/usr/bin/env bash
set -euo pipefail
tools/azk-test wait ready >/dev/null
tools/azk-test step click --text "Sign in" --settle idle >/dev/null
tools/azk-test step type --id com.example:id/email user@example.com \
  --settle idle >/dev/null
tools/azk-test wait exists --text "Welcome" >/dev/null
```

Run it through the repository harness:

```sh
WLR_RENDERER=gles2 tools/shot.sh /tmp/login.png \
  --test ./tests/login-ui -- \
  ./zig-out/bin/androlinzberg app.apk
```

For an output such as `/tmp/login.png`, inspect:

- `/tmp/login.steps.jsonl`: one complete JSON record per `step`.
- `/tmp/login.log`: the complete application stdout/stderr stream.
- `/tmp/login.failure.json`: final diagnostics and accessibility tree when the
  test failed.
- `/tmp/login.a11y.json`: final accessibility observation.
- `/tmp/login.png`: final rendered frame.

## Discovering missing Android functionality

1. Reproduce the behavior with the smallest possible `azk-test step` action.
   Prefer semantic selectors (`--id`, `--text`, `--description`, `--role`) over
   coordinates so the finding identifies the affected Android UI element.
2. Read `diagnostics.runtime.issues` in that step's JSONL record first. This is
   the authoritative missing-functionality journal:
   - `kind: "unimplemented"` identifies an explicit hosted-framework stub;
     use its `owner`, `member`, and `detail` fields to locate the implementation
     seam.
   - `kind: "runtime_exception"` identifies linkage, class-loading, verification,
     native-link, or generated `Stub!` failures observed in Android logging.
3. Use `diagnostics.runtime.logs`, `diagnostics.android.events`, `raw_log`, and
   the before/after accessibility trees to explain what led to the issue. Raw
   stderr is supporting evidence, not the source of truth for classifying a
   framework method as unimplemented.
4. Do not classify the launcher's optional host capability probes as missing
   functionality merely because the process log contains `NoSuchMethodError`.
   These probes are expected for hosts that omit optional methods and are
   deliberately excluded from structured issues. Report them only when there
   is separate evidence of broken requested behavior.
5. By default, `azk-test step` exits nonzero when it observes a new structured
   issue. Use `--allow-missing` only when deliberately inventorying an already
   known gap; do not use it in regression tests intended to prove the gap is
   fixed.
6. After implementing a missing method, rerun the same step and require all of:
   the action succeeds, `diagnostics.runtime.issues` is empty, the expected UI
   state is present, and the Cage screenshot is correct.

For a manual sequence outside `step`, use cursor-based diagnosis so startup
noise is not attributed to the action:

```sh
tools/azk-test mark > /tmp/before.json
tools/azk-test click --text "Continue" --settle idle
tools/azk-test diagnose --since @/tmp/before.json
```

When reporting a discovered gap, include the exact action, issue `owner` and
`member`, relevant log lines, before/after UI state, and paths to the retained
`.steps.jsonl`, `.failure.json`, and screenshot artifacts.
