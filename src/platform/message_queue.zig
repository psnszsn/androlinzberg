//! Readiness seam for AOSP's native MessageQueue/ALooper. The C++ Looper owns
//! the descriptor; the host event loop borrows it and stock pollOnce drains it.
pub const PollFdFn = *const fn () callconv(.c) c_int;
var poll_fd: ?PollFdFn = null;
pub fn configure(callback: PollFdFn) void { poll_fd = callback; }
pub fn hostQueueFd() ?c_int {
    const callback = poll_fd orelse return null;
    const fd = callback();
    return if (fd >= 0) fd else null;
}
pub fn deinit() void { poll_fd = null; }
