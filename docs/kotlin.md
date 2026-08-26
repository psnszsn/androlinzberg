# Kotlin source policy

Kotlin is the default language for new repository-owned JVM code. The root
framework build accepts Java and Kotlin in the same curated source set and
compiles them together through `zig build -j10 java`.

The compiler and standard library come from the pinned AOSP checkout at
`artzig/vendor/external/kotlinc`. OpenJDK 21 from the Nix development shell
runs `kotlinc`, `javac`, and D8. Kotlin language and API compatibility are
pinned to 2.0 and both compilers emit JVM 21 class files.

Directly reused AOSP sources stay in their original language. Generated AIDL,
resource, aconfig, and other generated Java also stays Java. Do not translate
or copy an AOSP class merely to make it Kotlin; the repository's AOSP reuse
policy still takes precedence.

Existing repository-owned Java should move to Kotlin in focused changes. A
framework or JNI-facing migration must preserve the fully-qualified class
name, visibility, JVM descriptors, static fields and methods, nested class
names, declared exceptions, and native registration contract. Compare
`javap -p -s` output before and after, and use `@JvmStatic`, `@JvmField`,
`@JvmName`, or `@Throws` when the Java-facing shape requires them.

The runtime artifact is `zig-out/framework/framework.jar`. It is a multidex
container because the framework plus Kotlin standard library exceeds one
dex file's method-reference limit. Kotlin reflection, coroutines, KAPT,
scripting, and compiler plugins are not part of the base runtime.

The hosted APK classloader keeps framework classes parent-first but treats
`kotlin.*` as app-owned. An APK-bundled Kotlin runtime therefore wins over the
host's pinned standard library; missing Kotlin classes fall back to the host.
Run `zig build -j10 jvm-test` to exercise that rule with a shadow dex through
the real ART classloader.
