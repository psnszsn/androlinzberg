# Acceptance testing

`acceptance-test` runs the complete stock-client acceptance suite through the
existing Zig targets. It builds and installs the runtime and fixtures, runs
APK/JVM/system/native/Binder/graphics/Wayland/WebView and resource/recovery tests,
and performs build and source audits. The target list is maintained in
`build.zig`; it does not create a second test runner.

## Run the complete suite

Prepare the three local archives as described in [AOSP packaging](../aosp/README.md).
Provide the pinned NewPipe APK from `tests/apks.json`, a clean reference checkout
at the pinned Android 16 r1 revision, and the host graphics-driver environment.
On hosts that need explicit Mesa paths, supply `__EGL_VENDOR_LIBRARY_FILENAMES`,
`GBM_BACKENDS_PATH`, `LIBVA_DRIVERS_PATH` and `VK_DRIVER_FILES` as appropriate.

```sh
nix develop ./artzig -c env -u AZK_WINDOW_BACKEND -u AZK_TASK_WINDOWS \
  AZK_APK_NEWPIPE_0_29_1=/absolute/path/to/NewPipe_v0.29.1.apk \
  AZK_TEST_REQUIRE_EGL=1 \
  zig build -j10 acceptance-test -Daosp-reference=/absolute/path/to/clean/aosp
```

Use at most `max(1, nproc - 2)` jobs and lower the limit for memory or load.
The acceptance target requires GPU checks, default backend selection and the
complete suite; it rejects test filters. For focused development, run the
individual targets documented in [AGENTS.md](../AGENTS.md), such as
`integration-test -- --class NewPipeTest --method launchesHomeWithoutRuntimeIssues`.

UI checks launch real APKs using the existing `tools/shot.sh` Cage/labwc harness
and Kotlin testkit. Passing requires expected UI state and empty
`diagnostics.runtime.issues` for each action. Graphics checks also require
rendered pixels, buffer/fence ownership and transaction callbacks.

Artifacts are retained under `zig-out/test-results/`. On APK failures, inspect
the case's `steps.jsonl`, `app.log`, `failure.json`, accessibility tree and
screenshot. Inspect rendering screenshots when changing graphics or UI behavior.

## Source identity

```sh
nix develop ./artzig -c zig build -j10 source-identity-test \
  -Daosp-reference=/absolute/path/to/clean/aosp
```

This invokes `tools/aosp-source-identity.py` with the exact build-owned static
Java/Kotlin/annotation response files, extracted package and live classes jar.
It compares source bytes against the supplied reference, reports server
adaptations separately, and fails for modified clients, missing originals or
owned Android/internal client substitutes. The report includes the jar checksum
and is installed at `zig-out/test-results/source-identity.json` after success.
Unlike a cache-directory search, this cannot select an unrelated older build.
The reference must be the clean pinned checkout; the tool does not download or
select an AOSP revision. Generated sources retain their separate build audits.

This is a JVM source check for the root framework build. It does not compare
JNI implementations, native libraries, the separate Artzig/libcore closure,
ELF dependencies or process placement. Host sources under `dev.artzig` may
implement client operations through JNI callbacks. Passing this check and the
UI suite does not establish a fully stock native client; see the
[native client audit](native-client-audit.md).

## Binder JNI provenance

`binder-jni-source-test -Daosp-reference=/absolute/path/to/clean/aosp` compares
all four build-selected Binder JNI units against the pin, checks the actual
facade header search order and verifies unique registrar ownership in the
native island. It rejects the obsolete Zig JNI and pointer-layout helper.
The report at `zig-out/test-results/binder-jni-source.json` records source and
library hashes. This target and `ndk-abi` also run in `acceptance-test`.
`binder-jni-test` runs the existing Java/NDK conversion and lifetime checks alone.

## Verify a fresh worktree

Create a new detached worktree at the revision being verified. Before building,
confirm that `.zig-cache`, `zig-pkg` and `zig-out` do not exist there. Copy only
the current framework, ART and WebView archives into its `aosp/` directory,
verify their checksums against the source copies, and run the same command above
from that worktree. Normal Zig dependency resolution verifies package pins.

Record the revision, command, environment requirements, archive checksums, exit
status and source-identity report alongside the retained test artifacts. Check
that every APK case produced fresh results and inspect the rendering screenshots.
The acceptance target does not remove caches or create worktrees automatically.

## Latest verification

The complete command above passed on 2026-09-11 at `c7b6f3e9` in 10 minutes
36 seconds. All 28 APK integration cases passed; those cases and the WebView,
video, desktop, IME, drag and clipboard checks produced 323 actions with no
structured runtime issues. WebView content/video and desktop modal screenshots
were inspected. Source identity reported 7,081 unchanged AOSP inputs, 62 server
adaptations and no modified or repository-owned client substitutes.
