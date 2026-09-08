//! Owned, bounded FD transfers for Wayland clipboard and drag-and-drop offers.
//! Run these operations on an Io concurrent task, never the Wayland owner.
//! One absolute deadline covers the entire stream, including slow progress.
const std = @import("std");
const Io = std.Io;

pub const Pipe = struct {
    read: Io.File,
    write: Io.File,

    pub fn init() !Pipe {
        // std.Io has no pipe constructor. Ownership transfers immediately;
        // subsequent operations, cancellation and close all belong to std.Io.
        var handles: [2]i32 = undefined;
        const result = std.os.linux.pipe2(&handles, .{ .CLOEXEC = true, .NONBLOCK = true });
        if (std.os.linux.errno(result) != .SUCCESS) return error.PipeCreationFailed;
        return .{
            .read = .{ .handle = handles[0], .flags = .{ .nonblocking = true } },
            .write = .{ .handle = handles[1], .flags = .{ .nonblocking = true } },
        };
    }
};

fn checkDeadline(io: Io, deadline: Io.Clock.Timestamp) !void {
    try io.checkCancel();
    if (Io.Clock.Timestamp.now(io, deadline.clock).compare(.gte, deadline)) return error.Timeout;
}

fn nonblocking(io: Io, file: Io.File) !Io.File {
    var enabled: i32 = 1;
    const result = try io.operate(.{ .device_io_control = .{
        .file = file,
        .code = std.os.linux.T.FIONBIO,
        .arg = &enabled,
    } });
    if (result.device_io_control != 0) return error.NonblockingTransferUnavailable;
    var ready = file;
    ready.flags.nonblocking = true;
    return ready;
}

/// Consumes file on success, error and cancellation. An overlong offer fails
/// atomically: callers never receive a truncated clipboard payload.
pub fn receive(gpa: std.mem.Allocator, io: Io, file: Io.File, limit: usize, deadline: Io.Clock.Timestamp) ![]u8 {
    defer file.close(io);
    const ready = try nonblocking(io, file);
    var bytes: std.ArrayList(u8) = .empty;
    defer bytes.deinit(gpa);
    var buffer: [8192]u8 = undefined;
    var storage: [1]Io.Operation.Storage = undefined;
    var batch = Io.Batch.init(&storage);
    defer batch.cancel(io);
    while (true) {
        try checkDeadline(io, deadline);
        // Read one byte beyond the limit to distinguish exact-size EOF from
        // an overlong stream, without allocating above the payload budget.
        const length = @min(buffer.len, (limit - bytes.items.len) +| 1);
        const vectors = [_][]u8{buffer[0..length]};
        batch.addAt(0, .{ .file_read_streaming = .{ .file = ready, .data = &vectors } });
        try batch.awaitConcurrent(io, .{ .deadline = deadline });
        const completion = batch.next() orelse return error.MissingTransferCompletion;
        const count = completion.result.file_read_streaming catch |err| switch (err) {
            error.EndOfStream => return bytes.toOwnedSlice(gpa),
            error.WouldBlock => continue,
            else => return err,
        };
        if (count == 0) continue;
        if (count > limit - bytes.items.len) return error.OfferTooLarge;
        const needed = bytes.items.len + count;
        if (needed > bytes.capacity)
            try bytes.ensureTotalCapacityPrecise(gpa, @min(limit, @max(needed, bytes.capacity +| bytes.capacity / 2 +| 8192)));
        bytes.appendSliceAssumeCapacity(buffer[0..count]);
    }
}

/// Consumes file. The caller retains immutable bytes until completion/cancel.
pub fn send(io: Io, file: Io.File, bytes: []const u8, deadline: Io.Clock.Timestamp) !void {
    defer file.close(io);
    const ready = try nonblocking(io, file);
    var storage: [1]Io.Operation.Storage = undefined;
    var batch = Io.Batch.init(&storage);
    defer batch.cancel(io);
    var offset: usize = 0;
    while (offset < bytes.len) {
        try checkDeadline(io, deadline);
        const vectors = [_][]const u8{bytes[offset..@min(bytes.len, offset +| 8192)]};
        batch.addAt(0, .{ .file_write_streaming = .{ .file = ready, .data = &vectors } });
        try batch.awaitConcurrent(io, .{ .deadline = deadline });
        const completion = batch.next() orelse return error.MissingTransferCompletion;
        const count = completion.result.file_write_streaming catch |err| switch (err) {
            error.WouldBlock => continue,
            else => return err,
        };
        if (count == 0) return error.NoTransferProgress;
        offset += count;
    }
}

fn after(io: Io, milliseconds: i64) Io.Clock.Timestamp {
    return .fromNow(io, .{ .clock = .awake, .raw = .fromMilliseconds(milliseconds) });
}

test "offer transfers preserve bytes across pipe backpressure and exact payload limit" {
    const io = std.testing.io;
    const gpa = std.testing.allocator;
    const original = try gpa.alloc(u8, 256 * 1024);
    defer gpa.free(original);
    for (original, 0..) |*byte, index| byte.* = @truncate(index);
    const pipe = try Pipe.init();
    var writer = io.concurrent(send, .{ io, pipe.write, original, after(io, 5000) }) catch |err| {
        pipe.read.close(io);
        pipe.write.close(io);
        return err;
    };
    defer writer.cancel(io) catch {};
    const received = try receive(gpa, io, pipe.read, original.len, after(io, 5000));
    defer gpa.free(received);
    try writer.await(io);
    try std.testing.expectEqualSlices(u8, original, received);
}

test "oversized and empty offers are distinct" {
    const io = std.testing.io;
    const pipe = try Pipe.init();
    try send(io, pipe.write, "abcd", after(io, 5000));
    try std.testing.expectError(error.OfferTooLarge, receive(std.testing.allocator, io, pipe.read, 3, after(io, 5000)));
    const empty = try Pipe.init();
    try send(io, empty.write, "", after(io, 5000));
    const bytes = try receive(std.testing.allocator, io, empty.read, 0, after(io, 5000));
    defer std.testing.allocator.free(bytes);
    try std.testing.expectEqual(@as(usize, 0), bytes.len);
}

test "unresponsive sources and receivers hit a transfer deadline" {
    const io = std.testing.io;
    const source = try Pipe.init();
    defer source.write.close(io);
    try std.testing.expectError(error.Timeout, receive(std.testing.allocator, io, source.read, 100, after(io, 10)));
    const sink = try Pipe.init();
    defer sink.read.close(io);
    const bytes: [256 * 1024]u8 = @splat('x');
    try std.testing.expectError(error.Timeout, send(io, sink.write, &bytes, after(io, 10)));
}

test "canceling an offer closes the owned read descriptor" {
    const io = std.testing.io;
    const pipe = try Pipe.init();
    defer pipe.write.close(io);
    var reader = io.concurrent(receive, .{ std.testing.allocator, io, pipe.read, 100, after(io, 5000) }) catch |err| {
        pipe.read.close(io);
        return err;
    };
    try std.testing.expectError(error.Canceled, reader.cancel(io));
    try std.testing.expectError(error.BrokenPipe, pipe.write.writeStreamingAll(io, "x"));
}

test "blocking descriptors supplied by native clients remain deadline bounded" {
    const io = std.testing.io;
    var handles: [2]i32 = undefined;
    try std.testing.expectEqual(@as(usize, 0), std.os.linux.pipe2(&handles, .{ .CLOEXEC = true }));
    const reader: Io.File = .{ .handle = handles[0], .flags = .{ .nonblocking = false } };
    const writer: Io.File = .{ .handle = handles[1], .flags = .{ .nonblocking = false } };
    defer reader.close(io);
    const bytes: [256 * 1024]u8 = @splat('x');
    try std.testing.expectError(error.Timeout, send(io, writer, &bytes, after(io, 10)));
}
