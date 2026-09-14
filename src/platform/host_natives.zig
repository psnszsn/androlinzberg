//! Host-owned Android natives. AOSP/HWUI registration stays behind the C++
//! bridge; Linux integrations register directly from Zig after it succeeds.
const std = @import("std");
const art = @import("art");
const codec_jni = @import("codec_jni");
const message_queue = @import("message_queue");

pub const LooperPollStateFn = message_queue.PollStateFn;

pub fn register(_: std.Io, env: [*c]art.jni.JNIEnv, looper_poll: LooperPollStateFn) !void {
    message_queue.bind(looper_poll);
    try codec_jni.register(env);
}

pub const looperPollState = message_queue.hostPollState;

pub fn deinitWakeups() void {
    message_queue.deinit();
}
