//! Pinned android_net_TrafficStats.cpp's two-method JNI surface. Java owns the
//! stock socket tagger; the host boundary validates descriptors and implements
//! NetdClient/FwmarkClient's absent-server result through existing Os primitives.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var backend: jni.jclass = null;
var tag_method: jni.jmethodID = null;
var untag_method: jni.jmethodID = null;

fn tag(env: Env, _: jni.jclass, fd: jni.jobject, value: jni.jint, uid: jni.jint) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = fd }, .{ .i = value }, .{ .i = uid } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, tag_method, &args);
}
fn untag(env: Env, _: jni.jclass, fd: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = fd }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, untag_method, &args);
}

pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "dev/artzig/HostedSocketAccounting") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    backend = @ptrCast(env.*.*.NewGlobalRef.?(env, cls) orelse return error.NoClass);
    tag_method = env.*.*.GetStaticMethodID.?(env, cls, "tagSocketFd", "(Ljava/io/FileDescriptor;II)I") orelse return error.NoMethod;
    untag_method = env.*.*.GetStaticMethodID.?(env, cls, "untagSocketFd", "(Ljava/io/FileDescriptor;)I") orelse return error.NoMethod;
    const traffic = env.*.*.FindClass.?(env, "android/net/TrafficStats") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, traffic);
    const methods = [_]jni.JNINativeMethod{
        .{ .name = @constCast("native_tagSocketFd"), .signature = @constCast("(Ljava/io/FileDescriptor;II)I"), .fnPtr = @ptrCast(@constCast(&tag)) },
        .{ .name = @constCast("native_untagSocketFd"), .signature = @constCast("(Ljava/io/FileDescriptor;)I"), .fnPtr = @ptrCast(@constCast(&untag)) },
    };
    if (env.*.*.RegisterNatives.?(env, traffic, &methods, methods.len) != 0) return error.RegisterFailed;
}
