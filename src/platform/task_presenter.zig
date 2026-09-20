//! Session-owned Wayland presentation. AZKB workers submit bounded requests;
//! only the main thread touches protocol objects or native consumer ownership.
const std = @import("std");
const Io = std.Io;
const Queue = @import("surface_queue");
const TestPlacement = @import("test_input_placement.zig").Placement;
const aidl = @import("aidl");
const interfaces = @import("task_presenter_aidl");
const Surface = @import("task_presenter_parcelables").@"android.view.Surface";
/// Generated from aidl-compat/dev/artzig/window by `aidl --lang=zig`.
const Presenter = interfaces.@"dev.artzig.window.IWaylandTaskPresenter";
const Events = interfaces.@"dev.artzig.window.IWaylandTaskEvents";
const Death = opaque {};
extern "c" fn AIBinder_DeathRecipient_new(*const fn (?*anyopaque) callconv(.c) void) *Death;
extern "c" fn AIBinder_DeathRecipient_delete(*Death) void;
extern "c" fn AIBinder_linkToDeath(*aidl.AIBinder, *Death, ?*anyopaque) i32;
extern "c" fn AIBinder_unlinkToDeath(*aidl.AIBinder, *Death, ?*anyopaque) i32;
extern "c" fn azk_graphics_live_buffers() u64;

const max_tasks = 64;
const Key = struct { id: i32, generation: i64 };
const Entry = struct {
    key: Key,
    window: *Queue.Window,
    queue: *Queue,
    events: Events.Client,
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
        self.events.deinit();
    }
};
const Request = struct {
    code: u32,
    pid: i32,
    input: aidl.Parcel,
    output: aidl.Parcel,
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
    owner: ?Events.Client = null,
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
    /// The first callback is held independently of any window. Its process
    /// death terminates this owner generation and all queues.
    fn adoptOwner(self: *Server, callback: Events.Client, pid: i32) !void {
        try ok(AIBinder_linkToDeath(callback.remote.handle, self.death, null));
        self.owner = .{ .remote = callback.remote.clone() };
        self.owner_pid = pid;
        if (self.session.tablets) |tablets| tablets.replayDevices();
    }
    fn connectSeat(self: *Server, request: *Request, arena: std.mem.Allocator, args: M.connectSeat.Args) !M.connectSeat.Reply {
        if (self.owner != null) return error.SeatAlreadyConnected;
        const snapshot = try self.session.seatSnapshot();
        try self.adoptOwner(args.events, request.pid);
        return .{ ._result = try arena.dupe(i32, &snapshot) };
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
                        try callback.nativeDragSourceEvent(@intCast(source_event.token), kind, value);
                    }
                    continue;
                },
                .native_drag_issue => |issue| {
                    if (self.owner) |callback| try callback.nativeDragIssue(@errorName(issue));
                    continue;
                },
                .native_drag => |event| {
                    const callback = self.owner orelse continue;
                    if (event == .ended) {
                        try callback.nativeDragEnded(@intCast(event.ended.token), event.ended.consumed);
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
                        .entered => |value| try callback.nativeDragEntered(entry.?.key.id, entry.?.key.generation, @intCast(value.token), @intCast(value.source_token), @floatCast(value.x), @floatCast(value.y)),
                        .motion => |value| try callback.nativeDragMotion(@intCast(value.token), @floatCast(value.x), @floatCast(value.y)),
                        .payload => |value| try callback.nativeDragPayload(@intCast(value.token), payloadText(value.value), payloadHtml(value.value), payloadUris(value.value)),
                        .dropped => |token| try callback.nativeDragDropped(@intCast(token)),
                        .ended => unreachable,
                    }
                    continue;
                },
                .clipboard_available => |available| {
                    std.log.info("task clipboard available={any}", .{available});
                    if (self.owner) |callback| try callback.clipboardAvailability(available);
                    continue;
                },
                .clipboard_changed => |change| {
                    if (self.owner) |callback| try callback.clipboardChanged(change.revision, payloadText(change.payload), payloadHtml(change.payload), payloadUris(change.payload));
                    continue;
                },
                .clipboard_issue => |issue| {
                    std.log.warn("task clipboard: {s}", .{@errorName(issue)});
                    if (self.owner) |callback| try callback.clipboardIssue(@errorName(issue));
                    continue;
                },
                .text_input_available => |available| {
                    std.log.info("task text input available={any}", .{available});
                    if (self.owner) |callback| try callback.textInputAvailability(available);
                    continue;
                },
                .seat_capabilities => |caps| {
                    if (self.owner) |callback| try callback.seatCapabilities(caps.generation, caps.mask);
                    continue;
                },
                .tablet => |tablet| if (tablet == .device) {
                    const device = tablet.device;
                    if (self.owner) |callback| try callback.tabletDevice(device.id, device.generation, device.kind, device.capabilities, device.present);
                    continue;
                },
                .keyboard_layout => |layout| {
                    if (self.owner) |callback| try sendLayout(callback, layout);
                    continue;
                },
                .keyboard_modifiers => |meta| {
                    if (self.owner) |callback| try callback.modifiers(@intCast(meta.effective), @intCast(meta.persistent));
                    continue;
                },
                .keyboard_repeat_configuration => |config| {
                    if (self.owner) |callback| try callback.repeatConfiguration(config.rate, config.delay_ms);
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
                .text_input_focus => |focused| try entry.events.textInputFocus(entry.key.id, entry.key.generation, focused),
                .text_input_batch => |batch| try entry.events.textInputEdit(entry.key.id, entry.key.generation, batch.editor, batch.sequence, batch.fields, batch.preedit.slice(), batch.cursor_begin, batch.cursor_end, batch.commit.slice(), @intCast(batch.before), @intCast(batch.after)),
                .tablet => |tablet| {
                    const sample = tablet.sample;
                    if (sample.action == .cancel) {
                        try entry.events.cancelTablet(entry.key.id, entry.key.generation, sample.id);
                    } else if (entry.inputGeometryReady()) {
                        try entry.events.tablet(entry.key.id, entry.key.generation, sample.id, sample.generation, @backingInt(sample.action), @intCast(sample.button), sample.pressed, &sample.axes);
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
                    try cancelInput(entry);
                    try entry.events.configure(entry.key.id, entry.key.generation, size.width, size.height, size.buffer_width, size.buffer_height, size.generation, size.serial);
                },
                .suspended_changed => |suspended| {
                    entry.suspended = suspended;
                    entry.queue.requestPresentation();
                    if (suspended) {
                        try cancelInput(entry);
                        try entry.events.keyboardFocus(entry.key.id, entry.key.generation, false, &.{});
                        try entry.window.setPointerCapture(false);
                    }
                    // WCT owns Android visibility/lifecycle; publish it before
                    // restoring focus or coordinates on compositor resumption.
                    try entry.events.suspended(entry.key.id, entry.key.generation, suspended);
                    if (!suspended) {
                        if (self.session.keyboard_focus == entry.window)
                            try sendFocus(entry, self.session.keyboard_state);
                        if (entry.inputGeometryReady() and self.session.pointer_focus == entry.window)
                            try pointer(entry, 1, 0, false, entry.window.pointer_x, entry.window.pointer_y);
                    }
                },
                .close => try entry.events.closeRequested(entry.key.id, entry.key.generation),
                .keyboard_focus => |focused| if (!focused.active or (!entry.suspended and !entry.window.suspended)) try sendFocus(entry, focused),
                .keyboard_reset => try entry.events.keyboardFocus(entry.key.id, entry.key.generation, false, &.{}),
                .pointer_motion => |p| try pointer(entry, 0, 0, false, p.x, p.y),
                .pointer_enter => |p| try pointer(entry, 1, 0, false, p.x, p.y),
                .pointer_leave => |p| try pointer(entry, 2, 0, false, p.x, p.y),
                .pointer_button => |p| try pointer(entry, 3, p.button, p.pressed, p.x, p.y),
                .pointer_cancel => try pointer(entry, 4, 0, false, 0, 0),
                .pointer_capture_changed => |capture| try entry.events.pointerCaptureChanged(entry.key.id, entry.key.generation, capture.enabled, capture.sequence),
                .pointer_relative => |motion| try entry.events.relativePointer(entry.key.id, entry.key.generation, @floatCast(motion.dx), @floatCast(motion.dy)),
                .pointer_axis => |axis| try entry.events.scroll(entry.key.id, entry.key.generation, @floatCast(axis.x), @floatCast(axis.y), @floatCast(axis.horizontal), @floatCast(axis.vertical)),
                .key => |key| if (!entry.suspended and !entry.window.suspended) try entry.events.key(entry.key.id, entry.key.generation, @intCast(key.keycode), key.pressed, key.repeatable),
                .touch => |touch| try entry.events.touch(entry.key.id, entry.key.generation, @backingInt(touch.action), @intCast(touch.id), @floatCast(touch.x), @floatCast(touch.y), @floatCast(touch.major), @floatCast(touch.minor), @floatCast(touch.orientation)),
                .touch_cancel => try entry.events.cancelTouch(entry.key.id, entry.key.generation),
                else => {},
            }
        }
    }
    fn observeTestFrames(self: *Server, _: *Request, arena: std.mem.Allocator, args: M.observeTestFrames.Args) !M.observeTestFrames.Reply {
        if (args.requestFrame) {
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
        return .{ ._result = try arena.dupe(i64, &.{ self.submitted_round, self.completed_round }) };
    }
    fn testWindow(self: *Server, _: *Request, arena: std.mem.Allocator, args: M.testWindow.Args) !M.testWindow.Reply {
        const value = for (&self.entries) |*slot| {
            if (slot.*) |*entry| if (entry.key.id == args.taskId) break entry;
        } else return .{ ._result = &.{} };
        const state = @as(i64, @intFromBool(value.window.isFullscreen())) | (@as(i64, @intFromBool(value.window.maximized)) << 1) | (@as(i64, @intFromBool(value.window.suspended)) << 2);
        var numbers: [16]i64 = undefined;
        numbers[0] = value.key.generation;
        for (value.bounds, numbers[1..7]) |bound, *number| number.* = bound;
        numbers[7..].* = .{ value.window.width, value.window.height, state, value.window.configuration_generation, value.configuration_generation, value.output_generation, value.presented_output_generation, value.queue.presented_width, value.queue.presented_height };
        return .{ ._result = try arena.dupe(i64, &numbers) };
    }
    fn injectTestInput(self: *Server, _: *Request, arena: std.mem.Allocator, args: M.injectTestInput.Args) !M.injectTestInput.Reply {
        // Positive ordered sequence; zero is rejection; -1 is unknown completion.
        const injected = self.injectTestInputChecked(arena, args) catch |failure| unknown: {
            std.log.warn("test input failed; completion unknown: {s}", .{@errorName(failure)});
            break :unknown -1;
        };
        return .{ ._result = injected };
    }
    fn injectTestInputChecked(self: *Server, arena: std.mem.Allocator, args: M.injectTestInput.Args) !i64 {
        if (self.test_drag != null) return error.TestGestureAlreadyActive;
        const key: Key = .{ .id = args.taskId, .generation = args.generation };
        const configuration_generation = args.configurationGeneration;
        if (args.geometry.len != 8) return error.InvalidGeometry;
        const geometry: [8]i32 = args.geometry[0..8].*;
        const kind = args.kind;
        if (args.points.len != 4) return error.InvalidPoints;
        const points: [4]i32 = args.points[0..4].*;
        const text = try boundedText(arena, args.text);
        const entry = self.find(key) orelse return 0;
        if (!entry.matchesInputGeometry(geometry, configuration_generation)) return 0;
        try entry.window.prepareTestInput();
        // Seat roundtrips can receive a configure; reject its old geometry too.
        if (!entry.matchesInputGeometry(geometry, configuration_generation)) return 0;
        if (kind == 2 or kind == 4) try Queue.Window.validateTestText(text);
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
                const value = text;
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
            } else try entry.window.testKey(text);
        }
        // Drain the compositor's events before sending the barrier on the same
        // ordered Binder callback used by the entire seat. Shell receipt means
        // preceding events have reached InputDispatcher, not that the app drew.
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        try self.dispatchEvents();
        self.test_input_sequence = try std.math.add(i64, self.test_input_sequence, 1);
        if (self.test_drag) |*drag| drag.sequence = self.test_input_sequence else try entry.events.testInputBarrier(entry.key.id, entry.key.generation, self.test_input_sequence);
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
        if (entry) |target| try target.events.testInputBarrier(target.key.id, target.key.generation, drag.sequence);
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
    /// startNativeDrag's reply: [sourceToken, status] with accepted=1,
    /// rejected before takeover=2, uncertain=3.
    fn startReply(arena: std.mem.Allocator, queue: ?*Queue, token: u64, state: i64) !M.startNativeDrag.Reply {
        return .{
            ._result = if (queue) |value| .{ .queue = value } else null,
            .result = try arena.dupe(i64, &.{ @intCast(token), state }),
        };
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
                var numbers: [2]i64 = .{ @intCast(outbound.token), if (accepted) 1 else if (state == .rejected) 2 else 3 };
                aidl.encodeReply(M.startNativeDrag, request.output, .{
                    ._result = if (accepted) .{ .queue = outbound.queue } else null,
                    .result = &numbers,
                }) catch break :status aidl.status_bad_value;
                break :status aidl.status_ok;
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
            if (self.owner) |callback| try callback.nativeDragIssue("NativeDragIconRetirementTimedOut");
        };
    }
    fn cancelNativeDragSource(self: *Server, _: *Request, _: std.mem.Allocator, args: M.cancelNativeDragSource.Args) !M.cancelNativeDragSource.Reply {
        if (args.sourceToken <= 0) return error.InvalidDragSourceToken;
        if (self.outbound) |outbound| if (outbound.token == args.sourceToken and outbound.request == null) {
            if (self.source()) |value| value.cancel();
            return .{ ._result = true };
        };
        return .{ ._result = false };
    }
    fn retireNativeDragIcon(self: *Server, _: *Request, _: std.mem.Allocator, args: M.retireNativeDragIcon.Args) !M.retireNativeDragIcon.Reply {
        if (args.sourceToken <= 0) return error.InvalidDragSourceToken;
        if (self.outbound) |outbound| if (outbound.token == args.sourceToken and outbound.request == null) {
            if (self.source() == null or !self.source().?.active()) {
                self.retireSourceOutput();
                return .{ ._result = true };
            }
        };
        return .{ ._result = false };
    }
    fn dragKind(kind: i32) !Queue.DragKind {
        return switch (kind) {
            0 => .pointer,
            1 => .touch,
            else => error.InvalidDragInputKind,
        };
    }
    fn dragTarget(self: *Server, key: Key, configuration: i64) ?*Entry {
        const entry = self.find(key) orelse return null;
        return if (entry.inputGeometryReady() and entry.configuration_generation == configuration) entry else null;
    }
    fn claimNativeDrag(self: *Server, _: *Request, _: std.mem.Allocator, args: M.claimNativeDrag.Args) !M.claimNativeDrag.Reply {
        const kind = try dragKind(args.kind);
        const entry = self.dragTarget(.{ .id = args.taskId, .generation = args.generation }, args.configurationGeneration);
        const available = if (self.session.clipboard) |device_owner|
            device_owner.manager.getVersion() >= 3 and device_owner.device.getVersion() >= 3 and self.session.viewporter != null
        else
            false;
        // Unsupported presentation must not stage URI exports or consume a
        // held generation merely to discover the missing protocol later.
        const generation = if (available and entry != null and self.outbound == null) self.session.dragClaim(entry.?.window, kind, args.inputId) else null;
        return .{ ._result = @intCast(generation orelse 0) };
    }
    fn startNativeDrag(self: *Server, request: *Request, arena: std.mem.Allocator, args: M.startNativeDrag.Args) !?M.startNativeDrag.Reply {
        const key: Key = .{ .id = args.taskId, .generation = args.generation };
        const kind = try dragKind(args.kind);
        const entry = self.dragTarget(key, args.configurationGeneration);
        if (args.claim <= 0 or !dimension(args.width) or !dimension(args.height) or !dimension(args.bufferWidth) or !dimension(args.bufferHeight) or
            args.hotspotX < 0 or args.hotspotY < 0) return error.InvalidDragIcon;
        const text = try boundedClipboard(args.text);
        const html = try boundedClipboard(args.html);
        const uris = try boundedClipboard(args.uris);
        if (args.result_length != 2) return error.InvalidDragResultArray;
        if (entry == null or self.outbound != null or self.session.dragClaim(entry.?.window, kind, args.inputId) != @as(u64, @intCast(args.claim)))
            return try startReply(arena, null, 0, 2);
        const clipboard = self.session.clipboard orelse return try startReply(arena, null, 0, 2);
        if (clipboard.manager.getVersion() < 3 or clipboard.device.getVersion() < 3) return try startReply(arena, null, 0, 2);
        const icon = try Queue.DragIcon.init(self.session, args.width, args.height, .{ args.hotspotX, args.hotspotY });
        errdefer icon.deinit();
        const queue = try Queue.init(self.gpa, self.io, @intCast(args.bufferWidth), @intCast(args.bufferHeight));
        errdefer queue.deinit();
        queue.expected_size = .{ @intCast(args.bufferWidth), @intCast(args.bufferHeight) };
        queue.pace_frames = true;
        const payload = if (text != null or html != null or uris != null)
            try Queue.Clipboard.Payload.initAll(self.gpa, text, html, uris)
        else
            null;
        const current = self.session.startDrag(entry.?.window, kind, args.inputId, @intCast(args.claim), icon.surface, payload, self.session.nativeSourceHooks()) catch |failure| {
            // The validated generation was consumed. Allocation/transport errors
            // after admission cannot prove that the compositor kept the grab.
            std.log.warn("native drag start uncertain: {s}", .{@errorName(failure)});
            queue.deinit();
            icon.deinit();
            return try startReply(arena, null, 0, 3);
        };
        self.outbound = .{ .key = key, .token = current.token, .icon = icon, .queue = queue, .request = request, .retire_deadline = Io.Clock.awake.now(self.io).toMilliseconds() + 6000 };
        return null; // tickSource answers; drain keeps this request alive meanwhile.
    }
    fn testResources(self: *Server, _: *Request, arena: std.mem.Allocator, _: M.testResources.Args) !M.testResources.Reply {
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
        return .{ ._result = try arena.dupe(i64, &.{ tasks, queues, imports, @intCast(azk_graphics_live_buffers()), descriptors }) };
    }
    fn testHoldOutput(self: *Server, _: *Request, _: std.mem.Allocator, args: M.testHoldOutput.Args) !M.testHoldOutput.Reply {
        const entry = self.find(.{ .id = args.taskId, .generation = args.generation }) orelse return error.StaleTask;
        entry.hold_output = args.hold;
        if (!entry.hold_output) entry.queue.requestPresentation();
        return .{ ._result = true };
    }
    fn testWindowState(self: *Server, _: *Request, _: std.mem.Allocator, args: M.testWindowState.Args) !M.testWindowState.Reply {
        const entry = self.find(.{ .id = args.taskId, .generation = args.generation }) orelse return error.StaleTask;
        if (args.state < 0 or args.state > 2) return error.InvalidWindowState;
        entry.window.setFullscreen(args.state == 1);
        if (args.state == 2) entry.window.toplevel.setMaximized() else entry.window.toplevel.unsetMaximized();
        return .{ ._result = true };
    }
    fn textInputAvailable(self: *Server, _: *Request, _: std.mem.Allocator, _: M.textInputAvailable.Args) !M.textInputAvailable.Reply {
        return .{ ._result = self.session.text_input != null };
    }
    fn clipboardAvailable(self: *Server, _: *Request, _: std.mem.Allocator, _: M.clipboardAvailable.Args) !M.clipboardAvailable.Reply {
        return .{ ._result = self.session.clipboard != null };
    }
    fn acceptNativeDrag(self: *Server, _: *Request, _: std.mem.Allocator, args: M.acceptNativeDrag.Args) !M.acceptNativeDrag.Reply {
        if (args.token <= 0) return error.InvalidDragResponse;
        if (self.session.clipboard) |clipboard| if (clipboard.native_drag) |*drag|
            return .{ ._result = drag.accept(@intCast(args.token), args.accepted) };
        return .{ ._result = false };
    }
    fn finishNativeDrag(self: *Server, _: *Request, _: std.mem.Allocator, args: M.finishNativeDrag.Args) !M.finishNativeDrag.Reply {
        if (args.token <= 0) return error.InvalidDragResponse;
        if (self.session.clipboard) |clipboard| if (clipboard.native_drag != null)
            return .{ ._result = clipboard.finishNativeDrag(@intCast(args.token), args.consumed) };
        return .{ ._result = false };
    }
    fn setClipboard(self: *Server, _: *Request, _: std.mem.Allocator, args: M.setClipboard.Args) !M.setClipboard.Reply {
        if (args.revision <= 0) return error.InvalidClipboardRevision;
        const text = try boundedClipboard(args.text);
        const html = try boundedClipboard(args.html);
        const uris = try boundedClipboard(args.uris);
        if (text == null and html != null) return error.HtmlWithoutClipboardText;
        if (self.session.clipboard) |clipboard| {
            const payload = if (text != null or html != null or uris != null) try Queue.Clipboard.Payload.initAll(self.gpa, text, html, uris) else null;
            try clipboard.set(args.revision, payload);
        }
        return .{ ._result = self.session.clipboard != null };
    }
    fn updateTextInput(self: *Server, _: *Request, _: std.mem.Allocator, args: M.updateTextInput.Args) !M.updateTextInput.Reply {
        const key = try taskKey(args.taskId, args.generation);
        if (args.surrounding) |text| if (text.len > Queue.TextInput.max_bytes) return error.InvalidText;
        var state: Queue.TextInput.State = .{
            .editor = args.editorGeneration,
            .applied = args.appliedSequence,
            .enabled = args.enabled,
            .surrounding = if (args.surrounding) |text| try Queue.TextInput.Text.init(text) else null,
            .cursor = args.cursor,
            .anchor = args.anchor,
            .hint = @bitCast(args.hint),
            .purpose = @bitCast(args.purpose),
        };
        state.external_change = args.externalChange;
        if (args.cursorBounds.len != 0 and args.cursorBounds.len != 4) return error.InvalidCursorBounds;
        if (args.cursorBounds.len == 4) state.rectangle = args.cursorBounds[0..4].*;
        try state.validate();
        const entry = self.find(key) orelse return error.StaleTask;
        var accepted = false;
        if (self.session.text_input) |text_input| if (text_input.surface) |surface| {
            if (surface == entry.window.surface or surface == entry.window.ui_surface)
                accepted = try text_input.update(surface, &state);
        };
        return .{ ._result = accepted };
    }
    fn requestFullscreen(self: *Server, _: *Request, _: std.mem.Allocator, args: M.requestFullscreen.Args) !M.requestFullscreen.Reply {
        const key = try taskKey(args.taskId, args.generation);
        if (args.mode < 0 or args.mode > 2) return error.InvalidWindowState;
        const entry = self.find(key) orelse return error.StaleTask;
        if (args.mode == 2) entry.window.toggleFullscreen() else entry.window.setFullscreen(args.mode == 1);
        return .{ ._result = true };
    }
    fn createWindow(self: *Server, request: *Request, arena: std.mem.Allocator, args: M.createWindow.Args) !M.createWindow.Reply {
        const key = try taskKey(args.taskId, args.generation);
        if (!dimension(args.width) or !dimension(args.height)) return error.InvalidSize;
        const app_id = try boundedText(arena, args.appId);
        const title = try boundedText(arena, args.title);
        if (args.configuration_length != 6) return error.InvalidConfigurationArray;
        var free: ?*?Entry = null;
        for (&self.entries) |*slot| {
            if (slot.*) |entry| {
                if (entry.key.id == key.id) return error.DuplicateTask;
            } else if (free == null) {
                free = slot;
            }
        }
        const slot = free orelse return error.TaskCapacity;
        const window = try self.session.createWindowWithAppId(args.width, args.height, title, app_id, false);
        errdefer window.deinit();
        const queue = try Queue.init(self.gpa, self.io, @intCast(window.width), @intCast(window.height));
        queue.pace_frames = true;
        errdefer queue.deinit();
        if (self.owner == null) try self.adoptOwner(args.events, request.pid);
        const configuration = try arena.dupe(i64, &.{ window.width, window.height, window.bufferWidth(), window.bufferHeight(), window.configuration_generation, window.configure_serial });
        slot.* = .{ .key = key, .window = window, .queue = queue, .events = .{ .remote = args.events.remote.clone() } };
        return .{ ._result = .{ .queue = queue }, .configuration = configuration };
    }
    fn destroyWindow(self: *Server, _: *Request, _: std.mem.Allocator, args: M.destroyWindow.Args) !M.destroyWindow.Reply {
        const key = try taskKey(args.taskId, args.generation);
        for (&self.entries) |*slot| if (slot.*) |*entry| {
            if (std.meta.eql(entry.key, key)) {
                entry.deinit();
                slot.* = null;
                break;
            }
        };
        return .{};
    }
    fn setMetadata(self: *Server, _: *Request, arena: std.mem.Allocator, args: M.setMetadata.Args) !M.setMetadata.Reply {
        const key = try taskKey(args.taskId, args.generation);
        const app_id = try boundedText(arena, args.appId);
        const title = try boundedText(arena, args.title);
        const entry = self.find(key) orelse return error.StaleTask;
        entry.window.toplevel.setAppId(app_id);
        entry.window.toplevel.setTitle(title);
        return .{};
    }
    fn configurationCommitted(self: *Server, _: *Request, _: std.mem.Allocator, args: M.configurationCommitted.Args) !M.configurationCommitted.Reply {
        const key = try taskKey(args.taskId, args.generation);
        const bounds: [6]i32 = .{ args.left, args.top, args.width, args.height, args.outputWidth, args.outputHeight };
        if (bounds[0] < 0 or bounds[1] < 0 or !dimension(bounds[2]) or !dimension(bounds[3]) or
            !dimension(bounds[4]) or !dimension(bounds[5]) or
            @as(i64, bounds[0]) + bounds[2] > 16384 or @as(i64, bounds[1]) + bounds[3] > 16384)
            return error.InvalidBounds;
        const entry = self.find(key) orelse return error.StaleTask;
        const pending = entry.pending_queue orelse return error.OutputNotPrepared;
        if (entry.pending_output_generation != args.outputGeneration or
            entry.pending_configuration_generation != args.configurationGeneration)
            return error.StaleOutputConfiguration;
        // SF's transaction has disconnected the old producer. Keep its
        // compositor image until a new buffer arrives, but abandon its
        // queue so no late same-sized frame can enter this generation.
        entry.window.retireUiProducer();
        entry.queue.deinit();
        entry.queue = pending;
        entry.pending_queue = null;
        entry.output_generation = args.outputGeneration;
        entry.configuration_generation = args.configurationGeneration;
        entry.has_content = false;
        entry.bounds = bounds;
        // Pending producers can finish a fence before the transaction
        // commits. Their eventfd was not watched; wake the active loop.
        entry.queue.requestPresentation();
        return .{};
    }
    fn prepareOutput(self: *Server, _: *Request, _: std.mem.Allocator, args: M.prepareOutput.Args) !M.prepareOutput.Reply {
        const key = try taskKey(args.taskId, args.generation);
        const entry = self.find(key) orelse return error.StaleTask;
        if (!dimension(args.bufferWidth) or !dimension(args.bufferHeight) or args.outputGeneration <= entry.output_generation or
            args.configurationGeneration <= 0 or args.configurationGeneration > entry.window.configuration_generation)
            return error.InvalidOutputConfiguration;
        if (entry.pending_queue != null) return error.OutputConfigurationPending;
        const queue = try Queue.init(self.gpa, self.io, @intCast(args.bufferWidth), @intCast(args.bufferHeight));
        queue.pace_frames = true;
        errdefer queue.deinit();
        queue.expected_size = .{ @intCast(args.bufferWidth), @intCast(args.bufferHeight) };
        // Preparation also covers Android-initiated relocation with
        // unchanged compositor geometry. Cancel before WCT changes its
        // input windows, and hold input until this producer presents.
        try cancelInput(entry);
        entry.pending_queue = queue;
        entry.queue.requestPresentation();
        entry.pending_output_generation = args.outputGeneration;
        entry.pending_configuration_generation = args.configurationGeneration;
        return .{ ._result = .{ .queue = queue } };
    }
    fn setPointerCapture(self: *Server, _: *Request, _: std.mem.Allocator, args: M.setPointerCapture.Args) !M.setPointerCapture.Reply {
        const key = try taskKey(args.taskId, args.generation);
        const entry = self.find(key) orelse return error.StaleTask;
        entry.window.requestPointerCapture(args.enabled, args.sequence) catch |err| {
            std.log.warn("task {d} pointer capture unavailable: {s}", .{ key.id, @errorName(err) });
            return .{ ._result = false };
        };
        return .{ ._result = true };
    }
    fn noteCursorIcon(self: *Server, entry: *Entry, icon: i32) void {
        if (entry.cursor_icon == icon) return;
        entry.cursor_icon = icon;
        if (self.inspection_enabled) std.log.info("task cursor task={d} type={d} applied=true", .{ entry.key.id, icon });
    }
    fn setPointerIcon(self: *Server, _: *Request, _: std.mem.Allocator, args: M.setPointerIcon.Args) !M.setPointerIcon.Reply {
        const entry = self.find(try taskKey(args.taskId, args.generation)) orelse return error.StaleTask;
        const applied = entry.inputGeometryReady() and entry.window.setPointerIcon(args.type);
        if (applied) self.noteCursorIcon(entry, args.type);
        return .{ ._result = applied };
    }
    fn setTabletPointerIcon(self: *Server, _: *Request, _: std.mem.Allocator, args: M.setTabletPointerIcon.Args) !M.setTabletPointerIcon.Reply {
        const entry = self.find(try taskKey(args.taskId, args.generation)) orelse return error.StaleTask;
        const applied = entry.inputGeometryReady() and entry.window.setTabletPointerIcon(args.deviceId, args.deviceGeneration, args.type);
        if (applied) self.noteCursorIcon(entry, args.type);
        return .{ ._result = applied };
    }
    fn noteCursorImage(self: *Server, key: Key, width: i32, height: i32, hotspot: [2]i32, pixels: []const i32) void {
        if (self.inspection_enabled) std.log.info("task bitmap cursor task={d} size={d}x{d} frames={d} hotspot={d},{d}", .{ key.id, width, height, pixels.len / @as(usize, @intCast(width * height)), hotspot[0], hotspot[1] });
    }
    fn setCursorImage(self: *Server, _: *Request, _: std.mem.Allocator, args: M.setCursorImage.Args) !M.setCursorImage.Reply {
        const key = try taskKey(args.taskId, args.generation);
        const entry = self.find(key) orelse return error.StaleTask;
        const pixels = try boundedPixels(args.pixels);
        const applied = entry.inputGeometryReady() and try entry.window.setCursorImage(args.width, args.height, args.hotspotX, args.hotspotY, args.frameMillis, pixels);
        if (applied) self.noteCursorImage(key, args.width, args.height, .{ args.hotspotX, args.hotspotY }, pixels);
        return .{ ._result = applied };
    }
    fn setTabletCursorImage(self: *Server, _: *Request, _: std.mem.Allocator, args: M.setTabletCursorImage.Args) !M.setTabletCursorImage.Reply {
        const key = try taskKey(args.taskId, args.generation);
        const entry = self.find(key) orelse return error.StaleTask;
        const pixels = try boundedPixels(args.pixels);
        const applied = entry.inputGeometryReady() and try entry.window.setTabletCursorImage(args.deviceId, args.deviceGeneration, args.width, args.height, args.hotspotX, args.hotspotY, args.frameMillis, pixels);
        if (applied) self.noteCursorImage(key, args.width, args.height, .{ args.hotspotX, args.hotspotY }, pixels);
        return .{ ._result = applied };
    }

    /// Transport status of one admitted request. The generated tables supply
    /// the transaction codes, argument decoding and reply framing; the methods
    /// above are this interface's implementation on the Wayland thread.
    fn handle(self: *Server, request: *Request) !i32 {
        inline for (Presenter.methods) |Method| {
            if (request.code == Method.code) return self.serve(Method, request);
        }
        return aidl.status_unknown_transaction;
    }
    fn serve(self: *Server, comptime Method: type, request: *Request) !i32 {
        const name = Method.name;
        // The read/test-pacing API is available to the system controller; only
        // the system-server owner may mutate task identities and geometry.
        const inspection = comptime isOneOf(name, &.{ "observeTestFrames", "testWindow", "testResources", "testHoldOutput", "testWindowState", "injectTestInput" });
        const drag_source = comptime isOneOf(name, &.{ "acceptNativeDrag", "finishNativeDrag", "claimNativeDrag", "startNativeDrag", "cancelNativeDragSource", "retireNativeDragIcon" });
        if (inspection and !self.inspection_enabled) return permission_denied;
        if (!inspection and comptime !std.mem.eql(u8, name, "connectSeat")) {
            if (self.owner_pid != 0 and self.owner_pid != request.pid) return permission_denied;
            if (drag_source and self.owner == null) return permission_denied;
        }
        var arena: std.heap.ArenaAllocator = .init(self.gpa);
        defer arena.deinit();
        const args = try aidl.decodeArgs(Method, request.input, arena.allocator());
        // A handler that keeps a callback clones it.
        defer aidl.releaseArgs(Method, self.io, args);
        try request.input.expectEnd();
        const outcome = try @field(Server, name)(self, request, arena.allocator(), args);
        const reply: Method.Reply = if (@TypeOf(outcome) == ?Method.Reply) outcome orelse return aidl.status_ok else outcome;
        try aidl.encodeReply(Method, request.output, reply);
        return aidl.status_ok;
    }
};
const M = Presenter.method;
/// STATUS_PERMISSION_DENIED; the Java proxy raises SecurityException.
const permission_denied: i32 = -1;
fn isOneOf(comptime name: []const u8, comptime names: []const []const u8) bool {
    inline for (names) |candidate| if (std.mem.eql(u8, name, candidate)) return true;
    return false;
}
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
/// Binder thread: admit the request, hand it to the Wayland thread and wait.
fn transact(_: *anyopaque, code: u32, input: aidl.Parcel, output: aidl.Parcel) i32 {
    _ = callbacks.fetchAdd(1, .acq_rel);
    defer _ = callbacks.fetchSub(1, .acq_rel);
    if (!accepting.load(.acquire)) return aidl.status_dead_object;
    if (aidl.callingUid() != 1000) return permission_denied;
    const io = server.io;
    var request: Request = .{ .code = code, .pid = aidl.callingPid(), .input = input, .output = output };
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
fn dimension(value: i32) bool {
    return value > 0 and value <= 16384;
}
fn taskKey(id: i32, generation: i64) !Key {
    if (id < 0 or generation <= 0) return error.InvalidTask;
    return .{ .id = id, .generation = generation };
}
/// Window metadata and test input: at most 1024 bytes without an embedded NUL.
fn boundedText(arena: std.mem.Allocator, text: []const u8) ![:0]const u8 {
    if (text.len > 1024 or std.mem.indexOfScalar(u8, text, 0) != null) return error.InvalidText;
    return arena.dupeSentinel(u8, text, 0);
}
fn boundedClipboard(bytes: ?[]const u8) !?[]const u8 {
    if (bytes) |value| if (value.len > 256 * 1024) return error.ClipboardRepresentationTooLarge;
    return bytes;
}
fn boundedPixels(pixels: []const i32) ![]const i32 {
    if (pixels.len < 1 or pixels.len > 128 * 1024) return error.InvalidCursorImage;
    return pixels;
}
fn payloadText(payload: ?*Queue.Clipboard.Payload) ?[]const u8 {
    return if (payload) |value| value.text else null;
}
fn payloadHtml(payload: ?*Queue.Clipboard.Payload) ?[]const u8 {
    return if (payload) |value| value.html else null;
}
fn payloadUris(payload: ?*Queue.Clipboard.Payload) ?[]const u8 {
    return if (payload) |value| value.uris else null;
}
fn pointer(entry: *Entry, kind: i32, button: anytype, pressed: bool, x: f64, y: f64) !void {
    try entry.events.pointer(entry.key.id, entry.key.generation, kind, @intCast(button), pressed, @floatCast(x), @floatCast(y));
}
/// Ends every gesture Android may still hold for this window.
fn cancelInput(entry: *Entry) !void {
    try pointer(entry, 4, 0, false, 0, 0);
    try entry.events.cancelTouch(entry.key.id, entry.key.generation);
    try entry.events.cancelTablet(entry.key.id, entry.key.generation, -1);
}
fn sendLayout(callback: Events.Client, layout: Queue.KeyboardLayout) !void {
    const characters = try layout.characters();
    const symbols = try layout.symbols();
    try callback.keyboardLayout(layout.generation, &characters, &symbols);
}
fn sendFocus(entry: *Entry, focus: anytype) !void {
    var held: [768]i32 = undefined;
    var count: usize = 0;
    for (0..768) |scan| {
        if (focus.held[scan / 64] & (@as(u64, 1) << @intCast(scan % 64)) == 0) continue;
        held[count] = @intCast(scan);
        count += 1;
    }
    try entry.events.keyboardFocus(entry.key.id, entry.key.generation, focus.active, held[0..count]);
}
pub fn main(init: std.process.Init) !void {
    // run unwinds its owned queues and Wayland imports on both orderly exit
    // and compositor disconnect. Verify that cleanup on either return path.
    const result = run(init);
    if (azk_graphics_live_buffers() != 0) return error.LeakedGraphicsBuffers;
    // The supervisor may have stopped draining stdout before orderly shutdown.
    // Resource verification belongs on stderr with the other native diagnostics.
    try Io.File.stderr().writeStreamingAll(init.io, "task presenter released all buffers\n");
    return result;
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
    server = .{ .gpa = init.gpa, .io = io, .session = session, .wake = wake, .death = death };
    server.inspection_enabled = inspection_enabled;
    if (server.inspection_enabled) if (init.minimal.environ.getPosix("AZK_TEST_WINDOW_PLACEMENT")) |placement| {
        server.test_placement = try TestPlacement.parse(placement);
        // The harness may start on an empty headless seat. Publish its keyboard
        // before the shell snapshots devices and any activity is launched;
        // creating it at the first click would relaunch the selected editor.
        if (session.keyboard == null and session.virtual_keyboard_manager != null)
            try session.prepareTestKeyboard();
    };
    var object: aidl.LocalObject = .{ .context = &server, .transact = transact };
    const binder = object.binder(Presenter);
    defer binder.deinit();
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
            _ = AIBinder_unlinkToDeath(owner.remote.handle, death, null);
            owner.deinit();
        }
        for (&server.entries) |*slot| if (slot.*) |*entry| {
            entry.deinit();
            slot.* = null;
        };
    }
    try aidl.addService(binder, "dev.artzig.window.presenter");
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
                    try pointer(entry, 1, 0, false, entry.window.pointer_x, entry.window.pointer_y);
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
                    if (server.owner) |callback| try callback.nativeDragIssue(@errorName(failure));
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
