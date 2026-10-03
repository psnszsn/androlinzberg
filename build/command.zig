//! Typed build-graph interface to tools/build-command.zig.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Command = struct {
    b: *std.Build,
    run: *std.Build.Step.Run,

    pub fn addArg(command: Command, value: []const u8) void {
        command.run.addArgs(&.{ "--arg", value });
    }

    pub fn addArgs(command: Command, values: []const []const u8) void {
        for (values) |value| command.addArg(value);
    }

    pub fn addFileArg(command: Command, value: LazyPath) void {
        command.run.addArg("--arg");
        command.run.addFileArg(value);
    }

    pub fn addDirectoryArg(command: Command, value: LazyPath) void {
        command.run.addArg("--arg");
        command.run.addDirectoryArg(value);
    }

    pub fn addPrefixedFileArg(command: Command, prefix: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--prefix", prefix });
        command.run.addFileArg(value);
    }

    pub fn addPrefixedDirectoryArg(command: Command, prefix: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--prefix", prefix });
        command.run.addDirectoryArg(value);
    }

    pub fn addChildPathArg(command: Command, child: []const u8, parent: LazyPath) void {
        command.run.addArgs(&.{ "--child-path", child });
        command.run.addDirectoryArg(parent);
    }

    pub fn addPrefixedChildPathArg(command: Command, prefix: []const u8, child: []const u8, parent: LazyPath) void {
        command.run.addArgs(&.{ "--prefix-child-path", prefix, child });
        command.run.addDirectoryArg(parent);
    }

    pub fn addPrefixedJoinedChildPaths(command: Command, prefix: []const u8, separator: []const u8, children: []const []const u8, parent: LazyPath) void {
        command.run.addArgs(&.{ "--join-child-paths", prefix, separator, command.b.fmt("{d}", .{children.len}) });
        command.run.addArgs(children);
        command.run.addDirectoryArg(parent);
    }

    pub fn addPrefixedOutputFileArg(command: Command, prefix: []const u8, name: []const u8) LazyPath {
        command.run.addArgs(&.{ "--prefix", prefix });
        return command.run.addOutputFileArg(name);
    }

    pub fn addPrefixedOutputDirectoryArg(command: Command, prefix: []const u8, name: []const u8) LazyPath {
        command.run.addArgs(&.{ "--prefix", prefix });
        return command.run.addOutputDirectoryArg(name);
    }

    pub fn addOutputFileArg(command: Command, name: []const u8) LazyPath {
        command.run.addArg("--arg");
        return command.run.addOutputFileArg(name);
    }

    pub fn addOutputDirectoryArg(command: Command, name: []const u8) LazyPath {
        command.run.addArg("--arg");
        return command.run.addOutputDirectoryArg(name);
    }

    /// Declare a parent directory as the cached output while passing a child
    /// path to a tool that insists its own output path must not exist yet.
    pub fn addOutputChildArg(command: Command, parent_name: []const u8, child_name: []const u8) LazyPath {
        command.run.addArgs(&.{ "--child", child_name });
        const parent = command.run.addOutputDirectoryArg(parent_name);
        return parent.path(command.b, child_name);
    }

    pub fn addJoinedPaths(command: Command, separator: []const u8, values: []const LazyPath) void {
        command.run.addArgs(&.{ "--join", separator, command.b.fmt("{d}", .{values.len}) });
        for (values) |value| command.run.addFileArg(value);
    }

    pub const PathInput = union(enum) {
        file: LazyPath,
        directory: LazyPath,
    };

    pub fn addJoinedPathInputs(command: Command, separator: []const u8, values: []const PathInput) void {
        command.run.addArgs(&.{ "--join", separator, command.b.fmt("{d}", .{values.len}) });
        for (values) |value| switch (value) {
            .file => |path| command.run.addFileArg(path),
            .directory => |path| command.run.addDirectoryArg(path),
        };
    }

    pub fn addPrefixedJoinedPaths(command: Command, prefix: []const u8, separator: []const u8, values: []const LazyPath) void {
        command.run.addArgs(&.{ "--join-prefix", prefix, separator, command.b.fmt("{d}", .{values.len}) });
        for (values) |value| command.run.addFileArg(value);
    }

    /// Sets a plain environment variable through the wrapper instead of
    /// Step.Run.setEnvironmentVariable: a Run step that owns an environment
    /// map clones the one captured in Zig's configuration cache, so the child
    /// would stop seeing variables exported for the current invocation (the
    /// Mesa paths the Wayland test harness needs). Through the wrapper the
    /// step inherits the live environment and adds only this entry.
    pub fn setEnvironment(command: Command, name: []const u8, value: []const u8) void {
        command.run.addArgs(&.{ "--env", name, value });
    }

    pub fn setPathEnvironment(command: Command, name: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--env-path", name });
        command.run.addDirectoryArg(value);
    }

    /// Prepends a path to the inherited value of a list variable such as
    /// LD_LIBRARY_PATH, so a host-provided search path (Mesa for the Wayland
    /// test harness) stays visible behind the emitted runtime's directory.
    pub fn prependPathEnvironment(command: Command, name: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--env-path-prepend", name });
        command.run.addDirectoryArg(value);
    }

    /// A build-root-relative path, passed absolute: Android processes run from "/".
    pub fn setHostPathEnvironment(command: Command, name: []const u8, relative: []const u8) void {
        command.run.addArgs(&.{ "--env-path", name, relative });
    }

    /// Prepends an absolute file path to a colon-separated list variable.
    pub fn prependFileEnvironment(command: Command, name: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--env-path-prepend", name });
        command.run.addFileArg(value);
    }

    pub fn setChildPathEnvironment(command: Command, name: []const u8, child: []const u8, parent: LazyPath) void {
        command.run.addArgs(&.{ "--env-child", name, child });
        command.run.addDirectoryArg(parent);
    }

    pub fn setFileEnvironment(command: Command, name: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--env-path", name });
        command.run.addFileArg(value);
    }

    pub fn setOutputDirectoryEnvironment(command: Command, name: []const u8, output_name: []const u8) LazyPath {
        command.run.addArgs(&.{ "--env", name });
        return command.run.addOutputDirectoryArg(output_name);
    }

    pub fn filterStderr(command: Command, pattern: []const u8) void {
        command.run.addArgs(&.{ "--stderr-filter", pattern });
    }

    pub fn addPassthruArgs(command: Command) void {
        command.run.addArg("--passthru");
        command.run.addPassthruArgs();
    }
};

pub fn executable(b: *std.Build) *std.Build.Step.Compile {
    return b.addExecutable(.{
        .name = "build-command",
        .root_module = b.createModule(.{
            .root_source_file = b.path("artzig/tools/build-command.zig"),
            .target = b.graph.host,
            .optimize = .ReleaseSafe,
        }),
    });
}

pub fn create(b: *std.Build) Command {
    return .{ .b = b, .run = b.addRunArtifact(executable(b)) };
}

pub fn addTests(b: *std.Build, parent: *std.Build.Step) void {
    const tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("artzig/tools/build-command.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    parent.dependOn(&b.addRunArtifact(tests).step);
}
