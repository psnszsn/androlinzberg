# Repository agent instructions

- Always run Zig build/run commands with `-j10` (for example,
  `zig build -j10 run`) to avoid overwhelming the system.

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
