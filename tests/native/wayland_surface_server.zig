const std = @import("std");
const Queue = @import("surface_queue");
const Io = std.Io;
extern "c" fn AServiceManager_addService(?*anyopaque, [*:0]const u8) i32;
extern "c" fn AIBinder_decStrong(?*anyopaque) void;
extern "c" fn azk_graphics_live_buffers() u64;
const Stop = struct { io: Io, queue: *Queue, hold: std.atomic.Value(bool), stopped: std.atomic.Value(bool) = .init(false) };
fn watchStdin(stop: *Stop) void {
    var bytes: [16]u8 = undefined;
    while (true) {
        const count = Io.File.stdin().readStreaming(stop.io, &.{&bytes}) catch break;
        if (count == 0) break;
        stop.hold.store(false, .release);
        stop.queue.requestPresentation();
    }
    stop.stopped.store(true, .release);
}
pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len == 2 and std.mem.eql(u8, args[1], "--keyboard-seat")) {
        const session = try Queue.Session.init(init.gpa, init.io);
        defer session.deinit();
        try session.prepareTestKeyboard();
        try Io.File.stdout().writeStreamingAll(init.io, "ready\n");
        var buffer: [1]u8 = undefined;
        while (true) {
            const count = Io.File.stdin().readStreaming(init.io, &.{&buffer}) catch |err| switch (err) {
                error.EndOfStream => break,
                else => return err,
            };
            if (count == 0) break;
            switch (buffer[0]) {
                'c' => try session.testKeyboardShortcut(62, 1 << 3), // Alt+F4: compositor close.
                'p', 'r' => {
                    try session.testPointerPresent(buffer[0] == 'p');
                    var status: [48]u8 = undefined;
                    const line = try std.fmt.bufPrint(&status, "pointer updated {d}\n", .{session.seat_capabilities});
                    try Io.File.stdout().writeStreamingAll(init.io, line);
                },
                'd', 'u' => {
                    try session.testKeyboardLayout(buffer[0] == 'd');
                    try Io.File.stdout().writeStreamingAll(init.io, "keyboard updated\n");
                },
                '\n' => {},
                else => return error.UnknownKeyboardSeatCommand,
            }
        }
        return;
    }
    try run(init);
    if (azk_graphics_live_buffers() != 0) return error.LeakedGraphicsBuffer;
    try Io.File.stdout().writeStreamingAll(init.io, "released all buffers\n");
}
fn run(init: std.process.Init) !void {
    const io = init.io;
    const queue = try Queue.init(init.gpa, io, 320, 240);
    defer queue.deinit();
    const session = try Queue.Session.init(init.gpa, io);
    defer session.deinit();
    var sibling: ?*Queue.Window = try session.createWindow(160, 120, "Sibling task", false);
    defer if (sibling) |window| window.deinit();
    const window = try session.createWindow(320, 240, "Stock Surface / AZKB", false);
    defer window.deinit();
    std.debug.assert(session.windows.items.len == 2);
    std.debug.assert(window.session == sibling.?.session);
    sibling.?.deinit();
    sibling = null;
    std.debug.assert(session.windows.items.len == 1);
    // The following producer frames must still render after the sibling closes.
    const producer = queue.producer();
    defer AIBinder_decStrong(producer);
    if (AServiceManager_addService(producer, "dev.artzig.surface.v1") != 0) return error.PublishFailed;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    const unavailable = args.len > 1 and std.mem.eql(u8, args[1], "--pointer-capture-unavailable");
    const capture = unavailable or (args.len > 1 and std.mem.eql(u8, args[1], "--pointer-capture"));
    var captured = false;
    const hold = args.len > 1 and std.mem.eql(u8, args[1], "--hold-presentation");
    var stop: Stop = .{ .io = io, .queue = queue, .hold = .init(hold) };
    var group: Io.Group = .init;
    try group.concurrent(io, watchStdin, .{&stop});
    defer group.cancel(io);
    try Io.File.stdout().writeStreamingAll(io, "ready\n");
    var frames: usize = 0;
    while (!stop.stopped.load(.acquire)) {
        var ready = [_]bool{false};
        try session.dispatch(&.{queue.notify_file}, &ready, 100);
        for (window.takeEvents()) |event| if (event == .close) return;
        if (ready[0]) {
            if (stop.hold.load(.acquire)) {
                continue;
            }
            frames += try queue.presentNotified(window);
            var buffer: [80]u8 = undefined;
            const line = try std.fmt.bufPrint(&buffer, "presented {d}\n", .{frames});
            try Io.File.stdout().writeStreamingAll(io, line);
            if (capture and !captured and frames >= 12) {
                try verifyCapture(session, window, io, queue.notify_file, unavailable);
                captured = true;
                try Io.File.stdout().writeStreamingAll(io, if (unavailable) "pointer capture unavailable passed\n" else "pointer capture passed\n");
            }
        }
    }
}

fn verifyCapture(session: *Queue.Session, window: *Queue.Window, io: Io, wake: Io.File, unavailable: bool) !void {
    try window.prepareTestInput();
    try window.testTap(@divTrunc(window.bufferWidth(), 2), @divTrunc(window.bufferHeight(), 2));
    var phase: enum { focus, lock, relative, unlock } = .focus;
    const deadline = Io.Clock.awake.now(io).nanoseconds + 5 * std.time.ns_per_s;
    while (Io.Clock.awake.now(io).nanoseconds < deadline) {
        var ready = [_]bool{false};
        try session.dispatch(&.{wake}, &ready, 100);
        for (window.takeEvents()) |event| switch (event) {
            .close => return error.UnexpectedClose,
            .pointer_capture_changed => |state| {
                if (phase == .lock and state.enabled) {
                    if (state.sequence != 101) return error.WrongCaptureSequence;
                    phase = .relative;
                    try window.testRelativeMotion(7, -5);
                } else if (phase == .unlock and !state.enabled) {
                    if (state.sequence != 102) return error.WrongCaptureSequence;
                    if (window.hasPointerCapture()) return error.CaptureStillActive;
                    return;
                }
            },
            .pointer_relative => |motion| if (phase == .relative) {
                if (motion.dx != 7 or motion.dy != -5) return error.WrongRelativeMotion;
                phase = .unlock;
                try window.requestPointerCapture(false, 102);
            },
            else => {},
        };
        if (phase == .focus and session.pointer_focus == window and session.keyboard_focus == window) {
            phase = .lock;
            window.requestPointerCapture(true, 101) catch |err| {
                if (!unavailable or (err != error.PointerConstraintsUnavailable and err != error.RelativePointerUnavailable)) return err;
                if (window.hasPointerCapture() or window.pointer_capture_requested or
                    window.locked_pointer != null or window.relative_pointer != null) return error.CaptureFailureLeakedState;
                return;
            };
            if (unavailable) return error.ExpectedUnsupportedCapture;
            if (window.hasPointerCapture()) return error.PrematureCaptureAcknowledgement;
        }
    }
    std.log.err("capture timeout phase={s} pointer={any} keyboard={any} requested={any} actual={any} size={d}x{d} cursor={d},{d}", .{ @tagName(phase), session.pointer_focus == window, session.keyboard_focus == window, window.pointer_capture_requested, window.hasPointerCapture(), window.width, window.height, window.pointer_x, window.pointer_y });
    return error.PointerCaptureTimeout;
}
