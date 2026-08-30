//! azk-binder-broker: the hosted Binder transaction router process.
//!
//! Usage: AZK_BINDER_COOKIE=<cookie> azk-binder-broker --socket <path> [--app-uid N]
//!
//! The launch cookie comes from the environment, not argv, so it is not
//! readable through /proc/<pid>/cmdline. Prints "ready" on stdout once
//! listening. Exits when stdin reaches EOF (the launcher holds the other end).
const std = @import("std");
const Io = std.Io;
const Broker = @import("Broker.zig");
const Client = @import("Client.zig");

pub const std_options: std.Options = .{ .log_level = .info };

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const gpa = init.gpa;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    var socket_path: ?[]const u8 = null;
    const cookie = init.minimal.environ.getPosix("AZK_BINDER_COOKIE");
    var app_uid: u32 = Client.default_app_uid;
    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        const arg = args[i];
        if (std.mem.eql(u8, arg, "--socket") and i + 1 < args.len) {
            i += 1;
            socket_path = args[i];
        } else if (std.mem.eql(u8, arg, "--app-uid") and i + 1 < args.len) {
            i += 1;
            app_uid = try std.fmt.parseInt(u32, args[i], 10);
        } else {
            std.debug.print("usage: AZK_BINDER_COOKIE=<cookie> azk-binder-broker --socket <path> [--app-uid N]\n", .{});
            return error.InvalidArguments;
        }
    }
    const broker = try Broker.init(gpa, io, .{
        .socket_path = socket_path orelse return error.InvalidArguments,
        .cookie = cookie orelse {
            std.debug.print("azk-binder-broker: AZK_BINDER_COOKIE is not set\n", .{});
            return error.InvalidArguments;
        },
        .app_uid = app_uid,
    });
    defer broker.deinit();

    var group: Io.Group = .init;
    try group.concurrent(io, watchStdin, .{broker});
    try Io.File.stdout().writeStreamingAll(io, "ready\n");
    try broker.run();
    group.await(io) catch {};
}

/// The launcher owns the other end of stdin; EOF means the session is over.
fn watchStdin(broker: *Broker) Io.Cancelable!void {
    const io = broker.io;
    var buf: [64]u8 = undefined;
    while (true) {
        const n = Io.File.stdin().readStreaming(io, &.{&buf}) catch break;
        if (n == 0) break;
    }
    broker.shutdown();
}
