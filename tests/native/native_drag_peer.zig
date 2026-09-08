//! Native data-source peer for the existing shot.sh compositor harness.
const std = @import("std");
const Io = std.Io;
const Queue = @import("surface_queue");
const State = struct {
    mime: [:0]const u8,
    bytes: []const u8,
    accepted: bool = false,
    copy: bool = false,
    dropped: bool = false,
    finished: bool = false,
    cancelled: bool = false,
    sends: u32 = 0,
    start: ?[]const u8 = null,
    rejected_start: bool = false,
    aborted_start: ?[]const u8 = null,
    failure: ?anyerror = null,
    fn event(context: *anyopaque, _: u64, value: Queue.Clipboard.DragSource.Event) void {
        const self: *State = @ptrCast(@alignCast(context));
        switch (value) {
            .accepted => |accepted| self.accepted = accepted,
            .copy => |copy| self.copy = copy,
            .dropped => self.dropped = true,
            .ended => |consumed| {
                self.finished = consumed;
                self.cancelled = !consumed;
            },
        }
    }
    fn issue(context: *anyopaque, failure: anyerror) void {
        const self: *State = @ptrCast(@alignCast(context));
        self.failure = failure;
    }
};

pub fn run(init: std.process.Init, args: []const [:0]const u8) !void {
    if (args.len != 6) return error.ExpectedMimePayloadResultAndAcceptance;
    var state: State = .{ .mime = args[2], .bytes = args[3] };
    const consumed = std.mem.eql(u8, args[5], "accept");
    const cancelled = std.mem.eql(u8, args[5], "cancel");
    if (!consumed and !cancelled and !std.mem.eql(u8, args[5], "reject")) return error.InvalidExpectedResult;
    defer {
        const report = std.json.Stringify.valueAlloc(init.gpa, .{
            .accepted = state.accepted,
            .copy = state.copy,
            .dropped = state.dropped,
            .finished = state.finished,
            .cancelled = state.cancelled,
            .sends = state.sends,
            .start = state.start,
            .rejected_start = state.rejected_start,
            .aborted_start = state.aborted_start,
            .failure = if (state.failure) |err| @errorName(err) else null,
        }, .{ .whitespace = .indent_2 }) catch unreachable;
        defer init.gpa.free(report);
        Io.Dir.cwd().writeFile(init.io, .{ .sub_path = args[4], .data = report }) catch {};
    }
    exercise(init, &state, consumed, cancelled) catch |err| {
        state.failure = err;
        return err;
    };
}

fn exercise(init: std.process.Init, state: *State, consumed: bool, cancelled: bool) !void {
    const session = try Queue.Session.initWithOptions(init.gpa, init.io, .{ .clipboard = true });
    defer session.deinit();
    const window = try session.createWindowWithAppId(320, 480, "Native drag source", "dev.artzig.drag-peer", false);
    defer window.deinit();
    try window.prepareTestInput();
    if (std.mem.startsWith(u8, state.mime, "text/plain")) {
        try ownRoundtrip(session, window, true);
        try ownRoundtrip(session, window, false);
    }
    // The harness explicitly places this peer at (720,0), 320x480. These
    // output coordinates are test input, never inferred from Android bounds.
    try window.testPointerAtOutput(.{ 800, 240 }, .{ 1280, 720 }, .{ 80, 240 }, true);
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    const first_generation = session.dragClaim(window, .pointer, 0) orelse return error.NoPressedGesture;
    var aborted: State = .{ .mime = state.mime, .bytes = state.bytes };
    const aborted_payload = try Queue.Clipboard.Payload.init(init.gpa, "Aborted drag start", null);
    const aborted_source = try session.startDrag(window, .pointer, 0, first_generation, null, aborted_payload, .{ .context = &aborted, .event = State.event, .issue = State.issue });
    aborted_source.cancel(); // The request could already be reaching the compositor.
    state.aborted_start = @tagName(aborted_source.startState());
    if (aborted_source.startState() != .unknown or session.dragClaim(window, .pointer, 0) != null)
        return error.AbortedStartWasConsideredSafeToResume;
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    try Queue.Window.releaseTestPointer(session);
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    try window.testPointerAtOutput(.{ 800, 240 }, .{ 1280, 720 }, .{ 80, 240 }, true);
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    const generation = session.dragClaim(window, .pointer, 0) orelse return error.NoFreshPressedGesture;
    if (generation == first_generation) return error.AbortedGestureGenerationWasReused;
    // Protocol-negative probe: bypass the session's validated API only here.
    // Rejection must leave the real grab available for the subsequent handoff.
    var rejected: State = .{ .mime = state.mime, .bytes = state.bytes };
    const invalid_payload = try Queue.Clipboard.Payload.init(init.gpa, "Invalid drag serial", null);
    const invalid_source = try session.clipboard.?.startDrag(window.surface, session.clipboard.?.serial.? +% 0x80000000, null, invalid_payload, .{ .context = &rejected, .event = State.event, .issue = State.issue });
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    if (invalid_source.startState() != .rejected or !rejected.cancelled or rejected.finished or
        rejected.dropped or invalid_source.sends != 0 or session.dragClaim(window, .pointer, 0) != generation)
        return error.InvalidSerialLostPhysicalGrab;
    state.rejected_start = true;
    // No other input is emitted between that actual button event and start_drag.
    const plain = std.mem.startsWith(u8, state.mime, "text/plain");
    const html = std.mem.eql(u8, state.mime, "text/html");
    const uris = std.mem.eql(u8, state.mime, "text/uri-list");
    const payload = try Queue.Clipboard.Payload.initAll(init.gpa, if (plain) state.bytes else null, if (html) state.bytes else null, if (uris) state.bytes else null);
    const source = try session.startDrag(window, .pointer, 0, generation, null, payload, .{ .context = state, .event = State.event, .issue = State.issue });
    if (session.dragClaim(window, .pointer, 0) != null) return error.DragGestureNotConsumed;
    defer state.sends = source.sends;
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    try window.testPointerAtOutput(.{ 480, 273 }, .{ 1280, 720 }, null, false);
    state.start = @tagName(source.startState());
    if (source.startState() != .accepted) return error.NativeDragNotAccepted;
    var released = false;
    defer if (!released) Queue.Window.releaseTestPointer(session) catch {};
    const deadline = Io.Clock.awake.now(init.io).toMilliseconds() + 7_000;
    while (!state.finished and !state.cancelled) {
        if (state.failure) |err| return err;
        if (Io.Clock.awake.now(init.io).toMilliseconds() >= deadline) return error.NativeDragTimedOut;
        var ready: [0]bool = .{};
        try session.dispatch(&.{}, &ready, 20);
        _ = window.takeEvents();
        if (state.accepted and state.copy and !released) {
            if (cancelled) try window.testPointerAtOutput(.{ 1150, 600 }, .{ 1280, 720 }, null, false);
            try Queue.Window.releaseTestPointer(session);
            released = true;
        }
    }
    if (!released or source.sends == 0 or state.dropped == cancelled) return error.NativeDragWasNotDelivered;
    if (consumed != state.finished or consumed == state.cancelled) return error.NativeRecipientResultMismatch;
}

const OwnOffer = struct {
    token: u64 = 0,
    source_token: u64 = 0,
    enters: u32 = 0,
    dropped: bool = false,
    payloads: u32 = 0,
    failure: ?anyerror = null,
    fn event(context: *anyopaque, value: Queue.Clipboard.DragOffer.Event) void {
        const self: *OwnOffer = @ptrCast(@alignCast(context));
        switch (value) {
            .entered => |entry| {
                self.token = entry.token;
                self.source_token = entry.source_token;
                self.enters += 1;
                if (entry.representations != 0) self.failure = error.PrivateSourceExportedRepresentations;
            },
            .payload => |payload| {
                self.payloads += 1;
                payload.value.destroy();
            },
            .dropped => self.dropped = true,
            .motion, .ended => {},
        }
    }
    fn issue(context: *anyopaque, failure: anyerror) void {
        const self: *OwnOffer = @ptrCast(@alignCast(context));
        self.failure = failure;
    }
};

/// Use real compositor offers for a source entering its own connection. A
/// private marker carries no bytes and does not replace Android authorization.
fn ownRoundtrip(session: *Queue.Session, window: *Queue.Window, authorized: bool) !void {
    const clipboard = session.clipboard.?;
    var offer: OwnOffer = .{};
    clipboard.native_drag = .{ .io = session.io, .hooks = .{ .context = &offer, .event = OwnOffer.event, .issue = OwnOffer.issue } };
    defer {
        clipboard.native_drag.?.cancel();
        clipboard.native_drag = null;
    }
    try window.testPointerAtOutput(.{ 800, 240 }, .{ 1280, 720 }, .{ 80, 240 }, true);
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    const generation = session.dragClaim(window, .pointer, 0) orelse return error.NoOwnSourceGrab;
    var result: State = .{ .mime = "private", .bytes = "" };
    const source = try session.startDrag(window, .pointer, 0, generation, null, null, .{ .context = &result, .event = State.event, .issue = State.issue });
    defer source.cancel();
    try window.testPointerAtOutput(.{ 810, 240 }, .{ 1280, 720 }, null, false);
    var released = false;
    defer if (!released) Queue.Window.releaseTestPointer(session) catch {};
    var left = false;
    var reentered = false;
    var completed = false;
    var first_token: u64 = 0;
    const deadline = Io.Clock.awake.now(session.io).toMilliseconds() + 5000;
    while (!result.finished and !result.cancelled) {
        if (Io.Clock.awake.now(session.io).toMilliseconds() > deadline) {
            std.log.err("own source timeout enters={d} left={any} reentered={any} released={any} dropped={any} completed={any}", .{ offer.enters, left, reentered, released, offer.dropped, completed });
            return error.OwnSourceTimedOut;
        }
        var ready: [0]bool = .{};
        try session.dispatch(&.{}, &ready, 20);
        _ = window.takeEvents();
        if (offer.failure) |failure| return failure;
        if (result.failure) |failure| return failure;
        if (offer.enters > 0 and !left) {
            if (offer.source_token != source.token) return error.OwnSourceIdentityLost;
            first_token = offer.token;
            try window.testPointerAtOutput(.{ 1150, 600 }, .{ 1280, 720 }, null, false);
            left = true;
        } else if (left and !reentered and clipboard.native_drag.?.active == null) {
            if (!source.active()) return error.LeaveEndedOriginalSource;
            try window.testPointerAtOutput(.{ 820, 240 }, .{ 1280, 720 }, null, false);
            reentered = true;
        } else if (reentered and offer.enters == 2 and !released) {
            if (offer.token == first_token or offer.source_token != source.token) return error.OwnSourceReentryIdentityLost;
            if (!clipboard.native_drag.?.accept(offer.token, true)) return error.OwnOfferRejected;
            try Queue.Window.releaseTestPointer(session);
            released = true;
        }
        if (offer.dropped and !completed) {
            // A foreign peer can finish a marker; only the Android-authorized
            // acknowledgement may set consumption for a private-only source.
            const applied = if (authorized) clipboard.finishNativeDrag(offer.token, true) else clipboard.native_drag.?.finish(offer.token, true);
            if (!applied) return error.OwnDropNotReady;
            completed = true;
        }
    }
    if (!completed or result.finished != authorized or result.cancelled == authorized or
        source.sends != 0 or offer.payloads != 0) return error.PrivateSourceAuthorizationMismatch;
}

const Destination = struct {
    token: u64 = 0,
    payload: ?*Queue.Clipboard.Payload = null,
    dropped: bool = false,
    finished: bool = false,
    terminal: bool = false,
    failure: ?anyerror = null,
    fn event(context: *anyopaque, value: Queue.Clipboard.DragOffer.Event) void {
        const self: *Destination = @ptrCast(@alignCast(context));
        switch (value) {
            .entered => |entry| {
                self.token = entry.token;
                if (entry.source_token != 0) self.failure = error.ForeignAndroidSourceAliasedLocalState;
            },
            .payload => |payload| {
                if (self.payload) |old| old.destroy();
                self.payload = payload.value;
            },
            .dropped => self.dropped = true,
            .ended => |ended| {
                self.finished = ended.consumed;
                self.terminal = true;
            },
            .motion => {},
        }
    }
    fn issue(context: *anyopaque, failure: anyerror) void {
        const self: *Destination = @ptrCast(@alignCast(context));
        self.failure = failure;
    }
};

pub fn destination(init: std.process.Init, args: []const [:0]const u8) !void {
    if (args.len != 4) return error.ExpectedDestinationResultPath;
    const updating = std.mem.eql(u8, args[3], "accept-update");
    const consumed = updating or std.mem.eql(u8, args[3], "accept");
    const cancel = std.mem.eql(u8, args[3], "cancel");
    if (!consumed and !cancel and !std.mem.eql(u8, args[3], "reject")) return error.InvalidDestinationOutcome;
    var state: Destination = .{};
    defer if (state.payload) |payload| payload.destroy();
    defer {
        const report = std.json.Stringify.valueAlloc(init.gpa, .{
            .token = state.token,
            .dropped = state.dropped,
            .finished = state.finished,
            .text = if (state.payload) |payload| payload.text else null,
            .uris = if (state.payload) |payload| payload.uris else null,
            .failure = if (state.failure) |failure| @errorName(failure) else null,
        }, .{ .whitespace = .indent_2 }) catch unreachable;
        defer init.gpa.free(report);
        Io.Dir.cwd().writeFile(init.io, .{ .sub_path = args[2], .data = report }) catch {};
    }
    receiveAndroid(init, &state, args[2], consumed, cancel, updating) catch |failure| {
        state.failure = failure;
        return failure;
    };
}
fn receiveAndroid(init: std.process.Init, state: *Destination, path: []const u8, consumed: bool, cancel: bool, updating: bool) !void {
    const session = try Queue.Session.initWithOptions(init.gpa, init.io, .{ .clipboard = true });
    defer session.deinit();
    const window = try session.createWindowWithAppId(320, 480, "Native drag destination", "dev.artzig.drag-peer", false);
    defer window.deinit();
    try coloredBackground(session, window);
    const clipboard = session.clipboard.?;
    clipboard.native_drag = .{ .io = init.io, .hooks = .{ .context = state, .event = Destination.event, .issue = Destination.issue } };
    try window.prepareTestInput();
    // Android's handle is at (160,273); this peer is explicitly at (720,0).
    // All coordinates are compositor test input. No production desktop position
    // is inferred from an Android task rectangle.
    try window.testPointerAtOutput(.{ 160, 273 }, .{ 1280, 720 }, null, true);
    var released = false;
    defer if (!released) Queue.Window.releaseTestPointer(session) catch {};
    const begin = Io.Clock.awake.now(init.io).toMilliseconds();
    var moved = false;
    var accepted = false;
    var accepted_at: i64 = 0;
    var captured = false;
    var finished = false;
    while (!state.terminal) {
        const now = Io.Clock.awake.now(init.io).toMilliseconds();
        if (now - begin > 9000) return error.AndroidSourceTimedOut;
        var ready: [0]bool = .{};
        try session.dispatch(&.{}, &ready, 20);
        _ = window.takeEvents();
        if (state.failure) |failure| return failure;
        // Give stock View/WMS time to authorize/transfer the actual held grab.
        if (!moved and now - begin >= 500) {
            try window.testPointerAtOutput(.{ 800, 240 }, .{ 1280, 720 }, null, false);
            moved = true;
        }
        if (state.payload != null and !accepted) {
            if (!clipboard.native_drag.?.accept(state.token, consumed or cancel)) return error.AndroidOfferCannotAccept;
            accepted = true;
            accepted_at = now;
        }
        if (accepted and !captured and now - accepted_at >= 400) {
            // Capture the stock SF mirror before a producer update. The second
            // capture must contain the new buffer while the same grab is held.
            try capturePpm(init, path);
            captured = true;
        }
        if (captured and !released and (!updating or now - accepted_at >= 1800)) {
            if (updating) {
                const updated = try std.fmt.allocPrint(init.gpa, "{s}.updated", .{path});
                defer init.gpa.free(updated);
                try capturePpm(init, updated);
            }
            if (cancel) try window.testPointerAtOutput(.{ 1150, 600 }, .{ 1280, 720 }, null, false);
            try Queue.Window.releaseTestPointer(session);
            released = true;
        }
        if (state.dropped and released and !finished) {
            if (!clipboard.finishNativeDrag(state.token, true)) return error.AndroidDropCannotFinish;
            finished = true;
        }
    }
    if (!moved or !released or finished != consumed or state.finished != consumed or state.payload == null) return error.AndroidSourceWasNotDelivered;
    // Flush finish before disconnecting this destination client.
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
}

/// Two explicitly placed Android tasks share the source's native drag identity.
/// This peer supplies physical input only; WMS owns the complete Android drop.
pub fn crossTask(init: std.process.Init) !void {
    try taskGesture(init, .{ 160, 273 }, .{ 960, 273 }, null, false);
}
pub fn slowTask(init: std.process.Init) !void {
    try taskGesture(init, .{ 160, 273 }, .{ 480, 273 }, null, true);
}
pub fn localTask(init: std.process.Init, path: []const u8, target: bool) !void {
    try taskGesture(init, if (target) .{ 800, 273 } else .{ 160, 273 }, if (target) .{ 960, 273 } else .{ 480, 273 }, path, false);
}
fn taskGesture(init: std.process.Init, origin_point: [2]u32, destination_point: [2]u32, capture: ?[]const u8, slow: bool) !void {
    const session = try Queue.Session.init(init.gpa, init.io);
    defer session.deinit();
    const window = try session.createWindowWithAppId(100, 100, "Drag test input", "dev.artzig.drag-driver", false);
    defer window.deinit();
    try window.prepareTestInput();
    try window.testPointerAtOutput(origin_point, .{ 1280, 720 }, null, true);
    var released = false;
    defer if (!released) Queue.Window.releaseTestPointer(session) catch {};
    const begin = Io.Clock.awake.now(init.io).toMilliseconds();
    var moved = false;
    var early_motion = false;
    while (!released) {
        const now = Io.Clock.awake.now(init.io).toMilliseconds();
        var ready: [0]bool = .{};
        try session.dispatch(&.{}, &ready, 20);
        _ = window.takeEvents();
        // The fixture provider takes 800ms. Establish local Android drag focus
        // first, then move within the same View after native takeover.
        if (slow and !early_motion and now - begin >= 100) {
            try window.testPointerAtOutput(.{ origin_point[0] + 40, origin_point[1] }, .{ 1280, 720 }, null, false);
            early_motion = true;
        }
        if (!moved and now - begin >= @as(i64, if (slow) 1100 else 500)) {
            if (capture) |path| try capturePpm(init, path);
            try window.testPointerAtOutput(destination_point, .{ 1280, 720 }, null, false);
            moved = true;
        }
        if (moved and now - begin >= @as(i64, if (slow) 1600 else 1000)) {
            try Queue.Window.releaseTestPointer(session);
            released = true;
        }
    }
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
}

fn capturePpm(init: std.process.Init, path: []const u8) !void {
    const snapshot = try std.process.run(init.gpa, init.io, .{ .argv = &.{ "grim", "-t", "ppm", "-" }, .stdout_limit = .limited(4 * 1024 * 1024), .stderr_limit = .limited(4096), .timeout = .{ .duration = .{ .raw = .fromSeconds(3), .clock = .awake } } });
    defer init.gpa.free(snapshot.stdout);
    defer init.gpa.free(snapshot.stderr);
    if (snapshot.term != .exited or snapshot.term.exited != 0) return error.DragIconCaptureFailed;
    const screenshot = try std.fmt.allocPrint(init.gpa, "{s}.ppm", .{path});
    defer init.gpa.free(screenshot);
    try Io.Dir.cwd().writeFile(init.io, .{ .sub_path = screenshot, .data = snapshot.stdout });
}

/// A static SHM backdrop makes GPU drag alpha observable. The changing shadow
/// still comes from the APK's real stock Surface through SF and DMA-BUF.
fn coloredBackground(session: *Queue.Session, window: *Queue.Window) !void {
    const raw = std.os.linux.memfd_create("drag-peer-background", std.os.linux.MFD.CLOEXEC);
    if (std.os.linux.errno(raw) != .SUCCESS) return error.BackgroundFileCreationFailed;
    const file: Io.File = .{ .handle = @intCast(raw), .flags = .{ .nonblocking = false } };
    defer file.close(session.io);
    try file.writeStreamingAll(session.io, &.{ 50, 100, 200, 0 }); // XRGB, little endian.
    const pool = try session.shm.?.createPool(file.handle, 4);
    defer pool.destroy();
    const buffer = try pool.createBuffer(0, 1, 1, 4, .xrgb8888);
    window.surface.attach(buffer, 0, 0);
    window.surface.damageBuffer(0, 0, 1, 1);
    window.surface.commit();
    window.base_buffer.destroy();
    window.base_buffer = buffer;
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
}
