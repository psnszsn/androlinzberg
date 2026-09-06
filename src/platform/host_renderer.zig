//! JNI/state half of the HWUI host-surface adapter. The C++ side is a tiny
//! RenderProxy ABI bridge; target ownership and Java handling stay in Zig.
const std = @import("std");
const art = @import("art");
const jni = art.jni;
const EventFd = @import("event_fd").EventFd;

pub const ConfigureFn = *const fn (proxy: *anyopaque, display: ?*anyopaque, surface: ?*anyopaque, width: c_int, height: c_int) callconv(.c) void;
pub const DestroyFn = *const fn (proxy: *anyopaque) callconv(.c) void;
pub const RetainRenderNodeFn = *const fn (node: *anyopaque) callconv(.c) void;
pub const Bridges = struct { bind_egl_display: RetainRenderNodeFn, configure: ConfigureFn, destroy: DestroyFn, retain_render_node: RetainRenderNodeFn, release_render_node: RetainRenderNodeFn };

var g_io: std.Io = undefined;
var mutex: std.Io.Mutex = .init;
var display: ?*anyopaque = null;
var surface: ?*anyopaque = null;
var native_proxy_field: jni.jfieldID = null;
var renderer_field: jni.jfieldID = null;
var bridges: ?Bridges = null;
var wakeup: ?EventFd = null;

pub fn bindWayland(new_display: *anyopaque, new_surface: *anyopaque) void {
    mutex.lockUncancelable(g_io);
    defer mutex.unlock(g_io);
    if (bridges) |bridge| bridge.bind_egl_display(new_display);
    display = new_display;
    surface = new_surface;
}

fn proxyFromRenderer(env: [*c]jni.JNIEnv, renderer: jni.jobject) ?*anyopaque {
    const field = native_proxy_field orelse return null;
    const wrapper_field = renderer_field orelse return null;
    const stock_renderer = env.*.*.GetObjectField.?(env, renderer, wrapper_field) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, stock_renderer);
    const handle = env.*.*.GetLongField.?(env, stock_renderer, field);
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

fn nativeSnapshot(env: [*c]jni.JNIEnv, _: jni.jclass, node: jni.jlong, width: jni.jint, height: jni.jint) callconv(.c) jni.jobject {
    if (node == 0 or width <= 0 or height <= 0) return null;
    const bridge = bridges orelse return null;
    // Retain before any JNI lookup can collect the caller's Java RenderNode.
    const pointer: *anyopaque = @ptrFromInt(@as(usize, @bitCast(node)));
    bridge.retain_render_node(pointer);
    var adopted = false;
    defer if (!adopted) bridge.release_render_node(pointer);
    const node_class = env.*.*.FindClass.?(env, "android/graphics/RenderNode") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, node_class);
    const adopt = env.*.*.GetStaticMethodID.?(env, node_class, "adopt", "(J)Landroid/graphics/RenderNode;") orelse return null;
    const node_args = [_]jni.jvalue{.{ .j = node }};
    const owned = env.*.*.CallStaticObjectMethodA.?(env, node_class, adopt, &node_args) orelse return null;
    adopted = true;
    defer env.*.*.DeleteLocalRef.?(env, owned);
    const cls = env.*.*.FindClass.?(env, "dev/artzig/HostedHardwareSnapshot") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const create = env.*.*.GetStaticMethodID.?(env, cls, "create", "(Landroid/graphics/RenderNode;II)Landroid/graphics/Bitmap;") orelse return null;
    const args = [_]jni.jvalue{ .{ .l = owned }, .{ .i = width }, .{ .i = height } };
    return env.*.*.CallStaticObjectMethodA.?(env, cls, create, &args);
}

pub fn register(io: std.Io, env: [*c]jni.JNIEnv, hwui_bridges: Bridges) !void {
    g_io = io;
    if (wakeup == null) wakeup = try EventFd.init(io);
    const hardware_renderer = env.*.*.FindClass.?(env, "android/graphics/HardwareRenderer") orelse return error.NoClass;
    native_proxy_field = env.*.*.GetFieldID.?(env, hardware_renderer, "mNativeProxy", "J") orelse return error.NoField;
    const threaded_renderer = env.*.*.FindClass.?(env, "dev/artzig/HostedRenderer") orelse return error.NoClass;
    renderer_field = env.*.*.GetFieldID.?(env, threaded_renderer, "renderer", "Landroid/view/ThreadedRenderer;") orelse return error.NoField;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nConfigureHostSurface", .signature = "(II)V", .fnPtr = @ptrCast(@constCast(&nativeConfigure)) },
        .{ .name = "nDestroyHostSurface", .signature = "()V", .fnPtr = @ptrCast(@constCast(&nativeDestroy)) },
        .{ .name = "nNotifyHostFrameCommitted", .signature = "()V", .fnPtr = @ptrCast(@constCast(&nativeNotifyFrameCommitted)) },
    };
    bridges = hwui_bridges;
    if (env.*.*.RegisterNatives.?(env, threaded_renderer, &methods, methods.len) != 0) return error.RegisterFailed;
    // AOSP's snapshot JNI uses ImageReader/BufferQueue under __ANDROID__. Reuse
    // the existing hosted HardwareBufferRenderer path below its stock Java API.
    const snapshots = [_]jni.JNINativeMethod{
        .{ .name = "nCreateHardwareBitmap", .signature = "(JII)Landroid/graphics/Bitmap;", .fnPtr = @ptrCast(@constCast(&nativeSnapshot)) },
    };
    if (env.*.*.RegisterNatives.?(env, hardware_renderer, &snapshots, snapshots.len) != 0) return error.RegisterFailed;
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
