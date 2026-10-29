const std = @import("std");

// Bionic's struct addrinfo: ai_canonname precedes ai_addr, unlike glibc.
const AddrInfo = extern struct {
    flags: c_int,
    family: c_int,
    socktype: c_int,
    protocol: c_int,
    addrlen: u32,
    canonname: ?[*:0]u8,
    addr: ?*anyopaque,
    next: ?*AddrInfo,
};
const ai_numerichost = 4;
const af_inet = 2;

extern fn bionic_getaddrinfo(?[*:0]const u8, ?[*:0]const u8, ?*const AddrInfo, *?*AddrInfo) callconv(.c) c_int;
extern fn bionic_freeaddrinfo(?*AddrInfo) callconv(.c) void;

fn require(ok: bool) !void {
    if (!ok) return error.BionicLibcContractFailed;
}

pub fn main() !void {
    const numeric = AddrInfo{ .flags = ai_numerichost, .family = 0, .socktype = 0, .protocol = 0, .addrlen = 0, .canonname = null, .addr = null, .next = null };
    // Applications pass an uninitialized result and free it when non-NULL.
    // Bionic's getaddrinfo stores NULL on failure.
    var stale = numeric;
    const poison: *AddrInfo = &stale;
    var result: ?*AddrInfo = poison;
    try require(bionic_getaddrinfo("not-a-numeric-address", null, &numeric, &result) != 0);
    try require(result == null);
    bionic_freeaddrinfo(result);

    // The NULL host and service check precedes Bionic's result reset.
    result = poison;
    try require(bionic_getaddrinfo(null, null, &numeric, &result) != 0);
    try require(result == poison);

    result = poison;
    try require(bionic_getaddrinfo("127.0.0.1", null, &numeric, &result) == 0);
    const first = result orelse return error.MissingAddress;
    try require(first.family == af_inet and first.addr != null and first.addrlen == 16);
    bionic_freeaddrinfo(result);
    std.debug.print("Bionic getaddrinfo result contract: PASS\n", .{});
}
