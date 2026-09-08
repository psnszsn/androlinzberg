//! Stock BufferItemConsumer leases delivered to the Wayland owner. All acquire
//! fence waits and consumer release calls execute on one bounded worker.
const std = @import("std");
const Io = std.Io;
const wayland = @import("wayland.zig");
pub const Window = wayland.Window;
pub const Session = wayland.Session;
pub const DragKind = wayland.DragKind;
pub const DragIcon = wayland.DragIcon;
pub const KeyboardLayout = wayland.KeyboardLayout;
pub const TextInput = wayland.TextInput;
pub const Clipboard = wayland.Clipboard;
const c = struct {
    const AzkSurfaceQueue = opaque {};
    const AzkSurfaceFrame = opaque {};
    const AIBinder = opaque {};
    const AParcel = opaque {};
    const AzkGraphicsBufferInfo = @import("graphics_types.zig").Info;
    extern "c" fn azk_surface_queue_create(u32, u32, *const fn (?*anyopaque) callconv(.c) void, ?*anyopaque) ?*AzkSurfaceQueue;
    extern "c" fn azk_surface_queue_producer(*AzkSurfaceQueue) ?*AIBinder;
    extern "c" fn azk_surface_queue_write_surface(*AzkSurfaceQueue, *c.AParcel) i32;
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
waiting_frame: std.atomic.Value(u64) = .init(0),
group: Io.Group = .init,
ready_storage: [2]*Frame = undefined,
return_storage: [4]Return = undefined,
ready: Io.Queue(*Frame),
returns: Io.Queue(Return),
in_flight: usize = 0, // Worker-owned; stricter than AOSP's extra-acquire allowance.
abandoned: bool = false, // Worker-owned; abandoning a lease retires its consumer.
// Owner-thread state. Private SF outputs fix their size for one generation;
// legacy consumers may resize their producer without replacing the queue.
expected_size: ?[2]u32 = null,
presented_width: u32 = 0,
presented_height: u32 = 0,
presented_number: u64 = 0,
pace_frames: bool = false,
pending_frame: ?*Frame = null,
submitted_frames: u64 = 0,
deferred_frames: u64 = 0,
coalesced_frames: u64 = 0,

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
    if (self.pending_frame) |frame| returnFrame(frame, true);
    self.pending_frame = null;
    self.wake.set(self.io);
    self.group.await(self.io) catch unreachable;
    c.azk_surface_queue_destroy(self.native);
    self.notify_file.close(self.io);
    self.gpa.destroy(self);
}

pub fn producer(self: *Self) *c.AIBinder {
    return c.azk_surface_queue_producer(self.native).?;
}
pub fn writeSurface(self: *Self, parcel: *anyopaque) i32 {
    return c.azk_surface_queue_write_surface(self.native, @ptrCast(parcel));
}
pub fn requestPresentation(self: *Self) void {
    self.signalOwner();
}
/// A frame whose acquire fence is currently owned by the worker. Used by
/// session diagnostics to distinguish GPU waiting from an unresponsive owner.
pub fn waitingFrame(self: *const Self) u64 {
    return self.waiting_frame.load(.acquire);
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
        self.abandoned = true;
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
        while (!self.abandoned and !self.stopping.load(.acquire)) {
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
            // A retired output must not hold the Wayland owner in deinit for
            // an entire acquire-fence timeout. AOSP still owns the fence; poll
            // it in bounded slices while retaining the original one-second limit.
            const deadline = Io.Clock.awake.now(self.io).toMilliseconds() + 1000;
            const wait_result: ?i32 = waiting: {
                self.waiting_frame.store(number, .release);
                defer self.waiting_frame.store(0, .release);
                while (true) {
                    if (self.stopping.load(.acquire)) {
                        c.azk_surface_frame_discard(native.?);
                        break :waiting null;
                    }
                    const result = c.azk_surface_frame_wait(native.?, 10);
                    if (result != -110 or Io.Clock.awake.now(self.io).toMilliseconds() >= deadline)
                        break :waiting result;
                }
            };
            const waited = wait_result orelse break;
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
pub fn presentReady(self: *Self, window: anytype) !usize {
    try self.drainNotification();
    return self.presentNotified(window);
}

/// Session.dispatch already consumed this queue's eventfd notification.
pub fn presentNotified(self: *Self, window: anytype) !usize {
    return self.presentConfiguration(window, true);
}

/// An obsolete compositor generation may release its completed buffers but may
/// never attach them, even when a later configure returned to the same size.
pub fn presentConfiguration(self: *Self, window: anytype, accepted: bool) !usize {
    if (self.failure.load(.acquire) != 0) return error.SurfaceQueueFailed;
    var frames: [2]*Frame = undefined;
    const count = try self.ready.get(self.io, &frames, 0);
    if (self.pace_frames) {
        for (frames[0..count]) |frame| {
            if (self.pending_frame) |previous| {
                returnFrame(previous, false);
                self.coalesced_frames += 1;
            }
            self.pending_frame = frame;
        }
        const selected = self.pending_frame orelse return 0;
        if (!accepted) {
            self.pending_frame = null;
            returnFrame(selected, false);
            return 0;
        }
        // The first image of a new configuration must make progress even if
        // an old callback was withheld while covered. Subsequent images use
        // this window's callback, never an all-task presentation barrier.
        if (window.frame_cb != null and self.presented_number != 0) {
            self.deferred_frames += 1;
            return 0;
        }
        self.pending_frame = null;
        window.requestFrame() catch |err| {
            returnFrame(selected, false);
            return err;
        };
        try self.presentFrame(window, selected);
        return 1;
    }
    for (frames[0..count], 0..) |frame, index| {
        errdefer for (frames[index + 1 .. count]) |pending| returnFrame(pending, true);
        if (!accepted) {
            returnFrame(frame, false);
            continue;
        }
        try self.presentFrame(window, frame);
    }
    return if (accepted) count else 0;
}

/// Consumes the acquired lease on every outcome; a frame callback paces only
/// submission. wl_buffer.release remains the independent storage-release gate.
fn presentFrame(self: *Self, window: anytype, frame: *Frame) !void {
    const info = frame.info;
    if (self.expected_size) |size| {
        if (info.width != size[0] or info.height != size[1]) {
            returnFrame(frame, false);
            return error.InvalidOutputBufferSize;
        }
    }
    std.log.info("surface-frame number={d} buffer={d} width={d} height={d}", .{ frame.number, info.id, info.width, info.height });
    try window.pushUiDmabuf(.{
        .frame_number = frame.number,
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
    self.presented_width = info.width;
    self.presented_height = info.height;
    self.presented_number = frame.number;
    self.submitted_frames += 1;
}
