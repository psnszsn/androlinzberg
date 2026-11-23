# AOSP source checkout and packages

This directory contains the selective AOSP checkout used to build the source
packages consumed by Artzig and Androlinzberg. The checkout is pinned to
`android-16.0.0_r1`; it is not intended to contain every project in the AOSP
manifest.

The Androlinzberg package includes the selected Soong Rust 1.83.0 compiler and
its Linux x86-64 runtime for stock libinput. Its crate closure is compiled from
the pinned source trees listed in `build/rust_sources.zig`; Cargo does not fetch
dependencies during a build. The development shell supplies bindgen and sets
`AZK_AOSP_TOOL_LIBRARY_PATH` for the compiler's host zlib dependency. On a
standard Linux installation the loader can normally find zlib without that
override.

The AIDL compiler is compiled from `system/tools/aidl` by `build/aidl.zig`
with flex and bison from the development shell; the package no longer carries
the `prebuilts/sdk/tools/linux/bin/aidl` executable.

The host Looper patch exposes a borrowed epoll descriptor and the next native
message deadline to native/Java integration tests. APK processes run the stock
Java Looper; Wayland runs in the separate task presenter. The application compiles
pinned
`android_os_MessageQueue.cpp`, `android_view_InputChannel.cpp`,
`android_view_InputEventReceiver.cpp` and `android_view_InputEventSender.cpp`
directly; queue and input callbacks retain their stock native object lifetimes.
The island also reuses InputApplicationHandle/InputWindowHandle JNI and its
Rect/Size/region helpers for the Java/native window-metadata boundary.

## Create the checkout

Enter the development shell from the repository root. It provides Google's
`repo` tool and the other host tools used by the build:

```sh
nix develop ./artzig
```

Initialize Repo inside this directory:

```sh
cd aosp
repo init \
  -u https://android.googlesource.com/platform/manifest \
  -b android-16.0.0_r1 \
  --depth=1 \
  --partial-clone \
  --clone-filter=blob:none
```

Sync only the projects selected in `aosp-projects.txt`:

```sh
xargs repo sync -c -j5 < aosp-projects.txt
```

The `xargs` form works in both POSIX shells and Fish. Increase the job count
only when the machine has enough CPU, memory, and network capacity; do not use
more than `-j10`. Do not run a bare `repo sync`, because the release manifest
contains many projects that neither package uses.

When a package starts using another AOSP project, add its manifest project name
to `aosp-projects.txt`, keep that file sorted, and sync just that project, for
example:

```sh
repo sync -c -j5 platform/external/skia
```

The values in `aosp-projects.txt` are manifest project names, which are not
always the same as checkout paths. For example, `platform/build` is checked out
at `build/make`.

## Select packaged files

The two package manifests are:

- `artzig-files.txt`
- `androlinzberg-files.txt`

Each non-comment line has the form `<kind> aosp <path>`:

- `F` includes one Git-tracked regular file.
- `L` includes one Git-tracked symbolic link.
- `D` includes all Git-tracked files below a directory. A project root is
  allowed and includes the complete project.
- `P` includes a file created by one of the package's patches. It must not exist
  before patching.

`artzig-files.txt` additionally accepts `X` to exclude all Git-tracked files
below a file or directory selected by a broader rule. Exclusions are applied
after all inclusions.

Prefer exact `F`/`L` entries unless a directory is a cohesive source closure or
most of its files and bytes are used. Paths are checkout paths such as
`frameworks/base/...`, not manifest project names.

If a build reports a missing AOSP input, add the smallest suitable rule to the
appropriate file manifest. If the path belongs to a project that is not already
selected, also add and sync that project as described above.

## Refresh Perfetto build declarations

`tools/gen-perfetto-sources.py` exports the pinned Linux Perfetto C SDK's GN
source and generator declarations into `build/gen/perfetto_sources.zig` and
`build/generator_sources.zig`:

```sh
nix develop ./artzig -c python3 tools/gen-perfetto-sources.py aosp/external/perfetto
```

This refresh uses GN from the development shell. Normal builds use Zig and the
packaged AOSP protobuf, build-flag, and version generators; they do not run GN
or Ninja or download Perfetto buildtools. `zig build -j10 perfetto` builds the
SDK and its generators alone.

## Regenerate the tarballs

The Androlinzberg package includes the pinned `prebuilts/r8/keepanno-annotations.jar`
and R8's license. This is AOSP's `keepanno-annotations` Java import, containing
the original generated annotation API. The build extracts its annotation class
package into the framework classpath without including the R8 compiler there.
The annotation artifact's SHA-256 is
`85925982c269c0c3a1edc121cfc5cf3dbde86e869bdcaa5f0fb9acd1d84181a9`.

Run the packagers from the repository root, one after the other:

```sh
python3 aosp/package-artzig-aosp.py
python3 aosp/package-androlinzberg-aosp.py
```

Archives contain source roots such as `frameworks/` directly, without an enclosing
release directory. Zig's local `file:` fetch must produce the paths consumed by
`dependency.path()` without manually flattening its cache. File permissions are
normalized to 0644 or 0755 according to the executable bit, independently of the
checkout's umask. `build-tools-test` checks these archive contracts.

From a worktree without its own Repo checkout, point the Artzig packager at the
shared pinned checkout with `ARTZIG_AOSP_ROOT=/absolute/path/to/aosp`.

After regenerating an archive for the Nix package, register it in the store
and update its hashes as described in [packaging](../docs/packaging.md).

Do not run the packagers concurrently: their patch sets overlap. Each script:

1. refuses to run when relevant tracked AOSP files are modified;
2. expands its file manifest using Git-tracked files only;
3. temporarily applies the package's patches;
4. writes a deterministic archive with normalized ordering, ownership, and
   timestamps while preserving symlinks;
5. reverses the patches and verifies that the checkout was restored; and
6. prints the archive path, source and archive sizes, file counts, and SHA-256.

For this release the outputs are:

```text
aosp/artzig-aosp-android-16.0.0_r1.tar.gz
aosp/androlinzberg-aosp-android-16.0.0_r1.tar.gz
```

The scripts replace each output atomically only after packaging and cleanup
succeed. On an error or interruption they remove the temporary archive and
attempt to restore every applied patch before exiting.

## WebView provider

The runtime installs the unchanged official AOSP WebView **140.0.7339.15** APK
from `platform/external/chromium-webview` commit
`27d78d3db63b2dbe56d1136b7cefc502ca50548d`, path
`140.0.7339.15/x86_64/webview.apk` ([original artifact](https://android.googlesource.com/platform/external/chromium-webview/+/27d78d3db63b2dbe56d1136b7cefc502ca50548d/140.0.7339.15/x86_64/webview.apk)).
This provider has its own package pin; it does not change the Android 16 r1
framework reference. The framework source archive does not include a provider APK;
the independent WebView package is its only source for installation.

Download the original artifact, then package it locally:

```sh
python3 aosp/package-webview.py --apk /absolute/path/to/webview.apk
```

The script verifies the APK's 327,642,777-byte size, SHA-256
`04b161c3e5cd125068e41633d60b027f9c96f46fbd5ae79a0b4e86eb71ea01af` and Git blob
`0b8ea7c8f0961d1d84392830021673ec037f5215`. It atomically writes the deterministic
`aosp/androlinzberg-webview-140.0.7339.15.tar.gz`, containing `webview.apk` and
`SOURCE.json`. Its SHA-256 is
`ff78ad3c60e851a5ff3d217d3a2d16074f453f1814a4c4bbd105b54996e9918d`; the `.webview`
dependency in `build.zig.zon` pins the extracted package to
`N-V-__8AAF1whxNdNKACWB1dSoSDGefcMMlNxw5Ttc9ZdbzL`.
Reproduction was checked with two byte-identical archives and an archived APK
checksum; invalid input leaves an existing output untouched.

Copy this third archive alongside the framework and ART archives when preparing
a new worktree. The provider retains upstream production defaults. The optional
overlay regression uses Chromium's supported developer setting only inside
temporary test data. This version's upstream overlay implementation guards zero
frame intervals before calculating frame-rate hints, fixing the invalid infinite
hint sent by the formerly bundled provider. Original SurfaceComposerClient
validation remains intact. See [acceptance testing](../docs/acceptance.md).

## Published archives

`build.zig.zon` and `artzig/build.zig.zon` fetch the three archives from
releases of the public GitHub mirror, `psnszsn/androlinzberg`. The Forgejo
workflow `.forgejo/workflows/aosp-tarballs.yml` runs `tools/aosp-release.sh`
whenever a file manifest, packager or patch under `artzig/patches` changes on
`main`, and can be started by hand. It syncs this checkout on the runner, runs
the three packagers, and publishes one release whose tag is derived from those
inputs:

```sh
tools/aosp-release.sh --print-tag
```

The release notes carry the `build.zig.zon` entries (URL and Zig package hash)
for each archive and say whether each hash matches the current pin. After
changing the packagers' inputs, wait for the workflow and copy those entries
into both manifests. The tag is pushed to Forgejo and reaches GitHub through the
repository's push mirror; do not create release tags on GitHub, because the
mirror deletes refs that Forgejo lacks.

The job needs the Forgejo secret `GH_RELEASE_TOKEN`, a fine-grained GitHub
token with Contents read and write on the mirror.

## Refresh the Zig package hashes

The SDK-sandbox client/interface closure comes from
`platform/packages/modules/AdServices` at Android 16 r1, commit
`a6ee8245f54f1719a899809cc8727f7fcce9ca35`. Its curated framework sources,
six AIDL interfaces and declared aconfig flags are compiled directly; the SDK
sandbox daemon is not part of the hosted session. Stock ATMS checks availability
through `HostedSdkSandboxActivities` before permitting sandbox activity hosting.

After regenerating a tarball, update its `.aosp` dependency hash:

- Artzig archive: `artzig/build.zig.zon`
- Androlinzberg archive: `build.zig.zon`

To check a locally regenerated archive before publishing, point the `.url` at
it (`file:aosp/<archive>` in `build.zig.zon`, `file:../aosp/<archive>` in
`artzig/build.zig.zon`), temporarily replace the corresponding `.hash` with a
known incorrect but well-formed Zig package hash, then run the normal build:

```sh
nix develop ./artzig -c zig build -j5
```

Zig fetches the local archive as part of the build and reports the correct
package hash. Copy that reported hash into the relevant `build.zig.zon` and run
the build again. The SHA-256 printed by the packager is an archive checksum; it
is not the Zig package hash.

Do not use `zig fetch`, `--fetch`, or a Zig global-cache environment override
for this workflow.

The input closure includes the pinned inputflinger dispatcher and policy
interfaces, its upstream test fixtures, and StatsD native libraries/flags.
`build/input_dispatcher.zig` generates package-manager/StatsD Binder interfaces,
KLL protobuf, native statistics and native window/StatsD flags. Dispatcher
verification uses host OpenSSL's constant-time comparison declaration through
`0005-inputdispatcher-host-crypto.patch`. The native audit records additional
[dispatcher adaptations](../docs/native-client-audit.md#graphics-input-and-webview).
