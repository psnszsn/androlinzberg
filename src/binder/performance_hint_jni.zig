//! Pinned PerformanceHintManager JNI / APerformanceHintManager::create boundary.
//! The original libandroid client needs Android IHintManager, Power HAL and FMQ.
//! This host has none of those services: acquisition follows its nullptr path,
//! leaving stock Java to throw ServiceNotFoundException. No handles are issued.
//! Hidden constructors/reflection cannot turn fabricated handles into working
//! sessions; reject them explicitly instead of silently accepting power hints.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;

fn acquire(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return 0;
}

fn unavailable(env: Env) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, "java/lang/IllegalStateException") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, "No performance_hint service or native hint session");
}

fn rate(env: Env, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jlong {
    unavailable(env);
    return 0;
}
fn create(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jintArray, _: jni.jlong) callconv(.c) jni.jlong {
    unavailable(env);
    return 0;
}
fn threadIds(env: Env, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jintArray {
    unavailable(env);
    return null;
}
fn duration(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jlong) callconv(.c) void {
    unavailable(env);
}
fn close(env: Env, _: jni.jclass, _: jni.jlong) callconv(.c) void {
    unavailable(env);
}
fn hint(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jint) callconv(.c) void {
    unavailable(env);
}
fn threads(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jintArray) callconv(.c) void {
    unavailable(env);
}
fn efficiency(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jboolean) callconv(.c) void {
    unavailable(env);
}
fn work(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jlong, _: jni.jlong, _: jni.jlong, _: jni.jlong) callconv(.c) void {
    unavailable(env);
}

fn method(name: [*:0]const u8, signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}

pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/os/PerformanceHintManager") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("nativeAcquireManager", "()J", &acquire),
        method("nativeGetPreferredUpdateRateNanos", "(J)J", &rate),
        method("nativeCreateSession", "(J[IJ)J", &create),
        method("nativeGetThreadIds", "(J)[I", &threadIds),
        method("nativeUpdateTargetWorkDuration", "(JJ)V", &duration),
        method("nativeReportActualWorkDuration", "(JJ)V", &duration),
        method("nativeCloseSession", "(J)V", &close),
        method("nativeSendHint", "(JI)V", &hint),
        method("nativeSetThreads", "(J[I)V", &threads),
        method("nativeSetPreferPowerEfficiency", "(JZ)V", &efficiency),
        method("nativeReportActualWorkDuration", "(JJJJJ)V", &work),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
