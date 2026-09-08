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
extern "c" fn AParcel_readString(*const Parcel, ?*anyopaque, *const fn (?*anyopaque, i32, *?[*]u8) callconv(.c) bool) i32;
extern "c" fn AParcel_readStrongBinder(*const Parcel, *?*Binder) i32;
extern "c" fn AParcel_writeInt32(*Parcel, i32) i32;
extern "c" fn AParcel_writeInt64(*Parcel, i64) i32;
extern "c" fn AParcel_writeFloat(*Parcel, f32) i32;
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
    test_frame_pending: bool = false,
    cursor_icon: ?i32 = null,
    fn matchesInputGeometry(self: *const Entry, geometry: [8]i32, configuration_generation: i64) bool {
        return self.has_content and configuration_generation == self.configuration_generation and
            configuration_generation == self.window.configuration_generation and
            self.presented_output_generation == self.output_generation and std.mem.eql(i32, geometry[0..6], &self.bounds) and
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
        try ok(AParcel_writeInt32(request.output, 0));
        try ok(AParcel_writeInt32Array(request.output, &snapshot, snapshot.len));
        return 0;
    }
    fn dispatchEvents(self: *Server) !void {
        if (self.session.ordered_events.?.failed) return error.SeatEventQueueOverflow;
        for (self.session.ordered_events.?.take()) |item| {
            switch (item.event) {
                .seat_capabilities => |caps| {
                    if (self.owner) |callback| try sendGlobal(callback, 15, .{ caps.generation, caps.mask });
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
            switch (item.event) {
                .frame_done => {
                    // A covered task may receive no frame callbacks. Progress
                    // belongs to actual callbacks, not an all-window barrier.
                    if (entry.test_frame_pending) self.completed_round += 1;
                    entry.test_frame_pending = false;
                },
                .configure => |size| try send(entry, 1, .{
                    size.width,      size.height,           size.buffer_width, size.buffer_height,
                    size.generation, @as(i64, size.serial),
                }),
                .close => try send(entry, 2, .{}),
                .keyboard_focus => |focused| try sendFocus(entry, focused),
                .keyboard_reset => try send(entry, 3, .{ false, @as([]const i32, &.{}) }),
                .pointer_motion => |p| try send(entry, 4, .{ 0, 0, false, p.x, p.y }),
                .pointer_enter => |p| try send(entry, 4, .{ 1, 0, false, p.x, p.y }),
                .pointer_leave => |p| try send(entry, 4, .{ 2, 0, false, p.x, p.y }),
                .pointer_button => |p| try send(entry, 4, .{ 3, p.button, p.pressed, p.x, p.y }),
                .pointer_cancel => try send(entry, 4, .{ 4, 0, false, 0.0, 0.0 }),
                .pointer_capture_changed => |capture| try send(entry, 12, .{ capture.enabled, capture.sequence }),
                .pointer_relative => |motion| try send(entry, 13, .{ motion.dx, motion.dy }),
                .pointer_axis => |axis| try send(entry, 5, .{ axis.x, axis.y, axis.horizontal, axis.vertical }),
                .key => |key| try send(entry, 6, .{ key.keycode, key.pressed, key.repeatable }),
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
                if (!entry.has_content or entry.test_frame_pending or
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
            try ok(AParcel_writeInt64(request.output, @as(i64, @intFromBool(value.window.isFullscreen())) | (@as(i64, @intFromBool(value.window.maximized)) << 1)));
            for ([_]i64{ value.window.configuration_generation, value.configuration_generation, value.output_generation, value.presented_output_generation, value.queue.presented_width, value.queue.presented_height }) |number|
                try ok(AParcel_writeInt64(request.output, number));
        }
        return 0;
    }
    fn injectTestInput(self: *Server, request: *Request) !i64 {
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
                    try entry.window.testDragAtOutput(from, to, extent, local);
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
        try send(entry, 14, .{self.test_input_sequence});
        return self.test_input_sequence;
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
        const input = request.input;
        const output = request.output;
        const key: Key = .{ .id = try readInt(input), .generation = try readLong(input) };
        if (key.id < 0 or key.generation <= 0) return error.InvalidTask;
        switch (request.code) {
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
                errdefer queue.deinit();
                // Hold the first callback independently of its window. Its
                // process death terminates this owner generation and all queues.
                if (self.owner == null) {
                    try ok(AIBinder_linkToDeath(callback, self.death, null));
                    AIBinder_incStrong(callback);
                    self.owner = callback;
                    self.owner_pid = request.pid;
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
                errdefer queue.deinit();
                queue.expected_size = .{ @intCast(width), @intCast(height) };
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, 1)); // non-null stock Surface
                try ok(queue.writeSurface(output));
                entry.pending_queue = queue;
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
            9 => {
                const icon = try readInt(input);
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                const applied = entry.window.setPointerIcon(icon);
                if (applied and entry.cursor_icon != icon) {
                    entry.cursor_icon = icon;
                    if (self.inspection_enabled) std.log.info("task cursor task={d} type={d} applied=true", .{ key.id, icon });
                }
                try ok(AParcel_writeInt32(output, 0));
                try ok(AParcel_writeInt32(output, @intFromBool(applied)));
            },
            15 => {
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
                const applied = try entry.window.setCursorImage(width, height, hotspot_x, hotspot_y, frame_ms, values);
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
    if (code < 1 or code > 15) return -38;
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
    fn allocate(raw: ?*anyopaque, length: i32, out: *?[*]u8) callconv(.c) bool {
        const self: *Text = @ptrCast(@alignCast(raw.?));
        if (length < 1 or length > self.bytes.len) return false;
        self.len = @intCast(length - 1);
        out.* = &self.bytes;
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
fn writeValues(parcel: *Parcel, values: anytype) !void {
    inline for (values) |value| switch (@typeInfo(@TypeOf(value))) {
        .bool => try ok(AParcel_writeInt32(parcel, @intFromBool(value))),
        .int, .comptime_int => if (@TypeOf(value) == i64)
            try ok(AParcel_writeInt64(parcel, value))
        else
            try ok(AParcel_writeInt32(parcel, @intCast(value))),
        .float, .comptime_float => try ok(AParcel_writeFloat(parcel, @floatCast(value))),
        .pointer => |pointer| {
            if (pointer.size != .slice or pointer.child != i32) @compileError("Unsupported task callback array");
            try ok(AParcel_writeInt32Array(parcel, value.ptr, @intCast(value.len)));
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
        .virtual_pointer_max_version = pointer_version,
        .cursor_shape = cursor_shape,
    });
    if (inspection_enabled) {
        const version = if (session.virtual_pointer_manager) |manager| manager.getVersion() else 0;
        std.log.info("task virtual pointer protocol version={d}", .{version});
        std.log.info("task cursor shape protocol available={any}", .{session.cursor_shape_manager != null});
    }
    session.ordered_events = .{};
    session.generate_key_repeats = false; // Stock InputDispatcher owns the timer.
    defer session.deinit();
    const death = AIBinder_DeathRecipient_new(onDeath);
    defer AIBinder_DeathRecipient_delete(death);
    server = .{ .gpa = init.gpa, .io = io, .session = session, .wake = wake, .death = death, .events_class = AIBinder_Class_define("dev.artzig.window.IWaylandTaskEvents", null, null, null) };
    server.inspection_enabled = inspection_enabled;
    if (server.inspection_enabled) if (init.minimal.environ.getPosix("AZK_TEST_WINDOW_PLACEMENT")) |placement| {
        server.test_placement = try TestPlacement.parse(placement);
    };
    const class = AIBinder_Class_define("dev.artzig.window.IWaylandTaskPresenter", null, null, transact);
    const binder = AIBinder_new(class, null);
    defer AIBinder_decStrong(binder);
    accepting.store(true, .release);
    defer {
        accepting.store(false, .release);
        server.drain(false);
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
        var files: [max_tasks + 1]Io.File = undefined;
        var ready: [max_tasks + 1]bool = undefined;
        var entries: [max_tasks]*Entry = undefined;
        files[0] = wake;
        var count: usize = 1;
        for (&server.entries) |*slot| if (slot.*) |*entry| {
            files[count] = entry.queue.notify_file;
            entries[count - 1] = entry;
            count += 1;
        };
        try session.dispatch(files[0..count], ready[0..count], -1);
        // Consume existing windows before requests can destroy/reuse a slot.
        for (entries[0 .. count - 1], 1..) |entry, index| {
            if (ready[index] and !entry.hold_output and try entry.queue.presentConfiguration(entry.window, entry.configuration_generation == entry.window.configuration_generation) > 0) {
                entry.has_content = true;
                entry.presented_output_generation = entry.output_generation;
            }
        }
        try server.dispatchEvents();
        // Configure/viewport transactions must progress without waiting for a
        // producer frame. Retained content fills the acknowledged geometry
        // while Android draws the new configuration.
        for (entries[0 .. count - 1]) |entry| try entry.window.present();
        if (ready[0]) server.drain(!disconnected.load(.acquire));
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
