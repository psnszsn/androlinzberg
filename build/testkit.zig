//! Host-JDK Kotlin test-control client, CLI, and JUnit integration tests.
const std = @import("std");

pub const Result = struct {
    install_step: *std.Build.Step,
};

fn stamp(b: *std.Build, name: []const u8, paths: []const std.Build.LazyPath) std.Build.LazyPath {
    const run = b.addSystemCommand(&.{ "python3", "tools/tree-stamp.py" });
    const out = run.addOutputFileArg(name);
    for (paths) |path| run.addDirectoryArg(path);
    run.has_side_effects = true;
    run.setName(b.fmt("tree-stamp {s}", .{name}));
    return out;
}

pub fn build(b: *std.Build, aosp: *std.Build.Dependency) Result {
    const inputs = stamp(b, "testkit.stamp", &.{
        b.path("src/testkit/kotlin"),
        b.path("tests/testkit/kotlin"),
        b.path("tests/integration/kotlin"),
        b.path("tools/build-testkit.sh"),
        aosp.path("external/junit/src/main/java"),
        aosp.path("external/hamcrest/hamcrest-core/src/main/java"),
    });

    const run = b.addSystemCommand(&.{"bash"});
    run.addFileArg(b.path("tools/build-testkit.sh"));
    run.addDirectoryArg(aosp.path("external/kotlinc"));
    run.addDirectoryArg(aosp.path("external/junit"));
    run.addDirectoryArg(aosp.path("external/hamcrest"));
    run.addFileArg(aosp.path("prebuilts/tools/common/m2/repository/com/google/code/gson/gson/2.9.1/gson-2.9.1.jar"));
    run.addDirectoryArg(b.path("src/testkit/kotlin"));
    run.addDirectoryArg(b.path("tests/testkit/kotlin"));
    run.addDirectoryArg(b.path("tests/integration/kotlin"));
    const output = run.addOutputDirectoryArg("azk-testkit");
    run.addFileInput(inputs);

    const install = b.addInstallDirectory(.{
        .source_dir = output,
        .install_dir = .lib,
        .install_subdir = "azk-test",
    });
    return .{ .install_step = &install.step };
}
