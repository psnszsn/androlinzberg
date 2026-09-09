//! Pinned android_app_Activity.cpp getDlWarning at the dynamic-loader boundary.
//! The hosted loader reports load failures immediately and has no bionic deferred
//! android_dlwarning journal. Match AOSP's null result when that journal is empty.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
fn getDlWarning(_: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return null;
}
// Pinned android_app_ActivityThread.cpp requires bionic's mallopt operations.
// glibc releases free heap pages with malloc_trim; a fresh host process has no
// inherited zygote-child profiling state to initialize.
extern "c" fn malloc_trim(pad: usize) c_int;
fn purgePendingResources(_: Env, _: jni.jclass) callconv(.c) void {
    _ = malloc_trim(0);
}
fn initZygoteChildHeapProfiling(_: Env, _: jni.jclass) callconv(.c) void {}

// Pinned com_android_internal_os_Zygote.cpp: all three capability probes use
// these values on non-aarch64. No zygote fork/process implementation is needed.
fn supportsTagging(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
    comptime {
        if (@import("builtin").cpu.arch != .x86_64) @compileError("implement the pinned architecture tagging probes");
    }
    return 0;
}
fn currentTaggingLevel(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return 0;
}

pub fn register(env: Env) !void {
    const zygote = env.*.*.FindClass.?(env, "com/android/internal/os/Zygote") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, zygote);
    const tagging_methods = [_]jni.JNINativeMethod{
        .{ .name = @constCast("nativeSupportsMemoryTagging"), .signature = @constCast("()Z"), .fnPtr = @ptrCast(@constCast(&supportsTagging)) },
        .{ .name = @constCast("nativeSupportsTaggedPointers"), .signature = @constCast("()Z"), .fnPtr = @ptrCast(@constCast(&supportsTagging)) },
        .{ .name = @constCast("nativeCurrentTaggingLevel"), .signature = @constCast("()I"), .fnPtr = @ptrCast(@constCast(&currentTaggingLevel)) },
    };
    if (env.*.*.RegisterNatives.?(env, zygote, &tagging_methods, tagging_methods.len) != 0) return error.RegisterFailed;
    const cls = env.*.*.FindClass.?(env, "android/app/Activity") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{.{
        .name = @constCast("getDlWarning"),
        .signature = @constCast("()Ljava/lang/String;"),
        .fnPtr = @ptrCast(@constCast(&getDlWarning)),
    }};
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
    const thread = env.*.*.FindClass.?(env, "android/app/ActivityThread") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, thread);
    const thread_methods = [_]jni.JNINativeMethod{
        .{ .name = @constCast("nPurgePendingResources"), .signature = @constCast("()V"), .fnPtr = @ptrCast(@constCast(&purgePendingResources)) },
        .{ .name = @constCast("nInitZygoteChildHeapProfiling"), .signature = @constCast("()V"), .fnPtr = @ptrCast(@constCast(&initZygoteChildHeapProfiling)) },
    };
    if (env.*.*.RegisterNatives.?(env, thread, &thread_methods, thread_methods.len) != 0) return error.RegisterFailed;
}
