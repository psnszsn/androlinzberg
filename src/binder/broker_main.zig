//! azk-binder-broker: the hosted Binder transaction router process.
//!
//! Usage: AZK_BINDER_COOKIE=<app cookie> AZK_BINDER_SYSTEM_COOKIE=<system cookie>
//!        azk-binder-broker --socket <path> [--first-app-uid N]
//!
//! Both cookies come from the environment, not argv, so they are not readable
//! through /proc/<pid>/cmdline. The system cookie identifies the session's one
//! trusted system process (uid 1000); the app cookie admits app processes,
//! which get one uid per package. Prints "ready" on stdout once listening.
//! Exits when stdin reaches EOF (the launcher holds the other end); with
//! `--session-dir <dir>` it then removes that runtime directory, so a session
//! whose launcher died without cleaning up leaves nothing behind.
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
    var session_dir: ?[]const u8 = null;
    const cookie = init.minimal.environ.getPosix("AZK_BINDER_COOKIE");
    const system_cookie = init.minimal.environ.getPosix("AZK_BINDER_SYSTEM_COOKIE");
    var first_app_uid: u32 = Client.default_app_uid;
    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        const arg = args[i];
        if (std.mem.eql(u8, arg, "--socket") and i + 1 < args.len) {
            i += 1;
            socket_path = args[i];
        } else if (std.mem.eql(u8, arg, "--session-dir") and i + 1 < args.len) {
            i += 1;
            session_dir = args[i];
        } else if (std.mem.eql(u8, arg, "--first-app-uid") and i + 1 < args.len) {
            i += 1;
            first_app_uid = try std.fmt.parseInt(u32, args[i], 10);
        } else {
            std.debug.print("usage: AZK_BINDER_COOKIE=<cookie> AZK_BINDER_SYSTEM_COOKIE=<cookie> azk-binder-broker --socket <path> [--first-app-uid N]\n", .{});
            return error.InvalidArguments;
        }
    }
    const broker = try Broker.init(gpa, io, .{
        .socket_path = socket_path orelse return error.InvalidArguments,
        .cookie = cookie orelse {
            std.debug.print("azk-binder-broker: AZK_BINDER_COOKIE is not set\n", .{});
            return error.InvalidArguments;
        },
        .system_cookie = system_cookie orelse {
            std.debug.print("azk-binder-broker: AZK_BINDER_SYSTEM_COOKIE is not set\n", .{});
            return error.InvalidArguments;
        },
        .first_app_uid = first_app_uid,
    });
    var group: Io.Group = .init;
    try group.concurrent(io, watchStdin, .{broker});
    try Io.File.stdout().writeStreamingAll(io, "ready\n");
    try broker.run();
    group.await(io) catch {};
    broker.deinit();
    if (session_dir) |dir| Io.Dir.cwd().deleteTree(io, dir) catch {};
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
