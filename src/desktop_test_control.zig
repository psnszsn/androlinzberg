//! Desktop controller adapter for the existing Varlink server. Java owns the
//! controller loop; this callback runs synchronously on that same ART thread.
const std = @import("std");
const art = @import("art");
const control = @import("test_control.zig");
const jni = art.jni;
const State = struct {
    server: *control.Server,
    android: control.Android,
    select_method: jni.jmethodID,
    inject_method: jni.jmethodID,
    fullscreen_method: jni.jmethodID,
};
var active: ?State = null;

pub fn register(env: [*c]jni.JNIEnv, server: *control.Server) !void {
    std.debug.assert(active == null);
    const class = env.*.*.FindClass.?(env, "dev/artzig/testing/SessionTestControl") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, class);
    const global: jni.jclass = @ptrCast(env.*.*.NewGlobalRef.?(env, class) orelse return error.OutOfMemory);
    errdefer env.*.*.DeleteGlobalRef.?(env, global);
    const e = art.Env{ .env = env };
    const state: State = .{
        .server = server,
        .android = try control.Android.init(env, global),
        .select_method = try e.getStaticMethodID(class, "selectInputWindow", "(JJII)Z"),
        .inject_method = try e.getStaticMethodID(class, "injectInput", "(IIIIILjava/lang/String;)Z"),
        .fullscreen_method = try e.getStaticMethodID(class, "inputFullscreen", "()Z"),
    };
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "poll", .signature = "(JJ)Z", .fnPtr = @ptrCast(@constCast(&poll)) },
    };
    if (env.*.*.RegisterNatives.?(env, class, &methods, methods.len) != 0) return error.RegisterFailed;
    active = state;
    server.setInput(.{ .context = &active.?, .select_fn = select, .tap_fn = tap, .drag_fn = drag, .type_fn = typeText, .key_fn = key, .type_at_fn = typeAt, .key_at_fn = keyAt, .fullscreen_fn = fullscreen });
}

pub fn deinit() void {
    if (active) |state| state.android.env.*.*.DeleteGlobalRef.?(state.android.env, state.android.class);
    active = null;
}

fn poll(_: [*c]jni.JNIEnv, _: jni.jclass, frame: jni.jlong, presented: jni.jlong) callconv(.c) jni.jboolean {
    const state = if (active) |*value| value else return 0;
    if (frame < 0 or presented < 0) return 0;
    state.server.tick(&state.android, @intCast(frame), @intCast(presented));
    return @intFromBool(state.server.needsFrame());
}

fn select(context: *anyopaque, revision: u64, window_id: i64, view_id: i32, virtual_id: i32) !void {
    const state: *State = @ptrCast(@alignCast(context));
    const android = &state.android;
    const result = android.env.*.*.CallStaticBooleanMethod.?(android.env, android.class, state.select_method, @as(jni.jlong, @intCast(revision)), @as(jni.jlong, window_id), @as(jni.jint, view_id), @as(jni.jint, virtual_id));
    try (art.Env{ .env = android.env }).check();
    if (result == 0) return error.InputTargetChanged;
}

fn inject(context: *anyopaque, kind: i32, points: [4]i32, text: []const u8) !void {
    const state: *State = @ptrCast(@alignCast(context));
    const android = &state.android;
    const e = art.Env{ .env = android.env };
    const text_z = try state.server.gpa.dupeSentinel(u8, text, 0);
    defer state.server.gpa.free(text_z);
    const jtext = try e.newString(text_z.ptr);
    defer android.env.*.*.DeleteLocalRef.?(android.env, jtext);
    const result = android.env.*.*.CallStaticBooleanMethod.?(android.env, android.class, state.inject_method, @as(jni.jint, kind), @as(jni.jint, points[0]), @as(jni.jint, points[1]), @as(jni.jint, points[2]), @as(jni.jint, points[3]), jtext);
    try e.check();
    if (result == 0) return error.CompositorInputRejected;
}
fn tap(context: *anyopaque, x: i32, y: i32) !void {
    try inject(context, 0, .{ x, y, 0, 0 }, "");
}
fn drag(context: *anyopaque, x1: i32, y1: i32, x2: i32, y2: i32) !void {
    try inject(context, 1, .{ x1, y1, x2, y2 }, "");
}
fn typeText(context: *anyopaque, text: []const u8) !void {
    try inject(context, 2, @splat(0), text);
}
fn key(context: *anyopaque, text: []const u8) !void {
    try inject(context, 3, @splat(0), text);
}
fn typeAt(context: *anyopaque, x: i32, y: i32, text: []const u8) !void {
    try inject(context, 4, .{ x, y, 0, 0 }, text);
}
fn keyAt(context: *anyopaque, x: i32, y: i32, text: []const u8) !void {
    try inject(context, 5, .{ x, y, 0, 0 }, text);
}
fn fullscreen(context: *anyopaque) bool {
    const state: *State = @ptrCast(@alignCast(context));
    const android = &state.android;
    const result = android.env.*.*.CallStaticBooleanMethod.?(android.env, android.class, state.fullscreen_method);
    (art.Env{ .env = android.env }).check() catch return false;
    return result != 0;
}
