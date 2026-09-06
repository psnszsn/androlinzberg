//! Link pinned AOSP Rust tests with the same C++ runtime as their native inputs.
const std = @import("std");

pub fn main(init: std.process.Init) !void {
    const allocator = init.arena.allocator();
    const args = try init.minimal.args.toSlice(allocator);
    const argv = try allocator.alloc([]const u8, args.len + 1);
    argv[0] = init.environ_map.get("AZK_RUST_LINK_ZIG") orelse return error.MissingZig;
    argv[1] = "c++";
    @memcpy(argv[2..], args[1..]);
    var child = try std.process.spawn(init.io, .{
        .argv = argv,
        .stdin = .inherit,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    switch (try child.wait(init.io)) {
        .exited => |code| std.process.exit(code),
        else => std.process.exit(1),
    }
}
