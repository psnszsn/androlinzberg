//! Framework JVM: the vendored AOSP subset + our Java/Kotlin replacements ->
//! a multidex framework.jar, plus framework-res.apk (aapt2) and generated Java. Replaces
//! tools/build-java.sh; the curated source list lives in tools/java-srcs.py.
//!
//! zig's Run step does not hash directory contents, so steps that depend on
//! whole source trees take a configure-time "tree stamp" (tools/tree-stamp.py)
//! as a file input: the stamp changes iff any file in the trees changed.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    artzig: *std.Build.Dependency,
};

pub const Result = struct {
    /// Classpath jar containing classes.dex and any secondary dex files.
    classpath_jar: LazyPath,
    /// Test dex whose kotlin.KotlinVersion must shadow the host copy.
    kotlin_shadow_fixture: LazyPath,
    res_apk: LazyPath,
    /// Directory of signed resources-only fixture APKs (testdata/fixture-apks).
    fixture_apks_dir: LazyPath,
    java_step: *std.Build.Step,
};

/// Always-run step writing a content stamp of the given trees; consumers take
/// it with addFileInput so they re-run iff something in the trees changed.
/// (A configure-time stamp would not work: build.zig configuration is cached.)
fn stamp(b: *std.Build, name: []const u8, paths: []const LazyPath) LazyPath {
    const r = b.addSystemCommand(&.{ "python3", "tools/tree-stamp.py" });
    const out = r.addOutputFileArg(name);
    for (paths) |p| r.addDirectoryArg(p);
    r.has_side_effects = true;
    r.setName(b.fmt("tree-stamp {s}", .{name}));
    return out;
}

pub fn build(b: *std.Build, o: Options) Result {
    const dep = o.artzig;
    const fb = dep.path("extra/frameworks-base");
    const core_all = dep.namedLazyPath("core-all-jar");
    const stamp_jvm = stamp(b, "jvm.stamp", &.{
        fb.path(b, "core/java"),           fb.path(b, "graphics/java"),
        fb.path(b, "libs/hwui/apex/java"), dep.path("extra/modules-utils/java"),
        b.path("src/framework"),           b.path("src/framework-stubs"),
        b.path("src/annotation-runtime"),
        b.path("src/java"),                b.path("tools/java-srcs.py"),
        b.path("tools/compile-jvm.sh"),
    });
    const stamp_kotlinc = stamp(b, "kotlinc.stamp", &.{
        dep.path("vendor/external/kotlinc/bin"),
        dep.path("vendor/external/kotlinc/lib"),
        dep.path("vendor/external/kotlinc/build.txt"),
    });
    const stamp_res = stamp(b, "res.stamp", &.{
        fb.path(b, "core/res"),
        b.path("tools/build-res.sh"),
        b.path("tools/signing"),
    });
    const stamp_fixture_apks = stamp(b, "fixture-apks.stamp", &.{
        b.path("testdata/fixture-apks"),
        b.path("tools/build-fixture-apks.sh"),
        b.path("tools/signing"),
    });
    const stamp_aidl = stamp(b, "aidl.stamp", &.{
        b.path("tools/aidl-list.txt"),
        b.path("tools/build-aidl.sh"),
        b.path("aidl-compat"),
        dep.path("extra/frameworks-native/libs/input/android/os"),
    });
    const stamp_logtags = stamp(b, "logtags.stamp", &.{
        b.path("tools/logtags-list.txt"),
        b.path("tools/build-logtags.sh"),
    });
    const stamp_aconfig = stamp(b, "aconfig.stamp", &.{
        b.path("tools/aconfig-packages.txt"),
        b.path("tools/build-aconfig.sh"),
    });

    const source_policy = b.addSystemCommand(&.{ "bash", "tools/check-jvm-source-policy.sh" });
    source_policy.addFileInput(stamp_jvm);

    // 1. core-all + annotation classes -> compile-time "JDK" (system modules)
    const plus = b.addSystemCommand(&.{ "bash", "tools/make-core-plus.sh" });
    plus.addFileArg(core_all);
    plus.addDirectoryArg(fb);
    const core_plus = plus.addOutputFileArg("core-all-plus.jar");

    const sysmod = b.addSystemCommand(&.{"bash"});
    sysmod.addFileArg(dep.path("tools/make-system-modules.sh"));
    sysmod.addFileArg(core_plus);
    const sysmod_dir = sysmod.addOutputDirectoryArg("sysmodules");
    sysmod.addDirectoryArg(dep.path("vendor"));

    // 2. generated Java: AIDL interfaces and android.R / framework-res.apk
    const aidl = b.addSystemCommand(&.{ "bash", "tools/build-aidl.sh" });
    const aidl_dir = aidl.addOutputDirectoryArg("aidl-java");
    aidl.addFileInput(stamp_aidl);

    // EventLogTags Java from the AOSP java-event-log-tags.py tool.
    const logtags = b.addSystemCommand(&.{ "bash", "tools/build-logtags.sh" });
    const logtags_dir = logtags.addOutputDirectoryArg("logtags-java");
    logtags.addFileInput(stamp_logtags);

    // aconfig flag classes (Flags/FeatureFlags/…) from the AOSP aconfig tool.
    const aconfig = b.addSystemCommand(&.{ "bash", "tools/build-aconfig.sh" });
    const aconfig_dir = aconfig.addOutputDirectoryArg("aconfig-java");
    aconfig.addFileInput(stamp_aconfig);

    const res = b.addSystemCommand(&.{ "bash", "tools/build-res.sh" });
    const res_dir = res.addOutputDirectoryArg("res");
    res.addFileInput(stamp_res);
    const res_apk = res_dir.path(b, "framework-res.apk");

    // Resources-only test packages, parsed server-side by the hosted package
    // manager (signed with the same azk-platform key as framework-res.apk).
    const fixture_apks = b.addSystemCommand(&.{ "bash", "tools/build-fixture-apks.sh" });
    fixture_apks.addFileArg(res_apk);
    const fixture_apks_dir = fixture_apks.addOutputDirectoryArg("fixture-apks");
    fixture_apks.addFileInput(stamp_fixture_apks);

    // 3. curated source list
    const srcs = b.addSystemCommand(&.{ "python3", "tools/java-srcs.py" });
    const srcs_txt = srcs.addOutputFileArg("framework-srcs.txt");
    srcs.addPrefixedDirectoryArg("FB=", fb);
    srcs.addPrefixedDirectoryArg("MU=", dep.path("extra/modules-utils"));
    srcs.addPrefixedDirectoryArg("APP=", b.path("."));
    srcs.addPrefixedDirectoryArg("AIDL=", aidl_dir);
    srcs.addPrefixedDirectoryArg("LOGTAGS=", logtags_dir);
    srcs.addPrefixedDirectoryArg("ACONFIG=", aconfig_dir);
    srcs.addPrefixedDirectoryArg("RES=", res_dir);
    srcs.addFileInput(stamp_jvm);

    // 4. mixed Kotlin/Java compile against the Android system-modules image.
    // The helper owns Kotlin-first/Java-second ordering and their classpaths.
    const compile = b.addSystemCommand(&.{"bash"});
    compile.addFileArg(b.path("tools/compile-jvm.sh"));
    compile.addFileArg(core_plus);
    compile.addDirectoryArg(sysmod_dir);
    compile.addFileArg(srcs_txt);
    compile.addDirectoryArg(dep.path("vendor/external/kotlinc"));
    const classes_dir = compile.addOutputDirectoryArg("classes");
    compile.addFileInput(stamp_jvm);
    compile.addFileInput(stamp_kotlinc);
    compile.step.dependOn(&source_policy.step);

    // 5. d8 (via a classes jar; the r8 CLI takes jars, not directories)
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const classes_jar = jar.addOutputFileArg("framework-classes.jar");
    jar.addArg("-C");
    jar.addDirectoryArg(classes_dir);
    jar.addArg(".");

    const d8 = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-cp" });
    d8.addFileArg(dep.path("vendor/prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(core_plus);
    d8.addArg("--output");
    const classpath_jar = d8.addOutputFileArg("framework.jar");
    d8.addFileArg(classes_jar);

    // HostedApkClassLoader keeps framework classes parent-first but lets an
    // APK's kotlin.* definitions shadow the compiler-pinned host stdlib. Give
    // dex2oat that same compiler view; the full framework.jar remains the
    // stored runtime context used for freshness validation.
    const app_context_d8 = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-cp" });
    app_context_d8.addFileArg(dep.path("vendor/prebuilts/r8/r8.jar"));
    app_context_d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    app_context_d8.addFileArg(core_plus);
    app_context_d8.addArg("--lib");
    app_context_d8.addFileArg(dep.path("vendor/external/kotlinc/lib/kotlin-stdlib.jar"));
    app_context_d8.addArg("--output");
    const app_context_jar = app_context_d8.addOutputFileArg("framework-app-context.jar");
    app_context_d8.addFileArg(classes_jar);

    // Android's framework jars are dexpreopted into the system image. Build an adjacent hosted
    // odex/vdex pair against the exact ART boot image and final framework.jar location.
    const dexopt = b.addSystemCommand(&.{"bash"});
    dexopt.addFileArg(b.path("tools/dexopt-framework.sh"));
    dexopt.addDirectoryArg(dep.namedLazyPath("art-runtime"));
    dexopt.addFileArg(classpath_jar);
    dexopt.addFileArg(b.graph.path(.install_prefix, "framework/framework.jar"));
    const framework_oat = dexopt.addOutputDirectoryArg("framework-oat");
    dexopt.setName("dex2oat framework.jar (speed)");
    d8.addFileArg(dep.path("vendor/external/kotlinc/lib/kotlin-stdlib.jar"));

    // A tiny app dex used to verify that APK-bundled kotlin.* classes win over
    // the host's pinned stdlib without making the rest of the APK child-first.
    const fixture_javac = b.addSystemCommand(&.{"javac"});
    fixture_javac.addPrefixedDirectoryArg("--system=", sysmod_dir);
    fixture_javac.addArgs(&.{ "-source", "21", "-target", "21", "-d" });
    const fixture_classes = fixture_javac.addOutputDirectoryArg("kotlin-shadow-classes");
    fixture_javac.addFileArg(b.path("testdata/kotlin-shadow/kotlin/KotlinVersion.java"));
    fixture_javac.addFileArg(b.path("testdata/kotlin-shadow/fixture/NewerKotlinCaller.java"));

    const fixture_jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const fixture_classes_jar = fixture_jar.addOutputFileArg("kotlin-shadow-classes.jar");
    fixture_jar.addArg("-C");
    fixture_jar.addDirectoryArg(fixture_classes);
    fixture_jar.addArg(".");

    const fixture_d8 = b.addSystemCommand(&.{ "java", "-Xmx512M", "-cp" });
    fixture_d8.addFileArg(dep.path("vendor/prebuilts/r8/r8.jar"));
    fixture_d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    fixture_d8.addFileArg(core_plus);
    fixture_d8.addArg("--output");
    const kotlin_shadow_fixture = fixture_d8.addOutputFileArg("kotlin-shadow.jar");
    fixture_d8.addFileArg(fixture_classes_jar);

    const java_step = b.step("java", "Build the framework jar (kotlinc + javac + d8), framework-res.apk and generated Java");
    java_step.dependOn(&b.addInstallFileWithDir(classpath_jar, .{ .custom = "framework" }, "framework.jar").step);
    java_step.dependOn(&b.addInstallFileWithDir(
        app_context_jar,
        .{ .custom = "framework" },
        "framework-app-context.jar",
    ).step);
    java_step.dependOn(&b.addInstallFileWithDir(
        framework_oat.path(b, "framework.odex"),
        .{ .custom = "framework/oat/x86_64" },
        "framework.odex",
    ).step);
    java_step.dependOn(&b.addInstallFileWithDir(
        framework_oat.path(b, "framework.vdex"),
        .{ .custom = "framework/oat/x86_64" },
        "framework.vdex",
    ).step);
    java_step.dependOn(&b.addInstallFileWithDir(res_apk, .{ .custom = "res" }, "framework-res.apk").step);
    return .{
        .classpath_jar = classpath_jar,
        .kotlin_shadow_fixture = kotlin_shadow_fixture,
        .res_apk = res_apk,
        .fixture_apks_dir = fixture_apks_dir,
        .java_step = java_step,
    };
}
