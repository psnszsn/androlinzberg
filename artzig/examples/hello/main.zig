const std = @import("std");
const art = @import("art");

pub fn main(init: std.process.Init) !void {
    const gpa = init.gpa;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2) {
        std.debug.print("usage: {s} <classes.dex> [Main [args...]]\n", .{args[0]});
        return error.Usage;
    }

    var rt = try art.Runtime.create(gpa, init.io, init.minimal.environ, .{
        .classpath = &.{args[1]},
        .check_jni = true,
        .use_bionic_loader = init.minimal.environ.getPosix("ARTZIG_BIONIC") != null,
    });
    defer rt.destroy();
    std.debug.print("ART up, runtime tree: {s}\n", .{rt.root});

    const main_class: []const u8 = if (args.len > 2) args[2] else "Hello";
    const rest: []const [:0]const u8 = if (args.len > 3) args[3..] else &.{};
    try rt.runMain(main_class, rest);
}
