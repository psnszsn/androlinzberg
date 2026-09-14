//! Host-owned Android natives. AOSP/HWUI registration stays behind the C++
//! bridge; Linux integrations register directly from Zig after it succeeds.
const message_queue = @import("message_queue");

pub const LooperPollStateFn = message_queue.PollStateFn;

pub fn bindLooper(looper_poll: LooperPollStateFn) void {
    message_queue.bind(looper_poll);
}

pub const looperPollState = message_queue.hostPollState;

pub fn deinitWakeups() void {
    message_queue.deinit();
}
