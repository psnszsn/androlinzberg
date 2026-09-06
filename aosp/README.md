# AOSP source checkout and packages

This directory contains the selective AOSP checkout used to build the source
packages consumed by Artzig and Androlinzberg. The checkout is pinned to
`android-16.0.0_r1`; it is not intended to contain every project in the AOSP
manifest.

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

From a worktree without its own Repo checkout, point the Artzig packager at the
shared pinned checkout with `ARTZIG_AOSP_ROOT=/absolute/path/to/aosp`.

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

## Refresh the Zig package hashes

After regenerating a tarball, update its `.aosp` dependency hash:

- Artzig archive: `artzig/build.zig.zon`
- Androlinzberg archive: `build.zig.zon`

Temporarily replace the corresponding `.hash` with a known incorrect but
well-formed Zig package hash, then run the normal build:

```sh
nix develop ./artzig -c zig build -j5
```

Zig fetches the local archive as part of the build and reports the correct
package hash. Copy that reported hash into the relevant `build.zig.zon` and run
the build again. The SHA-256 printed by the packager is an archive checksum; it
is not the Zig package hash.

Do not use `zig fetch`, `--fetch`, or a Zig global-cache environment override
for this workflow.
