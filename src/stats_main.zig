//! `androlinzberg stats`: `adb shell cmd stats` and `dumpsys stats` for a
//! running session. It joins the session as the broker's inspector, Android's
//! shell uid, which is the identity stock statsd accepts commands from.
const std = @import("std");
const binder_session = @import("binder/session.zig");
const StorageView = @import("binder/StorageView.zig");

const AIBinder = opaque {};
const AParcel = opaque {};
extern fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
extern fn unsetenv(name: [*:0]const u8) c_int;
extern fn AServiceManager_checkService(instance: [*:0]const u8) callconv(.c) ?*AIBinder;
extern fn AIBinder_dump(binder: ?*AIBinder, fd: i32, args: ?[*]const [*:0]const u8, count: u32) callconv(.c) i32;
extern fn AParcel_create() callconv(.c) *AParcel;
extern fn AParcel_delete(parcel: ?*AParcel) callconv(.c) void;
extern fn AParcel_writeInt32(parcel: *AParcel, value: i32) callconv(.c) i32;
extern fn AParcel_writeString(parcel: *AParcel, string: ?[*]const u8, length: i32) callconv(.c) i32;
extern fn AParcel_writeStrongBinder(parcel: *AParcel, binder: ?*AIBinder) callconv(.c) i32;
extern fn azk_parcel_writeDupFd(parcel: *AParcel, fd: i32) callconv(.c) i32;
extern fn azk_binder_transact(binder: *AIBinder, code: u32, input: *const AParcel, output: ?*AParcel, flags: u32) callconv(.c) i32;

const SHELL_COMMAND_TRANSACTION: u32 = ('_' << 24) | ('C' << 16) | ('M' << 8) | 'D';

const usage =
    \\usage: androlinzberg stats [--join <session-dir>] <command> [arguments]
    \\  dump [arguments]      dumpsys stats (default arguments: --metadata)
    \\  <anything else>       cmd stats <command>; `help` lists statsd's commands
    \\
    \\Standard input and output reach statsd directly: `config update <id>` reads
    \\a StatsdConfig and `dump-report <id> --proto` writes a report.
    \\
;

fn fail(comptime format: []const u8, arguments: anytype) noreturn {
    std.debug.print("androlinzberg stats: " ++ format ++ "\n", arguments);
    std.process.exit(1);
}

fn soleSession(init: std.process.Init) []const u8 {
    const a = init.arena.allocator();
    const sessions = binder_session.liveSessions(a, init.io, init.minimal.environ) catch |err|
        fail("cannot list sessions: {s}", .{@errorName(err)});
    if (sessions.len == 1) return sessions[0];
    if (sessions.len == 0) fail("no session is running", .{});
    std.debug.print("androlinzberg stats: several sessions are running; choose one with --join:\n", .{});
    for (sessions) |dir| std.debug.print("  {s}\n", .{dir});
    std.process.exit(1);
}

fn join(init: std.process.Init, dir: []const u8) !void {
    const a = init.arena.allocator();
    const info = try binder_session.SessionInfo.read(a, init.io, dir);
    const cookie = info.inspector_cookie orelse return error.SessionDoesNotPermitInspection;
    // The broker admits cookies only from the owner's user namespace.
    if (info.storage != null) try StorageView.join(init.io, a, init.minimal.environ, info.pid);
    _ = unsetenv(binder_session.system_cookie_env);
    _ = unsetenv("AZK_LAUNCH_CREDENTIAL");
    _ = setenv(binder_session.socket_env, try a.dupeSentinel(u8, info.socket, 0), 1);
    _ = setenv(binder_session.cookie_env, try a.dupeSentinel(u8, cookie, 0), 1);
    _ = setenv(binder_session.prop_area_env, try a.dupeSentinel(u8, info.prop_area, 0), 1);
    _ = setenv("AZK_BINDER_PACKAGE", "com.android.shell", 1);
}

/// Pinned IBinder::shellCommand, without a result receiver: the transaction's
/// own status carries the command's result.
fn shellCommand(stats: *AIBinder, arguments: []const [:0]const u8) i32 {
    const request = AParcel_create();
    defer AParcel_delete(request);
    const reply = AParcel_create();
    defer AParcel_delete(reply);
    for ([_]i32{ 0, 1, 2 }) |fd| {
        const status = azk_parcel_writeDupFd(request, fd);
        if (status != 0) return status;
    }
    _ = AParcel_writeInt32(request, @intCast(arguments.len));
    for (arguments) |argument| _ = AParcel_writeString(request, argument.ptr, @intCast(argument.len));
    _ = AParcel_writeStrongBinder(request, null); // IShellCallback
    _ = AParcel_writeStrongBinder(request, null); // IResultReceiver
    return azk_binder_transact(stats, SHELL_COMMAND_TRANSACTION, request, reply, 0);
}

pub fn main(init: std.process.Init) !void {
    const a = init.arena.allocator();
    var arguments: []const [:0]const u8 = (try init.minimal.args.toSlice(a))[1..];
    var dir: ?[]const u8 = null;
    if (arguments.len >= 2 and std.mem.eql(u8, arguments[0], "--join")) {
        dir = arguments[1];
        arguments = arguments[2..];
    }
    if (arguments.len == 0 or std.mem.eql(u8, arguments[0], "--help") or std.mem.eql(u8, arguments[0], "-h")) {
        try std.Io.File.stdout().writeStreamingAll(init.io, usage);
        return;
    }
    join(init, dir orelse soleSession(init)) catch |err| fail("cannot join the session: {s}", .{@errorName(err)});
    const stats = AServiceManager_checkService("stats") orelse fail("statsd is not running", .{});
    const status = if (std.mem.eql(u8, arguments[0], "dump")) dump: {
        const given = arguments[1..];
        const dumped: []const [:0]const u8 = if (given.len == 0) &.{"--metadata"} else given;
        const pointers = try a.alloc([*:0]const u8, dumped.len);
        for (dumped, pointers) |argument, *pointer| pointer.* = argument.ptr;
        break :dump AIBinder_dump(stats, 1, pointers.ptr, @intCast(pointers.len));
    } else shellCommand(stats, arguments);
    if (status != 0) fail("statsd reported status {d}", .{status});
}
