//! Native media service lifetime is owned by the hosted session, like Android init.
const std = @import("std");
const Io = std.Io;

extern "c" fn azk_media_service_start() c_int;
extern "c" fn azk_media_service_exit(c_int) noreturn;

pub fn main(init: std.process.Init) !void {
    for ([_][]const u8{ "AZK_BINDER_SOCKET", "AZK_BINDER_COOKIE", "AZK_BINDER_SYSTEM_COOKIE", "AZK_PROP_AREA" }) |name| {
        if (init.minimal.environ.getPosix(name) == null) return error.MissingSessionEnvironment;
    }
    if (azk_media_service_start() != 0) {
        std.log.err("native media services could not start", .{});
        azk_media_service_exit(1);
    }
    const io = init.io;
    Io.File.stdout().writeStreamingAll(io, "ready\n") catch azk_media_service_exit(1);
    var bytes: [64]u8 = undefined;
    while (true) {
        const count = Io.File.stdin().readStreaming(io, &.{&bytes}) catch break;
        if (count == 0) break;
    }
    azk_media_service_exit(0);
}
