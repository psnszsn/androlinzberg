//! Writable Android system partition layout around an immutable ART distribution.
//! Framework SystemCertificateSource reads ANDROID_ROOT/etc/security/cacerts;
//! Android's partition/APEX mount setup is replaced here by a private filesystem
//! overlay. All other entries resolve to the original runtime distribution.
const std = @import("std");

pub const Library = struct { name: []const u8, path: []const u8 };

pub fn create(a: std.mem.Allocator, io: std.Io, runtime_root: []const u8, data_root: []const u8, libraries: []const Library) ![]const u8 {
    try std.Io.Dir.cwd().createDirPath(io, data_root);
    var nonce: [16]u8 = undefined;
    io.random(&nonce);
    const path = try std.fmt.allocPrint(a, "{s}/system-{s}", .{ data_root, std.fmt.bytesToHex(nonce, .lower) });
    try std.Io.Dir.cwd().createDir(io, path, .fromMode(0o700));
    errdefer std.Io.Dir.cwd().deleteTree(io, path) catch {};
    try overlayLevel(a, io, runtime_root, path, "etc");
    const etc = try std.fs.path.join(a, &.{ path, "etc" });
    try overlayLevel(a, io, try std.fs.path.join(a, &.{ runtime_root, "etc" }), etc, "security");
    const security = try std.fs.path.join(a, &.{ etc, "security" });
    try overlayLevel(a, io, try std.fs.path.join(a, &.{ runtime_root, "etc", "security" }), security, "cacerts");
    try std.Io.Dir.cwd().createDir(io, try std.fs.path.join(a, &.{ security, "cacerts" }), .default_dir);
    // The platform projection contains real native implementations, separately
    // from ART's own lib64. Do not advertise a 32-bit ABI or absent libraries.
    const lib64 = try std.fs.path.join(a, &.{ path, "platform/lib64" });
    try std.Io.Dir.cwd().createDirPath(io, lib64);
    for (libraries) |library| {
        if (library.name.len == 0 or std.mem.indexOfScalar(u8, library.name, '/') != null or
            !std.mem.endsWith(u8, library.name, ".so")) return error.InvalidLibraryName;
        const target = try std.Io.Dir.cwd().realPathFileAlloc(io, library.path, a);
        try std.Io.Dir.cwd().symLink(io, target, try std.fs.path.join(a, &.{ lib64, library.name }), .{});
    }
    return path;
}

fn overlayLevel(a: std.mem.Allocator, io: std.Io, source: []const u8, destination: []const u8, private_child: []const u8) !void {
    try std.Io.Dir.cwd().createDirPath(io, destination);
    var dir = std.Io.Dir.cwd().openDir(io, source, .{ .iterate = true }) catch |err| switch (err) {
        error.FileNotFound => return,
        else => return err,
    };
    defer dir.close(io);
    const absolute_source = try std.Io.Dir.cwd().realPathFileAlloc(io, source, a);
    var iterator = dir.iterate();
    while (try iterator.next(io)) |entry| {
        if (std.mem.eql(u8, entry.name, private_child)) continue;
        const target = try std.fs.path.join(a, &.{ absolute_source, entry.name });
        try std.Io.Dir.cwd().symLink(io, target, try std.fs.path.join(a, &.{ destination, entry.name }), .{});
    }
}
