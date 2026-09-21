//! JUnit 4 and Hamcrest from the pinned AOSP sources, for the UI tests on ART.
const std = @import("std");
const SourceFiles = @import("artzig").SourceSet;
const command = @import("command.zig");

fn javac(b: *std.Build) command.Command {
    const result = command.create(b);
    result.addArg("javac");
    inline for (.{
        "Note: Some input files use or override a deprecated API.",
        "Note: Recompile with -Xlint:deprecation for details.",
        "Note: Some input files use unchecked or unsafe operations.",
        "Note: Recompile with -Xlint:unchecked for details.",
    }) |pattern| result.filterStderr(pattern);
    return result;
}

fn jarClasses(
    b: *std.Build,
    name: []const u8,
    directories: []const std.Build.LazyPath,
) std.Build.LazyPath {
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const output = jar.addOutputFileArg(name);
    for (directories) |directory| {
        jar.addArg("-C");
        jar.addDirectoryArg(directory);
        jar.addArg(".");
    }
    return output;
}

/// `junit-hamcrest.jar`.
pub fn build(b: *std.Build, aosp: *std.Build.Dependency) std.Build.LazyPath {
    var hamcrest_collector = SourceFiles.Collector.init(b);
    hamcrest_collector.addTree(
        SourceFiles.Root.dependency(b, aosp, "external/hamcrest/hamcrest-core/src/main/java"),
        .{ .extensions = &.{".java"} },
    );
    const hamcrest_sources = hamcrest_collector.select(".java");

    var junit_collector = SourceFiles.Collector.init(b);
    junit_collector.addTree(
        SourceFiles.Root.dependency(b, aosp, "external/junit/src/main/java"),
        .{ .extensions = &.{".java"} },
    );
    const junit_sources = junit_collector.select(".java");

    const hamcrest_javac = javac(b);
    hamcrest_javac.addArgs(&.{ "--release", "11", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-d" });
    const hamcrest_classes = hamcrest_javac.addOutputDirectoryArg("hamcrest-classes");
    hamcrest_javac.addPrefixedFileArg("@", hamcrest_sources.responseFile(b, "hamcrest-sources.rsp"));
    hamcrest_sources.addInputs(hamcrest_javac.run);
    hamcrest_javac.run.setName("compile Hamcrest");

    const junit_javac = javac(b);
    junit_javac.addArgs(&.{ "--release", "11", "-nowarn", "-Xlint:none", "-encoding", "UTF-8", "-classpath" });
    junit_javac.addDirectoryArg(hamcrest_classes);
    junit_javac.addArg("-d");
    const junit_classes = junit_javac.addOutputDirectoryArg("junit-classes");
    junit_javac.addPrefixedFileArg("@", junit_sources.responseFile(b, "junit-sources.rsp"));
    junit_sources.addInputs(junit_javac.run);
    junit_javac.run.setName("compile JUnit");

    return jarClasses(
        b,
        "junit-hamcrest.jar",
        &.{ hamcrest_classes, junit_classes },
    );
}
