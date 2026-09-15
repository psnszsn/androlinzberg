//! AndroidRuntime RuntimeInit JNI / AppRuntime.onStarted. The launcher creates
//! a non-zygote ART process and owns its lifetime. The exit invocation hook uses
//! the C++ AndroidRuntime _exit boundary on either cleanup branch.
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const Env = [*c]jni.JNIEnv;
var bootstrap: jni.jclass = null;
var on_started: jni.jmethodID = null;

fn finishInit(env: Env, _: jni.jclass) callconv(.c) void {
    // Android already has OS credentials when AppRuntime starts Binder here.
    // Hosted credentials are claimed by BinderSession during target startup;
    // its first IPC then starts the reader/workers with that assigned identity.
    env.*.*.CallStaticVoidMethod.?(env, bootstrap, on_started);
    // The hosted Binder reader belongs to the launcher process. Its socket and
    // workers are released on process exit, including when main throws.
}

fn setExitWithoutCleanup(_: Env, _: jni.jclass, _: jni.jboolean) callconv(.c) void {
    // AppRuntime.onExit only stops IPC for the zygote (empty target class).
    // Hosted entries always have a target class; both values have the same
    // process-exit action. The launcher's VM exit hook avoids libc teardown.
}

fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}

pub fn register(env: Env) !void {
    const runtime = env.*.*.FindClass.?(env, "com/android/internal/os/RuntimeInit") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, runtime);
    const methods = [_]jni.JNINativeMethod{
        method("nativeFinishInit", "()V", &finishInit),
        method("nativeSetExitWithoutCleanup", "(Z)V", &setExitWithoutCleanup),
    };
    if (env.*.*.RegisterNatives.?(env, runtime, &methods, methods.len) != 0) return error.RegisterFailed;
    bootstrap = ndk.findGlobalClass(env, "dev/artzig/RuntimeBootstrap") orelse return error.NoClass;
    on_started = env.*.*.GetStaticMethodID.?(env, bootstrap, "onStarted", "()V") orelse return error.NoMethod;
}
