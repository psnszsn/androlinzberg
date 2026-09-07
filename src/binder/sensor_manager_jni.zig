//! Pinned android_hardware_SensorManager.cpp and libsensor/SensorManager.cpp
//! at the sensorservice boundary. No hosted physical/runtime sensor service is
//! installed: discovery is empty, injection is disabled, channel operations
//! return NO_INIT, and event-queue creation fails. Stock SystemSensorManager
//! owns listener validation, device policy, callbacks and cleanup ordering.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const no_init: jni.jint = -19;
const bad_value: jni.jint = -22;

fn fail(env: Env, name: [*:0]const u8, message: [*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}
fn classInit(_: Env, _: jni.jclass) callconv(.c) void {
    // The original caches fields used only when translating actual sensors/events.
}
fn create(env: Env, _: jni.jclass, package: jni.jstring) callconv(.c) jni.jlong {
    if (package == null) {
        fail(env, "java/lang/NullPointerException", "opPackageName == null");
        return 0;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, package, null) orelse return 0;
    env.*.*.ReleaseStringUTFChars.?(env, package, chars);
    // Immortal, immutable unavailable-service manager token, never a pointer.
    return 1;
}
fn sensorAt(_: Env, _: jni.jclass, _: jni.jlong, _: jni.jobject, _: jni.jint) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
}
fn dynamicSensors(_: Env, _: jni.jclass, _: jni.jlong, _: jni.jobject) callconv(.c) void {
    // Native discovery appends entries; an empty result leaves the supplied list intact.
}
fn runtimeSensors(_: Env, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jobject) callconv(.c) void {}
fn injection(_: Env, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jboolean {
    return jni.JNI_FALSE;
}
fn createDirect(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jlong, channel_type: jni.jint, _: jni.jint, buffer: jni.jobject) callconv(.c) jni.jint {
    // JNI builds an ashmem native handle even for an invalid fd. HardwareBuffer
    // extraction and unknown-type rejection happen before the service check.
    if (channel_type == 1) return no_init;
    if (channel_type != 2 or buffer == null) return bad_value;
    const cls = env.*.*.GetObjectClass.?(env, buffer) orelse return bad_value;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const is_closed = env.*.*.GetMethodID.?(env, cls, "isClosed", "()Z") orelse return bad_value;
    return if (env.*.*.CallBooleanMethodA.?(env, buffer, is_closed, null) != 0) bad_value else no_init;
}
fn destroyDirect(_: Env, _: jni.jclass, _: jni.jlong, _: jni.jint) callconv(.c) void {
    // No channel can have been allocated while the service is absent.
}
fn configDirect(_: Env, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
    return no_init;
}
fn operation(_: Env, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jint, _: jni.jfloatArray, _: jni.jintArray) callconv(.c) jni.jint {
    return no_init;
}
fn initQueue(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jobject, _: jni.jobject, package: jni.jstring, _: jni.jint, _: jni.jstring, _: jni.jstring) callconv(.c) jni.jlong {
    if (package == null) {
        fail(env, "java/lang/NullPointerException", "packageName == null");
        return 0;
    }
    fail(env, "java/lang/RuntimeException", "Cannot construct native SensorEventQueue.");
    return 0;
}
// A failed factory publishes no queue handle. These defensive entries cannot
// be reached by the stock client; reject a forged handle rather than treating
// enable/flush/injection as successful operations on a missing queue.
fn noQueue(env: Env) jni.jint {
    fail(env, "java/lang/IllegalStateException", "No native SensorEventQueue available");
    return no_init;
}
fn enable(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
    return noQueue(env);
}
fn disable(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jint) callconv(.c) jni.jint {
    return noQueue(env);
}
fn destroyQueue(env: Env, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    if (handle != 0) _ = noQueue(env);
}
fn flush(env: Env, _: jni.jclass, _: jni.jlong) callconv(.c) jni.jint {
    return noQueue(env);
}
fn inject(env: Env, _: jni.jclass, _: jni.jlong, _: jni.jint, _: jni.jfloatArray, _: jni.jint, _: jni.jlong) callconv(.c) jni.jint {
    return noQueue(env);
}
fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
fn registerClass(env: Env, name: [*:0]const u8, methods: []const jni.JNINativeMethod) !void {
    const cls = env.*.*.FindClass.?(env, name) orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    if (env.*.*.RegisterNatives.?(env, cls, methods.ptr, @intCast(methods.len)) != 0) return error.RegisterFailed;
}
pub fn register(env: Env) !void {
    try registerClass(env, "android/hardware/SystemSensorManager", &.{
        method("nativeClassInit", "()V", &classInit),
        method("nativeCreate", "(Ljava/lang/String;)J", &create),
        method("nativeGetSensorAtIndex", "(JLandroid/hardware/Sensor;I)Z", &sensorAt),
        method("nativeGetDefaultDeviceSensorAtIndex", "(JLandroid/hardware/Sensor;I)Z", &sensorAt),
        method("nativeGetDynamicSensors", "(JLjava/util/List;)V", &dynamicSensors),
        method("nativeGetRuntimeSensors", "(JILjava/util/List;)V", &runtimeSensors),
        method("nativeIsDataInjectionEnabled", "(J)Z", &injection),
        method("nativeIsReplayDataInjectionEnabled", "(J)Z", &injection),
        method("nativeIsHalBypassReplayDataInjectionEnabled", "(J)Z", &injection),
        method("nativeCreateDirectChannel", "(JIJIILandroid/hardware/HardwareBuffer;)I", &createDirect),
        method("nativeDestroyDirectChannel", "(JI)V", &destroyDirect),
        method("nativeConfigDirectChannel", "(JIII)I", &configDirect),
        method("nativeSetOperationParameter", "(JII[F[I)I", &operation),
    });
    try registerClass(env, "android/hardware/SystemSensorManager$BaseEventQueue", &.{
        method("nativeInitBaseEventQueue", "(JLjava/lang/ref/WeakReference;Landroid/os/MessageQueue;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)J", &initQueue),
        method("nativeEnableSensor", "(JIII)I", &enable),
        method("nativeDisableSensor", "(JI)I", &disable),
        method("nativeDestroySensorEventQueue", "(J)V", &destroyQueue),
        method("nativeFlushSensor", "(J)I", &flush),
        method("nativeInjectSensorData", "(JI[FIJ)I", &inject),
    });
}
