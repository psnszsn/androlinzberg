# Repository agent instructions

- Always run Zig build/run commands with `-j10` (for example,
  `zig build -j10 run`) to avoid overwhelming the system.

## JVM language policy

Use Kotlin for new repository-owned JVM code. Keep directly reused AOSP and
generated sources in Java, and do not copy or translate an AOSP class merely to
make it Kotlin. Follow `docs/kotlin.md` for compiler, interoperability, and
migration requirements.

## AOSP reuse policy

Treat the vendored AOSP tree under `artzig/extra/frameworks-base` as the
reference implementation for Android framework behavior. For work in
`android.*` or `com.android.internal.*`, do not begin with a new shim method.

1. Locate the corresponding class and method in the vendored AOSP version and
   trace the behavior far enough to identify its ordering, state, collaborators,
   and observable failure modes. Use the vendored version, not an arbitrary
   newer Android branch.
2. Check `tools/java-srcs.py` to determine whether the AOSP class is already
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
