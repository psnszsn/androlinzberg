//! Native ABI regression helpers for dev.artzig.StockEgl15Smoke.
//! This registers no Android framework methods.
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const none = 0x3038;
fn pointer(value: jni.jlong) Handle {
    return @ptrFromInt(@as(usize, @bitCast(value)));
}
fn boolean(value: u32) jni.jboolean {
    return if (value != 0) jni.JNI_TRUE else jni.JNI_FALSE;
}

fn nativePbufferInterop(env: Env, _: jni.jobject, path: jni.jstring, raw_display: jni.jlong, raw_surface: jni.jlong) callconv(.c) jni.jboolean {
    const name = env.*.*.GetStringUTFChars.?(env, path, null) orelse return jni.JNI_FALSE;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, name);
    var library = std.DynLib.open(std.mem.span(name)) catch return jni.JNI_FALSE;
    defer library.close();
    const get_proc = library.lookup(*const fn ([*:0]const u8) callconv(.c) ?*const anyopaque, "eglGetProcAddress") orelse return jni.JNI_FALSE;
    const init = library.lookup(*const fn (Handle, ?*i32, ?*i32) callconv(.c) u32, "eglInitialize") orelse return jni.JNI_FALSE;
    const term = library.lookup(*const fn (Handle) callconv(.c) u32, "eglTerminate") orelse return jni.JNI_FALSE;
    const query = library.lookup(*const fn (Handle, Handle, i32, *i32) callconv(.c) u32, "eglQuerySurface") orelse return jni.JNI_FALSE;
    const swap = library.lookup(*const fn (Handle, Handle) callconv(.c) u32, "eglSwapBuffers") orelse return jni.JNI_FALSE;
    if (get_proc("eglQuerySurface") != @as(?*const anyopaque, @ptrCast(query))) return jni.JNI_FALSE;
    const display = pointer(raw_display);
    const surface = pointer(raw_surface);
    if (init(display, null, null) == 0) return jni.JNI_FALSE;
    defer _ = term(display);
    var width: i32 = -1;
    var height: i32 = -1;
    return boolean(@intFromBool(query(display, surface, 0x3057, &width) != 0 and width == 0 and
        query(display, surface, 0x3056, &height) != 0 and height == 0 and swap(display, surface) != 0 and
        nativeBufferInterop(&library, get_proc, display)));
}

fn nativeBufferInterop(library: *std.DynLib, get_proc: *const fn ([*:0]const u8) callconv(.c) ?*const anyopaque, display: Handle) bool {
    // Public NDK AHardwareBuffer_Desc; the provider uses the same API/ABI.
    const Desc = extern struct { width: u32 = 2, height: u32 = 2, layers: u32 = 1, format: u32 = 1, usage: u64 = 0x330, stride: u32 = 0, rfu0: u32 = 0, rfu1: u64 = 0 };
    const allocate = library.lookup(*const fn (*const Desc, *Handle) callconv(.c) i32, "AHardwareBuffer_allocate") orelse return false;
    const release = library.lookup(*const fn (Handle) callconv(.c) void, "AHardwareBuffer_release") orelse return false;
    const describe = library.lookup(*const fn (Handle, *Desc) callconv(.c) void, "AHardwareBuffer_describe") orelse return false;
    const lock = library.lookup(*const fn (Handle, u64, i32, Handle, *Handle) callconv(.c) i32, "AHardwareBuffer_lock") orelse return false;
    const unlock = library.lookup(*const fn (Handle, ?*i32) callconv(.c) i32, "AHardwareBuffer_unlock") orelse return false;
    const live = library.lookup(*const fn () callconv(.c) u64, "azk_graphics_live_buffers") orelse return false;
    const client: *const fn (Handle) callconv(.c) Handle = @ptrCast(get_proc("eglGetNativeClientBufferANDROID") orelse return false);
    const create: *const fn (Handle, Handle, u32, Handle, ?[*]const i32) callconv(.c) Handle = @ptrCast(get_proc("eglCreateImageKHR") orelse return false);
    const create_core: *const fn (Handle, Handle, u32, Handle, ?[*]const isize) callconv(.c) Handle = @ptrCast(get_proc("eglCreateImage") orelse return false);
    const destroy: *const fn (Handle, Handle) callconv(.c) u32 = @ptrCast(get_proc("eglDestroyImageKHR") orelse return false);
    const get_error: *const fn () callconv(.c) i32 = @ptrCast(get_proc("eglGetError") orelse return false);
    if (client(null) != null or get_error() != 0x300c or get_error() != 0x3000) return false;
    if (create(display, null, 0x3140, null, null) != null or get_error() != 0x300c) return false;
    const before = live();
    var desc: Desc = .{};
    var buffer: Handle = null;
    if (allocate(&desc, &buffer) != 0) return false;
    var owned = true;
    defer if (owned) release(buffer);
    describe(buffer, &desc);
    var pixels: Handle = null;
    if (lock(buffer, 0x30, -1, null, &pixels) != 0) return false;
    const bytes: [*]u8 = @ptrCast(pixels);
    for (0..2) |y| for (0..2) |x| {
        @memcpy(bytes[(y * desc.stride + x) * 4 ..][0..4], &[_]u8{ 0x33, 0x66, 0x99, 0xff });
    };
    if (unlock(buffer, null) != 0) return false;
    const native = client(buffer) orelse return false;
    const image = create(display, null, 0x3140, native, &.{ 0x30d2, 1, none }) orelse return false;
    var image_live = true;
    defer if (image_live) {
        _ = destroy(display, image);
    };
    release(buffer);
    owned = false;
    // The returned client buffer survives the last application AHB reference.
    const second = create_core(display, null, 0x3140, native, &.{ 0x30d2, 0, none }) orelse return false;
    defer _ = destroy(display, second);
    if (destroy(display, image) == 0) return false;
    image_live = false;
    if (live() != before + 1) return false;
    const gen: *const fn (i32, *u32) callconv(.c) void = @ptrCast(get_proc("glGenTextures") orelse return false);
    const bind: *const fn (u32, u32) callconv(.c) void = @ptrCast(get_proc("glBindTexture") orelse return false);
    const delete: *const fn (i32, *const u32) callconv(.c) void = @ptrCast(get_proc("glDeleteTextures") orelse return false);
    const target: *const fn (u32, Handle) callconv(.c) void = @ptrCast(get_proc("glEGLImageTargetTexture2DOES") orelse return false);
    const gen_fbo: *const fn (i32, *u32) callconv(.c) void = @ptrCast(get_proc("glGenFramebuffers") orelse return false);
    const bind_fbo: *const fn (u32, u32) callconv(.c) void = @ptrCast(get_proc("glBindFramebuffer") orelse return false);
    const delete_fbo: *const fn (i32, *const u32) callconv(.c) void = @ptrCast(get_proc("glDeleteFramebuffers") orelse return false);
    const attach: *const fn (u32, u32, u32, u32, i32) callconv(.c) void = @ptrCast(get_proc("glFramebufferTexture2D") orelse return false);
    const read: *const fn (i32, i32, i32, i32, u32, u32, *[4]u8) callconv(.c) void = @ptrCast(get_proc("glReadPixels") orelse return false);
    const gl_error: *const fn () callconv(.c) u32 = @ptrCast(get_proc("glGetError") orelse return false);
    var texture: u32 = 0;
    gen(1, &texture);
    defer delete(1, &texture);
    bind(0x0de1, texture);
    target(0x0de1, second);
    var framebuffer: u32 = 0;
    gen_fbo(1, &framebuffer);
    defer delete_fbo(1, &framebuffer);
    bind_fbo(0x8d40, framebuffer);
    defer bind_fbo(0x8d40, 0);
    attach(0x8d40, 0x8ce0, 0x0de1, texture, 0);
    var result: [4]u8 = undefined;
    read(0, 0, 1, 1, 0x1908, 0x1401, &result);
    return gl_error() == 0 and std.mem.eql(u8, &result, &.{ 0x33, 0x66, 0x99, 0xff });
}

// Call the pinned C++ ADisplay interface through its opaque pointer ABI.
fn nativeDisplaySnapshot(env: Env, _: jni.jobject, path: jni.jstring) callconv(.c) jni.jdoubleArray {
    const name = env.*.*.GetStringUTFChars.?(env, path, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, path, name);
    var library = std.DynLib.open(std.mem.span(name)) catch return null;
    defer library.close();
    const acquire = library.lookup(*const fn (*?[*]Handle) callconv(.c) i32, "_ZN7android32ADisplay_acquirePhysicalDisplaysEPPPNS_8ADisplayE") orelse return null;
    const release = library.lookup(*const fn (?[*]Handle) callconv(.c) void, "_ZN7android16ADisplay_releaseEPPNS_8ADisplayE") orelse return null;
    const config = library.lookup(*const fn (Handle, *Handle) callconv(.c) i32, "_ZN7android25ADisplay_getCurrentConfigEPNS_8ADisplayEPPNS_14ADisplayConfigE") orelse return null;
    const kind = library.lookup(*const fn (Handle) callconv(.c) i32, "_ZN7android23ADisplay_getDisplayTypeEPNS_8ADisplayE") orelse return null;
    const width = library.lookup(*const fn (Handle) callconv(.c) i32, "_ZN7android23ADisplayConfig_getWidthEPNS_14ADisplayConfigE") orelse return null;
    const height = library.lookup(*const fn (Handle) callconv(.c) i32, "_ZN7android24ADisplayConfig_getHeightEPNS_14ADisplayConfigE") orelse return null;
    const fps = library.lookup(*const fn (Handle) callconv(.c) f32, "_ZN7android21ADisplayConfig_getFpsEPNS_14ADisplayConfigE") orelse return null;
    const max_fps = library.lookup(*const fn (Handle) callconv(.c) f32, "_ZN7android27ADisplay_getMaxSupportedFpsEPNS_8ADisplayE") orelse return null;
    const offset = library.lookup(*const fn (Handle) callconv(.c) i64, "_ZN7android37ADisplayConfig_getAppVsyncOffsetNanosEPNS_14ADisplayConfigE") orelse return null;
    var displays: ?[*]Handle = null;
    const count = acquire(&displays);
    if (count <= 0) return null;
    defer release(displays);
    const values = std.heap.c_allocator.alloc(f64, @as(usize, @intCast(count)) * 6) catch return null;
    defer std.heap.c_allocator.free(values);
    for (displays.?[0..@intCast(count)], 0..) |display, i| {
        var mode: Handle = null;
        if (config(display, &mode) != 0) return null;
        values[i * 6 ..][0..6].* = .{ @floatFromInt(kind(display)), @floatFromInt(width(mode)), @floatFromInt(height(mode)), fps(mode), max_fps(display), @floatFromInt(offset(mode)) };
    }
    const result = env.*.*.NewDoubleArray.?(env, @intCast(values.len)) orelse return null;
    env.*.*.SetDoubleArrayRegion.?(env, result, 0, @intCast(values.len), values.ptr);
    return result;
}

fn nativeMethod(name: [*:0]const u8, signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "dev/artzig/StockEgl15Smoke") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        nativeMethod("nativeDisplaySnapshot", "(Ljava/lang/String;)[D", &nativeDisplaySnapshot),
        nativeMethod("nativePbufferInterop", "(Ljava/lang/String;JJ)Z", &nativePbufferInterop),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
