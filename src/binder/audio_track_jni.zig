//! Pinned android_media_AudioTrack.cpp registration signatures. The unavailable
//! AudioFlinger track is implemented at HostedAudioTrackBackend; its JNI port
//! retains the original Java native-context publication and release ownership.
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var backend: jni.jclass = null;
var context: jni.jfieldID = null;
var stream_type: jni.jfieldID = null;

fn method(env: Env, name: [*:0]const u8, signature: [*:0]const u8) jni.jmethodID {
    return env.*.*.GetStaticMethodID.?(env, backend, name, signature);
}

fn native_is_direct_output_supported(env: Env, _: jni.jclass, a0: jni.jint, a1: jni.jint, a2: jni.jint, a3: jni.jint, a4: jni.jint, a5: jni.jint, a6: jni.jint) callconv(.c) jni.jboolean {
    const id = method(env, "is_direct_output_supported", "(IIIIIII)Z") orelse return 0;
    const args = [_]jni.jvalue{ .{ .i = a0 }, .{ .i = a1 }, .{ .i = a2 }, .{ .i = a3 }, .{ .i = a4 }, .{ .i = a5 }, .{ .i = a6 } };
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, id, &args);
}

fn native_start(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "start", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_stop(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "stop", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_pause(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "pause", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_flush(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "flush", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setup(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jobject, a2: jni.jobject, a3: jni.jint, a4: jni.jint, a5: jni.jint, a6: jni.jint, a7: jni.jint, a8: jni.jobject, a9: jni.jobject, a10: jni.jlong, a11: jni.jboolean, a12: jni.jint, a13: jni.jobject, a14: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "setup", "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;)J") orelse return 0;
    const args = [_]jni.jvalue{ .{ .l = a0 }, .{ .l = a1 }, .{ .l = a2 }, .{ .i = a3 }, .{ .i = a4 }, .{ .i = a5 }, .{ .i = a6 }, .{ .i = a7 }, .{ .l = a8 }, .{ .l = a9 }, .{ .j = a10 }, .{ .z = a11 }, .{ .i = a12 }, .{ .l = a13 }, .{ .l = a14 } };
    const handle = env.*.*.CallStaticLongMethodA.?(env, backend, id, &args);
    if (env.*.*.ExceptionCheck.?(env) != 0) return -20;
    if (handle <= 0) return @intCast(handle);
    env.*.*.SetLongField.?(env, object, context, handle);
    const stream = method(env, "streamType", "(J)I") orelse return -20;
    const stream_args = [_]jni.jvalue{.{ .j = handle }};
    const value = env.*.*.CallStaticIntMethodA.?(env, backend, stream, &stream_args);
    env.*.*.SetIntField.?(env, object, stream_type, value);
    return 0;
}

fn native_finalize(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "release", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
    env.*.*.SetLongField.?(env, object, context, 0);
}

fn native_release(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "release", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
    env.*.*.SetLongField.?(env, object, context, 0);
}

fn native_write_byte(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jint, a2: jni.jint, a3: jni.jint, a4: jni.jboolean) callconv(.c) jni.jint {
    const id = method(env, "write_byte", "(J[BIIIZ)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .i = a1 }, .{ .i = a2 }, .{ .i = a3 }, .{ .z = a4 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_write_native_bytes(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jint, a2: jni.jint, a3: jni.jint, a4: jni.jboolean) callconv(.c) jni.jint {
    const id = method(env, "write_native_bytes", "(JLjava/nio/ByteBuffer;IIIZ)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .i = a1 }, .{ .i = a2 }, .{ .i = a3 }, .{ .z = a4 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_write_short(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jint, a2: jni.jint, a3: jni.jint, a4: jni.jboolean) callconv(.c) jni.jint {
    const id = method(env, "write_short", "(J[SIIIZ)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .i = a1 }, .{ .i = a2 }, .{ .i = a3 }, .{ .z = a4 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_write_float(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jint, a2: jni.jint, a3: jni.jint, a4: jni.jboolean) callconv(.c) jni.jint {
    const id = method(env, "write_float", "(J[FIIIZ)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .i = a1 }, .{ .i = a2 }, .{ .i = a3 }, .{ .z = a4 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_setVolume(env: Env, object: jni.jobject, a0: jni.jfloat, a1: jni.jfloat) callconv(.c) void {
    const id = method(env, "setVolume", "(JFF)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .f = a0 }, .{ .f = a1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_get_buffer_size_frames(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_buffer_size_frames", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_buffer_size_frames(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_buffer_size_frames", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_buffer_capacity_frames(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_buffer_capacity_frames", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_playback_rate(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_playback_rate", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_playback_rate(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_playback_rate", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_playback_params(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "set_playback_params", "(JLandroid/media/PlaybackParams;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_get_playback_params(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "get_playback_params", "(J)Landroid/media/PlaybackParams;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_set_marker_pos(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_marker_pos", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_marker_pos(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_marker_pos", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_pos_update_period(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_pos_update_period", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_pos_update_period(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_pos_update_period", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_position(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_position", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_position(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_position", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_latency(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_latency", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_underrun_count(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_underrun_count", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_flags(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_flags", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_timestamp(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_timestamp", "(J[J)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_getMetrics(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getMetrics", "(J)Landroid/os/PersistableBundle;") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_set_loop(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jint, a2: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_loop", "(JIII)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .i = a1 }, .{ .i = a2 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_reload_static(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "reload_static", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_output_sample_rate(env: Env, _: jni.jclass, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "get_output_sample_rate", "(I)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .i = a0 }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_min_buff_size(env: Env, _: jni.jclass, a0: jni.jint, a1: jni.jint, a2: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "get_min_buff_size", "(III)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .i = a0 }, .{ .i = a1 }, .{ .i = a2 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_setAuxEffectSendLevel(env: Env, object: jni.jobject, a0: jni.jfloat) callconv(.c) jni.jint {
    const id = method(env, "setAuxEffectSendLevel", "(JF)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .f = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_attachAuxEffect(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "attachAuxEffect", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_setOutputDevice(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jboolean {
    const id = method(env, "setOutputDevice", "(JI)Z") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticBooleanMethodA.?(env, backend, id, &args);
}

fn native_getRoutedDeviceIds(env: Env, object: jni.jobject) callconv(.c) jni.jobject {
    const id = method(env, "getRoutedDeviceIds", "(J)[I") orelse return null;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_enableDeviceCallback(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "enableDeviceCallback", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_disableDeviceCallback(env: Env, object: jni.jobject) callconv(.c) void {
    const id = method(env, "disableDeviceCallback", "(J)V") orelse return;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_applyVolumeShaper(env: Env, object: jni.jobject, a0: jni.jobject, a1: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "applyVolumeShaper", "(JLandroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 }, .{ .l = a1 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_getVolumeShaperState(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jobject {
    const id = method(env, "getVolumeShaperState", "(JI)Landroid/media/VolumeShaper$State;") orelse return null;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticObjectMethodA.?(env, backend, id, &args);
}

fn native_setPresentation(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "setPresentation", "(JII)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .i = a1 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_getPortId(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "getPortId", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_delay_padding(env: Env, object: jni.jobject, a0: jni.jint, a1: jni.jint) callconv(.c) void {
    const id = method(env, "set_delay_padding", "(JII)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 }, .{ .i = a1 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_set_audio_description_mix_level_db(env: Env, object: jni.jobject, a0: jni.jfloat) callconv(.c) jni.jint {
    const id = method(env, "set_audio_description_mix_level_db", "(JF)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .f = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_audio_description_mix_level_db(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_audio_description_mix_level_db", "(J[F)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_set_dual_mono_mode(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "set_dual_mono_mode", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_get_dual_mono_mode(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "get_dual_mono_mode", "(J[I)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_setLogSessionId(env: Env, object: jni.jobject, a0: jni.jobject) callconv(.c) void {
    const id = method(env, "setLogSessionId", "(JLjava/lang/String;)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .l = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setPlayerIId(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) void {
    const id = method(env, "setPlayerIId", "(JI)V") orelse return;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, id, &args);
}

fn native_setStartThresholdInFrames(env: Env, object: jni.jobject, a0: jni.jint) callconv(.c) jni.jint {
    const id = method(env, "setStartThresholdInFrames", "(JI)I") orelse return 0;
    const args = [_]jni.jvalue{ .{ .j = env.*.*.GetLongField.?(env, object, context) }, .{ .i = a0 } };
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

fn native_getStartThresholdInFrames(env: Env, object: jni.jobject) callconv(.c) jni.jint {
    const id = method(env, "getStartThresholdInFrames", "(J)I") orelse return 0;
    const args = [_]jni.jvalue{.{ .j = env.*.*.GetLongField.?(env, object, context) }};
    return env.*.*.CallStaticIntMethodA.?(env, backend, id, &args);
}

pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/AudioTrack") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const local = env.*.*.FindClass.?(env, "dev/artzig/HostedAudioTrackBackend") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, local);
    backend = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return error.NoClass);
    context = env.*.*.GetFieldID.?(env, cls, "mNativeTrackInJavaObj", "J") orelse return error.NoField;
    stream_type = env.*.*.GetFieldID.?(env, cls, "mStreamType", "I") orelse return error.NoField;
    // Validate the full bridge, including natives outside the startup smoke path.
    const backend_methods = .{
        .{ "is_direct_output_supported", "(IIIIIII)Z" },
        .{ "start", "(J)V" },
        .{ "stop", "(J)V" },
        .{ "pause", "(J)V" },
        .{ "flush", "(J)V" },
        .{ "setup", "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;)J" },
        .{ "release", "(J)V" },
        .{ "release", "(J)V" },
        .{ "write_byte", "(J[BIIIZ)I" },
        .{ "write_native_bytes", "(JLjava/nio/ByteBuffer;IIIZ)I" },
        .{ "write_short", "(J[SIIIZ)I" },
        .{ "write_float", "(J[FIIIZ)I" },
        .{ "setVolume", "(JFF)V" },
        .{ "get_buffer_size_frames", "(J)I" },
        .{ "set_buffer_size_frames", "(JI)I" },
        .{ "get_buffer_capacity_frames", "(J)I" },
        .{ "set_playback_rate", "(JI)I" },
        .{ "get_playback_rate", "(J)I" },
        .{ "set_playback_params", "(JLandroid/media/PlaybackParams;)V" },
        .{ "get_playback_params", "(J)Landroid/media/PlaybackParams;" },
        .{ "set_marker_pos", "(JI)I" },
        .{ "get_marker_pos", "(J)I" },
        .{ "set_pos_update_period", "(JI)I" },
        .{ "get_pos_update_period", "(J)I" },
        .{ "set_position", "(JI)I" },
        .{ "get_position", "(J)I" },
        .{ "get_latency", "(J)I" },
        .{ "get_underrun_count", "(J)I" },
        .{ "get_flags", "(J)I" },
        .{ "get_timestamp", "(J[J)I" },
        .{ "getMetrics", "(J)Landroid/os/PersistableBundle;" },
        .{ "set_loop", "(JIII)I" },
        .{ "reload_static", "(J)I" },
        .{ "get_output_sample_rate", "(I)I" },
        .{ "get_min_buff_size", "(III)I" },
        .{ "setAuxEffectSendLevel", "(JF)I" },
        .{ "attachAuxEffect", "(JI)I" },
        .{ "setOutputDevice", "(JI)Z" },
        .{ "getRoutedDeviceIds", "(J)[I" },
        .{ "enableDeviceCallback", "(J)V" },
        .{ "disableDeviceCallback", "(J)V" },
        .{ "applyVolumeShaper", "(JLandroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I" },
        .{ "getVolumeShaperState", "(JI)Landroid/media/VolumeShaper$State;" },
        .{ "setPresentation", "(JII)I" },
        .{ "getPortId", "(J)I" },
        .{ "set_delay_padding", "(JII)V" },
        .{ "set_audio_description_mix_level_db", "(JF)I" },
        .{ "get_audio_description_mix_level_db", "(J[F)I" },
        .{ "set_dual_mono_mode", "(JI)I" },
        .{ "get_dual_mono_mode", "(J[I)I" },
        .{ "setLogSessionId", "(JLjava/lang/String;)V" },
        .{ "setPlayerIId", "(JI)V" },
        .{ "setStartThresholdInFrames", "(JI)I" },
        .{ "getStartThresholdInFrames", "(J)I" },
        .{ "streamType", "(J)I" },
    };
    inline for (backend_methods) |entry| {
        _ = method(env, entry[0], entry[1]) orelse return error.NoMethod;
    }
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "native_is_direct_output_supported", .signature = "(IIIIIII)Z", .fnPtr = @ptrCast(@constCast(&native_is_direct_output_supported)) },
        .{ .name = "native_start", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_start)) },
        .{ .name = "native_stop", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_stop)) },
        .{ .name = "native_pause", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_pause)) },
        .{ .name = "native_flush", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_flush)) },
        .{ .name = "native_setup", .signature = "(Ljava/lang/Object;Ljava/lang/Object;[IIIIII[ILandroid/os/Parcel;JZILjava/lang/Object;Ljava/lang/String;)I", .fnPtr = @ptrCast(@constCast(&native_setup)) },
        .{ .name = "native_finalize", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_finalize)) },
        .{ .name = "native_release", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_release)) },
        .{ .name = "native_write_byte", .signature = "([BIIIZ)I", .fnPtr = @ptrCast(@constCast(&native_write_byte)) },
        .{ .name = "native_write_native_bytes", .signature = "(Ljava/nio/ByteBuffer;IIIZ)I", .fnPtr = @ptrCast(@constCast(&native_write_native_bytes)) },
        .{ .name = "native_write_short", .signature = "([SIIIZ)I", .fnPtr = @ptrCast(@constCast(&native_write_short)) },
        .{ .name = "native_write_float", .signature = "([FIIIZ)I", .fnPtr = @ptrCast(@constCast(&native_write_float)) },
        .{ .name = "native_setVolume", .signature = "(FF)V", .fnPtr = @ptrCast(@constCast(&native_setVolume)) },
        .{ .name = "native_get_buffer_size_frames", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_buffer_size_frames)) },
        .{ .name = "native_set_buffer_size_frames", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_set_buffer_size_frames)) },
        .{ .name = "native_get_buffer_capacity_frames", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_buffer_capacity_frames)) },
        .{ .name = "native_set_playback_rate", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_set_playback_rate)) },
        .{ .name = "native_get_playback_rate", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_playback_rate)) },
        .{ .name = "native_set_playback_params", .signature = "(Landroid/media/PlaybackParams;)V", .fnPtr = @ptrCast(@constCast(&native_set_playback_params)) },
        .{ .name = "native_get_playback_params", .signature = "()Landroid/media/PlaybackParams;", .fnPtr = @ptrCast(@constCast(&native_get_playback_params)) },
        .{ .name = "native_set_marker_pos", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_set_marker_pos)) },
        .{ .name = "native_get_marker_pos", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_marker_pos)) },
        .{ .name = "native_set_pos_update_period", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_set_pos_update_period)) },
        .{ .name = "native_get_pos_update_period", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_pos_update_period)) },
        .{ .name = "native_set_position", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_set_position)) },
        .{ .name = "native_get_position", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_position)) },
        .{ .name = "native_get_latency", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_latency)) },
        .{ .name = "native_get_underrun_count", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_underrun_count)) },
        .{ .name = "native_get_flags", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_get_flags)) },
        .{ .name = "native_get_timestamp", .signature = "([J)I", .fnPtr = @ptrCast(@constCast(&native_get_timestamp)) },
        .{ .name = "native_getMetrics", .signature = "()Landroid/os/PersistableBundle;", .fnPtr = @ptrCast(@constCast(&native_getMetrics)) },
        .{ .name = "native_set_loop", .signature = "(III)I", .fnPtr = @ptrCast(@constCast(&native_set_loop)) },
        .{ .name = "native_reload_static", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_reload_static)) },
        .{ .name = "native_get_output_sample_rate", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_get_output_sample_rate)) },
        .{ .name = "native_get_min_buff_size", .signature = "(III)I", .fnPtr = @ptrCast(@constCast(&native_get_min_buff_size)) },
        .{ .name = "native_setAuxEffectSendLevel", .signature = "(F)I", .fnPtr = @ptrCast(@constCast(&native_setAuxEffectSendLevel)) },
        .{ .name = "native_attachAuxEffect", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_attachAuxEffect)) },
        .{ .name = "native_setOutputDevice", .signature = "(I)Z", .fnPtr = @ptrCast(@constCast(&native_setOutputDevice)) },
        .{ .name = "native_getRoutedDeviceIds", .signature = "()[I", .fnPtr = @ptrCast(@constCast(&native_getRoutedDeviceIds)) },
        .{ .name = "native_enableDeviceCallback", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_enableDeviceCallback)) },
        .{ .name = "native_disableDeviceCallback", .signature = "()V", .fnPtr = @ptrCast(@constCast(&native_disableDeviceCallback)) },
        .{ .name = "native_applyVolumeShaper", .signature = "(Landroid/media/VolumeShaper$Configuration;Landroid/media/VolumeShaper$Operation;)I", .fnPtr = @ptrCast(@constCast(&native_applyVolumeShaper)) },
        .{ .name = "native_getVolumeShaperState", .signature = "(I)Landroid/media/VolumeShaper$State;", .fnPtr = @ptrCast(@constCast(&native_getVolumeShaperState)) },
        .{ .name = "native_setPresentation", .signature = "(II)I", .fnPtr = @ptrCast(@constCast(&native_setPresentation)) },
        .{ .name = "native_getPortId", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_getPortId)) },
        .{ .name = "native_set_delay_padding", .signature = "(II)V", .fnPtr = @ptrCast(@constCast(&native_set_delay_padding)) },
        .{ .name = "native_set_audio_description_mix_level_db", .signature = "(F)I", .fnPtr = @ptrCast(@constCast(&native_set_audio_description_mix_level_db)) },
        .{ .name = "native_get_audio_description_mix_level_db", .signature = "([F)I", .fnPtr = @ptrCast(@constCast(&native_get_audio_description_mix_level_db)) },
        .{ .name = "native_set_dual_mono_mode", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_set_dual_mono_mode)) },
        .{ .name = "native_get_dual_mono_mode", .signature = "([I)I", .fnPtr = @ptrCast(@constCast(&native_get_dual_mono_mode)) },
        .{ .name = "native_setLogSessionId", .signature = "(Ljava/lang/String;)V", .fnPtr = @ptrCast(@constCast(&native_setLogSessionId)) },
        .{ .name = "native_setPlayerIId", .signature = "(I)V", .fnPtr = @ptrCast(@constCast(&native_setPlayerIId)) },
        .{ .name = "native_setStartThresholdInFrames", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&native_setStartThresholdInFrames)) },
        .{ .name = "native_getStartThresholdInFrames", .signature = "()I", .fnPtr = @ptrCast(@constCast(&native_getStartThresholdInFrames)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
