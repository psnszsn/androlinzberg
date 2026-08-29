//! JNI/state half of the HWUI host-surface adapter. The C++ side is a tiny
//! RenderProxy ABI bridge; target ownership and Java handling stay in Zig.
const std = @import("std");
const art = @import("art");
const jni = art.jni;
const EventFd = @import("event_fd").EventFd;

pub const ConfigureFn = *const fn (proxy: *anyopaque, display: ?*anyopaque, surface: ?*anyopaque, width: c_int, height: c_int) callconv(.c) void;
pub const DestroyFn = *const fn (proxy: *anyopaque) callconv(.c) void;
pub const Bridges = struct { configure: ConfigureFn, destroy: DestroyFn };

var g_io: std.Io = undefined;
var mutex: std.Io.Mutex = .init;
var display: ?*anyopaque = null;
var surface: ?*anyopaque = null;
var native_proxy_field: jni.jfieldID = null;
var bridges: ?Bridges = null;
var wakeup: ?EventFd = null;

pub fn bindWayland(new_display: *anyopaque, new_surface: *anyopaque) void {
    mutex.lockUncancelable(g_io);
    defer mutex.unlock(g_io);
    display = new_display;
    surface = new_surface;
}

fn proxyFromRenderer(env: [*c]jni.JNIEnv, renderer: jni.jobject) ?*anyopaque {
    const field = native_proxy_field orelse return null;
    const handle = env.*.*.GetLongField.?(env, renderer, field);
    if (handle == 0) return null;
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

fn throwIllegalState(env: [*c]jni.JNIEnv) void {
    const cls = env.*.*.FindClass.?(env, "java/lang/IllegalStateException") orelse return;
    _ = env.*.*.ThrowNew.?(env, cls, "Wayland presentation target is not bound");
}

fn nativeConfigure(env: [*c]jni.JNIEnv, renderer: jni.jobject, width: jni.jint, height: jni.jint) callconv(.c) void {
    const proxy = proxyFromRenderer(env, renderer) orelse return;
    if (width <= 0 or height <= 0) return;
    mutex.lockUncancelable(g_io);
    const target_display = display;
    const target_surface = surface;
    mutex.unlock(g_io);
    if (target_display == null or target_surface == null) {
        throwIllegalState(env);
        return;
    }
    const bridge = bridges orelse return;
    bridge.configure(proxy, target_display, target_surface, width, height);
}

fn nativeDestroy(env: [*c]jni.JNIEnv, renderer: jni.jobject) callconv(.c) void {
    const proxy = proxyFromRenderer(env, renderer) orelse return;
    const bridge = bridges orelse return;
    bridge.destroy(proxy);
}

fn nativeNotifyFrameCommitted(_: [*c]jni.JNIEnv, _: jni.jclass) callconv(.c) void {
    if (wakeup) |event| event.signal();
}

pub fn wakeupFd() ?std.posix.fd_t {
    return if (wakeup) |event| event.fd() else null;
}

pub fn consumeWakeup() u64 {
    return if (wakeup) |event| event.consume() else 0;
}

pub fn deinitWakeup() void {
    if (wakeup) |event| event.close();
    wakeup = null;
}

pub fn register(io: std.Io, env: [*c]jni.JNIEnv, hwui_bridges: Bridges) !void {
    g_io = io;
    if (wakeup == null) wakeup = try EventFd.init(io);
    const hardware_renderer = env.*.*.FindClass.?(env, "android/graphics/HardwareRenderer") orelse return error.NoClass;
    native_proxy_field = env.*.*.GetFieldID.?(env, hardware_renderer, "mNativeProxy", "J") orelse return error.NoField;
    const threaded_renderer = env.*.*.FindClass.?(env, "android/view/ThreadedRenderer") orelse return error.NoClass;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nConfigureHostSurface", .signature = "(II)V", .fnPtr = @ptrCast(@constCast(&nativeConfigure)) },
        .{ .name = "nDestroyHostSurface", .signature = "()V", .fnPtr = @ptrCast(@constCast(&nativeDestroy)) },
        .{ .name = "nNotifyHostFrameCommitted", .signature = "()V", .fnPtr = @ptrCast(@constCast(&nativeNotifyFrameCommitted)) },
    };
    bridges = hwui_bridges;
    if (env.*.*.RegisterNatives.?(env, threaded_renderer, &methods, methods.len) != 0) return error.RegisterFailed;
}

test "HWUI bridge ABI compiles" {
    _ = &register;
}

test "frame commit wakeups are coalesced" {
    wakeup = try EventFd.init(std.testing.io);
    defer deinitWakeup();
    nativeNotifyFrameCommitted(undefined, null);
    nativeNotifyFrameCommitted(undefined, null);
    try std.testing.expectEqual(@as(u64, 2), consumeWakeup());
}
