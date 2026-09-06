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
    try run(init);
    if (azk_graphics_live_buffers() != 0) return error.LeakedGraphicsBuffer;
    try Io.File.stdout().writeStreamingAll(init.io, "released all buffers\n");
}
fn run(init: std.process.Init) !void {
    const io = init.io;
    const queue = try Queue.init(init.gpa, io, 320, 240);
    defer queue.deinit();
    const window = try Queue.Window.init(init.gpa, io, 320, 240, "Stock Surface / AZKB");
    defer window.deinit();
    const producer = queue.producer();
    defer AIBinder_decStrong(producer);
    if (AServiceManager_addService(producer, "dev.artzig.surface.v1") != 0) return error.PublishFailed;
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    const hold = args.len > 1 and std.mem.eql(u8, args[1], "--hold-presentation");
    var stop: Stop = .{ .io = io, .queue = queue, .hold = .init(hold) };
    var group: Io.Group = .init;
    try group.concurrent(io, watchStdin, .{&stop});
    defer group.cancel(io);
    try Io.File.stdout().writeStreamingAll(io, "ready\n");
    var frames: usize = 0;
    while (!stop.stopped.load(.acquire)) {
        const events = try window.waitEvents(.{ .renderer = queue.notify_file.handle, .timeout_ms = 100 });
        for (events.events) |event| if (event == .close) return;
        if (events.renderer_ready) {
            if (stop.hold.load(.acquire)) {
                try queue.drainNotification();
                continue;
            }
            frames += try queue.presentReady(window);
            var buffer: [80]u8 = undefined;
            const line = try std.fmt.bufPrint(&buffer, "presented {d}\n", .{frames});
            try Io.File.stdout().writeStreamingAll(io, line);
        }
    }
}
