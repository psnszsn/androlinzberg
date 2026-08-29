//! Asynchronous owner for the host installd/dex2oat adapter. Compilation never
//! blocks the Android UI thread; completion is folded into the Wayland poll set.
const std = @import("std");
const EventFd = @import("event_fd").EventFd;

pub const Completion = enum(u8) { none, success, failure };

const Worker = struct {
    child: std.process.Child,
    io: std.Io,
    allocator: std.mem.Allocator,
};

var wakeup: ?EventFd = null;
var completion: std.atomic.Value(u8) = std.atomic.Value(u8).init(@intFromEnum(Completion.none));

fn ensureWakeup(io: std.Io) !void {
    if (wakeup == null) wakeup = try EventFd.init(io);
}

fn signal() void {
    if (wakeup) |event| event.signal();
}

fn waitForCompiler(worker: *Worker) void {
    defer worker.allocator.destroy(worker);
    const term = worker.child.wait(worker.io) catch {
        completion.store(@intFromEnum(Completion.failure), .release);
        signal();
        return;
    };
    completion.store(
        @intFromEnum(if (term.success()) Completion.success else Completion.failure),
        .release,
    );
    signal();
}

pub fn launch(
    io: std.Io,
    allocator: std.mem.Allocator,
    script: []const u8,
    runtime_root: []const u8,
    apk: []const u8,
    framework_jar: []const u8,
    android_data: []const u8,
    framework_compile_jar: []const u8,
) !void {
    try ensureWakeup(io);
    completion.store(@intFromEnum(Completion.none), .release);
    var child = try std.process.spawn(io, .{
        .argv = &.{ "bash", script, runtime_root, apk, framework_jar, android_data, framework_compile_jar },
        .stdin = .ignore,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    errdefer child.kill(io);
    const worker = try allocator.create(Worker);
    worker.* = .{ .child = child, .io = io, .allocator = allocator };
    const thread = try std.Thread.spawn(.{}, waitForCompiler, .{worker});
    thread.detach();
}

pub fn wakeupFd() ?std.posix.fd_t {
    return if (wakeup) |event| event.fd() else null;
}

pub fn consume() Completion {
    if (wakeup) |event| _ = event.consume();
    return @enumFromInt(completion.swap(@intFromEnum(Completion.none), .acq_rel));
}

test "completion eventfd reports the final compiler state" {
    try ensureWakeup(std.testing.io);
    defer {
        wakeup.?.close();
        wakeup = null;
    }
    completion.store(@intFromEnum(Completion.success), .release);
    signal();
    try std.testing.expectEqual(Completion.success, consume());
    try std.testing.expectEqual(Completion.none, consume());
}
