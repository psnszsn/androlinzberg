//! Linux wake seam for AOSP MessageQueue.nativeWake(). Android normally wakes
//! the main looper's epoll fd; the hosted runtime wakes the Wayland owner loop.
const std = @import("std");
const art = @import("art");
const jni = art.jni;
const EventFd = @import("event_fd").EventFd;

var wakeup: ?EventFd = null;

fn signal() void {
    if (wakeup) |event| event.signal();
}

fn nativeWakeHost(_: [*c]jni.JNIEnv, _: jni.jclass) callconv(.c) void {
    signal();
}

pub fn register(io: std.Io, env: [*c]jni.JNIEnv) !void {
    if (wakeup == null) wakeup = try EventFd.init(io);
    const cls = env.*.*.FindClass.?(env, "android/os/MessageQueue") orelse return error.NoClass;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nativeWakeHost", .signature = "()V", .fnPtr = @ptrCast(@constCast(&nativeWakeHost)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

pub fn wakeupFd() ?std.posix.fd_t {
    return if (wakeup) |event| event.fd() else null;
}

pub fn consume() u64 {
    return if (wakeup) |event| event.consume() else 0;
}

pub fn deinit() void {
    if (wakeup) |event| event.close();
    wakeup = null;
}

test "eventfd coalesces MessageQueue wakeups" {
    wakeup = try EventFd.init(std.testing.io);
    defer deinit();
    signal();
    signal();
    try std.testing.expectEqual(@as(u64, 2), consume());
    try std.testing.expectEqual(@as(u64, 0), consume());
}

test "cross-thread MessageQueue wake interrupts host poll" {
    wakeup = try EventFd.init(std.testing.io);
    defer deinit();
    const thread = try std.Thread.spawn(.{}, signal, .{});
    var fds = [_]std.posix.pollfd{.{
        .fd = wakeup.?.fd(),
        .events = std.posix.POLL.IN,
        .revents = 0,
    }};
    try std.testing.expectEqual(@as(usize, 1), try std.posix.poll(&fds, 1000));
    thread.join();
    try std.testing.expectEqual(@as(u64, 1), consume());
}
