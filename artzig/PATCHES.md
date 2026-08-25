# art_standalone patches and their status in artzig

Catalogue of what art_standalone changed on top of AOSP (Android 10 era), to
cherry-pick into `patches/` as needed. Source: `git log` of
https://gitlab.com/android_translation_layer/art_standalone (227 commits; ~34 are
bulk AOSP imports/rebases, the rest are listed by theme below).

Legend: **keep** = port to the new tree; **drop** = obsolete with modern AOSP /
Soong or replaced by a system dep; **later** = only once ATL itself is ported.

## Build system / portability — all **drop**
Everything about the GNU-make build (`Makefile`, `build/core/combo`, mini_soong,
`LOCAL_MULTILIB`, gcc-specific fixes, musl/aarch64/armv7/x86 fixes, vixl from
host, asm_defines genrules) is obsolete: Soong handles host builds of ART with
its own clang. Portability to musl will need to be revisited at the build.zig
stage instead.

## Runtime behaviour (art/) — **keep**
| commit | change |
|---|---|
| `ad8a1a0e`/`b89ec9d2`/`ce8fe1f0` | **drop** — modern ART reads `ANDROID_DATA`; verified: setting it is enough, dex2oat writes `$ANDROID_DATA/dalvik-cache/x86_64/` |
| `95e0af94` | **drop** — boot image location is `-Ximage`/`ANDROID_ROOT`-relative; set by the launcher |
| `eb1efc58` | OatFileAssistant: prefer preinstalled oat files |
| `79ab0c15` | also dex2oat non-bootclasspath jars on first launch — **drop**, upstream does this now (verified: `.odex` lands in dalvik-cache) |
| `d58f96f9` | don't dex2oat `framework-res.apk` — **drop** for now; revisit if a dex-less apk on the classpath logs noise |
| `dfb2ba5d` | `ART_NON_FATAL_JNI_LOOKUPS` env: swallow exceptions on some JNI lookups (ATL relies on this for partially-implemented classes) — **ported**: `patches/art/0001-art-non-fatal-jni-lookups.patch` |
| `c4d89077` | use NativeBridge for foreign-arch native libs when loaded |
| `eb04efa9` | thread stack: max(64 kB, PTHREAD_STACK_MIN) — **ported** `patches/art/0002` |
| `22a3d27c` | fix `Thread.setPriority()` — **drop**, fixed upstream |
| `649751be` | export JNI_* symbols from libart — **drop**, already exported (dlsym works) |
| `e2219f85`/`345a912e` | dex2oat: include symbols by default; synthesize `_ARG%n` names |
| `a4fdc2ac` | remove warnings about bootclasspath location — **drop**, upstream only warns on target |
| `a1ea1c33`/`bac441cf` | `ucontext_t` for glibc — likely already fine on AOSP host builds, verify |
| `java_vm_ext.cc` bionic hook + `c4d89077` NativeBridge | **replaced** by `patches/art/0003`: `artzig_set_native_loader()` lets the embedder install dlopen/dlsym/dlerror (Zig: `Options.native_loader`). bionic_translation is wired on the host side, not compiled into ART. |

## libcore — **keep** (small)
| commit | change |
|---|---|
| `5bda8706` | `File.java`: spoof existence of files in `/system/lib` — **ported** `patches/libcore/0001` (also `/system/lib64`) |
| `1c5b26e4` | remove `Runtime.loadLibrary()` deprecation warning — **ported** `patches/libcore/0001` |
| `9be15e73` | `dladdr(JNI_CreateJavaVM)` → `art_sigsegv_fault` |
| `1da40caf` | JKS keystore from OpenJDK — **drop if** conscrypt/OpenSSL path gives us a usable trust store (see below) |

## Crypto / TLS — **replace**
art_standalone swapped conscrypt+BoringSSL for wolfSSL + wolfJSSE (many
commits: `44418..`, `4951d336`, `4c474a9f`, `9dfce753`, `WolfSSLSession.*`). For
artzig the goal is a system OpenSSL. Options, in order of preference:
1. Link `external/conscrypt` against system **OpenSSL 3**. Conscrypt's README
   says BoringSSL only (OpenSSL support was dropped ~2016), and its JNI
   (`common/src/jni/main/cpp/conscrypt/native_crypto.cc`, ~10k lines) uses a
   number of BoringSSL-only APIs (`SSL_CTX_set_custom_verify`,
   `SSL_set_shed_handshake_config`, `EVP_AEAD_*`, `HRSS`/`X25519`-specific
   entry points, `CBB`/`CBS`). Porting is real work — estimate a few hundred
   lines of `#ifdef OPENSSL_IS_BORINGSSL` shims — but keeps the runtime on the
   distro's TLS stack (CA store, security updates, no duplicate libcrypto).
2. Keep `external/boringssl` from vendor, statically linked into
   `libjavacrypto.so` (the default Soong configuration; zero patches). This is
   the path of least resistance and what we'll do first.
Decision deferred until the Soong host build of libcore works.

BouncyCastle commits (`52e4bcda`, `63a7e3fb`, `06373163`, `154ac31b`,
`ecda21d2`: re-add algorithms AOSP strips out because Android delegates them to
conscrypt) stay **keep** only if we end up without conscrypt.

## dx / dalvik — **drop**
`dx` is gone from AOSP; use `d8` from `prebuilts/r8` (in the manifest). ATL's
meson.build needs `dx --dex --multi-dex` → `d8 --min-api 26 --output`.

## libandroidfw — **keep**, separate concern
AOSP keeps it in `frameworks/base/libs/androidfw` (not in the master-art
manifest; fetch with sparse checkout). art_standalone adds a C API
(`androidfw_c_api.h`: AssetManager2, ResXMLParser, themes, ApkAssets_loadDir,
DynamicRefTable, locales, lock/unlock) and a couple of behaviour tweaks
(`e78bf689` overlay-dir silence). The C API files should be copied over nearly
verbatim; they are ATL's stable interface.

## Logging — **keep** (cosmetic)
`66a5d907` liblog colours, `7a589ffb` 64-char tags, `2d82a30c` route libbase
logs through liblog.

## bionic_translation / adb — **out of scope** here (separate repos).
