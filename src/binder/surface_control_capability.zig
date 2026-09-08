//! Absent-compositor boundary for the pinned NDK surface_control.cpp API.
//! SurfaceComposerClient and SurfaceFlinger transactions cannot run through the
//! existing hosted window backend. Do not manufacture controls, transactions,
//! completion callbacks or presentation statistics. Every attempted operation
//! reports a structured unsupported issue; factories fail without a handle.
//! Chromium loads these ABI names even when HWUI disables SurfaceControl overlays.
const std = @import("std");
const jni = @import("art").jni;
const ndk = @import("ndk.zig");
const Scope = @import("media_ndk.zig").Scope;
const Handle = ?*anyopaque;
const Rect = extern struct { left: i32, top: i32, right: i32, bottom: i32 };
const Callback = ?*const fn (Handle, Handle) callconv(.c) void;
var diagnostics: jni.jclass = null;
var report: jni.jmethodID = null;

fn missing(comptime owner: [:0]const u8, comptime member: [:0]const u8) void {
    const scope = Scope.enter() orelse return;
    defer scope.leave();
    const env = scope.env;
    const kind = env.*.*.NewStringUTF.?(env, "unimplemented") orelse return;
    const cls = env.*.*.NewStringUTF.?(env, owner) orelse return;
    const method = env.*.*.NewStringUTF.?(env, member) orelse return;
    const detail = env.*.*.NewStringUTF.?(env, "SurfaceFlinger layer transactions are unavailable on this host; no surface, transaction, completion or presentation result was produced") orelse return;
    const args = [_]jni.jvalue{ .{ .l = kind }, .{ .l = cls }, .{ .l = method }, .{ .l = detail } };
    env.*.*.CallStaticVoidMethodA.?(env, diagnostics, report, &args);
}
export fn ASurfaceControl_createFromWindow(_: Handle, _: ?[*:0]const u8) callconv(.c) Handle {
    missing("ASurfaceControl", "createFromWindow"); return null;
}
export fn ASurfaceControl_create(_: Handle, _: ?[*:0]const u8) callconv(.c) Handle {
    missing("ASurfaceControl", "create"); return null;
}
export fn ASurfaceControl_release(_: Handle) callconv(.c) void { missing("ASurfaceControl", "release"); }
export fn ASurfaceTransaction_create() callconv(.c) Handle { missing("ASurfaceTransaction", "create"); return null; }
export fn ASurfaceTransaction_delete(value: Handle) callconv(.c) void {
    // Pinned delete accepts nullptr. No non-null transaction can originate here.
    if (value != null) missing("ASurfaceTransaction", "delete");
}
export fn ASurfaceTransaction_apply(_: Handle) callconv(.c) void { missing("ASurfaceTransaction", "apply"); }
export fn ASurfaceTransaction_setOnComplete(_: Handle, _: Handle, _: Callback) callconv(.c) void {
    missing("ASurfaceTransaction", "setOnComplete");
}
export fn ASurfaceTransaction_reparent(_: Handle, _: Handle, _: Handle) callconv(.c) void { missing("ASurfaceTransaction", "reparent"); }
export fn ASurfaceTransaction_setVisibility(_: Handle, _: Handle, _: i32) callconv(.c) void { missing("ASurfaceTransaction", "setVisibility"); }
export fn ASurfaceTransaction_setZOrder(_: Handle, _: Handle, _: i32) callconv(.c) void { missing("ASurfaceTransaction", "setZOrder"); }
export fn ASurfaceTransaction_setBuffer(_: Handle, _: Handle, _: Handle, acquire_fence_fd: i32) callconv(.c) void {
    // The NDK transfers the acquire fence to the transaction. Rejecting the
    // unsupported operation must still release that transferred descriptor.
    if (acquire_fence_fd >= 0) (std.Io.File{ .handle = acquire_fence_fd, .flags = .{ .nonblocking = false } }).close(ndk.frameworkIo());
    missing("ASurfaceTransaction", "setBuffer");
}
export fn ASurfaceTransaction_setGeometry(_: Handle, _: Handle, _: ?*const Rect, _: ?*const Rect, _: i32) callconv(.c) void {
    missing("ASurfaceTransaction", "setGeometry");
}
export fn ASurfaceTransaction_setBufferTransparency(_: Handle, _: Handle, _: i32) callconv(.c) void { missing("ASurfaceTransaction", "setBufferTransparency"); }
export fn ASurfaceTransaction_setDamageRegion(_: Handle, _: Handle, _: ?[*]const Rect, _: u32) callconv(.c) void { missing("ASurfaceTransaction", "setDamageRegion"); }
export fn ASurfaceTransaction_setBufferDataSpace(_: Handle, _: Handle, _: i32) callconv(.c) void { missing("ASurfaceTransaction", "setBufferDataSpace"); }
export fn ASurfaceTransaction_setHdrMetadata_cta861_3(_: Handle, _: Handle, _: Handle) callconv(.c) void { missing("ASurfaceTransaction", "setHdrMetadata_cta861_3"); }
export fn ASurfaceTransaction_setHdrMetadata_smpte2086(_: Handle, _: Handle, _: Handle) callconv(.c) void { missing("ASurfaceTransaction", "setHdrMetadata_smpte2086"); }
export fn ASurfaceTransactionStats_getPresentFenceFd(_: Handle) callconv(.c) i32 { missing("ASurfaceTransactionStats", "getPresentFenceFd"); return -1; }
export fn ASurfaceTransactionStats_getLatchTime(_: Handle) callconv(.c) i64 { missing("ASurfaceTransactionStats", "getLatchTime"); return -1; }
export fn ASurfaceTransactionStats_getASurfaceControls(_: Handle, _: ?*?[*]Handle, _: ?*usize) callconv(.c) void {
    missing("ASurfaceTransactionStats", "getASurfaceControls");
}
export fn ASurfaceTransactionStats_releaseASurfaceControls(_: ?[*]Handle) callconv(.c) void { missing("ASurfaceTransactionStats", "releaseASurfaceControls"); }
export fn ASurfaceTransactionStats_getPreviousReleaseFenceFd(_: Handle, _: Handle) callconv(.c) i32 { missing("ASurfaceTransactionStats", "getPreviousReleaseFenceFd"); return -1; }

pub fn register(env: [*c]jni.JNIEnv) !void {
    const local = env.*.*.FindClass.?(env, "dev/artzig/ArtzigTestDiagnostics") orelse return error.MissingDiagnostics;
    defer env.*.*.DeleteLocalRef.?(env, local);
    diagnostics = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return error.OutOfMemory);
    report = env.*.*.GetStaticMethodID.?(env, diagnostics, "reportIssue", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V") orelse return error.MissingReportMethod;
}
