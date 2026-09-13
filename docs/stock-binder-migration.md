# Stock Binder JNI over the hosted libbinder

Status: complete, verified on 2026-09-13.
Reference: pinned AOSP `android-16.0.0_r1`. Implementation baseline: `08a54c91`.
Runtime candidate: `b7d78a39`.

## Implemented boundary

The production native island compiles these four pinned units unchanged:

- `frameworks/base/core/jni/android_util_Binder.cpp`
- `frameworks/base/core/jni/android_os_Parcel.cpp`
- `frameworks/base/core/jni/android_os_ServiceManager.cpp`
- `frameworks/base/core/jni/android_os_ServiceManagerNative.cpp`

```text
stock Java Binder / Parcel / ServiceManager
                    |
             unchanged AOSP JNI
                    |
       hosted C++ IBinder / Parcel facade
                    |
          shared Zig AIBinder / AParcel
                    |
                AZKB broker
```

Original JNI owns JavaBBinderHolder, JavaBBinder, BinderProxy native data and
Java's C++ Parcel. The facade owns or borrows shared AParcel storage. An explicitly
installed callback table provides the NDK Java conversions and original JNI
registration, preserving island -> libbinder -> libbinder_ndk dependencies.
Standalone native clients retain no ART dependency.

The facade now supplies string/range/allocation contracts, interface work-source
headers, nested calling-identity and transaction-state restoration, Binder
extensions, proxy accounting/watermarks and the typed service-manager context.
Native service-manager interfaces are generated from pinned AIDL. Optional
freezer subscriptions report unsupported. Hosted scheduling does not implicitly
demote dispatch workers.

System-server startup initializes the existing application shared-memory backend
and passes its read-only descriptor in bindApplication. The embedded runtime
installs the pinned AndroidRuntime libutils thread-creation contract, keeping
`canCallJava` threads attached through native Binder destruction. Host input and
NDK conversions respect stock JNI's borrowed global references.

## Completed checkpoints

| Stage | Commit | Verification |
|---|---|---|
| Contract and baseline | `2f5cedb1` | Native/NDK/broker/JVM/Binder and focused APK baseline |
| Parcel and transaction state | `e3e53e18` | Public-interface edge cases and actual nested remote callbacks |
| Binder facade | `ffe951c8` | Native/NDK extensions, identity, accounting and context discovery |
| Coordinated JNI switch | `84dc6e75` | Java/NDK conversions and GC, Binder/JVM, native input/compositor and APK launch |
| Source allowlist ordering | `7dadd078` | Exact sorted C++ allowlist audit |
| Native thread lifecycle | `b7d78a39` | Reproduced desktop crash fixed; 16 actions without issues, JVM/Binder and JNI provenance |
| Full acceptance and reproduction | `b7d78a39` | Archive-only build and complete fresh-worktree acceptance passed |

The old Zig JNI registrations, native holder/proxy layouts, callback convention
and `AospParcelAbi.cpp` are deleted. Three obsolete Binder investigation documents
were removed; [native-boundary.md](native-boundary.md) and the
[native audit](native-client-audit.md#stock-binder-jni-over-the-existing-facade)
describe the current implementation.

Custom production sources changed by **672 added / 1,361 removed physical lines**:
**689 fewer lines net**. This excludes build files, documentation, vendored AOSP,
standalone native/JVM tests and 25 new JNI test-helper lines embedded in ndk.zig.

## Verification

Use [acceptance.md](acceptance.md) for commands and environment requirements.
`binder-jni-test` exercises conversion, identity, shared Parcel mutation and GC
lifetime. `binder-jni-source-test` checks actual compiled bytes, facade header
selection and ELF registrar ownership. It and `ndk-abi` are part of full acceptance.

The first complete run passed all 28 APK cases but failed the sorted allowlist
and desktop task-close/input sequence. Both failures were fixed in the commits
above and verified with their focused tests. Retained investigation/build logs
are under `/tmp/azk-binder-jni-implementation/`; the desktop failure is preserved
in `desktop-before-thread-fix/`.

The detached worktree `/tmp/androlinzberg-stock-binder-acceptance` began at
`7dadd078` without `.zig-cache`, `zig-pkg` or `zig-out`. Only the three archives
were copied from the main workspace, and their checksums matched. Its archive-only
build passed. It then advanced to `b7d78a39` for complete acceptance, using only
compilation outputs it produced itself. Results are retained beneath that
worktree's `zig-out/test-results/` and in `fresh-acceptance.log` / `fresh-worktree.json`
under the investigation directory. Full acceptance passed: **28 APK cases,
329 recorded actions, zero failed actions and zero structured runtime issues**.
JVM source identity reports 7,081 unchanged inputs and 62 server adaptations,
with no modified or owned client substitutes. All four original JNI units,
facade headers, registrar ownership and the NDK ABI pass their audits. Installed
library hashes match the provenance reports. WebView video and desktop modal
screenshots were inspected. The main workspace install was also refreshed and
its installed Binder/native-island hashes match its tested report.

## Packages and remaining adaptations

The framework archive was rebuilt and selected by package pin
`N-V-__8AAMBTuz6gFHrgO1lp2h0Biw3NfanhkVHdO0fSJy5q`. ART and WebView inputs did not
change. All three archives were verified in the fresh worktree:

| Archive | SHA-256 |
|---|---|
| androlinzberg-aosp-android-16.0.0_r1.tar.gz | `29e413b00058ea37b78b5ad8d9cbf6031448fdd5b6ed46f8b7a0b6a1d5118ca8` |
| artzig-aosp-android-16.0.0_r1.tar.gz | `aa71ef019f0360bc53c5e795858e58b3ec216d8ccafa6d623d5c6c6e3b9a921c` |
| androlinzberg-webview-140.0.7339.15.tar.gz | `ff78ad3c60e851a5ff3d217d3a2d16074f453f1814a4c4bbd105b54996e9918d` |

This completes the stock Binder JNI migration. C++ libbinder, libbinder_ndk,
Parcel storage, AZKB transport, registry and hosted process bootstrap remain
adaptations. Literal stock native-client identity, kernel Binder, audio and
codec service placement are separate work described in the native audit.
