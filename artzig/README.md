# artzig

A host-Linux build of the Android Runtime (ART) and libcore from **current AOSP
main**, intended as a replacement for
[art_standalone](https://gitlab.com/android_translation_layer/art_standalone)
(which is stuck on Android 10 / libcore 8.1 because it depends on the old
GNU-make AOSP build system).

## Layout

- `vendor/` — unmodified AOSP projects, checked out from `vendor/manifest.xml`
  (the `master-art` manifest, i.e. the minimal tree Google uses to build the ART
  mainline module). Populated by `tools/sync-aosp.py`; not committed. Local
  changes to vendor projects live as patches in `patches/<project-path>/*.patch`
  and are applied by `tools/apply-patches.sh`.
- `patches/` — our modifications to AOSP sources (see `PATCHES.md`).
- `tools/` — sync/patch scripts.
- `build.zig` — the eventual goal: a `build.zig` generated from the Soong
  `Android.bp` module graph. Until that exists, `vendor/` is built with Soong
  itself (see below).

## Syncing

```sh
tools/sync-aosp.py -j 8          # shallow clone everything not in EXCLUDE
tools/sync-aosp.py --only prebuilts/ndk   # add a skipped project later
tools/sync-aosp.py --list        # show what would be synced
```

`prebuilts/clang/host/linux-x86` and `prebuilts/sdk` are several GB each even at
depth 1; they are what Soong needs to bootstrap and to build Java.

## Building with Soong (interim)

```sh
tools/soong-build.sh                 # default host target set (see script)
tools/soong-build.sh build-art-host  # everything the host `art` wrapper needs
tools/soong-build.sh dalvikvm-host   # explicit targets (use the -host phonies)
```

Status (2026-08-23): `build-art-host` builds and runs Java 17+ code
(records, `List.of`, streams `.toList()`, `java.time`):

```sh
javac -source 17 -target 17 -d classes Hello.java   # any JDK
tools/d8.sh --min-api 26 --output . classes/*.class
tools/art-run.sh -cp $PWD/classes.dex Hello
```

`tools/install.sh [DEST]` then assembles a **relocatable runtime tree**
(default `dist/art`, ~250 MB): `bin/`, `lib64/` (ldd+dlopen closure),
`apex/com.android.*/javalib/*.jar` (boot classpath, dex) plus a `speed`-compiled
boot image, ICU/tz data, `framework/*-classes.jar` (javac bootclasspath) and
`runtime.env` describing the launcher contract. `dist/art/bin/art -cp x.dex Main`
runs it. The boot image uses canonical `/apex/...` dex locations and the launcher
passes `-Xbootclasspath-locations`, so the tree can be moved anywhere. On NixOS
the binaries still need an FHS loader (`nix run .#aosp-env` or `ld.so
--library-path`); on ordinary distros they run as-is.

Runtime paths are all env-driven in modern ART (`ANDROID_DATA`,
`ANDROID_ROOT`, `ANDROID_ART_ROOT`, `ANDROID_I18N_ROOT`, `ANDROID_TZDATA_ROOT`,
`ART_APEX_DATA`), so the art_standalone path patches are unnecessary; a
launcher just sets `ANDROID_DATA=$XDG_CACHE_HOME/...`. `tools/install.sh` generates the boot image with `generate-boot-image64`.

The script runs `build/soong/soong_ui.bash --make-mode` with
`TARGET_PRODUCT=silvermont` (the x86_64 product shipped in `device/generic/art`),
`TARGET_BUILD_UNBUNDLED=true` and `SOONG_ALLOW_MISSING_DEPENDENCIES=true` — the
same setup as `art/tools/buildbot-build.sh --host` for the reduced manifest.
Output goes to `out/` (outside `vendor/`).

On NixOS the AOSP prebuilts (clang, ninja, jdk, python) are FHS binaries, so
everything runs inside the `buildFHSEnv` from `flake.nix`
(`nix run .#aosp-env` drops you into it; `nix develop` also works for an
interactive shell).

Host artifacts land in `out/host/linux-x86/{bin,lib64,framework}`.
Host target names: `dalvikvm`, `dex2oat`, `libart`, `core-oj-hostdex`,
`core-libart-hostdex`, `conscrypt-host-hostdex`, `libjavacrypto`, … — run
`grep -rhoE '^build [A-Za-z0-9_.-]+: phony' out/soong/build.*.ninja` to list.

Soong evaluates the whole module graph even for one target, which is why the
sync keeps seemingly unrelated projects (e2fsprogs, avb, tradefed core,
gradle-plugin, pgo-profiles): they are needed for the graph to validate, not
for ART.

## Zig module

```zig
const dep = b.dependency("artzig", .{ .target = target, .optimize = optimize });
exe.root_module.addImport("art", dep.module("art"));          // ART runtime (JNI + Runtime/Env)
exe.root_module.addImport("androidfw", dep.module("androidfw")); // resources.arsc / assets C API
@import("artzig").installRuntime(b, dep, &install_step.step);  // builds/caches ART next to the exe
const dex_dir = @import("artzig").d8(b, dep, classes_dir);      // javac output → classes.dex
```

- `art`: `Runtime.create(gpa, io, environ, .{ .classpath = …, .native_loader = … })`
  dlopen()s `libart.so` from the runtime tree found next to the executable
  (`art/`, `../lib/art`, `$ARTZIG_ROOT`), sets the env vars ART expects and
  creates the VM. `Runtime.runMain`, `Env.findClass/getMethodID/...`, raw JNI
  via `art.jni`. `Options.native_loader` installs the `artzig_set_native_loader`
  hook (patches/art/0003) so `System.loadLibrary` goes through e.g.
  bionic_translation.
- `androidfw`: `libandroidfw` (AOSP main) + libbase/liblog/libcutils/libutils/
  libziparchive/fmtlib compiled **by Zig** (`build/androidfw.zig`) with system
  zlib/libpng, exposing art_standalone's C API (`src/androidfw/`). This is the
  pilot for the build.zig generator: ~110 AOSP C++ files needed only a gtest
  stub, a `<cstdlib>` prelude and two `-Wno-` flags.
- Examples: `zig build run` (Java 17 hello world on ART), `zig build
  run-resources -- framework-res.apk string/ok`.

Java toolchain for consumers is the system JDK (`javac`/`java` on PATH; the
`nix develop` shell provides one); `d8.jar` ships in the runtime tree.

## build.zig generator (phase B)

`tools/ninja2zig.py` translates Soong's **ninja graph** (the exact commands, via
`ninja -t commands <output>`) into `build/gen/<name>.zig`:

- `clang -c` → `addCSourceFiles` with the verbatim flags, minus toolchain bits
  Zig supplies itself (sysroot, `-target`, libc++ include dirs, `-fsanitize*`,
  linker hardening flags). The emitter factors them: `base_cflags` is the
  intersection over all units, each module adds a named delta (`cf_N`, ~30 for
  ART), and include lists are named `inc_N` sets resolved to absolute `-I`s per
  compile unit (Zig's module-level include paths would reorder them). Sources of
  each archive live in `srcs_<module>()`, which whole-archive consumers call too,
  so `build()` reads like the Soong module graph.
- `llvm-ar` → static lib; `clang++ @rsp -o x.so` → shared lib / exe, with
  `linkLibrary` edges. `--whole-archive` deps are compiled into the consumer
  (Zig has no whole-archive). Prebuilt toolchain libs map to Zig's own
  (compiler-rt, libc++) or to system libs (`libcap`, ICU, zlib).
- Genrules are ported (`build/genrules.zig`): `generate_operator_out.py` (driven
  by a table the generator emits into `art.zig`), `gen_mterp.py`, and
  `asm_defines` (Zig compiles `asm_defines.cc -S` with the module's own flags,
  then `make_header.py`) — outputs verified byte-identical to Soong's. The
  aconfig C++ flag stubs are vendored in `src/gen/aconfig/` (regenerate from a
  Soong build when `art-flags.aconfig` changes), the per-binary Rust
  `librustlibs.a` is replaced by a `rustc_demangle` stub
  (`src/gen/rustlibs_stub.c`), and the NDK-stable `libicu.so` comes straight
  from `vendor/prebuilts/runtime`. **`zig build gen-art` therefore needs no
  Soong `out/` tree at all** (verified by building with `out/` removed); Soong
  is only needed to *regenerate* `build/gen/*.zig` after a manifest bump
  (`tools/soong-build.sh` + `tools/regen.sh`) and for the Java-side jars.

`tools/regen.sh` regenerates `build/gen/{libartbase,art}.zig`;
`zig build gen-art` builds `libart.so`, `dalvikvm64`, `dex2oat64`, `libjavacore`,
`libopenjdk`, `libjavacrypto` (BoringSSL), … — 1,879 compile units — with Zig's
clang and the system glibc into `zig-out/gen-art/`. **Verified 2026-08-23**: a
runtime tree with the Zig-built `lib64/*.so` + `bin/` runs the Java 17 example
(JIT and AOT via the Zig-built `dex2oat64`), natively on NixOS (no FHS env).
Required host fixes for glibc ≥ 2.38 / clang 20 are in `patches/art/0004`.
Generated modules set `sanitize_c = .off`: Zig's ubsan runtime would add a 256 K
static-TLS block to every `.so`, which makes them un-`dlopen`-able.

## Fully Zig-built runtime (`zig build dist`)

As of 2026-08-24 the entire runtime tree is built without Soong output:

- **Native**: `zig build gen-art` (see the generator section) → `libart.so`,
  `dalvikvm64`, `dex2oat64`, `generate-boot-image64`, libjavacore, …
- **Java boot classpath**: `zig build java` with the **system JDK**.
  `tools/ninja2java.py` extracts per-module `.java`/resource lists from Soong's
  ninja graph (`build/gen/java/*.rsp|.res`, generated stubs vendored in
  `src/gen/java/`); `build/javalib.zig` runs the classic bootstrap — javac
  `core-all` → a `java.base` system-module image (`tools/make-system-modules.sh`,
  jmod+jlink) → per-module javac → `d8` (r8.jar) → boot jar — replacing Soong's
  metalava/turbine/hiddenapi. conscrypt's `NativeConstants.java` is produced by
  compiling the upstream C++ generator against vendored BoringSSL; `core-icu4j`
  is `d8` of the vendored i18n SDK jar.
- **`zig build dist`** assembles the relocatable tree in `zig-out/dist/art`:
  gen-art libs + boot jars + ICU/tz data (extracted from the vendored `.apex`
  images with `debugfs`, `tools/extract-apex-etc.sh`) + prebuilt i18n JNI libs +
  a boot image generated in place by the Zig-built `generate-boot-image64`.
  **Verified: runs Java with no Soong `out/`**, in both Debug and
  `-Doptimize=ReleaseFast` (the latter drops `libart.so` from ~270 MB to ~87 MB).
- **bionic_translation** (`build/bionic.zig`): builds the shim libdl/libc/
  pthread/stdc++ (`libdl_bio.so` etc.), all of which load. Only `linker.c` uses
  a GNU-`as`-specific section hack, so that single TU is compiled with the system
  `gcc` (`gccObject`) and linked in; everything else is Zig. The `art` module's
  `Options.use_bionic_loader` dlopens `libdl_bio.so` and routes
  `System.loadLibrary` through `bionic_dlopen/dlsym/dlerror` (verified: the
  example runs with the bionic loader active; loading a real bionic `.so` needs
  a test APK with native code).

`build.zig` steps: `gen-art`, `java`, `dist`, `bionic`, `androidfw`, `example`,
`run`, `run-resources`, plus `soong`/`dist-soong` (legacy, need the FHS env).

## Distributing (pruned source tree)

The full checkout is 36 GB (mostly `prebuilts/clang` and unused externals), but
the build only touches a fraction. `tools/prune-vendor.py` computes the exact set
of `vendor/`+`extra/` paths the Zig build references — every `addCSourceFiles`
source, every include dir (headers only), the `.java`/resource lists, and the
handful of prebuilts (`r8.jar`, x86_64 i18n libs/APEXes, `libc++.so`) — and
writes `build/gen/vendor-manifest.txt`. `tools/make-dist-tarball.sh <dir> [--tar]`
materializes that into a **~130 MB** pruned tree (or a `.tar.zst`) that a consumer
unpacks in place of the AOSP checkout and builds with `zig build dist`. The system
JDK is a build-time requirement, not shipped. **Verified 2026-08-24**: `zig build
gen-art` builds `libart.so` cleanly from the pruned tree alone. (The pruned build
also flushes out any manifest gaps — e.g. genrule scripts, symlinked header dirs,
and `#include`d `.inl`/`_impl.c`/`dlmalloc.c` siblings, all now captured.)

## Rebasing onto newer AOSP

`tools/rebase.sh [--sync] [--build] [--to <ref>]` fetches the current master-art
manifest, diffs it against the pinned `vendor/manifest.xml`, adopts it, then
resets each patched project to its manifest revision and re-applies `patches/`
with `git am --3way`, reporting any patch that no longer applies. `--sync`
fast-forwards the vendor checkouts; `--build` runs Soong once + `regen.sh` +
`ninja2java.py` + `zig build dist` + a smoke test. A bump is thus a manifest diff
plus a patch re-apply — only genuinely conflicting patches need hand-work.
Validated 2026-08-24: all five ART/libcore patches re-apply cleanly at
master-art HEAD.

## Decisions (2026-08-23)

- Crypto: vendored BoringSSL statically linked into conscrypt — no OpenSSL port.
- Work order: runtime tree → Zig module (wrapping Soong output) → port remaining
  patches → bionic_translation dlopen hook → androidfw via Zig → build.zig generator.
- Java toolchain for consumers: system JDK (`javac`/`java` on PATH); `d8.jar` ships
  in the runtime tree.
- Runtime tree is resolved relative to the consumer executable, never a fixed prefix.

## Goals / differences from art_standalone

1. Track AOSP main instead of Android 10.
2. System dependencies where possible (OpenSSL via conscrypt-on-OpenSSL or
   BoringSSL from vendor — TBD; zlib, expat, lz4, icu from the distro).
3. Keep the out-of-tree patch set small and mechanical so rebases are cheap.
4. Generate `build.zig` from the Soong module graph (`soong_build` can dump the
   graph as JSON via `m json-module-graph`), so Soong is only a one-time
   generator, not a build dependency.
