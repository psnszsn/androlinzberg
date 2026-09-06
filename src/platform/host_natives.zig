//! Host-owned Android natives. AOSP/HWUI registration stays behind the C++
//! bridge; Linux integrations register directly from Zig after it succeeds.
const std = @import("std");
const art = @import("art");
const audio = @import("audio");
const codec_jni = @import("codec_jni");
const host_renderer = @import("host_renderer");
const message_queue = @import("message_queue");

pub const LooperPollStateFn = message_queue.PollStateFn;
pub const HwuiBridges = host_renderer.Bridges;

pub fn register(io: std.Io, env: [*c]art.jni.JNIEnv, hwui: HwuiBridges, looper_poll: LooperPollStateFn) !void {
    message_queue.bind(looper_poll);
    try host_renderer.register(io, env, hwui);
    try audio.register(io, env);
    try codec_jni.register(env);
}

pub const bindWayland = host_renderer.bindWayland;
pub const looperPollState = message_queue.hostPollState;
pub const rendererWakeupFd = host_renderer.wakeupFd;
pub const consumeRendererWakeup = host_renderer.consumeWakeup;

pub fn deinitWakeups() void {
    host_renderer.deinitWakeup();
    message_queue.deinit();
}
