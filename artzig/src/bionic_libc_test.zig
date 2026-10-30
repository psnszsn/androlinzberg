const std = @import("std");

// Bionic's struct addrinfo: ai_canonname precedes ai_addr, unlike glibc.
const AddrInfo = extern struct {
    flags: c_int = 0,
    family: c_int = 0,
    socktype: c_int = 0,
    protocol: c_int = 0,
    addrlen: u32 = 0,
    canonname: ?[*:0]u8 = null,
    addr: ?*anyopaque = null,
    next: ?*AddrInfo = null,
};
const SockaddrIn = extern struct { family: u16, port: u16, addr: [4]u8, zero: [8]u8 = @splat(0) };

// Pinned bionic/libc/include/netdb.h values.
const ai_numerichost = 0x4;
const ai_numericserv = 0x8;
const ai_addrconfig = 0x400;
const ai_v4mapped = 0x800;
const ni_numerichost = 0x2;
const ni_numericserv = 0x8;
const eai_badflags = 3;
const eai_nodata = 7;
const eai_noname = 8;
const eai_badhints = 12;
const af_inet = 2;
const sock_stream = 1;
const ipproto_udp = 17;

extern fn bionic_getaddrinfo(?[*:0]const u8, ?[*:0]const u8, ?*const AddrInfo, *?*AddrInfo) callconv(.c) c_int;
extern fn bionic_freeaddrinfo(?*AddrInfo) callconv(.c) void;
extern fn bionic_getnameinfo(*const anyopaque, u32, [*]u8, usize, [*]u8, usize, c_int) callconv(.c) c_int;
extern fn bionic_gai_strerror(c_int) callconv(.c) [*:0]const u8;

fn require(ok: bool) !void {
    if (!ok) return error.BionicLibcContractFailed;
}

fn port(result: ?*AddrInfo) !u16 {
    const first = result orelse return error.MissingAddress;
    const address: *const SockaddrIn = @ptrCast(@alignCast(first.addr orelse return error.MissingAddress));
    return std.mem.bigToNative(u16, address.port);
}

pub fn main() !void {
    const numeric = AddrInfo{ .flags = ai_numerichost };
    // Applications pass an uninitialized result and free it when non-NULL.
    // Bionic's getaddrinfo stores NULL on failure.
    var stale = numeric;
    const poison: *AddrInfo = &stale;
    var result: ?*AddrInfo = poison;
    try require(bionic_getaddrinfo("not-a-numeric-address", null, &numeric, &result) == eai_noname);
    try require(result == null);
    bionic_freeaddrinfo(result);
    try require(std.mem.eql(u8, std.mem.span(bionic_gai_strerror(eai_noname)), "hostname nor servname provided, or not known"));
    try require(std.mem.eql(u8, std.mem.span(bionic_gai_strerror(eai_nodata)), "No address associated with hostname"));
    try require(std.mem.eql(u8, std.mem.span(bionic_gai_strerror(-2)), "Unknown error"));

    // The NULL host and service check precedes Bionic's result reset.
    result = poison;
    try require(bionic_getaddrinfo(null, null, &numeric, &result) == eai_noname);
    try require(result == poison);

    result = poison;
    try require(bionic_getaddrinfo("127.0.0.1", null, &numeric, &result) == 0);
    const first = result orelse return error.MissingAddress;
    try require(first.family == af_inet and first.addr != null and first.addrlen == 16);
    bionic_freeaddrinfo(result);

    // Bionic's AI_NUMERICSERV is glibc's AI_V4MAPPED; its AI_ADDRCONFIG is
    // glibc's AI_NUMERICSERV.
    const numeric_service = AddrInfo{ .flags = ai_numerichost | ai_numericserv, .family = af_inet, .socktype = sock_stream };
    try require(bionic_getaddrinfo("127.0.0.1", "http", &numeric_service, &result) == eai_noname);
    try require(bionic_getaddrinfo("127.0.0.1", "80", &numeric_service, &result) == 0);
    try require(try port(result) == 80);
    bionic_freeaddrinfo(result);
    const address_config = AddrInfo{ .flags = ai_numerichost | ai_addrconfig, .family = af_inet, .socktype = sock_stream };
    try require(bionic_getaddrinfo("127.0.0.1", "http", &address_config, &result) == 0);
    try require(try port(result) == 80);
    bionic_freeaddrinfo(result);

    // Bionic validates hints before resolving and rejects flags outside AI_MASK.
    result = poison;
    try require(bionic_getaddrinfo("127.0.0.1", null, &.{ .flags = ai_v4mapped }, &result) == eai_badflags);
    try require(result == null);
    try require(bionic_getaddrinfo("127.0.0.1", null, &.{ .flags = ai_numerichost, .addrlen = 16 }, &result) == eai_badhints);
    try require(bionic_getaddrinfo("127.0.0.1", null, &.{ .socktype = sock_stream, .protocol = ipproto_udp }, &result) == eai_badhints);

    // Bionic's NI_NUMERICHOST is glibc's NI_NUMERICSERV.
    const loopback = SockaddrIn{ .family = af_inet, .port = std.mem.nativeToBig(u16, 80), .addr = .{ 127, 0, 0, 1 } };
    var host: [64]u8 = undefined;
    var service: [16]u8 = undefined;
    try require(bionic_getnameinfo(&loopback, @sizeOf(SockaddrIn), &host, host.len, &service, service.len, ni_numerichost | ni_numericserv) == 0);
    try require(std.mem.eql(u8, std.mem.sliceTo(&host, 0), "127.0.0.1"));
    try require(std.mem.eql(u8, std.mem.sliceTo(&service, 0), "80"));
    std.debug.print("Bionic netdb contract: PASS\n", .{});
}
