//! NetworkUtilsInternal JNI over the hosted libc/network service boundary.
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const Env = [*c]jni.JNIEnv;
var backend: jni.jclass = null;
var allow_method: jni.jmethodID = null;
var protect_fd_method: jni.jmethodID = null;
var protect_int_method: jni.jmethodID = null;

fn allow(env: Env, _: jni.jclass, allowed: jni.jboolean) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .z = allowed }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, allow_method, &args);
}
fn protectFd(env: Env, _: jni.jclass, fd: jni.jobject) callconv(.c) jni.jboolean {
    // AFileDescriptor_getFd is fatal on a null object in the pinned JNI helper.
    if (fd == null) {
        env.*.*.FatalError.?(env, "FileDescriptor is NULL");
        return jni.JNI_FALSE;
    }
    const args = [_]jni.jvalue{.{ .l = fd }};
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, protect_fd_method, &args);
}
fn protectInt(env: Env, _: jni.jclass, fd: jni.jint) callconv(.c) jni.jboolean {
    const args = [_]jni.jvalue{.{ .i = fd }};
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, protect_int_method, &args);
}
fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    backend = ndk.findGlobalClass(env, "dev/artzig/HostedProcessNetworking") orelse return error.NoClass;
    allow_method = env.*.*.GetStaticMethodID.?(env, backend, "setAllowNetworkingForProcess", "(Z)V") orelse return error.NoMethod;
    protect_fd_method = env.*.*.GetStaticMethodID.?(env, backend, "protectFromVpn", "(Ljava/io/FileDescriptor;)Z") orelse return error.NoMethod;
    protect_int_method = env.*.*.GetStaticMethodID.?(env, backend, "protectFromVpn", "(I)Z") orelse return error.NoMethod;
    const cls = env.*.*.FindClass.?(env, "com/android/internal/net/NetworkUtilsInternal") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("setAllowNetworkingForProcess", "(Z)V", &allow),
        method("protectFromVpn", "(Ljava/io/FileDescriptor;)Z", &protectFd),
        method("protectFromVpn", "(I)Z", &protectInt),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
