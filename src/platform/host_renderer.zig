//! JNI/state half of the HWUI host-surface adapter. The C++ side is a tiny
//! RenderProxy ABI bridge; target ownership and Java handling stay in Zig.
const std = @import("std");
const art = @import("art");
const jni = art.jni;

const Mutex = struct {
    raw: std.c.pthread_mutex_t = std.c.PTHREAD_MUTEX_INITIALIZER,
    fn lock(self: *Mutex) void {
        _ = std.c.pthread_mutex_lock(&self.raw);
    }
    fn unlock(self: *Mutex) void {
        _ = std.c.pthread_mutex_unlock(&self.raw);
    }
};

pub const ConfigureFn = *const fn (proxy: *anyopaque, display: ?*anyopaque, surface: ?*anyopaque, width: c_int, height: c_int) callconv(.c) void;
pub const DestroyFn = *const fn (proxy: *anyopaque) callconv(.c) void;
pub const Bridges = struct { configure: ConfigureFn, destroy: DestroyFn };

var mutex: Mutex = .{};
var display: ?*anyopaque = null;
var surface: ?*anyopaque = null;
var native_proxy_field: jni.jfieldID = null;
var bridges: ?Bridges = null;

pub fn bindWayland(new_display: *anyopaque, new_surface: *anyopaque) void {
    mutex.lock();
    defer mutex.unlock();
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
    mutex.lock();
    const target_display = display;
    const target_surface = surface;
    mutex.unlock();
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

pub fn register(env: [*c]jni.JNIEnv, hwui_bridges: Bridges) !void {
    const hardware_renderer = env.*.*.FindClass.?(env, "android/graphics/HardwareRenderer") orelse return error.NoClass;
    native_proxy_field = env.*.*.GetFieldID.?(env, hardware_renderer, "mNativeProxy", "J") orelse return error.NoField;
    const threaded_renderer = env.*.*.FindClass.?(env, "android/view/ThreadedRenderer") orelse return error.NoClass;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nConfigureHostSurface", .signature = "(II)V", .fnPtr = @ptrCast(@constCast(&nativeConfigure)) },
        .{ .name = "nDestroyHostSurface", .signature = "()V", .fnPtr = @ptrCast(@constCast(&nativeDestroy)) },
    };
    bridges = hwui_bridges;
    if (env.*.*.RegisterNatives.?(env, threaded_renderer, &methods, methods.len) != 0) return error.RegisterFailed;
}

test "HWUI bridge ABI compiles" {
    _ = &register;
}
