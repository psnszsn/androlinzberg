//! Host-JDK checks for task-shell policy that does not require an Android process.
const std = @import("std");
const SourceFiles = @import("artzig").SourceSet;
const command = @import("command.zig");

pub fn addGeometryTests(b: *std.Build, aosp: *std.Build.Dependency, parent: *std.Build.Step) void {
    const kotlin = SourceFiles.Root.dependency(b, aosp, "external/kotlinc");
    var compiler = SourceFiles.Collector.init(b);
    compiler.addTree(kotlin.child(b, "bin"), .{ .extensions = &.{""} });
    compiler.addTree(kotlin.child(b, "lib"), .{ .extensions = &.{""} });
    compiler.addFile(kotlin, "build.txt");
    const compile = command.create(b);
    compile.addFileArg(kotlin.path.path(b, "bin/kotlinc"));
    compile.addArgs(&.{ "-no-stdlib", "-no-reflect", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-classpath" });
    const stdlib = kotlin.path.path(b, "lib/kotlin-stdlib.jar");
    compile.addFileArg(stdlib);
    compile.addFileArg(b.path("src/java/dev/artzig/window/TaskGeometry.kt"));
    compile.addFileArg(b.path("src/java/dev/artzig/window/TaskGeometrySmoke.kt"));
    compile.addArg("-d");
    const classes = compile.addOutputFileArg("task-geometry.jar");
    compiler.select(null).addInputs(compile.run);
    const run = command.create(b);
    run.addArgs(&.{ "java", "-cp" });
    run.addJoinedPaths(":", &.{ classes, stdlib });
    run.addArg("dev.artzig.window.TaskGeometrySmoke");
    run.run.setName("verify bounded task geometry and transaction reservations");
    b.step("task-geometry-test", "Verify task bounds, relocation, capacity and stale acknowledgements").dependOn(&run.run.step);
    parent.dependOn(&run.run.step);
}
