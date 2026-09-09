//! Host transport admission, held until a delivered handler and input cleanup
//! have finished, including cleanup after a synchronous reply. The async allowance leaves capacity for
//! synchronous control and nested calls. Caller and recipient share no locks.
const Budget = @This();
const std = @import("std");

pub const Usage = struct {
    bytes: usize = 0,
    fds: usize = 0,
    count: usize = 0,

    pub fn fits(used: Usage, extra: Usage, limit: Usage) bool {
        return extra.bytes <= limit.bytes -| used.bytes and
            extra.fds <= limit.fds -| used.fds and
            extra.count <= limit.count -| used.count;
    }
    pub fn add(used: *Usage, extra: Usage) void {
        used.bytes += extra.bytes;
        used.fds += extra.fds;
        used.count += extra.count;
    }
    pub fn subtract(used: *Usage, extra: Usage) void {
        used.bytes -= extra.bytes;
        used.fds -= extra.fds;
        used.count -= extra.count;
    }
};

pub const Limits = struct {
    total: Usage = .{ .bytes = 32 * 1024 * 1024, .fds = 1024, .count = 4096 },
    asynchronous: Usage = .{ .bytes = 16 * 1024 * 1024, .fds = 512, .count = 2048 },
};
pub const global_limits: Limits = .{
    .total = .{ .bytes = 256 * 1024 * 1024, .fds = 8192, .count = 32768 },
    .asynchronous = .{ .bytes = 128 * 1024 * 1024, .fds = 4096, .count = 16384 },
};

total: Usage = .{},
asynchronous: Usage = .{},

pub fn fits(b: Budget, cost: Usage, asynchronous: bool, limits: Limits) bool {
    return b.total.fits(cost, limits.total) and
        (!asynchronous or b.asynchronous.fits(cost, limits.asynchronous));
}
pub fn add(b: *Budget, cost: Usage, asynchronous: bool) void {
    b.total.add(cost);
    if (asynchronous) b.asynchronous.add(cost);
}
pub fn subtract(b: *Budget, cost: Usage, asynchronous: bool) void {
    b.total.subtract(cost);
    if (asynchronous) b.asynchronous.subtract(cost);
}

test "async admission preserves synchronous capacity and independently bounds resources" {
    const t = std.testing;
    const limits: Limits = .{ .total = .{ .bytes = 8, .fds = 4, .count = 6 }, .asynchronous = .{ .bytes = 4, .fds = 2, .count = 3 } };
    for ([_]Usage{ .{ .bytes = 4 }, .{ .fds = 2 }, .{ .count = 3 } }) |cost| {
        var b: Budget = .{};
        try t.expect(b.fits(cost, true, limits));
        b.add(cost, true);
        try t.expect(!b.fits(cost, true, limits));
        try t.expect(b.fits(cost, false, limits));
        b.add(cost, false);
        try t.expect(!b.fits(cost, false, limits));
        b.subtract(cost, false);
        b.subtract(cost, true);
        try t.expectEqualDeep(Budget{}, b);
    }
}
