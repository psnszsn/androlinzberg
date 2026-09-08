//! Seat-owned wl_data_device clipboard. Android owns ClipData and permissions;
//! this module owns offers, publication serials and asynchronous native streams.
const std = @import("std");
const wl = @import("wayland").client.wl;
const Transfers = @import("data_transfers.zig");
const Self = @This();

const DataOffer = @import("data_offer.zig");
pub const MimeTypes = DataOffer.MimeTypes;
pub const Payload = DataOffer.Payload;
pub const DragOffer = @import("drag_offer.zig");
pub const DragSource = @import("drag_source.zig");

pub const Hooks = struct {
    context: *anyopaque,
    /// Ownership transfers; null clears Android's clipboard.
    changed: *const fn (*anyopaque, i64, ?*Payload) void,
    issue: *const fn (*anyopaque, anyerror) void,
    failure: *const fn (*anyopaque, anyerror) void,
    drag: ?DragOffer.Hooks = null,
};
pub const Pending = struct { payload: ?*Payload };
const Offer = DataOffer;
const Source = struct {
    owner: *Self,
    proxy: *wl.DataSource,
    token: u64,
    marker: [80:0]u8 = @splat(0),
    marker_length: usize = 0,
    payload: *Payload,

    fn mime(self: *const Source) [:0]const u8 {
        return self.marker[0..self.marker_length :0];
    }
};

gpa: std.mem.Allocator,
io: std.Io,
display: *wl.Display, // Borrowed from Session; publications share its event order.
manager: *wl.DataDeviceManager, // Borrowed from Session.
device: *wl.DataDevice,
transfers: *Transfers,
hooks: Hooks,
offers: [16]?*Offer = @splat(null),
selection: ?*Offer = null,
drag: ?*Offer = null,
native_drag: ?DragOffer = null,
outbound_drag: ?*DragSource = null,
source: ?*Source = null,
publication_barrier: ?*wl.Callback = null,
retired_marker: [80:0]u8 = @splat(0),
retired_marker_length: usize = 0,
pending: ?*Payload = null,
has_pending: bool = false,
clear_echo: bool = false,
publication_confirmed: bool = true,
publication_serial: ?u32 = null,
serial: ?u32 = null,
focused: bool = false,
next_token: *u64,
revision: i64 = 0,

pub fn init(gpa: std.mem.Allocator, io: std.Io, display: *wl.Display, manager: *wl.DataDeviceManager, seat: *wl.Seat, next_token: *u64, hooks: Hooks) !*Self {
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    const transfers = try Transfers.init(gpa, io);
    errdefer transfers.deinit();
    const device = try manager.getDataDevice(seat);
    self.* = .{ .gpa = gpa, .io = io, .display = display, .manager = manager, .device = device, .transfers = transfers, .hooks = hooks, .next_token = next_token };
    if (hooks.drag) |drag| self.native_drag = .{ .io = io, .hooks = drag };
    device.setListener(*Self, deviceListener, self);
    return self;
}

pub fn deinit(self: *Self) void {
    if (self.publication_barrier) |callback| callback.destroy();
    if (self.outbound_drag) |drag| drag.deinit();
    if (self.native_drag) |*drag| drag.cancel();
    for (self.offers) |offer| if (offer) |live| self.destroyOffer(live);
    if (self.source) |source| self.destroySource(source);
    if (self.pending) |pending| pending.destroy();
    self.transfers.deinit();
    if (self.device.getVersion() >= 2) self.device.release() else self.device.destroy();
    self.gpa.destroy(self);
}

fn token(self: *Self) !u64 {
    if (self.next_token.* > std.math.maxInt(i64) - 8) return error.ClipboardGenerationExhausted;
    const result = self.next_token.*;
    self.next_token.* += 8;
    return result;
}

/// Consumes payload on every outcome. Clipboard owns this source through its
/// terminal event and frees it at the next drag or seat/manager teardown. The
/// caller supplies a validated held gesture, never a generic clipboard serial.
pub fn startDrag(self: *Self, origin: *wl.Surface, serial: u32, icon: ?*wl.Surface, payload: *Payload, hooks: DragSource.Hooks) !*DragSource {
    if (self.outbound_drag) |previous| {
        if (previous.active()) {
            payload.destroy();
            return error.NativeDragAlreadyActive;
        }
        previous.deinit();
        self.outbound_drag = null;
    }
    const source_token = self.token() catch |err| {
        payload.destroy();
        return err;
    };
    const source = try DragSource.init(self.gpa, self.io, self.display, self.manager, self.device, self.transfers, source_token, origin, serial, icon, payload, hooks);
    self.outbound_drag = source;
    return source;
}

pub fn dragTimeout(self: *Self) i32 {
    var timeout: i32 = -1;
    if (self.native_drag) |*drag| timeout = drag.tick();
    if (self.outbound_drag) |drag| {
        const outgoing = drag.tick();
        if (outgoing >= 0) timeout = if (timeout < 0) outgoing else @min(timeout, outgoing);
    }
    return timeout;
}
pub fn removeDragSurface(self: *Self, surface: *wl.Surface) void {
    if (self.native_drag) |*drag| drag.removed(surface);
    if (self.outbound_drag) |drag| if (drag.origin == surface) drag.cancel();
}

/// Actual wl_keyboard.enter is a seat event with a compositor-issued serial.
/// Programmatic Android copy may defer until focus supplies such an event.
pub fn focus(self: *Self, gained: bool, serial: u32) void {
    self.focused = gained;
    self.serial = if (gained) serial else null;
    if (!gained) {
        if (self.selection) |offer| self.destroyOffer(offer);
    }
    self.retryPublication() catch |err| self.hooks.issue(self.hooks.context, err);
    if (gained) if (self.selection) |offer| self.readSelection(offer) catch |err| self.hooks.issue(self.hooks.context, err);
}
pub fn inputSerial(self: *Self, serial: u32) void {
    if (!self.focused) return;
    self.serial = serial;
    self.retryPublication() catch |err| self.hooks.issue(self.hooks.context, err);
}

/// Consumes payload, even if publication fails. Pending requests coalesce;
/// an existing source continues serving until its replacement can be published.
pub fn set(self: *Self, revision: i64, payload: ?*Payload) !void {
    if (revision <= self.revision) {
        if (payload) |owned| owned.destroy();
        return error.StaleClipboardRevision;
    }
    self.revision = revision;
    if (self.pending) |pending| pending.destroy();
    self.pending = payload;
    self.has_pending = true;
    if (self.selection) |offer| self.destroyOffer(offer);
    try self.publish();
}

/// Transfer an unpublished copy/clear across manager or seat replacement.
pub fn takePending(self: *Self) ?Pending {
    if (!self.has_pending) {
        if (self.publication_confirmed) return null;
        self.publication_confirmed = true;
        return .{ .payload = if (self.source) |source| self.removeSource(source) else null };
    }
    const pending: Pending = .{ .payload = self.pending };
    self.pending = null;
    self.has_pending = false;
    return pending;
}
pub fn restorePending(self: *Self, pending: Pending) void {
    std.debug.assert(!self.has_pending);
    self.pending = pending.payload;
    self.has_pending = true;
}

/// set_selection has no rejection event: a compositor can ignore an old seat
/// serial after another client copied. Keep an unconfirmed publication and
/// retry with a NEW data source when real input supplies another serial.
fn retryPublication(self: *Self) !void {
    if (!self.has_pending and !self.publication_confirmed and self.focused and
        self.serial != null and self.serial != self.publication_serial)
    {
        self.pending = if (self.source) |source|
            try Payload.initAll(self.gpa, source.payload.text, source.payload.html, source.payload.uris)
        else
            null;
        self.has_pending = true;
    }
    try self.publish();
}

fn publish(self: *Self) !void {
    if (!self.has_pending or !self.focused or self.publication_barrier != null) return;
    const serial = self.serial orelse return;
    var replacement: ?*Source = null;
    if (self.pending) |payload| {
        const source = try self.gpa.create(Source);
        errdefer self.gpa.destroy(source);
        const proxy = try self.manager.createDataSource();
        errdefer proxy.destroy();
        source.* = .{ .owner = self, .proxy = proxy, .token = try self.token(), .payload = payload };
        var nonce: [16]u8 = undefined;
        self.io.random(&nonce);
        const marker = try std.fmt.bufPrint(&source.marker, "application/x-artzig-clipboard-{s}", .{std.fmt.bytesToHex(nonce, .lower)});
        source.marker_length = marker.len;
        source.marker[marker.len] = 0;
        proxy.setListener(*Source, sourceListener, source);
        if (payload.text != null) {
            proxy.offer("text/plain;charset=utf-8");
            proxy.offer("UTF8_STRING");
            proxy.offer("text/plain");
        }
        if (payload.html != null) proxy.offer("text/html");
        if (payload.uris != null) proxy.offer("text/uri-list");
        proxy.offer(source.mime());
        replacement = source;
    }
    // An empty selection has no MIME marker. Consume its own null echo so
    // an asynchronous Android file export is not cancelled by its preparatory
    // clear. A subsequent non-null native owner always retires this echo state.
    self.clear_echo = replacement == null;
    self.publication_confirmed = false;
    self.publication_serial = serial;
    self.device.setSelection(if (replacement) |source| source.proxy else null, serial);
    if (self.source) |source| {
        // A focus echo can already be in flight when this replacement is sent.
        // Retain exactly the preceding marker until the replacement's barrier;
        // recognizing all historical markers would swallow clipboard-manager
        // restores of an earlier Android copy after foreign ownership.
        self.retired_marker = source.marker;
        self.retired_marker_length = source.marker_length;
        self.destroySource(source);
    }
    self.source = replacement;
    self.pending = null;
    self.has_pending = false;
    const callback = self.display.sync() catch |err| {
        // set_selection has already been sent. Continuing without its ordering
        // barrier could misclassify an old echo as a new native copy.
        self.hooks.failure(self.hooks.context, err);
        return err;
    };
    self.publication_barrier = callback;
    callback.setListener(*Self, publicationDone, self);
}

fn publicationDone(callback: *wl.Callback, _: wl.Callback.Event, self: *Self) void {
    std.debug.assert(self.publication_barrier == callback);
    callback.destroy();
    self.publication_barrier = null;
    self.retired_marker_length = 0;
    // A roundtrip orders server processing; it does not confirm acceptance of
    // the seat serial. Only our selection echo confirms that. Coalesce all
    // intervening copies/clears into the one latest pending publication.
    self.publish() catch |err| self.hooks.issue(self.hooks.context, err);
}

fn removeSource(self: *Self, source: *Source) *Payload {
    self.transfers.cancel(source.token + 4);
    source.proxy.destroy();
    const payload = source.payload;
    if (self.source == source) self.source = null;
    self.gpa.destroy(source);
    return payload;
}
fn destroySource(self: *Self, source: *Source) void {
    self.removeSource(source).destroy();
}
fn destroyOffer(self: *Self, offer: *Offer) void {
    for (&self.offers) |*slot| if (slot.* == offer) {
        slot.* = null;
    };
    if (self.selection == offer) self.selection = null;
    if (self.drag == offer) self.drag = null;
    offer.destroy();
}
fn find(self: *Self, proxy: *wl.DataOffer) ?*Offer {
    for (self.offers) |slot| if (slot) |offer| if (offer.proxy == proxy) return offer;
    return null;
}

fn newOffer(self: *Self, proxy: *wl.DataOffer) !void {
    errdefer proxy.destroy();
    const slot = for (&self.offers) |*slot| {
        if (slot.* == null) break slot;
    } else return error.ClipboardOfferCapacityExceeded;
    const offer = try DataOffer.init(self.gpa, self.io, self.transfers, proxy, try self.token(), self.hooks.context, self.hooks.failure);
    slot.* = offer;
}
fn selected(self: *Self, proxy: ?*wl.DataOffer) !void {
    const replacement = if (proxy) |p| self.find(p) orelse return error.UnknownClipboardOffer else null;
    if (replacement != null and replacement == self.selection) return;
    if (self.selection) |old| self.destroyOffer(old);
    const offer = replacement orelse {
        if (self.clear_echo) {
            self.clear_echo = false;
            self.publication_confirmed = true;
            return;
        }
        self.publication_confirmed = true;
        if (self.focused and !self.has_pending) self.hooks.changed(self.hooks.context, self.revision, null);
        return;
    };
    self.clear_echo = false;
    self.selection = offer;
    try self.readSelection(offer);
}
fn readSelection(self: *Self, offer: *Offer) !void {
    if (!self.focused or self.has_pending or offer.received) return;
    offer.received = true;
    // Our marker avoids replacing stock ClipData (and its URI grants/metadata)
    // with a native roundtrip of our own source after focus returns.
    if (self.source) |source| if (offer.mimes.contains(source.mime())) {
        self.publication_confirmed = true;
        return;
    };
    if (self.retired_marker_length != 0 and
        offer.mimes.contains(self.retired_marker[0..self.retired_marker_length])) return;
    // A delivered foreign selection supersedes an unconfirmed older copy.
    self.publication_confirmed = true;
    // The new native owner supersedes the old contents immediately. While an
    // asynchronous offer is being read (or if its peer fails), Android must not
    // paste the previous owner's data.
    self.hooks.changed(self.hooks.context, self.revision, null);
    const plain = offer.mimes.text();
    const html = offer.mimes.html();
    const uris = offer.mimes.uris();
    if (plain == null and html == null and uris == null) {
        return error.UnsupportedClipboardMimeTypes;
    }
    errdefer self.destroyOffer(offer);
    if (plain) |mime| try offer.receive(mime, 0);
    if (html) |mime| try offer.receive(mime, 1);
    if (uris) |mime| try offer.receive(mime, 2);
}

/// Called by the owner after the worker eventfd wakes. All offer/proxy state
/// and callbacks remain on the Wayland thread.
pub fn drain(self: *Self) void {
    while (self.transfers.take()) |completion| {
        if (self.outbound_drag) |drag| if (drag.complete(completion)) continue;
        if (self.native_drag) |*drag| if (drag.complete(completion)) continue;
        const offer = self.selection;
        if (offer == null or (completion.token < offer.?.token or completion.token > offer.?.token + 2)) {
            if (completion.result) |bytes| self.gpa.free(bytes) else |err| self.hooks.issue(self.hooks.context, err);
            continue;
        }
        const current = offer.?;
        const payload = current.complete(completion) catch |err| {
            self.destroyOffer(current);
            self.hooks.issue(self.hooks.context, err);
            continue;
        };
        if (payload) |owned| self.hooks.changed(self.hooks.context, self.revision, owned);
    }
}

fn sourceListener(_: *wl.DataSource, event: wl.DataSource.Event, source: *Source) void {
    const self = source.owner;
    switch (event) {
        .send => |request| {
            const file: std.Io.File = .{ .handle = request.fd, .flags = .{ .nonblocking = false } };
            const mime = std.mem.span(request.mime_type);
            const bytes: ?[]const u8 = if (std.mem.eql(u8, mime, "text/uri-list")) source.payload.uris else if (std.mem.eql(u8, mime, "text/html")) source.payload.html else if (std.mem.eql(u8, mime, "text/plain;charset=utf-8") or std.mem.eql(u8, mime, "text/plain") or std.mem.eql(u8, mime, "UTF8_STRING")) source.payload.text else if (std.mem.eql(u8, mime, source.mime())) "" else null;
            if (bytes) |value| self.transfers.send(source.token + 4, file, value) catch |err| self.hooks.issue(self.hooks.context, err) else {
                file.close(self.io);
                self.hooks.issue(self.hooks.context, error.UnadvertisedClipboardMimeType);
            }
        },
        .cancelled => {
            self.publication_confirmed = true; // Explicit ownership loss retires retries.
            self.retired_marker_length = 0;
            self.clear_echo = false;
            self.destroySource(source);
        },
        .target, .action, .dnd_drop_performed, .dnd_finished => {},
    }
}
fn deviceListener(_: *wl.DataDevice, event: wl.DataDevice.Event, self: *Self) void {
    switch (event) {
        .data_offer => |offer| self.newOffer(offer.id) catch |err| self.hooks.failure(self.hooks.context, err),
        .selection => |selection| self.selected(selection.id) catch |err| self.hooks.issue(self.hooks.context, err),
        .enter => |enter| {
            if (self.outbound_drag) |source| if (enter.id) |id| if (self.find(id)) |offer| {
                if (offer.mimes.contains(source.mime())) source.protocolAccepted();
            };
            if (self.native_drag) |*drag| {
                const surface = enter.surface orelse {
                    drag.leave();
                    if (enter.id) |id| if (self.find(id)) |offer| self.destroyOffer(offer);
                    return;
                };
                if (enter.id) |id| {
                    const offer = self.find(id) orelse {
                        self.hooks.failure(self.hooks.context, error.UnknownDragOffer);
                        return;
                    };
                    // Transfer ownership out of the registry before callbacks
                    // can retire the drag. Clipboard ownership remains separate.
                    for (&self.offers) |*slot| if (slot.* == offer) {
                        slot.* = null;
                    };
                    drag.enter(offer, enter.serial, surface, enter.x.toDouble(), enter.y.toDouble()) catch |err|
                        drag.hooks.issue(drag.hooks.context, err);
                } else drag.leave();
            } else {
                if (self.drag) |old| self.destroyOffer(old);
                if (enter.id) |id| {
                    self.drag = self.find(id);
                    id.accept(enter.serial, null);
                    self.hooks.issue(self.hooks.context, error.NativeDragBridgeUnavailable);
                }
            }
        },
        .leave => {
            if (self.native_drag) |*drag| drag.leave();
            if (self.drag) |offer| self.destroyOffer(offer);
        },
        .motion => |motion| if (self.native_drag) |*drag| {
            drag.motion(motion.x.toDouble(), motion.y.toDouble());
        },
        .drop => if (self.native_drag) |*drag| {
            drag.drop();
        },
    }
}

test "clipboard MIME negotiation is bounded, deduplicated and prefers explicit UTF-8" {
    var mimes: MimeTypes = .{};
    try mimes.add("text/plain");
    try mimes.add("text/html");
    try mimes.add("UTF8_STRING");
    try mimes.add("text/plain;charset=utf-8");
    try mimes.add("text/plain");
    try std.testing.expectEqual(@as(usize, 4), mimes.count);
    try std.testing.expectEqualStrings("text/plain;charset=utf-8", mimes.text().?);
    const long: [129]u8 = @splat('x');
    try std.testing.expectError(error.InvalidOfferMimeType, mimes.add(&long));
    for (4..64) |i| {
        var buffer: [40]u8 = undefined;
        try mimes.add(try std.fmt.bufPrint(&buffer, "application/x-test-{d}", .{i}));
    }
    try std.testing.expectError(error.OfferMimeCapacityExceeded, mimes.add("text/overflow"));
}

test "clipboard payload keeps plain text and HTML distinct and rejects invalid text" {
    const payload = try Payload.init(std.testing.allocator, "😀 café", "<b>😀 café</b>");
    defer payload.destroy();
    try std.testing.expectEqualStrings("😀 café", payload.text.?);
    try std.testing.expectEqualStrings("<b>😀 café</b>", payload.html.?);
    try std.testing.expectError(error.InvalidClipboardText, Payload.init(std.testing.allocator, "bad\x00text", null));
    try std.testing.expectError(error.InvalidClipboardText, Payload.init(std.testing.allocator, "\xff", null));
}

test "MIME charset negotiation preserves the offered spelling and rejects other encodings" {
    var mimes: MimeTypes = .{};
    try mimes.add("text/plain; charset=ISO-8859-1");
    try std.testing.expect(mimes.text() == null);
    try mimes.add("Text/Plain; charset=\"UTF-8\"");
    try std.testing.expectEqualStrings("Text/Plain; charset=\"UTF-8\"", mimes.text().?);
    try mimes.add("TEXT/HTML; CHARSET=UTF-8");
    try std.testing.expectEqualStrings("TEXT/HTML; CHARSET=UTF-8", mimes.html().?);
}
