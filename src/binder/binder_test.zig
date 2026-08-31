//! Multi-process hosted Binder test driver.
//!
//! Usage: azk-binder-test <androlinzberg> <azk-binder-broker>
//!
//! Starts one broker, then three ART peers that share it: a server that
//! publishes a generated AIDL stub, a helper that registers a callback with
//! the server and waits for the server's death, and a client that exercises
//! calls, callbacks, forwarding, oneway order, descriptors, identity, and
//! death. Every peer must exit 0.
const std = @import("std");
const Io = std.Io;

const timeout_seconds = 300;

/// Peer pids for the watchdog to kill on timeout.
var peer_pids: [3]std.atomic.Value(i32) = .{ .init(0), .init(0), .init(0) };

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len < 3) {
        std.debug.print("usage: azk-binder-test <androlinzberg> <azk-binder-broker>\n", .{});
        return error.InvalidArguments;
    }
    const launcher = args[1];
    const broker_exe = args[2];

    var random: [8]u8 = undefined;
    io.random(&random);
    const dir = try std.fmt.allocPrint(arena, "/tmp/azk-binder-test-{x}", .{&random});
    try Io.Dir.createDirAbsolute(io, dir, @fromBackingInt(@intCast(0o700)));
    defer Io.Dir.cwd().deleteTree(io, dir) catch {};
    const socket_path = try std.fs.path.join(arena, &.{ dir, "binder.sock" });
    const prop_area_path = try std.fs.path.join(arena, &.{ dir, "props" });
    const cookie = "binder-test";

    var env = try init.minimal.environ.createMap(gpa);
    defer env.deinit();
    try env.put("AZK_BINDER_SOCKET", socket_path);
    try env.put("AZK_BINDER_COOKIE", cookie);
    try env.put("AZK_BINDER_SYSTEM_COOKIE", "binder-test-system");
    try env.put("AZK_BINDER_PACKAGE", "dev.artzig.binder");
    try env.put("AZK_PROP_AREA", prop_area_path);
    // Hermetic: a nonexistent display keeps the hosted clipboard monitor off
    // the developer's real Wayland clipboard (an empty value would fall back
    // to wayland-0; parallel test sessions would cross-talk through it).
    try env.put("WAYLAND_DISPLAY", "azk-hermetic-none");

    var broker = try std.process.spawn(io, .{
        .argv = &.{ broker_exe, "--socket", socket_path, "--prop-area", prop_area_path },
        .environ_map = &env,
        .stdin = .pipe,
        .stdout = .pipe,
        .stderr = .inherit,
    });
    var broker_checked = false;
    defer if (!broker_checked) {
        if (broker.stdin) |stdin| stdin.close(io);
        broker.stdin = null;
        _ = broker.wait(io) catch {};
    };
    var line: [16]u8 = undefined;
    const n = try broker.stdout.?.readStreaming(io, &.{&line});
    if (!std.mem.startsWith(u8, line[0..n], "ready")) return error.BrokerNotReady;

    var watchdog: Io.Group = .init;
    var expired = std.atomic.Value(bool).init(false);
    try watchdog.concurrent(io, watch, .{ io, &expired });
    defer watchdog.cancel(io);

    var server = try spawnPeer(io, gpa, &env, launcher, "dev.artzig.binder.BinderTestServer");
    peer_pids[0].store(server.id.?, .release);
    var helper = try spawnPeer(io, gpa, &env, launcher, "dev.artzig.binder.BinderTestHelper");
    peer_pids[1].store(helper.id.?, .release);
    var client = try spawnPeer(io, gpa, &env, launcher, "dev.artzig.binder.BinderTestClient");
    peer_pids[2].store(client.id.?, .release);

    var failed = false;
    failed = !expectExit(io, &client, "client") or failed;
    failed = !expectExit(io, &server, "server") or failed;
    failed = !expectExit(io, &helper, "helper") or failed;
    if (expired.load(.acquire)) {
        std.debug.print("binder-test: timed out after {d}s\n", .{timeout_seconds});
        failed = true;
    }
    // The broker must survive the whole session and exit cleanly on EOF.
    broker.stdin.?.close(io);
    broker.stdin = null;
    broker_checked = true;
    failed = !expectExit(io, &broker, "broker") or failed;
    if (failed) return error.BinderTestFailed;
    std.debug.print("binder-test: all peers exited cleanly\n", .{});
}

fn spawnPeer(
    io: Io,
    gpa: std.mem.Allocator,
    env: *std.process.Environ.Map,
    launcher: []const u8,
    class_name: []const u8,
) !std.process.Child {
    try env.put("AZK_TEST_CLASS", class_name);
    _ = gpa;
    return std.process.spawn(io, .{
        .argv = &.{ launcher, "--javatest" },
        .environ_map = env,
        .stdin = .ignore,
        .stdout = .inherit,
        .stderr = .inherit,
    });
}

fn expectExit(io: Io, child: *std.process.Child, name: []const u8) bool {
    const term = child.wait(io) catch |err| {
        std.debug.print("binder-test: {s}: wait failed: {t}\n", .{ name, err });
        return false;
    };
    switch (term) {
        .exited => |code| {
            if (code == 0) return true;
            std.debug.print("binder-test: {s} exited with {d}\n", .{ name, code });
            return false;
        },
        else => {
            std.debug.print("binder-test: {s} terminated abnormally: {any}\n", .{ name, term });
            return false;
        },
    }
}

fn watch(io: Io, expired: *std.atomic.Value(bool)) Io.Cancelable!void {
    try Io.sleep(io, .{ .nanoseconds = timeout_seconds * std.time.ns_per_s }, .awake);
    expired.store(true, .release);
    // A hung peer must not wedge the build.
    for (&peer_pids) |*pid| {
        const id = pid.load(.acquire);
        if (id != 0) _ = std.os.linux.kill(id, .KILL);
    }
}
