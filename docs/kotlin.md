# Kotlin source policy

Use Kotlin for new repository-owned JVM code. Keep AOSP and generated sources
in Java when their source language is Java.

## Build configuration

The framework build compiles Java and Kotlin in one curated source set. Run this
build step:

```sh
zig build -j10 java
```

The pinned AOSP tree supplies the Kotlin compiler and standard library. OpenJDK
21 runs Kotlin, `javac`, and D8. The build uses Kotlin language level 2.0 and JVM
21 class files. Generated aconfig and nano directories are also explicit Java source roots:
K2 needs those package roots to resolve fully qualified flag constants in
inherited AOSP annotations and relocated protobuf references. The build uses only the generator's declared output
directories, so this does not expose unselected AOSP sources.

The output is `zig-out/framework/framework.jar`. This file is a multidex
container. The base runtime does not include reflection, coroutines, KAPT,
scripting, or compiler plug-ins.

## Source rules

- Put new repository-owned JVM code in `src/java` as Kotlin.
- Keep reused AOSP source in its original language.
- Keep generated AIDL, resource, and aconfig source in Java.
- Do not copy an AOSP class only to change its language.
- Make focused changes to existing compatibility Java.

The `jvm-source` mode of `tools/build-audit.zig` checks these rules from the
same explicit `SourceSet` inputs used by the Zig build graph.

## Java compatibility

A Kotlin replacement must keep the existing Java interface. Check these items:

- fully qualified class name
- visibility
- JVM method and field descriptors
- static fields and methods
- nested class names
- declared exceptions
- native registration interface

Compare `javap -p -s` output before and after the change. Use `@JvmStatic`,
`@JvmField`, `@JvmName`, or `@Throws` when the Java interface needs them.

Do not make a bulk conversion. Kotlin null types and synthetic properties can
change source behavior.

For resource-owning `try`/`finally`, prefer an explicit loop exit to a nonlocal
return inside an inline lambda such as `let`. The pinned K2 compiler omitted a
nested cleanup `catch` when copying `DesktopLauncher`'s `finally` for that return;
`javap -c -p` confirmed the missing exception-table range before D8. The
`stock-session-loss-test` regression exercises this cleanup with real Binder
death. Keep cleanup exceptions covered when changing these exit paths.

## APK class loading

APK loaders use the original Android PathClassLoader or the manifest's
DelegateLastClassLoader. Their platform parent shares Android API identities
while keeping host implementation dependencies private. APKs supply their own
Kotlin, Chromium and other libraries, or obtain them through explicitly declared
shared libraries. Missing APK dependencies fail with Android's normal class
lookup errors; the host compiler's standard library is not an implicit fallback.
Framework implementation classes retain access to their own pinned dependencies.

Run this test after a class-loading or JVM-interface change:

```sh
zig build -j10 jvm-test
```
