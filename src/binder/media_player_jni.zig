//! JNI signatures from pinned android_media_MediaPlayer.cpp; client state and the
//! absent media.player boundary live in HostedMediaPlayer.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var backend: jni.jclass = null;
var ids: [49]jni.jmethodID = @splat(null);
fn call0(env: Env, player: jni.jobject, p0: jni.jobject, p1: jni.jobject, p2: jni.jobject, p3: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 }, .{ .l = p1 }, .{ .l = p2 }, .{ .l = p3 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[0], &args);
}
fn call1(env: Env, player: jni.jobject, p0: jni.jobject, p1: jni.jlong, p2: jni.jlong) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 }, .{ .j = p1 }, .{ .j = p2 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[1], &args);
}
fn call2(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[2], &args);
}
fn call3(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[3], &args);
}
fn call4(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[4], &args);
}
fn call5(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[5], &args);
}
fn call6(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[6], &args);
}
fn call7(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[7], &args);
}
fn call8(env: Env, player: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[8], &args);
}
fn call9(env: Env, player: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[9], &args);
}
fn call10(env: Env, player: jni.jobject) callconv(.c) jni.jobject {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, ids[10], &args);
}
fn call11(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[11], &args);
}
fn call12(env: Env, player: jni.jobject) callconv(.c) jni.jobject {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, ids[12], &args);
}
fn call13(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[13], &args);
}
fn call14(env: Env, player: jni.jobject) callconv(.c) jni.jobject {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, ids[14], &args);
}
fn call15(env: Env, player: jni.jobject, p0: jni.jlong, p1: jni.jint) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .j = p0 }, .{ .i = p1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[15], &args);
}
fn call16(env: Env, player: jni.jobject, p0: jni.jlong) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .j = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[16], &args);
}
fn call17(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[17], &args);
}
fn call18(env: Env, player: jni.jobject) callconv(.c) jni.jboolean {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, ids[18], &args);
}
fn call19(env: Env, player: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[19], &args);
}
fn call20(env: Env, player: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[20], &args);
}
fn call21(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[21], &args);
}
fn call22(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[22], &args);
}
fn call23(env: Env, player: jni.jobject, p0: jni.jint) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .i = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[23], &args);
}
fn call24(env: Env, player: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[24], &args);
}
fn call25(env: Env, player: jni.jobject, p0: jni.jint, p1: jni.jobject) callconv(.c) jni.jboolean {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .i = p0 }, .{ .l = p1 } };
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, ids[25], &args);
}
fn call26(env: Env, player: jni.jobject, p0: jni.jboolean) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .z = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[26], &args);
}
fn call27(env: Env, player: jni.jobject) callconv(.c) jni.jboolean {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, ids[27], &args);
}
fn call28(env: Env, player: jni.jobject, p0: jni.jfloat, p1: jni.jfloat) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .f = p0 }, .{ .f = p1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[28], &args);
}
fn call29(env: Env, player: jni.jobject, p0: jni.jobject, p1: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 }, .{ .l = p1 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[29], &args);
}
fn call30(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[30], &args);
}
fn call31(env: Env, player: jni.jobject, p0: jni.jboolean, p1: jni.jboolean, p2: jni.jobject) callconv(.c) jni.jboolean {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .z = p0 }, .{ .z = p1 }, .{ .l = p2 } };
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, ids[31], &args);
}
fn call32(env: Env, _: jni.jclass) callconv(.c) void {
    const args = [_]jni.jvalue{};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[32], &args);
}
fn call33(env: Env, player: jni.jobject, p0: jni.jobject, p1: jni.jobject, p2: jni.jint) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 }, .{ .l = p1 }, .{ .i = p2 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[33], &args);
}
fn call34(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[34], &args);
}
fn call35(env: Env, player: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[35], &args);
}
fn call36(env: Env, player: jni.jobject, p0: jni.jint) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .i = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[36], &args);
}
fn call37(env: Env, player: jni.jobject, p0: jni.jfloat) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .f = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[37], &args);
}
fn call38(env: Env, player: jni.jobject, p0: jni.jint) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .i = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[38], &args);
}
fn call39(env: Env, _: jni.jclass, p0: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{.{ .l = p0 }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[39], &args);
}
fn call40(env: Env, player: jni.jobject, p0: jni.jobject, p1: jni.jint) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 }, .{ .i = p1 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[40], &args);
}
fn call41(env: Env, player: jni.jobject, p0: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[41], &args);
}
fn call42(env: Env, player: jni.jobject, p0: jni.jobject, p1: jni.jobject) callconv(.c) jni.jint {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = p0 }, .{ .l = p1 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, ids[42], &args);
}
fn call43(env: Env, player: jni.jobject, p0: jni.jint) callconv(.c) jni.jobject {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .i = p0 } };
    return env.*.*.CallStaticObjectMethodA.?(env, backend, ids[43], &args);
}
fn call44(env: Env, player: jni.jobject, uuid: jni.jobject, session: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .l = uuid }, .{ .l = session } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[44], &args);
}
fn call45(env: Env, player: jni.jobject) callconv(.c) void {
    const args = [_]jni.jvalue{.{ .l = player }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[45], &args);
}
fn call46(env: Env, player: jni.jobject, p0: jni.jint) callconv(.c) jni.jboolean {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .i = p0 } };
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, ids[46], &args);
}
fn call47(env: Env, player: jni.jobject) callconv(.c) jni.jobject {
    const args = [_]jni.jvalue{.{ .l = player }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, ids[47], &args);
}
fn call48(env: Env, player: jni.jobject, p0: jni.jboolean) callconv(.c) void {
    const args = [_]jni.jvalue{ .{ .l = player }, .{ .z = p0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, ids[48], &args);
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaPlayer") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nativeSetDataSource", .signature = "(Landroid/os/IBinder;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V", .fnPtr = @ptrCast(@constCast(&call0)) },
        .{ .name = "_setDataSource", .signature = "(Ljava/io/FileDescriptor;JJ)V", .fnPtr = @ptrCast(@constCast(&call1)) },
        .{ .name = "_setDataSource", .signature = "(Landroid/media/MediaDataSource;)V", .fnPtr = @ptrCast(@constCast(&call2)) },
        .{ .name = "_setVideoSurface", .signature = "(Landroid/view/Surface;)V", .fnPtr = @ptrCast(@constCast(&call3)) },
        .{ .name = "_prepare", .signature = "(Landroid/os/Parcel;)I", .fnPtr = @ptrCast(@constCast(&call4)) },
        .{ .name = "_prepareAsync", .signature = "(Landroid/os/Parcel;)I", .fnPtr = @ptrCast(@constCast(&call5)) },
        .{ .name = "_start", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call6)) },
        .{ .name = "_stop", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call7)) },
        .{ .name = "getVideoWidth", .signature = "()I", .fnPtr = @ptrCast(@constCast(&call8)) },
        .{ .name = "getVideoHeight", .signature = "()I", .fnPtr = @ptrCast(@constCast(&call9)) },
        .{ .name = "native_getMetrics", .signature = "()Landroid/os/PersistableBundle;", .fnPtr = @ptrCast(@constCast(&call10)) },
        .{ .name = "setPlaybackParams", .signature = "(Landroid/media/PlaybackParams;)V", .fnPtr = @ptrCast(@constCast(&call11)) },
        .{ .name = "getPlaybackParams", .signature = "()Landroid/media/PlaybackParams;", .fnPtr = @ptrCast(@constCast(&call12)) },
        .{ .name = "setSyncParams", .signature = "(Landroid/media/SyncParams;)V", .fnPtr = @ptrCast(@constCast(&call13)) },
        .{ .name = "getSyncParams", .signature = "()Landroid/media/SyncParams;", .fnPtr = @ptrCast(@constCast(&call14)) },
        .{ .name = "_seekTo", .signature = "(JI)V", .fnPtr = @ptrCast(@constCast(&call15)) },
        .{ .name = "_notifyAt", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&call16)) },
        .{ .name = "_pause", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call17)) },
        .{ .name = "isPlaying", .signature = "()Z", .fnPtr = @ptrCast(@constCast(&call18)) },
        .{ .name = "getCurrentPosition", .signature = "()I", .fnPtr = @ptrCast(@constCast(&call19)) },
        .{ .name = "getDuration", .signature = "()I", .fnPtr = @ptrCast(@constCast(&call20)) },
        .{ .name = "_release", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call21)) },
        .{ .name = "_reset", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call22)) },
        .{ .name = "_setAudioStreamType", .signature = "(I)V", .fnPtr = @ptrCast(@constCast(&call23)) },
        .{ .name = "_getAudioStreamType", .signature = "()I", .fnPtr = @ptrCast(@constCast(&call24)) },
        .{ .name = "setParameter", .signature = "(ILandroid/os/Parcel;)Z", .fnPtr = @ptrCast(@constCast(&call25)) },
        .{ .name = "setLooping", .signature = "(Z)V", .fnPtr = @ptrCast(@constCast(&call26)) },
        .{ .name = "isLooping", .signature = "()Z", .fnPtr = @ptrCast(@constCast(&call27)) },
        .{ .name = "_setVolume", .signature = "(FF)V", .fnPtr = @ptrCast(@constCast(&call28)) },
        .{ .name = "native_invoke", .signature = "(Landroid/os/Parcel;Landroid/os/Parcel;)I", .fnPtr = @ptrCast(@constCast(&call29)) },
        .{ .name = "native_setMetadataFilter", .signature = "(Landroid/os/Parcel;)I", .fnPtr = @ptrCast(@constCast(&call30)) },
        .{ .name = "native_getMetadata", .signature = "(ZZLandroid/os/Parcel;)Z", .fnPtr = @ptrCast(@constCast(&call31)) },
        .{ .name = "native_init", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call32)) },
        .{ .name = "native_setup", .signature = "(Ljava/lang/Object;Landroid/os/Parcel;I)V", .fnPtr = @ptrCast(@constCast(&call33)) },
        .{ .name = "native_finalize", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call34)) },
        .{ .name = "getAudioSessionId", .signature = "()I", .fnPtr = @ptrCast(@constCast(&call35)) },
        .{ .name = "native_setAudioSessionId", .signature = "(I)V", .fnPtr = @ptrCast(@constCast(&call36)) },
        .{ .name = "_setAuxEffectSendLevel", .signature = "(F)V", .fnPtr = @ptrCast(@constCast(&call37)) },
        .{ .name = "attachAuxEffect", .signature = "(I)V", .fnPtr = @ptrCast(@constCast(&call38)) },
        .{ .name = "native_pullBatteryData", .signature = "(Landroid/os/Parcel;)I", .fnPtr = @ptrCast(@constCast(&call39)) },
        .{ .name = "native_setRetransmitEndpoint", .signature = "(Ljava/lang/String;I)I", .fnPtr = @ptrCast(@constCast(&call40)) },
        .{ .name = "setNextMediaPlayer", .signature = "(Landroid/media/MediaPlayer;)V", .fnPtr = @ptrCast(@constCast(&call41)) },
        .{ .name = "native_applyVolumeShaper", .signature = "(Landroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I", .fnPtr = @ptrCast(@constCast(&call42)) },
        .{ .name = "native_getVolumeShaperState", .signature = "(I)Landroid/media/VolumeShaper$State;", .fnPtr = @ptrCast(@constCast(&call43)) },
        .{ .name = "_prepareDrm", .signature = "([B[B)V", .fnPtr = @ptrCast(@constCast(&call44)) },
        .{ .name = "_releaseDrm", .signature = "()V", .fnPtr = @ptrCast(@constCast(&call45)) },
        .{ .name = "native_setOutputDevice", .signature = "(I)Z", .fnPtr = @ptrCast(@constCast(&call46)) },
        .{ .name = "native_getRoutedDeviceIds", .signature = "()[I", .fnPtr = @ptrCast(@constCast(&call47)) },
        .{ .name = "native_enableDeviceCallback", .signature = "(Z)V", .fnPtr = @ptrCast(@constCast(&call48)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
    const local = env.*.*.FindClass.?(env, "dev/artzig/HostedMediaPlayer") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, local);
    backend = env.*.*.NewGlobalRef.?(env, local) orelse return error.OutOfMemory;
    ids[0] = env.*.*.GetStaticMethodID.?(env, backend, "setUri", "(Landroid/media/MediaPlayer;Landroid/os/IBinder;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V") orelse return error.NoMethod;
    ids[1] = env.*.*.GetStaticMethodID.?(env, backend, "setFd", "(Landroid/media/MediaPlayer;Ljava/io/FileDescriptor;JJ)V") orelse return error.NoMethod;
    ids[2] = env.*.*.GetStaticMethodID.?(env, backend, "setCallback", "(Landroid/media/MediaPlayer;Landroid/media/MediaDataSource;)V") orelse return error.NoMethod;
    ids[3] = env.*.*.GetStaticMethodID.?(env, backend, "setSurface", "(Landroid/media/MediaPlayer;Landroid/view/Surface;)V") orelse return error.NoMethod;
    ids[4] = env.*.*.GetStaticMethodID.?(env, backend, "prepare", "(Landroid/media/MediaPlayer;Landroid/os/Parcel;)I") orelse return error.NoMethod;
    ids[5] = env.*.*.GetStaticMethodID.?(env, backend, "prepareAsync", "(Landroid/media/MediaPlayer;Landroid/os/Parcel;)I") orelse return error.NoMethod;
    ids[6] = env.*.*.GetStaticMethodID.?(env, backend, "start", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[7] = env.*.*.GetStaticMethodID.?(env, backend, "stop", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[8] = env.*.*.GetStaticMethodID.?(env, backend, "width", "(Landroid/media/MediaPlayer;)I") orelse return error.NoMethod;
    ids[9] = env.*.*.GetStaticMethodID.?(env, backend, "height", "(Landroid/media/MediaPlayer;)I") orelse return error.NoMethod;
    ids[10] = env.*.*.GetStaticMethodID.?(env, backend, "metrics", "(Landroid/media/MediaPlayer;)Landroid/os/PersistableBundle;") orelse return error.NoMethod;
    ids[11] = env.*.*.GetStaticMethodID.?(env, backend, "setPlayback", "(Landroid/media/MediaPlayer;Landroid/media/PlaybackParams;)V") orelse return error.NoMethod;
    ids[12] = env.*.*.GetStaticMethodID.?(env, backend, "getPlayback", "(Landroid/media/MediaPlayer;)Landroid/media/PlaybackParams;") orelse return error.NoMethod;
    ids[13] = env.*.*.GetStaticMethodID.?(env, backend, "setSync", "(Landroid/media/MediaPlayer;Landroid/media/SyncParams;)V") orelse return error.NoMethod;
    ids[14] = env.*.*.GetStaticMethodID.?(env, backend, "getSync", "(Landroid/media/MediaPlayer;)Landroid/media/SyncParams;") orelse return error.NoMethod;
    ids[15] = env.*.*.GetStaticMethodID.?(env, backend, "seek", "(Landroid/media/MediaPlayer;JI)V") orelse return error.NoMethod;
    ids[16] = env.*.*.GetStaticMethodID.?(env, backend, "notifyAt", "(Landroid/media/MediaPlayer;J)V") orelse return error.NoMethod;
    ids[17] = env.*.*.GetStaticMethodID.?(env, backend, "pause", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[18] = env.*.*.GetStaticMethodID.?(env, backend, "isPlaying", "(Landroid/media/MediaPlayer;)Z") orelse return error.NoMethod;
    ids[19] = env.*.*.GetStaticMethodID.?(env, backend, "position", "(Landroid/media/MediaPlayer;)I") orelse return error.NoMethod;
    ids[20] = env.*.*.GetStaticMethodID.?(env, backend, "duration", "(Landroid/media/MediaPlayer;)I") orelse return error.NoMethod;
    ids[21] = env.*.*.GetStaticMethodID.?(env, backend, "release", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[22] = env.*.*.GetStaticMethodID.?(env, backend, "reset", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[23] = env.*.*.GetStaticMethodID.?(env, backend, "setStream", "(Landroid/media/MediaPlayer;I)V") orelse return error.NoMethod;
    ids[24] = env.*.*.GetStaticMethodID.?(env, backend, "getStream", "(Landroid/media/MediaPlayer;)I") orelse return error.NoMethod;
    ids[25] = env.*.*.GetStaticMethodID.?(env, backend, "setParameter", "(Landroid/media/MediaPlayer;ILandroid/os/Parcel;)Z") orelse return error.NoMethod;
    ids[26] = env.*.*.GetStaticMethodID.?(env, backend, "setLooping", "(Landroid/media/MediaPlayer;Z)V") orelse return error.NoMethod;
    ids[27] = env.*.*.GetStaticMethodID.?(env, backend, "isLooping", "(Landroid/media/MediaPlayer;)Z") orelse return error.NoMethod;
    ids[28] = env.*.*.GetStaticMethodID.?(env, backend, "setVolume", "(Landroid/media/MediaPlayer;FF)V") orelse return error.NoMethod;
    ids[29] = env.*.*.GetStaticMethodID.?(env, backend, "invoke", "(Landroid/media/MediaPlayer;Landroid/os/Parcel;Landroid/os/Parcel;)I") orelse return error.NoMethod;
    ids[30] = env.*.*.GetStaticMethodID.?(env, backend, "setFilter", "(Landroid/media/MediaPlayer;Landroid/os/Parcel;)I") orelse return error.NoMethod;
    ids[31] = env.*.*.GetStaticMethodID.?(env, backend, "getMetadata", "(Landroid/media/MediaPlayer;ZZLandroid/os/Parcel;)Z") orelse return error.NoMethod;
    ids[32] = env.*.*.GetStaticMethodID.?(env, backend, "init", "()V") orelse return error.NoMethod;
    ids[33] = env.*.*.GetStaticMethodID.?(env, backend, "setup", "(Landroid/media/MediaPlayer;Ljava/lang/Object;Landroid/os/Parcel;I)V") orelse return error.NoMethod;
    ids[34] = env.*.*.GetStaticMethodID.?(env, backend, "release", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[35] = env.*.*.GetStaticMethodID.?(env, backend, "getSession", "(Landroid/media/MediaPlayer;)I") orelse return error.NoMethod;
    ids[36] = env.*.*.GetStaticMethodID.?(env, backend, "setSession", "(Landroid/media/MediaPlayer;I)V") orelse return error.NoMethod;
    ids[37] = env.*.*.GetStaticMethodID.?(env, backend, "setSend", "(Landroid/media/MediaPlayer;F)V") orelse return error.NoMethod;
    ids[38] = env.*.*.GetStaticMethodID.?(env, backend, "attachEffect", "(Landroid/media/MediaPlayer;I)V") orelse return error.NoMethod;
    ids[39] = env.*.*.GetStaticMethodID.?(env, backend, "battery", "(Landroid/os/Parcel;)I") orelse return error.NoMethod;
    ids[40] = env.*.*.GetStaticMethodID.?(env, backend, "retransmit", "(Landroid/media/MediaPlayer;Ljava/lang/String;I)I") orelse return error.NoMethod;
    ids[41] = env.*.*.GetStaticMethodID.?(env, backend, "next", "(Landroid/media/MediaPlayer;Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[42] = env.*.*.GetStaticMethodID.?(env, backend, "shape", "(Landroid/media/MediaPlayer;Landroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I") orelse return error.NoMethod;
    ids[43] = env.*.*.GetStaticMethodID.?(env, backend, "shapeState", "(Landroid/media/MediaPlayer;I)Landroid/media/VolumeShaper$State;") orelse return error.NoMethod;
    ids[44] = env.*.*.GetStaticMethodID.?(env, backend, "prepareDrm", "(Landroid/media/MediaPlayer;[B[B)V") orelse return error.NoMethod;
    ids[45] = env.*.*.GetStaticMethodID.?(env, backend, "releaseDrm", "(Landroid/media/MediaPlayer;)V") orelse return error.NoMethod;
    ids[46] = env.*.*.GetStaticMethodID.?(env, backend, "output", "(Landroid/media/MediaPlayer;I)Z") orelse return error.NoMethod;
    ids[47] = env.*.*.GetStaticMethodID.?(env, backend, "routes", "(Landroid/media/MediaPlayer;)[I") orelse return error.NoMethod;
    ids[48] = env.*.*.GetStaticMethodID.?(env, backend, "deviceCallback", "(Landroid/media/MediaPlayer;Z)V") orelse return error.NoMethod;
}
