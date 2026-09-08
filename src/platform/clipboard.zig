//! Seat-owned wl_data_device clipboard. Android owns ClipData and permissions;
//! this module owns offers, publication serials and asynchronous native streams.
const std = @import("std");
const wl = @import("wayland").client.wl;
const Transfers = @import("data_transfers.zig");
const Stream = @import("data_transfer.zig");
const Self = @This();

pub const MimeTypes = struct {
    const max_count = 64;
    const max_length = 128;
    values: [max_count][max_length + 1]u8 = undefined,
    lengths: [max_count]u8 = undefined,
    count: usize = 0,

    pub fn add(self: *MimeTypes, value: []const u8) !void {
        if (value.len == 0 or value.len > max_length or std.mem.indexOfScalar(u8, value, 0) != null)
            return error.InvalidOfferMimeType;
        if (self.contains(value)) return;
        if (self.count == max_count) return error.OfferMimeCapacityExceeded;
        @memcpy(self.values[self.count][0..value.len], value);
        self.values[self.count][value.len] = 0;
        self.lengths[self.count] = @intCast(value.len);
        self.count += 1;
    }
    pub fn get(self: *const MimeTypes, index: usize) [:0]const u8 {
        return self.values[index][0..self.lengths[index] :0];
    }
    pub fn contains(self: *const MimeTypes, value: []const u8) bool {
        for (0..self.count) |i| if (std.mem.eql(u8, self.get(i), value)) return true;
        return false;
    }
    pub fn text(self: *const MimeTypes) ?[:0]const u8 {
        return self.representation("text/plain", true);
    }
    pub fn html(self: *const MimeTypes) ?[:0]const u8 {
        return self.representation("text/html", false);
    }
    fn representation(self: *const MimeTypes, media_type: []const u8, legacy_utf8: bool) ?[:0]const u8 {
        var best: ?[:0]const u8 = null;
        var rank: u8 = 0;
        for (0..self.count) |i| {
            const value = self.get(i);
            var candidate: u8 = 0;
            if (legacy_utf8 and std.mem.eql(u8, value, "UTF8_STRING")) candidate = 2 else {
                var parts = std.mem.splitScalar(u8, value, ';');
                if (!std.ascii.eqlIgnoreCase(std.mem.trim(u8, parts.next().?, " \t"), media_type)) continue;
                candidate = 1;
                while (parts.next()) |parameter| {
                    const equals = std.mem.indexOfScalar(u8, parameter, '=') orelse continue;
                    if (!std.ascii.eqlIgnoreCase(std.mem.trim(u8, parameter[0..equals], " \t"), "charset")) continue;
                    const encoding = std.mem.trim(u8, parameter[equals + 1 ..], " \t\"");
                    if (!std.ascii.eqlIgnoreCase(encoding, "utf-8")) {
                        candidate = 0;
                        break;
                    }
                    candidate = 3;
                }
            }
            if (candidate > rank) {
                best = value;
                rank = candidate;
            }
        }
        return best;
    }
};

pub const Payload = struct {
    gpa: std.mem.Allocator,
    text: ?[]u8,
    html: ?[]u8 = null,

    pub fn init(gpa: std.mem.Allocator, text: ?[]const u8, html: ?[]const u8) !*Payload {
        if (text == null and html == null) return error.EmptyClipboardRepresentations;
        if (text) |value| try validate(value);
        if (html) |value| try validate(value);
        const self = try gpa.create(Payload);
        errdefer gpa.destroy(self);
        const plain = if (text) |value| try gpa.dupe(u8, value) else null;
        errdefer if (plain) |bytes| gpa.free(bytes);
        self.* = .{ .gpa = gpa, .text = plain, .html = if (html) |value| try gpa.dupe(u8, value) else null };
        return self;
    }
    pub fn destroy(self: *Payload) void {
        if (self.text) |text| self.gpa.free(text);
        if (self.html) |html| self.gpa.free(html);
        self.gpa.destroy(self);
    }
    fn validate(bytes: []const u8) !void {
        if (bytes.len > Transfers.max_bytes) return error.OfferTooLarge;
        if (!std.unicode.utf8ValidateSlice(bytes) or std.mem.indexOfScalar(u8, bytes, 0) != null)
            return error.InvalidClipboardText;
    }
};

pub const Hooks = struct {
    context: *anyopaque,
    /// Ownership transfers; null clears Android's clipboard.
    changed: *const fn (*anyopaque, i64, ?*Payload) void,
    issue: *const fn (*anyopaque, anyerror) void,
    failure: *const fn (*anyopaque, anyerror) void,
};
pub const Pending = struct { payload: ?*Payload };
const Offer = struct {
    owner: *Self,
    proxy: *wl.DataOffer,
    token: u64,
    mimes: MimeTypes = .{},
    waiting: u2 = 0,
    received: bool = false,
    text: ?[]u8 = null,
    html: ?[]u8 = null,
};
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
manager: *wl.DataDeviceManager, // Borrowed from Session.
device: *wl.DataDevice,
transfers: *Transfers,
hooks: Hooks,
offers: [16]?*Offer = @splat(null),
selection: ?*Offer = null,
drag: ?*Offer = null,
source: ?*Source = null,
pending: ?*Payload = null,
has_pending: bool = false,
serial: ?u32 = null,
focused: bool = false,
next_token: u64 = 4,
revision: i64 = 0,

pub fn init(gpa: std.mem.Allocator, io: std.Io, manager: *wl.DataDeviceManager, seat: *wl.Seat, hooks: Hooks) !*Self {
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    const transfers = try Transfers.init(gpa, io);
    errdefer transfers.deinit();
    const device = try manager.getDataDevice(seat);
    self.* = .{ .gpa = gpa, .io = io, .manager = manager, .device = device, .transfers = transfers, .hooks = hooks };
    device.setListener(*Self, deviceListener, self);
    return self;
}

pub fn deinit(self: *Self) void {
    for (self.offers) |offer| if (offer) |live| self.destroyOffer(live);
    if (self.source) |source| self.destroySource(source);
    if (self.pending) |pending| pending.destroy();
    self.transfers.deinit();
    if (self.device.getVersion() >= 2) self.device.release() else self.device.destroy();
    self.gpa.destroy(self);
}

fn token(self: *Self) !u64 {
    if (self.next_token > std.math.maxInt(u64) - 4) return error.ClipboardGenerationExhausted;
    const result = self.next_token;
    self.next_token += 4;
    return result;
}

/// Actual wl_keyboard.enter is a seat event with a compositor-issued serial.
/// Programmatic Android copy may defer until focus supplies such an event.
pub fn focus(self: *Self, gained: bool, serial: u32) void {
    self.focused = gained;
    self.serial = if (gained) serial else null;
    if (!gained) {
        if (self.selection) |offer| self.destroyOffer(offer);
    }
    self.publish() catch |err| self.hooks.issue(self.hooks.context, err);
    if (gained) if (self.selection) |offer| self.readSelection(offer) catch |err| self.hooks.issue(self.hooks.context, err);
}
pub fn inputSerial(self: *Self, serial: u32) void {
    if (!self.focused) return;
    self.serial = serial;
    self.publish() catch |err| self.hooks.issue(self.hooks.context, err);
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
    if (!self.has_pending) return null;
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

fn publish(self: *Self) !void {
    if (!self.has_pending or !self.focused) return;
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
        proxy.offer(source.mime());
        replacement = source;
    }
    self.device.setSelection(if (replacement) |source| source.proxy else null, serial);
    if (self.source) |source| self.destroySource(source);
    self.source = replacement;
    self.pending = null;
    self.has_pending = false;
}

fn destroySource(self: *Self, source: *Source) void {
    self.transfers.cancel(source.token + 2);
    source.proxy.destroy();
    source.payload.destroy();
    if (self.source == source) self.source = null;
    self.gpa.destroy(source);
}
fn destroyOffer(self: *Self, offer: *Offer) void {
    self.transfers.cancel(offer.token);
    self.transfers.cancel(offer.token + 1);
    if (offer.text) |bytes| self.gpa.free(bytes);
    if (offer.html) |bytes| self.gpa.free(bytes);
    offer.proxy.destroy();
    for (&self.offers) |*slot| if (slot.* == offer) {
        slot.* = null;
    };
    if (self.selection == offer) self.selection = null;
    if (self.drag == offer) self.drag = null;
    self.gpa.destroy(offer);
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
    const offer = try self.gpa.create(Offer);
    errdefer self.gpa.destroy(offer);
    offer.* = .{ .owner = self, .proxy = proxy, .token = try self.token() };
    proxy.setListener(*Offer, offerListener, offer);
    slot.* = offer;
}
fn receive(self: *Self, offer: *Offer, mime: [:0]const u8, html: bool) !void {
    const pipe = try Stream.Pipe.init();
    defer pipe.write.close(self.io);
    try self.transfers.receive(offer.token + @as(u64, if (html) 1 else 0), pipe.read);
    offer.proxy.receive(mime, pipe.write.handle);
    offer.waiting |= if (html) @as(u2, 2) else 1;
}
fn selected(self: *Self, proxy: ?*wl.DataOffer) !void {
    const replacement = if (proxy) |p| self.find(p) orelse return error.UnknownClipboardOffer else null;
    if (replacement != null and replacement == self.selection) return;
    if (self.selection) |old| self.destroyOffer(old);
    const offer = replacement orelse {
        if (self.focused and !self.has_pending) self.hooks.changed(self.hooks.context, self.revision, null);
        return;
    };
    self.selection = offer;
    try self.readSelection(offer);
}
fn readSelection(self: *Self, offer: *Offer) !void {
    if (!self.focused or self.has_pending or offer.received) return;
    offer.received = true;
    // Our marker avoids replacing stock ClipData (and its URI grants/metadata)
    // with a native roundtrip of our own source after focus returns.
    if (self.source) |source| if (offer.mimes.contains(source.mime())) return;
    // The new native owner supersedes the old contents immediately. While an
    // asynchronous offer is being read (or if its peer fails), Android must not
    // paste the previous owner's data.
    self.hooks.changed(self.hooks.context, self.revision, null);
    const plain = offer.mimes.text();
    const html = offer.mimes.html();
    if (plain == null and html == null) {
        return error.UnsupportedClipboardMimeTypes;
    }
    errdefer self.destroyOffer(offer);
    if (plain) |mime| try self.receive(offer, mime, false);
    if (html) |mime| try self.receive(offer, mime, true);
}

/// Called by the owner after the worker eventfd wakes. All offer/proxy state
/// and callbacks remain on the Wayland thread.
pub fn drain(self: *Self) void {
    while (self.transfers.take()) |completion| {
        const offer = self.selection;
        if (offer == null or (completion.token != offer.?.token and completion.token != offer.?.token + 1)) {
            if (completion.result) |bytes| self.gpa.free(bytes) else |err| self.hooks.issue(self.hooks.context, err);
            continue;
        }
        const current = offer.?;
        const bytes = completion.result catch |err| {
            self.destroyOffer(current);
            self.hooks.issue(self.hooks.context, err);
            continue;
        };
        Payload.validate(bytes) catch |err| {
            self.gpa.free(bytes);
            self.destroyOffer(current);
            self.hooks.issue(self.hooks.context, err);
            continue;
        };
        if (completion.token == current.token) {
            current.text = bytes;
            current.waiting &= ~@as(u2, 1);
        } else {
            current.html = bytes;
            current.waiting &= ~@as(u2, 2);
        }
        if (current.waiting == 0) {
            const payload = self.gpa.create(Payload) catch |err| {
                self.destroyOffer(current);
                self.hooks.issue(self.hooks.context, err);
                continue;
            };
            payload.* = .{ .gpa = self.gpa, .text = current.text, .html = current.html };
            current.text = null;
            current.html = null;
            self.hooks.changed(self.hooks.context, self.revision, payload);
        }
    }
}

fn offerListener(_: *wl.DataOffer, event: wl.DataOffer.Event, offer: *Offer) void {
    switch (event) {
        .offer => |mime| offer.mimes.add(std.mem.span(mime.mime_type)) catch |err| offer.owner.hooks.failure(offer.owner.hooks.context, err),
        .source_actions, .action => {},
    }
}
fn sourceListener(_: *wl.DataSource, event: wl.DataSource.Event, source: *Source) void {
    const self = source.owner;
    switch (event) {
        .send => |request| {
            const file: std.Io.File = .{ .handle = request.fd, .flags = .{ .nonblocking = false } };
            const mime = std.mem.span(request.mime_type);
            const bytes: ?[]const u8 = if (std.mem.eql(u8, mime, "text/html")) source.payload.html else if (std.mem.eql(u8, mime, "text/plain;charset=utf-8") or std.mem.eql(u8, mime, "text/plain") or std.mem.eql(u8, mime, "UTF8_STRING")) source.payload.text else if (std.mem.eql(u8, mime, source.mime())) "" else null;
            if (bytes) |value| self.transfers.send(source.token + 2, file, value) catch |err| self.hooks.issue(self.hooks.context, err) else {
                file.close(self.io);
                self.hooks.issue(self.hooks.context, error.UnadvertisedClipboardMimeType);
            }
        },
        .cancelled => self.destroySource(source),
        .target, .action, .dnd_drop_performed, .dnd_finished => {},
    }
}
fn deviceListener(_: *wl.DataDevice, event: wl.DataDevice.Event, self: *Self) void {
    switch (event) {
        .data_offer => |offer| self.newOffer(offer.id) catch |err| self.hooks.failure(self.hooks.context, err),
        .selection => |selection| self.selected(selection.id) catch |err| self.hooks.issue(self.hooks.context, err),
        .enter => |enter| {
            if (self.drag) |old| self.destroyOffer(old);
            if (enter.id) |id| {
                self.drag = self.find(id);
                id.accept(enter.serial, null);
                self.hooks.issue(self.hooks.context, error.NativeDragBridgeUnavailable);
            }
        },
        .leave => if (self.drag) |offer| {
            self.destroyOffer(offer);
        },
        .motion, .drop => {},
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
