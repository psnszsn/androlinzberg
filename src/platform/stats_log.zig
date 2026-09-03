//! Native sink for stock AOSP android.util.StatsLog.
//!
//! Android forwards encoded atoms to statsd through libstatssocket. The hosted
//! runtime has no statsd daemon, so this library deliberately accepts and drops
//! the buffer, matching AOSP StatsLog.writeImpl$ravenwood while preserving the
//! stock Java API, encoding, and StatsEvent ownership behavior.
const art = @import("art");
const jni = art.jni;

export fn JNI_OnLoad(_: ?*anyopaque, _: ?*anyopaque) callconv(.c) jni.jint {
    return jni.JNI_VERSION_1_6;
}

export fn Java_android_util_StatsLog_writeImpl(
    _: [*c]jni.JNIEnv,
    _: jni.jclass,
    _: jni.jbyteArray,
    _: jni.jint,
    _: jni.jint,
) callconv(.c) void {}
