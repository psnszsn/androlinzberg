//! Pinned android_view_DisplayEventReceiver.cpp JNI contract. The host event
//! connection owns its pipe in Kotlin; stock MessageQueue dispatches its events.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var backend: jni.jclass = null;
var create_method: jni.jmethodID = null;
var schedule_method: jni.jmethodID = null;
var latest_method: jni.jmethodID = null;
var dispose_method: jni.jmethodID = null;
var vm: [*c]jni.JavaVM = null;

fn create(env: Env, _: jni.jclass, receiver: jni.jobject, data: jni.jobject, queue: jni.jobject, source: jni.jint, events: jni.jint, layer: jni.jlong) callconv(.c) jni.jlong {
    const args = [_]jni.jvalue{ .{ .l = receiver }, .{ .l = data }, .{ .l = queue }, .{ .i = source }, .{ .i = events }, .{ .j = layer } };
    return env.*.*.CallStaticLongMethodA.?(env, backend, create_method, &args);
}
fn schedule(env: Env, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .j = handle }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, schedule_method, &args);
}
fn latest(env: Env, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jobject {
    const args = [_]jni.jvalue{.{ .j = handle }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, latest_method, &args);
}
fn release(pointer: ?*anyopaque) callconv(.c) void {
    // NativeAllocationRegistry invokes this from an attached Java thread.
    var raw_env: ?*anyopaque = null;
    if (vm == null or vm.*.*.GetEnv.?(vm, &raw_env, jni.JNI_VERSION_1_6) != jni.JNI_OK) return;
    const env: Env = @ptrCast(@alignCast(raw_env));
    const args = [_]jni.jvalue{.{ .j = @intCast(@intFromPtr(pointer)) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, dispose_method, &args);
}
fn finalizer(_: Env, _: jni.jclass) callconv(.c) jni.jlong {
    return @intCast(@intFromPtr(&release));
}
fn method(comptime name: [*:0]const u8, comptime signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    if (env.*.*.GetJavaVM.?(env, &vm) != jni.JNI_OK) return error.NoVm;
    const cls = env.*.*.FindClass.?(env, "android/view/DisplayEventReceiver") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("nativeInit", "(Ljava/lang/ref/WeakReference;Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;IIJ)J", &create),
        method("nativeGetDisplayEventReceiverFinalizer", "()J", &finalizer),
        method("nativeScheduleVsync", "(J)V", &schedule),
        method("nativeGetLatestVsyncEventData", "(J)Landroid/view/DisplayEventReceiver$VsyncEventData;", &latest),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
    const local_backend = env.*.*.FindClass.?(env, "dev/artzig/HostedDisplayEvents") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, local_backend);
    backend = env.*.*.NewGlobalRef.?(env, local_backend) orelse return error.OutOfMemory;
    create_method = env.*.*.GetStaticMethodID.?(env, backend, "create", "(Ljava/lang/ref/WeakReference;Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;IIJ)J") orelse return error.NoMethod;
    schedule_method = env.*.*.GetStaticMethodID.?(env, backend, "schedule", "(J)V") orelse return error.NoMethod;
    latest_method = env.*.*.GetStaticMethodID.?(env, backend, "latest", "(J)Landroid/view/DisplayEventReceiver$VsyncEventData;") orelse return error.NoMethod;
    dispose_method = env.*.*.GetStaticMethodID.?(env, backend, "dispose", "(J)V") orelse return error.NoMethod;
}
