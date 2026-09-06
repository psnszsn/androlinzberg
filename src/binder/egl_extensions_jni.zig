//! AOSP android_opengl_EGLExt.cpp's handle/exception contract over Mesa's
//! extension dispatch. The stock JNI imports the unavailable Android GLConsumer
//! implementation and calls Android-only libEGL exports; GLVND exposes these
//! extensions through eglGetProcAddress. Java and fence ownership stay stock.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const DupFence = *const fn (Handle, Handle) callconv(.c) i32;
const PresentationTime = *const fn (Handle, Handle, i64) callconv(.c) u32;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;

const Cached = struct { class: jni.jclass, handle: jni.jmethodID };
var classes: [3]Cached = undefined;
var dup_fence: ?DupFence = null;
var presentation_time: ?PresentationTime = null;

fn init(env: Env, _: jni.jclass) callconv(.c) void {
    inline for (.{ "EGLDisplay", "EGLSurface", "EGLSync" }, 0..) |name, i| {
        const local = env.*.*.FindClass.?(env, "android/opengl/" ++ name) orelse return;
        defer env.*.*.DeleteLocalRef.?(env, local);
        classes[i] = .{
            .class = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return),
            .handle = env.*.*.GetMethodID.?(env, local, "getNativeHandle", "()J") orelse return,
        };
    }
    dup_fence = @ptrCast(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    presentation_time = @ptrCast(eglGetProcAddress("eglPresentationTimeANDROID"));
}

fn handle(env: Env, index: usize, object: jni.jobject) Handle {
    if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    if (object == null) {
        const cls = env.*.*.FindClass.?(env, "java/lang/IllegalArgumentException") orelse return null;
        defer env.*.*.DeleteLocalRef.?(env, cls);
        _ = env.*.*.ThrowNew.?(env, cls, "Object is set to null.");
        return null;
    }
    const value = env.*.*.CallLongMethodA.?(env, object, classes[index].handle, null);
    return @ptrFromInt(@as(usize, @bitCast(value)));
}

fn unsupported(env: Env, member: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method = env.*.*.GetStaticMethodID.?(env, cls, "unsupported", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;") orelse return;
    const owner = env.*.*.NewStringUTF.?(env, "android.opengl.EGLExt") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, owner);
    const name = env.*.*.NewStringUTF.?(env, member) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, name);
    const detail = env.*.*.NewStringUTF.?(env, "The EGL driver does not expose this Android extension") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, detail);
    const args = [_]jni.jvalue{ .{ .l = owner }, .{ .l = name }, .{ .l = detail } };
    const exception = env.*.*.CallStaticObjectMethodA.?(env, cls, method, &args) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, exception);
    _ = env.*.*.Throw.?(env, @ptrCast(exception));
}

fn dupFence(env: Env, _: jni.jclass, display: jni.jobject, sync: jni.jobject) callconv(.c) jni.jint {
    const dpy = handle(env, 0, display);
    const fence = handle(env, 2, sync);
    if (env.*.*.ExceptionCheck.?(env) != 0) return -1;
    const operation = dup_fence orelse {
        unsupported(env, "eglDupNativeFenceFDANDROID");
        return -1;
    };
    // The returned descriptor transfers directly to stock SyncFence's native
    // Fence owner through the stock Java ParcelFileDescriptor.adoptFd path.
    return operation(dpy, fence);
}

fn presentationTime(env: Env, _: jni.jclass, display: jni.jobject, surface: jni.jobject, time: jni.jlong) callconv(.c) jni.jboolean {
    const dpy = handle(env, 0, display);
    const drawable = handle(env, 1, surface);
    if (env.*.*.ExceptionCheck.?(env) != 0) return jni.JNI_FALSE;
    const operation = presentation_time orelse {
        unsupported(env, "eglPresentationTimeANDROID");
        return jni.JNI_FALSE;
    };
    return if (operation(dpy, drawable, time) == 0) jni.JNI_FALSE else jni.JNI_TRUE;
}

pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/opengl/EGLExt") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        .{ .name = @constCast("_nativeClassInit"), .signature = @constCast("()V"), .fnPtr = @ptrCast(@constCast(&init)) },
        .{ .name = @constCast("eglDupNativeFenceFDANDROIDImpl"), .signature = @constCast("(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSync;)I"), .fnPtr = @ptrCast(@constCast(&dupFence)) },
        .{ .name = @constCast("eglPresentationTimeANDROID"), .signature = @constCast("(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;J)Z"), .fnPtr = @ptrCast(@constCast(&presentationTime)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
