//! Execute a generic-linux AOSP prebuilt through the host glibc loader.
const std = @import("std");
const Io = std.Io;

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2) {
        try Io.File.stderr().writeStreamingAll(io, "usage: aosp-tool-runner [--library-dir <path>] <tool> [args...]\n");
        return error.InvalidArguments;
    }
    var tool_index: usize = 1;
    var library_dir: ?[]const u8 = null;
    if (std.mem.eql(u8, args[tool_index], "--library-dir")) {
        if (args.len < 4) return error.InvalidArguments;
        library_dir = args[tool_index + 1];
        tool_index += 2;
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

    const child_len = 1 + (if (library_dir != null) @as(usize, 2) else 0) + args.len - tool_index;
    const child_argv = try init.arena.allocator().alloc([]const u8, child_len);
    var child_index: usize = 0;
    child_argv[child_index] = loader;
    child_index += 1;
    if (library_dir) |path| {
        // The build cache copies the executable away from its AOSP $ORIGIN/../lib64 tree.
        // Pass that pinned runtime directory to the loader without mutating the inherited
        // LD_LIBRARY_PATH (which may carry Mesa or other host integration libraries).
        child_argv[child_index] = "--library-path";
        child_argv[child_index + 1] = path;
        child_index += 2;
    }
    @memcpy(child_argv[child_index..], args[tool_index..]);
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
