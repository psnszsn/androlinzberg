//! Framework JVM: the vendored AOSP subset + our Java/Kotlin replacements ->
//! a multidex framework.jar, plus framework-res.apk (aapt2) and generated Java. Replaces
//! tools/build-java.sh; the curated source list lives in build/java_sources.zig.
const std = @import("std");
const LazyPath = std.Build.LazyPath;
const SourceFiles = @import("artzig").SourceFiles;
const java_sources = @import("java_sources.zig");

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

pub fn build(b: *std.Build, o: Options) Result {
    const dep = o.artzig;
    const aosp = o.aosp;
    const fb = aosp.path("frameworks/base");
    const core_all = dep.namedLazyPath("core-all-jar");
    const sources = java_sources.configure(b, aosp);
    var kotlinc_input_collector = SourceFiles.Collector.init(b);
    const kotlinc_root = SourceFiles.Root.dependency(b, aosp, "external/kotlinc");
    kotlinc_input_collector.addTree(kotlinc_root.child(b, "bin"), .{ .extensions = &.{""} });
    kotlinc_input_collector.addTree(kotlinc_root.child(b, "lib"), .{ .extensions = &.{""} });
    kotlinc_input_collector.addFile(kotlinc_root, "build.txt");
    const kotlinc_inputs = kotlinc_input_collector.select(null);

    const source_policy = b.addSystemCommand(&.{"bash"});
    source_policy.addFileArg(b.path("tools/check-jvm-source-policy.sh"));
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
    const res_apk = o.generated.res_apk;
    const fixture_apks_dir = o.generated.fixture_apks_dir;

    // 3. Kotlin first, then Java, matching AOSP Soong's mixed-source ordering.
    // Each compiler owns its output directory; the only shell logic joins
    // LazyPaths into classpath arguments and shifts the fixed arguments before
    // forwarding every generated Java file directly.
    const kotlin_stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");
    const kotlinc = b.addSystemCommand(&.{
        "sh",
        "-c",
        \\compiler="$1"; classpath="$2:$3"; output="$4"; java_sources="$5"; kotlin_sources="$6"; shift 6
        \\exec "$compiler" -no-jdk -no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 21 -java-parameters -Xsam-conversions=class -Xstring-concat=inline -classpath "$classpath" -d "$output" "@$java_sources" "@$kotlin_sources" "$@"
        ,
        "sh",
    });
    kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    kotlinc.addFileArg(core_plus);
    kotlinc.addFileArg(kotlin_stdlib);
    const kotlin_classes = kotlinc.addOutputDirectoryArg("kotlin-classes");
    kotlinc.addFileArg(sources.java_rsp);
    kotlinc.addFileArg(sources.kotlin_rsp);
    for (o.generated.java_files) |file| kotlinc.addFileArg(file);
    sources.addKotlincInputs(kotlinc);
    kotlinc_inputs.addInputs(kotlinc);
    kotlinc.step.dependOn(&source_policy.step);
    // C1-only code fills the code cache faster; size it for the whole tree.
    kotlinc.setEnvironmentVariable("JAVA_OPTS", "-Xmx2048M -Xms256M -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=512m");
    kotlinc.setName("compile framework Kotlin");

    // android.annotation belongs to the system-module image, so runtime copies
    // of annotation classes need a separate module-free javac pass.
    const annotation_javac = b.addSystemCommand(&.{
        "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-d",
    });
    const annotation_classes = annotation_javac.addOutputDirectoryArg("annotation-classes");
    annotation_javac.addPrefixedFileArg("@", sources.annotations_rsp);
    sources.addAnnotationInputs(annotation_javac);
    annotation_javac.setName("compile runtime annotations");

    const javac = b.addSystemCommand(&.{
        "bash",
        "-c",
        \\system="$1"; classpath="$2:$3:$4"; output="$5"; sources="$6"; shift 6
        \\exec javac --system="$system" -source 21 -target 21 -encoding UTF-8 -g -parameters -XDstringConcat=inline -Xmaxerrs 2000 -nowarn -Xlint:none -classpath "$classpath" -d "$output" "@$sources" "$@" 2> >(grep -F -v -e 'Note: Some input files use or override a deprecated API.' -e 'Note: Recompile with -Xlint:deprecation for details.' -e 'Note: Some input files use unchecked or unsafe operations.' -e 'Note: Recompile with -Xlint:unchecked for details.' >&2)
        ,
        "bash",
    });
    javac.addDirectoryArg(sysmod_dir);
    javac.addDirectoryArg(kotlin_classes);
    javac.addDirectoryArg(annotation_classes);
    javac.addFileArg(kotlin_stdlib);
    const java_classes = javac.addOutputDirectoryArg("java-classes");
    javac.addFileArg(sources.java_rsp);
    for (o.generated.java_files) |file| javac.addFileArg(file);
    sources.addJavacInputs(javac);
    javac.step.dependOn(&source_policy.step);
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
