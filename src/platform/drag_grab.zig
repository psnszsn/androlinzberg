//! A drag can consume only the current physical pointer/touch grab. Serials
//! from keyboard focus or clipboard publication never enter this state. A
//! generation distinguishes reused contact IDs, surfaces and wrapped serials.
const std = @import("std");
pub const Kind = enum { pointer, touch };

pub fn Held(comptime Window: type, comptime Surface: type) type {
    return struct {
        const Self = @This();
        pub const Origin = struct { window: *Window, surface: *Surface };
        pub const Claim = struct { origin: Origin, serial: u32, generation: u64 };
        const Pointer = struct {
            claim: Claim,
            buttons: [16]u32 = undefined,
            count: usize = 0,
            used: bool = false,
            overflow: bool = false,
        };
        const Touch = struct { claim: Claim, id: i32, used: bool = false };
        pointer: ?Pointer = null,
        touches: [10]?Touch = @splat(null),
        next_generation: u64 = 1,

        fn claim(self: *Self, origin: Origin, serial: u32) ?Claim {
            if (self.next_generation > std.math.maxInt(i64)) return null;
            const generation = self.next_generation;
            self.next_generation += 1;
            return .{ .origin = origin, .serial = serial, .generation = generation };
        }
        pub fn pointerButton(self: *Self, origin: ?Origin, serial: u32, button: u32, pressed: bool) void {
            if (pressed) {
                if (self.pointer == null) {
                    self.pointer = .{ .claim = self.claim(origin orelse return, serial) orelse return };
                }
                const pointer = &self.pointer.?;
                for (pointer.buttons[0..pointer.count]) |held| if (held == button) return;
                if (pointer.count == pointer.buttons.len) {
                    pointer.overflow = true;
                    return;
                }
                pointer.buttons[pointer.count] = button;
                pointer.count += 1;
            } else if (self.pointer) |*pointer| {
                for (pointer.buttons[0..pointer.count], 0..) |held, index| {
                    if (held != button) continue;
                    pointer.count -= 1;
                    std.mem.copyForwards(u32, pointer.buttons[index..pointer.count], pointer.buttons[index + 1 .. pointer.count + 1]);
                    if (pointer.count == 0 and !pointer.overflow) self.pointer = null;
                    break;
                }
            }
        }
        pub fn touchDown(self: *Self, origin: Origin, serial: u32, id: i32) void {
            for (&self.touches) |*slot| if (slot.*) |held| if (held.id == id) {
                // The compositor can consume UP after start_drag. A new DOWN
                // for a consumed contact starts a fresh physical gesture.
                if (held.used) slot.* = .{ .claim = self.claim(origin, serial) orelse return, .id = id };
                return;
            };
            for (&self.touches) |*slot| if (slot.* == null) {
                slot.* = .{ .claim = self.claim(origin, serial) orelse return, .id = id };
                return;
            };
        }
        pub fn touchUp(self: *Self, id: i32) void {
            for (&self.touches) |*slot| if (slot.*) |touch| if (touch.id == id) {
                slot.* = null;
                return;
            };
        }
        pub fn clearPointer(self: *Self) void {
            self.pointer = null;
        }
        pub fn clearTouch(self: *Self) void {
            self.touches = @splat(null);
        }
        pub fn removeWindow(self: *Self, window: *Window) void {
            if (self.pointer) |pointer| if (pointer.claim.origin.window == window) {
                self.pointer = null;
            };
            for (&self.touches) |*slot| if (slot.*) |touch| if (touch.claim.origin.window == window) {
                slot.* = null;
            };
        }
        pub fn snapshot(self: *const Self, window: *Window, kind: Kind, id: i32) ?Claim {
            switch (kind) {
                .pointer => {
                    const pointer = self.pointer orelse return null;
                    if (id != 0 or pointer.used or pointer.overflow or pointer.claim.origin.window != window) return null;
                    return pointer.claim;
                },
                .touch => for (self.touches) |slot| {
                    const touch = slot orelse continue;
                    if (touch.id == id and !touch.used and touch.claim.origin.window == window) return touch.claim;
                },
            }
            return null;
        }
        /// Generation is checked again after asynchronous URI staging. Taking
        /// the claim consumes it even if subsequent native source setup fails.
        pub fn take(self: *Self, window: *Window, kind: Kind, id: i32, generation: u64) ?Claim {
            const current = self.snapshot(window, kind, id) orelse return null;
            if (current.generation != generation) return null;
            switch (kind) {
                .pointer => self.pointer.?.used = true,
                .touch => for (&self.touches) |*slot| {
                    if (slot.*) |*touch| if (touch.id == id) {
                        touch.used = true;
                        break;
                    };
                },
            }
            return current;
        }
    };
}

test "native drag uses the original held pointer serial and consumes one generation" {
    const State = Held(u8, u8);
    var state: State = .{};
    var a: u8 = 1;
    var b: u8 = 2;
    var child: u8 = 3;
    const origin: State.Origin = .{ .window = &a, .surface = &child };
    try std.testing.expect(state.snapshot(&a, .pointer, 0) == null);
    state.pointerButton(origin, 12, 0x110, true);
    const first = state.snapshot(&a, .pointer, 0).?;
    state.pointerButton(origin, 15, 0x111, true);
    try std.testing.expectEqual(@as(u32, 12), state.snapshot(&a, .pointer, 0).?.serial);
    try std.testing.expect(state.snapshot(&b, .pointer, 0) == null);
    try std.testing.expect(state.take(&a, .pointer, 0, first.generation + 1) == null);
    const taken = state.take(&a, .pointer, 0, first.generation).?;
    try std.testing.expect(taken.origin.surface == &child);
    try std.testing.expect(state.take(&a, .pointer, 0, first.generation) == null);
    state.pointerButton(null, 16, 0x110, false);
    state.pointerButton(null, 17, 0x111, false);
    state.pointerButton(origin, 12, 0x110, true); // Serial wrap/reuse must not revive the old claim.
    try std.testing.expect(state.take(&a, .pointer, 0, first.generation) == null);
    state.removeWindow(&a);
    try std.testing.expect(state.snapshot(&a, .pointer, 0) == null);
}

test "native touch claims are task scoped and expire on contact reuse and seat loss" {
    const State = Held(u8, u8);
    var state: State = .{};
    var a: u8 = 1;
    var b: u8 = 2;
    var sa: u8 = 3;
    var sb: u8 = 4;
    const oa: State.Origin = .{ .window = &a, .surface = &sa };
    const ob: State.Origin = .{ .window = &b, .surface = &sb };
    state.touchDown(oa, 9, 72);
    state.touchDown(ob, 10, 99);
    const first = state.snapshot(&a, .touch, 72).?;
    try std.testing.expect(state.snapshot(&a, .touch, 99) == null);
    _ = state.take(&a, .touch, 72, first.generation).?;
    state.touchDown(oa, 9, 72); // Native drag consumed the previous UP.
    try std.testing.expect(state.take(&a, .touch, 72, first.generation) == null);
    state.touchUp(72);
    state.touchDown(oa, 9, 72);
    try std.testing.expect(state.take(&a, .touch, 72, first.generation) == null);
    state.removeWindow(&a);
    try std.testing.expect(state.snapshot(&b, .touch, 99) != null);
    state.clearTouch();
    state.clearPointer();
    try std.testing.expect(state.snapshot(&b, .touch, 99) == null);
}

test "pointer button overflow stays invalid until the implicit grab is reset" {
    const State = Held(u8, u8);
    var state: State = .{};
    var window: u8 = 0;
    var surface: u8 = 0;
    const origin: State.Origin = .{ .window = &window, .surface = &surface };
    for (0..17) |button| state.pointerButton(origin, 5, @intCast(button), true);
    for (0..17) |button| state.pointerButton(null, 6, @intCast(button), false);
    state.pointerButton(origin, 7, 0, true);
    try std.testing.expect(state.snapshot(&window, .pointer, 0) == null);
    state.clearPointer();
    state.pointerButton(origin, 8, 0, true);
    try std.testing.expectEqual(@as(u32, 8), state.snapshot(&window, .pointer, 0).?.serial);
}
