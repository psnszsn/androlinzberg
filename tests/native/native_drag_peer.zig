//! Native data-source peer for the existing shot.sh compositor harness.
const std = @import("std");
const Io = std.Io;
const Queue = @import("surface_queue");
const wl = @import("wayland").client.wl;
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
    source_live: bool = false,
    failure: ?anyerror = null,
    fn listener(source: *wl.DataSource, event: wl.DataSource.Event, self: *State) void {
        switch (event) {
            .target => |target| self.accepted = target.mime_type != null,
            .action => |action| self.copy = action.dnd_action.copy,
            .dnd_drop_performed => self.dropped = true,
            .dnd_finished, .cancelled => {
                self.finished = event == .dnd_finished;
                self.cancelled = event == .cancelled;
                // Both events end ownership. Destroy immediately; a later
                // invalidation of the offer cannot undo a completed copy.
                self.source_live = false;
                source.destroy();
            },
            .send => |send| {
                const file: Io.File = .{ .handle = send.fd, .flags = .{ .nonblocking = false } };
                if (!std.mem.eql(u8, std.mem.span(send.mime_type), self.mime)) {
                    file.close(self.io);
                    self.failure = error.UnadvertisedDragMime;
                    return;
                }
                self.clipboard.?.transfers.send(1, file, self.bytes) catch |err| {
                    self.failure = err;
                    return;
                };
                self.sends += 1;
            },
        }
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
    const source = try session.data_device_manager.?.createDataSource();
    state.source_live = true;
    defer if (state.source_live) source.destroy();
    if (source.getVersion() < 3) return error.DragResultProtocolUnavailable;
    source.setListener(*State, State.listener, state);
    source.offer(state.mime);
    source.setActions(.{ .copy = true });
    // The harness explicitly places this peer at (720,0), 320x480. These
    // output coordinates are test input, never inferred from Android bounds.
    const previous = state.clipboard.?.serial;
    try window.testPointerAtOutput(.{ 800, 240 }, .{ 1280, 720 }, .{ 80, 240 }, true);
    if (session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    const serial = state.clipboard.?.serial orelse return error.NoPressedSerial;
    if (serial == previous or session.pointer_focus != window) return error.PressDidNotReachSource;
    // No other input is emitted between that actual button event and start_drag.
    state.clipboard.?.device.startDrag(source, window.surface, null, serial);
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
    if (!released or state.sends == 0 or state.dropped == cancelled) return error.NativeDragWasNotDelivered;
    if (consumed != state.finished or consumed == state.cancelled) return error.NativeRecipientResultMismatch;
}
