//! Libraries Soong compiles from source for the stock system apps:
//! kotlinx atomicfu, kotlinx coroutines (core and Android dispatchers) and
//! Guava's ListenableFuture, following their pinned Android.bp modules.
const std = @import("std");
const LazyPath = std.Build.LazyPath;
const SourceFiles = @import("artzig").SourceSet;

pub const Libraries = struct {
    /// Compiled classes, dexed into the apps that link them.
    jars: []const LazyPath,
    /// Java resources (META-INF/services) the apps package.
    resources: []const LazyPath,
};

/// external/kotlinx.coroutines kotlinx_coroutines + kotlinx_coroutines_android,
/// with external/kotlinx.atomicfu kotlinx_atomicfu, and external/guava's
/// guava-listenablefuture-prebuilt-jar.
pub fn build(b: *std.Build, aosp: *std.Build.Dependency, core: LazyPath, framework: LazyPath) Libraries {
    const stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");
    const root = SourceFiles.Root.dependency(b, aosp, "");

    var atomicfu_jvm = SourceFiles.Collector.init(b);
    atomicfu_jvm.addTree(root.child(b, "external/kotlinx.atomicfu/atomicfu/src/jvmMain"), .{
        .extensions = &.{".kt"},
        .not_names = &.{"LockFreedomTestEnvironment.kt"},
    });
    var atomicfu_common = SourceFiles.Collector.init(b);
    atomicfu_common.addTree(root.child(b, "external/kotlinx.atomicfu/atomicfu/src/commonMain"), .{ .extensions = &.{".kt"} });
    const atomicfu = multiplatform(b, aosp, "kotlinx-atomicfu", atomicfu_jvm.select(".kt"), atomicfu_common.select(".kt"),
        &.{ core, stdlib }, "-Xmulti-platform");

    const stubs = javaJar(b, "kotlinx-coroutines-android-annotation-stubs",
        tree(b, root.child(b, "external/kotlinx.coroutines/android-annotation-stubs/src"), ".java"), &.{core});

    var core_jvm = SourceFiles.Collector.init(b);
    core_jvm.addTree(root.child(b, "external/kotlinx.coroutines/kotlinx-coroutines-core/jvm/src"), .{
        .extensions = &.{".kt"},
        .not_names = &.{"TestCoroutineContext.kt"},
    });
    var core_common = SourceFiles.Collector.init(b);
    core_common.addTree(root.child(b, "external/kotlinx.coroutines/kotlinx-coroutines-core/common/src"), .{
        .extensions = &.{".kt"},
        .not_names = &.{"LockFreeLinkedList.common.kt"},
    });
    core_common.addTree(root.child(b, "external/kotlinx.coroutines/kotlinx-coroutines-core/concurrent/src"), .{ .extensions = &.{".kt"} });
    // kotlinx_coroutines-host is a java_library_host (its debug agent uses
    // java.lang.instrument) exposed to devices by java_host_for_device.
    const coroutines = multiplatformHost(b, aosp, "kotlinx-coroutines", core_jvm.select(".kt"), core_common.select(".kt"),
        // core-all-plus supplies android.annotation (Soong "annotations").
        &.{ stdlib, atomicfu, stubs, framework, core },
        "-Xmulti-platform -Xexpect-actual-classes -opt-in=kotlin.RequiresOptIn -opt-in=kotlin.experimental.ExperimentalTypeInference " ++
            "-opt-in=kotlin.ExperimentalMultiplatform -opt-in=kotlinx.coroutines.DelicateCoroutinesApi " ++
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi -opt-in=kotlinx.coroutines.ObsoleteCoroutinesApi " ++
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi -opt-in=kotlinx.coroutines.FlowPreview");

    const android = multiplatform(b, aosp, "kotlinx-coroutines-android",
        tree(b, root.child(b, "external/kotlinx.coroutines/ui/kotlinx-coroutines-android/src"), ".kt"), .{ .items = &.{} },
        &.{ core, framework, stdlib, coroutines, aosp.path("prebuilts/sdk/current/androidx/m2repository/androidx/annotation/annotation-jvm/1.9.0-rc01/annotation-jvm-1.9.0-rc01.jar") },
        "-opt-in=kotlinx.coroutines.InternalCoroutinesApi -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi");

    // prebuilts/tools/common/m2 guava-listenablefuture-prebuilt-jar.
    const listenable_future = aosp.path("prebuilts/tools/common/m2/repository/com/google/guava/guava/listenablefuture/1.0/listenablefuture-1.0.jar");

    const jars = [_]LazyPath{ atomicfu, coroutines, android, listenable_future };
    const resources = [_]LazyPath{
        resourceJar(b, "kotlinx-coroutines-resources", aosp.path("external/kotlinx.coroutines/kotlinx-coroutines-core/jvm/resources"), &.{"DebugProbesKt.bin"}),
        resourceJar(b, "kotlinx-coroutines-android-resources", aosp.path("external/kotlinx.coroutines/ui/kotlinx-coroutines-android/resources"), &.{}),
    };
    return .{
        .jars = b.allocator.dupe(LazyPath, &jars) catch @panic("OOM"),
        .resources = b.allocator.dupe(LazyPath, &resources) catch @panic("OOM"),
    };
}

fn tree(b: *std.Build, root: SourceFiles.Root, extension: []const u8) SourceFiles.Files {
    var collector = SourceFiles.Collector.init(b);
    collector.addTree(root, .{ .extensions = &.{extension} });
    return collector.select(extension);
}

/// kotlinc over a Kotlin module whose `common` sources are multiplatform
/// common code (Soong common_srcs, -Xcommon-sources).
fn multiplatform(b: *std.Build, aosp: *std.Build.Dependency, name: []const u8, sources: SourceFiles.Files,
    common: SourceFiles.Files, classpath: []const LazyPath, flags: []const u8) LazyPath {
    const run = b.addSystemCommand(&.{ "sh", "-c",
        \\set -e
        \\kotlinc=$1 out=$2 sources=$3 common=$4 flags=$5
        \\shift 5
        \\classpath=$(IFS=:; echo "$*")
        \\set -- $flags
        \\if [ -s "$common" ]; then set -- "$@" "-Xcommon-sources=$(paste -sd, "$common")"; fi
        \\exec "$kotlinc" -no-jdk -no-stdlib -no-reflect -nowarn -jvm-target 21 "$@" -classpath "$classpath" -d "$out" @"$sources" @"$common"
    , "kotlinc-module" });
    run.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    const out = run.addOutputFileArg(b.fmt("{s}.jar", .{name}));
    run.addFileArg(sources.responseFile(b, b.fmt("{s}-sources.rsp", .{name})));
    run.addFileArg(common.responseFile(b, b.fmt("{s}-common.rsp", .{name})));
    run.addArg(flags);
    for (classpath) |entry| run.addFileArg(entry);
    sources.addInputs(run);
    common.addInputs(run);
    run.setName(b.fmt("compile {s}", .{name}));
    return out;
}

/// A Soong host Kotlin module: compiled against the host JDK.
fn multiplatformHost(b: *std.Build, aosp: *std.Build.Dependency, name: []const u8, sources: SourceFiles.Files,
    common: SourceFiles.Files, classpath: []const LazyPath, flags: []const u8) LazyPath {
    const run = b.addSystemCommand(&.{ "sh", "-c",
        \\set -e
        \\kotlinc=$1 out=$2 sources=$3 common=$4 flags=$5
        \\shift 5
        \\classpath=$(IFS=:; echo "$*")
        \\set -- $flags
        \\if [ -s "$common" ]; then set -- "$@" "-Xcommon-sources=$(paste -sd, "$common")"; fi
        \\exec "$kotlinc" -no-stdlib -no-reflect -nowarn -jvm-target 21 "$@" -classpath "$classpath" -d "$out" @"$sources" @"$common"
    , "kotlinc-host-module" });
    run.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    const out = run.addOutputFileArg(b.fmt("{s}.jar", .{name}));
    run.addFileArg(sources.responseFile(b, b.fmt("{s}-sources.rsp", .{name})));
    run.addFileArg(common.responseFile(b, b.fmt("{s}-common.rsp", .{name})));
    run.addArg(flags);
    for (classpath) |entry| run.addFileArg(entry);
    sources.addInputs(run);
    common.addInputs(run);
    run.setName(b.fmt("compile {s}", .{name}));
    return out;
}

fn javaJar(b: *std.Build, name: []const u8, sources: SourceFiles.Files, classpath: []const LazyPath) LazyPath {
    const run = b.addSystemCommand(&.{ "sh", "-c",
        \\set -e
        \\out=$1 sources=$2
        \\shift 2
        \\classpath=$(IFS=:; echo "$*")
        \\classes=$(mktemp -d)
        \\javac -source 21 -target 21 -nowarn -encoding UTF-8 -proc:none -classpath "$classpath" -d "$classes" @"$sources"
        \\jar --create --file "$out" -C "$classes" .
        \\rm -rf "$classes"
    , "java-library" });
    const out = run.addOutputFileArg(b.fmt("{s}.jar", .{name}));
    run.addFileArg(sources.responseFile(b, b.fmt("{s}-sources.rsp", .{name})));
    for (classpath) |entry| run.addFileArg(entry);
    sources.addInputs(run);
    run.setName(b.fmt("compile {s}", .{name}));
    return out;
}

/// Soong java_resource_dirs (minus exclude_java_resources) as a jar.
fn resourceJar(b: *std.Build, name: []const u8, directory: LazyPath, exclude: []const []const u8) LazyPath {
    const run = b.addSystemCommand(&.{ "sh", "-c",
        \\set -e
        \\out=$1 directory=$2
        \\shift 2
        \\staging=$(mktemp -d)
        \\cp -R "$directory"/. "$staging"
        \\for excluded in "$@"; do rm -f "$staging/$excluded"; done
        \\jar --create --file "$out" -C "$staging" .
        \\rm -rf "$staging"
    , "java-resources" });
    const out = run.addOutputFileArg(b.fmt("{s}.jar", .{name}));
    run.addDirectoryArg(directory);
    run.addArgs(exclude);
    run.setName(b.fmt("package {s}", .{name}));
    return out;
}
