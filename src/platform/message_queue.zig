//! Borrow the stock C++ Looper's poll state for the Wayland owner loop.
//! AOSP android_os_MessageQueue.cpp owns JNI, callbacks, exceptions and all
//! descriptors. The host never reads, closes or transfers this borrowed fd.
const std = @import("std");

pub const PollStateFn = *const fn (fd: *c_int, timeout_ms: *c_int) callconv(.c) void;
pub const PollState = struct { fd: ?std.posix.fd_t = null, timeout_ms: i32 = -1 };
var poll_state: ?PollStateFn = null;

pub fn bind(callback: PollStateFn) void {
    poll_state = callback;
}

/// Owning thread only; fetch anew because the stock Looper can rebuild epoll.
pub fn hostPollState() PollState {
    const callback = poll_state orelse return .{};
    var fd: c_int = -1;
    var timeout_ms: c_int = -1;
    callback(&fd, &timeout_ms);
    return .{ .fd = if (fd >= 0) fd else null, .timeout_ms = timeout_ms };
}

pub fn deinit() void {
    poll_state = null;
}
pub fn deinit() void { poll_fd = null; }
