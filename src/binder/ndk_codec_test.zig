//! Public stock NDK MediaCodec regression, registered only under dev.artzig.
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const unknown: i32 = -10000;
const invalid_parameter: i32 = -10004;
const BufferInfo = extern struct { offset: i32, size: i32, time_us: i64, flags: u32 };
const Callbacks = extern struct { input: usize, output: usize, format: usize, err: usize };
var AMediaCodec_createCodecByName: *const fn (name: ?[*:0]const u8) callconv(.c) Handle = undefined;
var AMediaCodec_createDecoderByType: *const fn (name: ?[*:0]const u8) callconv(.c) Handle = undefined;
var AMediaCodec_delete: *const fn (handle: Handle) callconv(.c) i32 = undefined;
var AMediaCodec_start: *const fn (handle: Handle) callconv(.c) i32 = undefined;
var AMediaCodec_stop: *const fn (handle: Handle) callconv(.c) i32 = undefined;
var AMediaCodec_flush: *const fn (handle: Handle) callconv(.c) i32 = undefined;
var AMediaCodec_getName: *const fn (handle: Handle, out_name: ?*?[*:0]u8) callconv(.c) i32 = undefined;
var AMediaCodec_releaseName: *const fn (_: Handle, name: ?[*:0]u8) callconv(.c) void = undefined;
var AMediaCodec_configure: *const fn (handle: Handle, format: Handle, window: Handle, crypto: Handle, flags: u32) callconv(.c) i32 = undefined;
var AMediaCodec_dequeueInputBuffer: *const fn (handle: Handle, timeout: i64) callconv(.c) isize = undefined;
var AMediaCodec_dequeueOutputBuffer: *const fn (handle: Handle, output: ?*BufferInfo, timeout: i64) callconv(.c) isize = undefined;
var AMediaCodec_getInputBuffer: *const fn (handle: Handle, index: usize, size: ?*usize) callconv(.c) ?[*]u8 = undefined;
var AMediaCodec_getOutputBuffer: *const fn (handle: Handle, index: usize, size: ?*usize) callconv(.c) ?[*]u8 = undefined;
var AMediaCodec_queueInputBuffer: *const fn (handle: Handle, index: usize, offset: usize, size: usize, time_us: u64, flags: u32) callconv(.c) i32 = undefined;
var AMediaCodec_releaseOutputBuffer: *const fn (handle: Handle, index: usize, render: bool) callconv(.c) i32 = undefined;
var AMediaCodec_getInputFormat: *const fn (handle: Handle) callconv(.c) Handle = undefined;
var AMediaCodec_setAsyncNotifyCallback: *const fn (handle: Handle, callbacks: Callbacks, userdata: Handle) callconv(.c) i32 = undefined;
pub fn register(env: Env, platform: *std.DynLib) !void {
    format_new = platform.lookup(FormatNew, "AMediaFormat_new") orelse return error.NoMethod;
    format_delete = platform.lookup(FormatDelete, "AMediaFormat_delete") orelse return error.NoMethod;
    format_int = platform.lookup(FormatInt, "AMediaFormat_setInt32") orelse return error.NoMethod;
    format_string = platform.lookup(FormatString, "AMediaFormat_setString") orelse return error.NoMethod;
    format_get_int = platform.lookup(FormatGetInt, "AMediaFormat_getInt32") orelse return error.NoMethod;
    const smoke_cls = env.*.*.FindClass.?(env, "dev/artzig/StockNdkMediaCodecSmoke") orelse return error.NoClass;
    defer env.*.*.DeleteLocalRef.?(env, smoke_cls);
    const smoke_natives = [_]jni.JNINativeMethod{.{ .name = "runNative", .signature = "()V", .fnPtr = @ptrCast(@constCast(&runSmoke)) }};
    if (env.*.*.RegisterNatives.?(env, smoke_cls, &smoke_natives, smoke_natives.len) != 0) return error.RegisterFailed;
    AMediaCodec_configure = platform.lookup(@TypeOf(AMediaCodec_configure), "AMediaCodec_configure") orelse return error.NoMethod;
    AMediaCodec_createCodecByName = platform.lookup(@TypeOf(AMediaCodec_createCodecByName), "AMediaCodec_createCodecByName") orelse return error.NoMethod;
    AMediaCodec_createDecoderByType = platform.lookup(@TypeOf(AMediaCodec_createDecoderByType), "AMediaCodec_createDecoderByType") orelse return error.NoMethod;
    AMediaCodec_delete = platform.lookup(@TypeOf(AMediaCodec_delete), "AMediaCodec_delete") orelse return error.NoMethod;
    AMediaCodec_dequeueInputBuffer = platform.lookup(@TypeOf(AMediaCodec_dequeueInputBuffer), "AMediaCodec_dequeueInputBuffer") orelse return error.NoMethod;
    AMediaCodec_dequeueOutputBuffer = platform.lookup(@TypeOf(AMediaCodec_dequeueOutputBuffer), "AMediaCodec_dequeueOutputBuffer") orelse return error.NoMethod;
    AMediaCodec_flush = platform.lookup(@TypeOf(AMediaCodec_flush), "AMediaCodec_flush") orelse return error.NoMethod;
    AMediaCodec_getInputBuffer = platform.lookup(@TypeOf(AMediaCodec_getInputBuffer), "AMediaCodec_getInputBuffer") orelse return error.NoMethod;
    AMediaCodec_getInputFormat = platform.lookup(@TypeOf(AMediaCodec_getInputFormat), "AMediaCodec_getInputFormat") orelse return error.NoMethod;
    AMediaCodec_getName = platform.lookup(@TypeOf(AMediaCodec_getName), "AMediaCodec_getName") orelse return error.NoMethod;
    AMediaCodec_getOutputBuffer = platform.lookup(@TypeOf(AMediaCodec_getOutputBuffer), "AMediaCodec_getOutputBuffer") orelse return error.NoMethod;
    AMediaCodec_queueInputBuffer = platform.lookup(@TypeOf(AMediaCodec_queueInputBuffer), "AMediaCodec_queueInputBuffer") orelse return error.NoMethod;
    AMediaCodec_releaseName = platform.lookup(@TypeOf(AMediaCodec_releaseName), "AMediaCodec_releaseName") orelse return error.NoMethod;
    AMediaCodec_releaseOutputBuffer = platform.lookup(@TypeOf(AMediaCodec_releaseOutputBuffer), "AMediaCodec_releaseOutputBuffer") orelse return error.NoMethod;
    AMediaCodec_setAsyncNotifyCallback = platform.lookup(@TypeOf(AMediaCodec_setAsyncNotifyCallback), "AMediaCodec_setAsyncNotifyCallback") orelse return error.NoMethod;
    AMediaCodec_start = platform.lookup(@TypeOf(AMediaCodec_start), "AMediaCodec_start") orelse return error.NoMethod;
    AMediaCodec_stop = platform.lookup(@TypeOf(AMediaCodec_stop), "AMediaCodec_stop") orelse return error.NoMethod;
}

const FormatNew = *const fn () callconv(.c) Handle;
const FormatDelete = *const fn (Handle) callconv(.c) i32;
const FormatInt = *const fn (Handle, [*:0]const u8, i32) callconv(.c) void;
const FormatString = *const fn (Handle, [*:0]const u8, [*:0]const u8) callconv(.c) void;
const FormatGetInt = *const fn (Handle, [*:0]const u8, *i32) callconv(.c) bool;
var format_new: FormatNew = undefined;
var format_delete: FormatDelete = undefined;
var format_int: FormatInt = undefined;
var format_string: FormatString = undefined;
var format_get_int: FormatGetInt = undefined;
fn smokeCheck(condition: bool) !void {
    if (!condition) return error.NdkCodecContract;
}
fn smokeFormat() !Handle {
    const format = format_new() orelse return error.NoFormat;
    format_string(format, "mime", "audio/mpeg");
    format_int(format, "sample-rate", 44100);
    format_int(format, "channel-count", 2);
    return format;
}
fn smokeQueue(codec: Handle, index: i32, eos: bool) !void {
    var capacity: usize = 0;
    const bytes = AMediaCodec_getInputBuffer(codec, @intCast(index), &capacity) orelse return error.NoInput;
    try smokeCheck(capacity >= 417);
    if (!eos) {
        @memset(bytes[0..417], 0);
        @memcpy(bytes[0..4], &[_]u8{ 0xff, 0xfb, 0x90, 0 });
    }
    try smokeCheck(AMediaCodec_queueInputBuffer(codec, @intCast(index), 0, if (eos) 0 else 417, 12345, if (eos) 4 else 0) == 0);
}
fn smokePcm(codec: Handle, index: i32, info: *BufferInfo) !bool {
    defer _ = AMediaCodec_releaseOutputBuffer(codec, @intCast(index), false);
    if (info.size == 0) return false;
    var capacity: usize = 0;
    const bytes = AMediaCodec_getOutputBuffer(codec, @intCast(index), &capacity) orelse return error.NoOutput;
    try smokeCheck(info.time_us == 12345 and info.offset >= 0 and info.size > 0);
    const offset: usize = @intCast(info.offset);
    const size: usize = @intCast(info.size);
    try smokeCheck(offset + size <= capacity);
    for (bytes[offset..][0..size]) |byte| try smokeCheck(byte == 0);
    return true;
}
const AsyncSmoke = struct {
    // NdkMediaCodec delivers callbacks on its codec looper. Match the pinned
    // asynchronous fuzzer: callbacks hand off events; codec calls run elsewhere.
    inputs: std.atomic.Value(u32) = .init(0),
    input_indices: [2]i32 = undefined,
    outputs: std.atomic.Value(u32) = .init(0),
    output_events: [8]struct { index: i32, info: BufferInfo } = undefined,
    pcm: std.atomic.Value(bool) = .init(false),
    eos: std.atomic.Value(bool) = .init(false),
    bad: std.atomic.Value(bool) = .init(false),
    formats: std.atomic.Value(u32) = .init(0),
};
fn smokeInput(_: Handle, userdata: Handle, index: i32) callconv(.c) void {
    const state: *AsyncSmoke = @ptrCast(@alignCast(userdata));
    const count = state.inputs.load(.monotonic);
    if (count >= state.input_indices.len) return;
    state.input_indices[count] = index;
    state.inputs.store(count + 1, .release);
}
fn smokeOutput(_: Handle, userdata: Handle, index: i32, info: *BufferInfo) callconv(.c) void {
    const state: *AsyncSmoke = @ptrCast(@alignCast(userdata));
    const count = state.outputs.load(.monotonic);
    if (count >= state.output_events.len) {
        state.bad.store(true, .release);
        return;
    }
    state.output_events[count] = .{ .index = index, .info = info.* };
    state.outputs.store(count + 1, .release);
}
fn smokeChanged(_: Handle, userdata: Handle, format: Handle) callconv(.c) void {
    const state: *AsyncSmoke = @ptrCast(@alignCast(userdata));
    defer _ = format_delete(format);
    var rate: i32 = 0;
    if (!format_get_int(format, "sample-rate", &rate) or rate != 44100) state.bad.store(true, .release);
    _ = state.formats.fetchAdd(1, .monotonic);
}
fn smokeError(_: Handle, userdata: Handle, _: i32, _: i32, _: [*:0]const u8) callconv(.c) void {
    const state: *AsyncSmoke = @ptrCast(@alignCast(userdata));
    state.bad.store(true, .release);
}
fn smoke() !void {
    // The soname mapping must expose formats and codec calls through one handle.
    // The original native codec and format share the platform DSO.
    var library = try std.DynLib.open("libandroid_platform.so");
    defer library.close();
    const Create = *const fn (?[*:0]const u8) callconv(.c) Handle;
    try smokeCheck(library.lookup(Create, "AMediaCodec_createDecoderByType") == AMediaCodec_createDecoderByType);
    try smokeCheck(library.lookup(FormatNew, "AMediaFormat_new") == format_new);
    try smokeCheck(AMediaCodec_createCodecByName("dev.artzig.missing.codec") == null);
    try smokeCheck(AMediaCodec_delete(null) == 0);
    const codec = AMediaCodec_createDecoderByType("audio/mpeg") orelse return error.NoCodec;
    defer _ = AMediaCodec_delete(codec);
    var name: ?[*:0]u8 = null;
    try smokeCheck(AMediaCodec_getName(codec, null) == invalid_parameter);
    try smokeCheck(AMediaCodec_getName(codec, &name) == 0 and name != null);
    AMediaCodec_releaseName(codec, name);
    const format = try smokeFormat();
    defer _ = format_delete(format);
    try smokeCheck(AMediaCodec_configure(codec, format, null, null, 0) == 0);
    format_int(format, "sample-rate", 8000);
    const snapshot = AMediaCodec_getInputFormat(codec) orelse return error.NoFormat;
    defer _ = format_delete(snapshot);
    var rate: i32 = 0;
    try smokeCheck(format_get_int(snapshot, "sample-rate", &rate) and rate == 44100);
    try smokeCheck(AMediaCodec_dequeueInputBuffer(codec, 0) == unknown);
    try smokeCheck(AMediaCodec_start(codec) == 0);
    const input = AMediaCodec_dequeueInputBuffer(codec, 1000000);
    try smokeCheck(input >= 0);
    try smokeQueue(codec, @intCast(input), false);
    var info: BufferInfo = undefined;
    var received = false;
    for (0..2000) |_| {
        const output = AMediaCodec_dequeueOutputBuffer(codec, &info, 1000);
        if (output >= 0) {
            received = try smokePcm(codec, @intCast(output), &info);
            if (received) break;
        } else try smokeCheck(output == -1 or output == -2);
    }
    try smokeCheck(received);
    try smokeCheck(AMediaCodec_flush(codec) == 0);
    try smokeCheck(AMediaCodec_dequeueInputBuffer(codec, 1000000) >= 0);
    try smokeCheck(AMediaCodec_stop(codec) == 0);

    const async_codec = AMediaCodec_createDecoderByType("audio/mpeg") orelse return error.NoCodec;
    var state: AsyncSmoke = .{};
    // Delete and drain before this stack's
    // callback userdata can go out of scope, including assertion failures.
    defer _ = AMediaCodec_delete(async_codec);
    const callbacks = Callbacks{ .input = @intFromPtr(&smokeInput), .output = @intFromPtr(&smokeOutput), .format = @intFromPtr(&smokeChanged), .err = @intFromPtr(&smokeError) };
    try smokeCheck(AMediaCodec_setAsyncNotifyCallback(async_codec, callbacks, &state) == 0);
    format_int(format, "sample-rate", 44100);
    try smokeCheck(AMediaCodec_configure(async_codec, format, null, null, 0) == 0);
    try smokeCheck(AMediaCodec_start(async_codec) == 0);
    const io = @import("ndk.zig").frameworkIo();
    var queued: u32 = 0;
    var received_outputs: u32 = 0;
    for (0..1500) |_| {
        while (queued < state.inputs.load(.acquire)) : (queued += 1)
            try smokeQueue(async_codec, state.input_indices[queued], queued == 1);
        while (received_outputs < state.outputs.load(.acquire)) : (received_outputs += 1) {
            var event = state.output_events[received_outputs];
            if (try smokePcm(async_codec, event.index, &event.info)) state.pcm.store(true, .release);
            if (event.info.flags & 4 != 0) state.eos.store(true, .release);
        }
        if (state.eos.load(.acquire) or state.bad.load(.acquire)) break;
        std.Io.sleep(io, .fromMilliseconds(2), .awake) catch {};
    }
    try smokeCheck(!state.bad.load(.acquire) and state.eos.load(.acquire) and state.pcm.load(.acquire) and state.formats.load(.acquire) > 0);
}
fn runSmoke(env: Env, _: jni.jclass) callconv(.c) void {
    smoke() catch |err| {
        const cls = env.*.*.FindClass.?(env, "java/lang/AssertionError") orelse return;
        _ = env.*.*.ThrowNew.?(env, cls, @errorName(err));
    };
}
