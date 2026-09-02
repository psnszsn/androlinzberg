//! Framework JVM: the vendored AOSP subset + our Java/Kotlin replacements ->
//! a multidex framework.jar, plus framework-res.apk (aapt2) and generated Java. Replaces
//! tools/build-java.sh; the curated source list lives in build/java_sources.zig.
//!
//! zig's Run step does not hash directory contents, so steps that depend on
//! whole source trees take a configure-time "tree stamp" (tools/tree-stamp.py)
//! as a file input: the stamp changes iff any file in the trees changed.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    artzig: *std.Build.Dependency,
    aosp: *std.Build.Dependency,
    generated: @import("generators.zig").Result,
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
    const aosp = o.aosp;
    const fb = aosp.path("frameworks/base");
    const core_all = dep.namedLazyPath("core-all-jar");
    const stamp_jvm = stamp(b, "jvm.stamp", &.{
        fb.path(b, "core/java"),           fb.path(b, "graphics/java"),
        fb.path(b, "libs/hwui/apex/java"), aosp.path("frameworks/libs/modules-utils/java"),
        b.path("src/framework"),           b.path("src/framework-stubs"),
        b.path("src/annotation-runtime"),  b.path("src/java"),
    });
    const stamp_kotlinc = stamp(b, "kotlinc.stamp", &.{
        aosp.path("external/kotlinc/bin"),
        aosp.path("external/kotlinc/lib"),
        aosp.path("external/kotlinc/build.txt"),
    });
    const source_policy = b.addSystemCommand(&.{ "bash", "tools/check-jvm-source-policy.sh" });
    source_policy.addFileInput(stamp_jvm);
    source_policy.addDirectoryArg(aosp.path(""));

    // 1. core-all + annotation classes -> compile-time "JDK" (system modules)
    const plus = b.addSystemCommand(&.{ "bash", "tools/make-core-plus.sh" });
    plus.addFileArg(core_all);
    plus.addDirectoryArg(fb);
    const core_plus = plus.addOutputFileArg("core-all-plus.jar");

    const sysmod = b.addSystemCommand(&.{"bash"});
    sysmod.addFileArg(dep.path("tools/make-system-modules.sh"));
    sysmod.addFileArg(core_plus);
    const sysmod_dir = sysmod.addOutputDirectoryArg("sysmodules");
    sysmod.addDirectoryArg(aosp.path(""));

    // 2. Generated Java, framework resources, and fixture APKs are cacheable
    // Run/WriteFiles nodes owned by build/generators.zig.
    const aidl_dir = o.generated.aidl_java;
    const logtags_dir = o.generated.logtags_java;
    const aconfig_dir = o.generated.aconfig_java;
    const sysprop_dir = o.generated.sysprop_java;
    const nano_dir = o.generated.nano_java;
    const stats_dir = o.generated.stats_java;
    const proto_dir = o.generated.proto_java;
    const res_dir = o.generated.res_dir;
    const res_apk = o.generated.res_apk;
    const fixture_apks_dir = o.generated.fixture_apks_dir;

    // 3. Curated Java, Kotlin, and runtime-annotation source lists. This must
    // run after generated directories exist, so it is a host tool rather than
    // configure-time build.zig traversal.
    const source_lister = b.addExecutable(.{
        .name = "java-sources",
        .root_module = b.createModule(.{
            .root_source_file = b.path("build/java_sources.zig"),
            .target = b.graph.host,
            .optimize = .ReleaseSafe,
        }),
    });
    const srcs = b.addRunArtifact(source_lister);
    const java_srcs = srcs.addOutputFileArg("framework-java-sources.rsp");
    const kotlin_srcs = srcs.addOutputFileArg("framework-kotlin-sources.rsp");
    const annotation_srcs = srcs.addOutputFileArg("framework-annotation-sources.rsp");
    srcs.addPrefixedDirectoryArg("FB=", fb);
    srcs.addPrefixedDirectoryArg("MU=", aosp.path("frameworks/libs/modules-utils"));
    srcs.addPrefixedDirectoryArg("APP=", b.path("."));
    srcs.addPrefixedDirectoryArg("AIDL=", aidl_dir);
    srcs.addPrefixedDirectoryArg("LOGTAGS=", logtags_dir);
    srcs.addPrefixedDirectoryArg("ACONFIG=", aconfig_dir);
    srcs.addPrefixedDirectoryArg("SYSPROP=", sysprop_dir);
    srcs.addPrefixedDirectoryArg("PROTO=", proto_dir);
    srcs.addPrefixedDirectoryArg("STATS=", stats_dir);
    srcs.addPrefixedDirectoryArg("NANO=", nano_dir);
    srcs.addPrefixedDirectoryArg("NRT=", aosp.path("external/protobuf/javanano/src/main/java"));
    srcs.addPrefixedDirectoryArg("SD=", aosp.path("packages/modules/StatsD"));
    srcs.addPrefixedDirectoryArg("RES=", res_dir);
    srcs.addFileInput(stamp_jvm);
    srcs.step.dependOn(&source_policy.step);

    // 4. Kotlin first, then Java, matching AOSP Soong's mixed-source ordering.
    // Each compiler owns its output directory; the only shell logic joins
    // generated LazyPaths into the single classpath argument the JVM tools require.
    const kotlin_stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");
    const kotlinc = b.addSystemCommand(&.{
        "sh",
        "-c",
        \\exec "$1" -no-jdk -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 21 -java-parameters -Xsam-conversions=class -Xstring-concat=inline -classpath "$2:$3" -d "$4" "@$5" "@$6"
        ,
        "sh",
    });
    kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    kotlinc.addFileArg(core_plus);
    kotlinc.addFileArg(kotlin_stdlib);
    const kotlin_classes = kotlinc.addOutputDirectoryArg("kotlin-classes");
    kotlinc.addFileArg(java_srcs);
    kotlinc.addFileArg(kotlin_srcs);
    kotlinc.addFileInput(stamp_jvm);
    kotlinc.addFileInput(stamp_kotlinc);
    // C1-only code fills the code cache faster; size it for the whole tree.
    kotlinc.setEnvironmentVariable("JAVA_OPTS", "-Xmx2048M -Xms256M -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=512m");
    kotlinc.setName("compile framework Kotlin");

    // android.annotation belongs to the system-module image, so runtime copies
    // of annotation classes need a separate module-free javac pass.
    const annotation_javac = b.addSystemCommand(&.{
        "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-d",
    });
    const annotation_classes = annotation_javac.addOutputDirectoryArg("annotation-classes");
    annotation_javac.addPrefixedFileArg("@", annotation_srcs);
    annotation_javac.addFileInput(stamp_jvm);
    annotation_javac.setName("compile runtime annotations");

    const javac = b.addSystemCommand(&.{
        "bash",
        "-c",
        \\exec javac --system="$1" -source 21 -target 21 -encoding UTF-8 -g -parameters -XDstringConcat=inline -Xmaxerrs 2000 -nowarn -Xlint:none -classpath "$2:$3:$4" -d "$5" "@$6" 2> >(grep -F -v -e 'Note: Some input files use or override a deprecated API.' -e 'Note: Recompile with -Xlint:deprecation for details.' -e 'Note: Some input files use unchecked or unsafe operations.' -e 'Note: Recompile with -Xlint:unchecked for details.' >&2)
        ,
        "bash",
    });
    javac.addDirectoryArg(sysmod_dir);
    javac.addDirectoryArg(kotlin_classes);
    javac.addDirectoryArg(annotation_classes);
    javac.addFileArg(kotlin_stdlib);
    const java_classes = javac.addOutputDirectoryArg("java-classes");
    javac.addFileArg(java_srcs);
    javac.addFileInput(stamp_jvm);
    javac.setName("compile framework Java");

    // 5. Package each compiler's directory directly, with no intermediate
    // class-tree copies. The r8 CLI takes jars rather than directories.
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const classes_jar = jar.addOutputFileArg("framework-classes.jar");
    jar.addArg("-C");
    jar.addDirectoryArg(kotlin_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(annotation_classes);
    jar.addArg(".");
    jar.addArg("-C");
    jar.addDirectoryArg(java_classes);
    jar.addArg(".");

    const d8 = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
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
    app_context_d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    app_context_d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    app_context_d8.addFileArg(core_plus);
    app_context_d8.addArg("--lib");
    app_context_d8.addFileArg(aosp.path("external/kotlinc/lib/kotlin-stdlib.jar"));
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
    d8.addFileArg(aosp.path("external/kotlinc/lib/kotlin-stdlib.jar"));

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
    fixture_d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
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
