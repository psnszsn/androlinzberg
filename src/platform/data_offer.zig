//! Native data-offer ownership shared by clipboard and drag-and-drop. The seat
//! owns the bounded offer registry; each offer owns its proxy, receive streams
//! and partial representations until a complete immutable payload is delivered.
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
        return self.matching(value) != null;
    }
    /// Borrow the offer's own storage, never a source marker that can retire.
    pub fn matching(self: *const MimeTypes, value: []const u8) ?[:0]const u8 {
        for (0..self.count) |i| if (std.mem.eql(u8, self.get(i), value)) return self.get(i);
        return null;
    }
    pub fn text(self: *const MimeTypes) ?[:0]const u8 {
        return self.representation("text/plain", true);
    }
    pub fn uris(self: *const MimeTypes) ?[:0]const u8 {
        return self.representation("text/uri-list", false);
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
    uris: ?[]u8 = null,

    pub fn init(gpa: std.mem.Allocator, text: ?[]const u8, html: ?[]const u8) !*Payload {
        return initAll(gpa, text, html, null);
    }
    pub fn initAll(gpa: std.mem.Allocator, text: ?[]const u8, html: ?[]const u8, uris: ?[]const u8) !*Payload {
        if (text == null and html == null and uris == null) return error.EmptyClipboardRepresentations;
        if (text) |value| try validate(value);
        if (html) |value| try validate(value);
        if (uris) |value| try validate(value);
        const self = try gpa.create(Payload);
        errdefer gpa.destroy(self);
        const plain = if (text) |value| try gpa.dupe(u8, value) else null;
        errdefer if (plain) |bytes| gpa.free(bytes);
        const rich = if (html) |value| try gpa.dupe(u8, value) else null;
        errdefer if (rich) |bytes| gpa.free(bytes);
        self.* = .{ .gpa = gpa, .text = plain, .html = rich, .uris = if (uris) |value| try gpa.dupe(u8, value) else null };
        return self;
    }
    pub fn destroy(self: *Payload) void {
        if (self.text) |text| self.gpa.free(text);
        if (self.html) |html| self.gpa.free(html);
        if (self.uris) |uris| self.gpa.free(uris);
        self.gpa.destroy(self);
    }
    pub fn validate(bytes: []const u8) !void {
        if (bytes.len > Transfers.max_bytes) return error.OfferTooLarge;
        if (!std.unicode.utf8ValidateSlice(bytes) or std.mem.indexOfScalar(u8, bytes, 0) != null)
            return error.InvalidClipboardText;
    }
};

gpa: std.mem.Allocator,
io: std.Io,
transfers: *Transfers,
proxy: *wl.DataOffer,
token: u64,
mimes: MimeTypes = .{},
waiting: u3 = 0,
source_actions: wl.DataDeviceManager.DndAction = .{},
action: wl.DataDeviceManager.DndAction = .{},
received: bool = false,
text: ?[]u8 = null,
html: ?[]u8 = null,
uris: ?[]u8 = null,
context: *anyopaque,
failure: *const fn (*anyopaque, anyerror) void,

/// The caller retains the proxy if allocation fails.
pub fn init(gpa: std.mem.Allocator, io: std.Io, transfers: *Transfers, proxy: *wl.DataOffer, token: u64, context: *anyopaque, failure: *const fn (*anyopaque, anyerror) void) !*Self {
    const self = try gpa.create(Self);
    self.* = .{ .gpa = gpa, .io = io, .transfers = transfers, .proxy = proxy, .token = token, .context = context, .failure = failure };
    proxy.setListener(*Self, listener, self);
    return self;
}

pub fn destroy(self: *Self) void {
    for (0..3) |representation| self.transfers.cancel(self.token + representation);
    if (self.text) |bytes| self.gpa.free(bytes);
    if (self.html) |bytes| self.gpa.free(bytes);
    if (self.uris) |bytes| self.gpa.free(bytes);
    self.proxy.destroy();
    self.gpa.destroy(self);
}

pub fn receive(self: *Self, mime: [:0]const u8, representation: u2) !void {
    if (representation > 2 or self.waiting & (@as(u3, 1) << representation) != 0)
        return error.InvalidOfferRepresentation;
    const pipe = try Stream.Pipe.init();
    defer pipe.write.close(self.io);
    try self.transfers.receive(self.token + representation, pipe.read);
    self.proxy.receive(mime, pipe.write.handle);
    self.waiting |= @as(u3, 1) << representation;
}

/// Consumes a receive completion on every return. A completed payload transfers
/// to the caller; partial bytes remain with this offer and die with its owner.
pub fn complete(self: *Self, completion: Transfers.Completion) !?*Payload {
    const bytes = try completion.result;
    errdefer self.gpa.free(bytes);
    if (completion.token < self.token or completion.token > self.token + 2)
        return error.InvalidOfferCompletion;
    const representation: u2 = @intCast(completion.token - self.token);
    const bit = @as(u3, 1) << representation;
    if (self.waiting & bit == 0) return error.DuplicateOfferCompletion;
    try Payload.validate(bytes);
    // Allocate before moving this completion into owned storage, so a failed
    // allocation leaves exactly one owner for every completed representation.
    const payload = if (self.waiting == bit) try self.gpa.create(Payload) else null;
    switch (representation) {
        0 => self.text = bytes,
        1 => self.html = bytes,
        2 => self.uris = bytes,
        else => unreachable,
    }
    self.waiting &= ~bit;
    if (payload) |result| {
        result.* = .{ .gpa = self.gpa, .text = self.text, .html = self.html, .uris = self.uris };
        self.text = null;
        self.html = null;
        self.uris = null;
    }
    return payload;
}

fn listener(_: *wl.DataOffer, event: wl.DataOffer.Event, self: *Self) void {
    switch (event) {
        .offer => |mime| self.mimes.add(std.mem.span(mime.mime_type)) catch |err| self.failure(self.context, err),
        .source_actions => |event_actions| self.source_actions = event_actions.source_actions,
        .action => |event_action| self.action = event_action.dnd_action,
    }
}
