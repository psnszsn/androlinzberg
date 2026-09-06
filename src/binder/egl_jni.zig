//! JNI for pinned android_opengl_EGL14.cpp and the verbatim EGL handle classes.
//! EGL platform adaptation lives in egl_backend.zig; HWUI/Wayland presentation
//! remains separately owned by its existing backend.
const std = @import("std");
const backend = @import("egl_backend.zig");
const frameworkIo = @import("ndk.zig").frameworkIo;
const GetGbmDevice = *const fn () callconv(.c) Handle;
var get_gbm_device: GetGbmDevice = undefined;
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const alloc = std.heap.c_allocator;
const none: i32 = 0x3038;
const Kind = enum { display, context, surface, config };
const CachedClass = struct { class: jni.jclass, constructor: jni.jmethodID, get_handle: jni.jmethodID, zero: jni.jobject = null };
var classes: [4]CachedClass = undefined;

extern "EGL" fn eglGetError() i32;
extern "EGL" fn eglGetDisplay(Handle) Handle;
extern "EGL" fn eglGetPlatformDisplay(u32, Handle, ?[*]const isize) Handle;
extern "EGL" fn eglInitialize(Handle, ?*i32, ?*i32) u32;
extern "EGL" fn eglTerminate(Handle) u32;
extern "EGL" fn eglQueryString(Handle, i32) ?[*:0]const u8;
extern "EGL" fn eglGetConfigs(Handle, ?[*]Handle, i32, *i32) u32;
extern "EGL" fn eglChooseConfig(Handle, ?[*]const i32, ?[*]Handle, i32, *i32) u32;
extern "EGL" fn eglGetConfigAttrib(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglCreatePbufferSurface(Handle, Handle, ?[*]const i32) Handle;
extern "EGL" fn eglDestroySurface(Handle, Handle) u32;
extern "EGL" fn eglQuerySurface(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglBindAPI(u32) u32;
extern "EGL" fn eglQueryAPI() u32;
extern "EGL" fn eglWaitClient() u32;
extern "EGL" fn eglReleaseThread() u32;
extern "EGL" fn eglCreatePbufferFromClientBuffer(Handle, u32, Handle, Handle, ?[*]const i32) Handle;
extern "EGL" fn eglSurfaceAttrib(Handle, Handle, i32, i32) u32;
extern "EGL" fn eglBindTexImage(Handle, Handle, i32) u32;
extern "EGL" fn eglReleaseTexImage(Handle, Handle, i32) u32;
extern "EGL" fn eglSwapInterval(Handle, i32) u32;
extern "EGL" fn eglCreateContext(Handle, Handle, Handle, ?[*]const i32) Handle;
extern "EGL" fn eglDestroyContext(Handle, Handle) u32;
extern "EGL" fn eglMakeCurrent(Handle, Handle, Handle, Handle) u32;
extern "EGL" fn eglGetCurrentContext() Handle;
extern "EGL" fn eglGetCurrentSurface(i32) Handle;
extern "EGL" fn eglGetCurrentDisplay() Handle;
extern "EGL" fn eglQueryContext(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglWaitGL() u32;
extern "EGL" fn eglWaitNative(i32) u32;
extern "EGL" fn eglSwapBuffers(Handle, Handle) u32;

fn throwNamed(env: Env, name: [*:0]const u8, message: [*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}
fn invalid(env: Env, message: [*:0]const u8) void {
    throwNamed(env, "java/lang/IllegalArgumentException", message);
}
fn boolean(value: u32) jni.jboolean {
    return if (value == 0) jni.JNI_FALSE else jni.JNI_TRUE;
}
fn pointer(value: jni.jlong) Handle {
    return @ptrFromInt(@as(usize, @bitCast(value)));
}
fn handle(env: Env, kind: Kind, object: jni.jobject) Handle {
    if (object == null) {
        invalid(env, "Object is set to null.");
        return null;
    }
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    return pointer(env.*.*.CallLongMethodA.?(env, object, classes[@backingInt(kind)].get_handle, null));
}
fn wrapHandle(env: Env, kind: Kind, value: Handle) jni.jobject {
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    const cls = classes[@backingInt(kind)];
    if (value == null and kind != .config) return cls.zero;
    const args = [_]jni.jvalue{.{ .j = @bitCast(@intFromPtr(value)) }};
    return env.*.*.NewObjectA.?(env, cls.class, cls.constructor, &args);
}
fn nativeClassInit(env: Env, cls: jni.jclass) callconv(.c) void {
    inline for (.{ "EGLDisplay", "EGLContext", "EGLSurface", "EGLConfig" }, 0..) |name, i| {
        const local = env.*.*.FindClass.?(env, "android/opengl/" ++ name) orelse return;
        defer env.*.*.DeleteLocalRef.?(env, local);
        classes[i] = .{
            .class = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return),
            .constructor = env.*.*.GetMethodID.?(env, local, "<init>", "(J)V") orelse return,
            .get_handle = env.*.*.GetMethodID.?(env, local, "getNativeHandle", "()J") orelse return,
        };
        if (i < 3) {
            const args = [_]jni.jvalue{.{ .j = 0 }};
            const zero = env.*.*.NewObjectA.?(env, local, classes[i].constructor, &args) orelse return;
            defer env.*.*.DeleteLocalRef.?(env, zero);
            classes[i].zero = env.*.*.NewGlobalRef.?(env, zero) orelse return;
            const field_name = .{ "EGL_NO_DISPLAY", "EGL_NO_CONTEXT", "EGL_NO_SURFACE" }[i];
            const field = env.*.*.GetStaticFieldID.?(env, cls, field_name, "Landroid/opengl/" ++ name ++ ";") orelse return;
            env.*.*.SetStaticObjectField.?(env, cls, field, classes[i].zero);
        }
    }
}

const IntArray = struct {
    env: Env,
    ref: jni.jintArray,
    base: [*c]jni.jint = null,
    offset: usize = 0,
    len: usize = 0,
    fn deinit(self: IntArray, write: bool) void {
        if (self.base != null) self.env.*.*.ReleaseIntArrayElements.?(self.env, self.ref, self.base, if (write) 0 else jni.JNI_ABORT);
    }
    fn ptr(self: IntArray) ?[*]i32 {
        return if (self.base == null) null else self.base + self.offset;
    }
    fn one(self: IntArray) ?*i32 {
        return if (self.ptr()) |p| &p[0] else null;
    }
};
fn intArray(env: Env, ref: jni.jintArray, offset: i32, comptime name: []const u8, comptime offset_name: []const u8, optional: bool, minimum: usize) ?IntArray {
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    if (ref == null) {
        if (optional) return .{ .env = env, .ref = ref };
        invalid(env, name ++ " == null");
        return null;
    }
    if (offset < 0) {
        invalid(env, offset_name ++ " < 0");
        return null;
    }
    const size = env.*.*.GetArrayLength.?(env, ref);
    if (offset > size or @as(usize, @intCast(size - offset)) < minimum) {
        invalid(env, "length - " ++ offset_name ++ " < 1 < needed");
        return null;
    }
    return .{ .env = env, .ref = ref, .base = env.*.*.GetIntArrayElements.?(env, ref, null) orelse return null, .offset = @intCast(offset), .len = @intCast(size - offset) };
}
fn attributes(env: Env, ref: jni.jintArray, offset: i32, comptime offset_name: []const u8, optional: bool) ?IntArray {
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    if (ref != null and offset >= 0 and offset >= env.*.*.GetArrayLength.?(env, ref)) {
        invalid(env, "attrib_list must contain EGL_NONE!");
        return null;
    }
    const result = intArray(env, ref, offset, "attrib_list", offset_name, optional, 0) orelse return null;
    if (result.ptr()) |p| {
        var index: usize = 0;
        while (index < result.len) : (index += 2) if (p[index] == none) return result;
        result.deinit(false);
        // Enforce a key-position terminator so a malformed attribute value cannot
        // make the driver walk beyond the Java array (undefined in the pinned JNI).
        invalid(env, "attrib_list must contain EGL_NONE!");
        return null;
    }
    return result;
}
fn getError(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return backend.getError();
}
fn getDisplay(env: Env, _: jni.jclass, native: jni.jlong) callconv(.c) jni.jobject {
    const display = if (native == 0) backend.defaultDisplay(get_gbm_device()) else eglGetDisplay(pointer(native));
    return wrapHandle(env, .display, display);
}
fn getDisplayInt(env: Env, cls: jni.jclass, native: jni.jint) callconv(.c) jni.jobject {
    if (native != 0) {
        throwNamed(env, "java/lang/UnsupportedOperationException", "eglGetDisplay");
        return null;
    }
    return getDisplay(env, cls, native);
}
fn initialize(env: Env, _: jni.jclass, dpy: jni.jobject, major: jni.jintArray, major_offset: jni.jint, minor: jni.jintArray, minor_offset: jni.jint) callconv(.c) jni.jboolean {
    const display = handle(env, .display, dpy);
    const a = intArray(env, major, major_offset, "major", "majorOffset", true, 1) orelse return jni.JNI_FALSE;
    defer a.deinit(true);
    const b = intArray(env, minor, minor_offset, "minor", "minorOffset", true, 1) orelse return jni.JNI_FALSE;
    defer b.deinit(true);
    return boolean(eglInitialize(display, a.one(), b.one()));
}
fn queryString(env: Env, _: jni.jclass, dpy: jni.jobject, name: jni.jint) callconv(.c) jni.jstring {
    const display = handle(env, .display, dpy);
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    const string = eglQueryString(display, name) orelse return null;
    return env.*.*.NewStringUTF.?(env, string);
}

fn configurations(env: Env, display: Handle, attribs: ?[*]const i32, choose: bool, configs: jni.jobjectArray, offset: i32, size: i32, count: jni.jintArray, count_offset: i32) jni.jboolean {
    var values: ?[]Handle = null;
    defer if (values) |v| alloc.free(v);
    if (configs != null) {
        if (offset < 0) {
            invalid(env, "configsOffset < 0");
            return jni.JNI_FALSE;
        }
        const remaining = env.*.*.GetArrayLength.?(env, configs) - offset;
        if (remaining < size or remaining < 0) {
            invalid(env, "length - configsOffset < config_size < needed");
            return jni.JNI_FALSE;
        }
        values = alloc.alloc(Handle, @intCast(@max(size, 1))) catch {
            throwNamed(env, "java/lang/OutOfMemoryError", "EGLConfig array");
            return jni.JNI_FALSE;
        };
        @memset(values.?, null);
    }
    const out = intArray(env, count, count_offset, "num_config", "num_configOffset", false, 1) orelse return jni.JNI_FALSE;
    defer out.deinit(true);
    const ptr = if (values) |v| v.ptr else null;
    const result = if (choose) backend.chooseConfig(display, attribs, ptr, size, out.one().?) else eglGetConfigs(display, ptr, size, out.one().?);
    // Only returned configs have defined handles. The pinned generated JNI also
    // copies uninitialized tail slots, which cannot be reproduced safely.
    if (result != 0) if (values) |v| {
        const written: usize = @intCast(@max(0, @min(size, out.one().?.*)));
        for (v[0..written], 0..) |value, i| {
            const config = wrapHandle(env, .config, value) orelse return jni.JNI_FALSE;
            defer env.*.*.DeleteLocalRef.?(env, config);
            env.*.*.SetObjectArrayElement.?(env, configs, offset + @as(i32, @intCast(i)), config);
            if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
        }
    };
    return boolean(result);
}
fn getConfigs(env: Env, _: jni.jclass, dpy: jni.jobject, configs: jni.jobjectArray, offset: jni.jint, size: jni.jint, count: jni.jintArray, count_offset: jni.jint) callconv(.c) jni.jboolean {
    const display = handle(env, .display, dpy);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return configurations(env, display, null, false, configs, offset, size, count, count_offset);
}
fn chooseConfig(env: Env, _: jni.jclass, dpy: jni.jobject, attribs: jni.jintArray, attrib_offset: jni.jint, configs: jni.jobjectArray, offset: jni.jint, size: jni.jint, count: jni.jintArray, count_offset: jni.jint) callconv(.c) jni.jboolean {
    const display = handle(env, .display, dpy);
    const attrs = attributes(env, attribs, attrib_offset, "attrib_listOffset", false) orelse return jni.JNI_FALSE;
    defer attrs.deinit(false);
    return configurations(env, display, attrs.ptr(), true, configs, offset, size, count, count_offset);
}
fn queryValue(env: Env, dpy: jni.jobject, target: jni.jobject, kind: Kind, attribute: jni.jint, out: jni.jintArray, offset: jni.jint, comptime native: anytype) jni.jboolean {
    const display = handle(env, .display, dpy);
    const object_handle = handle(env, kind, target);
    const values = intArray(env, out, offset, "value", "offset", false, 1) orelse return jni.JNI_FALSE;
    defer values.deinit(true);
    return boolean(native(display, object_handle, attribute, values.one().?));
}
fn getConfigAttrib(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, attribute: jni.jint, out: jni.jintArray, offset: jni.jint) callconv(.c) jni.jboolean {
    return queryValue(env, dpy, config, .config, attribute, out, offset, backend.getConfigAttrib);
}
fn queryHostedSurface(display: Handle, surface: Handle, attribute: i32, value: *i32) u32 {
    return backend.querySurface(frameworkIo(), display, surface, attribute, value);
}
fn querySurface(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject, attribute: jni.jint, out: jni.jintArray, offset: jni.jint) callconv(.c) jni.jboolean {
    return queryValue(env, dpy, surface, .surface, attribute, out, offset, queryHostedSurface);
}
fn queryContext(env: Env, _: jni.jclass, dpy: jni.jobject, context: jni.jobject, attribute: jni.jint, out: jni.jintArray, offset: jni.jint) callconv(.c) jni.jboolean {
    return queryValue(env, dpy, context, .context, attribute, out, offset, eglQueryContext);
}
fn createPbufferSurface(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, attribs: jni.jintArray, offset: jni.jint) callconv(.c) jni.jobject {
    const display = handle(env, .display, dpy);
    const cfg = handle(env, .config, config);
    const attrs = attributes(env, attribs, offset, "offset", true) orelse return null;
    defer attrs.deinit(false);
    return wrapHandle(env, .surface, backend.createPbuffer(frameworkIo(), display, cfg, attrs.ptr()));
}
fn createContext(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, shared: jni.jobject, attribs: jni.jintArray, offset: jni.jint) callconv(.c) jni.jobject {
    const display = handle(env, .display, dpy);
    const cfg = handle(env, .config, config);
    const share = handle(env, .context, shared);
    const attrs = attributes(env, attribs, offset, "offset", false) orelse return null;
    defer attrs.deinit(false);
    return wrapHandle(env, .context, eglCreateContext(display, cfg, share, attrs.ptr()));
}
fn createClientPbuffer(env: Env, _: jni.jclass, dpy: jni.jobject, buftype: jni.jint, buffer: jni.jlong, config: jni.jobject, attribs: jni.jintArray, offset: jni.jint) callconv(.c) jni.jobject {
    const display = handle(env, .display, dpy);
    const cfg = handle(env, .config, config);
    const attrs = attributes(env, attribs, offset, "offset", true) orelse return null;
    defer attrs.deinit(false);
    return wrapHandle(env, .surface, eglCreatePbufferFromClientBuffer(display, @bitCast(buftype), pointer(buffer), cfg, attrs.ptr()));
}
fn createClientPbufferInt(env: Env, cls: jni.jclass, dpy: jni.jobject, buftype: jni.jint, buffer: jni.jint, config: jni.jobject, attribs: jni.jintArray, offset: jni.jint) callconv(.c) jni.jobject {
    if (@sizeOf(usize) != @sizeOf(u32)) {
        throwNamed(env, "java/lang/UnsupportedOperationException", "eglCreatePbufferFromClientBuffer");
        return null;
    }
    return createClientPbuffer(env, cls, dpy, buftype, buffer, config, attribs, offset);
}
fn createPixmap(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jobject, _: jni.jint, _: jni.jintArray, _: jni.jint) callconv(.c) jni.jobject {
    throwNamed(env, "java/lang/UnsupportedOperationException", "eglCreatePixmapSurface");
    return null;
}
fn copyBuffers(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jobject, _: jni.jint) callconv(.c) jni.jboolean {
    throwNamed(env, "java/lang/UnsupportedOperationException", "eglCopyBuffers");
    return jni.JNI_FALSE;
}
fn unsupportedWindow(env: Env) void {
    const cls = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method = env.*.*.GetStaticMethodID.?(env, cls, "unsupported", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;") orelse return;
    const owner = env.*.*.NewStringUTF.?(env, "android.opengl.EGL14") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, owner);
    const name = env.*.*.NewStringUTF.?(env, "eglCreateWindowSurface") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, name);
    const detail = env.*.*.NewStringUTF.?(env, "Android native-window EGL presentation requires a hosted compositor adapter") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, detail);
    const args = [_]jni.jvalue{ .{ .l = owner }, .{ .l = name }, .{ .l = detail } };
    const exception = env.*.*.CallStaticObjectMethodA.?(env, cls, method, &args) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, exception);
    _ = env.*.*.Throw.?(env, @ptrCast(exception));
}
fn createWindowSurface(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, window: jni.jobject, attribs: jni.jintArray, offset: jni.jint) callconv(.c) jni.jobject {
    _ = handle(env, .display, dpy);
    _ = handle(env, .config, config);
    const attrs = attributes(env, attribs, offset, "offset", true) orelse return null;
    defer attrs.deinit(false);
    const message = "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface";
    if (window == null) {
        invalid(env, message);
        return null;
    }
    const cls = env.*.*.GetObjectClass.?(env, window) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const valid = env.*.*.GetMethodID.?(env, cls, "isValid", "()Z") orelse return null;
    if (env.*.*.CallBooleanMethodA.?(env, window, valid, null) != jni.JNI_TRUE) {
        invalid(env, message);
        return null;
    }
    // Mesa accepts wl_egl_window/GBM objects, not Android ANativeWindow pointers.
    // Do not pass one ABI's object into the other or pretend a pbuffer is a window.
    unsupportedWindow(env);
    return null;
}
fn createWindowTexture(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, _: jni.jobject, attribs: jni.jintArray, offset: jni.jint) callconv(.c) jni.jobject {
    _ = handle(env, .display, dpy);
    _ = handle(env, .config, config);
    const attrs = attributes(env, attribs, offset, "offset", false) orelse return null;
    defer attrs.deinit(false);
    // The current SurfaceTexture is a token without an IGraphicBufferProducer.
    // Pinned JNI rejects that absent producer before asking EGL for a surface.
    invalid(env, "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface");
    return null;
}

fn terminate(env: Env, _: jni.jclass, dpy: jni.jobject) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.terminate(frameworkIo(), dpy_handle));
}

fn destroySurface(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const surface_handle = handle(env, .surface, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.destroySurface(frameworkIo(), dpy_handle, surface_handle));
}

fn bindAPI(_: Env, _: jni.jclass, api: jni.jint) callconv(.c) jni.jboolean {
    return boolean(eglBindAPI(@as(u32, @bitCast(api))));
}

fn queryAPI(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return @bitCast(eglQueryAPI());
}

fn waitClient(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    return boolean(eglWaitClient());
}

fn releaseThread(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    return boolean(backend.releaseThread(frameworkIo()));
}

fn surfaceAttrib(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject, attribute: jni.jint, value: jni.jint) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const surface_handle = handle(env, .surface, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.surfaceAttrib(frameworkIo(), dpy_handle, surface_handle, attribute, value));
}

fn bindTexImage(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject, buffer: jni.jint) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const surface_handle = handle(env, .surface, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(eglBindTexImage(dpy_handle, surface_handle, buffer));
}

fn releaseTexImage(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject, buffer: jni.jint) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const surface_handle = handle(env, .surface, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(eglReleaseTexImage(dpy_handle, surface_handle, buffer));
}

fn swapInterval(env: Env, _: jni.jclass, dpy: jni.jobject, interval: jni.jint) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(eglSwapInterval(dpy_handle, interval));
}

fn destroyContext(env: Env, _: jni.jclass, dpy: jni.jobject, context: jni.jobject) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const context_handle = handle(env, .context, context);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(eglDestroyContext(dpy_handle, context_handle));
}

fn makeCurrent(env: Env, _: jni.jclass, dpy: jni.jobject, draw: jni.jobject, read: jni.jobject, context: jni.jobject) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const draw_handle = handle(env, .surface, draw);
    const read_handle = handle(env, .surface, read);
    const context_handle = handle(env, .context, context);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.makeCurrent(frameworkIo(), dpy_handle, draw_handle, read_handle, context_handle));
}

fn getCurrentContext(env: Env, _: jni.jclass) callconv(.c) jni.jobject {
    return wrapHandle(env, .context, eglGetCurrentContext());
}

fn getCurrentSurface(env: Env, _: jni.jclass, which: jni.jint) callconv(.c) jni.jobject {
    return wrapHandle(env, .surface, eglGetCurrentSurface(which));
}

fn getCurrentDisplay(env: Env, _: jni.jclass) callconv(.c) jni.jobject {
    return wrapHandle(env, .display, eglGetCurrentDisplay());
}

fn waitGL(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    return boolean(eglWaitGL());
}

fn waitNative(_: Env, _: jni.jclass, engine: jni.jint) callconv(.c) jni.jboolean {
    return boolean(eglWaitNative(engine));
}

fn swapBuffers(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const surface_handle = handle(env, .surface, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.swapBuffers(frameworkIo(), dpy_handle, surface_handle));
}

fn nativeMethod(name: [*:0]const u8, signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env, platform: *std.DynLib) !void {
    get_gbm_device = platform.lookup(GetGbmDevice, "azk_hardware_buffer_gbm_device") orelse return error.NoMethod;
    const cls = env.*.*.FindClass.?(env, "android/opengl/EGL14") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        nativeMethod("_nativeClassInit", "()V", &nativeClassInit),
        nativeMethod("eglGetError", "()I", &getError),
        nativeMethod("eglGetDisplay", "(I)Landroid/opengl/EGLDisplay;", &getDisplayInt),
        nativeMethod("eglGetDisplay", "(J)Landroid/opengl/EGLDisplay;", &getDisplay),
        nativeMethod("eglInitialize", "(Landroid/opengl/EGLDisplay;[II[II)Z", &initialize),
        nativeMethod("eglTerminate", "(Landroid/opengl/EGLDisplay;)Z", &terminate),
        nativeMethod("eglQueryString", "(Landroid/opengl/EGLDisplay;I)Ljava/lang/String;", &queryString),
        nativeMethod("eglGetConfigs", "(Landroid/opengl/EGLDisplay;[Landroid/opengl/EGLConfig;II[II)Z", &getConfigs),
        nativeMethod("eglChooseConfig", "(Landroid/opengl/EGLDisplay;[II[Landroid/opengl/EGLConfig;II[II)Z", &chooseConfig),
        nativeMethod("eglGetConfigAttrib", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;I[II)Z", &getConfigAttrib),
        nativeMethod("_eglCreateWindowSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Ljava/lang/Object;[II)Landroid/opengl/EGLSurface;", &createWindowSurface),
        nativeMethod("_eglCreateWindowSurfaceTexture", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Ljava/lang/Object;[II)Landroid/opengl/EGLSurface;", &createWindowTexture),
        nativeMethod("eglCreatePbufferSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;[II)Landroid/opengl/EGLSurface;", &createPbufferSurface),
        nativeMethod("eglCreatePixmapSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;I[II)Landroid/opengl/EGLSurface;", &createPixmap),
        nativeMethod("eglDestroySurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z", &destroySurface),
        nativeMethod("eglQuerySurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I[II)Z", &querySurface),
        nativeMethod("eglBindAPI", "(I)Z", &bindAPI),
        nativeMethod("eglQueryAPI", "()I", &queryAPI),
        nativeMethod("eglWaitClient", "()Z", &waitClient),
        nativeMethod("eglReleaseThread", "()Z", &releaseThread),
        nativeMethod("eglCreatePbufferFromClientBuffer", "(Landroid/opengl/EGLDisplay;IILandroid/opengl/EGLConfig;[II)Landroid/opengl/EGLSurface;", &createClientPbufferInt),
        nativeMethod("eglCreatePbufferFromClientBuffer", "(Landroid/opengl/EGLDisplay;IJLandroid/opengl/EGLConfig;[II)Landroid/opengl/EGLSurface;", &createClientPbuffer),
        nativeMethod("eglSurfaceAttrib", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;II)Z", &surfaceAttrib),
        nativeMethod("eglBindTexImage", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I)Z", &bindTexImage),
        nativeMethod("eglReleaseTexImage", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I)Z", &releaseTexImage),
        nativeMethod("eglSwapInterval", "(Landroid/opengl/EGLDisplay;I)Z", &swapInterval),
        nativeMethod("eglCreateContext", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Landroid/opengl/EGLContext;[II)Landroid/opengl/EGLContext;", &createContext),
        nativeMethod("eglDestroyContext", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLContext;)Z", &destroyContext),
        nativeMethod("eglMakeCurrent", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;Landroid/opengl/EGLSurface;Landroid/opengl/EGLContext;)Z", &makeCurrent),
        nativeMethod("eglGetCurrentContext", "()Landroid/opengl/EGLContext;", &getCurrentContext),
        nativeMethod("eglGetCurrentSurface", "(I)Landroid/opengl/EGLSurface;", &getCurrentSurface),
        nativeMethod("eglGetCurrentDisplay", "()Landroid/opengl/EGLDisplay;", &getCurrentDisplay),
        nativeMethod("eglQueryContext", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLContext;I[II)Z", &queryContext),
        nativeMethod("eglWaitGL", "()Z", &waitGL),
        nativeMethod("eglWaitNative", "(I)Z", &waitNative),
        nativeMethod("eglSwapBuffers", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z", &swapBuffers),
        nativeMethod("eglCopyBuffers", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I)Z", &copyBuffers),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
