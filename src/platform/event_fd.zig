//! Linux eventfd creation with std.Io ownership and operations.
//!
//! std.Io does not currently expose an eventfd constructor, so creation is the
//! only raw Linux operation. The resulting descriptor is immediately wrapped
//! in std.Io.File; reads, writes, and close always go through std.Io.
const std = @import("std");
const linux = std.os.linux;

pub const EventFd = struct {
    io: std.Io,
    file: std.Io.File,

    pub fn init(io: std.Io) !EventFd {
        const rc = linux.eventfd(0, linux.EFD.CLOEXEC | linux.EFD.NONBLOCK);
        if (linux.errno(rc) != .SUCCESS) return error.EventFdFailed;
        return .{
            .io = io,
            .file = .{
                .handle = @intCast(rc),
                .flags = .{ .nonblocking = true },
            },
        };
    }

    pub fn fd(self: EventFd) std.posix.fd_t {
        return self.file.handle;
    }

    pub fn signal(self: EventFd) void {
        var value: u64 = 1;
        self.file.writeStreamingAll(self.io, std.mem.asBytes(&value)) catch {};
    }

    pub fn consume(self: EventFd) u64 {
        var value: u64 = 0;
        const bytes = std.mem.asBytes(&value);
        const count = self.file.readStreaming(self.io, &.{bytes}) catch return 0;
        return if (count == bytes.len) value else 0;
    }

    pub fn close(self: EventFd) void {
        self.file.close(self.io);
    }
};

test "eventfd coalesces std.Io writes" {
    const event = try EventFd.init(std.testing.io);
    defer event.close();
    event.signal();
    event.signal();
    try std.testing.expectEqual(@as(u64, 2), event.consume());
    try std.testing.expectEqual(@as(u64, 0), event.consume());
}
