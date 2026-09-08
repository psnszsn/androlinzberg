//! NdkImageReader.cpp / NdkImage.cpp failure boundary for a host without Android
//! BufferQueue image producers. These symbols are also strong imports of WebView
//! even when its SurfaceControl overlay path is disabled. Never create a fake
//! reader, image, native window or hardware buffer. A valid creation request
//! reports the missing producer seam and returns AMEDIA_ERROR_UNSUPPORTED.
const jni = @import("art").jni;
const Scope = @import("media_ndk.zig").Scope;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const invalid_parameter: i32 = -10004;
const invalid_object: i32 = -10003;
const unsupported: i32 = -10002;
var diagnostics: jni.jclass = null;
var report: jni.jmethodID = null;

fn missing(member: [*:0]const u8) void {
    const scope = Scope.enter() orelse return;
    defer scope.leave();
    const env = scope.env;
    if (diagnostics == null or report == null) return;
    const kind = env.*.*.NewStringUTF.?(env, "unimplemented") orelse return;
    const owner = env.*.*.NewStringUTF.?(env, "AImageReader") orelse return;
    const method = env.*.*.NewStringUTF.?(env, member) orelse return;
    const detail = env.*.*.NewStringUTF.?(env, "Android BufferQueue image producers are not connected to this host; no reader or image was created") orelse return;
    const args = [_]jni.jvalue{ .{ .l = kind }, .{ .l = owner }, .{ .l = method }, .{ .l = detail } };
    env.*.*.CallStaticVoidMethodA.?(env, diagnostics, report, &args);
}

// Pinned AImageReader::isSupportedFormatAndUsage, including private depth formats
// from NdkImagePriv.h. Public format PRIVATE forbids CPU read usage.
fn validFormat(format: i32, usage: u64) bool {
    return switch (format) {
        1, 2, 3, 4, 0x16, 0x23, 0x100, 0x20, 0x1002, 0x24, 0x25, 0x26,
        0x44363159, 0x101, 0x20203859, 0x48454946, 0x69656963, 0x1003, 0x36 => true,
        0x22 => usage & 3 == 0,
        else => false,
    };
}
export fn AImageReader_newWithUsage(width: i32, height: i32, format: i32, usage: u64, max_images: i32, output: ?*Handle) callconv(.c) i32 {
    if (output == null or width < 1 or height < 1 or max_images < 1 or max_images > 64 or !validFormat(format, usage)) return invalid_parameter;
    missing("newWithUsage");
    return unsupported;
}
export fn AImageReader_delete(reader: Handle) callconv(.c) void {
    if (reader != null) missing("delete");
}
fn invalid(handle: Handle, output: anytype) i32 {
    return if (handle == null or output == null) invalid_parameter else invalid_object;
}
export fn AImageReader_getWindow(reader: Handle, window: ?*Handle) callconv(.c) i32 {
    return invalid(reader, window);
}
export fn AImageReader_setImageListener(reader: Handle, _: ?*const anyopaque) callconv(.c) i32 {
    return if (reader == null) invalid_parameter else invalid_object;
}
export fn AImageReader_acquireNextImageAsync(reader: Handle, output: ?*Handle, _: ?*i32) callconv(.c) i32 {
    return invalid(reader, output);
}
export fn AImageReader_acquireLatestImageAsync(reader: Handle, output: ?*Handle, _: ?*i32) callconv(.c) i32 {
    return invalid(reader, output);
}
export fn AImage_delete(value: Handle) callconv(.c) void {
    if (value != null) missing("AImage_delete");
}
export fn AImage_deleteAsync(value: Handle, _: i32) callconv(.c) void {
    if (value != null) missing("AImage_deleteAsync");
}
export fn AImage_getWidth(value: Handle, output: ?*i32) callconv(.c) i32 { return invalid(value, output); }
export fn AImage_getHeight(value: Handle, output: ?*i32) callconv(.c) i32 { return invalid(value, output); }
export fn AImage_getCropRect(value: Handle, output: ?*anyopaque) callconv(.c) i32 { return invalid(value, output); }
export fn AImage_getHardwareBuffer(value: Handle, output: ?*Handle) callconv(.c) i32 { return invalid(value, output); }

pub fn register(env: Env) !void {
    const local = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return error.MissingDiagnostics;
    defer env.*.*.DeleteLocalRef.?(env, local);
    diagnostics = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return error.OutOfMemory);
    report = env.*.*.GetStaticMethodID.?(env, diagnostics, "reportIssue", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V") orelse return error.MissingReportMethod;
}
