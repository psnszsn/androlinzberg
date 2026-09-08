//! One seat-owned outbound COPY source. Wayland's terminal source event owns
//! completion; preview reads and drop_performed alone do not mean consumption.
//! Hooks enqueue owner events and must not destroy this object reentrantly.
const std = @import("std");
const Io = std.Io;
const wl = @import("wayland").client.wl;
const Payload = @import("data_offer.zig").Payload;
const Transfers = @import("data_transfers.zig");
const Self = @This();

pub const Event = union(enum) {
    accepted: bool,
    copy: bool,
    dropped,
    ended: bool,
};
pub const Hooks = struct {
    context: *anyopaque,
    event: *const fn (*anyopaque, u64, Event) void,
    issue: *const fn (*anyopaque, anyerror) void,
};
gpa: std.mem.Allocator,
io: Io,
transfers: *Transfers,
proxy: ?*wl.DataSource,
payload: *Payload,
origin: *wl.Surface,
input_kind: ?@import("drag_grab.zig").Kind = null,
token: u64,
hooks: Hooks,
marker: [80:0]u8 = @splat(0),
marker_length: usize = 0,
dropped: bool = false,
sends: u32 = 0,
deadline: ?i64 = null,

/// Consumes payload on every return. The caller must supply the actual held
/// gesture's origin and serial, after its Android/WMS authorization succeeds.
pub fn init(gpa: std.mem.Allocator, io: Io, manager: *wl.DataDeviceManager, device: *wl.DataDevice, transfers: *Transfers, token: u64, origin: *wl.Surface, serial: u32, icon: ?*wl.Surface, payload: *Payload, hooks: Hooks) !*Self {
    errdefer payload.destroy();
    if (manager.getVersion() < 3 or device.getVersion() < 3) return error.DragResultProtocolUnavailable;
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    const proxy = try manager.createDataSource();
    errdefer proxy.destroy();
    self.* = .{ .gpa = gpa, .io = io, .transfers = transfers, .proxy = proxy, .payload = payload, .origin = origin, .token = token, .hooks = hooks };
    var nonce: [16]u8 = undefined;
    io.random(&nonce);
    const marker = try std.fmt.bufPrint(&self.marker, "application/x-artzig-drag-{s}", .{std.fmt.bytesToHex(nonce, .lower)});
    self.marker_length = marker.len;
    self.marker[marker.len] = 0;
    proxy.setListener(*Self, listener, self);
    if (payload.text != null) {
        proxy.offer("text/plain;charset=utf-8");
        proxy.offer("UTF8_STRING");
        proxy.offer("text/plain");
    }
    if (payload.html != null) proxy.offer("text/html");
    if (payload.uris != null) proxy.offer("text/uri-list");
    proxy.offer(self.mime());
    // Android drag flags provide no source-side deletion contract for MOVE.
    proxy.setActions(.{ .copy = true });
    device.startDrag(proxy, origin, icon, serial);
    return self;
}
pub fn deinit(self: *Self) void {
    self.finish(false);
    self.payload.destroy();
    self.gpa.destroy(self);
}
pub fn mime(self: *const Self) [:0]const u8 {
    return self.marker[0..self.marker_length :0];
}
pub fn active(self: *const Self) bool {
    return self.proxy != null;
}
pub fn cancel(self: *Self) void {
    self.finish(false);
}
pub fn complete(self: *Self, completion: Transfers.Completion) bool {
    if (completion.token != self.token + 4) return false;
    if (completion.result) |value| self.gpa.free(value) else |err| {
        self.finish(false);
        self.hooks.issue(self.hooks.context, err);
    }
    return true;
}
pub fn tick(self: *Self) i32 {
    const deadline = self.deadline orelse return -1;
    const remaining = deadline - Io.Clock.awake.now(self.io).toMilliseconds();
    if (remaining <= 0) {
        self.finish(false);
        self.hooks.issue(self.hooks.context, error.DragDestinationTimedOut);
        return -1;
    }
    return @intCast(@min(remaining, std.math.maxInt(i32)));
}
fn finish(self: *Self, consumed: bool) void {
    const proxy = self.proxy orelse return;
    self.proxy = null;
    self.deadline = null;
    proxy.destroy();
    self.transfers.cancel(self.token + 4);
    self.hooks.event(self.hooks.context, self.token, .{ .ended = consumed });
}
fn bytes(self: *const Self, mime_type: []const u8) ?[]const u8 {
    if (std.mem.eql(u8, mime_type, self.mime())) return "";
    if (std.mem.eql(u8, mime_type, "text/plain;charset=utf-8") or
        std.mem.eql(u8, mime_type, "text/plain") or std.mem.eql(u8, mime_type, "UTF8_STRING")) return self.payload.text;
    if (std.mem.eql(u8, mime_type, "text/html")) return self.payload.html;
    if (std.mem.eql(u8, mime_type, "text/uri-list")) return self.payload.uris;
    return null;
}
fn listener(_: *wl.DataSource, event: wl.DataSource.Event, self: *Self) void {
    switch (event) {
        .send => |request| {
            const file: Io.File = .{ .handle = request.fd, .flags = .{ .nonblocking = false } };
            const value = self.bytes(std.mem.span(request.mime_type)) orelse {
                file.close(self.io);
                self.hooks.issue(self.hooks.context, error.UnadvertisedDragMimeType);
                return;
            };
            self.sends +|= 1;
            self.transfers.send(self.token + 4, file, value) catch |err| {
                self.finish(false);
                self.hooks.issue(self.hooks.context, err);
            };
        },
        .target => |target| self.hooks.event(self.hooks.context, self.token, .{ .accepted = if (target.mime_type) |mime_type| self.bytes(std.mem.span(mime_type)) != null else false }),
        .action => |action| self.hooks.event(self.hooks.context, self.token, .{ .copy = action.dnd_action.copy }),
        .dnd_drop_performed => {
            self.dropped = true;
            self.deadline = Io.Clock.awake.now(self.io).toMilliseconds() + 10_000;
            self.hooks.event(self.hooks.context, self.token, .dropped);
        },
        .dnd_finished => self.finish(true),
        .cancelled => self.finish(false),
    }
}
