//! Native data-source peer for the existing shot.sh compositor harness.
const std = @import("std");
const Io = std.Io;
const Queue = @import("surface_queue");
const State = struct {
    io: Io,
    clipboard: ?*Queue.Clipboard = null,
    mime: [:0]const u8,
    bytes: []const u8,
    accepted: bool = false,
    copy: bool = false,
    dropped: bool = false,
    finished: bool = false,
    cancelled: bool = false,
    sends: u32 = 0,
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
    var state: State = .{ .io = init.io, .mime = args[2], .bytes = args[3] };
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
    state.clipboard = session.clipboard orelse return error.NoDataDevice;
    // The harness explicitly places this peer at (720,0), 320x480. These
    // output coordinates are test input, never inferred from Android bounds.
    const previous = state.clipboard.?.serial;
    try window.testPointerAtOutput(.{ 800, 240 }, .{ 1280, 720 }, .{ 80, 240 }, true);
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    const serial = state.clipboard.?.serial orelse return error.NoPressedSerial;
    if (serial == previous or session.pointer_focus != window) return error.PressDidNotReachSource;
    // No other input is emitted between that actual button event and start_drag.
    const plain = std.mem.startsWith(u8, state.mime, "text/plain");
    const html = std.mem.eql(u8, state.mime, "text/html");
    const uris = std.mem.eql(u8, state.mime, "text/uri-list");
    const payload = try Queue.Clipboard.Payload.initAll(init.gpa, if (plain) state.bytes else null, if (html) state.bytes else null, if (uris) state.bytes else null);
    const source = try state.clipboard.?.startDrag(window.surface, serial, null, payload, .{ .context = state, .event = State.event, .issue = State.issue });
    defer state.sends = source.sends;
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    try window.testPointerAtOutput(.{ 480, 273 }, .{ 1280, 720 }, null, false);
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
