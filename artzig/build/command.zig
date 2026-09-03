//! Typed build-graph interface to the shared host command runner.
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

    pub fn addOutputDirectoryArg(command: Command, name: []const u8) LazyPath {
        command.run.addArg("--arg");
        return command.run.addOutputDirectoryArg(name);
    }

    pub fn addPrefixedOutputDirectoryArg(command: Command, prefix: []const u8, name: []const u8) LazyPath {
        command.run.addArgs(&.{ "--prefix", prefix });
        return command.run.addOutputDirectoryArg(name);
    }

    pub fn filterStderr(command: Command, pattern: []const u8) void {
        command.run.addArgs(&.{ "--stderr-filter", pattern });
    }

    pub fn setPathEnvironment(command: Command, name: []const u8, value: LazyPath) void {
        command.run.addArgs(&.{ "--env", name });
        command.run.addDirectoryArg(value);
    }

    pub fn setChildPathEnvironment(command: Command, name: []const u8, child: []const u8, parent: LazyPath) void {
        command.run.addArgs(&.{ "--env-child", name, child });
        command.run.addDirectoryArg(parent);
    }
};

pub fn create(b: *std.Build) Command {
    const executable = b.addExecutable(.{
        .name = "build-command",
        .root_module = b.createModule(.{
            .root_source_file = b.path("tools/build-command.zig"),
            .target = b.graph.host,
            .optimize = .ReleaseSafe,
        }),
    });
    return .{ .b = b, .run = b.addRunArtifact(executable) };
}
