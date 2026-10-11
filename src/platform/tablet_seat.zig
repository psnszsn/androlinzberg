//! Tablet-v2 tool identity and frame assembly. Protocol objects stay on the
//! session thread; callbacks carry value snapshots and borrowed surface owners.
const std = @import("std");
const client = @import("wayland").client;
const wl = client.wl;
const zwp = client.zwp;
const wp = client.wp;
const CursorImage = @import("cursor_image.zig");
const Self = @This();
pub const max_tools = 16;
pub const first_device = 3;
pub const Device = struct { id: i32, generation: i32, kind: i32, capabilities: i32, present: bool };
/// `frame` ends one protocol frame for its target, as SYN_REPORT ends an evdev packet.
pub const Action = enum(i32) { enter, move, down, up, button, leave, cancel, frame };
pub const Sample = struct {
    id: i32,
    generation: i32,
    action: Action,
    button: u32 = 0,
    pressed: bool = false,
    axes: [9]f32,
};
pub const Event = union(enum) { device: Device, sample: Sample };
pub const Callbacks = struct {
    context: *anyopaque,
    target: *const fn (*anyopaque, *wl.Surface) ?*anyopaque,
    emit: *const fn (*anyopaque, ?*anyopaque, Event) void,
};
gpa: std.mem.Allocator,
proxy: *zwp.TabletSeatV2,
callbacks: Callbacks,
tools: [max_tools]?*Tool = @splat(null),
tablets: [16]?*Tablet = @splat(null),
pads: [16]?*Pad = @splat(null),
generation: *i32,
failed: ?anyerror = null,
pointer_captured: bool = false,

pub fn init(gpa: std.mem.Allocator, manager: *zwp.TabletManagerV2, seat: *wl.Seat, generation: *i32, callbacks: Callbacks) !*Self {
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    self.* = .{ .gpa = gpa, .proxy = try manager.getTabletSeat(seat), .callbacks = callbacks, .generation = generation };
    self.proxy.setListener(*Self, listener, self);
    return self;
}
pub fn deinit(self: *Self) void {
    for (self.tools) |slot| if (slot) |tool| {
        if (tool.complete) self.emit(null, .{ .device = tool.device(false) });
        tool.destroy();
    };
    for (self.tablets) |slot| if (slot) |tablet| tablet.destroy();
    for (self.pads) |slot| if (slot) |pad| pad.destroy();
    self.proxy.destroy();
    self.gpa.destroy(self);
}
pub fn replayDevices(self: *Self) void {
    for (self.tools) |slot| if (slot) |tool| if (tool.complete) self.emit(null, .{ .device = tool.device(true) });
}
pub fn detachTarget(self: *Self, target: *anyopaque) void {
    for (self.tools) |slot| if (slot) |tool| {
        if (tool.target == target) {
            tool.target = null;
            tool.serial = 0;
            tool.clearCursor();
        }
        var index: usize = 0;
        while (index < tool.edge_count) {
            if (tool.edges[index].target == target) {
                tool.edge_count -= 1;
                std.mem.copyForwards(Edge, tool.edges[index..tool.edge_count], tool.edges[index + 1 .. tool.edge_count + 1]);
            } else index += 1;
        }
    };
}
pub fn pointerCapture(self: *Self, enabled: bool) void {
    self.pointer_captured = enabled;
    if (enabled) for (self.tools) |slot| if (slot) |tool| {
        if (tool.kind == 0x145) continue;
        if (tool.target != null and tool.serial != 0) tool.proxy.setCursor(tool.serial, null, 0, 0);
        tool.clearCursor();
    };
}
pub fn findTool(self: *Self, target: *anyopaque, id: i32, generation: i32) ?*Tool {
    if (id < first_device or id >= first_device + max_tools) return null;
    const tool = self.tools[@intCast(id - first_device)] orelse return null;
    if (self.pointer_captured and tool.kind != 0x145) return null;
    return if (tool.complete and tool.generation == generation and tool.target == target and tool.serial != 0) tool else null;
}
fn emit(self: *Self, target: ?*anyopaque, event: Event) void {
    if (self.failed == null) self.callbacks.emit(self.callbacks.context, target, event);
}
fn listener(_: *zwp.TabletSeatV2, event: zwp.TabletSeatV2.Event, self: *Self) void {
    switch (event) {
        .tool_added => |added| {
            const index = for (self.tools, 0..) |tool, index| {
                if (tool == null) break index;
            } else {
                added.id.destroy();
                self.failed = error.TooManyTabletTools;
                return;
            };
            self.generation.* = std.math.add(i32, self.generation.*, 1) catch {
                added.id.destroy();
                self.failed = error.TabletGenerationExhausted;
                return;
            };
            const tool = self.gpa.create(Tool) catch {
                added.id.destroy();
                self.failed = error.OutOfMemory;
                return;
            };
            tool.* = .{ .owner = self, .proxy = added.id, .slot = index, .generation = self.generation.* };
            self.tools[index] = tool;
            added.id.setListener(*Tool, Tool.listener, tool);
        },
        .tablet_added => |added| {
            const index = for (self.tablets, 0..) |tablet, index| {
                if (tablet == null) break index;
            } else {
                added.id.destroy();
                self.failed = error.TooManyTablets;
                return;
            };
            const tablet = self.gpa.create(Tablet) catch {
                added.id.destroy();
                self.failed = error.OutOfMemory;
                return;
            };
            tablet.* = .{ .owner = self, .proxy = added.id, .slot = index };
            self.tablets[index] = tablet;
            added.id.setListener(*Tablet, Tablet.listener, tablet);
        },
        .pad_added => |added| {
            const index = for (self.pads, 0..) |pad, index| {
                if (pad == null) break index;
            } else {
                added.id.destroy();
                self.failed = error.TooManyTabletPads;
                return;
            };
            const pad = self.gpa.create(Pad) catch {
                added.id.destroy();
                self.failed = error.OutOfMemory;
                return;
            };
            pad.* = .{ .owner = self, .proxy = added.id, .slot = index };
            self.pads[index] = pad;
            added.id.setListener(*Pad, Pad.listener, pad);
        },
    }
}
const Tablet = struct {
    owner: *Self,
    proxy: *zwp.TabletV2,
    slot: usize,
    fn destroy(self: *Tablet) void {
        for (self.owner.tools) |slot| if (slot) |tool| if (tool.tablet == self.proxy) {
            if (tool.target) |target| tool.owner.emit(target, .{ .sample = tool.sample(.cancel) });
            tool.target = null;
            tool.serial = 0;
            tool.tablet = null;
            tool.clearCursor();
            tool.edge_count = 0;
        };
        self.owner.tablets[self.slot] = null;
        self.proxy.destroy();
        self.owner.gpa.destroy(self);
    }
    fn listener(_: *zwp.TabletV2, event: zwp.TabletV2.Event, self: *Tablet) void {
        switch (event) {
            .removed => self.destroy(),
            else => {},
        }
    }
};
const Edge = struct { target: *anyopaque, sample: Sample, sealed: bool = false };
pub const Tool = struct {
    owner: *Self,
    proxy: *zwp.TabletToolV2,
    slot: usize,
    generation: i32,
    kind: i32 = 0,
    capabilities: i32 = 0,
    complete: bool = false,
    tablet: ?*zwp.TabletV2 = null,
    target: ?*anyopaque = null,
    serial: u32 = 0,
    cursor_image: ?CursorImage = null,
    cursor_device: ?*wp.CursorShapeDeviceV1 = null,
    axes: [9]f32 = @splat(0),
    dirty: bool = false,
    edges: [32]Edge = undefined,
    edge_count: usize = 0,
    fn device(self: *Tool, present: bool) Device {
        return .{ .id = @intCast(first_device + self.slot), .generation = self.generation, .kind = self.kind, .capabilities = self.capabilities, .present = present };
    }
    fn sample(self: *Tool, action: Action) Sample {
        return .{ .id = @intCast(first_device + self.slot), .generation = self.generation, .action = action, .axes = self.axes };
    }
    pub fn clearCursor(self: *Tool) void {
        if (self.cursor_image) |*cursor| cursor.deinit();
        self.cursor_image = null;
    }
    pub fn setPointerIcon(self: *Tool, manager: ?*wp.CursorShapeManagerV1, icon: i32) bool {
        if (self.target == null or self.serial == 0) return false;
        if (icon == 0) {
            self.proxy.setCursor(self.serial, null, 0, 0);
            self.clearCursor();
            return true;
        }
        const shape = @import("cursor_shape.zig").fromAndroid(icon) orelse return false;
        const factory = manager orelse return false;
        if (self.cursor_device == null) self.cursor_device = factory.getTabletToolV2(self.proxy) catch return false;
        self.cursor_device.?.setShape(self.serial, @fromBackingInt(@intCast(shape)));
        self.clearCursor();
        return true;
    }
    pub fn setCursorImage(self: *Tool, io: std.Io, compositor: *wl.Compositor, shm: *wl.Shm, width: i32, height: i32, hotspot_x: i32, hotspot_y: i32, frame_ms: i32, pixels: []const i32) !bool {
        if (self.target == null or self.serial == 0) return false;
        var cursor = try CursorImage.init(io, compositor, shm, width, height, hotspot_x, hotspot_y, frame_ms, pixels);
        cursor.apply(io, self.proxy, self.serial);
        self.clearCursor();
        self.cursor_image = cursor;
        return true;
    }
    pub fn updateCursorAnimation(self: *Tool, io: std.Io) i32 {
        if (self.target == null) return -1;
        if (self.cursor_image) |*cursor| {
            cursor.tick(io);
            return cursor.timeout(io);
        }
        return -1;
    }
    fn destroy(self: *Tool) void {
        self.clearCursor();
        if (self.cursor_device) |cursor_device| cursor_device.destroy();
        self.owner.tools[self.slot] = null;
        self.proxy.destroy();
        self.owner.gpa.destroy(self);
    }
    fn edge(self: *Tool, action: Action, button: u32, pressed: bool) void {
        const target = self.target orelse return;
        if (self.edge_count == self.edges.len) {
            self.owner.failed = error.TabletFrameOverflow;
            return;
        }
        var value = self.sample(action);
        value.button = button;
        value.pressed = pressed;
        value.axes[8] = 0;
        self.edges[self.edge_count] = .{ .target = target, .sample = value };
        self.edge_count += 1;
    }
    fn axis(self: *Tool, index: usize, value: f32) void {
        if (!std.math.isFinite(value)) {
            self.owner.failed = error.InvalidTabletAxis;
            return;
        }
        self.axes[index] = value;
        self.dirty = true;
        // DOWN/ENTER can precede motion and pressure in the same protocol
        // frame. Sealed edges belong to an earlier surface's proximity.
        for (self.edges[0..self.edge_count]) |*edge_value| {
            if (index != 8 and !edge_value.sealed and edge_value.target == self.target)
                edge_value.sample.axes[index] = value;
        }
    }
    fn unit(self: *Tool, index: usize, value: u32) void {
        if (value > 65535) {
            self.owner.failed = error.InvalidTabletAxis;
            return;
        }
        self.axis(index, @as(f32, @floatFromInt(value)) / 65535.0);
    }
    fn frame(self: *Tool) void {
        if (!self.complete) {
            self.owner.failed = error.IncompleteTabletTool;
            return;
        }
        // Each target's run of samples ends with its frame.
        var framed: ?*anyopaque = null;
        var pending = false;
        const Publish = struct {
            fn sample(tool: *Tool, target: *anyopaque, value: Sample, last: *?*anyopaque, open: *bool) void {
                if (open.* and last.* != target) tool.owner.emit(last.*, .{ .sample = tool.sample(.frame) });
                tool.owner.emit(target, .{ .sample = value });
                last.* = target;
                open.* = true;
            }
        };
        if (self.edge_count == 0 and self.dirty) {
            if (self.target) |target| Publish.sample(self, target, self.sample(.move), &framed, &pending);
        } else {
            for (self.edges[0..self.edge_count]) |edge_value|
                Publish.sample(self, edge_value.target, edge_value.sample, &framed, &pending);
            if (self.axes[8] != 0) if (self.target) |target|
                Publish.sample(self, target, self.sample(.move), &framed, &pending);
        }
        if (pending) self.owner.emit(framed, .{ .sample = self.sample(.frame) });
        self.edge_count = 0;
        self.dirty = false;
        self.axes[8] = 0;
    }
    fn listener(_: *zwp.TabletToolV2, event: zwp.TabletToolV2.Event, self: *Tool) void {
        switch (event) {
            .type => |kind| {
                const value = @backingInt(kind.tool_type);
                if (value < 0x140 or value > 0x147) {
                    self.owner.failed = error.InvalidTabletDescription;
                    return;
                }
                self.kind = @intCast(value);
            },
            .hardware_serial, .hardware_id_wacom => {},
            .capability => |cap| self.capabilities |= switch (cap.capability) {
                .pressure => 1,
                .distance => 2,
                .tilt => 4,
                .rotation => 8,
                .slider => 16,
                .wheel => 32,
                else => 0, // Extra capabilities may be added by future protocol versions.
            },
            .done => {
                if (self.kind < 0x140 or self.kind > 0x147 or self.complete) {
                    self.owner.failed = error.InvalidTabletDescription;
                    return;
                }
                self.complete = true;
                self.owner.emit(null, .{ .device = self.device(true) });
            },
            .removed => {
                if (self.complete) self.owner.emit(null, .{ .device = self.device(false) });
                self.destroy();
            },
            .proximity_in => |entered| {
                if (self.target != null) {
                    self.owner.failed = error.TabletProximityAlreadyActive;
                    return;
                }
                self.target = if (entered.surface != null and entered.tablet != null)
                    self.owner.callbacks.target(self.owner.callbacks.context, entered.surface.?)
                else
                    null;
                self.tablet = entered.tablet;
                self.serial = entered.serial;
                if (self.owner.pointer_captured and self.kind != 0x145)
                    self.proxy.setCursor(self.serial, null, 0, 0);
                self.edge(.enter, 0, false);
            },
            .proximity_out => {
                self.edge(.leave, 0, false);
                for (self.edges[0..self.edge_count]) |*edge_value| edge_value.sealed = true;
                self.target = null;
                self.serial = 0;
                self.tablet = null;
                self.clearCursor();
                self.axes[8] = 0;
            },
            .down => self.edge(.down, 0, true),
            .up => self.edge(.up, 0, false),
            .motion => |motion| {
                self.axis(0, @floatCast(motion.x.toDouble()));
                self.axis(1, @floatCast(motion.y.toDouble()));
            },
            .pressure => |pressure| self.unit(2, pressure.pressure),
            .distance => |distance| self.unit(3, distance.distance),
            .tilt => |tilt| {
                const x = tilt.tilt_x.toDouble();
                const y = tilt.tilt_y.toDouble();
                if (@abs(x) > 90 or @abs(y) > 90) {
                    self.owner.failed = error.InvalidTabletAxis;
                    return;
                }
                self.axis(4, @floatCast(x));
                self.axis(5, @floatCast(y));
            },
            .rotation => |rotation| {
                const degrees = rotation.degrees.toDouble();
                if (degrees < 0 or degrees > 360) {
                    self.owner.failed = error.InvalidTabletAxis;
                    return;
                }
                self.axis(6, @floatCast(degrees));
            },
            .slider => |slider| {
                if (slider.position < -65535 or slider.position > 65535) {
                    self.owner.failed = error.InvalidTabletAxis;
                    return;
                }
                self.axis(7, @as(f32, @floatFromInt(slider.position)) / 65535.0);
            },
            .wheel => |wheel| self.axis(8, if (wheel.clicks != 0) @floatFromInt(wheel.clicks) else @floatCast(wheel.degrees.toDouble() / 15.0)),
            .button => |button| {
                if (button.button > std.math.maxInt(i32)) {
                    self.owner.failed = error.InvalidTabletButton;
                    return;
                }
                self.edge(.button, button.button, button.state == .pressed);
            },
            .frame => self.frame(),
        }
    }
};
// Pad controls are not exposed as stylus tools. Consume their bounded initial
// description, including every child new_id, then destroy the unused graph.
const Pad = struct {
    owner: *Self,
    proxy: *zwp.TabletPadV2,
    slot: usize,
    groups: [8]?*zwp.TabletPadGroupV2 = @splat(null),
    rings: [16]?*zwp.TabletPadRingV2 = @splat(null),
    strips: [16]?*zwp.TabletPadStripV2 = @splat(null),
    fn destroy(self: *Pad) void {
        for (self.rings) |slot| if (slot) |ring| ring.destroy();
        for (self.strips) |slot| if (slot) |strip| strip.destroy();
        for (self.groups) |slot| if (slot) |group| group.destroy();
        self.owner.pads[self.slot] = null;
        self.proxy.destroy();
        self.owner.gpa.destroy(self);
    }
    fn listener(_: *zwp.TabletPadV2, event: zwp.TabletPadV2.Event, self: *Pad) void {
        switch (event) {
            .group => |added| {
                const index = for (self.groups, 0..) |group, index| {
                    if (group == null) break index;
                } else {
                    added.pad_group.destroy();
                    self.owner.failed = error.TooManyTabletPadGroups;
                    return;
                };
                self.groups[index] = added.pad_group;
                added.pad_group.setListener(*Pad, groupListener, self);
            },
            .done, .removed => self.destroy(),
            else => {},
        }
    }
    fn groupListener(_: *zwp.TabletPadGroupV2, event: zwp.TabletPadGroupV2.Event, self: *Pad) void {
        switch (event) {
            .ring => |added| {
                const index = for (self.rings, 0..) |ring, index| {
                    if (ring == null) break index;
                } else {
                    added.ring.destroy();
                    self.owner.failed = error.TooManyTabletPadRings;
                    return;
                };
                self.rings[index] = added.ring;
                added.ring.setListener(*Pad, ringListener, self);
            },
            .strip => |added| {
                const index = for (self.strips, 0..) |strip, index| {
                    if (strip == null) break index;
                } else {
                    added.strip.destroy();
                    self.owner.failed = error.TooManyTabletPadStrips;
                    return;
                };
                self.strips[index] = added.strip;
                added.strip.setListener(*Pad, stripListener, self);
            },
            else => {},
        }
    }
    fn ringListener(_: *zwp.TabletPadRingV2, _: zwp.TabletPadRingV2.Event, _: *Pad) void {}
    fn stripListener(_: *zwp.TabletPadStripV2, _: zwp.TabletPadStripV2.Event, _: *Pad) void {}
};

const Recorder = struct {
    targets: [64]?*anyopaque = undefined,
    events: [64]Event = undefined,
    count: usize = 0,
    fn resolve(_: *anyopaque, _: *wl.Surface) ?*anyopaque {
        return null;
    }
    fn emit(context: *anyopaque, target: ?*anyopaque, event: Event) void {
        const self: *Recorder = @ptrCast(@alignCast(context));
        self.targets[self.count] = target;
        self.events[self.count] = event;
        self.count += 1;
    }
    fn callbacks(self: *Recorder) Callbacks {
        return .{ .context = self, .target = resolve, .emit = Recorder.emit };
    }
};

test "tablet frames publish final axes with ordered edges and one wheel delta" {
    var recorder: Recorder = .{};
    var generation: i32 = 1;
    var owner: Self = .{ .gpa = std.testing.allocator, .proxy = undefined, .callbacks = recorder.callbacks(), .generation = &generation };
    var target: u8 = 0;
    var tool: Tool = .{ .owner = &owner, .proxy = undefined, .slot = 0, .generation = 1, .complete = true, .target = &target };
    tool.edge(.enter, 0, false);
    tool.edge(.down, 0, true);
    tool.axis(0, 50);
    tool.axis(1, 60);
    tool.unit(2, 32768);
    tool.axis(8, 2);
    tool.frame();
    try std.testing.expectEqual(@as(usize, 4), recorder.count);
    try std.testing.expectEqual(Action.frame, recorder.events[3].sample.action);
    for (recorder.events[0..3], 0..) |event, index| {
        try std.testing.expectEqual(([_]Action{ .enter, .down, .move })[index], event.sample.action);
        try std.testing.expectEqual(@as(f32, 50), event.sample.axes[0]);
        try std.testing.expectEqual(@as(f32, 60), event.sample.axes[1]);
        try std.testing.expectApproxEqAbs(@as(f32, 0.5), event.sample.axes[2], 0.0001);
        try std.testing.expectEqual(@as(f32, if (index == 2) 2 else 0), event.sample.axes[8]);
        try std.testing.expectEqual(@as(?*anyopaque, &target), recorder.targets[index]);
    }
    tool.frame();
    try std.testing.expectEqual(@as(usize, 4), recorder.count);
}

test "tablet proximity handover retains old coordinates and detaches only its owner" {
    var recorder: Recorder = .{};
    var generation: i32 = 1;
    var owner: Self = .{ .gpa = std.testing.allocator, .proxy = undefined, .callbacks = recorder.callbacks(), .generation = &generation };
    var targets: [2]u8 = @splat(0);
    var tool: Tool = .{ .owner = &owner, .proxy = undefined, .slot = 0, .generation = 1, .complete = true, .target = &targets[0] };
    owner.tools[0] = &tool;
    tool.axis(0, 10);
    tool.edge(.leave, 0, false);
    for (tool.edges[0..tool.edge_count]) |*edge_value| edge_value.sealed = true;
    tool.target = &targets[1];
    tool.edge(.enter, 0, false);
    tool.axis(0, 90);
    tool.frame();
    // The old surface's run ends with its own frame before the new one's.
    try std.testing.expectEqual(@as(usize, 4), recorder.count);
    try std.testing.expectEqual(@as(f32, 10), recorder.events[0].sample.axes[0]);
    try std.testing.expectEqual(Action.frame, recorder.events[1].sample.action);
    try std.testing.expectEqual(@as(?*anyopaque, &targets[0]), recorder.targets[1]);
    try std.testing.expectEqual(@as(f32, 90), recorder.events[2].sample.axes[0]);
    try std.testing.expectEqual(Action.frame, recorder.events[3].sample.action);
    tool.edge(.down, 0, true);
    owner.detachTarget(&targets[0]);
    tool.frame();
    try std.testing.expectEqual(@as(usize, 6), recorder.count);
    tool.edge(.up, 0, false);
    owner.detachTarget(&targets[1]);
    tool.frame();
    try std.testing.expectEqual(@as(usize, 6), recorder.count);
}

test "tablet invalid pressure and frame overflow fail without partial delivery" {
    for ([_]bool{ false, true }) |overflow| {
        var recorder: Recorder = .{};
        var generation: i32 = 1;
        var owner: Self = .{ .gpa = std.testing.allocator, .proxy = undefined, .callbacks = recorder.callbacks(), .generation = &generation };
        var target: u8 = 0;
        var tool: Tool = .{ .owner = &owner, .proxy = undefined, .slot = 0, .generation = 1, .complete = true, .target = &target };
        if (overflow) {
            for (0..33) |_| tool.edge(.button, 0x14b, true);
        } else tool.unit(2, 65536);
        tool.frame();
        try std.testing.expectEqual(if (overflow) error.TabletFrameOverflow else error.InvalidTabletAxis, owner.failed.?);
        try std.testing.expectEqual(@as(usize, 0), recorder.count);
    }
}

test "tablet cursor ownership requires current tool generation and proximity serial" {
    var recorder: Recorder = .{};
    var generation: i32 = 2;
    var owner: Self = .{ .gpa = std.testing.allocator, .proxy = undefined, .callbacks = recorder.callbacks(), .generation = &generation };
    var targets: [2]u8 = @splat(0);
    var tool: Tool = .{ .owner = &owner, .proxy = undefined, .slot = 0, .generation = 2, .complete = true, .kind = 0x140, .serial = 12, .target = &targets[0] };
    owner.tools[0] = &tool;
    try std.testing.expectEqual(&tool, owner.findTool(&targets[0], 3, 2).?);
    try std.testing.expect(owner.findTool(&targets[0], 3, 1) == null);
    try std.testing.expect(owner.findTool(&targets[1], 3, 2) == null);
    try std.testing.expect(owner.findTool(&targets[0], 2, 2) == null);
    try std.testing.expect(owner.findTool(&targets[0], 19, 2) == null);
    owner.pointer_captured = true;
    try std.testing.expect(owner.findTool(&targets[0], 3, 2) == null);
    owner.pointer_captured = false;
    tool.serial = 0;
    try std.testing.expect(owner.findTool(&targets[0], 3, 2) == null);
}
