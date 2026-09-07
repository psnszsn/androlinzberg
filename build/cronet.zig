//! Pinned httpclient_impl_internal compilation and HttpEngine preload generation.
const std = @import("std");
const SourceFiles = @import("artzig").SourceSet;
const command = @import("command.zig");
const declarations = @import("generator_sources.zig").cronet;
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    aosp: *std.Build.Dependency,
    system_modules: LazyPath,
    implementation_sources: SourceFiles.Files,
    generated_sources: []const LazyPath,
    protobuf_classes: LazyPath,
    androidx_annotations: LazyPath,
    error_prone_annotations: LazyPath,
    kotlin_stdlib: LazyPath,
};

pub const Result = struct {
    preload_source: LazyPath,
    experimental_annotations: LazyPath,
};

pub fn build(b: *std.Build, o: Options) Result {
    const aar = b.addSystemCommand(&.{ "unzip", "-q" });
    aar.addFileArg(o.aosp.path(declarations.preload.experimental_annotations));
    aar.addArgs(&.{ "classes.jar", "-d" });
    const aar_root = aar.addOutputDirectoryArg("annotation-experimental-aar");
    const annotations = b.addSystemCommand(&.{ "unzip", "-q" });
    annotations.addFileArg(aar_root.path(b, "classes.jar"));
    annotations.addArgs(&.{ "androidx/annotation/*", "-d" });
    const annotation_classes = annotations.addOutputDirectoryArg("annotation-experimental-classes");
    annotations.setName("extract pinned experimental AndroidX annotations");

    // AOSP compiles this implementation against SDK contracts before compiling
    // HttpEngine itself. These headers and intermediate classes are never shipped.
    const javac = command.create(b);
    javac.addArgs(&.{ "javac", "--system" });
    javac.addDirectoryArg(o.system_modules);
    javac.addArgs(&.{ "-source", "21", "-target", "21", "-encoding", "UTF-8", "-XDstringConcat=inline", "-nowarn", "-classpath" });
    javac.addJoinedPathInputs(":", &.{
        .{ .file = o.aosp.path(declarations.preload.stats_sdk) },
        .{ .file = o.aosp.path(declarations.preload.sdk) },
        .{ .directory = o.protobuf_classes },
        .{ .directory = o.androidx_annotations },
        .{ .directory = annotation_classes },
        .{ .directory = o.error_prone_annotations },
        .{ .file = o.kotlin_stdlib },
    });
    javac.addArg("-d");
    const classes = javac.addOutputDirectoryArg("httpclient-impl-classes");
    javac.addPrefixedFileArg("@", o.implementation_sources.responseFile(b, "httpclient-impl-sources.rsp"));
    o.implementation_sources.addInputs(javac.run);
    for (o.generated_sources) |source| javac.addFileArg(source);
    javac.run.setName("compile stock HttpEngine implementation for preload inventory");

    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const implementation_jar = jar.addOutputFileArg("httpclient-impl-internal.jar");
    for ([_]LazyPath{ classes, o.protobuf_classes }) |directory| {
        jar.addArg("-C");
        jar.addDirectoryArg(directory);
        jar.addArg(".");
    }
    jar.setName("package stock HttpEngine preload inputs");

    // This monolithic framework keeps original package names, without Soong's
    // module jarjar relocation. Feed that namespace policy to the original
    // collector; retain its exclusion rules and enumerate actual class files.
    const policy = b.addWriteFiles();
    const unrelocated = policy.add("unrelocated-packages.txt", ".*\n");
    const collect = b.addSystemCommand(&.{ "python3", "-B" });
    collect.addFileArg(o.aosp.path(declarations.preload.tool));
    collect.addFileArg(implementation_jar);
    collect.addArgs(&.{ "--prefix", "android.net.connectivity", "--prefix-excludes" });
    collect.addFileArg(o.aosp.path(declarations.preload.prefix_excludes));
    collect.addArg("--prefix-excludes");
    collect.addFileArg(unrelocated);
    collect.addArg("--excludes");
    collect.addFileArg(o.aosp.path(declarations.preload.excludes));
    collect.addArgs(&.{ "--java-package", "android.net.http", "--output" });
    const preload = collect.addOutputFileArg(declarations.preload.output);
    collect.setName("generate stock HttpEngine Java preload list");
    b.step("gen-httpengine-preload", "Generate HttpEngine's preload list from compiled AOSP classes").dependOn(&collect.step);
    return .{ .preload_source = preload, .experimental_annotations = annotation_classes };
}
