//! Multi-process hosted system-service test driver.
//!
//! Usage: azk-system-test <androlinzberg> <azk-binder-broker> <fixture-apk-dir>
//!
//! Starts one broker and one system process, then two ART app peers: package
//! `dev.artzig.test.b` hosting a real `android.app.Service` through the
//! session's activity manager, and package `dev.artzig.test.a` binding it
//! across processes. Every child must exit 0.
const std = @import("std");
const Io = std.Io;

const timeout_seconds = 300;

var peer_pids: [3]std.atomic.Value(i32) = .{ .init(0), .init(0), .init(0) };

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len < 5) {
        std.debug.print("usage: azk-system-test <androlinzberg> <azk-binder-broker> <fixture-apk-dir> <ndk-system-client>\n", .{});
        return error.InvalidArguments;
    }
    const launcher = args[1];
    const broker_exe = args[2];
    const fixture_apks = args[3];
    const ndk_client = args[4];

    var random: [8]u8 = undefined;
    io.random(&random);
    const dir = try std.fmt.allocPrint(arena, "/tmp/azk-system-test-{x}", .{&random});
    try Io.Dir.createDirAbsolute(io, dir, @fromBackingInt(@intCast(0o700)));
    defer Io.Dir.cwd().deleteTree(io, dir) catch {};
    const socket_path = try std.fs.path.join(arena, &.{ dir, "binder.sock" });
    const prop_area_path = try std.fs.path.join(arena, &.{ dir, "props" });

    var env = try init.minimal.environ.createMap(gpa);
    defer env.deinit();
    try env.put("AZK_BINDER_SOCKET", socket_path);
    try env.put("AZK_BINDER_COOKIE", "system-test");
    try env.put("AZK_BINDER_SYSTEM_COOKIE", "system-test-system");
    try env.put("AZK_PROP_AREA", prop_area_path);
    // The peers install their packages from build-time fixture APKs.
    try env.put("AZK_FIXTURE_APKS", fixture_apks);
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
    try awaitReady(io, &broker, "broker");

    var system = try std.process.spawn(io, .{
        .argv = &.{ launcher, "--system-server" },
        .environ_map = &env,
        .stdin = .pipe,
        .stdout = .pipe,
        .stderr = .inherit,
    });
    var system_checked = false;
    defer if (!system_checked) {
        if (system.stdin) |stdin| stdin.close(io);
        system.stdin = null;
        _ = system.wait(io) catch {};
    };
    try awaitReady(io, &system, "system process");

    var watchdog: Io.Group = .init;
    var expired = std.atomic.Value(bool).init(false);
    try watchdog.concurrent(io, watch, .{ io, &expired });
    defer watchdog.cancel(io);

    var host = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.SystemTestHostB", "dev.artzig.test.b");
    peer_pids[0].store(host.id.?, .release);
    var client = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.SystemTestClientA", "dev.artzig.test.a");
    peer_pids[1].store(client.id.?, .release);

    // Cross-language native peer: a standalone libbinder_ndk process (its own
    // package/uid) that connects to this broker via the inherited env and calls
    // the Java service host B registers. Not a launcher --javatest peer.
    try env.put("AZK_BINDER_PACKAGE", "dev.artzig.ndkclient");
    var ndkc = try std.process.spawn(io, .{
        .argv = &.{ndk_client},
        .environ_map = &env,
        .stdin = .ignore,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    peer_pids[2].store(ndkc.id.?, .release);

    var failed = false;
    failed = !expectExit(io, &client, "client") or failed;
    failed = !expectExit(io, &ndkc, "ndk system client") or failed;
    failed = !expectExit(io, &host, "service host") or failed;
    if (expired.load(.acquire)) {
        std.debug.print("system-test: timed out after {d}s\n", .{timeout_seconds});
        failed = true;
    }
    system.stdin.?.close(io);
    system.stdin = null;
    system_checked = true;
    failed = !expectExit(io, &system, "system process") or failed;
    broker.stdin.?.close(io);
    broker.stdin = null;
    broker_checked = true;
    failed = !expectExit(io, &broker, "broker") or failed;
    if (failed) return error.SystemTestFailed;
    std.debug.print("system-test: all processes exited cleanly\n", .{});
}

fn awaitReady(io: Io, child: *std.process.Child, name: []const u8) !void {
    var line: [64]u8 = undefined;
    var got: usize = 0;
    while (got < line.len) {
        const n = try child.stdout.?.readStreaming(io, &.{line[got..]});
        if (n == 0) break;
        got += n;
        if (std.mem.indexOfScalar(u8, line[0..got], '\n') != null) break;
    }
    if (!std.mem.startsWith(u8, line[0..got], "ready")) {
        std.debug.print("system-test: {s} not ready\n", .{name});
        return error.NotReady;
    }
}

fn spawnPeer(
    io: Io,
    env: *std.process.Environ.Map,
    launcher: []const u8,
    class_name: []const u8,
    package: []const u8,
) !std.process.Child {
    try env.put("AZK_TEST_CLASS", class_name);
    try env.put("AZK_BINDER_PACKAGE", package);
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
        std.debug.print("system-test: {s}: wait failed: {t}\n", .{ name, err });
        return false;
    };
    switch (term) {
        .exited => |code| {
            if (code == 0) return true;
            std.debug.print("system-test: {s} exited with {d}\n", .{ name, code });
            return false;
        },
        else => {
            std.debug.print("system-test: {s} terminated abnormally: {any}\n", .{ name, term });
            return false;
        },
    }
}

fn watch(io: Io, expired: *std.atomic.Value(bool)) Io.Cancelable!void {
    try Io.sleep(io, .{ .nanoseconds = timeout_seconds * std.time.ns_per_s }, .awake);
    expired.store(true, .release);
    for (&peer_pids) |*pid| {
        const id = pid.load(.acquire);
        if (id != 0) _ = std.os.linux.kill(id, .KILL);
    }
}
