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

`src/java` is Kotlin-only. `tools/check-jvm-source-policy.sh` enforces that
repository-owned Java is limited to generated output, directly reused Android
examples, Java-compiler test fixtures, and the AOSP-facing compatibility trees
under `src/framework` and `src/framework-stubs`.

Existing compatibility Java should move to Kotlin only in focused changes. A
framework or JNI-facing migration must preserve the fully-qualified class
name, visibility, JVM descriptors, static fields and methods, nested class
names, declared exceptions, Android platform-type behavior, and native
registration contract. Compare `javap -p -s` output before and after, and use
`@JvmStatic`, `@JvmField`, `@JvmName`, or `@Throws` when the Java-facing shape
requires them. Do not bulk-convert the compatibility trees: Kotlin's nullability
and synthetic-property rules can change source behavior even when a file looks
mechanically equivalent.

The runtime artifact is `zig-out/framework/framework.jar`. It is a multidex
container because the framework plus Kotlin standard library exceeds one
dex file's method-reference limit. Kotlin reflection, coroutines, KAPT,
scripting, and compiler plugins are not part of the base runtime.

The hosted APK classloader keeps framework classes parent-first but treats
`kotlin.*` as app-owned. An APK-bundled Kotlin runtime therefore wins over the
host's pinned standard library; missing Kotlin classes fall back to the host.
Run `zig build -j10 jvm-test` to exercise that rule with a shadow dex through
the real ART classloader.

Host-side APK integration tests are Kotlin too. `zig build -j10 testkit` uses
the same pinned AOSP compiler plus vendored `kotlin.test`, JUnit, Hamcrest, and
Gson jars; it does not use Gradle or resolve network dependencies. The host JDK
runs the test supervisor so an APK crash or hang cannot take down the runner,
while the framework and APK continue to execute on ART. See the Kotlin APK
integration-test section in the repository README for the rule API, registry,
filters, and artifacts.
