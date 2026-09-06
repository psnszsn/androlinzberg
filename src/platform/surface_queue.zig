//! Stock BufferItemConsumer leases delivered to the Wayland owner. All acquire
//! fence waits and consumer release calls execute on one bounded worker.
const std = @import("std");
const Io = std.Io;
const wayland = @import("wayland.zig");
pub const Window = wayland.Window;
const c = struct {
    const AzkSurfaceQueue = opaque {};
    const AzkSurfaceFrame = opaque {};
    const AIBinder = opaque {};
    const AzkGraphicsBufferInfo = @import("graphics_types.zig").Info;
    extern "c" fn azk_surface_queue_create(u32, u32, *const fn (?*anyopaque) callconv(.c) void, ?*anyopaque) ?*AzkSurfaceQueue;
    extern "c" fn azk_surface_queue_producer(*AzkSurfaceQueue) ?*AIBinder;
    extern "c" fn azk_surface_queue_destroy(*AzkSurfaceQueue) void;
    extern "c" fn azk_surface_queue_acquire(*AzkSurfaceQueue, *?*AzkSurfaceFrame, *AzkGraphicsBufferInfo, *u64) i32;
    extern "c" fn azk_surface_frame_wait(*AzkSurfaceFrame, i32) i32;
    extern "c" fn azk_surface_frame_release(*AzkSurfaceFrame, i32) i32;
    extern "c" fn azk_surface_frame_discard(*AzkSurfaceFrame) void;
    extern "c" fn azk_surface_frame_abandon(*AzkSurfaceFrame) void;
};
const Self = @This();
const Frame = struct {
    owner: *Self,
    native: *c.AzkSurfaceFrame,
    info: c.AzkGraphicsBufferInfo,
    number: u64,
};
const Return = struct { frame: *Frame, abandon: bool };

gpa: std.mem.Allocator,
io: Io,
native: *c.AzkSurfaceQueue,
notify_file: Io.File,
wake: Io.Event = .unset,
stopping: std.atomic.Value(bool) = .init(false),
failure: std.atomic.Value(i32) = .init(0),
group: Io.Group = .init,
ready_storage: [2]*Frame = undefined,
return_storage: [4]Return = undefined,
ready: Io.Queue(*Frame),
returns: Io.Queue(Return),
in_flight: usize = 0, // Worker-owned; stricter than AOSP's extra-acquire allowance.

pub fn init(gpa: std.mem.Allocator, io: Io, width: u32, height: u32) !*Self {
    const raw = std.os.linux.eventfd(0, std.os.linux.EFD.CLOEXEC | std.os.linux.EFD.NONBLOCK);
    if (std.os.linux.errno(raw) != .SUCCESS) return error.EventfdFailed;
    const file: Io.File = .{ .handle = @intCast(raw), .flags = .{ .nonblocking = true } };
    errdefer file.close(io);
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    self.* = .{ .gpa = gpa, .io = io, .native = undefined, .notify_file = file, .ready = undefined, .returns = undefined };
    self.ready = .init(&self.ready_storage);
    self.returns = .init(&self.return_storage);
    self.native = c.azk_surface_queue_create(width, height, notify, self) orelse return error.QueueCreationFailed;
    errdefer c.azk_surface_queue_destroy(self.native);
    try self.group.concurrent(io, worker, .{self});
    return self;
}

/// Destroy the Wayland window first, so every imported lease is returned with
/// its release/abandonment distinction before the worker and native queue stop.
pub fn deinit(self: *Self) void {
    self.stopping.store(true, .release);
    self.wake.set(self.io);
    self.group.await(self.io) catch unreachable;
    c.azk_surface_queue_destroy(self.native);
    self.notify_file.close(self.io);
    self.gpa.destroy(self);
}

pub fn producer(self: *Self) *c.AIBinder {
    return c.azk_surface_queue_producer(self.native).?;
}
pub fn requestPresentation(self: *Self) void {
    self.signalOwner();
}
pub fn drainNotification(self: *Self) !void {
    var counter: u64 = undefined;
    _ = try self.notify_file.readStreaming(self.io, &.{std.mem.asBytes(&counter)});
}

fn notify(context: ?*anyopaque) callconv(.c) void {
    const self: *Self = @ptrCast(@alignCast(context.?));
    self.wake.set(self.io);
}
fn signalOwner(self: *Self) void {
    const one: u64 = 1;
    self.notify_file.writeStreamingAll(self.io, std.mem.asBytes(&one)) catch |err| {
        std.log.err("surface queue notification failed: {s}", .{@errorName(err)});
        self.failure.store(-5, .release);
    };
}
fn finish(self: *Self, returned: Return) void {
    if (returned.abandon) {
        c.azk_surface_frame_abandon(returned.frame.native);
    } else {
        const status = c.azk_surface_frame_release(returned.frame.native, -1);
        // AOSP STALE_BUFFER_SLOT means the slot was retired while in flight.
        if (status != 0 and status != 1 and !self.stopping.load(.acquire)) self.failure.store(status, .release);
    }
    self.gpa.destroy(returned.frame);
    self.in_flight -= 1;
}
fn drainReturns(self: *Self) void {
    var batch: [4]Return = undefined;
    const count = self.returns.getUncancelable(self.io, &batch, 0) catch unreachable;
    for (batch[0..count]) |returned| self.finish(returned);
}
fn worker(self: *Self) void {
    while (true) {
        self.wake.waitUncancelable(self.io);
        self.wake.reset();
        self.drainReturns();
        if (self.stopping.load(.acquire)) break;
        while (!self.stopping.load(.acquire)) {
            if (self.in_flight == self.ready_storage.len) break;
            var native: ?*c.AzkSurfaceFrame = null;
            var info: c.AzkGraphicsBufferInfo = undefined;
            var number: u64 = undefined;
            const status = c.azk_surface_queue_acquire(self.native, &native, &info, &number);
            // Our own two-lease bound prevents AOSP's extra-acquire limit;
            // INVALID_OPERATION therefore remains an error, not queue pressure.
            if (status == 2) break;
            if (status != 0) {
                std.log.err("surface queue acquire failed: {d}", .{status});
                self.failure.store(status, .release);
                self.signalOwner();
                break;
            }
            const waited = c.azk_surface_frame_wait(native.?, 1000);
            if (waited != 0) {
                std.log.err("surface queue acquire fence failed: {d}", .{waited});
                c.azk_surface_frame_discard(native.?);
                self.failure.store(waited, .release);
                self.signalOwner();
                break;
            }
            const frame = self.gpa.create(Frame) catch {
                c.azk_surface_frame_discard(native.?);
                self.failure.store(-12, .release);
                self.signalOwner();
                break;
            };
            frame.* = .{ .owner = self, .native = native.?, .info = info, .number = number };
            self.in_flight += 1;
            self.ready.putOneUncancelable(self.io, frame) catch unreachable;
            self.signalOwner();
        }
    }
    self.drainReturns();
    var remaining: [2]*Frame = undefined;
    const count = self.ready.getUncancelable(self.io, &remaining, 0) catch unreachable;
    for (remaining[0..count]) |frame| {
        c.azk_surface_frame_abandon(frame.native);
        self.gpa.destroy(frame);
    }
}
fn returnFrame(context: *anyopaque, should_abandon: bool) void {
    const frame: *Frame = @ptrCast(@alignCast(context));
    const owner = frame.owner;
    // Capacity exceeds the stock consumer's maximum acquired count. This
    // enqueue never waits on a producer, GPU or Binder callback.
    owner.returns.putOneUncancelable(owner.io, .{ .frame = frame, .abandon = should_abandon }) catch unreachable;
    owner.wake.set(owner.io);
}
fn release(context: *anyopaque) void {
    returnFrame(context, false);
}
fn abandon(context: *anyopaque) void {
    returnFrame(context, true);
}

/// Call only when notify_file is readable, then present all newly ready frames.
pub fn presentReady(self: *Self, window: *wayland.Window) !usize {
    try self.drainNotification();
    if (self.failure.load(.acquire) != 0) return error.SurfaceQueueFailed;
    var frames: [2]*Frame = undefined;
    const count = try self.ready.get(self.io, &frames, 0);
    for (frames[0..count], 0..) |frame, index| {
        errdefer for (frames[index + 1 .. count]) |pending| returnFrame(pending, true);
        const info = frame.info;
        std.log.info("surface-frame number={d} buffer={d} width={d} height={d}", .{ frame.number, info.id, info.width, info.height });
        try window.pushUiDmabuf(.{
            .context = frame,
            .release_fn = release,
            .abandon_fn = abandon,
            .width = @intCast(info.width),
            .height = @intCast(info.height),
            .format = info.drm_format,
            .plane_count = 1,
            .fds = .{ info.fd, -1, -1, -1 },
            .offsets = .{ info.offset, 0, 0, 0 },
            .strides = .{ info.stride, 0, 0, 0 },
            .modifiers = .{ info.modifier, 0, 0, 0 },
        });
        try window.present();
    }
    return count;
}
