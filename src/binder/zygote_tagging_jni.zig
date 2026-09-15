//! Architecture-specific probes retained at the hosted zygote runtime boundary.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;

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
}
