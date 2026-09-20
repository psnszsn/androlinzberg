//! Self-test of the Zig AIDL backend (`aidl --lang=zig`, src/binder/aidl.zig).
//!
//! tests/aidl-zig declares one interface that uses every marshalling category
//! the backend supports. The same AIDL is also compiled by the pinned NDK
//! backend into tests/aidl-zig/ProbeNdk.cpp, which supplies the wire oracle:
//!
//!   Zig proxy  -> Zig server   (the backend agrees with itself)
//!   Zig proxy  -> NDK server   (what Zig writes, stock generated C++ reads)
//!   NDK proxy  -> Zig server   (what stock generated C++ writes, Zig reads)
//!
//! The servers live in a child process behind a real `azk-binder-broker`, so
//! every call, callback, Binder and descriptor crosses AZKB. One process cannot
//! hold both: libbinder_ndk permits one class per interface descriptor.
//!
//! Usage: azk-aidl-zig-test <azk-binder-broker>
const std = @import("std");
const Io = std.Io;
const aidl = @import("aidl");
const probe = @import("probe");

const IProbe = probe.@"dev.artzig.probe.IProbe";
const IProbeCallback = probe.@"dev.artzig.probe.IProbeCallback";
const Shape = probe.@"dev.artzig.probe.Shape";
const Point = probe.@"dev.artzig.probe.Point";
const Choice = probe.@"dev.artzig.probe.Choice";
const Kind = probe.@"dev.artzig.probe.Kind";

extern fn azk_probe_ndk_new() callconv(.c) *aidl.AIBinder;
/// Drives `binder` from a stock NDK proxy; returns the line of the first
/// failed expectation in ProbeNdk.cpp, or 0.
extern fn azk_probe_ndk_drive(binder: *aidl.AIBinder) callconv(.c) c_int;

/// The reference behaviour; ProbeNdk.cpp implements the same contract.
const Probe = struct {
    io: Io,

    pub fn add(_: *Probe, _: *aidl.Call, a: i32, b: i32) !i32 {
        return a +% b;
    }
    pub fn echo(_: *Probe, _: *aidl.Call, text: []const u8) ![]const u8 {
        return text;
    }
    pub fn maybe(_: *Probe, _: *aidl.Call, text: ?[]const u8) !?[]const u8 {
        return text;
    }
    pub fn transform(_: *Probe, call: *aidl.Call, shape: Shape, cursor: Point) !IProbe.method.transform.Reply {
        var mirrored = shape;
        mirrored.name = try std.fmt.allocPrint(call.arena, "mirror:{s}", .{shape.name});
        return .{
            ._result = shape,
            .mirrored = mirrored,
            .cursor = .{ .x = cursor.x + 1, .y = cursor.y + shape.rgb[0] },
        };
    }
    pub fn choose(_: *Probe, _: *aidl.Call, choice: Choice) !Choice {
        return switch (choice) {
            .number => |number| .{ .number = number + 1 },
            .text => |text| .{ .point = .{ .x = @intCast(text.len), .y = 7 } },
            .point => .{ .kinds = &.{ .CIRCLE, .SQUARE } },
            .kinds => |kinds| .{ .number = @intCast(kinds.len) },
        };
    }
    pub fn scale(_: *Probe, call: *aidl.Call, values: []const i64, factor: i64, _: i32) !IProbe.method.scale.Reply {
        const scaled = try call.arena.alloc(i64, values.len);
        const doubled = try call.arena.alloc(i64, values.len);
        for (values, scaled, doubled) |value, *s, *d| {
            s.* = value * factor;
            d.* = value * 2;
        }
        return .{ ._result = scaled, .doubled = doubled };
    }
    pub fn relay(_: *Probe, _: *aidl.Call, callback: IProbeCallback.Client, question: i32) !i32 {
        const answer = try callback.ask(question);
        try callback.onEvent(answer, "relayed");
        return answer;
    }
    pub fn fail(_: *Probe, _: *aidl.Call, mode: i32) !void {
        switch (mode) {
            1 => {
                aidl.setExceptionMessage("mode one");
                return error.IllegalArgument;
            },
            2 => {
                aidl.setServiceSpecific(77, "mode two");
                return error.ServiceSpecific;
            },
            3 => return error.SecurityException,
            else => {},
        }
    }
    pub fn reopen(_: *Probe, _: *aidl.Call, descriptor: aidl.File) !aidl.File {
        // The reply duplicates it before the argument is released.
        return descriptor;
    }
    pub fn same(_: *Probe, _: *aidl.Call, binder: ?aidl.Binder) !?aidl.Binder {
        return binder;
    }
    pub fn next(_: *Probe, _: *aidl.Call, kind: Kind) !Kind {
        return switch (kind) {
            .NONE => .CIRCLE,
            .CIRCLE => .SQUARE,
            .SQUARE => .NONE,
            else => kind,
        };
    }
    pub fn names(_: *Probe, _: *aidl.Call, values: ?[]const ?[]const u8) !?[]const ?[]const u8 {
        return values;
    }
};

const Listener = struct {
    asked: i32 = 0,
    /// Published last: a oneway call lands on a Binder thread after relay() returns.
    event: std.atomic.Value(i32) = .init(0),
    event_text: [16]u8 = undefined,
    event_len: usize = 0,

    pub fn ask(self: *Listener, _: *aidl.Call, question: i32) !i32 {
        self.asked = question;
        return question * 2;
    }
    pub fn onEvent(self: *Listener, _: *aidl.Call, id: i32, text: []const u8) !void {
        self.event_len = @min(text.len, self.event_text.len);
        @memcpy(self.event_text[0..self.event_len], text[0..self.event_len]);
        self.event.store(id, .release);
    }
};

fn expect(ok: bool, comptime what: []const u8, peer: []const u8, failures: *u32) void {
    if (ok) return;
    failures.* += 1;
    std.debug.print("azk-aidl-zig-test: FAIL ({s}): {s}\n", .{ peer, what });
}

/// Every expectation here has a counterpart in ProbeNdk.cpp's drive().
fn drive(io: Io, gpa: std.mem.Allocator, client: IProbe.Client, peer: []const u8, failures: *u32) !void {
    var arena_state: std.heap.ArenaAllocator = .init(gpa);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    expect(try client.getInterfaceVersion() == IProbe.version and IProbe.version == 3, "interface version", peer, failures);
    expect(std.mem.eql(u8, try client.getInterfaceHash(arena), IProbe.hash), "interface hash", peer, failures);
    expect(try client.add(40, 2) == IProbe.MAGIC, "int arguments and result", peer, failures);
    expect(std.mem.eql(u8, try client.echo(arena, "grüße, 世界"), "grüße, 世界"), "UTF-8 <-> UTF-16 string", peer, failures);
    expect(std.mem.eql(u8, try client.echo(arena, ""), ""), "empty string is not null", peer, failures);
    expect(try client.maybe(arena, null) == null, "null string", peer, failures);
    expect(std.mem.eql(u8, (try client.maybe(arena, "x")).?, "x"), "present nullable string", peer, failures);

    const child: Shape = .{ .name = "child", .kind = .CIRCLE };
    const shape: Shape = .{
        .label = "label",
        .origin = .{ .x = 3 },
        .vertices = &.{ .{ .x = 1, .y = 2 }, .{ .x = 3, .y = 4 } },
        .weights = &.{ 1, -2, std.math.maxInt(i64) },
        .blob = &.{ 0, 1, 0xff },
        .tag = .{ 9, 8, 7, 6 },
        .glyph = 0x4e16,
        .anchor = .{ .x = -1, .y = -2 },
        .tags = &.{ "a", "bc" },
        .child = &child,
        .pair = .{ .first = "p", .second = .{ .x = 5 } },
        .span = .{ 4, 5 },
        .mark = .{ 6, 7 },
    };
    const transformed = try client.transform(arena, shape, .{ .x = 10, .y = 20 });
    const result = transformed._result;
    expect(result.kind == .SQUARE and result.scale == 1.5 and result.visible and result.glyph == 0x4e16, "parcelable scalar fields and defaults", peer, failures);
    expect(std.mem.eql(u8, result.name, "shape") and std.mem.eql(u8, result.label.?, "label"), "parcelable strings", peer, failures);
    expect(result.origin.x == 3 and result.origin.y == 7, "nested parcelable with a declared default", peer, failures);
    expect(result.vertices.len == 2 and result.vertices[1].x == 3 and result.vertices[1].y == 4, "parcelable array", peer, failures);
    expect(std.mem.eql(i64, result.weights, shape.weights), "long array", peer, failures);
    expect(std.mem.eql(u8, result.blob, shape.blob), "byte array", peer, failures);
    expect(std.mem.eql(i32, &result.rgb, &.{ 1, 2, 3 }) and std.mem.eql(u8, &result.tag, &shape.tag), "fixed-size arrays", peer, failures);
    expect(result.anchor.?.y == -2 and result.token == null, "nullable parcelable and absent binder", peer, failures);
    expect(result.tags.len == 2 and std.mem.eql(u8, result.tags[1], "bc"), "List<String>", peer, failures);
    expect(result.child != null and result.child.?.kind == .CIRCLE and result.child.?.child == null, "heap-nullable recursion", peer, failures);
    expect(std.mem.eql(u8, result.pair.first, "p") and result.pair.second.x == 5 and result.pair.second.y == 7, "generic parcelable", peer, failures);
    expect(result.span != null and result.span.?[1] == 5 and result.mark != null and result.mark.?[0] == 6 and result.absent == null, "nullable fixed-size arrays", peer, failures);
    expect(std.mem.eql(u8, transformed.mirrored.name, "mirror:shape"), "out parcelable", peer, failures);
    expect(transformed.cursor.x == 11 and transformed.cursor.y == 21, "inout parcelable", peer, failures);

    expect((try client.choose(arena, .{ .number = 5 })).number == 6, "union int alternative", peer, failures);
    const point = (try client.choose(arena, .{ .text = "four" })).point;
    expect(point.x == 4 and point.y == 7, "union string -> parcelable alternative", peer, failures);
    const kinds = (try client.choose(arena, .{ .point = .{} })).kinds;
    expect(kinds.len == 2 and kinds[0] == Kind.ROUND and kinds[1] == .SQUARE, "union enum array and enum alias", peer, failures);
    expect((try client.choose(arena, .{ .kinds = &.{ .NONE, .NONE, .NONE } })).number == 3, "union array alternative", peer, failures);

    const scaled = try client.scale(arena, &.{ 1, 2, 3 }, 10, 3);
    expect(std.mem.eql(i64, scaled._result, &.{ 10, 20, 30 }) and std.mem.eql(i64, scaled.doubled, &.{ 2, 4, 6 }), "out array with a length hint", peer, failures);

    var listener: Listener = .{};
    var listener_server: IProbeCallback.Server(Listener) = undefined;
    listener_server.init(gpa, io, &listener);
    const callback = try IProbeCallback.Client.fromBinder(listener_server.binder());
    defer callback.deinit();
    expect(try client.relay(callback, 21) == 42 and listener.asked == 21, "interface argument and nested call", peer, failures);
    var waited: u32 = 0;
    while (listener.event.load(.acquire) == 0 and waited < 2000) : (waited += 1)
        Io.sleep(io, .fromMilliseconds(1), .awake) catch {};
    expect(listener.event.load(.acquire) == 42 and std.mem.eql(u8, listener.event_text[0..listener.event_len], "relayed"), "oneway callback", peer, failures);

    try client.fail(0);
    expect(client.fail(1) == error.IllegalArgument and aidl.lastStatus().exception == .illegal_argument and
        std.mem.eql(u8, aidl.lastStatus().message(), "mode one"), "exception with a message", peer, failures);
    expect(client.fail(2) == error.ServiceSpecific and aidl.lastStatus().service_specific == 77, "service-specific error", peer, failures);
    expect(client.fail(3) == error.SecurityException, "security exception", peer, failures);

    const source = try Io.Dir.cwd().openFile(io, "/proc/self/exe", .{});
    defer source.close(io);
    const reopened = try client.reopen(source);
    defer reopened.close(io);
    const expected = try source.stat(io);
    const actual = try reopened.stat(io);
    expect(reopened.handle != source.handle and expected.inode == actual.inode, "descriptor passing duplicates", peer, failures);

    expect(try client.same(null) == null, "null binder", peer, failures);
    const returned = (try client.same(callback.remote)).?;
    defer returned.deinit();
    expect(returned.handle == callback.remote.handle, "binder identity", peer, failures);

    expect(try client.next(.CIRCLE) == .SQUARE and try client.next(@fromBackingInt(@intCast(99))) == @as(Kind, @fromBackingInt(@intCast(99))), "enum, including an unknown value", peer, failures);
    expect(try client.names(arena, null) == null, "null string array", peer, failures);
    const listed = (try client.names(arena, &.{ "a", null, "" })).?;
    expect(listed.len == 3 and listed[1] == null and listed[2].?.len == 0 and std.mem.eql(u8, listed[0].?, "a"), "nullable elements", peer, failures);
}

extern fn AServiceManager_addService(binder: *aidl.AIBinder, instance: [*:0]const u8) callconv(.c) i32;
extern fn AServiceManager_waitForService(instance: [*:0]const u8) callconv(.c) ?*aidl.AIBinder;
extern fn ABinderProcess_startThreadPool() callconv(.c) void;
extern fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;

const zig_service = "dev.artzig.probe.zig";
const ndk_service = "dev.artzig.probe.ndk";
const cookie = "aidl-zig-test-system";

/// Child role: serve both implementations until the parent closes stdin.
fn serve(io: Io, gpa: std.mem.Allocator) !void {
    var implementation: Probe = .{ .io = io };
    var server: IProbe.Server(Probe) = undefined;
    server.init(gpa, io, &implementation);
    const local = server.binder();
    defer local.deinit();
    if (AServiceManager_addService(local.handle, zig_service) != 0) return error.RegistrationFailed;
    const ndk: aidl.Binder = .adopt(azk_probe_ndk_new());
    defer ndk.deinit();
    if (AServiceManager_addService(ndk.handle, ndk_service) != 0) return error.RegistrationFailed;
    ABinderProcess_startThreadPool();
    var byte: [1]u8 = undefined;
    while ((Io.File.stdin().readStreaming(io, &.{&byte}) catch 0) != 0) {}
}

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len == 2 and std.mem.eql(u8, args[1], "--serve")) return serve(io, gpa);
    if (args.len != 2) {
        std.debug.print("usage: azk-aidl-zig-test <azk-binder-broker>\n", .{});
        return error.InvalidArguments;
    }

    var random: [8]u8 = undefined;
    io.random(&random);
    const dir = try std.fmt.allocPrint(arena, "/tmp/azk-aidl-zig-test-{x}", .{&random});
    try Io.Dir.createDirAbsolute(io, dir, @fromBackingInt(@intCast(0o700)));
    defer Io.Dir.cwd().deleteTree(io, dir) catch {};
    const socket_path = try std.fmt.allocPrintSentinel(arena, "{s}/binder.sock", .{dir}, 0);
    const prop_area = try std.fmt.allocPrintSentinel(arena, "{s}/props", .{dir}, 0);

    // Both peers are trusted host processes and join as the system role.
    var env = try init.minimal.environ.createMap(gpa);
    defer env.deinit();
    try env.put("AZK_BINDER_SOCKET", socket_path);
    try env.put("AZK_BINDER_COOKIE", cookie);
    try env.put("AZK_BINDER_SYSTEM_COOKIE", cookie);
    try env.put("AZK_PROP_AREA", prop_area);

    var broker = try std.process.spawn(io, .{
        .argv = &.{ args[1], "--socket", socket_path, "--prop-area", prop_area },
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
    var line: [16]u8 = undefined;
    var got: usize = 0;
    while (got < line.len) {
        const n = try broker.stdout.?.readStreaming(io, &.{line[got..]});
        if (n == 0) break;
        got += n;
        if (std.mem.indexOfScalar(u8, line[0..got], '\n') != null) break;
    }
    if (!std.mem.startsWith(u8, line[0..got], "ready")) return error.BrokerNotReady;

    var peer = try std.process.spawn(io, .{
        .argv = &.{ "/proc/self/exe", "--serve" },
        .environ_map = &env,
        .stdin = .pipe,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    defer {
        if (peer.stdin) |stdin| stdin.close(io);
        peer.stdin = null;
        _ = peer.wait(io) catch {};
    }

    _ = setenv("AZK_BINDER_SOCKET", socket_path, 1);
    _ = setenv("AZK_BINDER_COOKIE", cookie, 1);
    _ = setenv("AZK_PROP_AREA", prop_area, 1);
    // Callbacks from the peer arrive on this process's Binder threads.
    ABinderProcess_startThreadPool();

    var failures: u32 = 0;
    const zig_remote: aidl.Binder = .adopt(AServiceManager_waitForService(zig_service) orelse return error.ServiceUnavailable);
    const zig_client = try IProbe.Client.fromBinder(zig_remote);
    defer zig_client.deinit();
    try drive(io, gpa, zig_client, "zig proxy -> zig server", &failures);

    const ndk_client = try IProbe.Client.fromBinder(.adopt(AServiceManager_waitForService(ndk_service) orelse return error.ServiceUnavailable));
    defer ndk_client.deinit();
    try drive(io, gpa, ndk_client, "zig proxy -> ndk server", &failures);

    // A second handle: the first is already associated with the Zig class.
    const for_ndk: aidl.Binder = .adopt(AServiceManager_waitForService(zig_service) orelse return error.ServiceUnavailable);
    defer for_ndk.deinit();
    const source_line = azk_probe_ndk_drive(for_ndk.handle);
    if (source_line != 0) {
        failures += 1;
        std.debug.print("azk-aidl-zig-test: FAIL (ndk proxy -> zig server): ProbeNdk.cpp:{d}\n", .{source_line});
    }

    if (failures != 0) return error.AidlZigBackendMismatch;
    std.debug.print("azk-aidl-zig-test: ok\n", .{});
}
