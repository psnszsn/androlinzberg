//! A native drag offer survives pointer leave after drop. Android acknowledges
//! the actual recipient result; reading bytes alone never completes the drag.
//! The seat thread owns this state, and callbacks enqueue owned events rather
//! than reentering it. Clipboard and drag reads share one bounded transfer pool.
const std = @import("std");
const wl = @import("wayland").client.wl;
const Offer = @import("data_offer.zig");
const Transfers = @import("data_transfers.zig");
const Self = @This();

pub const Event = union(enum) {
    entered: struct { token: u64, surface: *wl.Surface, x: f64, y: f64, representations: u3 },
    motion: struct { token: u64, x: f64, y: f64 },
    payload: struct { token: u64, value: *Offer.Payload },
    dropped: u64,
    ended: struct { token: u64, consumed: bool },
};
pub const Hooks = struct {
    context: *anyopaque,
    event: *const fn (*anyopaque, Event) void,
    issue: *const fn (*anyopaque, anyerror) void,
};
const Active = struct {
    offer: *Offer,
    surface: *wl.Surface,
    serial: u32,
    mime: [:0]const u8,
    accepted: bool = false,
    received: bool = false,
    dropped: bool = false,
    deadline_ms: ?i64 = null,
};
io: std.Io,
hooks: Hooks,
active: ?Active = null,

/// Consumes the offer on every return. Only one drag may await an Android
/// recipient at a time, matching stock DragDropController's admission policy.
pub fn enter(self: *Self, offer: *Offer, serial: u32, surface: *wl.Surface, x: f64, y: f64) !void {
    if (self.active) |active| {
        if (active.dropped) {
            offer.destroy();
            return error.DragRecipientStillPending;
        }
        self.cancel();
    }
    const plain = offer.mimes.text();
    const html = offer.mimes.html();
    const uris = offer.mimes.uris();
    const mime = uris orelse html orelse plain orelse {
        offer.destroy();
        return error.UnsupportedDragMimeTypes;
    };
    if (!std.math.isFinite(x) or !std.math.isFinite(y)) {
        offer.destroy();
        return error.InvalidDragPosition;
    }
    self.active = .{ .offer = offer, .surface = surface, .serial = serial, .mime = mime };
    errdefer self.cancel();
    // Preview reads are legal before drop. Acceptance remains false until an
    // Android view actually becomes the recipient; URI staging can run first.
    offer.proxy.accept(serial, null);
    if (offer.proxy.getVersion() >= 3) offer.proxy.setActions(.{ .copy = true }, .{ .copy = true });
    const representations: u3 = @as(u3, @intFromBool(plain != null)) |
        (@as(u3, @intFromBool(html != null)) << 1) | (@as(u3, @intFromBool(uris != null)) << 2);
    self.emit(.{ .entered = .{ .token = offer.token, .surface = surface, .x = x, .y = y, .representations = representations } });
    if (plain) |value| try offer.receive(value, 0);
    if (html) |value| try offer.receive(value, 1);
    if (uris) |value| try offer.receive(value, 2);
}

pub fn motion(self: *Self, x: f64, y: f64) void {
    const active = self.active orelse return;
    if (active.dropped) return;
    if (!std.math.isFinite(x) or !std.math.isFinite(y)) {
        self.fail(error.InvalidDragPosition);
        return;
    }
    self.emit(.{ .motion = .{ .token = active.offer.token, .x = x, .y = y } });
}
pub fn leave(self: *Self) void {
    const active = self.active orelse return;
    if (!active.dropped) self.cancel();
}
pub fn drop(self: *Self) void {
    if (self.active) |*active| {
        if (active.dropped) return;
        active.dropped = true;
        active.deadline_ms = std.Io.Clock.awake.now(self.io).toMilliseconds() + 10_000;
        self.emit(.{ .dropped = active.offer.token });
    }
}
pub fn accept(self: *Self, token: u64, accepted: bool) bool {
    const active = if (self.active) |*value| value else return false;
    if (active.offer.token != token or active.dropped) return false;
    active.accepted = accepted;
    active.offer.proxy.accept(active.serial, if (accepted) active.mime else null);
    return true;
}
pub fn finish(self: *Self, token: u64, consumed: bool) bool {
    const active = self.active orelse return false;
    if (active.offer.token != token) return false;
    if (consumed and (!active.dropped or !active.received or !active.accepted or
        (active.offer.proxy.getVersion() >= 3 and !active.offer.action.copy))) return false;
    self.active = null;
    if (consumed and active.offer.proxy.getVersion() >= 3) active.offer.proxy.finish();
    active.offer.destroy();
    self.emit(.{ .ended = .{ .token = token, .consumed = consumed } });
    return true;
}
pub fn cancel(self: *Self) void {
    const active = self.active orelse return;
    _ = self.finish(active.offer.token, false);
}
pub fn removed(self: *Self, surface: *wl.Surface) void {
    if (self.active) |active| if (active.surface == surface) self.cancel();
}
pub fn tick(self: *Self) i32 {
    const active = self.active orelse return -1;
    const deadline = active.deadline_ms orelse return -1;
    const remaining = deadline - std.Io.Clock.awake.now(self.io).toMilliseconds();
    if (remaining <= 0) {
        self.fail(error.DragRecipientTimedOut);
        return -1;
    }
    return @intCast(@min(remaining, std.math.maxInt(i32)));
}

/// Returns true only when this module consumes the completion and its bytes.
pub fn complete(self: *Self, completion: Transfers.Completion) bool {
    const active = if (self.active) |*value| value else return false;
    if (completion.token < active.offer.token or completion.token > active.offer.token + 2) return false;
    const payload = active.offer.complete(completion) catch |err| {
        self.fail(err);
        return true;
    };
    if (payload) |value| {
        active.received = true;
        self.emit(.{ .payload = .{ .token = active.offer.token, .value = value } });
    }
    return true;
}
fn fail(self: *Self, reason: anyerror) void {
    self.cancel();
    self.hooks.issue(self.hooks.context, reason);
}
fn emit(self: *Self, event: Event) void {
    self.hooks.event(self.hooks.context, event);
}
