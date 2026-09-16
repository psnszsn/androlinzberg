//! Android account database files live in the immutable platform image.
//! Original Bionic grp_pwd_file.cpp owns parsing and mapped-file lifetimes.
const std = @import("std");
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;
extern "c" fn __errno_location() *c_int;
var g_io: std.Io = undefined;
pub fn bindIo(io: std.Io) void {
    g_io = io;
}
export fn azk_android_account_open(path: [*:0]const u8, flags: c_int) c_int {
    const original = std.mem.span(path);
    // This private boundary is only used by the original read-only account reader.
    const partition_path = if (std.mem.eql(u8, original, "/etc/passwd")) "/system/etc/passwd" else if (std.mem.eql(u8, original, "/etc/group")) "/system/etc/group" else original;
    const root = getenv("ARTZIG_PLATFORM_ROOT") orelse return fail(.NOENT);
    if (std.mem.span(root).len == 0) return fail(.NOENT);
    if (flags != 0xA0000) return fail(.INVAL); // O_CLOEXEC | O_NOFOLLOW | O_RDONLY
    var buffer: [4096]u8 = undefined;
    const mapped = std.fmt.bufPrint(&buffer, "{s}{s}", .{ std.mem.trimEnd(u8, std.mem.span(root), "/"), partition_path }) catch return fail(.NAMETOOLONG);
    const file = std.Io.Dir.cwd().openFile(g_io, mapped, .{ .mode = .read_only, .follow_symlinks = false }) catch |err| return fail(switch (err) {
        error.FileNotFound => .NOENT,
        error.AccessDenied => .ACCES,
        error.SymLinkLoop => .LOOP,
        else => .IO,
    });
    // Ownership transfers to the pinned ScopedFd/mmap reader.
    return file.handle;
}
fn fail(err: std.os.linux.E) c_int {
    __errno_location().* = @backingInt(err);
    return -1;
}
