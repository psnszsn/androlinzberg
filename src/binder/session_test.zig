//! Process-level supervision contracts, with finite service fixtures and std.Io ownership.
const std = @import("std");
const Io = std.Io;
const Session = @import("session.zig").Session;

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len >= 3 and std.mem.eql(u8, args[1], "--service-exec"))
        return std.process.replace(init.io, .{ .argv = args[2..] });
    if (args.len < 2 or !std.mem.eql(u8, args[1], "--test")) return fixture(init, args);
    const io = init.io;
    const arena = init.arena.allocator();
    const exe = try std.process.executablePathAlloc(io, arena);
    var random: [8]u8 = undefined;
    io.random(&random);
    const dir = try std.fmt.allocPrint(arena, "/tmp/azk-session-supervision-{x}", .{&random});
    try Io.Dir.createDirAbsolute(io, dir, .default_dir);
    defer Io.Dir.cwd().deleteTree(io, dir) catch {};
    const log = try std.fs.path.join(arena, &.{ dir, "events" });
    const invalid_exe = try std.fs.path.join(arena, &.{ dir, "invalid-executable" });
    const invalid = try Io.Dir.createFileAbsolute(io, invalid_exe, .{
        .permissions = @fromBackingInt(@intCast(0o700)),
    });
    try invalid.writeStreamingAll(io, "not an executable\n");
    invalid.close(io);
    var map = try init.minimal.environ.createMap(arena);
    try map.put("XDG_RUNTIME_DIR", dir);
    try map.put("AZK_SESSION_FIXTURE_LOG", log);
    const cases = [_]struct { mode: []const u8, failure: ?anyerror, expected: []const u8 }{
        .{ .mode = "normal", .failure = null, .expected = "broker start\nsf start\nsystem start\nsystem stop\nsf stop\nbroker stop\n" },
        .{ .mode = "sf-spawn", .failure = error.SurfaceFlingerNotReady, .expected = "broker start\nbroker stop\n" },
        .{ .mode = "sf-eof", .failure = error.SurfaceFlingerNotReady, .expected = "broker start\nsf start\nbroker stop\n" },
        .{ .mode = "sf-bad-ready", .failure = error.SurfaceFlingerNotReady, .expected = "broker start\nsf start\nbroker stop\n" },
        .{ .mode = "sf-timeout", .failure = error.ServiceReadyTimeout, .expected = "broker start\nsf start\nbroker stop\n" },
        .{ .mode = "system-spawn", .failure = error.SystemProcessNotReady, .expected = "broker start\nsf start\nsf stop\nbroker stop\n" },
        .{ .mode = "system-exec-format", .failure = error.SystemProcessNotReady, .expected = "broker start\nsf start\nsf stop\nbroker stop\n" },
        .{ .mode = "system-eof", .failure = error.SystemProcessNotReady, .expected = "broker start\nsf start\nsystem start\nsf stop\nbroker stop\n" },
        .{ .mode = "sf-shutdown-timeout", .failure = null, .expected = "broker start\nsf start\nsystem start\nsystem stop\nsf stop\nbroker stop\n" },
    };
    for (cases) |case| {
        const file = try Io.Dir.createFileAbsolute(io, log, .{});
        file.close(io);
        try map.put("AZK_SESSION_FIXTURE_MODE", case.mode);
        const env: std.process.Environ = .{ .block = try map.createPosixBlock(arena, .{}) };
        var debug: std.heap.DebugAllocator(.{}) = .init;
        const gpa = debug.allocator();
        const before = try descriptorCount(io);
        var failure: ?anyerror = null;
        if (Session.start(gpa, io, .{
            .broker_exe = exe,
            .launcher_exe = if (std.mem.eql(u8, case.mode, "system-spawn"))
                "/azk-no-such-system-executable"
            else if (std.mem.eql(u8, case.mode, "system-exec-format")) invalid_exe else exe,
            .surfaceflinger_exe = if (std.mem.eql(u8, case.mode, "sf-spawn")) "/azk-no-such-compositor-executable" else exe,
            .environ = env,
            .ready_timeout = .{ .nanoseconds = 500 * std.time.ns_per_ms },
            .shutdown_timeout = .{ .nanoseconds = 100 * std.time.ns_per_ms },
        })) |value| {
            var session = value;
            defer session.stop();
            const joined = try @import("session.zig").SessionInfo.read(arena, io, session.dir);
            try std.testing.expectEqualStrings(session.socket_path, joined.socket);
            try std.testing.expectEqualStrings(session.prop_area_path, joined.prop_area);
            try std.testing.expectEqualStrings(session.cookie, joined.cookie);
            if (std.mem.eql(u8, case.mode, "normal"))
                try std.testing.expectEqualStrings(exe, joined.launcher);
            session.awaitSystemReady() catch |err| {
                failure = err;
            };
            if (failure == null) try session.awaitSystemReady();
        } else |err| failure = err;
        try std.testing.expectEqual(case.failure, failure);
        try std.testing.expectEqual(std.heap.Check.ok, debug.deinit());
        const after = try descriptorCount(io);
        if (before != after) {
            std.debug.print("descriptor mismatch in {s}:\n", .{case.mode});
            var fds = try Io.Dir.cwd().openDir(io, "/proc/self/fd", .{ .iterate = true });
            defer fds.close(io);
            var it = fds.iterate();
            while (try it.next(io)) |entry| {
                var target: [256]u8 = undefined;
                const n = try fds.readLink(io, entry.name, &target);
                std.debug.print("{s} -> {s}\n", .{ entry.name, target[0..n] });
            }
        }
        try std.testing.expectEqual(before, after);
        const children = try Io.Dir.cwd().readFileAlloc(io, "/proc/thread-self/children", arena, .limited(4096));
        try std.testing.expectEqualStrings("", std.mem.trim(u8, children, " \n"));
        const actual = try Io.Dir.cwd().readFileAlloc(io, log, arena, .limited(4096));
        try std.testing.expectEqualStrings(case.expected, actual);
        var runtime = try Io.Dir.cwd().openDir(io, try std.fs.path.join(arena, &.{ dir, "androlinzberg" }), .{ .iterate = true });
        defer runtime.close(io);
        var iterator = runtime.iterate();
        try std.testing.expectEqual(@as(?Io.Dir.Entry, null), try iterator.next(io));
    }
    std.debug.print("Session: startup ordering, exact readiness, timeouts, spawn failure, cleanup and descriptor ownership passed\n", .{});
}

fn descriptorCount(io: Io) !usize {
    var dir = try Io.Dir.cwd().openDir(io, "/proc/self/fd", .{ .iterate = true });
    defer dir.close(io);
    var iterator = dir.iterate();
    var count: usize = 0;
    while (try iterator.next(io)) |_| count += 1;
    return count;
}

fn event(init: std.process.Init, role: []const u8, state: []const u8) !void {
    const path = init.minimal.environ.getPosix("AZK_SESSION_FIXTURE_LOG").?;
    const file = try Io.Dir.cwd().createFile(init.io, path, .{ .truncate = false });
    defer file.close(init.io);
    const line = try std.fmt.allocPrint(init.arena.allocator(), "{s} {s}\n", .{ role, state });
    try file.writePositionalAll(init.io, line, (try file.stat(init.io)).size);
}

fn fixture(init: std.process.Init, args: []const [:0]const u8) !void {
    const role: []const u8 = if (args.len < 2) "sf" else if (std.mem.eql(u8, args[1], "--socket")) "broker" else "system";
    const mode = init.minimal.environ.getPosix("AZK_SESSION_FIXTURE_MODE").?;
    try std.testing.expect(init.minimal.environ.getPosix("AZK_BINDER_SYSTEM_COOKIE") != null);
    try event(init, role, "start");
    if (std.mem.eql(u8, role, "sf")) {
        if (std.mem.eql(u8, mode, "sf-eof")) return;
        if (std.mem.eql(u8, mode, "sf-bad-ready")) {
            try Io.File.stdout().writeStreamingAll(init.io, "ready-wrong\n");
            return;
        }
        if (std.mem.eql(u8, mode, "sf-timeout")) {
            try Io.sleep(init.io, .{ .nanoseconds = 10 * std.time.ns_per_s }, .awake);
            return;
        }
    }
    if (std.mem.eql(u8, role, "system") and std.mem.eql(u8, mode, "system-eof")) return;
    try Io.File.stdout().writeStreamingAll(init.io, "ready\n");
    var bytes: [32]u8 = undefined;
    while (true) {
        const count = Io.File.stdin().readStreaming(init.io, &.{&bytes}) catch |err| switch (err) {
            error.EndOfStream => break,
            else => return err,
        };
        if (count == 0) break;
    }
    try event(init, role, "stop");
    if (std.mem.eql(u8, role, "sf") and std.mem.eql(u8, mode, "sf-shutdown-timeout"))
        try Io.sleep(init.io, .{ .nanoseconds = 10 * std.time.ns_per_s }, .awake);
}
