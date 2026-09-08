//! Follows pinned android_view_PointerIcon_toNative's field contract. The host
//! uses JVM bitmaps instead of InputReader's C++ PointerIcon/Bitmap objects;
//! resource loading and bitmap conversion remain stock Java operations.
const jni = @import("art").jni;
const std = @import("std");
const ndk = @import("ndk.zig");
const Env = [*c]jni.JNIEnv;
var bitmap_class: jni.jclass = null;
var bitmap_field: jni.jfieldID = null;
var frames_field: jni.jfieldID = null;
var x_field: jni.jfieldID = null;
var y_field: jni.jfieldID = null;
var duration_field: jni.jfieldID = null;
var shadow_field: jni.jfieldID = null;
var set_image_filter: *const fn (*anyopaque, c_int) callconv(.c) void = undefined;

fn invalid(env: Env, text: [*:0]const u8) jni.jobjectArray {
    const cls = env.*.*.FindClass.?(env, "java/lang/IllegalArgumentException") orelse return null;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    _ = env.*.*.ThrowNew.?(env, cls, text);
    return null;
}

fn read(env: Env, _: jni.jclass, icon: jni.jobject, metadata: jni.jintArray) callconv(.c) jni.jobjectArray {
    if (icon == null or metadata == null or env.*.*.GetArrayLength.?(env, metadata) != 4)
        return invalid(env, "Invalid cursor metadata target");
    const first = env.*.*.GetObjectField.?(env, icon, bitmap_field) orelse
        return invalid(env, "Pointer icon is not loaded");
    defer env.*.*.DeleteLocalRef.?(env, first);
    const more: jni.jobjectArray = @ptrCast(env.*.*.GetObjectField.?(env, icon, frames_field));
    defer if (more != null) env.*.*.DeleteLocalRef.?(env, more);
    const count = if (more == null) 0 else env.*.*.GetArrayLength.?(env, more);
    if (count < 0 or count >= 128) return invalid(env, "Cursor frame count exceeds host limit");
    const result = env.*.*.NewObjectArray.?(env, count + 1, bitmap_class, first) orelse return null;
    var index: jni.jint = 0;
    while (index < count) : (index += 1) {
        const frame = env.*.*.GetObjectArrayElement.?(env, more, index) orelse
            return invalid(env, "Null cursor frame");
        env.*.*.SetObjectArrayElement.?(env, result, index + 1, frame);
        env.*.*.DeleteLocalRef.?(env, frame);
    }
    const values = [_]jni.jint{
        @bitCast(env.*.*.GetFloatField.?(env, icon, x_field)),
        @bitCast(env.*.*.GetFloatField.?(env, icon, y_field)),
        env.*.*.GetIntField.?(env, icon, duration_field),
        @intCast(env.*.*.GetBooleanField.?(env, icon, shadow_field)),
    };
    env.*.*.SetIntArrayRegion.?(env, metadata, 0, values.len, &values);
    return result;
}

fn shadow(env: Env, _: jni.jclass, paint: jni.jlong) callconv(.c) void {
    if (paint == 0) {
        _ = invalid(env, "Missing cursor paint");
        return;
    }
    // APaint and Java Paint share the pinned HWUI Paint via apex/TypeCast.h.
    // Reuse SpriteIcon::draw's exact two-shadow image filter, not an
    // approximation using Java Paint's different shadow-layer operation.
    set_image_filter(@ptrFromInt(@as(usize, @bitCast(paint))), 0);
}

pub fn register(env: Env, platform: *std.DynLib) !void {
    if (bitmap_class != null) return;
    bitmap_class = ndk.findGlobalClass(env, "android/graphics/Bitmap") orelse return error.NoClass;
    const icon = env.*.*.FindClass.?(env, "android/view/PointerIcon") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, icon);
    bitmap_field = env.*.*.GetFieldID.?(env, icon, "mBitmap", "Landroid/graphics/Bitmap;") orelse return error.NoField;
    frames_field = env.*.*.GetFieldID.?(env, icon, "mBitmapFrames", "[Landroid/graphics/Bitmap;") orelse return error.NoField;
    x_field = env.*.*.GetFieldID.?(env, icon, "mHotSpotX", "F") orelse return error.NoField;
    y_field = env.*.*.GetFieldID.?(env, icon, "mHotSpotY", "F") orelse return error.NoField;
    duration_field = env.*.*.GetFieldID.?(env, icon, "mDurationPerFrame", "I") orelse return error.NoField;
    shadow_field = env.*.*.GetFieldID.?(env, icon, "mDrawNativeDropShadow", "Z") orelse return error.NoField;
    set_image_filter = platform.lookup(@TypeOf(set_image_filter), "APaint_setImageFilter") orelse return error.NoMethod;
    const cls = env.*.*.FindClass.?(env, "dev/artzig/window/WaylandCursor") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, cls);
    const methods = [_]jni.JNINativeMethod{ .{
        .name = @constCast("readFrames"),
        .signature = @constCast("(Landroid/view/PointerIcon;[I)[Landroid/graphics/Bitmap;"),
        .fnPtr = @ptrCast(@constCast(&read)),
    }, .{
        .name = @constCast("setShadow"),
        .signature = @constCast("(J)V"),
        .fnPtr = @ptrCast(@constCast(&shadow)),
    } };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}
