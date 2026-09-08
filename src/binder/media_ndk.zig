//! NdkMediaCodec.cpp's C ABI over stock Java MediaCodec and the existing decoder.
//! The original NDK implementation needs native Codec2/OMX and BufferQueue.
//! Native format ownership remains in original AMessage/AMediaFormat code.
const std = @import("std");
const jni = @import("art").jni;
const Env = [*c]jni.JNIEnv;
const Handle = ?*anyopaque;
const alloc = std.heap.c_allocator;
var vm: [*c]jni.JavaVM = null;
var backend: jni.jclass = null;
const ToMap = *const fn (Env, Handle) callconv(.c) jni.jobject;
const FromMap = *const fn (Env, jni.jobject) callconv(.c) Handle;
var to_map: ToMap = undefined;
var from_map: FromMap = undefined;
const unknown: i32 = -10000;
const invalid_parameter: i32 = -10004;
const BufferInfo = extern struct { offset: i32, size: i32, time_us: i64, flags: u32 };
const Callbacks = extern struct { input: usize, output: usize, format: usize, err: usize };
pub const Scope = struct {
    env: Env,
    attached: bool,
    pub fn enter() ?Scope {
        if (vm == null or backend == null) return null;
        var raw: ?*anyopaque = null;
        const attached = vm.*.*.GetEnv.?(vm, &raw, jni.JNI_VERSION_1_6) != jni.JNI_OK;
        if (attached and vm.*.*.AttachCurrentThread.?(vm, @ptrCast(&raw), null) != jni.JNI_OK) return null;
        const env: Env = @ptrCast(@alignCast(raw));
        if (env.*.*.PushLocalFrame.?(env, 32) != jni.JNI_OK) {
            env.*.*.ExceptionClear.?(env);
            if (attached) _ = vm.*.*.DetachCurrentThread.?(vm);
            return null;
        }
        return .{ .env = env, .attached = attached };
    }
    pub fn leave(self: Scope) void {
        self.env.*.*.ExceptionClear.?(self.env);
        _ = self.env.*.*.PopLocalFrame.?(self.env, null);
        if (self.attached) _ = vm.*.*.DetachCurrentThread.?(vm);
    }
};
fn method(env: Env, name: [*:0]const u8, signature: [*:0]const u8) jni.jmethodID {
    return env.*.*.GetStaticMethodID.?(env, backend, name, signature);
}
fn id(handle: Handle) jni.jvalue {
    return .{ .j = @intCast(@intFromPtr(handle)) };
}
fn status(env: Env) i32 {
    const exception = env.*.*.ExceptionOccurred.?(env) orelse return 0;
    env.*.*.ExceptionClear.?(env);
    const cls = env.*.*.FindClass.?(env, "android/media/MediaCodec$CodecException") orelse return unknown;
    if (env.*.*.IsInstanceOf.?(env, exception, cls) != 0) {
        const getter = env.*.*.GetMethodID.?(env, cls, "getErrorCode", "()I") orelse return unknown;
        const code = env.*.*.CallIntMethodA.?(env, exception, getter, null);
        if (code == 1100 or code == 1101) return code;
    }
    // NdkMediaCodec::translate_error maps other native errors to UNKNOWN.
    return unknown;
}
fn create(name: ?[*:0]const u8, by_type: bool, encoder: bool) Handle {
    const scope = Scope.enter() orelse return null;
    defer scope.leave();
    const env = scope.env;
    const text = env.*.*.NewStringUTF.?(env, name orelse return null) orelse return null;
    const mid = method(env, "create", "(Ljava/lang/String;ZZ)J") orelse return null;
    const args = [_]jni.jvalue{ .{ .l = text }, .{ .z = @intFromBool(by_type) }, .{ .z = @intFromBool(encoder) } };
    const result = env.*.*.CallStaticLongMethodA.?(env, backend, mid, &args);
    if (status(env) != 0 or result <= 0) return null;
    return @ptrFromInt(@as(usize, @intCast(result)));
}
export fn AMediaCodec_createCodecByName(name: ?[*:0]const u8) callconv(.c) Handle {
    return create(name, false, false);
}
export fn AMediaCodec_createDecoderByType(name: ?[*:0]const u8) callconv(.c) Handle {
    return create(name, true, false);
}
export fn AMediaCodec_createEncoderByType(name: ?[*:0]const u8) callconv(.c) Handle {
    return create(name, true, true);
}
fn simple(handle: Handle, name: [*:0]const u8) i32 {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, name, "(J)V") orelse return unknown;
    const args = [_]jni.jvalue{id(handle)};
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
    return status(env);
}
export fn AMediaCodec_delete(handle: Handle) callconv(.c) i32 {
    if (handle == null) return 0;
    return simple(handle, "delete");
}
export fn AMediaCodec_start(handle: Handle) callconv(.c) i32 {
    return simple(handle, "start");
}
export fn AMediaCodec_stop(handle: Handle) callconv(.c) i32 {
    return simple(handle, "stop");
}
export fn AMediaCodec_flush(handle: Handle) callconv(.c) i32 {
    return simple(handle, "flush");
}
export fn AMediaCodec_getName(handle: Handle, out_name: ?*?[*:0]u8) callconv(.c) i32 {
    const output = out_name orelse return invalid_parameter;
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "name", "(J)Ljava/lang/String;") orelse return unknown;
    const args = [_]jni.jvalue{id(handle)};
    const result = env.*.*.CallStaticObjectMethodA.?(env, backend, mid, &args);
    const error_code = status(env);
    if (error_code != 0) return error_code;
    const chars = env.*.*.GetStringUTFChars.?(env, result, null) orelse return unknown;
    defer env.*.*.ReleaseStringUTFChars.?(env, result, chars);
    output.* = (alloc.dupeSentinel(u8, std.mem.span(chars), 0) catch return 1100).ptr;
    return 0;
}
export fn AMediaCodec_releaseName(_: Handle, name: ?[*:0]u8) callconv(.c) void {
    if (name) |value| alloc.free(std.mem.span(value));
}
export fn AMediaCodec_configure(handle: Handle, format: Handle, window: Handle, crypto: Handle, flags: u32) callconv(.c) i32 {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const map = to_map(env, format) orelse return unknown;
    const mid = method(env, "configure", "(JLjava/util/Map;ZZI)V") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .l = map }, .{ .z = @intFromBool(window != null) }, .{ .z = @intFromBool(crypto != null) }, .{ .i = @bitCast(flags) } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
    return status(env);
}
export fn AMediaCodec_setParameters(handle: Handle, format: Handle) callconv(.c) i32 {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const map = to_map(env, format) orelse return unknown;
    const mid = method(env, "parameters", "(JLjava/util/Map;)V") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .l = map } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
    return status(env);
}
export fn AMediaCodec_dequeueInputBuffer(handle: Handle, timeout: i64) callconv(.c) isize {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "dequeueInput", "(JJ)I") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .j = timeout } };
    const result = env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args);
    const error_code = status(env);
    return if (error_code == 0) result else error_code;
}
export fn AMediaCodec_dequeueOutputBuffer(handle: Handle, output: ?*BufferInfo, timeout: i64) callconv(.c) isize {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const info = output orelse return invalid_parameter;
    const array = env.*.*.NewLongArray.?(env, 4) orelse return unknown;
    const mid = method(env, "dequeueOutput", "(JJ[J)I") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .j = timeout }, .{ .l = array } };
    const result = env.*.*.CallStaticIntMethodA.?(env, backend, mid, &args);
    const error_code = status(env);
    if (error_code != 0) return error_code;
    if (result >= 0) {
        var values: [4]jni.jlong = undefined;
        env.*.*.GetLongArrayRegion.?(env, array, 0, 4, &values);
        info.* = .{ .offset = @intCast(values[0]), .size = @intCast(values[1]), .time_us = values[2], .flags = @truncate(@as(u64, @bitCast(values[3]))) };
    }
    return result;
}
fn buffer(handle: Handle, index: usize, out_size: ?*usize, input: bool) ?[*]u8 {
    const scope = Scope.enter() orelse return null;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "buffer", "(JIZ)Ljava/nio/ByteBuffer;") orelse return null;
    const args = [_]jni.jvalue{ id(handle), .{ .i = std.math.cast(i32, index) orelse return null }, .{ .z = @intFromBool(input) } };
    const result = env.*.*.CallStaticObjectMethodA.?(env, backend, mid, &args);
    if (status(env) != 0 or result == null) return null;
    const raw = env.*.*.GetDirectBufferAddress.?(env, result) orelse return null;
    const capacity = env.*.*.GetDirectBufferCapacity.?(env, result);
    if (capacity < 0) return null;
    if (out_size) |size| size.* = @intCast(capacity);
    return @ptrCast(raw);
}
export fn AMediaCodec_getInputBuffer(handle: Handle, index: usize, size: ?*usize) callconv(.c) ?[*]u8 {
    return buffer(handle, index, size, true);
}
export fn AMediaCodec_getOutputBuffer(handle: Handle, index: usize, size: ?*usize) callconv(.c) ?[*]u8 {
    return buffer(handle, index, size, false);
}
export fn AMediaCodec_queueInputBuffer(handle: Handle, index: usize, offset: usize, size: usize, time_us: u64, flags: u32) callconv(.c) i32 {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "queueInput", "(JIIIJI)V") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .i = std.math.cast(i32, index) orelse return unknown }, .{ .i = std.math.cast(i32, offset) orelse return unknown }, .{ .i = std.math.cast(i32, size) orelse return unknown }, .{ .j = @bitCast(time_us) }, .{ .i = @bitCast(flags) } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
    return status(env);
}
fn releaseOutput(handle: Handle, index: usize, render: bool, timed: bool, timestamp: i64) i32 {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "releaseOutput", "(JIZZJ)V") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .i = std.math.cast(i32, index) orelse return unknown }, .{ .z = @intFromBool(render) }, .{ .z = @intFromBool(timed) }, .{ .j = timestamp } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
    return status(env);
}
export fn AMediaCodec_releaseOutputBuffer(handle: Handle, index: usize, render: bool) callconv(.c) i32 {
    return releaseOutput(handle, index, render, false, 0);
}
export fn AMediaCodec_releaseOutputBufferAtTime(handle: Handle, index: usize, timestamp: i64) callconv(.c) i32 {
    return releaseOutput(handle, index, true, true, timestamp);
}
fn getFormat(handle: Handle, input: bool, index: i32) Handle {
    const scope = Scope.enter() orelse return null;
    defer scope.leave();
    const env = scope.env;
    const mid = method(env, "getFormat", "(JZI)Ljava/util/Map;") orelse return null;
    const args = [_]jni.jvalue{ id(handle), .{ .z = @intFromBool(input) }, .{ .i = index } };
    const map = env.*.*.CallStaticObjectMethodA.?(env, backend, mid, &args);
    if (status(env) != 0) return null;
    return from_map(env, map);
}
export fn AMediaCodec_getInputFormat(handle: Handle) callconv(.c) Handle {
    return getFormat(handle, true, -1);
}
export fn AMediaCodec_getOutputFormat(handle: Handle) callconv(.c) Handle {
    return getFormat(handle, false, -1);
}
export fn AMediaCodec_getBufferFormat(handle: Handle, index: usize) callconv(.c) Handle {
    return getFormat(handle, false, std.math.cast(i32, index) orelse return null);
}
export fn AMediaCodec_setAsyncNotifyCallback(handle: Handle, callbacks: Callbacks, userdata: Handle) callconv(.c) i32 {
    const scope = Scope.enter() orelse return unknown;
    defer scope.leave();
    const env = scope.env;
    const values = [_]jni.jlong{ @bitCast(callbacks.input), @bitCast(callbacks.output), @bitCast(callbacks.format), @bitCast(callbacks.err), @bitCast(@intFromPtr(userdata)) };
    const array = env.*.*.NewLongArray.?(env, 5) orelse return unknown;
    env.*.*.SetLongArrayRegion.?(env, array, 0, 5, &values);
    const mid = method(env, "callback", "(J[J)V") orelse return unknown;
    const args = [_]jni.jvalue{ id(handle), .{ .l = array } };
    env.*.*.CallStaticVoidMethodA.?(env, backend, mid, &args);
    return status(env);
}
fn dispatch(env: Env, _: jni.jclass, handle: jni.jlong, callback: jni.jlong, userdata: jni.jlong, kind: jni.jint, index: jni.jint, offset: jni.jint, size: jni.jint, timestamp: jni.jlong, flags: jni.jint, map: jni.jobject, detail: jni.jobject) callconv(.c) void {
    const codec: Handle = @ptrFromInt(@as(usize, @intCast(handle)));
    const user: Handle = @ptrFromInt(@as(usize, @bitCast(userdata)));
    const address: usize = @bitCast(callback);
    switch (kind) {
        0 => {
            const call: *const fn (Handle, Handle, i32) callconv(.c) void = @ptrFromInt(address);
            call(codec, user, index);
        },
        1 => {
            const call: *const fn (Handle, Handle, i32, *BufferInfo) callconv(.c) void = @ptrFromInt(address);
            var info = BufferInfo{ .offset = offset, .size = size, .time_us = timestamp, .flags = @bitCast(flags) };
            call(codec, user, index, &info);
        },
        2 => {
            const call: *const fn (Handle, Handle, Handle) callconv(.c) void = @ptrFromInt(address);
            // Original NDK callback transfers this format allocation to its caller.
            const format = from_map(env, map) orelse return;
            call(codec, user, format);
        },
        3 => {
            const call: *const fn (Handle, Handle, i32, i32, [*:0]const u8) callconv(.c) void = @ptrFromInt(address);
            const text = env.*.*.GetStringUTFChars.?(env, detail, null) orelse return;
            defer env.*.*.ReleaseStringUTFChars.?(env, detail, text);
            call(codec, user, if (index == 1100 or index == 1101) index else unknown, flags, text);
        },
        else => unreachable,
    }
}
export fn AMediaCodecActionCode_isRecoverable(code: i32) callconv(.c) bool {
    return code == 2;
}
export fn AMediaCodecActionCode_isTransient(code: i32) callconv(.c) bool {
    return code == 1;
}
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
    to_map = platform.lookup(ToMap, "azk_media_format_to_map") orelse return error.NoMethod;
    from_map = platform.lookup(FromMap, "azk_media_format_from_map") orelse return error.NoMethod;
    if (env.*.*.GetJavaVM.?(env, &vm) != jni.JNI_OK) return error.NoVm;
    const local = env.*.*.FindClass.?(env, "dev/artzig/HostedNdkMediaCodec") orelse return error.NoClass;
    backend = @ptrCast(env.*.*.NewGlobalRef.?(env, local) orelse return error.NoClass);
    env.*.*.DeleteLocalRef.?(env, local);
    const natives = [_]jni.JNINativeMethod{
        .{ .name = "dispatch", .signature = "(JJJIIIIJILjava/util/Map;Ljava/lang/String;)V", .fnPtr = @ptrCast(@constCast(&dispatch)) },
    };
    if (env.*.*.RegisterNatives.?(env, backend, &natives, natives.len) != 0) return error.RegisterFailed;
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
    inputs: std.atomic.Value(u32) = .init(0),
    pcm: std.atomic.Value(bool) = .init(false),
    eos: std.atomic.Value(bool) = .init(false),
    bad: std.atomic.Value(bool) = .init(false),
    formats: std.atomic.Value(u32) = .init(0),
};
fn smokeInput(codec: Handle, userdata: Handle, index: i32) callconv(.c) void {
    const state: *AsyncSmoke = @ptrCast(@alignCast(userdata));
    const count = state.inputs.fetchAdd(1, .monotonic);
    if (count >= 2) return;
    smokeQueue(codec, index, count == 1) catch {
        state.bad.store(true, .release);
    };
}
fn smokeOutput(codec: Handle, userdata: Handle, index: i32, info: *BufferInfo) callconv(.c) void {
    const state: *AsyncSmoke = @ptrCast(@alignCast(userdata));
    if (smokePcm(codec, index, info) catch blk: {
        state.bad.store(true, .release);
        break :blk false;
    }) state.pcm.store(true, .release);
    if (info.flags & 4 != 0) state.eos.store(true, .release);
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
    // The platform's DT_NEEDED Binder library owns the live Java codec registrar.
    var library = try std.DynLib.open("libandroid_platform.so");
    defer library.close();
    const Create = *const fn (?[*:0]const u8) callconv(.c) Handle;
    try smokeCheck(library.lookup(Create, "AMediaCodec_createDecoderByType") == &AMediaCodec_createDecoderByType);
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
    const input = AMediaCodec_dequeueInputBuffer(codec, 0);
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
    try smokeCheck(AMediaCodec_dequeueInputBuffer(codec, 0) >= 0);
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
    for (0..1500) |_| {
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
