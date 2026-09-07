//! Pinned android_hardware_Camera.cpp: the hosted service boundary cannot create a
//! JNICameraContext. Keep CameraBase's absence/connection errors, release cleanup,
//! and get_native_camera's failure before instance argument checks. The original
//! Camera Java class owns callbacks, parameter formats and public validation.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var context_field: jni.jfieldID = null;
var backend: jni.jclass = null;
var count_method: jni.jmethodID = null;
var info_method: jni.jmethodID = null;
var connect_method: jni.jmethodID = null;

fn fail(env: Env, message: [*:0]const u8) void {
    const cls = env.*.*.FindClass.?(env, "java/lang/RuntimeException") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}
fn noCamera(env: Env) void {
    fail(env, "Camera is being used after Camera.release() was called");
}
fn _getNumberOfCameras(env: Env, _: jni.jclass, parcel: jni.jobject, policy: jni.jint) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = parcel }, .{ .i = policy } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, count_method, &args);
}
fn _getCameraInfo(env: Env, _: jni.jclass, id: jni.jint, rotation: jni.jint, parcel: jni.jobject, policy: jni.jint, out: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .i = id }, .{ .i = rotation }, .{ .l = parcel }, .{ .i = policy }, .{ .l = out } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, info_method, &args);
}
fn native_setup(env: Env, _: jni.jobject, _: jni.jobject, id: jni.jint, rotation: jni.jint, slow_jpeg: jni.jboolean, parcel: jni.jobject, policy: jni.jint) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .i = id }, .{ .i = rotation }, .{ .z = slow_jpeg }, .{ .l = parcel }, .{ .i = policy } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, connect_method, &args);
}
fn native_release(env: Env, object: jni.jobject) callconv(.c) void {
    env.*.*.SetLongField.?(env, object, context_field, 0);
}
// The original addCallbackBuffer skips a missing context without throwing.
fn _addCallbackBuffer(_: Env, _: jni.jobject, _: jni.jbyteArray, _: jni.jint) callconv(.c) void {}

fn setPreviewSurface(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn setPreviewTexture(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn setPreviewCallbackSurface(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn startPreview(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn _stopPreview(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn previewEnabled(env: Env, _: jni.jobject) callconv(.c) jni.jboolean {
    noCamera(env);
    return 0;
}

fn setHasPreviewCallback(env: Env, _: jni.jobject, _: jni.jboolean, _: jni.jboolean) callconv(.c) void {
    noCamera(env);
}

fn native_autoFocus(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn native_cancelAutoFocus(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn native_takePicture(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
    noCamera(env);
}

fn native_setParameters(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn native_getParameters(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
    noCamera(env);
    return null;
}

fn reconnect(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn lock(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn unlock(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn startSmoothZoom(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
    noCamera(env);
}

fn stopSmoothZoom(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn setDisplayOrientation(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
    noCamera(env);
}

fn _enableShutterSound(env: Env, _: jni.jobject, _: jni.jboolean) callconv(.c) jni.jboolean {
    noCamera(env);
    return 0;
}

fn _startFaceDetection(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
    noCamera(env);
}

fn _stopFaceDetection(env: Env, _: jni.jobject) callconv(.c) void {
    noCamera(env);
}

fn enableFocusMoveCallback(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
    noCamera(env);
}

fn setAudioRestriction(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
    fail(env, "camera has been disconnected");
}

fn getAudioRestriction(env: Env, _: jni.jobject) callconv(.c) jni.jint {
    fail(env, "camera has been disconnected");
    return -1;
}

fn method(comptime name: [*:0]const u8, comptime signature: [*:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/hardware/Camera") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    context_field = env.*.*.GetFieldID.?(env, cls, "mNativeContext", "J") orelse return error.NoField;
    const local_backend = env.*.*.FindClass.?(env, "dev/artzig/HostedLegacyCamera") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, local_backend);
    backend = env.*.*.NewGlobalRef.?(env, local_backend) orelse return error.OutOfMemory;
    count_method = env.*.*.GetStaticMethodID.?(env, backend, "count", "(Landroid/os/Parcel;I)I") orelse return error.NoMethod;
    info_method = env.*.*.GetStaticMethodID.?(env, backend, "info", "(IILandroid/os/Parcel;ILandroid/hardware/Camera$CameraInfo;)V") orelse return error.NoMethod;
    connect_method = env.*.*.GetStaticMethodID.?(env, backend, "connect", "(IIZLandroid/os/Parcel;I)I") orelse return error.NoMethod;
    const methods = [_]jni.JNINativeMethod{
        method("_getNumberOfCameras", "(Landroid/os/Parcel;I)I", &_getNumberOfCameras),
        method("_getCameraInfo", "(IILandroid/os/Parcel;ILandroid/hardware/Camera$CameraInfo;)V", &_getCameraInfo),
        method("native_setup", "(Ljava/lang/Object;IIZLandroid/os/Parcel;I)I", &native_setup),
        method("native_release", "()V", &native_release),
        method("setPreviewSurface", "(Landroid/view/Surface;)V", &setPreviewSurface),
        method("setPreviewTexture", "(Landroid/graphics/SurfaceTexture;)V", &setPreviewTexture),
        method("setPreviewCallbackSurface", "(Landroid/view/Surface;)V", &setPreviewCallbackSurface),
        method("startPreview", "()V", &startPreview),
        method("_stopPreview", "()V", &_stopPreview),
        method("previewEnabled", "()Z", &previewEnabled),
        method("setHasPreviewCallback", "(ZZ)V", &setHasPreviewCallback),
        method("_addCallbackBuffer", "([BI)V", &_addCallbackBuffer),
        method("native_autoFocus", "()V", &native_autoFocus),
        method("native_cancelAutoFocus", "()V", &native_cancelAutoFocus),
        method("native_takePicture", "(I)V", &native_takePicture),
        method("native_setParameters", "(Ljava/lang/String;)V", &native_setParameters),
        method("native_getParameters", "()Ljava/lang/String;", &native_getParameters),
        method("reconnect", "()V", &reconnect),
        method("lock", "()V", &lock),
        method("unlock", "()V", &unlock),
        method("startSmoothZoom", "(I)V", &startSmoothZoom),
        method("stopSmoothZoom", "()V", &stopSmoothZoom),
        method("setDisplayOrientation", "(I)V", &setDisplayOrientation),
        method("_enableShutterSound", "(Z)Z", &_enableShutterSound),
        method("_startFaceDetection", "(I)V", &_startFaceDetection),
        method("_stopFaceDetection", "()V", &_stopFaceDetection),
        method("enableFocusMoveCallback", "(I)V", &enableFocusMoveCallback),
        method("setAudioRestriction", "(I)V", &setAudioRestriction),
        method("getAudioRestriction", "()I", &getAudioRestriction),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
