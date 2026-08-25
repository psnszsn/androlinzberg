//! Host-owned Android natives. AOSP/HWUI registration stays behind the C++
//! bridge; Linux integrations register directly from Zig after it succeeds.
const art = @import("art");
const audio = @import("audio");
const codec_jni = @import("codec_jni");
const host_renderer = @import("host_renderer");

pub const HwuiBridges = host_renderer.Bridges;

pub fn register(env: [*c]art.jni.JNIEnv, hwui: HwuiBridges) !void {
    try host_renderer.register(env, hwui);
    try audio.register(env);
    try codec_jni.register(env);
}

pub const bindWayland = host_renderer.bindWayland;
