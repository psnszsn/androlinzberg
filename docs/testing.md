# Testing

## Native and JVM tests

Run the native tests and the source-boundary check:

```sh
zig build -j10 native-test cpp-audit
```

Run the framework and class-loading tests:

```sh
zig build -j10 jvm-test
```

Run the testkit tests:

```sh
zig build -j10 testkit-test
```

Run the multi-process hosted Binder tests. `native-test` already covers the
broker with in-process Zig peers; this target starts the broker and three ART
processes that talk through it:

```sh
zig build -j10 binder-test
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
