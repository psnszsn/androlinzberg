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
    compile.addFileArg(b.path("src/java/dev/artzig/window/WaylandTaskShell.kt"));
    compile.addFileArg(b.path("src/java/dev/artzig/window/WaylandTaskShellSmoke.kt"));
    compile.addFileArg(b.path("src/java/dev/artzig/system/HostedProcessStarts.kt"));
    compile.addFileArg(b.path("src/java/dev/artzig/system/HostedProcessStartsSmoke.kt"));
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
    const shell = command.create(b);
    shell.addArgs(&.{ "java", "-cp" });
    shell.addJoinedPaths(":", &.{ classes, stdlib });
    shell.addArg("dev.artzig.window.WaylandTaskShellSmoke");
    shell.run.setName("verify task window lifecycle and coordinated geometry");
    b.step("task-shell-test", "Verify task window ownership, resize, close and stale callbacks").dependOn(&shell.run.step);
    parent.dependOn(&shell.run.step);
    const processes = command.create(b);
    processes.addArgs(&.{ "java", "-cp" });
    processes.addJoinedPaths(":", &.{ classes, stdlib });
    processes.addArg("dev.artzig.system.HostedProcessStartsSmoke");
    processes.run.setName("verify process startup generations and deadlines");
    b.step("process-start-test", "Verify process attach, exit, retry and stale startup callbacks").dependOn(&processes.run.step);
    parent.dependOn(&processes.run.step);
}
