//! Execute a generic-linux AOSP prebuilt through the host glibc loader.
const std = @import("std");
const Io = std.Io;

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2) {
        try Io.File.stderr().writeStreamingAll(io, "usage: aosp-tool-runner <tool> [args...]\n");
        return error.InvalidArguments;
    }

    const probe = try std.process.run(init.gpa, io, .{
        .argv = &.{ "cc", "-print-file-name=ld-linux-x86-64.so.2" },
    });
    defer init.gpa.free(probe.stdout);
    defer init.gpa.free(probe.stderr);
    switch (probe.term) {
        .exited => |code| if (code != 0) {
            try Io.File.stderr().writeStreamingAll(io, probe.stderr);
            return error.LoaderProbeFailed;
        },
        else => return error.LoaderProbeFailed,
    }
    const loader = std.mem.trim(u8, probe.stdout, " \t\r\n");
    if (loader.len == 0) return error.LoaderNotFound;
    try Io.Dir.accessAbsolute(io, loader, .{});

    const child_argv = try init.arena.allocator().alloc([]const u8, args.len);
    child_argv[0] = loader;
    @memcpy(child_argv[1..], args[1..]);
    var child = try std.process.spawn(io, .{
        .argv = child_argv,
        .stdin = .inherit,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    const term = try child.wait(io);
    switch (term) {
        .exited => |code| std.process.exit(code),
        else => std.process.exit(1),
    }
}
