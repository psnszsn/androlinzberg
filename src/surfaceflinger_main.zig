//! Supervised hosted SurfaceFlinger. The session supplies its Binder identity,
//! reads stdout readiness, and keeps stdin open for the process lifetime.
const std = @import("std");
const Io = std.Io;

extern "c" fn azk_surfaceflinger_run(*const fn (?*anyopaque) callconv(.c) void, ?*anyopaque) c_int;
extern "c" fn azk_surfaceflinger_exit(c_int) noreturn;

const Supervisor = struct {
    io: Io,
    group: Io.Group = .init,

    fn ready(context: ?*anyopaque) callconv(.c) void {
        const self: *Supervisor = @ptrCast(@alignCast(context.?));
        self.group.concurrent(self.io, watch, .{self.io}) catch azk_surfaceflinger_exit(1);
        Io.File.stdout().writeStreamingAll(self.io, "ready\n") catch azk_surfaceflinger_exit(1);
    }

    fn watch(io: Io) void {
        var bytes: [64]u8 = undefined;
        while (true) {
            const count = Io.File.stdin().readStreaming(io, &.{&bytes}) catch break;
            if (count == 0) break;
        }
        // Stock SF's main loop has process lifetime. Exiting drops its broker
        // connection and notifies clients. The C++ service seam ends its runtime
        // without global destructors; std.process.exit would run libc atexit.
        azk_surfaceflinger_exit(0);
    }
};

pub fn main(init: std.process.Init) !void {
    for ([_][]const u8{ "AZK_BINDER_SOCKET", "AZK_BINDER_COOKIE", "AZK_BINDER_SYSTEM_COOKIE", "AZK_PROP_AREA" }) |name| {
        if (init.minimal.environ.getPosix(name) == null) return error.MissingSessionEnvironment;
    }
    var supervisor: Supervisor = .{ .io = init.io };
    if (azk_surfaceflinger_run(Supervisor.ready, &supervisor) != 0)
        return error.SurfaceFlingerStartupFailed;
}
