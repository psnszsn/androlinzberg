//! JNI port of pinned com_google_android_gles_jni_EGLImpl.cpp. The original
//! depends on Android GLConsumer/ANativeWindow and Android EGL display state.
//! Keep its Java objects, validation and EGL 1.0 reporting; the shared EGL
//! backend owns host display and offscreen surface lifetime.
const std = @import("std");
const backend = @import("egl_backend.zig");
const frameworkIo = @import("ndk.zig").frameworkIo;
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const alloc = std.heap.c_allocator;
const Kind = enum { display, context, surface, config };
var fields: [4]jni.jfieldID = undefined;
var config_class: jni.jclass = null;
var config_constructor: jni.jmethodID = null;
const GetGbmDevice = *const fn () callconv(.c) Handle;
var get_gbm_device: GetGbmDevice = undefined;
extern "EGL" fn eglGetError() i32;
extern "EGL" fn eglInitialize(Handle, ?*i32, ?*i32) u32;
extern "EGL" fn eglTerminate(Handle) u32;
extern "EGL" fn eglQueryString(Handle, i32) ?[*:0]const u8;
extern "EGL" fn eglGetConfigs(Handle, ?[*]Handle, i32, *i32) u32;
extern "EGL" fn eglChooseConfig(Handle, ?[*]const i32, ?[*]Handle, i32, *i32) u32;
extern "EGL" fn eglGetConfigAttrib(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglDestroySurface(Handle, Handle) u32;
extern "EGL" fn eglQuerySurface(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglReleaseThread() u32;
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
    return pointer(env.*.*.GetLongField.?(env, object, fields[@backingInt(kind)]));
}
fn init(env: Env, _: jni.jclass) callconv(.c) void {
    inline for (.{ "EGLDisplay", "EGLContext", "EGLSurface", "EGLConfig" }, 0..) |name, i| {
        const local = env.*.*.FindClass.?(env, "com/google/android/gles_jni/" ++ name ++ "Impl") orelse return;
        defer env.*.*.DeleteLocalRef.?(env, local);
        fields[i] = env.*.*.GetFieldID.?(env, local, "m" ++ name, "J") orelse return;
        if (i == 3) {
            config_class = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return);
            config_constructor = env.*.*.GetMethodID.?(env, local, "<init>", "(J)V") orelse return;
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
fn attributes(env: Env, ref: jni.jintArray) ?IntArray {
    const result = intArray(env, ref, 0, "attrib_list", "offset", true, 0) orelse return null;
    if (result.ptr()) |p| {
        // Pinned JNI requires the last entry to be EGL_NONE. Also require a
        // key-position terminator before handing the array to the host driver.
        if (result.len > 0 and p[result.len - 1] == 0x3038) {
            var index: usize = 0;
            while (index < result.len) : (index += 2) if (p[index] == 0x3038) return result;
        }
        result.deinit(false);
        invalid(env, "attrib_list must contain EGL_NONE!");
        return null;
    }
    return result;
}
fn initialize(env: Env, _: jni.jobject, dpy: jni.jobject, version: jni.jintArray) callconv(.c) jni.jboolean {
    const display = handle(env, .display, dpy);
    const output = intArray(env, version, 0, "major_minor", "offset", true, 2) orelse return jni.JNI_FALSE;
    defer output.deinit(true);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    const result = backend.initialize(frameworkIo(), display, null, null);
    if (result != 0) if (output.ptr()) |p| {
        p[0] = 1;
        p[1] = 0;
    };
    return boolean(result);
}
fn initCount(env: Env, _: jni.jclass, dpy: jni.jobject) callconv(.c) jni.jint {
    if (dpy == null) return 0;
    return backend.initCount(frameworkIo(), handle(env, .display, dpy));
}
fn getDisplay(_: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jlong {
    return @bitCast(@intFromPtr(backend.defaultDisplay(get_gbm_device())));
}
fn getError(_: Env, _: jni.jobject) callconv(.c) jni.jint {
    return backend.getError();
}
fn getCurrentContext(_: Env, _: jni.jobject) callconv(.c) jni.jlong {
    return @bitCast(@intFromPtr(eglGetCurrentContext()));
}
fn getCurrentDisplay(_: Env, _: jni.jobject) callconv(.c) jni.jlong {
    return @bitCast(@intFromPtr(eglGetCurrentDisplay()));
}
fn getCurrentSurface(env: Env, _: jni.jobject, which: jni.jint) callconv(.c) jni.jlong {
    if (which != 0x3059 and which != 0x305a) {
        invalid(env, "Expected EGL_READ or EGL_DRAW");
        return 0;
    }
    return @bitCast(@intFromPtr(eglGetCurrentSurface(which)));
}
fn queryString(env: Env, _: jni.jclass, dpy: jni.jobject, name: jni.jint) callconv(.c) jni.jstring {
    const display = handle(env, .display, dpy);
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    const string = eglQueryString(display, name) orelse return null;
    return env.*.*.NewStringUTF.?(env, string);
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

fn releaseThread(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    return boolean(backend.releaseThread(frameworkIo()));
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

fn waitGL(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    return boolean(eglWaitGL());
}

fn swapBuffers(env: Env, _: jni.jclass, dpy: jni.jobject, surface: jni.jobject) callconv(.c) jni.jboolean {
    const dpy_handle = handle(env, .display, dpy);
    const surface_handle = handle(env, .surface, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.swapBuffers(frameworkIo(), dpy_handle, surface_handle));
}

fn waitNative(_: Env, _: jni.jobject, engine: jni.jint, _: jni.jobject) callconv(.c) jni.jboolean {
    return boolean(eglWaitNative(engine));
}
fn copyBuffers(env: Env, _: jni.jobject, dpy: jni.jobject, surface: jni.jobject, pixmap: jni.jobject) callconv(.c) jni.jboolean {
    if (dpy == null or surface == null or pixmap == null) invalid(env, "Object is set to null.");
    return jni.JNI_FALSE; // The pinned implementation returns EGL_FALSE.
}
fn createPixmap(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jintArray) callconv(.c) void {
    throwNamed(env, "java/lang/UnsupportedOperationException", "eglCreatePixmapSurface");
}
fn createContext(env: Env, _: jni.jobject, dpy: jni.jobject, config: jni.jobject, shared: jni.jobject, attrs: jni.jintArray) callconv(.c) jni.jlong {
    const display = handle(env, .display, dpy);
    const cnf = handle(env, .config, config);
    const ctx = handle(env, .context, shared);
    const values = attributes(env, attrs) orelse return 0;
    defer values.deinit(false);
    if (env.*.*.ExceptionCheck.?(env) != 0) return 0;
    return @bitCast(@intFromPtr(eglCreateContext(display, cnf, ctx, values.ptr())));
}
fn createPbuffer(env: Env, _: jni.jobject, dpy: jni.jobject, config: jni.jobject, attrs: jni.jintArray) callconv(.c) jni.jlong {
    const display = handle(env, .display, dpy);
    const cnf = handle(env, .config, config);
    const values = attributes(env, attrs) orelse return 0;
    defer values.deinit(false);
    if (env.*.*.ExceptionCheck.?(env) != 0) return 0;
    return @bitCast(@intFromPtr(backend.createPbuffer(frameworkIo(), display, cnf, values.ptr())));
}
fn unsupportedWindow(env: Env) void {
    const cls = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method = env.*.*.GetStaticMethodID.?(env, cls, "unsupported", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;") orelse return;
    const owner = env.*.*.NewStringUTF.?(env, "com.google.android.gles_jni.EGLImpl") orelse return;
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

fn createWindowSurface(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, window: jni.jobject, attribs: jni.jintArray) callconv(.c) jni.jlong {
    _ = handle(env, .display, dpy);
    _ = handle(env, .config, config);
    const attrs = attributes(env, attribs) orelse return 0;
    defer attrs.deinit(false);
    const message = "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface";
    if (window == null) {
        invalid(env, message);
        return 0;
    }
    const cls = env.*.*.GetObjectClass.?(env, window) orelse return 0;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const valid = env.*.*.GetMethodID.?(env, cls, "isValid", "()Z") orelse return 0;
    if (env.*.*.CallBooleanMethodA.?(env, window, valid, null) != jni.JNI_TRUE) {
        invalid(env, message);
        return 0;
    }
    // Mesa accepts wl_egl_window/GBM objects, not Android ANativeWindow pointers.
    // Do not pass one ABI's object into the other or pretend a pbuffer is a window.
    unsupportedWindow(env);
    return 0;
}

fn createWindowTexture(env: Env, _: jni.jclass, dpy: jni.jobject, config: jni.jobject, _: jni.jobject, attribs: jni.jintArray) callconv(.c) jni.jlong {
    _ = handle(env, .display, dpy);
    _ = handle(env, .config, config);
    const attrs = attributes(env, attribs) orelse return 0;
    defer attrs.deinit(false);
    // The current SurfaceTexture is a token without an IGraphicBufferProducer.
    // Pinned JNI rejects that absent producer before asking EGL for a surface.
    invalid(env, "Make sure the SurfaceTexture is valid");
    return 0;
}

fn queryContext(env: Env, _: jni.jobject, dpy: jni.jobject, obj: jni.jobject, attribute: jni.jint, value: jni.jintArray) callconv(.c) jni.jboolean {
    const h = handle(env, .display, dpy);
    const object = handle(env, .context, obj);
    const output = intArray(env, value, 0, "value", "offset", false, 1) orelse return jni.JNI_FALSE;
    defer output.deinit(true);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(eglQueryContext(h, object, attribute, output.one().?));
}
fn querySurface(env: Env, _: jni.jobject, dpy: jni.jobject, obj: jni.jobject, attribute: jni.jint, value: jni.jintArray) callconv(.c) jni.jboolean {
    const h = handle(env, .display, dpy);
    const object = handle(env, .surface, obj);
    const output = intArray(env, value, 0, "value", "offset", false, 1) orelse return jni.JNI_FALSE;
    defer output.deinit(true);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    return boolean(backend.querySurface(frameworkIo(), h, object, attribute, output.one().?));
}
fn getConfigAttrib(env: Env, _: jni.jobject, dpy: jni.jobject, obj: jni.jobject, attribute: jni.jint, value: jni.jintArray) callconv(.c) jni.jboolean {
    const h = handle(env, .display, dpy);
    const object = handle(env, .config, obj);
    const output = intArray(env, value, 0, "value", "offset", false, 1) orelse return jni.JNI_FALSE;
    defer output.deinit(true);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    var value_result: i32 = 0;
    const result = backend.getConfigAttrib(h, object, attribute, &value_result);
    if (result != 0) output.one().?.* = value_result;
    return boolean(result);
}
fn configs(env: Env, dpy: jni.jobject, attrs: ?[*]const i32, output: jni.jobjectArray, size: jni.jint, count: jni.jintArray, choose: bool) jni.jboolean {
    const display = handle(env, .display, dpy);
    const number = intArray(env, count, 0, "num_config", "offset", true, 1) orelse return jni.JNI_FALSE;
    defer number.deinit(true);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    if (output != null and (size < 0 or env.*.*.GetArrayLength.?(env, output) < size)) {
        invalid(env, "configs length < config_size");
        return jni.JNI_FALSE;
    }
    const capacity: usize = if (output == null) 0 else @intCast(size);
    const native = alloc.alloc(Handle, capacity) catch {
        throwNamed(env, "java/lang/OutOfMemoryError", "EGLConfig array");
        return jni.JNI_FALSE;
    };
    defer alloc.free(native);
    var returned: i32 = 0;
    const values = if (output == null) null else native.ptr;
    const result = if (choose) backend.chooseConfig(display, attrs, values, @intCast(capacity), &returned) else eglGetConfigs(display, values, @intCast(capacity), &returned);
    if (number.one()) |value| value.* = returned;
    if (result != 0 and output != null) {
        const used: usize = @intCast(@max(0, @min(returned, @as(i32, @intCast(capacity)))));
        for (native[0..used], 0..) |value, index| {
            const args = [_]jni.jvalue{.{ .j = @bitCast(@intFromPtr(value)) }};
            const config = env.*.*.NewObjectA.?(env, config_class, config_constructor, &args) orelse return jni.JNI_FALSE;
            defer env.*.*.DeleteLocalRef.?(env, config);
            env.*.*.SetObjectArrayElement.?(env, output, @intCast(index), config);
            if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
        }
    }
    return boolean(result);
}
fn chooseConfig(env: Env, _: jni.jobject, dpy: jni.jobject, attrs: jni.jintArray, output: jni.jobjectArray, size: jni.jint, count: jni.jintArray) callconv(.c) jni.jboolean {
    const values = attributes(env, attrs) orelse return jni.JNI_FALSE;
    defer values.deinit(false);
    return configs(env, dpy, values.ptr(), output, size, count, true);
}
fn getConfigs(env: Env, _: jni.jobject, dpy: jni.jobject, output: jni.jobjectArray, size: jni.jint, count: jni.jintArray) callconv(.c) jni.jboolean {
    return configs(env, dpy, null, output, size, count, false);
}
fn nativeMethod(name: [*:0]const u8, signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env, platform: *std.DynLib) !void {
    get_gbm_device = platform.lookup(GetGbmDevice, "azk_graphics_gbm_device") orelse return error.NoMethod;
    const cls = env.*.*.FindClass.?(env, "com/google/android/gles_jni/EGLImpl") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        nativeMethod("_nativeClassInit", "()V", &init),
        nativeMethod("eglWaitGL", "()Z", &waitGL),
        nativeMethod("eglInitialize", "(Ljavax/microedition/khronos/egl/EGLDisplay;[I)Z", &initialize),
        nativeMethod("eglQueryContext", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLContext;I[I)Z", &queryContext),
        nativeMethod("eglQuerySurface", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLSurface;I[I)Z", &querySurface),
        nativeMethod("eglReleaseThread", "()Z", &releaseThread),
        nativeMethod("getInitCount", "(Ljavax/microedition/khronos/egl/EGLDisplay;)I", &initCount),
        nativeMethod("eglChooseConfig", "(Ljavax/microedition/khronos/egl/EGLDisplay;[I[Ljavax/microedition/khronos/egl/EGLConfig;I[I)Z", &chooseConfig),
        nativeMethod("_eglCreateContext", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLConfig;Ljavax/microedition/khronos/egl/EGLContext;[I)J", &createContext),
        nativeMethod("eglGetConfigs", "(Ljavax/microedition/khronos/egl/EGLDisplay;[Ljavax/microedition/khronos/egl/EGLConfig;I[I)Z", &getConfigs),
        nativeMethod("eglTerminate", "(Ljavax/microedition/khronos/egl/EGLDisplay;)Z", &terminate),
        nativeMethod("eglCopyBuffers", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLSurface;Ljava/lang/Object;)Z", &copyBuffers),
        nativeMethod("eglWaitNative", "(ILjava/lang/Object;)Z", &waitNative),
        nativeMethod("eglGetError", "()I", &getError),
        nativeMethod("eglGetConfigAttrib", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLConfig;I[I)Z", &getConfigAttrib),
        nativeMethod("_eglGetDisplay", "(Ljava/lang/Object;)J", &getDisplay),
        nativeMethod("_eglGetCurrentContext", "()J", &getCurrentContext),
        nativeMethod("_eglGetCurrentDisplay", "()J", &getCurrentDisplay),
        nativeMethod("_eglGetCurrentSurface", "(I)J", &getCurrentSurface),
        nativeMethod("_eglCreatePbufferSurface", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLConfig;[I)J", &createPbuffer),
        nativeMethod("_eglCreatePixmapSurface", "(Ljavax/microedition/khronos/egl/EGLSurface;Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLConfig;Ljava/lang/Object;[I)V", &createPixmap),
        nativeMethod("_eglCreateWindowSurface", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLConfig;Ljava/lang/Object;[I)J", &createWindowSurface),
        nativeMethod("_eglCreateWindowSurfaceTexture", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLConfig;Ljava/lang/Object;[I)J", &createWindowTexture),
        nativeMethod("eglDestroyContext", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLContext;)Z", &destroyContext),
        nativeMethod("eglDestroySurface", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLSurface;)Z", &destroySurface),
        nativeMethod("eglMakeCurrent", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLSurface;Ljavax/microedition/khronos/egl/EGLSurface;Ljavax/microedition/khronos/egl/EGLContext;)Z", &makeCurrent),
        nativeMethod("eglQueryString", "(Ljavax/microedition/khronos/egl/EGLDisplay;I)Ljava/lang/String;", &queryString),
        nativeMethod("eglSwapBuffers", "(Ljavax/microedition/khronos/egl/EGLDisplay;Ljavax/microedition/khronos/egl/EGLSurface;)Z", &swapBuffers),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
