//! Pinned android_media_MediaMetadataRetriever.cpp and libmedia's
//! mediametadataretriever.cpp at the media.player service boundary. This host
//! has no metadata retriever service. Model the allocated client with a null
//! remote retriever: setters return INVALID_OPERATION, reads return no data,
//! and release clears the client. No extraction or FFmpeg backend is added.
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
var context_field: jni.jfieldID = null;
var descriptor_field: jni.jfieldID = null;

fn fail(env: Env, class_name: [*:0]const u8, message: ?[*:0]const u8) void {
    if (env.*.*.ExceptionCheck.?(env) != 0) return;
    const cls = env.*.*.FindClass.?(env, class_name) orelse return;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, message);
}
fn available(env: Env, object: jni.jobject) bool {
    if (env.*.*.GetLongField.?(env, object, context_field) != 0) return true;
    fail(env, "java/lang/IllegalStateException", "No retriever available");
    return false;
}
fn noRemote(env: Env) void {
    // process_media_retriever_call maps INVALID_OPERATION to this exception.
    fail(env, "java/lang/IllegalStateException", null);
}
fn init(env: Env, cls: jni.jclass) callconv(.c) void {
    context_field = env.*.*.GetFieldID.?(env, cls, "mNativeContext", "J");
    const fd_class = env.*.*.FindClass.?(env, "java/io/FileDescriptor") orelse return;
    defer env.*.*.DeleteLocalRef.?(env, fd_class);
    descriptor_field = env.*.*.GetFieldID.?(env, fd_class, "descriptor", "I");
}
fn setup(env: Env, object: jni.jobject) callconv(.c) void {
    // A tag for an allocated client with no remote service, never a pointer.
    env.*.*.SetLongField.?(env, object, context_field, 1);
}
fn release(env: Env, object: jni.jobject) callconv(.c) void {
    env.*.*.SetLongField.?(env, object, context_field, 0);
}
fn setFd(env: Env, object: jni.jobject, descriptor: jni.jobject, offset: jni.jlong, length: jni.jlong) callconv(.c) void {
    if (!available(env, object)) return;
    if (descriptor == null or offset < 0 or length < 0 or
        env.*.*.GetIntField.?(env, descriptor, descriptor_field) < 0)
    {
        fail(env, "java/lang/IllegalArgumentException", null);
        return;
    }
    // The descriptor is borrowed; the absent remote neither reads nor owns it.
    noRemote(env);
}
fn setCallback(env: Env, object: jni.jobject, source: jni.jobject) callconv(.c) void {
    if (!available(env, object)) return;
    if (source == null) {
        fail(env, "java/lang/IllegalArgumentException", null);
        return;
    }
    noRemote(env);
}
fn setUri(env: Env, object: jni.jobject, _: jni.jobject, path: jni.jstring, keys: jni.jobjectArray, values: jni.jobjectArray) callconv(.c) void {
    if (!available(env, object)) return;
    if (path == null) {
        fail(env, "java/lang/IllegalArgumentException", "Null pointer");
        return;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, path, null) orelse return;
    const memory_path = std.mem.startsWith(u8, std.mem.span(chars), "mem://");
    env.*.*.ReleaseStringUTFChars.?(env, path, chars);
    if (memory_path) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid pathname");
        return;
    }
    // ConvertKeyValueArraysToKeyedVector validates pairing and converts each
    // string before calling the native client, even when its remote is absent.
    if ((keys == null) != (values == null)) {
        fail(env, "java/lang/IllegalArgumentException", "keys and values arrays have different length");
        return;
    }
    if (keys != null) {
        const count = env.*.*.GetArrayLength.?(env, keys);
        if (count != env.*.*.GetArrayLength.?(env, values)) {
            fail(env, "java/lang/IllegalArgumentException", "keys and values arrays have different length");
            return;
        }
        var index: jni.jsize = 0;
        while (index < count) : (index += 1) {
            for ([_]jni.jobjectArray{ keys, values }) |array| {
                const element = env.*.*.GetObjectArrayElement.?(env, array, index);
                defer env.*.*.DeleteLocalRef.?(env, element);
                const value = env.*.*.GetStringUTFChars.?(env, element, null) orelse return;
                env.*.*.ReleaseStringUTFChars.?(env, element, value);
            }
        }
    }
    noRemote(env);
}
fn metadata(env: Env, object: jni.jobject, _: jni.jint) callconv(.c) jni.jobject {
    _ = available(env, object);
    return null;
}
fn frame(env: Env, object: jni.jobject, _: jni.jlong, _: jni.jint, _: jni.jint, _: jni.jint, _: jni.jobject) callconv(.c) jni.jobject {
    _ = available(env, object);
    return null;
}
fn image(env: Env, object: jni.jobject, _: jni.jint, _: jni.jobject) callconv(.c) jni.jobject {
    _ = available(env, object);
    return null;
}
fn thumbnail(env: Env, object: jni.jobject, _: jni.jint, _: jni.jobject, _: jni.jint, _: jni.jint) callconv(.c) jni.jobject {
    _ = available(env, object);
    return null;
}
fn frames(env: Env, object: jni.jobject, _: jni.jint, _: jni.jint, params: jni.jobject) callconv(.c) jni.jobject {
    if (!available(env, object)) return null;
    // The original frame-list JNI selects the output format before trying
    // to retrieve its first frame, including on this empty-service path.
    if (params != null) {
        const cls = env.*.*.GetObjectClass.?(env, params) orelse return null;
        defer env.*.*.DeleteLocalRef.?(env, cls);
        const preferred = env.*.*.GetFieldID.?(env, cls, "inPreferredConfig", "Landroid/graphics/Bitmap$Config;") orelse return null;
        const actual = env.*.*.GetFieldID.?(env, cls, "outActualConfig", "Landroid/graphics/Bitmap$Config;") orelse return null;
        const config_class = env.*.*.FindClass.?(env, "android/graphics/Bitmap$Config") orelse return null;
        defer env.*.*.DeleteLocalRef.?(env, config_class);
        const rgb_field = env.*.*.GetStaticFieldID.?(env, config_class, "RGB_565", "Landroid/graphics/Bitmap$Config;") orelse return null;
        const rgba_field = env.*.*.GetStaticFieldID.?(env, config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;") orelse return null;
        const rgb = env.*.*.GetStaticObjectField.?(env, config_class, rgb_field);
        defer env.*.*.DeleteLocalRef.?(env, rgb);
        const rgba = env.*.*.GetStaticObjectField.?(env, config_class, rgba_field);
        defer env.*.*.DeleteLocalRef.?(env, rgba);
        const requested = env.*.*.GetObjectField.?(env, params, preferred);
        defer env.*.*.DeleteLocalRef.?(env, requested);
        env.*.*.SetObjectField.?(env, params, actual, if (env.*.*.IsSameObject.?(env, requested, rgb) != 0) rgb else rgba);
    }
    fail(env, "java/lang/IllegalStateException", "No frames from retriever");
    return null;
}
fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, function: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(signature), .fnPtr = @ptrCast(@constCast(function)) };
}
pub fn register(env: Env) !void {
    const cls = env.*.*.FindClass.?(env, "android/media/MediaMetadataRetriever") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{
        method("native_init", "()V", &init),
        method("native_setup", "()V", &setup),
        method("release", "()V", &release),
        method("native_finalize", "()V", &release),
        method("_setDataSource", "(Ljava/io/FileDescriptor;JJ)V", &setFd),
        method("_setDataSource", "(Landroid/media/MediaDataSource;)V", &setCallback),
        method("_setDataSource", "(Landroid/os/IBinder;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V", &setUri),
        method("nativeExtractMetadata", "(I)Ljava/lang/String;", &metadata),
        method("getEmbeddedPicture", "(I)[B", &metadata),
        method("_getFrameAtTime", "(JIIILandroid/media/MediaMetadataRetriever$BitmapParams;)Landroid/graphics/Bitmap;", &frame),
        method("_getImageAtIndex", "(ILandroid/media/MediaMetadataRetriever$BitmapParams;)Landroid/graphics/Bitmap;", &image),
        method("getThumbnailImageAtIndex", "(ILandroid/media/MediaMetadataRetriever$BitmapParams;II)Landroid/graphics/Bitmap;", &thumbnail),
        method("_getFrameAtIndex", "(IILandroid/media/MediaMetadataRetriever$BitmapParams;)Ljava/util/List;", &frames),
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
