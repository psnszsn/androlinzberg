//! Desktop controller adapter for the existing Varlink server. Java owns the
//! controller loop; this callback runs synchronously on that same ART thread.
const std = @import("std");
const art = @import("art");
const control = @import("test_control.zig");
const jni = art.jni;
var active: ?*control.Server = null;

pub fn register(env: [*c]jni.JNIEnv, server: *control.Server) !void {
    std.debug.assert(active == null);
    const class = env.*.*.FindClass.?(env, "dev/artzig/testing/SessionTestControl") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, class);
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "poll", .signature = "(JJ)Z", .fnPtr = @ptrCast(@constCast(&poll)) },
    };
    if (env.*.*.RegisterNatives.?(env, class, &methods, methods.len) != 0) return error.RegisterFailed;
    active = server;
}

pub fn deinit() void {
    active = null;
}

fn poll(env: [*c]jni.JNIEnv, class: jni.jclass, frame: jni.jlong, presented: jni.jlong) callconv(.c) jni.jboolean {
    const server = active orelse return 0;
    const android = control.Android.init(env, class) catch return 0; // The Env adapter reports and clears lookup exceptions.
    if (frame < 0 or presented < 0) return 0;
    server.tick(&android, @intCast(frame), @intCast(presented));
    return @intFromBool(server.needsFrame());
}
