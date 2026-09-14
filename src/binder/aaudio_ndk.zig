//! AAudio's C ABI over stock Java AudioTrack and the unchanged hosted PCM stream.
//! Pinned AAudioStreamParameters/AAudioUtilities retain native validation.
const std = @import("std");
const jni = @import("art").jni;
const Scope = @import("media_ndk.zig").Scope;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
var backend: jni.jclass = null;
const Validate = *const fn ([*]const i32) callconv(.c) i32;
const ChannelMask = *const fn (i32) callconv(.c) i32;
var validate_config: Validate = undefined;
var channel_mask: ChannelMask = undefined;
const internal_error: i32 = -896;
const null_error: i32 = -886;
fn method(env: Env, name: [*:0]const u8, sig: [*:0]const u8) jni.jmethodID {
    return env.*.*.GetStaticMethodID.?(env, backend, name, sig);
}
fn id(handle: Handle) jni.jvalue {
    return .{ .j = @intCast(@intFromPtr(handle)) };
}
fn result(env: Env, value: i32) i32 {
    return if (env.*.*.ExceptionCheck.?(env) == 0) value else internal_error;
}
export fn AAudio_createStreamBuilder(output: ?*Handle) callconv(.c) i32 {
    const out = output orelse return null_error;
    out.* = null;
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "createBuilder", "()J") orelse return internal_error;
    const handle = env.*.*.CallStaticLongMethodA.?(env, backend, mid, null);
    if (result(env, 0) != 0 or handle <= 0) return internal_error;
    out.* = @ptrFromInt(@as(usize, @intCast(handle)));
    return 0;
}
fn simple(handle: Handle, name: [*:0]const u8) i32 {
    if (handle == null) return null_error;
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, name, "(J)I") orelse return internal_error;
    const args = [_]jni.jvalue{id(handle)};
    return result(env, env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args));
}
export fn AAudioStreamBuilder_delete(handle: Handle) callconv(.c) i32 {
    return simple(handle, "deleteBuilder");
}
export fn AAudioStream_close(handle: Handle) callconv(.c) i32 {
    return simple(handle, "close");
}
fn set(handle: Handle, key: i32, value: i32) void {
    const scope = Scope.enter() orelse return;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "set", "(JII)V") orelse return;
    const args = [_]jni.jvalue{ id(handle), .{ .i = key }, .{ .i = value } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
}
export fn AAudioStreamBuilder_setChannelMask(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 0, value);
}
export fn AAudioStreamBuilder_setSampleRate(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 1, value);
}
export fn AAudioStreamBuilder_setFormat(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 2, value);
}
export fn AAudioStreamBuilder_setDirection(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 3, value);
}
export fn AAudioStreamBuilder_setBufferCapacityInFrames(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 4, value);
}
export fn AAudioStreamBuilder_setUsage(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 5, value);
}
export fn AAudioStreamBuilder_setSharingMode(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 6, value);
}
export fn AAudioStreamBuilder_setInputPreset(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 7, value);
}
export fn AAudioStreamBuilder_setContentType(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 8, value);
}
export fn AAudioStreamBuilder_setDeviceId(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 9, value);
}
export fn AAudioStreamBuilder_setPerformanceMode(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 10, value);
}
export fn AAudioStreamBuilder_setFramesPerDataCallback(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 11, value);
}
export fn AAudioStreamBuilder_setChannelCount(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 12, value);
}
export fn AAudioStreamBuilder_setSamplesPerFrame(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 12, value);
}
export fn AAudioStreamBuilder_setSessionId(handle: Handle, value: i32) callconv(.c) void {
    set(handle, 13, value);
}
fn callback(handle: Handle, is_error: bool, function: Handle, userdata: Handle) void {
    const scope = Scope.enter() orelse return;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "callback", "(JZJJ)V") orelse return;
    const args = [_]jni.jvalue{ id(handle), .{ .z = @intFromBool(is_error) }, .{ .j = @bitCast(@intFromPtr(function)) }, .{ .j = @bitCast(@intFromPtr(userdata)) } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
}
export fn AAudioStreamBuilder_setDataCallback(handle: Handle, function: Handle, userdata: Handle) callconv(.c) void {
    callback(handle, false, function, userdata);
}
export fn AAudioStreamBuilder_setErrorCallback(handle: Handle, function: Handle, userdata: Handle) callconv(.c) void {
    callback(handle, true, function, userdata);
}
export fn AAudioStreamBuilder_openStream(handle: Handle, output: ?*Handle) callconv(.c) i32 {
    const out = output orelse return null_error;
    out.* = null;
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "open", "(J)J") orelse return internal_error;
    const args = [_]jni.jvalue{id(handle)};
    const stream = env.*.*.CallStaticLongMethodA.?(env, backend, mid, &args);
    if (result(env, 0) != 0) return internal_error;
    if (stream < 0) return @intCast(stream);
    if (stream == 0) return internal_error;
    out.* = @ptrFromInt(@as(usize, @intCast(stream)));
    return 0;
}
fn command(handle: Handle, operation: i32) i32 {
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "command", "(JI)I") orelse return internal_error;
    const args = [_]jni.jvalue{ id(handle), .{ .i = operation } };
    return result(env, env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args));
}
export fn AAudioStream_requestStart(handle: Handle) callconv(.c) i32 {
    return command(handle, 0);
}
export fn AAudioStream_requestPause(handle: Handle) callconv(.c) i32 {
    return command(handle, 1);
}
export fn AAudioStream_requestFlush(handle: Handle) callconv(.c) i32 {
    return command(handle, 2);
}
export fn AAudioStream_requestStop(handle: Handle) callconv(.c) i32 {
    return command(handle, 3);
}
fn get(handle: Handle, key: i32) i64 {
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "get", "(JI)J") orelse return internal_error;
    const args = [_]jni.jvalue{ id(handle), .{ .i = key } };
    const value = env.*.*.CallStaticLongMethodA.?(env, backend, mid, &args);
    return if (result(env, 0) == 0) value else internal_error;
}
export fn AAudioStream_getFormat(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 0));
}
export fn AAudioStream_getSampleRate(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 1));
}
export fn AAudioStream_getChannelCount(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 2));
}
export fn AAudioStream_getSamplesPerFrame(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 2));
}
export fn AAudioStream_getFramesPerBurst(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 3));
}
export fn AAudioStream_getBufferSizeInFrames(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 4));
}
export fn AAudioStream_getBufferCapacityInFrames(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 5));
}
export fn AAudioStream_getState(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 6));
}
export fn AAudioStream_getFramesWritten(handle: Handle) callconv(.c) i64 {
    return @intCast(get(handle, 7));
}
export fn AAudioStream_getFramesRead(handle: Handle) callconv(.c) i64 {
    return @intCast(get(handle, 8));
}
export fn AAudioStream_getXRunCount(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 9));
}
export fn AAudioStream_getPerformanceMode(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 10));
}
export fn AAudioStream_getSharingMode(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 11));
}
export fn AAudioStream_getDirection(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 12));
}
export fn AAudioStream_getSessionId(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 14));
}
// Pinned AAudioAudio.cpp returns the first routed device, or AAUDIO_UNSPECIFIED
// when none is known. HostedAAudio retains the actual AudioTrack route.
export fn AAudioStream_getDeviceId(handle: Handle) callconv(.c) i32 {
    return @intCast(get(handle, 15));
}
export fn AAudioStream_setBufferSizeInFrames(handle: Handle, frames: i32) callconv(.c) i32 {
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "bufferSize", "(JI)I") orelse return internal_error;
    const args = [_]jni.jvalue{ id(handle), .{ .i = frames } };
    return result(env, env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args));
}
export fn AAudioStream_waitForStateChange(handle: Handle, current: i32, output: ?*i32, timeout: i64) callconv(.c) i32 {
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const array = env.*.*.NewIntArray.?(env, 1) orelse return internal_error;
    const mid = method(env, "waitState", "(JIJ[I)I") orelse return internal_error;
    const args = [_]jni.jvalue{ id(handle), .{ .i = current }, .{ .j = timeout }, .{ .l = array } };
    const status = result(env, env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args));
    if (env.*.*.ExceptionCheck.?(env) == 0) {
        if (output) |out| env.*.*.GetIntArrayRegion.?(env, array, 0, 1, out);
    }
    return status;
}
export fn AAudioStream_getTimestamp(handle: Handle, clock: i32, position: ?*i64, time: ?*i64) callconv(.c) i32 {
    const frame_position = position orelse return null_error;
    const timestamp = time orelse return null_error;
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const array = env.*.*.NewLongArray.?(env, 2) orelse return internal_error;
    const mid = method(env, "timestamp", "(JI[J)I") orelse return internal_error;
    const args = [_]jni.jvalue{ id(handle), .{ .i = clock }, .{ .l = array } };
    const status = result(env, env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args));
    if (status == 0) {
        var values: [2]jni.jlong = undefined;
        env.*.*.GetLongArrayRegion.?(env, array, 0, 2, &values);
        frame_position.* = values[0];
        timestamp.* = values[1];
    }
    return status;
}
export fn AAudioStream_write(handle: Handle, data: ?[*]const u8, frames: i32, timeout: i64) callconv(.c) i32 {
    const raw = data orelse return null_error;
    if (get(handle, 13) == 1) return -895;
    if (frames < 0) return -898;
    const channels = get(handle, 2);
    const format = get(handle, 0);
    if (channels < 0 or format < 0) return -895;
    const size = @as(i64, frames) * channels * @as(i64, if (format == 1) 2 else 4);
    if (size > std.math.maxInt(i32)) return -882;
    const scope = Scope.enter() orelse return internal_error;
    defer scope.leave();
    const env = scope.env;
    const buffer = env.*.*.NewDirectByteBuffer.?(env, @constCast(raw), size) orelse return internal_error;
    const mid = method(env, "write", "(JLjava/nio/ByteBuffer;IJ)I") orelse return internal_error;
    const args = [_]jni.jvalue{ id(handle), .{ .l = buffer }, .{ .i = frames }, .{ .j = timeout } };
    return result(env, env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args));
}
fn validate(env: Env, _: jni.jclass, input: jni.jintArray) callconv(.c) jni.jint {
    if (env.*.*.GetArrayLength.?(env, input) != 12) return -898;
    var config: [12]i32 = undefined;
    env.*.*.GetIntArrayRegion.?(env, input, 0, 12, &config);
    return validate_config(&config);
}
fn channelMask(_: Env, _: jni.jclass, count: jni.jint) callconv(.c) jni.jint {
    return channel_mask(count);
}
fn dataCallback(env: Env, _: jni.jclass, function: jni.jlong, userdata: jni.jlong, stream: jni.jlong, buffer: jni.jobject, frames: jni.jint) callconv(.c) jni.jint {
    const call: *const fn (Handle, Handle, ?*anyopaque, i32) callconv(.c) i32 = @ptrFromInt(@as(usize, @bitCast(function)));
    return call(@ptrFromInt(@as(usize, @intCast(stream))), @ptrFromInt(@as(usize, @bitCast(userdata))), env.*.*.GetDirectBufferAddress.?(env, buffer), frames);
}
fn errorCallback(_: Env, _: jni.jclass, function: jni.jlong, userdata: jni.jlong, stream: jni.jlong, err: jni.jint) callconv(.c) void {
    const call: *const fn (Handle, Handle, i32) callconv(.c) void = @ptrFromInt(@as(usize, @bitCast(function)));
    call(@ptrFromInt(@as(usize, @intCast(stream))), @ptrFromInt(@as(usize, @bitCast(userdata))), err);
}
pub fn register(env: Env, platform: *std.DynLib) !void {
    const smoke_cls = env.*.*.FindClass.?(env, "dev/artzig/StockAAudioSmoke") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, smoke_cls);
    const smoke_natives = [_]jni.JNINativeMethod{.{ .name = "runNative", .signature = "()V", .fnPtr = @ptrCast(@constCast(&runSmoke)) }};
    if (env.*.*.RegisterNatives.?(env, smoke_cls, &smoke_natives, smoke_natives.len) != 0) return error.RegisterFailed;
    validate_config = platform.lookup(Validate, "azk_aaudio_validate") orelse return error.NoMethod;
    channel_mask = platform.lookup(ChannelMask, "azk_aaudio_channel_mask") orelse return error.NoMethod;
    const local = env.*.*.FindClass.?(env, "dev/artzig/HostedAAudio") orelse return error.NoClass;
    backend = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return error.NoClass);
    env.*.*.DeleteLocalRef.?(env, local);
    const natives = [_]jni.JNINativeMethod{
        .{ .name = "validate", .signature = "([I)I", .fnPtr = @ptrCast(@constCast(&validate)) },
        .{ .name = "channelMask", .signature = "(I)I", .fnPtr = @ptrCast(@constCast(&channelMask)) },
        .{ .name = "dataCallback", .signature = "(JJJLjava/nio/ByteBuffer;I)I", .fnPtr = @ptrCast(@constCast(&dataCallback)) },
        .{ .name = "errorCallback", .signature = "(JJJI)V", .fnPtr = @ptrCast(@constCast(&errorCallback)) },
    };
    if (env.*.*.RegisterNatives.?(env, backend, &natives, natives.len) != 0) return error.RegisterFailed;
}

fn check(condition: bool, src: std.builtin.SourceLocation) !void {
    if (!condition) {
        std.debug.print("AAudio contract failed at {s}:{d}\n", .{ src.file, src.line });
        return error.AAudioContract;
    }
}
const SmokeState = struct {
    count: std.atomic.Value(u32) = .init(0),
    bad: std.atomic.Value(bool) = .init(false),
};
fn fill(stream: Handle, userdata: Handle, data: ?*anyopaque, frames: i32) callconv(.c) i32 {
    const state: *SmokeState = @ptrCast(@alignCast(userdata));
    if (frames != 64 or AAudioStream_close(stream) != -895 or AAudioStream_requestStop(stream) != -895)
        state.bad.store(true, .release);
    const bytes: [*]u8 = @ptrCast(data orelse {
        state.bad.store(true, .release);
        return 1;
    });
    @memset(bytes[0 .. @as(usize, @intCast(frames)) * 2], 0);
    _ = state.count.fetchAdd(1, .release);
    return 0;
}
fn failed(_: Handle, userdata: Handle, _: i32) callconv(.c) void {
    const state: *SmokeState = @ptrCast(@alignCast(userdata));
    state.bad.store(true, .release);
}
fn smoke() !void {
    try check(AAudio_createStreamBuilder(null) == null_error, @src());
    try check(AAudioStreamBuilder_delete(null) == null_error, @src());
    var builder: Handle = null;
    try check(AAudio_createStreamBuilder(&builder) == 0 and builder != null, @src());
    defer _ = AAudioStreamBuilder_delete(builder);
    var stream: Handle = @ptrFromInt(1);
    AAudioStreamBuilder_setSampleRate(builder, 1);
    try check(AAudioStreamBuilder_openStream(builder, &stream) == -880 and stream == null, @src());
    AAudioStreamBuilder_setSampleRate(builder, 8000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, 1);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, 64);
    AAudioStreamBuilder_setDirection(builder, 1);
    try check(AAudioStreamBuilder_openStream(builder, &stream) == -889 and stream == null, @src());
    AAudioStreamBuilder_setDirection(builder, 0);
    try check(AAudioStreamBuilder_openStream(builder, &stream) == 0 and stream != null, @src());
    defer _ = AAudioStream_close(stream);
    try check(AAudioStream_getSessionId(stream) == -1, @src());
    try check(AAudioStream_getDeviceId(stream) > 0, @src());
    AAudioStreamBuilder_setSessionId(builder, -2);
    var session_stream: Handle = null;
    try check(AAudioStreamBuilder_openStream(builder, &session_stream) == -898 and session_stream == null, @src());
    AAudioStreamBuilder_setSessionId(builder, 0);
    try check(AAudioStreamBuilder_openStream(builder, &session_stream) == 0, @src());
    const session = AAudioStream_getSessionId(session_stream);
    try check(session > 0 and AAudioStream_close(session_stream) == 0, @src());
    AAudioStreamBuilder_setSessionId(builder, session);
    try check(AAudioStreamBuilder_openStream(builder, &session_stream) == 0, @src());
    try check(AAudioStream_getSessionId(session_stream) == session and AAudioStream_close(session_stream) == 0, @src());
    AAudioStreamBuilder_setSessionId(builder, -1);
    try check(AAudioStream_getState(stream) == 2 and AAudioStream_getFormat(stream) == 1, @src());
    try check(AAudioStream_getSampleRate(stream) == 8000 and AAudioStream_getChannelCount(stream) == 1, @src());
    const burst = AAudioStream_getFramesPerBurst(stream);
    try check(burst == 80 and AAudioStream_setBufferSizeInFrames(stream, 1) == burst, @src());
    var next: i32 = 0;
    try check(AAudioStream_waitForStateChange(stream, 2, &next, 0) == -885 and next == 2, @src());
    try check(AAudioStream_waitForStateChange(stream, 0, null, 0) == 0, @src());
    var position: i64 = -1;
    var timestamp: i64 = -1;
    try check(AAudioStream_getTimestamp(stream, 1, &position, &timestamp) == -895, @src());
    try check(position == -1 and timestamp == -1, @src());
    var pcm: [320]u8 = undefined;
    @memset(&pcm, 0);
    try check(AAudioStream_write(stream, &pcm, 160, 0) == burst, @src());
    try check(AAudioStream_write(stream, &pcm, 160, 0) == 0, @src());
    try check(AAudioStream_getFramesWritten(stream) == burst and AAudioStream_getFramesRead(stream) == 0, @src());
    try check(AAudioStream_requestStart(stream) == 0, @src());
    try check(AAudioStream_getDeviceId(stream) > 0, @src());
    try check(AAudioStream_requestStart(stream) == -895, @src());
    const io = @import("ndk.zig").frameworkIo();
    for (0..1000) |_| {
        if (AAudioStream_getFramesRead(stream) >= burst) break;
        std.Io.sleep(io, .fromMilliseconds(2), .awake) catch {};
    }
    try check(AAudioStream_getFramesRead(stream) >= burst and AAudioStream_getState(stream) == 4, @src());
    try check(AAudioStream_requestFlush(stream) == -895, @src());
    try check(AAudioStream_requestPause(stream) == 0 and AAudioStream_getState(stream) == 6, @src());
    try check(AAudioStream_requestFlush(stream) == 0 and AAudioStream_getState(stream) == 8, @src());
    try check(AAudioStream_getFramesRead(stream) == AAudioStream_getFramesWritten(stream), @src());
    try check(AAudioStream_requestStop(stream) == 0 and AAudioStream_getState(stream) == 10, @src());
    try check(AAudioStream_getDeviceId(stream) > 0, @src());

    var callback_state: SmokeState = .{};
    var callback_stream: Handle = null;
    AAudioStreamBuilder_setDataCallback(builder, @ptrCast(@constCast(&fill)), &callback_state);
    AAudioStreamBuilder_setErrorCallback(builder, @ptrCast(@constCast(&failed)), &callback_state);
    try check(AAudioStreamBuilder_openStream(builder, &callback_stream) == 0, @src());
    defer _ = AAudioStream_close(callback_stream);
    try check(AAudioStream_setBufferSizeInFrames(callback_stream, 160) == 160, @src());
    try check(AAudioStream_requestStart(callback_stream) == 0, @src());
    try check(AAudioStream_write(callback_stream, &pcm, 0, 0) == -895, @src());
    var timed = false;
    for (0..1000) |_| {
        timed = AAudioStream_getTimestamp(callback_stream, 1, &position, &timestamp) == 0;
        if (timed or callback_state.bad.load(.acquire)) break;
        std.Io.sleep(io, .fromMilliseconds(2), .awake) catch {};
    }
    try check(timed and position > 0 and timestamp > 0, @src());
    try check(callback_state.count.load(.acquire) > 0 and !callback_state.bad.load(.acquire), @src());
    try check(AAudioStream_requestStop(callback_stream) == 0, @src());
    try check(AAudioStream_getFramesRead(callback_stream) == AAudioStream_getFramesWritten(callback_stream), @src());
}
fn runSmoke(env: Env, _: jni.jclass) callconv(.c) void {
    smoke() catch |err| {
        const cls = env.*.*.FindClass.?(env, "java/lang/AssertionError") orelse return;
        _ = env.*.*.ThrowNew.?(env, cls, @errorName(err));
    };
}
