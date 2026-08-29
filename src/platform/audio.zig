//! Android AudioTrack host backend. Zig owns stream state, buffering, timing,
//! and JNI; a small C adapter contains only PipeWire's macro-heavy SPA API.
const std = @import("std");
const art = @import("art");
const pw = @import("pipewire");
const jni = art.jni;

const Mutex = struct {
    raw: std.Io.Mutex = .init,

    fn lock(self: *Mutex) void {
        self.raw.lockUncancelable(g_io);
    }

    fn unlock(self: *Mutex) void {
        self.raw.unlock(g_io);
    }
};

var g_io: std.Io = undefined;

fn nowNs() u64 {
    return @intCast(std.Io.Clock.now(.awake, g_io).nanoseconds);
}

const Stream = struct {
    mutex: Mutex = .{},
    ring: []u8,
    head: usize = 0,
    tail: usize = 0,
    filled: usize = 0,
    sample_rate: u32,
    frame_bytes: usize,
    volume: f32 = 1.0,
    frames_played: u64 = 0,
    played_at_ns: u64 = 0,
    playing: bool = false,
    underruns: u32 = 0,
    pipewire: ?*pw.azk_pw_stream = null,
    fallback_last_ns: u64 = 0,

    fn create(sample_rate: u32, channels: u32) !*Stream {
        if (sample_rate == 0 or channels == 0 or channels > 32) return error.InvalidFormat;
        const allocator = std.heap.c_allocator;
        const stream = try allocator.create(Stream);
        errdefer allocator.destroy(stream);
        const frame_bytes = try std.math.mul(usize, 2, channels);
        const capacity = try std.math.mul(usize, frame_bytes, sample_rate);
        const ring = try allocator.alloc(u8, capacity);
        stream.* = .{ .ring = ring, .sample_rate = sample_rate, .frame_bytes = frame_bytes };
        stream.pipewire = pw.azk_pw_open(@intCast(sample_rate), @intCast(channels), @intCast(frame_bytes), pipewireFill, stream);
        return stream;
    }

    fn destroy(stream: *Stream) void {
        stream.mutex.lock();
        stream.playing = false;
        stream.mutex.unlock();
        if (stream.pipewire) |backend| pw.azk_pw_close(backend);
        std.heap.c_allocator.free(stream.ring);
        std.heap.c_allocator.destroy(stream);
    }

    fn write(stream: *Stream, source: [*]const u8, requested: usize) usize {
        stream.mutex.lock();
        defer stream.mutex.unlock();
        const amount = @min(requested, stream.ring.len - stream.filled);
        const first = @min(amount, stream.ring.len - stream.tail);
        @memcpy(stream.ring[stream.tail..][0..first], source[0..first]);
        const second = amount - first;
        @memcpy(stream.ring[0..second], source[first..][0..second]);
        stream.tail = (stream.tail + amount) % stream.ring.len;
        stream.filled += amount;
        return amount;
    }

    fn fill(stream: *Stream, destination: [*]u8, capacity: usize) usize {
        stream.mutex.lock();
        defer stream.mutex.unlock();
        if (!stream.playing) return 0;
        const amount = @min(capacity, stream.filled);
        const first = @min(amount, stream.ring.len - stream.head);
        @memcpy(destination[0..first], stream.ring[stream.head..][0..first]);
        const second = amount - first;
        @memcpy(destination[first..][0..second], stream.ring[0..second]);
        stream.head = (stream.head + amount) % stream.ring.len;
        stream.filled -= amount;
        if (amount == 0) stream.underruns +%= 1;
        applyVolume(destination[0..amount], stream.volume);
        stream.frames_played +%= amount / stream.frame_bytes;
        stream.played_at_ns = nowNs();
        return amount;
    }

    fn flush(stream: *Stream) void {
        stream.mutex.lock();
        defer stream.mutex.unlock();
        stream.head = 0;
        stream.tail = 0;
        stream.filled = 0;
    }

    fn fallbackTick(stream: *Stream) void {
        if (stream.pipewire != null) return;
        stream.fallbackTickAt(nowNs());
    }

    fn fallbackTickAt(stream: *Stream, current_ns: u64) void {
        stream.mutex.lock();
        defer stream.mutex.unlock();
        if (!stream.playing) {
            stream.fallback_last_ns = current_ns;
            return;
        }
        if (stream.fallback_last_ns == 0) stream.fallback_last_ns = current_ns;
        const elapsed = current_ns -| stream.fallback_last_ns;
        const due = elapsed * stream.sample_rate / std.time.ns_per_s;
        if (due == 0) return;
        const available = stream.filled / stream.frame_bytes;
        const consumed = @min(due, available);
        const bytes: usize = @intCast(consumed * stream.frame_bytes);
        stream.head = (stream.head + bytes) % stream.ring.len;
        stream.filled -= bytes;
        stream.frames_played +%= consumed;
        stream.played_at_ns = current_ns;
        stream.fallback_last_ns = current_ns;
        if (consumed < due and available > 0) stream.underruns +%= 1;
    }
};

fn applyVolume(bytes: []u8, volume: f32) void {
    if (volume >= 0.999) return;
    var index: usize = 0;
    while (index + 1 < bytes.len) : (index += 2) {
        const sample = std.mem.readInt(i16, bytes[index..][0..2], .little);
        const scaled: i16 = @intFromFloat(@as(f32, @floatFromInt(sample)) * volume);
        std.mem.writeInt(i16, bytes[index..][0..2], scaled, .little);
    }
}

fn pipewireFill(userdata: ?*anyopaque, destination: [*c]u8, capacity: usize) callconv(.c) usize {
    const stream: *Stream = @ptrCast(@alignCast(userdata orelse return 0));
    return stream.fill(@ptrCast(destination), capacity);
}

fn fromHandle(value: jni.jlong) ?*Stream {
    if (value == 0) return null;
    return @ptrFromInt(@as(usize, @intCast(value)));
}

fn nativeOpen(_: [*c]jni.JNIEnv, _: jni.jclass, sample_rate: jni.jint, channels: jni.jint) callconv(.c) jni.jlong {
    if (sample_rate <= 0 or channels <= 0) return 0;
    const stream = Stream.create(@intCast(sample_rate), @intCast(channels)) catch return 0;
    return @intCast(@intFromPtr(stream));
}

fn nativeWrite(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, buffer: jni.jobject, offset: jni.jint, size: jni.jint) callconv(.c) jni.jint {
    const stream = fromHandle(handle) orelse return -1;
    if (offset < 0 or size < 0) return -1;
    stream.fallbackTick();
    const raw = env.*.*.GetDirectBufferAddress.?(env, buffer) orelse return -1;
    const source: [*]const u8 = @ptrCast(raw);
    return @intCast(stream.write(source + @as(usize, @intCast(offset)), @intCast(size)));
}

fn nativeWriteArray(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, array: jni.jbyteArray, offset: jni.jint, size: jni.jint) callconv(.c) jni.jint {
    const stream = fromHandle(handle) orelse return -1;
    if (offset < 0 or size < 0) return -1;
    stream.fallbackTick();
    const raw = env.*.*.GetByteArrayElements.?(env, array, null) orelse return -1;
    defer env.*.*.ReleaseByteArrayElements.?(env, array, raw, 2); // JNI_ABORT
    const source: [*]const u8 = @ptrCast(raw);
    return @intCast(stream.write(source + @as(usize, @intCast(offset)), @intCast(size)));
}

fn nativePlay(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    const stream = fromHandle(handle) orelse return;
    stream.mutex.lock();
    stream.fallback_last_ns = nowNs();
    stream.playing = true;
    stream.mutex.unlock();
    if (stream.pipewire) |backend| pw.azk_pw_set_active(backend, 1);
}

fn nativePause(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    const stream = fromHandle(handle) orelse return;
    stream.fallbackTick();
    stream.mutex.lock();
    stream.playing = false;
    stream.mutex.unlock();
    if (stream.pipewire) |backend| pw.azk_pw_set_active(backend, 0);
}

fn nativeFlush(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    if (fromHandle(handle)) |stream| stream.flush();
}

fn nativeGetPosition(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jlong {
    const stream = fromHandle(handle) orelse return 0;
    stream.fallbackTick();
    stream.mutex.lock();
    defer stream.mutex.unlock();
    return @intCast(stream.frames_played);
}

fn nativeGetPositionNanos(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jlong {
    const stream = fromHandle(handle) orelse return 0;
    stream.mutex.lock();
    defer stream.mutex.unlock();
    return @intCast(stream.played_at_ns);
}

fn nativeGetUnderruns(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jint {
    const stream = fromHandle(handle) orelse return 0;
    stream.mutex.lock();
    defer stream.mutex.unlock();
    return @intCast(stream.underruns);
}

fn nativeSetVolume(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, volume: jni.jfloat) callconv(.c) void {
    const stream = fromHandle(handle) orelse return;
    stream.mutex.lock();
    defer stream.mutex.unlock();
    stream.volume = std.math.clamp(volume, 0.0, 1.0);
}

fn nativeRelease(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    if (fromHandle(handle)) |stream| stream.destroy();
}

pub fn register(io: std.Io, env: [*c]jni.JNIEnv) !void {
    g_io = io;
    const cls = env.*.*.FindClass.?(env, "android/media/AudioTrack") orelse return error.NoClass;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nativeOpen", .signature = "(II)J", .fnPtr = @ptrCast(@constCast(&nativeOpen)) },
        .{ .name = "nativeWrite", .signature = "(JLjava/nio/ByteBuffer;II)I", .fnPtr = @ptrCast(@constCast(&nativeWrite)) },
        .{ .name = "nativeWriteArray", .signature = "(J[BII)I", .fnPtr = @ptrCast(@constCast(&nativeWriteArray)) },
        .{ .name = "nativePlay", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativePlay)) },
        .{ .name = "nativePause", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativePause)) },
        .{ .name = "nativeFlush", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeFlush)) },
        .{ .name = "nativeGetPosition", .signature = "(J)J", .fnPtr = @ptrCast(@constCast(&nativeGetPosition)) },
        .{ .name = "nativeGetPositionNanos", .signature = "(J)J", .fnPtr = @ptrCast(@constCast(&nativeGetPositionNanos)) },
        .{ .name = "nativeGetUnderruns", .signature = "(J)I", .fnPtr = @ptrCast(@constCast(&nativeGetUnderruns)) },
        .{ .name = "nativeSetVolume", .signature = "(JF)V", .fnPtr = @ptrCast(@constCast(&nativeSetVolume)) },
        .{ .name = "nativeRelease", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeRelease)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

test "ring buffer wraps and flushes" {
    g_io = std.testing.io;
    var ring: [8]u8 = undefined;
    var stream = Stream{ .ring = &ring, .sample_rate = 4, .frame_bytes = 2 };
    try std.testing.expectEqual(@as(usize, 6), stream.write("abcdef", 6));
    stream.playing = true;
    var first: [4]u8 = undefined;
    try std.testing.expectEqual(@as(usize, 4), stream.fill(&first, first.len));
    try std.testing.expectEqualStrings("abcd", &first);
    try std.testing.expectEqual(@as(usize, 6), stream.write("ghijkl", 6));
    var second: [8]u8 = undefined;
    try std.testing.expectEqual(@as(usize, 8), stream.fill(&second, second.len));
    try std.testing.expectEqualStrings("efghijkl", &second);
    stream.flush();
    try std.testing.expectEqual(@as(usize, 0), stream.filled);
}

test "fallback clock consumes whole frames" {
    g_io = std.testing.io;
    var ring: [16]u8 = undefined;
    var stream = Stream{ .ring = &ring, .sample_rate = 4, .frame_bytes = 2 };
    try std.testing.expectEqual(@as(usize, 8), stream.write("abcdefgh", 8));
    stream.playing = true;
    stream.fallback_last_ns = 1;
    stream.fallbackTickAt(1 + std.time.ns_per_s / 2);
    try std.testing.expectEqual(@as(u64, 2), stream.frames_played);
    try std.testing.expectEqual(@as(usize, 4), stream.filled);
}
