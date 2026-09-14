//! Pinned MediaRecorder, CamcorderProfile and AudioEffect JNI at the hosted
//! media boundary: recorder and effect services are absent, so initialization
//! fails the way AOSP reports a missing recorder/effect and produces structured
//! diagnostics. AudioSystem/AudioTrack/AudioRecord use the original JNI over
//! stock libaudioclient (src/native/audio).
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;

fn throw(env: Env, class_name: [*:0]const u8, detail: [*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, class_name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, detail);
}

fn unsupported(env: Env, owner: [*:0]const u8, member: [*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const method_id = env.*.*.GetStaticMethodID.?(env, cls, "unsupported", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;") orelse return;
    const type_name = env.*.*.NewStringUTF.?(env, owner) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, type_name);
    const member_name = env.*.*.NewStringUTF.?(env, member) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, member_name);
    const detail = env.*.*.NewStringUTF.?(env, "The host has no Android audio policy or recording service for this operation") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, detail);
    const args = [_]jni.jvalue{ .{ .l = type_name }, .{ .l = member_name }, .{ .l = detail } };
    const exception = env.*.*.CallStaticObjectMethodA.?(env, cls, method_id, &args) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, exception);
    _ = env.*.*.Throw.?(env, @ptrCast(exception));
}

fn releasePersistentSurface(env: Env, _: jni.jclass, surface: jni.jobject) callconv(.c) void {
    // Pinned MediaCodec release leaves Surface.release to Java. The hosted codec
    // creates no persistent input surfaces, so only the zero-owner path exists.
    const cls = env.*.*.GetObjectClass.?(env, surface) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const field = env.*.*.GetFieldID.?(env, cls, "mPersistentObject", "J") orelse return;
    if (env.*.*.GetLongField.?(env, surface, field) != 0) {
        unsupported(env, "android.media.MediaCodec", "native_releasePersistentInputSurface");
    }
}

fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, comptime function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name.ptr), .signature = @constCast(signature.ptr), .fnPtr = @ptrCast(@constCast(function)) };
}

const MediaRecorder = struct {
    fn setCamera(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn getSurface(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return null;
    }
    fn native_setInputSurface(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setAudioSource(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setPrivacySensitive(env: Env, _: jni.jobject, _: jni.jboolean) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn isPrivacySensitive(env: Env, _: jni.jobject) callconv(.c) jni.jboolean {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn setVideoSource(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setOutputFormat(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setVideoSize(env: Env, _: jni.jobject, _: jni.jint, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setVideoFrameRate(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setMaxDuration(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setMaxFileSize(env: Env, _: jni.jobject, _: jni.jlong) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setAudioEncoder(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn setVideoEncoder(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn _setOutputFile(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn _setNextOutputFile(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn _prepare(env: Env, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn start(env: Env, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn stop(env: Env, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn pause(env: Env, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn @"resume"(env: Env, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn native_reset(env: Env, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn getMaxAmplitude(env: Env, _: jni.jobject) callconv(.c) jni.jint {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn native_setInputDevice(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jboolean {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn native_getRoutedDeviceIds(env: Env, _: jni.jobject) callconv(.c) jni.jintArray {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return null;
    }
    fn native_enableDeviceCallback(env: Env, _: jni.jobject, _: jni.jboolean) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn native_getActiveMicrophones(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jint {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn native_setPreferredMicrophoneDirection(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jint {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn native_setPreferredMicrophoneFieldDimension(env: Env, _: jni.jobject, _: jni.jfloat) callconv(.c) jni.jint {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn native_getPortId(env: Env, _: jni.jobject) callconv(.c) jni.jint {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return 0;
    }
    fn release(_: Env, _: jni.jobject) callconv(.c) void {
        // No native recorder exists to cache or release.
    }
    fn native_init(_: Env, _: jni.jclass) callconv(.c) void {
        // No native recorder exists to cache or release.
    }
    fn native_setup(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jstring, _: jni.jobject) callconv(.c) void {
        throw(env, "java/lang/RuntimeException", "Unable to initialize media recorder");
    }
    fn native_finalize(_: Env, _: jni.jobject) callconv(.c) void {
        // No native recorder exists to cache or release.
    }
    fn setParameter(env: Env, _: jni.jobject, _: jni.jstring) callconv(.c) void {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
    }
    fn native_getMetrics(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
        throw(env, "java/lang/IllegalStateException", "MediaRecorder is not initialized");
        return null;
    }
    const methods = [_]jni.JNINativeMethod{
        method("setCamera", "(Landroid/hardware/Camera;)V", &setCamera),
        method("getSurface", "()Landroid/view/Surface;", &getSurface),
        method("native_setInputSurface", "(Landroid/view/Surface;)V", &native_setInputSurface),
        method("setAudioSource", "(I)V", &setAudioSource),
        method("setPrivacySensitive", "(Z)V", &setPrivacySensitive),
        method("isPrivacySensitive", "()Z", &isPrivacySensitive),
        method("setVideoSource", "(I)V", &setVideoSource),
        method("setOutputFormat", "(I)V", &setOutputFormat),
        method("setVideoSize", "(II)V", &setVideoSize),
        method("setVideoFrameRate", "(I)V", &setVideoFrameRate),
        method("setMaxDuration", "(I)V", &setMaxDuration),
        method("setMaxFileSize", "(J)V", &setMaxFileSize),
        method("setAudioEncoder", "(I)V", &setAudioEncoder),
        method("setVideoEncoder", "(I)V", &setVideoEncoder),
        method("_setOutputFile", "(Ljava/io/FileDescriptor;)V", &_setOutputFile),
        method("_setNextOutputFile", "(Ljava/io/FileDescriptor;)V", &_setNextOutputFile),
        method("_prepare", "()V", &_prepare),
        method("start", "()V", &start),
        method("stop", "()V", &stop),
        method("pause", "()V", &pause),
        method("resume", "()V", &@"resume"),
        method("native_reset", "()V", &native_reset),
        method("getMaxAmplitude", "()I", &getMaxAmplitude),
        method("native_setInputDevice", "(I)Z", &native_setInputDevice),
        method("native_getRoutedDeviceIds", "()[I", &native_getRoutedDeviceIds),
        method("native_enableDeviceCallback", "(Z)V", &native_enableDeviceCallback),
        method("native_getActiveMicrophones", "(Ljava/util/ArrayList;)I", &native_getActiveMicrophones),
        method("native_setPreferredMicrophoneDirection", "(I)I", &native_setPreferredMicrophoneDirection),
        method("native_setPreferredMicrophoneFieldDimension", "(F)I", &native_setPreferredMicrophoneFieldDimension),
        method("native_getPortId", "()I", &native_getPortId),
        method("release", "()V", &release),
        method("native_init", "()V", &native_init),
        method("native_setup", "(Ljava/lang/Object;Ljava/lang/String;Landroid/os/Parcel;)V", &native_setup),
        method("native_finalize", "()V", &native_finalize),
        method("setParameter", "(Ljava/lang/String;)V", &setParameter),
        method("native_getMetrics", "()Landroid/os/PersistableBundle;", &native_getMetrics),
    };
};

const CamcorderProfile = struct {
    fn native_init(_: Env, _: jni.jclass) callconv(.c) void {
        // No camera profile service exists to cache.
    }
    fn native_get_camcorder_profile(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jobject {
        unsupported(env, "android.media.CamcorderProfile", "native_get_camcorder_profile");
        return null;
    }
    fn native_get_camcorder_profiles(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jobject {
        unsupported(env, "android.media.CamcorderProfile", "native_get_camcorder_profiles");
        return null;
    }
    fn native_has_camcorder_profile(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.CamcorderProfile", "native_has_camcorder_profile");
        return 0;
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_init", "()V", &native_init),
        method("native_get_camcorder_profile", "(II)Landroid/media/CamcorderProfile;", &native_get_camcorder_profile),
        method("native_get_camcorder_profiles", "(IIZ)Landroid/media/EncoderProfiles;", &native_get_camcorder_profiles),
        method("native_has_camcorder_profile", "(II)Z", &native_has_camcorder_profile),
    };
};

const AudioEffect = struct {
    fn native_init(_: Env, _: jni.jclass) callconv(.c) void {
        // No native effect engine exists to cache or release.
    }
    fn native_setup(_: Env, _: jni.jobject, _: jni.jobject, _: jni.jstring, _: jni.jstring, _: jni.jint, _: jni.jint, _: jni.jint, _: jni.jstring, _: jni.jintArray, _: jni.jobjectArray, _: jni.jobject, _: jni.jboolean) callconv(.c) jni.jint {
        return -3; // ERROR_NO_INIT
    }
    fn native_finalize(_: Env, _: jni.jobject) callconv(.c) void {
        // No native effect engine exists to cache or release.
    }
    fn native_release(_: Env, _: jni.jobject) callconv(.c) void {
        // No native effect engine exists to cache or release.
    }
    fn native_setEnabled(env: Env, _: jni.jobject, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.audiofx.AudioEffect", "native_setEnabled");
        return 0;
    }
    fn native_getEnabled(env: Env, _: jni.jobject) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.audiofx.AudioEffect", "native_getEnabled");
        return 0;
    }
    fn native_hasControl(env: Env, _: jni.jobject) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.audiofx.AudioEffect", "native_hasControl");
        return 0;
    }
    fn native_setParameter(env: Env, _: jni.jobject, _: jni.jint, _: jni.jbyteArray, _: jni.jint, _: jni.jbyteArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.audiofx.AudioEffect", "native_setParameter");
        return 0;
    }
    fn native_getParameter(env: Env, _: jni.jobject, _: jni.jint, _: jni.jbyteArray, _: jni.jint, _: jni.jbyteArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.audiofx.AudioEffect", "native_getParameter");
        return 0;
    }
    fn native_command(env: Env, _: jni.jobject, _: jni.jint, _: jni.jint, _: jni.jbyteArray, _: jni.jint, _: jni.jbyteArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.audiofx.AudioEffect", "native_command");
        return 0;
    }
    fn native_query_effects(_: Env, _: jni.jclass) callconv(.c) jni.jobjectArray {
        return null;
    }
    fn native_query_pre_processing(_: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jobjectArray {
        return null;
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_init", "()V", &native_init),
        method("native_setup", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;[I[Ljava/lang/Object;Landroid/os/Parcel;Z)I", &native_setup),
        method("native_finalize", "()V", &native_finalize),
        method("native_release", "()V", &native_release),
        method("native_setEnabled", "(Z)I", &native_setEnabled),
        method("native_getEnabled", "()Z", &native_getEnabled),
        method("native_hasControl", "()Z", &native_hasControl),
        method("native_setParameter", "(I[BI[B)I", &native_setParameter),
        method("native_getParameter", "(I[BI[B)I", &native_getParameter),
        method("native_command", "(II[BI[B)I", &native_command),
        method("native_query_effects", "()[Ljava/lang/Object;", &native_query_effects),
        method("native_query_pre_processing", "(I)[Ljava/lang/Object;", &native_query_pre_processing),
    };
};

pub fn register(env: Env, _: *std.DynLib) !void {
    // AudioSystem capabilities must precede class initialization in the other clients.
    inline for (.{
        .{ "android/media/MediaRecorder", &MediaRecorder.methods },
        .{ "android/media/CamcorderProfile", &CamcorderProfile.methods },
        .{ "android/media/audiofx/AudioEffect", &AudioEffect.methods },
    }) |entry| {
        const cls = env.*.*.FindClass.?(env, entry[0]) orelse return error.NoClass;
        defer env.*.*.DeleteLocalRef.?(env, cls);
        if (env.*.*.RegisterNatives.?(env, cls, entry[1], entry[1].len) != 0) return error.RegisterFailed;
    }
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCodec") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{method("native_releasePersistentInputSurface", "(Landroid/view/Surface;)V", &releasePersistentSurface)};
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
