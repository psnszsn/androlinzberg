//! MemoryIntArray retains stock Java and native atomic access/lifetime code.
//! Only ashmem creation and mapping use the sealed Linux memfd host port.
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const Env = [*c]jni.JNIEnv;

var backend: jni.jclass = null;
var create_method: jni.jmethodID = null;
var open_method: jni.jmethodID = null;
var fd_field: jni.jfieldID = null;

fn create(env: Env, _: jni.jobject, name: jni.jstring, size: jni.jint) callconv(.c) jni.jint {
    return env.*.*.CallStaticIntMethod.?(env, backend, create_method, name, size);
}

fn open(env: Env, instance: jni.jobject, fd: jni.jint, owner: jni.jboolean) callconv(.c) jni.jlong {
    const address = env.*.*.CallStaticLongMethod.?(env, backend, open_method, fd, @as(c_int, owner));
    if (env.*.*.ExceptionOccurred.?(env)) |failure| {
        // The memfd port closes a failed construction's transferred descriptor.
        // Disarm the stock finalizer so it cannot close a subsequently reused FD.
        env.*.*.ExceptionClear.?(env);
        env.*.*.SetIntField.?(env, instance, fd_field, -1);
        _ = env.*.*.Throw.?(env, failure);
        env.*.*.DeleteLocalRef.?(env, failure);
    }
    return address;
}

pub fn register(env: Env) !void {
    if (backend != null) return;
    backend = ndk.findGlobalClass(env, "dev/artzig/MemoryIntArrayBackend") orelse return error.NoClass;
    create_method = env.*.*.GetStaticMethodID.?(env, backend, "create", "(Ljava/lang/String;I)I") orelse return error.NoMethod;
    open_method = env.*.*.GetStaticMethodID.?(env, backend, "open", "(IZ)J") orelse return error.NoMethod;
    const cls = env.*.*.FindClass.?(env, "android/util/MemoryIntArray") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    fd_field = env.*.*.GetFieldID.?(env, cls, "mFd", "I") orelse return error.NoField;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = @constCast("nativeCreate"), .signature = @constCast("(Ljava/lang/String;I)I"), .fnPtr = @ptrCast(@constCast(&create)) },
        .{ .name = @constCast("nativeOpen"), .signature = @constCast("(IZ)J"), .fnPtr = @ptrCast(@constCast(&open)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
