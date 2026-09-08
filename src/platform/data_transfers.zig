//! Bounded worker ownership for clipboard/DnD streams. The Wayland thread
//! submits and drains; workers only touch files, immutable bytes and the wake.
const std = @import("std");
const Io = std.Io;
const Stream = @import("data_transfer.zig");
const EventFd = @import("event_fd").EventFd;
const Self = @This();

pub const capacity = 8;
pub const max_bytes = 256 * 1024;
pub const Completion = struct {
    token: u64,
    // Caller owns successful bytes, allocated with the pool's allocator.
    result: anyerror![]u8,
};
const Work = struct {
    pool: *Self,
    token: u64,
    file: Io.File,
    bytes: ?[]u8,
    deadline: Io.Clock.Timestamp,
    done: std.atomic.Value(bool) = .init(false),
    future: Io.Future(anyerror![]u8) = undefined,

    fn run(self: *Work) anyerror![]u8 {
        defer {
            self.done.store(true, .release);
            self.pool.wake.signal();
        }
        if (self.bytes) |bytes| {
            defer self.pool.gpa.free(bytes);
            try Stream.send(self.pool.io, self.file, bytes, self.deadline);
            return &.{};
        }
        return Stream.receive(self.pool.gpa, self.pool.io, self.file, max_bytes, self.deadline);
    }
};

gpa: std.mem.Allocator,
io: Io,
wake: EventFd,
slots: [capacity]?*Work = @splat(null),

pub fn init(gpa: std.mem.Allocator, io: Io) !*Self {
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    self.* = .{ .gpa = gpa, .io = io, .wake = try EventFd.init(io) };
    return self;
}

pub fn deinit(self: *Self) void {
    for (&self.slots) |*slot| if (slot.*) |work| {
        self.cancelWork(work);
        slot.* = null;
    };
    self.wake.close();
    self.gpa.destroy(self);
}

/// File ownership transfers even if the worker/byte budget rejects the request.
pub fn receive(self: *Self, token: u64, file: Io.File) !void {
    try self.start(token, file, null);
}

/// Copies immutable bytes before returning; File ownership always transfers.
pub fn send(self: *Self, token: u64, file: Io.File, bytes: []const u8) !void {
    if (bytes.len > max_bytes) {
        file.close(self.io);
        return error.OfferTooLarge;
    }
    // Check concurrency before copying, including workers awaiting collection.
    if (!self.hasRoom()) {
        file.close(self.io);
        return error.TransferCapacityExceeded;
    }
    const copy = self.gpa.dupe(u8, bytes) catch |err| {
        file.close(self.io);
        return err;
    };
    try self.start(token, file, copy);
}

fn hasRoom(self: *const Self) bool {
    for (self.slots) |slot| if (slot == null) return true;
    return false;
}

fn start(self: *Self, token: u64, file: Io.File, bytes: ?[]u8) !void {
    errdefer file.close(self.io);
    errdefer if (bytes) |owned| self.gpa.free(owned);
    const slot = for (&self.slots) |*slot| {
        if (slot.* == null) break slot;
    } else return error.TransferCapacityExceeded;
    const work = try self.gpa.create(Work);
    errdefer self.gpa.destroy(work);
    work.* = .{
        .pool = self,
        .token = token,
        .file = file,
        .bytes = bytes,
        .deadline = .fromNow(self.io, .{ .clock = .awake, .raw = .fromMilliseconds(3000) }),
    };
    work.future = try self.io.concurrent(Work.run, .{work});
    slot.* = work;
}

/// Cancel streams when an offer, source, seat or owning process is retired.
pub fn cancel(self: *Self, token: u64) void {
    for (&self.slots) |*slot| if (slot.*) |work| {
        if (work.token != token) continue;
        self.cancelWork(work);
        slot.* = null;
    };
}

fn cancelWork(self: *Self, work: *Work) void {
    if (work.future.cancel(self.io)) |bytes| self.gpa.free(bytes) else |_| {}
    self.gpa.destroy(work);
}

pub fn take(self: *Self) ?Completion {
    for (&self.slots) |*slot| if (slot.*) |work| {
        if (!work.done.load(.acquire)) continue;
        const completion: Completion = .{ .token = work.token, .result = work.future.await(self.io) };
        self.gpa.destroy(work);
        slot.* = null;
        return completion;
    };
    return null;
}

test "offer worker capacity includes completed transfers until collected" {
    const io = std.testing.io;
    const pool = try init(std.testing.allocator, io);
    defer pool.deinit();
    for (0..capacity) |i| {
        const pipe = try Stream.Pipe.init();
        pipe.write.close(io);
        try pool.receive(i, pipe.read);
    }
    const excess = try Stream.Pipe.init();
    defer excess.write.close(io);
    try std.testing.expectError(error.TransferCapacityExceeded, pool.receive(100, excess.read));
    try std.testing.expectError(error.BrokenPipe, excess.write.writeStreamingAll(io, "x"));
    // Cancellation also reclaims already completed results without leaking.
    pool.cancel(0);
    const replacement = try Stream.Pipe.init();
    replacement.write.close(io);
    try pool.receive(100, replacement.read);
}

test "retiring an offer cancels its blocked streams and preserves sibling streams" {
    const io = std.testing.io;
    const pool = try init(std.testing.allocator, io);
    defer pool.deinit();
    const old = try Stream.Pipe.init();
    defer old.write.close(io);
    try pool.receive(7, old.read);
    const sibling = try Stream.Pipe.init();
    try pool.receive(8, sibling.read);
    pool.cancel(7);
    try std.testing.expectError(error.BrokenPipe, old.write.writeStreamingAll(io, "x"));
    try Stream.send(io, sibling.write, "kept", .fromNow(io, .{ .clock = .awake, .raw = .fromMilliseconds(3000) }));
    // Wait for the worker using the same owned wake supplied to the event loop.
    const deadline: Io.Timeout = .{ .duration = .{ .clock = .awake, .raw = .fromMilliseconds(3000) } };
    const until = deadline.toDeadline(io);
    while (true) {
        if (pool.take()) |completion| {
            const bytes = try completion.result;
            defer std.testing.allocator.free(bytes);
            try std.testing.expectEqual(@as(u64, 8), completion.token);
            try std.testing.expectEqualStrings("kept", bytes);
            break;
        }
        var counter: u64 = undefined;
        var storage: [1]Io.Operation.Storage = undefined;
        var batch = Io.Batch.init(&storage);
        defer batch.cancel(io);
        batch.addAt(0, .{ .file_read_streaming = .{ .file = pool.wake.file, .data = &.{std.mem.asBytes(&counter)} } });
        try batch.awaitConcurrent(io, until);
        _ = try batch.next().?.result.file_read_streaming;
    }
}

test "stream contracts" {
    _ = Stream;
}
