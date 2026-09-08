//! Seat-owned text-input-v3, version 1. Android owns the editor; this module
//! owns protocol state, bounded UTF-8 payloads and the commit/done chronology.
const std = @import("std");
const wl = @import("wayland").client.wl;
const zwp = @import("wayland").client.zwp;
const Self = @This();

pub const max_bytes = 4000;
pub const Text = struct {
    storage: [max_bytes + 1]u8 = @splat(0),
    len: usize = 0,

    pub fn init(bytes: []const u8) !Text {
        if (bytes.len > max_bytes or std.mem.indexOfScalar(u8, bytes, 0) != null or
            !std.unicode.utf8ValidateSlice(bytes)) return error.InvalidTextInputString;
        var result: Text = .{ .len = bytes.len };
        @memcpy(result.storage[0..bytes.len], bytes);
        return result;
    }
    pub fn slice(self: *const Text) [:0]const u8 {
        return self.storage[0..self.len :0];
    }
    pub fn boundary(self: *const Text, offset: i32) bool {
        if (offset < 0 or offset > self.len) return false;
        const at: usize = @intCast(offset);
        return at == self.len or self.storage[at] & 0xc0 != 0x80;
    }
};

pub const State = struct {
    editor: i64,
    /// Last batch completely applied on the app InputConnection, not merely queued.
    applied: i64 = 0,
    enabled: bool = true,
    surrounding: ?Text = null,
    cursor: i32 = 0,
    anchor: i32 = 0,
    hint: u32 = 0,
    purpose: u32 = 0,
    external_change: bool = false,
    rectangle: ?[4]i32 = null,

    pub fn validate(self: *const State) !void {
        if (self.editor <= 0 or self.applied < 0 or self.hint & ~@as(u32, 0x3ff) != 0 or self.purpose > 13)
            return error.InvalidTextInputState;
        if (self.surrounding) |*text| {
            if (!text.boundary(self.cursor) or !text.boundary(self.anchor)) return error.InvalidTextInputSelection;
        } else if (self.cursor != 0 or self.anchor != 0) return error.InvalidTextInputSelection;
        if (self.rectangle) |rect| if (rect[2] < 0 or rect[3] < 0) return error.InvalidTextInputRectangle;
    }
};

/// Wrap-safe serials distinguish a superseded editor from a current editor's
/// valid edits based on an older state. A mismatch delays publication, not edits.
pub const Serials = struct {
    committed: u32 = 0,
    first: ?u32 = null,
    deferred: bool = false,
    delivered: i64 = 0,

    pub fn commit(self: *Serials, new_editor: bool) void {
        self.committed +%= 1;
        if (new_editor) {
            self.first = self.committed;
            self.deferred = false;
            self.delivered = 0;
        }
    }
    pub fn disable(self: *Serials) void {
        self.first = null;
        self.deferred = false;
        self.delivered = 0;
    }
    pub fn done(self: *Serials, serial: u32) !?i64 {
        const first = self.first orelse return null;
        const span = self.committed -% first;
        if (span >= 0x80000000) return error.TextInputSerialLifetimeExceeded;
        if (serial -% first > span) return null;
        self.deferred = serial != self.committed;
        if (self.delivered == std.math.maxInt(i64)) return error.TextInputSequenceExhausted;
        self.delivered += 1;
        return self.delivered;
    }
    pub fn canPublish(self: *const Serials, applied: i64) bool {
        return self.first != null and !self.deferred and applied == self.delivered;
    }
};

pub const Batch = struct {
    allocator: std.mem.Allocator,
    editor: i64 = 0,
    sequence: i64 = 0,
    serial: u32 = 0,
    fields: i32 = 0,
    preedit: Text = .{},
    commit: Text = .{},
    cursor_begin: i32 = 0,
    cursor_end: i32 = 0,
    before: u32 = 0,
    after: u32 = 0,

    pub fn destroy(self: *Batch) void {
        self.allocator.destroy(self);
    }
};
pub const Hooks = struct {
    context: *anyopaque,
    focus: *const fn (*anyopaque, *wl.Surface, bool) void,
    /// Ownership of the batch transfers even if delivery fails.
    batch: *const fn (*anyopaque, *wl.Surface, *Batch) void,
    failure: *const fn (*anyopaque, anyerror) void,
};

allocator: std.mem.Allocator,
proxy: *zwp.TextInputV3,
hooks: Hooks,
surface: ?*wl.Surface = null,
editor: i64 = 0,
highest_editor: i64 = 0,
serials: Serials = .{},
pending: Batch,
published_context: bool = false,
published_rectangle: bool = false,
published_hint: u32 = 0,
published_purpose: u32 = 0,

pub fn init(allocator: std.mem.Allocator, manager: *zwp.TextInputManagerV3, seat: *wl.Seat, hooks: Hooks) !*Self {
    const self = try allocator.create(Self);
    errdefer allocator.destroy(self);
    const proxy = try manager.getTextInput(seat);
    self.* = .{ .allocator = allocator, .proxy = proxy, .hooks = hooks, .pending = .{ .allocator = allocator } };
    proxy.setListener(*Self, listener, self);
    return self;
}

pub fn deinit(self: *Self) void {
    self.leave();
    self.proxy.destroy();
    self.allocator.destroy(self);
}

/// Return false when the compositor has not granted this surface text focus.
/// The Android adapter retains its editor and republishes after an enter event.
pub fn update(self: *Self, surface: *wl.Surface, state: *const State) !bool {
    try state.validate();
    if (self.surface != surface) return false;
    if (!state.enabled) {
        // A late disable for an old editor must not disable its replacement.
        if (self.editor != 0 and self.editor != state.editor) return false;
        self.proxy.disable();
        self.proxy.commit();
        self.serials.commit(false);
        self.reset();
        return true;
    }
    const new_editor = self.editor != state.editor;
    if (new_editor) {
        if (state.editor <= self.highest_editor) return false;
        if (state.applied != 0) return error.InvalidTextInputAcknowledgement;
        self.reset();
        self.editor = state.editor;
        self.highest_editor = state.editor;
        self.proxy.enable();
        self.publish(state, true);
    } else {
        if (state.applied > self.serials.delivered) return error.InvalidTextInputAcknowledgement;
        if (self.serials.canPublish(state.applied)) {
            self.publish(state, false);
        }
    }
    return true;
}

fn publish(self: *Self, state: *const State, new_editor: bool) void {
    // Context/rectangle support and content type last until enable/disable.
    // Reset when support disappears too, so a password cannot retain old text.
    const reset_support = !new_editor and (self.published_context != (state.surrounding != null) or
        self.published_rectangle != (state.rectangle != null) or self.published_hint != state.hint or self.published_purpose != state.purpose);
    if (reset_support) self.proxy.enable();
    if (state.surrounding) |*text| self.proxy.setSurroundingText(text.slice(), state.cursor, state.anchor);
    self.proxy.setTextChangeCause(if (state.external_change) .other else .input_method);
    self.proxy.setContentType(@bitCast(state.hint), @fromBackingInt(@intCast(state.purpose)));
    if (state.rectangle) |rect| self.proxy.setCursorRectangle(rect[0], rect[1], rect[2], rect[3]);
    self.proxy.commit();
    self.serials.commit(new_editor);
    self.published_context = state.surrounding != null;
    self.published_rectangle = state.rectangle != null;
    self.published_hint = state.hint;
    self.published_purpose = state.purpose;
}

fn reset(self: *Self) void {
    self.editor = 0;
    self.serials.disable();
    self.pending = .{ .allocator = self.allocator };
}

fn leave(self: *Self) void {
    const old = self.surface;
    self.surface = null;
    self.reset();
    if (old) |surface| self.hooks.focus(self.hooks.context, surface, false);
}

/// Destroying a task also retires its editor before its wl_surface disappears.
pub fn removeSurface(self: *Self, surface: *wl.Surface) void {
    if (self.surface == surface) {
        self.proxy.disable();
        self.proxy.commit();
        self.serials.commit(false);
        self.leave();
    }
}

fn listener(_: *zwp.TextInputV3, event: zwp.TextInputV3.Event, self: *Self) void {
    self.handle(event) catch |err| {
        self.reset();
        self.hooks.failure(self.hooks.context, err);
    };
}

fn incoming(raw: ?[*:0]const u8) !Text {
    const value = raw orelse return .{};
    // Scan only our protocol budget, even if a peer sends a larger message.
    for (0..max_bytes + 1) |index| if (value[index] == 0) return Text.init(value[0..index]);
    return error.TextInputStringTooLong;
}

fn handle(self: *Self, event: zwp.TextInputV3.Event) !void {
    switch (event) {
        .enter => |entered| {
            self.leave();
            const surface = entered.surface orelse return; // Surface was retired before dispatch.
            self.surface = surface;
            self.hooks.focus(self.hooks.context, surface, true);
        },
        .leave => |left| {
            if (self.surface == left.surface) self.leave();
        },
        .preedit_string => |preedit| {
            const value = try incoming(preedit.text);
            if (!(preedit.cursor_begin == -1 and preedit.cursor_end == -1) and
                (!value.boundary(preedit.cursor_begin) or !value.boundary(preedit.cursor_end)))
                return error.InvalidTextInputPreeditCursor;
            self.pending.preedit = value;
            self.pending.fields |= 1;
            self.pending.cursor_begin = preedit.cursor_begin;
            self.pending.cursor_end = preedit.cursor_end;
        },
        .commit_string => |committed| {
            self.pending.commit = try incoming(committed.text);
            self.pending.fields |= 2;
        },
        .delete_surrounding_text => |deleted| {
            if (deleted.before_length > max_bytes or deleted.after_length > max_bytes)
                return error.TextInputDeletionTooLarge;
            self.pending.before = deleted.before_length;
            self.pending.after = deleted.after_length;
        },
        .done => |done| {
            defer self.pending = .{ .allocator = self.allocator };
            const sequence = (try self.serials.done(done.serial)) orelse return;
            const surface = self.surface orelse return;
            const batch = try self.allocator.create(Batch);
            batch.* = self.pending;
            batch.editor = self.editor;
            batch.serial = done.serial;
            batch.sequence = sequence;
            // Publication waits for Android's acknowledgement of this batch.
            // Never publish a cached context before the app applies its edits.
            self.hooks.batch(self.hooks.context, surface, batch);
        },
    }
}

test "text input byte boundaries and bounded payloads" {
    const text = try Text.init("aé中😀");
    try std.testing.expect(text.boundary(10));
    try std.testing.expect(!text.boundary(8));
    try std.testing.expectError(error.InvalidTextInputString, Text.init("a\x00b"));
    try std.testing.expectError(error.InvalidTextInputString, Text.init("\xed\xa0\x80"));
    const long: [max_bytes + 1]u8 = @splat('x');
    try std.testing.expectError(error.InvalidTextInputString, Text.init(&long));
    try std.testing.expectEqual(max_bytes, (try Text.init(long[0..max_bytes])).len);
    try std.testing.expectError(error.InvalidTextInputSelection, (State{ .editor = 1, .surrounding = text, .cursor = 8 }).validate());
}

test "done mismatch applies edits but defers publication until app acknowledgement" {
    var serials: Serials = .{};
    serials.commit(true);
    serials.commit(false);
    try std.testing.expectEqual(@as(?i64, 1), try serials.done(1));
    try std.testing.expect(!serials.canPublish(1));
    try std.testing.expectEqual(@as(?i64, 2), try serials.done(2));
    try std.testing.expect(!serials.canPublish(1));
    try std.testing.expect(serials.canPublish(2));
    serials.commit(true);
    try std.testing.expectEqual(@as(?i64, null), try serials.done(2));
    try std.testing.expectEqual(@as(?i64, 1), try serials.done(3));
    serials.disable();
    try std.testing.expectEqual(@as(?i64, null), try serials.done(3));
}

test "text input serials wrap without accepting superseded editors or future state" {
    var serials: Serials = .{ .committed = 0xfffffffe };
    serials.commit(true);
    serials.commit(false);
    try std.testing.expectEqual(@as(?i64, 1), try serials.done(0xffffffff));
    try std.testing.expect(!serials.canPublish(1));
    try std.testing.expectEqual(@as(?i64, 2), try serials.done(0));
    try std.testing.expect(serials.canPublish(2));
    try std.testing.expectEqual(@as(?i64, null), try serials.done(1));
    try std.testing.expectEqual(@as(?i64, null), try serials.done(0xfffffffe));
}

test "incoming fields replace pending values and done restores their defaults" {
    const Capture = struct {
        batch: ?*Batch = null,
        fn focus(_: *anyopaque, _: *wl.Surface, _: bool) void {}
        fn receive(raw: *anyopaque, _: *wl.Surface, batch: *Batch) void {
            const self: *@This() = @ptrCast(@alignCast(raw));
            std.debug.assert(self.batch == null);
            self.batch = batch;
        }
        fn failure(_: *anyopaque, _: anyerror) void {
            unreachable;
        }
    };
    var captured: Capture = .{};
    defer if (captured.batch) |batch| batch.destroy();
    var input: Self = .{
        .allocator = std.testing.allocator,
        .proxy = undefined,
        .hooks = .{ .context = &captured, .focus = Capture.focus, .batch = Capture.receive, .failure = Capture.failure },
        .surface = @ptrFromInt(8),
        .editor = 7,
        .pending = .{ .allocator = std.testing.allocator },
    };
    input.serials.commit(true);
    try input.handle(.{ .preedit_string = .{ .text = "first", .cursor_begin = 5, .cursor_end = 5 } });
    try input.handle(.{ .preedit_string = .{ .text = "é", .cursor_begin = -1, .cursor_end = -1 } });
    try input.handle(.{ .commit_string = .{ .text = "中" } });
    try input.handle(.{ .delete_surrounding_text = .{ .before_length = 4, .after_length = 2 } });
    try input.handle(.{ .done = .{ .serial = 1 } });
    const batch = captured.batch.?;
    try std.testing.expectEqual(@as(i32, 3), batch.fields);
    try std.testing.expectEqualStrings("é", batch.preedit.slice());
    try std.testing.expectEqualStrings("中", batch.commit.slice());
    try std.testing.expectEqual(@as(i32, -1), batch.cursor_begin);
    try std.testing.expectEqual(@as(u32, 4), batch.before);
    try std.testing.expectEqual(@as(i64, 7), batch.editor);
    batch.destroy();
    captured.batch = null;
    try input.handle(.{ .done = .{ .serial = 1 } });
    try std.testing.expectEqual(@as(i32, 0), captured.batch.?.fields);
    try std.testing.expectEqual(@as(usize, 0), captured.batch.?.preedit.len);
    try std.testing.expectEqual(@as(u32, 0), captured.batch.?.before);
    try std.testing.expectEqual(@as(i64, 2), captured.batch.?.sequence);
    try std.testing.expectError(error.InvalidTextInputPreeditCursor, input.handle(.{ .preedit_string = .{ .text = "é", .cursor_begin = 1, .cursor_end = 1 } }));
}
