//! Pinned android_app_Activity.cpp getDlWarning at the dynamic-loader boundary.
//! The hosted loader reports load failures immediately and has no bionic deferred
//! android_dlwarning journal. Match AOSP's null result when that journal is empty.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
fn getDlWarning(_: Env, _: jni.jclass) callconv(.c) jni.jstring {
    return null;
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/app/Activity") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{.{
        .name = @constCast("getDlWarning"),
        .signature = @constCast("()Ljava/lang/String;"),
        .fnPtr = @ptrCast(@constCast(&getDlWarning)),
    }};
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
