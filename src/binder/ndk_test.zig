//! Native binder self-test: links `libbinder_ndk` (the NDK C ABI) and drives
//! it against a real `azk-binder-broker`, exercising the whole peer path in
//! one process — register a service, look it up (a local handle: the broker
//! short-circuits ownership), transact to it, and verify the reply the
//! `onTransact` callback wrote. This validates connect/hello, the service
//! registry round-trip, parcel marshalling, and in-process dispatch. (The
//! remote transact path is covered by the cross-language system-test.)
//!
//! Usage: azk-ndk-test <azk-binder-broker>
const std = @import("std");
const Io = std.Io;

// The NDK C ABI (subset under test), resolved from the linked libbinder_ndk.
const AIBinder = opaque {};
const AIBinder_Class = opaque {};
const AParcel = opaque {};
const AStatus = opaque {};
extern fn AIBinder_Class_define(
    desc: [*:0]const u8,
    onCreate: ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque,
    onDestroy: ?*const fn (?*anyopaque) callconv(.c) void,
    onTransact: ?*const fn (*AIBinder, u32, *const AParcel, *AParcel) callconv(.c) i32,
) callconv(.c) *AIBinder_Class;
extern fn AIBinder_new(clazz: *const AIBinder_Class, args: ?*anyopaque) callconv(.c) *AIBinder;
extern fn AIBinder_prepareTransaction(binder: *AIBinder, in: *?*AParcel) callconv(.c) i32;
extern fn AIBinder_transact(binder: *AIBinder, code: u32, in: *?*AParcel, out: *?*AParcel, flags: u32) callconv(.c) i32;
extern fn AIBinder_decStrong(binder: *AIBinder) callconv(.c) void;
extern fn AIBinder_isRemote(binder: *const AIBinder) callconv(.c) bool;
extern fn AServiceManager_addService(binder: *AIBinder, instance: [*:0]const u8) callconv(.c) i32;
extern fn AServiceManager_waitForService(instance: [*:0]const u8) callconv(.c) ?*AIBinder;
extern fn AParcel_writeInt32(parcel: *AParcel, value: i32) callconv(.c) i32;
extern fn AParcel_readInt32(parcel: *const AParcel, value: *i32) callconv(.c) i32;
extern fn AParcel_writeString(parcel: *AParcel, string: ?[*]const u8, length: i32) callconv(.c) i32;
extern fn AParcel_readString(parcel: *const AParcel, data: ?*anyopaque, allocator: *const fn (?*anyopaque, i32, ?*?[*]u8) callconv(.c) bool) callconv(.c) i32;
extern fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;

const TRANSACT_ADD: u32 = 1;
const TRANSACT_ECHO: u32 = 2;

/// The service under test: `add(a, b) -> a+b` and `echo(str) -> str`.
fn onTransact(binder: *AIBinder, code: u32, in: *const AParcel, out: *AParcel) callconv(.c) i32 {
    _ = binder;
    switch (code) {
        TRANSACT_ADD => {
            var a: i32 = 0;
            var b: i32 = 0;
            _ = AParcel_readInt32(in, &a);
            _ = AParcel_readInt32(in, &b);
            _ = AParcel_writeInt32(out, a + b);
            return 0;
        },
        TRANSACT_ECHO => {
            var buf: [256]u8 = undefined;
            echo_buf = &buf;
            echo_len = 0;
            _ = AParcel_readString(in, null, echoAlloc);
            _ = AParcel_writeString(out, &buf, @intCast(echo_len));
            return 0;
        },
        else => return -1,
    }
}

var echo_buf: ?[*]u8 = null;
var echo_len: usize = 0;
fn echoAlloc(_: ?*anyopaque, length: i32, buffer: ?*?[*]u8) callconv(.c) bool {
    if (length < 0) return true;
    // NDK length includes the NUL terminator; the content is length - 1.
    echo_len = @intCast(@max(length - 1, 0));
    if (buffer) |b| b.* = echo_buf;
    return true;
}

var read_buf: [256]u8 = undefined;
var read_len: usize = 0;
fn readAlloc(_: ?*anyopaque, length: i32, buffer: ?*?[*]u8) callconv(.c) bool {
    if (length < 0) return true;
    read_len = @intCast(@max(length - 1, 0));
    if (buffer) |b| b.* = &read_buf;
    return true;
}

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len < 2) {
        std.debug.print("usage: azk-ndk-test <azk-binder-broker>\n", .{});
        return error.InvalidArguments;
    }
    const broker_exe = args[1];

    var random: [8]u8 = undefined;
    io.random(&random);
    const dir = try std.fmt.allocPrint(arena, "/tmp/azk-ndk-test-{x}", .{&random});
    try Io.Dir.createDirAbsolute(io, dir, @fromBackingInt(@intCast(0o700)));
    defer Io.Dir.cwd().deleteTree(io, dir) catch {};
    const socket_path = try std.fmt.allocPrintSentinel(arena, "{s}/binder.sock", .{dir}, 0);
    const prop_area = try std.fmt.allocPrintSentinel(arena, "{s}/props", .{dir}, 0);

    var env = try init.minimal.environ.createMap(init.gpa);
    defer env.deinit();
    try env.put("AZK_BINDER_SOCKET", socket_path);
    try env.put("AZK_BINDER_COOKIE", "ndk-test");
    try env.put("AZK_BINDER_SYSTEM_COOKIE", "ndk-test-system");
    try env.put("AZK_BINDER_PACKAGE", "dev.artzig.ndk");
    try env.put("AZK_PROP_AREA", prop_area);

    var broker = try std.process.spawn(io, .{
        .argv = &.{ broker_exe, "--socket", socket_path, "--prop-area", prop_area },
        .environ_map = &env,
        .stdin = .pipe,
        .stdout = .pipe,
        .stderr = .inherit,
    });
    defer {
        if (broker.stdin) |stdin| stdin.close(io);
        broker.stdin = null;
        _ = broker.wait(io) catch {};
    }
    // Await the broker's "ready" line.
    var line: [16]u8 = undefined;
    var got: usize = 0;
    while (got < line.len) {
        const n = try broker.stdout.?.readStreaming(io, &.{line[got..]});
        if (n == 0) break;
        got += n;
        if (std.mem.indexOfScalar(u8, line[0..got], '\n') != null) break;
    }
    if (!std.mem.startsWith(u8, line[0..got], "ready")) return error.BrokerNotReady;

    // The library reads these with getenv on first use; set them in this process.
    _ = setenv("AZK_BINDER_SOCKET", socket_path, 1);
    _ = setenv("AZK_BINDER_COOKIE", "ndk-test", 1);
    _ = setenv("AZK_BINDER_PACKAGE", "dev.artzig.ndk", 1);

    var failures: u32 = 0;
    const check = struct {
        fn f(cond: bool, name: []const u8, fails: *u32) void {
            if (cond) {
                std.debug.print("azk-ndk-test: ok: {s}\n", .{name});
            } else {
                fails.* += 1;
                std.debug.print("azk-ndk-test: FAIL: {s}\n", .{name});
            }
        }
    }.f;

    const class = AIBinder_Class_define("dev.artzig.ndk.ITest", null, null, onTransact);
    const service = AIBinder_new(class, null);
    const add_rc = AServiceManager_addService(service, "dev.artzig.ndk.test");
    check(add_rc == 0, "addService accepted", &failures);
    if (add_rc != 0) {
        // Registration was denied; waitForService(wait=true) would block forever.
        std.debug.print("azk-ndk-test: addService failed ({d}); aborting\n", .{add_rc});
        return error.NdkTestFailed;
    }

    const remote = AServiceManager_waitForService("dev.artzig.ndk.test") orelse {
        std.debug.print("azk-ndk-test: FAIL: waitForService returned null\n", .{});
        return error.NdkTestFailed;
    };
    // The peer registered this service itself, so the broker returns a local
    // ref (an in-process handle), not a remote proxy.
    check(!AIBinder_isRemote(remote), "self-registered service resolves to a local handle", &failures);

    // add(20, 22) -> 42 through in-process local dispatch.
    {
        var in: ?*AParcel = null;
        _ = AIBinder_prepareTransaction(remote, &in);
        _ = AParcel_writeInt32(in.?, 20);
        _ = AParcel_writeInt32(in.?, 22);
        var out: ?*AParcel = null;
        const rc = AIBinder_transact(remote, TRANSACT_ADD, &in, &out, 0);
        check(rc == 0, "transact add succeeded", &failures);
        var result: i32 = 0;
        _ = AParcel_readInt32(out.?, &result);
        check(result == 42, "add returned 42 via local dispatch", &failures);
    }
    // echo("héllo") round-trips a UTF-8/UTF-16 string.
    {
        var in: ?*AParcel = null;
        _ = AIBinder_prepareTransaction(remote, &in);
        const s = "héllo";
        _ = AParcel_writeString(in.?, s, @intCast(s.len));
        var out: ?*AParcel = null;
        const rc = AIBinder_transact(remote, TRANSACT_ECHO, &in, &out, 0);
        check(rc == 0, "transact echo succeeded", &failures);
        read_len = 0;
        _ = AParcel_readString(out.?, null, readAlloc);
        check(std.mem.eql(u8, read_buf[0..read_len], s), "string round trip via local dispatch", &failures);
    }

    AIBinder_decStrong(remote);
    AIBinder_decStrong(service);

    if (failures != 0) {
        std.debug.print("azk-ndk-test: {d} failures\n", .{failures});
        return error.NdkTestFailed;
    }
    std.debug.print("azk-ndk-test: all checks passed\n", .{});
}
