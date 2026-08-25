//! JNI adapter for android.media.MediaCodec. The decoder implementation is
//! already Zig; keep Java array/direct-buffer handling at the same seam.
const art = @import("art");
const codec = @import("azk_codec");
const jni = art.jni;

fn nativeOpen(env: [*c]jni.JNIEnv, _: jni.jclass, name: jni.jstring) callconv(.c) jni.jlong {
    const chars = env.*.*.GetStringUTFChars.?(env, name, null) orelse return 0;
    defer env.*.*.ReleaseStringUTFChars.?(env, name, chars);
    const decoder = codec.azk_codec_open(@ptrCast(chars)) orelse return 0;
    return @intCast(@intFromPtr(decoder));
}

fn nativeConfigure(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, csd: jni.jbyteArray, sample_rate: jni.jint, channels: jni.jint) callconv(.c) jni.jint {
    const decoder: *codec.Dec = @ptrFromInt(@as(usize, @intCast(handle)));
    if (csd == null) return codec.azk_codec_configure(decoder, null, 0, sample_rate, channels);
    const base = env.*.*.GetByteArrayElements.?(env, csd, null) orelse return -100;
    defer env.*.*.ReleaseByteArrayElements.?(env, csd, base, 2); // JNI_ABORT
    const len = env.*.*.GetArrayLength.?(env, csd);
    return codec.azk_codec_configure(decoder, @ptrCast(base), len, sample_rate, channels);
}

fn nativeSetVideoTarget(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, width: jni.jint, height: jni.jint) callconv(.c) void {
    codec.azk_codec_set_video_target(@ptrFromInt(@as(usize, @intCast(handle))), width, height);
}

fn nativeSend(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, buffer: jni.jobject, offset: jni.jint, size: jni.jint, pts_us: jni.jlong, eof: jni.jint) callconv(.c) jni.jint {
    const decoder: *codec.Dec = @ptrFromInt(@as(usize, @intCast(handle)));
    const raw = env.*.*.GetDirectBufferAddress.?(env, buffer);
    if (raw == null and eof == 0) return -100;
    const data: ?[*]const u8 = if (raw) |p| @as([*]const u8, @ptrCast(p)) + @as(usize, @intCast(offset)) else null;
    return codec.azk_codec_send(decoder, data, size, pts_us, eof);
}

fn nativeReceiveAudio(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, dst: jni.jobject, capacity: jni.jint, meta: jni.jintArray, pts: jni.jlongArray) callconv(.c) jni.jint {
    const out_raw = env.*.*.GetDirectBufferAddress.?(env, dst) orelse return -100;
    const decoder: *codec.Dec = @ptrFromInt(@as(usize, @intCast(handle)));
    var values = [3]c_int{ 0, 0, 0 };
    var timestamp: i64 = 0;
    const result = codec.azk_codec_receive_audio(decoder, @ptrCast(out_raw), capacity, &values, &timestamp);
    env.*.*.SetIntArrayRegion.?(env, meta, 0, values.len, @ptrCast(&values));
    const java_timestamp: jni.jlong = timestamp;
    env.*.*.SetLongArrayRegion.?(env, pts, 0, 1, @ptrCast(&java_timestamp));
    return result;
}

fn nativeReceiveVideo(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, dst: jni.jobject, capacity: jni.jint, meta: jni.jintArray, pts: jni.jlongArray, hardware_handle: jni.jlongArray, surface_output: jni.jboolean) callconv(.c) jni.jint {
    const out_raw = env.*.*.GetDirectBufferAddress.?(env, dst) orelse return -100;
    const decoder: *codec.Dec = @ptrFromInt(@as(usize, @intCast(handle)));
    var values = [3]c_int{ 0, 0, 0 };
    var timestamp: i64 = 0;
    var native_handle: usize = 0;
    const result = if (surface_output != 0)
        codec.azk_codec_receive_video2(decoder, @ptrCast(out_raw), capacity, &values, &timestamp, &native_handle)
    else
        codec.azk_codec_receive_video(decoder, @ptrCast(out_raw), capacity, &values, &timestamp);
    env.*.*.SetIntArrayRegion.?(env, meta, 0, values.len, @ptrCast(&values));
    const java_timestamp: jni.jlong = timestamp;
    const java_handle: jni.jlong = @intCast(native_handle);
    env.*.*.SetLongArrayRegion.?(env, pts, 0, 1, @ptrCast(&java_timestamp));
    env.*.*.SetLongArrayRegion.?(env, hardware_handle, 0, 1, @ptrCast(&java_handle));
    return result;
}

fn nativeFlush(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    codec.azk_codec_flush(@ptrFromInt(@as(usize, @intCast(handle))));
}

fn nativeClose(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    codec.azk_codec_close(@ptrFromInt(@as(usize, @intCast(handle))));
}

pub fn register(env: [*c]jni.JNIEnv) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCodec") orelse return error.NoClass;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nativeOpen", .signature = "(Ljava/lang/String;)J", .fnPtr = @ptrCast(@constCast(&nativeOpen)) },
        .{ .name = "nativeConfigure", .signature = "(J[BII)I", .fnPtr = @ptrCast(@constCast(&nativeConfigure)) },
        .{ .name = "nativeSetVideoTarget", .signature = "(JII)V", .fnPtr = @ptrCast(@constCast(&nativeSetVideoTarget)) },
        .{ .name = "nativeSend", .signature = "(JLjava/nio/ByteBuffer;IIJI)I", .fnPtr = @ptrCast(@constCast(&nativeSend)) },
        .{ .name = "nativeReceiveAudio", .signature = "(JLjava/nio/ByteBuffer;I[I[J)I", .fnPtr = @ptrCast(@constCast(&nativeReceiveAudio)) },
        .{ .name = "nativeReceiveVideo", .signature = "(JLjava/nio/ByteBuffer;I[I[J[JZ)I", .fnPtr = @ptrCast(@constCast(&nativeReceiveVideo)) },
        .{ .name = "nativeFlush", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeFlush)) },
        .{ .name = "nativeClose", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeClose)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

test "JNI registration adapter compiles" {
    const registration: *const fn ([*c]jni.JNIEnv) anyerror!void = &register;
    _ = registration;
}
