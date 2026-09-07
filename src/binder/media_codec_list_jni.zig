//! Pinned android_media_MediaCodecList.cpp at its native catalogue boundary.
//! The original implementation requires IMediaCodecList, Codec2/OMX services
//! and Android's resource manager. Supply the existing hosted decoder catalogue
//! here; stock Java owns format/profile/level validation and list selection.
//! These are the previous adapter's advertised limits, not hardware discovery.
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Profile = struct { profile: jni.jint, level: jni.jint };
const Entry = struct {
    name: [:0]const u8,
    mime: [:0]const u8,
    backend: [:0]const u8,
    profiles: []const Profile = &.{},
};
const entries = [_]Entry{
    .{ .name = "c2.artzig.aac.decoder", .mime = "audio/mp4a-latm", .backend = "aac", .profiles = &.{ .{ .profile = 2, .level = 0 }, .{ .profile = 5, .level = 0 }, .{ .profile = 29, .level = 0 } } },
    .{ .name = "c2.artzig.opus.decoder", .mime = "audio/opus", .backend = "opus" },
    .{ .name = "c2.artzig.mp3.decoder", .mime = "audio/mpeg", .backend = "mp3" },
    .{ .name = "c2.artzig.flac.decoder", .mime = "audio/flac", .backend = "flac" },
    .{ .name = "c2.artzig.vorbis.decoder", .mime = "audio/vorbis", .backend = "vorbis" },
    .{ .name = "c2.artzig.avc.decoder", .mime = "video/avc", .backend = "h264", .profiles = &.{ .{ .profile = 1, .level = 0x10000 }, .{ .profile = 2, .level = 0x10000 }, .{ .profile = 8, .level = 0x10000 } } },
    .{ .name = "c2.artzig.vp9.decoder", .mime = "video/x-vnd.on2.vp9", .backend = "vp9", .profiles = &.{.{ .profile = 1, .level = 0x2000 }} },
    .{ .name = "c2.artzig.mpeg4.decoder", .mime = "video/mp4v-es", .backend = "mpeg4" },
};

fn badArgument(env: Env) void {
    const cls = env.*.*.FindClass.?(env, "java/lang/IllegalArgumentException") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, null);
}
fn entryAt(env: Env, index: jni.jint) ?*const Entry {
    if (index < 0 or index >= entries.len) {
        badArgument(env);
        return null;
    }
    return &entries[@intCast(index)];
}
fn count(_: Env, _: jni.jclass) callconv(.c) jni.jint {
    return entries.len;
}
fn name(env: Env, _: jni.jclass, index: jni.jint) callconv(.c) jni.jstring {
    const entry = entryAt(env, index) orelse return null;
    return env.*.*.NewStringUTF.?(env, entry.name.ptr);
}
fn attributes(env: Env, _: jni.jclass, index: jni.jint) callconv(.c) jni.jint {
    _ = entryAt(env, index) orelse return 0;
    return 1 << 2; // MediaCodecInfo.FLAG_IS_SOFTWARE_ONLY, as in the previous catalogue.
}
fn types(env: Env, _: jni.jclass, index: jni.jint) callconv(.c) jni.jobjectArray {
    const entry = entryAt(env, index) orelse return null;
    const cls = env.*.*.FindClass.?(env, "java/lang/String") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const mime = env.*.*.NewStringUTF.?(env, entry.mime.ptr) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, mime);
    return env.*.*.NewObjectArray.?(env, 1, cls, mime);
}
fn find(env: Env, _: jni.jclass, codec_name: jni.jstring) callconv(.c) jni.jint {
    if (codec_name == null) {
        badArgument(env);
        return -2;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, codec_name, null) orelse return -2;
    defer env.*.*.ReleaseStringUTFChars.?(env, codec_name, chars);
    for (entries, 0..) |entry, index| {
        if (std.mem.eql(u8, entry.name, std.mem.span(chars))) return @intCast(index);
    }
    return -2; // JavaMediaCodecListWrapper::findCodecByName returns -ENOENT.
}
fn backendName(env: Env, cls: jni.jclass, codec_name: jni.jstring) callconv(.c) jni.jstring {
    if (codec_name == null) return null;
    const index = find(env, cls, codec_name);
    if (index < 0) return null;
    return env.*.*.NewStringUTF.?(env, entries[@intCast(index)].backend.ptr);
}
fn decoderName(env: Env, _: jni.jclass, mime: jni.jstring) callconv(.c) jni.jstring {
    if (mime == null) return null;
    const chars = env.*.*.GetStringUTFChars.?(env, mime, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, mime, chars);
    for (entries) |entry| {
        if (std.ascii.eqlIgnoreCase(entry.mime, std.mem.span(chars))) return env.*.*.NewStringUTF.?(env, entry.name.ptr);
    }
    return null;
}
fn newMap(env: Env) jni.jobject {
    const cls = env.*.*.FindClass.?(env, "java/util/HashMap") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const ctor = env.*.*.GetMethodID.?(env, cls, "<init>", "()V") orelse return null;
    return env.*.*.NewObjectA.?(env, cls, ctor, null);
}
fn putString(env: Env, map: jni.jobject, key: [*:0]const u8, value: [*:0]const u8) bool {
    const cls = env.*.*.GetObjectClass.?(env, map) orelse return false;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const put = env.*.*.GetMethodID.?(env, cls, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") orelse return false;
    const k = env.*.*.NewStringUTF.?(env, key) orelse return false;
    defer env.*.*.DeleteLocalRef.?(env, k);
    const v = env.*.*.NewStringUTF.?(env, value) orelse return false;
    defer env.*.*.DeleteLocalRef.?(env, v);
    const args = [_]jni.jvalue{ .{ .l = k }, .{ .l = v } };
    const previous = env.*.*.CallObjectMethodA.?(env, map, put, &args);
    if (previous != null) env.*.*.DeleteLocalRef.?(env, previous);
    return env.*.*.ExceptionCheck.?(env) == 0;
}
fn settings(env: Env, _: jni.jclass) callconv(.c) jni.jobject {
    return newMap(env); // No native global overrides; stock Java supplies its defaults.
}
fn capabilities(env: Env, _: jni.jclass, index: jni.jint, mime: jni.jstring) callconv(.c) jni.jobject {
    if (mime == null) {
        badArgument(env);
        return null;
    }
    const entry = entryAt(env, index) orelse return null;
    const chars = env.*.*.GetStringUTFChars.?(env, mime, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, mime, chars);
    if (!std.ascii.eqlIgnoreCase(entry.mime, std.mem.span(chars))) {
        badArgument(env);
        return null;
    }
    const video = std.mem.startsWith(u8, entry.mime, "video/");
    const defaults = newMap(env) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, defaults);
    if (!putString(env, defaults, "mime", chars)) return null;
    const details = newMap(env) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, details);
    if (video) {
        if (!putString(env, details, "size-range", "2x2-4096x4096") or
            !putString(env, details, "alignment", "2x2") or
            !putString(env, details, "bitrate-range", "1-200000000") or
            !putString(env, details, "frame-rate-range", "1-120")) return null;
    } else {
        if (!putString(env, details, "sample-rate-ranges", "8000-192000") or
            !putString(env, details, "max-channel-count", "8")) return null;
    }
    const profile_cls = env.*.*.FindClass.?(env, "android/media/MediaCodecInfo$CodecProfileLevel") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, profile_cls);
    const profile_field = env.*.*.GetFieldID.?(env, profile_cls, "profile", "I") orelse return null;
    const level_field = env.*.*.GetFieldID.?(env, profile_cls, "level", "I") orelse return null;
    const profiles = env.*.*.NewObjectArray.?(env, @intCast(entry.profiles.len), profile_cls, null) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, profiles);
    for (entry.profiles, 0..) |profile, i| {
        const object = env.*.*.AllocObject.?(env, profile_cls) orelse return null;
        defer env.*.*.DeleteLocalRef.?(env, object);
        env.*.*.SetIntField.?(env, object, profile_field, profile.profile);
        env.*.*.SetIntField.?(env, object, level_field, profile.level);
        env.*.*.SetObjectArrayElement.?(env, profiles, @intCast(i), object);
        if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    }
    // The old adapter advertised these formats only for AVC and VP9.
    const has_colors = video and entry.profiles.len != 0;
    const colors = env.*.*.NewIntArray.?(env, if (has_colors) 2 else 0) orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, colors);
    if (has_colors) {
        const formats = [_]jni.jint{ 0x7f420888, 19 }; // YUV420Flexible, YUV420Planar.
        env.*.*.SetIntArrayRegion.?(env, colors, 0, formats.len, &formats);
        if (env.*.*.ExceptionCheck.?(env) != 0) return null;
    }
    const caps_cls = env.*.*.FindClass.?(env, "android/media/MediaCodecInfo$CodecCapabilities") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, caps_cls);
    const ctor = env.*.*.GetMethodID.?(env, caps_cls, "<init>", "([Landroid/media/MediaCodecInfo$CodecProfileLevel;[IZLjava/util/Map;Ljava/util/Map;)V") orelse return null;
    const args = [_]jni.jvalue{ .{ .l = profiles }, .{ .l = colors }, .{ .z = jni.JNI_FALSE }, .{ .l = defaults }, .{ .l = details } };
    return env.*.*.NewObjectA.?(env, caps_cls, ctor, &args);
}
fn init(_: Env, _: jni.jclass) callconv(.c) void {} // Original native_init is empty.
fn method(comptime n: [*:0]const u8, comptime s: [*:0]const u8, f: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(n), .signature = @constCast(s), .fnPtr = @ptrCast(@constCast(f)) };
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCodecList") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("native_getCodecCount", "()I", &count),
        method("getCodecName", "(I)Ljava/lang/String;", &name),
        method("getCanonicalName", "(I)Ljava/lang/String;", &name),
        method("getAttributes", "(I)I", &attributes),
        method("getSupportedTypes", "(I)[Ljava/lang/String;", &types),
        method("getCodecCapabilities", "(ILjava/lang/String;)Landroid/media/MediaCodecInfo$CodecCapabilities;", &capabilities),
        method("native_getGlobalSettings", "()Ljava/util/Map;", &settings),
        method("findCodecByName", "(Ljava/lang/String;)I", &find),
        method("native_init", "()V", &init),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
    const hosted = env.*.*.FindClass.?(env, "dev/artzig/HostedCodecCatalog") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, hosted);
    const host_methods = [_]jni.JNINativeMethod{
        method("backendNameFor", "(Ljava/lang/String;)Ljava/lang/String;", &backendName),
        method("decoderNameForMime", "(Ljava/lang/String;)Ljava/lang/String;", &decoderName),
    };
    if (env.*.*.RegisterNatives.?(env, hosted, &host_methods, host_methods.len) != 0) return error.RegisterFailed;
}
