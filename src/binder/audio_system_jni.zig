//! Pinned AudioSystem, AudioRecord, MediaRecorder, AudioEffect and audio policy JNI
//! at the hosted audio boundary. Preserve framework limits, the existing logical
//! PCM output, and absent-recorder initialization failures. Operations requiring
//! Android audio policy or recorder services produce structured diagnostics.
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Capability = *const fn (c_int) callconv(.c) c_int;
var capability: Capability = undefined;

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

fn ints(env: Env, values: []const jni.jint) jni.jintArray {
    const result = env.*.*.NewIntArray.?(env, @intCast(values.len)) orelse return null;
    if (values.len != 0) env.*.*.SetIntArrayRegion.?(env, result, 0, @intCast(values.len), values.ptr);
    return result;
}

// Same AudioDevicePort value construction as convertAudioPortFromNative. These
// capabilities describe the existing PCM adapter, not physical device discovery.
fn outputPort(env: Env) jni.jobject {
    if (env.*.*.PushLocalFrame.?(env, 16) != jni.JNI_OK) return null;
    const handle_class = env.*.*.FindClass.?(env, "android/media/AudioHandle") orelse return env.*.*.PopLocalFrame.?(env, null);
    const port_class = env.*.*.FindClass.?(env, "android/media/AudioDevicePort") orelse return env.*.*.PopLocalFrame.?(env, null);
    const gain_class = env.*.*.FindClass.?(env, "android/media/AudioGain") orelse return env.*.*.PopLocalFrame.?(env, null);
    const handle_constructor = env.*.*.GetMethodID.?(env, handle_class, "<init>", "(I)V") orelse return env.*.*.PopLocalFrame.?(env, null);
    const port_constructor = env.*.*.GetMethodID.?(env, port_class, "<init>", "(Landroid/media/AudioHandle;Ljava/lang/String;[I[I[I[I[Landroid/media/AudioGain;ILjava/lang/String;[I[I)V") orelse return env.*.*.PopLocalFrame.?(env, null);
    const handle_args = [_]jni.jvalue{.{ .i = 1 }};
    const handle = env.*.*.NewObjectA.?(env, handle_class, handle_constructor, &handle_args) orelse return env.*.*.PopLocalFrame.?(env, null);
    const name = env.*.*.NewStringUTF.?(env, "artzig") orelse return env.*.*.PopLocalFrame.?(env, null);
    const address = env.*.*.NewStringUTF.?(env, "") orelse return env.*.*.PopLocalFrame.?(env, null);
    const rates = ints(env, &.{ 44100, 48000 }) orelse return env.*.*.PopLocalFrame.?(env, null);
    // Pinned outChannelMaskFromNative: Java output channel bits are shifted by two.
    const masks = ints(env, &.{ capability(4) << 2, capability(5) << 2 }) orelse return env.*.*.PopLocalFrame.?(env, null);
    const empty = ints(env, &.{}) orelse return env.*.*.PopLocalFrame.?(env, null);
    // AudioFormat.ENCODING_PCM_16BIT is a Java encoding, not audio_format_t.
    const formats = ints(env, &.{2}) orelse return env.*.*.PopLocalFrame.?(env, null);
    const gains = env.*.*.NewObjectArray.?(env, 0, gain_class, null) orelse return env.*.*.PopLocalFrame.?(env, null);
    const args = [_]jni.jvalue{ .{ .l = handle }, .{ .l = name }, .{ .l = rates }, .{ .l = masks }, .{ .l = empty }, .{ .l = formats }, .{ .l = gains }, .{ .i = capability(3) }, .{ .l = address }, .{ .l = empty }, .{ .l = empty } };
    const port = env.*.*.NewObjectA.?(env, port_class, port_constructor, &args);
    return env.*.*.PopLocalFrame.?(env, port);
}

fn listPorts(env: Env, list: jni.jobject, generation: jni.jintArray, include_output: bool) jni.jint {
    // Pinned JNI requires ArrayList and a generation array of exactly one element.
    if (list == null or generation == null or env.*.*.GetArrayLength.?(env, generation) != 1) return -2;
    const cls = env.*.*.FindClass.?(env, "java/util/ArrayList") orelse return -1;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    if (env.*.*.IsInstanceOf.?(env, list, cls) == 0) return -2;
    if (include_output) {
        const port = outputPort(env) orelse return -1;
        defer env.*.*.DeleteLocalRef.?(env, port);
        const add = env.*.*.GetMethodID.?(env, cls, "add", "(Ljava/lang/Object;)Z") orelse return -1;
        const add_args = [_]jni.jvalue{.{ .l = port }};
        _ = env.*.*.CallBooleanMethodA.?(env, list, add, &add_args);
    }
    if (env.*.*.ExceptionCheck.?(env) != 0) return -1;
    const current = [_]jni.jint{1};
    env.*.*.SetIntArrayRegion.?(env, generation, 0, 1, &current);
    return if (env.*.*.ExceptionCheck.?(env) != 0) -1 else 0;
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

const AudioSystem = struct {
    fn native_getMaxChannelCount(_: Env, _: jni.jclass) callconv(.c) jni.jint {
        return capability(0);
    }
    fn native_getMaxSampleRate(_: Env, _: jni.jclass) callconv(.c) jni.jint {
        return capability(1);
    }
    fn native_getMinSampleRate(_: Env, _: jni.jclass) callconv(.c) jni.jint {
        return capability(2);
    }
    fn muteMicrophone(env: Env, _: jni.jclass, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "muteMicrophone");
        return 0;
    }
    fn isMicrophoneMuted(env: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "isMicrophoneMuted");
        return 0;
    }
    fn isStreamActive(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "isStreamActive");
        return 0;
    }
    fn isStreamActiveRemotely(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "isStreamActiveRemotely");
        return 0;
    }
    fn isSourceActive(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "isSourceActive");
        return 0;
    }
    // Pinned AudioSystem JNI tags: sessions use 1; players and recorders share 7.
    fn uniqueId(env: Env, use: jni.jint) jni.jint {
        const cls = env.*.*.FindClass.?(env, "dev/artzig/system/HostedAudioIds") orelse return 0;
        defer env.*.*.DeleteLocalRef.?(env, cls);
        const next = env.*.*.GetStaticMethodID.?(env, cls, "nextId", "(I)I") orelse return 0;
        const args = [_]jni.jvalue{.{ .i = use }};
        return env.*.*.CallStaticIntMethodA.?(env, cls, next, &args);
    }
    fn newAudioSessionId(env: Env, _: jni.jclass) callconv(.c) jni.jint {
        return uniqueId(env, 1);
    }
    fn newAudioPlayerId(env: Env, _: jni.jclass) callconv(.c) jni.jint {
        const id = uniqueId(env, 7);
        return if (id == 0) -1 else id;
    }
    fn newAudioRecorderId(env: Env, cls: jni.jclass) callconv(.c) jni.jint {
        return newAudioPlayerId(env, cls);
    }
    fn setParameters(env: Env, _: jni.jclass, _: jni.jstring) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setParameters");
        return 0;
    }
    fn getParameters(env: Env, _: jni.jclass, _: jni.jstring) callconv(.c) jni.jstring {
        return env.*.*.NewStringUTF.?(env, "");
    }
    fn setDeviceConnectionState(env: Env, _: jni.jclass, _: jni.jint, _: jni.jobject, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setDeviceConnectionState");
        return 0;
    }
    fn getDeviceConnectionState(env: Env, _: jni.jclass, _: jni.jint, _: jni.jstring) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getDeviceConnectionState");
        return 0;
    }
    fn handleDeviceConfigChange(env: Env, _: jni.jclass, _: jni.jint, _: jni.jstring, _: jni.jstring, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "handleDeviceConfigChange");
        return 0;
    }
    fn setPhoneState(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setPhoneState");
        return 0;
    }
    fn setForceUse(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setForceUse");
        return 0;
    }
    fn getForceUse(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getForceUse");
        return 0;
    }
    fn setDeviceAbsoluteVolumeEnabled(env: Env, _: jni.jclass, _: jni.jint, _: jni.jstring, _: jni.jboolean, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setDeviceAbsoluteVolumeEnabled");
        return 0;
    }
    fn initStreamVolume(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "initStreamVolume");
        return 0;
    }
    fn setStreamVolumeIndex(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jboolean, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setStreamVolumeIndex");
        return 0;
    }
    fn getStreamVolumeIndex(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getStreamVolumeIndex");
        return 0;
    }
    fn setVolumeIndexForAttributes(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jint, _: jni.jboolean, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setVolumeIndexForAttributes");
        return 0;
    }
    fn getVolumeIndexForAttributes(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getVolumeIndexForAttributes");
        return 0;
    }
    fn getMinVolumeIndexForAttributes(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getMinVolumeIndexForAttributes");
        return 0;
    }
    fn getMaxVolumeIndexForAttributes(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getMaxVolumeIndexForAttributes");
        return 0;
    }
    fn setMasterVolume(env: Env, _: jni.jclass, _: jni.jfloat) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setMasterVolume");
        return 0;
    }
    fn getMasterVolume(env: Env, _: jni.jclass) callconv(.c) jni.jfloat {
        unsupported(env, "android.media.AudioSystem", "getMasterVolume");
        return 0;
    }
    fn setMasterMute(env: Env, _: jni.jclass, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setMasterMute");
        return 0;
    }
    fn getMasterMute(env: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "getMasterMute");
        return 0;
    }
    fn getDevicesForAttributes(env: Env, _: jni.jclass, attributes: jni.jobject, devices: jni.jobjectArray, _: jni.jboolean) callconv(.c) jni.jint {
        // Pinned AudioSystem JNI array validation and device conversion. The existing
        // host PCM output is the sole route, with the same native type/address as listAudioPorts.
        if (devices == null or env.*.*.GetArrayLength.?(env, devices) == 0 or attributes == null) return -2;
        const cls = env.*.*.FindClass.?(env, "android/media/AudioDeviceAttributes") orelse return -1;
        defer env.*.*.DeleteLocalRef.?(env, cls);
        const constructor = env.*.*.GetMethodID.?(env, cls, "<init>", "(ILjava/lang/String;Ljava/lang/String;)V") orelse return -1;
        const address = env.*.*.NewStringUTF.?(env, "") orelse return -1;
        defer env.*.*.DeleteLocalRef.?(env, address);
        const name = env.*.*.NewStringUTF.?(env, "artzig") orelse return -1;
        defer env.*.*.DeleteLocalRef.?(env, name);
        const args = [_]jni.jvalue{ .{ .i = capability(3) }, .{ .l = address }, .{ .l = name } };
        const device = env.*.*.NewObjectA.?(env, cls, constructor, &args) orelse return -1;
        defer env.*.*.DeleteLocalRef.?(env, device);
        env.*.*.SetObjectArrayElement.?(env, devices, 0, device);
        return if (env.*.*.ExceptionCheck.?(env) != 0) -1 else 0;
    }
    fn getMasterMono(env: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "getMasterMono");
        return 0;
    }
    fn setMasterMono(env: Env, _: jni.jclass, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setMasterMono");
        return 0;
    }
    fn setRttEnabled(env: Env, _: jni.jclass, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setRttEnabled");
        return 0;
    }
    fn getMasterBalance(env: Env, _: jni.jclass) callconv(.c) jni.jfloat {
        unsupported(env, "android.media.AudioSystem", "getMasterBalance");
        return 0;
    }
    fn setMasterBalance(env: Env, _: jni.jclass, _: jni.jfloat) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setMasterBalance");
        return 0;
    }
    fn getPrimaryOutputSamplingRate(env: Env, _: jni.jclass) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getPrimaryOutputSamplingRate");
        return 0;
    }
    fn getPrimaryOutputFrameCount(env: Env, _: jni.jclass) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getPrimaryOutputFrameCount");
        return 0;
    }
    fn getOutputLatency(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getOutputLatency");
        return 0;
    }
    fn setLowRamDevice(env: Env, _: jni.jclass, _: jni.jboolean, _: jni.jlong) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setLowRamDevice");
        return 0;
    }
    fn checkAudioFlinger(env: Env, _: jni.jclass) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "checkAudioFlinger");
        return 0;
    }
    fn setAudioFlingerBinder(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) void {
        unsupported(env, "android.media.AudioSystem", "setAudioFlingerBinder");
    }
    fn listAudioPorts(env: Env, _: jni.jclass, a0: jni.jobject, a1: jni.jintArray) callconv(.c) jni.jint {
        return listPorts(env, a0, a1, true);
    }
    fn getSupportedDeviceTypes(env: Env, _: jni.jclass, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getSupportedDeviceTypes");
        return 0;
    }
    fn createAudioPatch(env: Env, _: jni.jclass, _: jni.jobjectArray, _: jni.jobjectArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "createAudioPatch");
        return 0;
    }
    fn releaseAudioPatch(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "releaseAudioPatch");
        return 0;
    }
    fn listAudioPatches(env: Env, _: jni.jclass, a0: jni.jobject, a1: jni.jintArray) callconv(.c) jni.jint {
        return listPorts(env, a0, a1, false);
    }
    fn setAudioPortConfig(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setAudioPortConfig");
        return 0;
    }
    fn startAudioSource(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "startAudioSource");
        return 0;
    }
    fn stopAudioSource(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "stopAudioSource");
        return 0;
    }
    fn native_register_dynamic_policy_callback(env: Env, _: jni.jclass) callconv(.c) void {
        unsupported(env, "android.media.AudioSystem", "native_register_dynamic_policy_callback");
    }
    fn native_register_recording_callback(env: Env, _: jni.jclass) callconv(.c) void {
        unsupported(env, "android.media.AudioSystem", "native_register_recording_callback");
    }
    fn native_register_routing_callback(env: Env, _: jni.jclass) callconv(.c) void {
        unsupported(env, "android.media.AudioSystem", "native_register_routing_callback");
    }
    fn native_register_vol_range_init_req_callback(env: Env, _: jni.jclass) callconv(.c) void {
        unsupported(env, "android.media.AudioSystem", "native_register_vol_range_init_req_callback");
    }
    fn getAudioHwSyncForSession(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getAudioHwSyncForSession");
        return 0;
    }
    fn registerPolicyMixes(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "registerPolicyMixes");
        return 0;
    }
    fn getRegisteredPolicyMixes(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getRegisteredPolicyMixes");
        return 0;
    }
    fn updatePolicyMixes(env: Env, _: jni.jclass, _: jni.jobjectArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "updatePolicyMixes");
        return 0;
    }
    fn setUidDeviceAffinities(env: Env, _: jni.jclass, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setUidDeviceAffinities");
        return 0;
    }
    fn removeUidDeviceAffinities(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "removeUidDeviceAffinities");
        return 0;
    }
    fn setUserIdDeviceAffinities(env: Env, _: jni.jclass, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setUserIdDeviceAffinities");
        return 0;
    }
    fn removeUserIdDeviceAffinities(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "removeUserIdDeviceAffinities");
        return 0;
    }
    fn systemReady(env: Env, _: jni.jclass) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "systemReady");
        return 0;
    }
    fn getStreamVolumeDB(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jfloat {
        unsupported(env, "android.media.AudioSystem", "getStreamVolumeDB");
        return 0;
    }
    fn setSupportedSystemUsages(env: Env, _: jni.jclass, _: jni.jintArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setSupportedSystemUsages");
        return 0;
    }
    fn setAllowedCapturePolicy(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setAllowedCapturePolicy");
        return 0;
    }
    fn getDirectPlaybackSupport(_: Env, _: jni.jclass, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jint {
        return 0;
    }
    fn native_get_offload_support(_: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        return 0;
    }
    fn getMicrophones(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getMicrophones");
        return 0;
    }
    fn getSurroundFormats(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getSurroundFormats");
        return 0;
    }
    fn getReportedSurroundFormats(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getReportedSurroundFormats");
        return 0;
    }
    fn getHwOffloadFormatsSupportedForBluetoothMedia(env: Env, _: jni.jclass, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getHwOffloadFormatsSupportedForBluetoothMedia");
        return 0;
    }
    fn setSurroundFormatEnabled(env: Env, _: jni.jclass, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setSurroundFormatEnabled");
        return 0;
    }
    fn setActiveAssistantServicesUids(env: Env, _: jni.jclass, _: jni.jintArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setActiveAssistantServicesUids");
        return 0;
    }
    fn setAssistantServicesUids(env: Env, _: jni.jclass, _: jni.jintArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setAssistantServicesUids");
        return 0;
    }
    fn setA11yServicesUids(env: Env, _: jni.jclass, _: jni.jintArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setA11yServicesUids");
        return 0;
    }
    fn setCurrentImeUid(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setCurrentImeUid");
        return 0;
    }
    fn isHapticPlaybackSupported(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        return jni.JNI_FALSE;
    }
    fn isUltrasoundSupported(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        return jni.JNI_FALSE;
    }
    fn setAudioHalPids(env: Env, _: jni.jclass, _: jni.jintArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setAudioHalPids");
        return 0;
    }
    fn isCallScreeningModeSupported(_: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        return jni.JNI_FALSE;
    }
    fn setDevicesRoleForStrategy(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setDevicesRoleForStrategy");
        return 0;
    }
    fn removeDevicesRoleForStrategy(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "removeDevicesRoleForStrategy");
        return 0;
    }
    fn clearDevicesRoleForStrategy(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "clearDevicesRoleForStrategy");
        return 0;
    }
    fn getDevicesForRoleAndStrategy(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getDevicesForRoleAndStrategy");
        return 0;
    }
    fn setDevicesRoleForCapturePreset(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setDevicesRoleForCapturePreset");
        return 0;
    }
    fn addDevicesRoleForCapturePreset(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "addDevicesRoleForCapturePreset");
        return 0;
    }
    fn removeDevicesRoleForCapturePreset(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobjectArray) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "removeDevicesRoleForCapturePreset");
        return 0;
    }
    fn clearDevicesRoleForCapturePreset(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "clearDevicesRoleForCapturePreset");
        return 0;
    }
    fn getDevicesForRoleAndCapturePreset(env: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getDevicesForRoleAndCapturePreset");
        return 0;
    }
    fn setVibratorInfos(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setVibratorInfos");
        return 0;
    }
    fn nativeGetSpatializer(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jobject {
        unsupported(env, "android.media.AudioSystem", "nativeGetSpatializer");
        return null;
    }
    fn canBeSpatialized(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jobject, _: jni.jobjectArray) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "canBeSpatialized");
        return 0;
    }
    fn nativeGetSoundDose(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jobject {
        unsupported(env, "android.media.AudioSystem", "nativeGetSoundDose");
        return null;
    }
    fn getDirectProfilesForAttributes(env: Env, _: jni.jclass, attributes: jni.jobject, profiles: jni.jobject) callconv(.c) jni.jint {
        // Pinned android_media_AudioSystem_getDirectProfilesForAttributes validates
        // the arguments and appends supported profiles. The existing PCM mixer
        // has no direct/offload profiles, so a valid caller's list stays intact.
        if (attributes == null or profiles == null) return -2;
        const cls = env.*.*.FindClass.?(env, "java/util/ArrayList") orelse return -1;
        defer env.*.*.DeleteLocalRef.?(env, cls);
        if (env.*.*.IsInstanceOf.?(env, profiles, cls) == 0) return -2;
        return 0;
    }
    fn getSupportedMixerAttributes(env: Env, _: jni.jclass, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getSupportedMixerAttributes");
        return 0;
    }
    fn setPreferredMixerAttributes(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jint, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setPreferredMixerAttributes");
        return 0;
    }
    fn getPreferredMixerAttributes(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jint, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "getPreferredMixerAttributes");
        return 0;
    }
    fn clearPreferredMixerAttributes(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "clearPreferredMixerAttributes");
        return 0;
    }
    fn supportsBluetoothVariableLatency(env: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "supportsBluetoothVariableLatency");
        return 0;
    }
    fn setBluetoothVariableLatencyEnabled(env: Env, _: jni.jclass, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "setBluetoothVariableLatencyEnabled");
        return 0;
    }
    fn isBluetoothVariableLatencyEnabled(env: Env, _: jni.jclass) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioSystem", "isBluetoothVariableLatencyEnabled");
        return 0;
    }
    fn listenForSystemPropertyChange(env: Env, _: jni.jclass, _: jni.jstring, _: jni.jobject) callconv(.c) jni.jlong {
        unsupported(env, "android.media.AudioSystem", "listenForSystemPropertyChange");
        return 0;
    }
    fn triggerSystemPropertyUpdate(env: Env, _: jni.jclass, _: jni.jlong) callconv(.c) void {
        unsupported(env, "android.media.AudioSystem", "triggerSystemPropertyUpdate");
    }
    fn registerAudioVolumeGroupCallback(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "registerAudioVolumeGroupCallback");
        return 0;
    }
    fn unregisterAudioVolumeGroupCallback(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioSystem", "unregisterAudioVolumeGroupCallback");
        return 0;
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_getMaxChannelCount", "()I", &native_getMaxChannelCount),
        method("native_getMaxSampleRate", "()I", &native_getMaxSampleRate),
        method("native_getMinSampleRate", "()I", &native_getMinSampleRate),
        method("muteMicrophone", "(Z)I", &muteMicrophone),
        method("isMicrophoneMuted", "()Z", &isMicrophoneMuted),
        method("isStreamActive", "(II)Z", &isStreamActive),
        method("isStreamActiveRemotely", "(II)Z", &isStreamActiveRemotely),
        method("isSourceActive", "(I)Z", &isSourceActive),
        method("newAudioSessionId", "()I", &newAudioSessionId),
        method("newAudioPlayerId", "()I", &newAudioPlayerId),
        method("newAudioRecorderId", "()I", &newAudioRecorderId),
        method("setParameters", "(Ljava/lang/String;)I", &setParameters),
        method("getParameters", "(Ljava/lang/String;)Ljava/lang/String;", &getParameters),
        method("setDeviceConnectionState", "(ILandroid/os/Parcel;IZ)I", &setDeviceConnectionState),
        method("getDeviceConnectionState", "(ILjava/lang/String;)I", &getDeviceConnectionState),
        method("handleDeviceConfigChange", "(ILjava/lang/String;Ljava/lang/String;I)I", &handleDeviceConfigChange),
        method("setPhoneState", "(II)I", &setPhoneState),
        method("setForceUse", "(II)I", &setForceUse),
        method("getForceUse", "(I)I", &getForceUse),
        method("setDeviceAbsoluteVolumeEnabled", "(ILjava/lang/String;ZI)I", &setDeviceAbsoluteVolumeEnabled),
        method("initStreamVolume", "(III)I", &initStreamVolume),
        method("setStreamVolumeIndex", "(IIZI)I", &setStreamVolumeIndex),
        method("getStreamVolumeIndex", "(II)I", &getStreamVolumeIndex),
        method("setVolumeIndexForAttributes", "(Landroid/media/AudioAttributes;IZI)I", &setVolumeIndexForAttributes),
        method("getVolumeIndexForAttributes", "(Landroid/media/AudioAttributes;I)I", &getVolumeIndexForAttributes),
        method("getMinVolumeIndexForAttributes", "(Landroid/media/AudioAttributes;)I", &getMinVolumeIndexForAttributes),
        method("getMaxVolumeIndexForAttributes", "(Landroid/media/AudioAttributes;)I", &getMaxVolumeIndexForAttributes),
        method("setMasterVolume", "(F)I", &setMasterVolume),
        method("getMasterVolume", "()F", &getMasterVolume),
        method("setMasterMute", "(Z)I", &setMasterMute),
        method("getMasterMute", "()Z", &getMasterMute),
        method("getDevicesForAttributes", "(Landroid/media/AudioAttributes;[Landroid/media/AudioDeviceAttributes;Z)I", &getDevicesForAttributes),
        method("getMasterMono", "()Z", &getMasterMono),
        method("setMasterMono", "(Z)I", &setMasterMono),
        method("setRttEnabled", "(Z)I", &setRttEnabled),
        method("getMasterBalance", "()F", &getMasterBalance),
        method("setMasterBalance", "(F)I", &setMasterBalance),
        method("getPrimaryOutputSamplingRate", "()I", &getPrimaryOutputSamplingRate),
        method("getPrimaryOutputFrameCount", "()I", &getPrimaryOutputFrameCount),
        method("getOutputLatency", "(I)I", &getOutputLatency),
        method("setLowRamDevice", "(ZJ)I", &setLowRamDevice),
        method("checkAudioFlinger", "()I", &checkAudioFlinger),
        method("setAudioFlingerBinder", "(Landroid/os/IBinder;)V", &setAudioFlingerBinder),
        method("listAudioPorts", "(Ljava/util/ArrayList;[I)I", &listAudioPorts),
        method("getSupportedDeviceTypes", "(ILandroid/util/IntArray;)I", &getSupportedDeviceTypes),
        method("createAudioPatch", "([Landroid/media/AudioPatch;[Landroid/media/AudioPortConfig;[Landroid/media/AudioPortConfig;)I", &createAudioPatch),
        method("releaseAudioPatch", "(Landroid/media/AudioPatch;)I", &releaseAudioPatch),
        method("listAudioPatches", "(Ljava/util/ArrayList;[I)I", &listAudioPatches),
        method("setAudioPortConfig", "(Landroid/media/AudioPortConfig;)I", &setAudioPortConfig),
        method("startAudioSource", "(Landroid/media/AudioPortConfig;Landroid/media/AudioAttributes;)I", &startAudioSource),
        method("stopAudioSource", "(I)I", &stopAudioSource),
        method("native_register_dynamic_policy_callback", "()V", &native_register_dynamic_policy_callback),
        method("native_register_recording_callback", "()V", &native_register_recording_callback),
        method("native_register_routing_callback", "()V", &native_register_routing_callback),
        method("native_register_vol_range_init_req_callback", "()V", &native_register_vol_range_init_req_callback),
        method("getAudioHwSyncForSession", "(I)I", &getAudioHwSyncForSession),
        method("registerPolicyMixes", "(Ljava/util/ArrayList;Z)I", &registerPolicyMixes),
        method("getRegisteredPolicyMixes", "(Ljava/util/List;)I", &getRegisteredPolicyMixes),
        method("updatePolicyMixes", "([Landroid/media/audiopolicy/AudioMix;[Landroid/media/audiopolicy/AudioMixingRule;)I", &updatePolicyMixes),
        method("setUidDeviceAffinities", "(I[I[Ljava/lang/String;)I", &setUidDeviceAffinities),
        method("removeUidDeviceAffinities", "(I)I", &removeUidDeviceAffinities),
        method("setUserIdDeviceAffinities", "(I[I[Ljava/lang/String;)I", &setUserIdDeviceAffinities),
        method("removeUserIdDeviceAffinities", "(I)I", &removeUserIdDeviceAffinities),
        method("systemReady", "()I", &systemReady),
        method("getStreamVolumeDB", "(III)F", &getStreamVolumeDB),
        method("setSupportedSystemUsages", "([I)I", &setSupportedSystemUsages),
        method("setAllowedCapturePolicy", "(II)I", &setAllowedCapturePolicy),
        method("getDirectPlaybackSupport", "(Landroid/media/AudioFormat;Landroid/media/AudioAttributes;)I", &getDirectPlaybackSupport),
        method("native_get_offload_support", "(IIIII)I", &native_get_offload_support),
        method("getMicrophones", "(Ljava/util/ArrayList;)I", &getMicrophones),
        method("getSurroundFormats", "(Ljava/util/Map;)I", &getSurroundFormats),
        method("getReportedSurroundFormats", "(Ljava/util/ArrayList;)I", &getReportedSurroundFormats),
        method("getHwOffloadFormatsSupportedForBluetoothMedia", "(ILjava/util/ArrayList;)I", &getHwOffloadFormatsSupportedForBluetoothMedia),
        method("setSurroundFormatEnabled", "(IZ)I", &setSurroundFormatEnabled),
        method("setActiveAssistantServicesUids", "([I)I", &setActiveAssistantServicesUids),
        method("setAssistantServicesUids", "([I)I", &setAssistantServicesUids),
        method("setA11yServicesUids", "([I)I", &setA11yServicesUids),
        method("setCurrentImeUid", "(I)I", &setCurrentImeUid),
        method("isHapticPlaybackSupported", "()Z", &isHapticPlaybackSupported),
        method("isUltrasoundSupported", "()Z", &isUltrasoundSupported),
        method("setAudioHalPids", "([I)I", &setAudioHalPids),
        method("isCallScreeningModeSupported", "()Z", &isCallScreeningModeSupported),
        method("setDevicesRoleForStrategy", "(II[I[Ljava/lang/String;)I", &setDevicesRoleForStrategy),
        method("removeDevicesRoleForStrategy", "(II[I[Ljava/lang/String;)I", &removeDevicesRoleForStrategy),
        method("clearDevicesRoleForStrategy", "(II)I", &clearDevicesRoleForStrategy),
        method("getDevicesForRoleAndStrategy", "(IILjava/util/List;)I", &getDevicesForRoleAndStrategy),
        method("setDevicesRoleForCapturePreset", "(II[I[Ljava/lang/String;)I", &setDevicesRoleForCapturePreset),
        method("addDevicesRoleForCapturePreset", "(II[I[Ljava/lang/String;)I", &addDevicesRoleForCapturePreset),
        method("removeDevicesRoleForCapturePreset", "(II[I[Ljava/lang/String;)I", &removeDevicesRoleForCapturePreset),
        method("clearDevicesRoleForCapturePreset", "(II)I", &clearDevicesRoleForCapturePreset),
        method("getDevicesForRoleAndCapturePreset", "(IILjava/util/List;)I", &getDevicesForRoleAndCapturePreset),
        method("setVibratorInfos", "(Ljava/util/List;)I", &setVibratorInfos),
        method("nativeGetSpatializer", "(Landroid/media/INativeSpatializerCallback;)Landroid/os/IBinder;", &nativeGetSpatializer),
        method("canBeSpatialized", "(Landroid/media/AudioAttributes;Landroid/media/AudioFormat;[Landroid/media/AudioDeviceAttributes;)Z", &canBeSpatialized),
        method("nativeGetSoundDose", "(Landroid/media/ISoundDoseCallback;)Landroid/os/IBinder;", &nativeGetSoundDose),
        method("getDirectProfilesForAttributes", "(Landroid/media/AudioAttributes;Ljava/util/ArrayList;)I", &getDirectProfilesForAttributes),
        method("getSupportedMixerAttributes", "(ILjava/util/List;)I", &getSupportedMixerAttributes),
        method("setPreferredMixerAttributes", "(Landroid/media/AudioAttributes;IILandroid/media/AudioMixerAttributes;)I", &setPreferredMixerAttributes),
        method("getPreferredMixerAttributes", "(Landroid/media/AudioAttributes;ILjava/util/List;)I", &getPreferredMixerAttributes),
        method("clearPreferredMixerAttributes", "(Landroid/media/AudioAttributes;II)I", &clearPreferredMixerAttributes),
        method("supportsBluetoothVariableLatency", "()Z", &supportsBluetoothVariableLatency),
        method("setBluetoothVariableLatencyEnabled", "(Z)I", &setBluetoothVariableLatencyEnabled),
        method("isBluetoothVariableLatencyEnabled", "()Z", &isBluetoothVariableLatencyEnabled),
        method("listenForSystemPropertyChange", "(Ljava/lang/String;Ljava/lang/Runnable;)J", &listenForSystemPropertyChange),
        method("triggerSystemPropertyUpdate", "(J)V", &triggerSystemPropertyUpdate),
        method("registerAudioVolumeGroupCallback", "(Landroid/media/INativeAudioVolumeGroupCallback;)I", &registerAudioVolumeGroupCallback),
        method("unregisterAudioVolumeGroupCallback", "(Landroid/media/INativeAudioVolumeGroupCallback;)I", &unregisterAudioVolumeGroupCallback),
    };
};

const AudioRecord = struct {
    fn native_getMetrics(env: Env, _: jni.jobject) callconv(.c) jni.jobject {
        unsupported(env, "android.media.AudioRecord", "native_getMetrics");
        return null;
    }
    fn native_setup(_: Env, _: jni.jobject, _: jni.jobject, _: jni.jobject, _: jni.jintArray, _: jni.jint, _: jni.jint, _: jni.jint, _: jni.jint, _: jni.jintArray, _: jni.jobject, _: jni.jlong, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        return -20; // AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED
    }
    fn native_finalize(_: Env, _: jni.jobject) callconv(.c) void {
        // No native recorder exists to release.
    }
    fn native_release(_: Env, _: jni.jobject) callconv(.c) void {
        // No native recorder exists to release.
    }
    fn native_start(env: Env, _: jni.jobject, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_start");
        return 0;
    }
    fn native_stop(env: Env, _: jni.jobject) callconv(.c) void {
        unsupported(env, "android.media.AudioRecord", "native_stop");
    }
    fn native_read_in_byte_array(env: Env, _: jni.jobject, _: jni.jbyteArray, _: jni.jint, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_read_in_byte_array");
        return 0;
    }
    fn native_read_in_short_array(env: Env, _: jni.jobject, _: jni.jshortArray, _: jni.jint, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_read_in_short_array");
        return 0;
    }
    fn native_read_in_float_array(env: Env, _: jni.jobject, _: jni.jfloatArray, _: jni.jint, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_read_in_float_array");
        return 0;
    }
    fn native_read_in_direct_buffer(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_read_in_direct_buffer");
        return 0;
    }
    fn native_get_buffer_size_in_frames(env: Env, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_get_buffer_size_in_frames");
        return 0;
    }
    fn native_set_marker_pos(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_set_marker_pos");
        return 0;
    }
    fn native_get_marker_pos(env: Env, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_get_marker_pos");
        return 0;
    }
    fn native_set_pos_update_period(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_set_pos_update_period");
        return 0;
    }
    fn native_get_pos_update_period(env: Env, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_get_pos_update_period");
        return 0;
    }
    fn native_get_min_buff_size(_: Env, _: jni.jclass, _: jni.jint, _: jni.jint, _: jni.jint) callconv(.c) jni.jint {
        return -1; // AudioRecord::getMinFrameCount: no recording service
    }
    fn native_setInputDevice(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jboolean {
        unsupported(env, "android.media.AudioRecord", "native_setInputDevice");
        return 0;
    }
    fn native_getRoutedDeviceIds(env: Env, _: jni.jobject) callconv(.c) jni.jintArray {
        unsupported(env, "android.media.AudioRecord", "native_getRoutedDeviceIds");
        return null;
    }
    fn native_enableDeviceCallback(env: Env, _: jni.jobject) callconv(.c) void {
        unsupported(env, "android.media.AudioRecord", "native_enableDeviceCallback");
    }
    fn native_disableDeviceCallback(env: Env, _: jni.jobject) callconv(.c) void {
        unsupported(env, "android.media.AudioRecord", "native_disableDeviceCallback");
    }
    fn native_get_timestamp(env: Env, _: jni.jobject, _: jni.jobject, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_get_timestamp");
        return 0;
    }
    fn native_get_active_microphones(env: Env, _: jni.jobject, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_get_active_microphones");
        return 0;
    }
    fn native_getPortId(env: Env, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_getPortId");
        return 0;
    }
    fn native_set_preferred_microphone_direction(env: Env, _: jni.jobject, _: jni.jint) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_set_preferred_microphone_direction");
        return 0;
    }
    fn native_set_preferred_microphone_field_dimension(env: Env, _: jni.jobject, _: jni.jfloat) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_set_preferred_microphone_field_dimension");
        return 0;
    }
    fn native_setLogSessionId(env: Env, _: jni.jobject, _: jni.jstring) callconv(.c) void {
        unsupported(env, "android.media.AudioRecord", "native_setLogSessionId");
    }
    fn native_shareAudioHistory(env: Env, _: jni.jobject, _: jni.jstring, _: jni.jlong) callconv(.c) jni.jint {
        unsupported(env, "android.media.AudioRecord", "native_shareAudioHistory");
        return 0;
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_getMetrics", "()Landroid/os/PersistableBundle;", &native_getMetrics),
        method("native_setup", "(Ljava/lang/Object;Ljava/lang/Object;[IIIII[ILandroid/os/Parcel;JII)I", &native_setup),
        method("native_finalize", "()V", &native_finalize),
        method("native_release", "()V", &native_release),
        method("native_start", "(II)I", &native_start),
        method("native_stop", "()V", &native_stop),
        method("native_read_in_byte_array", "([BIIZ)I", &native_read_in_byte_array),
        method("native_read_in_short_array", "([SIIZ)I", &native_read_in_short_array),
        method("native_read_in_float_array", "([FIIZ)I", &native_read_in_float_array),
        method("native_read_in_direct_buffer", "(Ljava/lang/Object;IZ)I", &native_read_in_direct_buffer),
        method("native_get_buffer_size_in_frames", "()I", &native_get_buffer_size_in_frames),
        method("native_set_marker_pos", "(I)I", &native_set_marker_pos),
        method("native_get_marker_pos", "()I", &native_get_marker_pos),
        method("native_set_pos_update_period", "(I)I", &native_set_pos_update_period),
        method("native_get_pos_update_period", "()I", &native_get_pos_update_period),
        method("native_get_min_buff_size", "(III)I", &native_get_min_buff_size),
        method("native_setInputDevice", "(I)Z", &native_setInputDevice),
        method("native_getRoutedDeviceIds", "()[I", &native_getRoutedDeviceIds),
        method("native_enableDeviceCallback", "()V", &native_enableDeviceCallback),
        method("native_disableDeviceCallback", "()V", &native_disableDeviceCallback),
        method("native_get_timestamp", "(Landroid/media/AudioTimestamp;I)I", &native_get_timestamp),
        method("native_get_active_microphones", "(Ljava/util/ArrayList;)I", &native_get_active_microphones),
        method("native_getPortId", "()I", &native_getPortId),
        method("native_set_preferred_microphone_direction", "(I)I", &native_set_preferred_microphone_direction),
        method("native_set_preferred_microphone_field_dimension", "(F)I", &native_set_preferred_microphone_field_dimension),
        method("native_setLogSessionId", "(Ljava/lang/String;)V", &native_setLogSessionId),
        method("native_shareAudioHistory", "(Ljava/lang/String;J)I", &native_shareAudioHistory),
    };
};

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

const AudioPortEventHandler = struct {
    fn native_setup(_: Env, _: jni.jobject, _: jni.jobject) callconv(.c) void {
        // Fixed logical output topology; Java dispatches initial-listener callbacks.
    }
    fn native_finalize(_: Env, _: jni.jobject) callconv(.c) void {
        // Fixed logical output topology; Java dispatches initial-listener callbacks.
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_setup", "(Ljava/lang/Object;)V", &native_setup),
        method("native_finalize", "()V", &native_finalize),
    };
};

// Pinned AidlConversionCppNdk.cpp enum switches. Both wire representations use
// identical numeric values; INVALID and values outside the switch are errors.
const AudioEnum = enum { encapsulation, stream, usage };
fn convertEnum(env: Env, value: jni.jint, kind: AudioEnum, legacy: bool) jni.jint {
    const valid = switch (kind) {
        .encapsulation => value >= 0 and value <= 2,
        .stream => value >= -1 and value <= 14,
        .usage => (value >= 0 and value <= 17) or (value >= 1000 and value <= 1004),
    };
    if (valid) return value;
    const type_name: []const u8 = switch (kind) {
        .encapsulation => if (legacy) "audio_encapsulation_mode_t" else "AudioEncapsulationMode",
        .stream => if (legacy) "audio_stream_type_t" else "AudioStreamType",
        .usage => if (legacy) "audio_usage_t" else "AudioUsage",
    };
    var buffer: [128]u8 = undefined;
    const message = std.mem.printSentinel(&buffer, "Failed to convert {s}{s} value {d}", .{ if (legacy) "legacy " else "", type_name, value }, 0) catch unreachable;
    throw(env, "java/lang/IllegalArgumentException", message);
    return switch (kind) {
        .encapsulation => if (legacy) -1 else 0,
        .stream => if (legacy) -2 else -1,
        .usage => if (legacy) -1 else 0,
    };
}

const AidlConversion = struct {
    fn aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t(env: Env, _: jni.jclass, value: jni.jint) callconv(.c) jni.jint {
        return convertEnum(env, value, .encapsulation, false);
    }
    fn legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode(env: Env, _: jni.jclass, value: jni.jint) callconv(.c) jni.jint {
        return convertEnum(env, value, .encapsulation, true);
    }
    fn aidl2legacy_AudioStreamType_audio_stream_type_t(env: Env, _: jni.jclass, value: jni.jint) callconv(.c) jni.jint {
        return convertEnum(env, value, .stream, false);
    }
    fn legacy2aidl_audio_stream_type_t_AudioStreamType(env: Env, _: jni.jclass, value: jni.jint) callconv(.c) jni.jint {
        return convertEnum(env, value, .stream, true);
    }
    fn aidl2legacy_AudioUsage_audio_usage_t(env: Env, _: jni.jclass, value: jni.jint) callconv(.c) jni.jint {
        return convertEnum(env, value, .usage, false);
    }
    fn legacy2aidl_audio_usage_t_AudioUsage(env: Env, _: jni.jclass, value: jni.jint) callconv(.c) jni.jint {
        return convertEnum(env, value, .usage, true);
    }
    fn aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t(env: Env, _: jni.jclass, _: jni.jobject, _: jni.jboolean) callconv(.c) jni.jint {
        unsupported(env, "android.media.audio.common.AidlConversion", "aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t");
        return 0;
    }
    fn legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel(env: Env, _: jni.jclass, _: jni.jint, _: jni.jboolean) callconv(.c) jni.jobject {
        unsupported(env, "android.media.audio.common.AidlConversion", "legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel");
        return null;
    }
    fn aidl2legacy_AudioFormatDescription_Parcel_audio_format_t(env: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        unsupported(env, "android.media.audio.common.AidlConversion", "aidl2legacy_AudioFormatDescription_Parcel_audio_format_t");
        return 0;
    }
    fn legacy2aidl_audio_format_t_AudioFormatDescription_Parcel(env: Env, _: jni.jclass, _: jni.jint) callconv(.c) jni.jobject {
        unsupported(env, "android.media.audio.common.AidlConversion", "legacy2aidl_audio_format_t_AudioFormatDescription_Parcel");
        return null;
    }
    const methods = [_]jni.JNINativeMethod{
        method("aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t", "(I)I", &aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t),
        method("legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode", "(I)I", &legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode),
        method("aidl2legacy_AudioStreamType_audio_stream_type_t", "(I)I", &aidl2legacy_AudioStreamType_audio_stream_type_t),
        method("legacy2aidl_audio_stream_type_t_AudioStreamType", "(I)I", &legacy2aidl_audio_stream_type_t_AudioStreamType),
        method("aidl2legacy_AudioUsage_audio_usage_t", "(I)I", &aidl2legacy_AudioUsage_audio_usage_t),
        method("legacy2aidl_audio_usage_t_AudioUsage", "(I)I", &legacy2aidl_audio_usage_t_AudioUsage),
        method("aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t", "(Landroid/os/Parcel;Z)I", &aidl2legacy_AudioChannelLayout_Parcel_audio_channel_mask_t),
        method("legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel", "(IZ)Landroid/os/Parcel;", &legacy2aidl_audio_channel_mask_t_AudioChannelLayout_Parcel),
        method("aidl2legacy_AudioFormatDescription_Parcel_audio_format_t", "(Landroid/os/Parcel;)I", &aidl2legacy_AudioFormatDescription_Parcel_audio_format_t),
        method("legacy2aidl_audio_format_t_AudioFormatDescription_Parcel", "(I)Landroid/os/Parcel;", &legacy2aidl_audio_format_t_AudioFormatDescription_Parcel),
    };
};

const AudioProductStrategy = struct {
    fn native_list_audio_product_strategies(_: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        return -3; // nativeToJavaStatus(NO_INIT): no audio policy service
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_list_audio_product_strategies", "(Ljava/util/ArrayList;)I", &native_list_audio_product_strategies),
    };
};

const AudioVolumeGroup = struct {
    fn native_list_audio_volume_groups(_: Env, _: jni.jclass, _: jni.jobject) callconv(.c) jni.jint {
        return -3; // nativeToJavaStatus(NO_INIT): no audio policy service
    }
    const methods = [_]jni.JNINativeMethod{
        method("native_list_audio_volume_groups", "(Ljava/util/ArrayList;)I", &native_list_audio_volume_groups),
    };
};

pub fn register(env: Env, platform: *std.DynLib) !void {
    capability = platform.lookup(Capability, "azk_audio_framework_capability") orelse return error.NoMethod;
    // AudioSystem capabilities must precede class initialization in the other clients.
    inline for (.{
        .{ "android/media/AudioSystem", &AudioSystem.methods },
        .{ "android/media/AudioRecord", &AudioRecord.methods },
        .{ "android/media/MediaRecorder", &MediaRecorder.methods },
        .{ "android/media/CamcorderProfile", &CamcorderProfile.methods },
        .{ "android/media/audiofx/AudioEffect", &AudioEffect.methods },
        .{ "android/media/AudioPortEventHandler", &AudioPortEventHandler.methods },
        .{ "android/media/audio/common/AidlConversion", &AidlConversion.methods },
        .{ "android/media/audiopolicy/AudioProductStrategy", &AudioProductStrategy.methods },
        .{ "android/media/audiopolicy/AudioVolumeGroup", &AudioVolumeGroup.methods },
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
