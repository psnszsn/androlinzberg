//! Replace the stock JNI's ashmem allocation/duplication with Linux memfds.
//! All layout, mmap, feature-cache and atomic access JNI remains verbatim AOSP.
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const Env = [*c]jni.JNIEnv;

var backend: jni.jclass = null;
var create_method: jni.jmethodID = null;
var duplicate_method: jni.jmethodID = null;

fn create(env: Env, _: jni.jclass) callconv(.c) jni.jint {
    return env.*.*.CallStaticIntMethod.?(env, backend, create_method);
}

fn duplicateReadOnly(env: Env, _: jni.jclass, fd: jni.jint) callconv(.c) jni.jint {
    return env.*.*.CallStaticIntMethod.?(env, backend, duplicate_method, fd);
}

pub fn register(env: Env) !void {
    if (backend != null) return;
    backend = ndk.findGlobalClass(env, "dev/artzig/ApplicationSharedMemoryBackend") orelse return error.NoClass;
    const initialize = env.*.*.GetStaticMethodID.?(env, backend, "initialize", "()V") orelse return error.NoMethod;
    create_method = env.*.*.GetStaticMethodID.?(env, backend, "create", "()I") orelse return error.NoMethod;
    duplicate_method = env.*.*.GetStaticMethodID.?(env, backend, "duplicateReadOnly", "(I)I") orelse return error.NoMethod;
    // The island's stock registration precedes Binder JNI registration in each
    // launcher path. Initialize against its factory before replacing two ports.
    env.*.*.CallStaticVoidMethod.?(env, backend, initialize);
    if (env.*.*.ExceptionCheck.?(env) != 0) return error.InitializationFailed;
    const cls = env.*.*.FindClass.?(env, "com/android/internal/os/ApplicationSharedMemory") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        .{ .name = @constCast("nativeCreate"), .signature = @constCast("()I"), .fnPtr = @ptrCast(@constCast(&create)) },
        .{ .name = @constCast("nativeDupAsReadOnly"), .signature = @constCast("(I)I"), .fnPtr = @ptrCast(@constCast(&duplicateReadOnly)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
