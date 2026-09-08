//! Session-owned Wayland presentation. AZKB workers submit bounded requests;
//! only the main thread touches protocol objects or native consumer ownership.
const std = @import("std");
const Io = std.Io;
const Queue = @import("surface_queue");
const TestPlacement = @import("test_input_placement.zig").Placement;
const Binder = opaque {};
const Class = opaque {};
const Parcel = opaque {};
const Death = opaque {};
extern "c" fn AIBinder_Class_define([*:0]const u8, ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque, ?*const fn (?*anyopaque) callconv(.c) void, ?*const fn (*Binder, u32, *const Parcel, *Parcel) callconv(.c) i32) *Class;
extern "c" fn AIBinder_new(*const Class, ?*anyopaque) *Binder;
extern "c" fn AIBinder_associateClass(*Binder, *const Class) bool;
extern "c" fn AIBinder_incStrong(*Binder) void;
extern "c" fn AIBinder_decStrong(*Binder) void;
extern "c" fn AIBinder_getCallingUid() i32;
extern "c" fn AIBinder_getCallingPid() i32;
extern "c" fn AIBinder_prepareTransaction(*Binder, *?*Parcel) i32;
extern "c" fn AIBinder_transact(*Binder, u32, *?*Parcel, *?*Parcel, u32) i32;
extern "c" fn AIBinder_DeathRecipient_new(*const fn (?*anyopaque) callconv(.c) void) *Death;
extern "c" fn AIBinder_DeathRecipient_delete(*Death) void;
extern "c" fn AIBinder_linkToDeath(*Binder, *Death, ?*anyopaque) i32;
extern "c" fn AIBinder_unlinkToDeath(*Binder, *Death, ?*anyopaque) i32;
extern "c" fn AServiceManager_addService(*Binder, [*:0]const u8) i32;
extern "c" fn AParcel_readInt32(*const Parcel, *i32) i32;
extern "c" fn AParcel_readInt64(*const Parcel, *i64) i32;
extern "c" fn AParcel_readInt32Array(*const Parcel, ?*anyopaque, *const fn (?*anyopaque, i32, *?[*]i32) callconv(.c) bool) i32;
extern "c" fn AParcel_getDataPosition(*const Parcel) i32;
extern "c" fn AParcel_getDataSize(*const Parcel) i32;
extern "c" fn AParcel_readString(*const Parcel, ?*anyopaque, *const fn (?*anyopaque, i32, ?*?[*]u8) callconv(.c) bool) i32;
extern "c" fn AParcel_readStrongBinder(*const Parcel, *?*Binder) i32;
extern "c" fn AParcel_readByteArray(*const Parcel, ?*anyopaque, *const fn (?*anyopaque, i32, ?*?[*]u8) callconv(.c) bool) i32;
extern "c" fn AParcel_writeByteArray(*Parcel, ?[*]const u8, i32) i32;
extern "c" fn AParcel_writeInt32(*Parcel, i32) i32;
extern "c" fn AParcel_writeInt64(*Parcel, i64) i32;
extern "c" fn AParcel_writeString(*Parcel, [*]const u8, i32) i32;
extern "c" fn AParcel_writeFloat(*Parcel, f32) i32;
extern "c" fn AParcel_writeFloatArray(*Parcel, [*]const f32, i32) i32;
extern "c" fn AParcel_writeInt32Array(*Parcel, [*]const i32, i32) i32;
extern "c" fn AParcel_delete(*Parcel) void;
extern "c" fn azk_graphics_live_buffers() u64;

const max_tasks = 64;
const Key = struct { id: i32, generation: i64 };
const Entry = struct {
    key: Key,
    window: *Queue.Window,
    queue: *Queue,
    events: *Binder,
    pending_queue: ?*Queue = null,
    pending_output_generation: i64 = 0,
    pending_configuration_generation: i64 = 0,
    output_generation: i64 = 0,
    configuration_generation: i64 = 0,
    presented_output_generation: i64 = 0,
    bounds: [6]i32 = @splat(0),
    has_content: bool = false,
    hold_output: bool = false,
    suspended: bool = false,
    test_frame_pending: bool = false,
    cursor_icon: ?i32 = null,
    fn inputGeometryReady(self: *const Entry) bool {
        return self.has_content and !self.suspended and !self.window.suspended and self.pending_queue == null and self.configuration_generation == self.window.configuration_generation and
            self.presented_output_generation == self.output_generation;
    }
    fn matchesInputGeometry(self: *const Entry, geometry: [8]i32, configuration_generation: i64) bool {
        return self.inputGeometryReady() and configuration_generation == self.configuration_generation and
            std.mem.eql(i32, geometry[0..6], &self.bounds) and
            geometry[6] == self.window.width and geometry[7] == self.window.height;
    }
    fn deinit(self: *Entry) void {
        self.window.deinit();
        self.queue.deinit();
        if (self.pending_queue) |queue| queue.deinit();
        AIBinder_decStrong(self.events);
    }
};
const Request = struct {
    code: u32,
    pid: i32,
    input: *const Parcel,
    output: *Parcel,
    status: i32 = -32,
    done: Io.Event = .unset,
};
const Server = struct {
    gpa: std.mem.Allocator,
    io: Io,
    session: *Queue.Session,
    wake: Io.File,
    mutex: Io.Mutex = .init,
    requests: [16]*Request = undefined,
    request_count: usize = 0,
    entries: [max_tasks]?Entry = @splat(null),
    owner: ?*Binder = null,
    owner_pid: i32 = 0,
    inspection_enabled: bool = false,
    submitted_round: i64 = 0,
    completed_round: i64 = 0,
    test_placement: ?TestPlacement = null,
    test_input_sequence: i64 = 0,
    outbound: ?struct {
        key: Key,
        token: u64,
        icon: *Queue.DragIcon,
        queue: *Queue,
        request: ?*Request,
        retire_deadline: ?i64,
        failed: bool = false,
    } = null,
    test_drag: ?struct {
        key: Key,
        configuration: i64,
        geometry: [8]i32,
        from: [2]u32,
        to: [2]u32,
        extent: [2]u32,
        seat_generation: i32,
        sequence: i64 = 0,
        step: u32 = 0,
        next_ms: i64,
    } = null,
    death: *Death,
    events_class: *Class,
    fn signal(self: *Server) void {
        const one: u64 = 1;
        self.wake.writeStreamingAll(self.io, std.mem.asBytes(&one)) catch {
            disconnected.store(true, .release);
        };
    }
    fn find(self: *Server, key: Key) ?*Entry {
        for (&self.entries) |*slot| if (slot.*) |*entry| {
            if (std.meta.eql(entry.key, key)) return entry;
        };
        return null;
    }
    fn drain(self: *Server, execute: bool) void {
        var pending: [16]*Request = undefined;
        self.mutex.lockUncancelable(self.io);
        const count = self.request_count;
        @memcpy(pending[0..count], self.requests[0..count]);
        self.request_count = 0;
        self.mutex.unlock(self.io);
        for (pending[0..count]) |request| {
            request.status = if (execute) self.handle(request) catch |err| status: {
                std.log.err("task presenter request {d}: {s}", .{ request.code, @errorName(err) });
                break :status -22;
            } else -32;
            if (self.outbound) |outbound| if (outbound.request == request) continue;
            request.done.set(self.io);
        }
    }
    fn connectSeat(self: *Server, request: *Request) !i32 {
        if (self.owner != null) return error.SeatAlreadyConnected;
        var remote: ?*Binder = null;
        try ok(AParcel_readStrongBinder(request.input, &remote));
        const callback = remote orelse return error.MissingCallback;
        defer AIBinder_decStrong(callback);
        if (!AIBinder_associateClass(callback, self.events_class)) return error.InvalidCallback;
        try end(request.input);
        const snapshot = try self.session.seatSnapshot();
        try ok(AIBinder_linkToDeath(callback, self.death, null));
        AIBinder_incStrong(callback);
        self.owner = callback;
        self.owner_pid = request.pid;
        if (self.session.tablets) |tablets| tablets.replayDevices();
        try ok(AParcel_writeInt32(request.output, 0));
        try ok(AParcel_writeInt32Array(request.output, &snapshot, snapshot.len));
        return 0;
    }
    fn dispatchEvents(self: *Server) !void {
        if (self.session.ordered_events.?.failed) return error.SeatEventQueueOverflow;
        for (self.session.ordered_events.?.take()) |item| {
            switch (item.event) {
                .native_drag_source => |source_event| {
                    if (self.owner) |callback| {
                        const kind: i32 = switch (source_event.value) {
                            .accepted => 0,
                            .copy => 1,
                            .dropped => 2,
                            .ended => 3,
                        };
                        const value = switch (source_event.value) {
                            .accepted, .copy, .ended => |v| v,
                            .dropped => true,
                        };
                        try sendGlobal(callback, 32, .{ @as(i64, @intCast(source_event.token)), kind, value });
                    }
                    continue;
                },
                .native_drag_issue => |issue| {
                    if (self.owner) |callback| try sendGlobal(callback, 31, .{@as([]const u8, @errorName(issue))});
                    continue;
                },
                .native_drag => |event| {
                    const callback = self.owner orelse continue;
                    if (event == .ended) {
                        try sendGlobal(callback, 30, .{ @as(i64, @intCast(event.ended.token)), event.ended.consumed });
                        continue;
                    }
                    const entry = for (&self.entries) |*slot| {
                        if (slot.*) |*candidate| if (candidate.window == item.window) break candidate;
                    } else null;
                    if (entry == null or !entry.?.inputGeometryReady()) {
                        if (self.session.clipboard) |clipboard| if (clipboard.native_drag) |*drag| drag.cancel();
                        continue;
                    }
                    switch (event) {
                        .entered => |value| try sendGlobal(callback, 26, .{ entry.?.key.id, entry.?.key.generation, @as(i64, @intCast(value.token)), @as(i64, @intCast(value.source_token)), value.x, value.y }),
                        .motion => |value| try sendGlobal(callback, 27, .{ @as(i64, @intCast(value.token)), value.x, value.y }),
                        .payload => |value| try sendPayload(callback, 28, @intCast(value.token), value.value),
                        .dropped => |token| try sendGlobal(callback, 29, .{@as(i64, @intCast(token))}),
                        .ended => unreachable,
                    }
                    continue;
                },
                .clipboard_available => |available| {
                    std.log.info("task clipboard available={any}", .{available});
                    if (self.owner) |callback| try sendGlobal(callback, 23, .{available});
                    continue;
                },
                .clipboard_changed => |change| {
                    if (self.owner) |callback| try sendPayload(callback, 24, change.revision, change.payload);
                    continue;
                },
                .clipboard_issue => |issue| {
                    std.log.warn("task clipboard: {s}", .{@errorName(issue)});
                    if (self.owner) |callback| try sendGlobal(callback, 25, .{@as([]const u8, @errorName(issue))});
                    continue;
                },
                .text_input_available => |available| {
                    std.log.info("task text input available={any}", .{available});
                    if (self.owner) |callback| try sendGlobal(callback, 20, .{available});
                    continue;
                },
                .seat_capabilities => |caps| {
                    if (self.owner) |callback| try sendGlobal(callback, 15, .{ caps.generation, caps.mask });
                    continue;
                },
                .tablet => |tablet| if (tablet == .device) {
                    const device = tablet.device;
                    if (self.owner) |callback| try sendGlobal(callback, 17, .{ device.id, device.generation, device.kind, device.capabilities, device.present });
                    continue;
                },
                .keyboard_layout => |layout| {
                    if (self.owner) |callback| try sendLayout(callback, layout);
                    continue;
                },
                .keyboard_modifiers => |meta| {
                    if (self.owner) |callback| try sendGlobal(callback, 11, .{ meta.effective, meta.persistent });
                    continue;
                },
                .keyboard_repeat_configuration => |config| {
                    if (self.owner) |callback| try sendGlobal(callback, 9, .{ config.rate, config.delay_ms });
                    continue;
                },
                else => {},
            }
            const entry = for (&self.entries) |*slot| {
                if (slot.*) |*candidate| if (candidate.window == item.window) break candidate;
            } else continue;
            // Retained pixels can be stretched while Android still uses the old
            // projection. Never deliver coordinates from that interval to it.
            if (!entry.inputGeometryReady()) switch (item.event) {
                .pointer_motion, .pointer_enter, .pointer_leave, .pointer_button, .pointer_relative, .pointer_axis, .touch => continue,
                else => {},
            };
            switch (item.event) {
                .text_input_focus => |focused| try send(entry, 21, .{focused}),
                .text_input_batch => |batch| try send(entry, 22, .{ batch.editor, batch.sequence, batch.fields, @as([]const u8, batch.preedit.slice()), batch.cursor_begin, batch.cursor_end, @as([]const u8, batch.commit.slice()), batch.before, batch.after }),
                .tablet => |tablet| {
                    const sample = tablet.sample;
                    if (sample.action == .cancel) {
                        try send(entry, 19, .{sample.id});
                    } else if (entry.inputGeometryReady()) {
                        try send(entry, 18, .{ sample.id, sample.generation, @backingInt(sample.action), sample.button, sample.pressed, @as([]const f32, &sample.axes) });
                    }
                },
                .frame_done => {
                    entry.queue.requestPresentation();
                    // A covered task may receive no frame callbacks. Progress
                    // belongs to actual callbacks, not an all-window barrier.
                    if (entry.test_frame_pending) self.completed_round += 1;
                    entry.test_frame_pending = false;
                },
                .configure => |size| {
                    // Release any paced frame from the obsolete geometry even
                    // when no further producer notification or callback arrives.
                    entry.queue.requestPresentation();
                    // Cancel before the Android bounds transaction, including
                    // active gestures whose physical release arrives later.
                    try send(entry, 4, .{ 4, 0, false, 0.0, 0.0 });
                    try send(entry, 8, .{});
                    try send(entry, 19, .{@as(i32, -1)});
                    try send(entry, 1, .{
                        size.width,      size.height,           size.buffer_width, size.buffer_height,
                        size.generation, @as(i64, size.serial),
                    });
                },
                .suspended_changed => |suspended| {
                    entry.suspended = suspended;
                    entry.queue.requestPresentation();
                    if (suspended) {
                        try send(entry, 4, .{ 4, 0, false, 0.0, 0.0 });
                        try send(entry, 8, .{});
                        try send(entry, 19, .{@as(i32, -1)});
                        try send(entry, 3, .{ false, @as([]const i32, &.{}) });
                        try entry.window.setPointerCapture(false);
                    }
                    // WCT owns Android visibility/lifecycle; publish it before
                    // restoring focus or coordinates on compositor resumption.
                    try send(entry, 16, .{suspended});
                    if (!suspended) {
                        if (self.session.keyboard_focus == entry.window)
                            try sendFocus(entry, self.session.keyboard_state);
                        if (entry.inputGeometryReady() and self.session.pointer_focus == entry.window)
                            try send(entry, 4, .{ 1, 0, false, entry.window.pointer_x, entry.window.pointer_y });
                    }
                },
                .close => try send(entry, 2, .{}),
                .keyboard_focus => |focused| if (!focused.active or (!entry.suspended and !entry.window.suspended)) try sendFocus(entry, focused),
                .keyboard_reset => try send(entry, 3, .{ false, @as([]const i32, &.{}) }),
                .pointer_motion => |p| try send(entry, 4, .{ 0, 0, false, p.x, p.y }),
                .pointer_enter => |p| try send(entry, 4, .{ 1, 0, false, p.x, p.y }),
                .pointer_leave => |p| try send(entry, 4, .{ 2, 0, false, p.x, p.y }),
                .pointer_button => |p| try send(entry, 4, .{ 3, p.button, p.pressed, p.x, p.y }),
                .pointer_cancel => try send(entry, 4, .{ 4, 0, false, 0.0, 0.0 }),
                .pointer_capture_changed => |capture| try send(entry, 12, .{ capture.enabled, capture.sequence }),
                .pointer_relative => |motion| try send(entry, 13, .{ motion.dx, motion.dy }),
                .pointer_axis => |axis| try send(entry, 5, .{ axis.x, axis.y, axis.horizontal, axis.vertical }),
                .key => |key| if (!entry.suspended and !entry.window.suspended) try send(entry, 6, .{ key.keycode, key.pressed, key.repeatable }),
                .touch => |touch| try send(entry, 7, .{ @backingInt(touch.action), touch.id, touch.x, touch.y, touch.major, touch.minor, touch.orientation }),
                .touch_cancel => try send(entry, 8, .{}),
                else => {},
            }
        }
    }
    fn observeTestFrames(self: *Server, request: *Request) !i32 {
        if (!self.inspection_enabled) return -1;
        const requested = try readInt(request.input);
        if (requested != 0 and requested != 1) return error.InvalidBoolean;
        try end(request.input);
        if (requested == 1) {
            for (&self.entries) |*slot| if (slot.*) |*entry| {
                // Do not assign an older, already committed callback to a new
                // inspection request. Each window retains at most one request.
                if (!entry.inputGeometryReady() or entry.test_frame_pending or
                    entry.window.frame_cb != null) continue;
                try entry.window.requestVsync();
                self.submitted_round += 1;
                entry.test_frame_pending = true;
                try entry.window.present();
            };
        }
        try ok(AParcel_writeInt32(request.output, 0)); // Status::ok
        try ok(AParcel_writeInt32(request.output, 2)); // long[] length
        try ok(AParcel_writeInt64(request.output, self.submitted_round));
        try ok(AParcel_writeInt64(request.output, self.completed_round));
        return 0;
    }
    fn testWindow(self: *Server, request: *Request) !i32 {
        if (!self.inspection_enabled) return -1;
        const id = try readInt(request.input);
        try end(request.input);
        const entry = for (&self.entries) |*slot| {
            if (slot.*) |*entry| if (entry.key.id == id) break entry;
        } else null;
        try ok(AParcel_writeInt32(request.output, 0));
        try ok(AParcel_writeInt32(request.output, if (entry != null) 16 else 0));
        if (entry) |value| {
            try ok(AParcel_writeInt64(request.output, value.key.generation));
            for (value.bounds) |number| try ok(AParcel_writeInt64(request.output, number));
            try ok(AParcel_writeInt64(request.output, value.window.width));
            try ok(AParcel_writeInt64(request.output, value.window.height));
            try ok(AParcel_writeInt64(request.output, @as(i64, @intFromBool(value.window.isFullscreen())) | (@as(i64, @intFromBool(value.window.maximized)) << 1) | (@as(i64, @intFromBool(value.window.suspended)) << 2)));
            for ([_]i64{ value.window.configuration_generation, value.configuration_generation, value.output_generation, value.presented_output_generation, value.queue.presented_width, value.queue.presented_height }) |number|
                try ok(AParcel_writeInt64(request.output, number));
        }
        return 0;
    }
    fn injectTestInput(self: *Server, request: *Request) !i64 {
        if (self.test_drag != null) return error.TestGestureAlreadyActive;
        const key: Key = .{ .id = try readInt(request.input), .generation = try readLong(request.input) };
        const configuration_generation = try readLong(request.input);
        var geometry: [8]i32 = undefined;
        if (try readInt(request.input) != geometry.len) return error.InvalidGeometry;
        for (&geometry) |*number| number.* = try readInt(request.input);
        const kind = try readInt(request.input);
        var points: [4]i32 = undefined;
        if (try readInt(request.input) != points.len) return error.InvalidPoints;
        for (&points) |*number| number.* = try readInt(request.input);
        var text: Text = .{};
        try text.read(request.input);
        try end(request.input);
        const entry = self.find(key) orelse return 0;
        if (!entry.matchesInputGeometry(geometry, configuration_generation)) return 0;
        try entry.window.prepareTestInput();
        // Seat roundtrips can receive a configure; reject its old geometry too.
        if (!entry.matchesInputGeometry(geometry, configuration_generation)) return 0;
        if (kind == 2 or kind == 4) try Queue.Window.validateTestText(text.slice());
        switch (kind) {
            0, 1, 4, 5 => {
                const placement = self.test_placement orelse return 0;
                const from = try placement.point(entry.bounds[0..4].*, .{ entry.window.width, entry.window.height }, points[0], points[1]);
                const extent = [2]u32{ @intCast(placement.output_width), @intCast(placement.output_height) };
                const local = [2]u32{ from[0] - @as(u32, @intCast(placement.left)), from[1] - @as(u32, @intCast(placement.top)) };
                if (kind != 1) try entry.window.testTapAtOutput(from, extent, local) else {
                    const to = try placement.point(entry.bounds[0..4].*, .{ entry.window.width, entry.window.height }, points[2], points[3]);
                    try entry.window.testPointerAtOutput(from, extent, local, true);
                    self.test_drag = .{
                        .key = key,
                        .configuration = configuration_generation,
                        .geometry = geometry,
                        .from = from,
                        .to = to,
                        .extent = extent,
                        .seat_generation = self.session.seat_generation,
                        .next_ms = Io.Clock.awake.now(self.io).toMilliseconds() + 300,
                    };
                }
            },
            2, 3 => {},
            else => return 0,
        }
        if (kind >= 4 and kind <= 5) {
            if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
            try self.dispatchEvents();
        }
        if (kind >= 2 and kind <= 5) {
            // Tap plus key/text is one validated operation. Its own focus
            // and selection events may legitimately change accessibility.
            if (self.session.keyboard_focus != entry.window or !entry.matchesInputGeometry(geometry, configuration_generation)) {
                if (kind >= 4) return error.InputTargetChanged;
                return 0;
            }
            if (kind == 2 or kind == 4) {
                const value = text.slice();
                // Shifted characters produce key and modifier callbacks.
                // Drain bounded groups well below the 4096-event seat cap,
                // preserving one final completion barrier for the action.
                var offset: usize = 0;
                while (offset < value.len) {
                    const limit = @min(offset + 128, value.len);
                    if (self.session.keyboard_focus != entry.window or !entry.matchesInputGeometry(geometry, configuration_generation))
                        return error.InputTargetChanged;
                    try entry.window.testType(value[offset..limit]);
                    if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
                    try self.dispatchEvents();
                    offset = limit;
                }
            } else try entry.window.testKey(text.slice());
        }
        // Drain the compositor's events before sending the barrier on the same
        // ordered Binder callback used by the entire seat. Shell receipt means
        // preceding events have reached InputDispatcher, not that the app drew.
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        try self.dispatchEvents();
        self.test_input_sequence = try std.math.add(i64, self.test_input_sequence, 1);
        if (self.test_drag) |*drag| drag.sequence = self.test_input_sequence else try send(entry, 14, .{self.test_input_sequence});
        return self.test_input_sequence;
    }

    fn tickTestDrag(self: *Server) !void {
        var drag = self.test_drag orelse return;
        const now = Io.Clock.awake.now(self.io).toMilliseconds();
        const entry = self.find(drag.key);
        const valid = self.session.seat_generation == drag.seat_generation and
            entry != null and entry.?.matchesInputGeometry(drag.geometry, drag.configuration);
        if (valid and now < drag.next_ms) return;
        if (valid and drag.step < 10) {
            drag.step += 1;
            var point: [2]u32 = undefined;
            for (&point, 0..) |*value, axis| {
                const start: i64 = drag.from[axis];
                const delta = @as(i64, drag.to[axis]) - start;
                value.* = @intCast(start + @divTrunc(delta * drag.step, 10));
            }
            try entry.?.window.testPointerAtOutput(point, drag.extent, null, false);
            drag.next_ms = now + 20;
            self.test_drag = drag;
            return;
        }
        self.test_drag = null;
        // Do not synthesize an edge on a replacement seat. A removed device
        // already cancels its compositor grab and Android contact.
        if (self.session.seat_generation == drag.seat_generation)
            try Queue.Window.releaseTestPointer(self.session);
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        try self.dispatchEvents();
        if (entry) |target| try send(target, 14, .{drag.sequence});
    }
    fn source(self: *Server) ?*Queue.Clipboard.DragSource {
        const outbound = self.outbound orelse return null;
        const clipboard = self.session.clipboard orelse return null;
        const value = clipboard.outbound_drag orelse return null;
        return if (value.token == outbound.token) value else null;
    }
    fn retireSourceOutput(self: *Server) void {
        const outbound = self.outbound orelse return;
        std.debug.assert(outbound.request == null);
        if (self.source()) |value| value.cancel();
        self.outbound = null;
        outbound.icon.deinit();
        outbound.queue.deinit();
    }
    fn writeStart(request: *Request, queue: ?*Queue, token: u64, state: i64) !void {
        try ok(AParcel_writeInt32(request.output, 0));
        try ok(AParcel_writeInt32(request.output, @intFromBool(queue != null)));
        if (queue) |value| try ok(value.writeSurface(request.output));
        try ok(AParcel_writeInt32(request.output, 2));
        try ok(AParcel_writeInt64(request.output, @intCast(token)));
        try ok(AParcel_writeInt64(request.output, state));
    }
    fn tickSource(self: *Server) !void {
        const outbound = if (self.outbound) |*value| value else return;
        const current = self.source();
        if (outbound.request) |request| {
            const state: Queue.Clipboard.DragSource.Start = if (current) |value| value.startState() else .unknown;
            if (state == .pending) return;
            const accepted = state == .accepted and current != null and current.?.active();
            outbound.request = null;
            request.status = status: {
                writeStart(request, if (accepted) outbound.queue else null, outbound.token, if (accepted) 1 else if (state == .rejected) 2 else 3) catch break :status -22;
                break :status 0;
            };
            const written = request.status == 0;
            request.done.set(self.io); // The Binder worker may now destroy Request.
            if (!accepted or !written) {
                self.retireSourceOutput();
                return;
            }
        }
        const now = Io.Clock.awake.now(self.io).toMilliseconds();
        if (current == null or !current.?.active()) {
            if (outbound.retire_deadline == null) outbound.retire_deadline = now + 6000;
        }
        if (outbound.retire_deadline) |deadline| if (now >= deadline) {
            // A dead/hung owner must not retain GPU leases forever. Destroying
            // the consumer abandons its imports; it never releases them as safe.
            self.retireSourceOutput();
            if (self.owner) |callback| try sendGlobal(callback, 31, .{@as([]const u8, "NativeDragIconRetirementTimedOut")});
        };
    }
    fn sourceRequest(self: *Server, request: *Request) !i32 {
        if (self.owner == null) return -1;
        if (request.code == 27 or request.code == 28) {
            const token = try readLong(request.input);
            try end(request.input);
            if (token <= 0) return error.InvalidDragSourceToken;
            var applied = false;
            if (self.outbound) |outbound| if (outbound.token == token and outbound.request == null) {
                if (request.code == 27) {
                    if (self.source()) |value| value.cancel();
                    applied = true;
                } else if (self.source() == null or !self.source().?.active()) {
                    self.retireSourceOutput();
                    applied = true;
                }
            };
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, @intFromBool(applied)));
            return 0;
        }
        const key: Key = .{ .id = try readInt(request.input), .generation = try readLong(request.input) };
        const configuration = try readLong(request.input);
        const kind = switch (try readInt(request.input)) {
            0 => Queue.DragKind.pointer,
            1 => Queue.DragKind.touch,
            else => return error.InvalidDragInputKind,
        };
        const id = try readInt(request.input);
        const entry = self.find(key);
        const valid = entry != null and entry.?.inputGeometryReady() and entry.?.configuration_generation == configuration;
        if (request.code == 25) {
            try end(request.input);
            const generation = if (valid and self.outbound == null) self.session.dragClaim(entry.?.window, kind, id) else null;
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt64(request.output, @intCast(generation orelse 0)));
            return 0;
        }
        const generation = try readLong(request.input);
        var geometry: [6]i32 = undefined;
        for (&geometry) |*value| value.* = try readInt(request.input);
        const width = geometry[0];
        const height = geometry[1];
        const pixel_width = geometry[4];
        const pixel_height = geometry[5];
        if (generation <= 0 or !dimension(width) or !dimension(height) or !dimension(pixel_width) or !dimension(pixel_height) or
            geometry[2] < 0 or geometry[2] >= width or geometry[3] < 0 or geometry[3] >= height) return error.InvalidDragIcon;
        var text: ClipboardBytes = .{ .gpa = self.gpa };
        defer text.deinit();
        var html: ClipboardBytes = .{ .gpa = self.gpa };
        defer html.deinit();
        var uris: ClipboardBytes = .{ .gpa = self.gpa };
        defer uris.deinit();
        try ok(AParcel_readByteArray(request.input, &text, ClipboardBytes.allocate));
        try ok(AParcel_readByteArray(request.input, &html, ClipboardBytes.allocate));
        try ok(AParcel_readByteArray(request.input, &uris, ClipboardBytes.allocate));
        if (try readInt(request.input) != 2) return error.InvalidDragResultArray;
        try end(request.input);
        if (!valid or self.outbound != null or self.session.dragClaim(entry.?.window, kind, id) != @as(u64, @intCast(generation))) {
            try writeStart(request, null, 0, 2);
            return 0;
        }
        const clipboard = self.session.clipboard orelse {
            try writeStart(request, null, 0, 2);
            return 0;
        };
        if (clipboard.manager.getVersion() < 3 or clipboard.device.getVersion() < 3) {
            try writeStart(request, null, 0, 2);
            return 0;
        }
        const icon = try Queue.DragIcon.init(self.session, width, height, .{ geometry[2], geometry[3] });
        errdefer icon.deinit();
        const queue = try Queue.init(self.gpa, self.io, @intCast(pixel_width), @intCast(pixel_height));
        errdefer queue.deinit();
        queue.expected_size = .{ @intCast(pixel_width), @intCast(pixel_height) };
        queue.pace_frames = true;
        const payload = if (text.bytes != null or html.bytes != null or uris.bytes != null)
            try Queue.Clipboard.Payload.initAll(self.gpa, text.bytes, html.bytes, uris.bytes)
        else
            null;
        const current = self.session.startDrag(entry.?.window, kind, id, @intCast(generation), icon.surface, payload, self.session.nativeSourceHooks()) catch |failure| {
            // The validated generation was consumed. Allocation/transport errors
            // after admission cannot prove that the compositor kept the grab.
            std.log.warn("native drag start uncertain: {s}", .{@errorName(failure)});
            try writeStart(request, null, 0, 3);
            queue.deinit();
            icon.deinit();
            return 0;
        };
        self.outbound = .{ .key = key, .token = current.token, .icon = icon, .queue = queue, .request = request, .retire_deadline = Io.Clock.awake.now(self.io).toMilliseconds() + 6000 };
        return 0; // drain keeps this request alive while protocol dispatch runs.
    }
    fn handle(self: *Server, request: *Request) !i32 {
        // This read/test-pacing API is available to the system controller; only
        // the system-server owner may mutate task identities and geometry.
        if (request.code == 10) return self.connectSeat(request);
        if (request.code == 6) return self.observeTestFrames(request);
        if (request.code == 7) return self.testWindow(request);
        if (request.code == 14) {
            if (!self.inspection_enabled) return -1;
            try end(request.input);
            var tasks: i64 = 0;
            var queues: i64 = 0;
            var imports: i64 = 0;
            for (self.entries) |slot| if (slot) |entry| {
                tasks += 1;
                queues += 1 + @as(i64, @intFromBool(entry.pending_queue != null));
                imports += @intCast(entry.window.ui_dmabuf_buffers.items.len);
            };
            var directory = try Io.Dir.cwd().openDir(self.io, "/proc/self/fd", .{ .iterate = true });
            defer directory.close(self.io);
            var iterator = directory.iterate();
            var descriptors: i64 = 0;
            while (try iterator.next(self.io)) |_| {
                descriptors += 1;
                if (descriptors > 65536) return error.DescriptorAccountingLimit;
            }
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, 5));
            for ([_]i64{ tasks, queues, imports, @intCast(azk_graphics_live_buffers()), descriptors }) |value|
                try ok(AParcel_writeInt64(request.output, value));
            return 0;
        }
        if (request.code == 13) {
            if (!self.inspection_enabled) return -1;
            const key: Key = .{ .id = try readInt(request.input), .generation = try readLong(request.input) };
            const hold = try readInt(request.input);
            try end(request.input);
            if (hold != 0 and hold != 1) return error.InvalidBoolean;
            const entry = self.find(key) orelse return error.StaleTask;
            entry.hold_output = hold != 0;
            if (!entry.hold_output) entry.queue.requestPresentation();
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, 1));
            return 0;
        }
        if (request.code == 12) {
            if (!self.inspection_enabled) return -1;
            const key: Key = .{ .id = try readInt(request.input), .generation = try readLong(request.input) };
            const state = try readInt(request.input);
            try end(request.input);
            const entry = self.find(key) orelse return error.StaleTask;
            if (state < 0 or state > 2) return error.InvalidWindowState;
            entry.window.setFullscreen(state == 1);
            if (state == 2) entry.window.toplevel.setMaximized() else entry.window.toplevel.unsetMaximized();
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, 1));
            return 0;
        }
        if (request.code == 8) {
            if (!self.inspection_enabled) return -1;
            const injected = self.injectTestInput(request) catch |failure| {
                std.log.warn("test input failed; completion unknown: {s}", .{@errorName(failure)});
                try ok(AParcel_writeInt32(request.output, 0));
                try ok(AParcel_writeInt64(request.output, -1));
                return 0;
            };
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt64(request.output, injected));
            return 0;
        }
        if (self.owner_pid != 0 and self.owner_pid != request.pid) return -1;
        if (request.code >= 25 and request.code <= 28) return self.sourceRequest(request);
        if (request.code == 19 or request.code == 21) {
            try end(request.input);
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, @intFromBool(if (request.code == 19) self.session.text_input != null else self.session.clipboard != null)));
            return 0;
        }
        if (request.code == 23 or request.code == 24) {
            if (self.owner == null) return -1;
            const token = try readLong(request.input);
            const accepted = try readInt(request.input);
            try end(request.input);
            if (token <= 0 or (accepted != 0 and accepted != 1)) return error.InvalidDragResponse;
            var applied = false;
            if (self.session.clipboard) |clipboard| if (clipboard.native_drag) |*drag| {
                applied = if (request.code == 23) drag.accept(@intCast(token), accepted != 0) else clipboard.finishNativeDrag(@intCast(token), accepted != 0);
            };
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, @intFromBool(applied)));
            return 0;
        }
        if (request.code == 22) {
            const revision = try readLong(request.input);
            if (revision <= 0) return error.InvalidClipboardRevision;
            var text: ClipboardBytes = .{ .gpa = self.gpa };
            defer text.deinit();
            var html: ClipboardBytes = .{ .gpa = self.gpa };
            defer html.deinit();
            var uris: ClipboardBytes = .{ .gpa = self.gpa };
            defer uris.deinit();
            try ok(AParcel_readByteArray(request.input, &text, ClipboardBytes.allocate));
            try ok(AParcel_readByteArray(request.input, &html, ClipboardBytes.allocate));
            try ok(AParcel_readByteArray(request.input, &uris, ClipboardBytes.allocate));
            try end(request.input);
            if (text.bytes == null and html.bytes != null) return error.HtmlWithoutClipboardText;
            if (self.session.clipboard) |clipboard| {
                const payload = if (text.bytes != null or html.bytes != null or uris.bytes != null) try Queue.Clipboard.Payload.initAll(self.gpa, text.bytes, html.bytes, uris.bytes) else null;
                try clipboard.set(revision, payload);
            }
            try ok(AParcel_writeInt32(request.output, 0));
            try ok(AParcel_writeInt32(request.output, @intFromBool(self.session.clipboard != null)));
            return 0;
        }
        const input = request.input;
        const output = request.output;
        const key: Key = .{ .id = try readInt(input), .generation = try readLong(input) };
        if (key.id < 0 or key.generation <= 0) return error.InvalidTask;
        switch (request.code) {
            20 => {
                const editor = try readLong(input);
                const applied = try readLong(input);
                const enabled = try readInt(input);
                if (enabled != 0 and enabled != 1) return error.InvalidBoolean;
                var text: InputText = .{};
                try ok(AParcel_readString(input, &text, InputText.allocate));
                var state: Queue.TextInput.State = .{
                    .editor = editor,
                    .applied = applied,
                    .enabled = enabled == 1,
                    .surrounding = if (text.len) |len| try Queue.TextInput.Text.init(text.bytes[0..len]) else null,
                    .cursor = try readInt(input),
                    .anchor = try readInt(input),
                    .hint = @bitCast(try readInt(input)),
                    .purpose = @bitCast(try readInt(input)),
                };
                const external_change = try readInt(input);
                if (external_change != 0 and external_change != 1) return error.InvalidBoolean;
                state.external_change = external_change == 1;
                const bounds_count = try readInt(input);
                if (bounds_count != 0 and bounds_count != 4) return error.InvalidCursorBounds;
                if (bounds_count == 4) state.rectangle = .{ try readInt(input), try readInt(input), try readInt(input), try readInt(input) };
                try end(input);
                try state.validate();
                const entry = self.find(key) orelse return error.StaleTask;
                var accepted = false;
                if (self.session.text_input) |text_input| if (text_input.surface) |surface| {
                    if (surface == entry.window.surface or surface == entry.window.ui_surface)
                        accepted = try text_input.update(surface, &state);
                };
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, @intFromBool(accepted)));
            },
            18 => {
                const mode = try readInt(input);
                try end(input);
                if (mode < 0 or mode > 2) return error.InvalidWindowState;
                const entry = self.find(key) orelse return error.StaleTask;
                if (mode == 2) entry.window.toggleFullscreen() else entry.window.setFullscreen(mode == 1);
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, 1));
            },
            1 => {
                const width = try readInt(input);
                const height = try readInt(input);
                if (!dimension(width) or !dimension(height)) return error.InvalidSize;
                var app_id: Text = .{};
                var title: Text = .{};
                try app_id.read(input);
                try title.read(input);
                var events: ?*Binder = null;
                try ok(AParcel_readStrongBinder(input, &events));
                const callback = events orelse return error.MissingCallback;
                errdefer AIBinder_decStrong(callback);
                if (!AIBinder_associateClass(callback, self.events_class)) return error.InvalidCallback;
                if (try readInt(input) != 6) return error.InvalidConfigurationArray;
                try end(input);
                var free: ?*?Entry = null;
                for (&self.entries) |*slot| {
                    if (slot.*) |entry| {
                        if (entry.key.id == key.id) return error.DuplicateTask;
                    } else if (free == null) {
                        free = slot;
                    }
                }
                const slot = free orelse return error.TaskCapacity;
                const window = try self.session.createWindowWithAppId(width, height, title.slice(), app_id.slice(), false);
                errdefer window.deinit();
                const queue = try Queue.init(self.gpa, self.io, @intCast(window.width), @intCast(window.height));
                queue.pace_frames = true;
                errdefer queue.deinit();
                // Hold the first callback independently of its window. Its
                // process death terminates this owner generation and all queues.
                if (self.owner == null) {
                    try ok(AIBinder_linkToDeath(callback, self.death, null));
                    AIBinder_incStrong(callback);
                    self.owner = callback;
                    self.owner_pid = request.pid;
                    if (self.session.tablets) |tablets| tablets.replayDevices();
                }
                try ok(AParcel_writeInt32(output, 0)); // Status::ok
                try ok(AParcel_writeInt32(output, 1)); // non-null Surface
                try ok(queue.writeSurface(output));
                try ok(AParcel_writeInt32(output, 6)); // out long[] snapshot
                for ([_]i64{ window.width, window.height, window.bufferWidth(), window.bufferHeight(), window.configuration_generation, window.configure_serial }) |value|
                    try ok(AParcel_writeInt64(output, value));
                slot.* = .{ .key = key, .window = window, .queue = queue, .events = callback };
            },
            2 => {
                try end(input);
                for (&self.entries) |*slot| if (slot.*) |*entry| {
                    if (std.meta.eql(entry.key, key)) {
                        entry.deinit();
                        slot.* = null;
                        break;
                    }
                };
                try ok(AParcel_writeInt32(output, 0));
            },
            3 => {
                var app_id: Text = .{};
                var title: Text = .{};
                try app_id.read(input);
                try title.read(input);
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                entry.window.toplevel.setAppId(app_id.slice());
                entry.window.toplevel.setTitle(title.slice());
                try ok(AParcel_writeInt32(output, 0));
            },
            4 => {
                var bounds: [6]i32 = undefined;
                for (&bounds) |*value| value.* = try readInt(input);
                const output_generation = try readLong(input);
                const configuration_generation = try readLong(input);
                try end(input);
                if (bounds[0] < 0 or bounds[1] < 0 or !dimension(bounds[2]) or !dimension(bounds[3]) or
                    !dimension(bounds[4]) or !dimension(bounds[5]) or
                    @as(i64, bounds[0]) + bounds[2] > 16384 or @as(i64, bounds[1]) + bounds[3] > 16384)
                    return error.InvalidBounds;
                const entry = self.find(key) orelse return error.StaleTask;
                const pending = entry.pending_queue orelse return error.OutputNotPrepared;
                if (entry.pending_output_generation != output_generation or
                    entry.pending_configuration_generation != configuration_generation)
                    return error.StaleOutputConfiguration;
                // SF's transaction has disconnected the old producer. Keep its
                // compositor image until a new buffer arrives, but abandon its
                // queue so no late same-sized frame can enter this generation.
                entry.window.retireUiProducer();
                entry.queue.deinit();
                entry.queue = pending;
                entry.pending_queue = null;
                entry.output_generation = output_generation;
                entry.configuration_generation = configuration_generation;
                entry.has_content = false;
                entry.bounds = bounds;
                // Pending producers can finish a fence before the transaction
                // commits. Their eventfd was not watched; wake the active loop.
                entry.queue.requestPresentation();
                try ok(AParcel_writeInt32(output, 0));
            },
            11 => {
                const output_generation = try readLong(input);
                const configuration_generation = try readLong(input);
                const width = try readInt(input);
                const height = try readInt(input);
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                if (!dimension(width) or !dimension(height) or output_generation <= entry.output_generation or
                    configuration_generation <= 0 or configuration_generation > entry.window.configuration_generation)
                    return error.InvalidOutputConfiguration;
                if (entry.pending_queue != null) return error.OutputConfigurationPending;
                const queue = try Queue.init(self.gpa, self.io, @intCast(width), @intCast(height));
                queue.pace_frames = true;
                errdefer queue.deinit();
                queue.expected_size = .{ @intCast(width), @intCast(height) };
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, 1)); // non-null stock Surface
                try ok(queue.writeSurface(output));
                // Preparation also covers Android-initiated relocation with
                // unchanged compositor geometry. Cancel before WCT changes its
                // input windows, and hold input until this producer presents.
                try send(entry, 4, .{ 4, 0, false, 0.0, 0.0 });
                try send(entry, 8, .{});
                try send(entry, 19, .{@as(i32, -1)});
                entry.pending_queue = queue;
                entry.queue.requestPresentation();
                entry.pending_output_generation = output_generation;
                entry.pending_configuration_generation = configuration_generation;
            },
            5 => {
                const enabled_value = try readInt(input);
                if (enabled_value != 0 and enabled_value != 1) return error.InvalidBoolean;
                const sequence = try readInt(input);
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                const supported = supported: {
                    entry.window.requestPointerCapture(enabled_value == 1, sequence) catch |err| {
                        std.log.warn("task {d} pointer capture unavailable: {s}", .{ key.id, @errorName(err) });
                        break :supported false;
                    };
                    break :supported true;
                };
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, @intFromBool(supported)));
            },
            9, 16 => {
                const device = if (request.code == 16) try readInt(input) else 0;
                const device_generation = if (request.code == 16) try readInt(input) else 0;
                const icon = try readInt(input);
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                const applied = entry.inputGeometryReady() and (if (request.code == 16)
                    entry.window.setTabletPointerIcon(device, device_generation, icon)
                else
                    entry.window.setPointerIcon(icon));
                if (applied and entry.cursor_icon != icon) {
                    entry.cursor_icon = icon;
                    if (self.inspection_enabled) std.log.info("task cursor task={d} type={d} applied=true", .{ key.id, icon });
                }
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, @intFromBool(applied)));
            },
            15, 17 => {
                const device = if (request.code == 17) try readInt(input) else 0;
                const device_generation = if (request.code == 17) try readInt(input) else 0;
                const width = try readInt(input);
                const height = try readInt(input);
                const hotspot_x = try readInt(input);
                const hotspot_y = try readInt(input);
                const frame_ms = try readInt(input);
                var pixels: CursorPixels = .{ .gpa = self.gpa };
                defer if (pixels.values) |values| self.gpa.free(values);
                try ok(AParcel_readInt32Array(input, &pixels, CursorPixels.allocate));
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                const values = pixels.values orelse return error.InvalidCursorImage;
                const applied = entry.inputGeometryReady() and (if (request.code == 17)
                    try entry.window.setTabletCursorImage(device, device_generation, width, height, hotspot_x, hotspot_y, frame_ms, values)
                else
                    try entry.window.setCursorImage(width, height, hotspot_x, hotspot_y, frame_ms, values));
                if (applied and self.inspection_enabled) std.log.info("task bitmap cursor task={d} size={d}x{d} frames={d} hotspot={d},{d}", .{ key.id, width, height, values.len / @as(usize, @intCast(width * height)), hotspot_x, hotspot_y });
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, @intFromBool(applied)));
            },
            else => return -38,
        }
        return 0;
    }
};
// Keep callback state at a stable address until process exit. Once accepting is
// false, new Binder calls touch only atomics; shutdown drains admitted requests
// before closing any std.Io resources.
var server: Server = undefined;
var accepting: std.atomic.Value(bool) = .init(false);
var callbacks: std.atomic.Value(u32) = .init(0);
var disconnected: std.atomic.Value(bool) = .init(false);
fn onDeath(_: ?*anyopaque) callconv(.c) void {
    _ = callbacks.fetchAdd(1, .acq_rel);
    defer _ = callbacks.fetchSub(1, .acq_rel);
    if (!accepting.load(.acquire)) return;
    disconnected.store(true, .release);
    server.signal();
}
fn transact(_: *Binder, code: u32, input: *const Parcel, output: *Parcel) callconv(.c) i32 {
    _ = callbacks.fetchAdd(1, .acq_rel);
    defer _ = callbacks.fetchSub(1, .acq_rel);
    if (!accepting.load(.acquire)) return -32;
    if (AIBinder_getCallingUid() != 1000) return -1;
    if (code < 1 or code > 28) return -38;
    const io = server.io;
    var request: Request = .{ .code = code, .pid = AIBinder_getCallingPid(), .input = input, .output = output };
    server.mutex.lockUncancelable(io);
    if (!accepting.load(.acquire) or server.request_count == server.requests.len) {
        server.mutex.unlock(io);
        return -11;
    }
    server.requests[server.request_count] = &request;
    server.request_count += 1;
    server.signal();
    server.mutex.unlock(io);
    request.done.waitUncancelable(io);
    return request.status;
}
fn ok(status: i32) !void {
    if (status != 0) return error.BinderOperationFailed;
}
const CursorPixels = struct {
    gpa: std.mem.Allocator,
    values: ?[]i32 = null,
    fn allocate(context: ?*anyopaque, count: i32, destination: *?[*]i32) callconv(.c) bool {
        const self: *CursorPixels = @ptrCast(@alignCast(context.?));
        if (count < 1 or count > 128 * 1024 or self.values != null) return false;
        self.values = self.gpa.alloc(i32, @intCast(count)) catch return false;
        destination.* = self.values.?.ptr;
        return true;
    }
};
fn readInt(parcel: *const Parcel) !i32 {
    var value: i32 = undefined;
    try ok(AParcel_readInt32(parcel, &value));
    return value;
}
fn readLong(parcel: *const Parcel) !i64 {
    var value: i64 = undefined;
    try ok(AParcel_readInt64(parcel, &value));
    return value;
}
fn dimension(value: i32) bool {
    return value > 0 and value <= 16384;
}
fn end(parcel: *const Parcel) !void {
    if (AParcel_getDataPosition(parcel) != AParcel_getDataSize(parcel)) return error.TrailingData;
}
const Text = struct {
    bytes: [1025]u8 = undefined,
    len: usize = 0,
    fn allocate(raw: ?*anyopaque, length: i32, out: ?*?[*]u8) callconv(.c) bool {
        const self: *Text = @ptrCast(@alignCast(raw.?));
        if (length < 1 or length > self.bytes.len) return false;
        self.len = @intCast(length - 1);
        (out orelse return false).* = &self.bytes;
        return true;
    }
    fn read(self: *Text, parcel: *const Parcel) !void {
        try ok(AParcel_readString(parcel, self, allocate));
        if (self.bytes[self.len] != 0 or std.mem.indexOfScalar(u8, self.bytes[0..self.len], 0) != null)
            return error.InvalidText;
    }
    fn slice(self: *const Text) [:0]const u8 {
        return self.bytes[0..self.len :0];
    }
};
const InputText = struct {
    bytes: [Queue.TextInput.max_bytes + 1]u8 = undefined,
    len: ?usize = null,
    fn allocate(raw: ?*anyopaque, length: i32, out: ?*?[*]u8) callconv(.c) bool {
        const self: *InputText = @ptrCast(@alignCast(raw.?));
        if (length == -1) {
            self.len = null;
            return true;
        }
        if (length < 1 or length > self.bytes.len) return false;
        self.len = @intCast(length - 1);
        (out orelse return false).* = &self.bytes;
        return true;
    }
};
fn sendLayout(callback: *Binder, layout: Queue.KeyboardLayout) !void {
    const characters = try layout.characters();
    const symbols = try layout.symbols();
    var input: ?*Parcel = null;
    try ok(AIBinder_prepareTransaction(callback, &input));
    defer if (input) |parcel| AParcel_delete(parcel);
    try ok(AParcel_writeInt32(input.?, layout.generation));
    try ok(AParcel_writeInt32Array(input.?, &characters, characters.len));
    try ok(AParcel_writeInt32Array(input.?, &symbols, symbols.len));
    var output: ?*Parcel = null;
    defer if (output) |parcel| AParcel_delete(parcel);
    try ok(AIBinder_transact(callback, 10, &input, &output, 1));
}

fn sendFocus(entry: *Entry, focus: anytype) !void {
    var held: [768]i32 = undefined;
    var count: usize = 0;
    for (0..768) |scan| {
        if (focus.held[scan / 64] & (@as(u64, 1) << @intCast(scan % 64)) == 0) continue;
        held[count] = @intCast(scan);
        count += 1;
    }
    try send(entry, 3, .{ focus.active, @as([]const i32, held[0..count]) });
}

fn send(entry: *Entry, code: u32, values: anytype) !void {
    var input: ?*Parcel = null;
    var output: ?*Parcel = null;
    try ok(AIBinder_prepareTransaction(entry.events, &input));
    defer if (input) |parcel| AParcel_delete(parcel);
    try ok(AParcel_writeInt32(input.?, entry.key.id));
    try ok(AParcel_writeInt64(input.?, entry.key.generation));
    defer if (output) |parcel| AParcel_delete(parcel);
    try writeValues(input.?, values);
    try ok(AIBinder_transact(entry.events, code, &input, &output, 1));
}
fn sendGlobal(callback: *Binder, code: u32, values: anytype) !void {
    var input: ?*Parcel = null;
    var output: ?*Parcel = null;
    try ok(AIBinder_prepareTransaction(callback, &input));
    defer if (input) |parcel| AParcel_delete(parcel);
    defer if (output) |parcel| AParcel_delete(parcel);
    try writeValues(input.?, values);
    try ok(AIBinder_transact(callback, code, &input, &output, 1));
}
const ClipboardBytes = struct {
    gpa: std.mem.Allocator,
    bytes: ?[]u8 = null,
    fn deinit(self: *ClipboardBytes) void {
        if (self.bytes) |bytes| self.gpa.free(bytes);
    }
    fn allocate(context: ?*anyopaque, length: i32, out: ?*?[*]u8) callconv(.c) bool {
        const self: *ClipboardBytes = @ptrCast(@alignCast(context.?));
        if (length < -1 or length > 256 * 1024 or self.bytes != null) return false;
        if (length == -1) return true;
        const destination = out orelse return false;
        const bytes = self.gpa.alloc(u8, @intCast(length)) catch return false;
        self.bytes = bytes;
        destination.* = bytes.ptr;
        return true;
    }
};
fn sendPayload(callback: *Binder, code: u32, revision: i64, payload: ?*Queue.Clipboard.Payload) !void {
    var input: ?*Parcel = null;
    var output: ?*Parcel = null;
    try ok(AIBinder_prepareTransaction(callback, &input));
    defer if (input) |parcel| AParcel_delete(parcel);
    defer if (output) |parcel| AParcel_delete(parcel);
    try ok(AParcel_writeInt64(input.?, revision));
    const text: ?[]const u8 = if (payload) |value| value.text else null;
    const html: ?[]const u8 = if (payload) |value| value.html else null;
    const uris: ?[]const u8 = if (payload) |value| value.uris else null;
    for ([_]?[]const u8{ text, html, uris }) |bytes|
        try ok(AParcel_writeByteArray(input.?, if (bytes) |value| value.ptr else null, if (bytes) |value| @intCast(value.len) else -1));
    try ok(AIBinder_transact(callback, code, &input, &output, 1));
}
fn writeValues(parcel: *Parcel, values: anytype) !void {
    inline for (values) |value| switch (@typeInfo(@TypeOf(value))) {
        .bool => try ok(AParcel_writeInt32(parcel, @intFromBool(value))),
        .int, .comptime_int => if (@TypeOf(value) == i64)
            try ok(AParcel_writeInt64(parcel, value))
        else
            try ok(AParcel_writeInt32(parcel, @intCast(value))),
        .float, .comptime_float => try ok(AParcel_writeFloat(parcel, @floatCast(value))),
        .pointer => |pointer| {
            if (pointer.size != .slice) @compileError("Unsupported task callback array");
            if (pointer.child == i32) try ok(AParcel_writeInt32Array(parcel, value.ptr, @intCast(value.len))) else if (pointer.child == f32) try ok(AParcel_writeFloatArray(parcel, value.ptr, @intCast(value.len))) else if (pointer.child == u8) try ok(AParcel_writeString(parcel, value.ptr, @intCast(value.len))) else @compileError("Unsupported task callback array element");
        },
        else => @compileError("Unsupported task callback field"),
    };
}
pub fn main(init: std.process.Init) !void {
    try run(init);
    if (azk_graphics_live_buffers() != 0) return error.LeakedGraphicsBuffers;
    // The supervisor may have stopped draining stdout before orderly shutdown.
    // Resource verification belongs on stderr with the other native diagnostics.
    try Io.File.stderr().writeStreamingAll(init.io, "task presenter released all buffers\n");
}

fn run(init: std.process.Init) !void {
    const io = init.io;
    const raw = std.os.linux.eventfd(0, std.os.linux.EFD.CLOEXEC | std.os.linux.EFD.NONBLOCK);
    if (std.os.linux.errno(raw) != .SUCCESS) return error.EventfdFailed;
    const wake: Io.File = .{ .handle = @intCast(raw), .flags = .{ .nonblocking = true } };
    defer wake.close(io);
    const inspection_enabled = init.minimal.environ.getPosix("AZK_TEST_SOCKET") != null or
        std.mem.eql(u8, init.minimal.environ.getPosix("AZK_TEST_INSPECTION") orelse "", "1");
    const pointer_version: u32 = if (inspection_enabled)
        try std.fmt.parseInt(u32, init.minimal.environ.getPosix("AZK_TEST_VIRTUAL_POINTER_VERSION") orelse "2", 10)
    else
        2;
    const cursor_shape = !inspection_enabled or !std.mem.eql(u8, init.minimal.environ.getPosix("AZK_TEST_CURSOR_SHAPE") orelse "1", "0");
    const session = try Queue.Session.initWithOptions(init.gpa, io, .{
        .clipboard = true,
        .virtual_pointer_max_version = pointer_version,
        .cursor_shape = cursor_shape,
        .xdg_shell_max_version = if (inspection_enabled)
            try std.fmt.parseInt(u32, init.minimal.environ.getPosix("AZK_TEST_XDG_SHELL_VERSION") orelse "6", 10)
        else
            6,
    });
    if (inspection_enabled) {
        const version = if (session.virtual_pointer_manager) |manager| manager.getVersion() else 0;
        std.log.info("task virtual pointer protocol version={d}", .{version});
        std.log.info("task cursor shape protocol available={any}", .{session.cursor_shape_manager != null});
        std.log.info("task xdg shell protocol version={d}", .{session.wm_base.?.getVersion()});
    }
    session.ordered_events = .{};
    session.enableNativeDrag();
    session.generate_key_repeats = false; // Stock InputDispatcher owns the timer.
    defer session.deinit();
    const death = AIBinder_DeathRecipient_new(onDeath);
    defer AIBinder_DeathRecipient_delete(death);
    server = .{ .gpa = init.gpa, .io = io, .session = session, .wake = wake, .death = death, .events_class = AIBinder_Class_define("dev.artzig.window.IWaylandTaskEvents", null, null, null) };
    server.inspection_enabled = inspection_enabled;
    if (server.inspection_enabled) if (init.minimal.environ.getPosix("AZK_TEST_WINDOW_PLACEMENT")) |placement| {
        server.test_placement = try TestPlacement.parse(placement);
        // The harness may start on an empty headless seat. Publish its keyboard
        // before the shell snapshots devices and any activity is launched;
        // creating it at the first click would relaunch the selected editor.
        if (session.keyboard == null and session.virtual_keyboard_manager != null)
            try session.prepareTestKeyboard();
    };
    const class = AIBinder_Class_define("dev.artzig.window.IWaylandTaskPresenter", null, null, transact);
    const binder = AIBinder_new(class, null);
    defer AIBinder_decStrong(binder);
    accepting.store(true, .release);
    defer {
        accepting.store(false, .release);
        server.drain(false);
        if (server.outbound) |*outbound| {
            if (outbound.request) |request| {
                outbound.request = null;
                request.status = -32;
                request.done.set(io);
            }
            server.retireSourceOutput();
        }
        while (callbacks.load(.acquire) != 0) Io.sleep(io, .fromMilliseconds(1), .awake) catch {};
        if (server.owner) |owner| {
            _ = AIBinder_unlinkToDeath(owner, death, null);
            AIBinder_decStrong(owner);
        }
        for (&server.entries) |*slot| if (slot.*) |*entry| {
            entry.deinit();
            slot.* = null;
        };
    }
    try ok(AServiceManager_addService(binder, "dev.artzig.window.presenter"));
    var stdin_group: Io.Group = .init;
    try stdin_group.concurrent(io, watchStdin, .{io});
    defer stdin_group.cancel(io);
    try Io.File.stdout().writeStreamingAll(io, "ready\n");
    while (!disconnected.load(.acquire)) {
        var files: [max_tasks + 2]Io.File = undefined;
        var ready: [max_tasks + 2]bool = undefined;
        var entries: [max_tasks]*Entry = undefined;
        files[0] = wake;
        var count: usize = 1;
        for (&server.entries) |*slot| if (slot.*) |*entry| {
            files[count] = entry.queue.notify_file;
            entries[count - 1] = entry;
            count += 1;
        };
        const task_count = count - 1;
        const icon_index = count;
        if (server.outbound) |outbound| {
            files[count] = outbound.queue.notify_file;
            count += 1;
        }
        try session.dispatch(files[0..count], ready[0..count], if (server.test_drag != null or server.outbound != null) 10 else -1);
        // Deliver protocol events before accepting frames: a queued event from
        // a pending configure must not become eligible because its replacement
        // frame happened to wake the same poll.
        try server.dispatchEvents();
        // Consume existing windows before requests can destroy/reuse a slot.
        for (entries[0..task_count], 1..) |entry, index| {
            if (ready[index] and !entry.hold_output and try entry.queue.presentConfiguration(entry.window, !entry.suspended and !entry.window.suspended and entry.pending_queue == null and entry.configuration_generation == entry.window.configuration_generation) > 0) {
                const restore_hover = !entry.inputGeometryReady();
                entry.has_content = true;
                entry.presented_output_generation = entry.output_generation;
                if (restore_hover and session.pointer_focus == entry.window and !entry.window.hasPointerCapture())
                    try send(entry, 4, .{ 1, 0, false, entry.window.pointer_x, entry.window.pointer_y });
            }
        }
        // Configure/viewport transactions must progress without waiting for a
        // producer frame. Retained content fills the acknowledged geometry
        // while Android draws the new configuration.
        for (entries[0..task_count]) |entry| try entry.window.present();
        if (server.outbound) |*outbound| {
            if (!outbound.failed) {
                const presented = if (icon_index < count and ready[icon_index]) outbound.queue.presentConfiguration(outbound.icon, true) catch |failure| failed: {
                    outbound.failed = true;
                    if (server.source()) |value| value.cancel();
                    if (server.owner) |callback| try sendGlobal(callback, 31, .{@as([]const u8, @errorName(failure))});
                    break :failed @as(u32, 0);
                } else 0;
                if (presented > 0) if (server.source()) |value| {
                    if (value.active()) outbound.retire_deadline = null;
                };
                try outbound.icon.present();
            }
        }
        try server.tickSource();
        if (ready[0]) server.drain(!disconnected.load(.acquire));
        try server.tickTestDrag();
    }
}

fn watchStdin(io: Io) void {
    var bytes: [16]u8 = undefined;
    while (true) {
        const count = Io.File.stdin().readStreaming(io, &.{&bytes}) catch break;
        if (count == 0) break;
    }
    disconnected.store(true, .release);
    server.signal();
}
