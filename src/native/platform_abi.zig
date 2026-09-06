//! Non-rendering C ABI required by AOSP components in libandroid_platform.
//! Keep these exports in Zig while the HWUI/Skia implementation remains C++.
const std = @import("std");

// Source: bionic's upstream-openbsd getprogname.c. The host libc owns the
// process-name storage and exposes the same __progname variable.
extern "c" var __progname: [*:0]const u8;
pub export fn getprogname() callconv(.c) [*:0]const u8 {
    return __progname;
}

// The pinned Android EGL client and HWUI use one default display. Mesa's
// implicit default may choose a different platform from the hosted window.
extern "c" fn azk_hwui_default_egl_display() ?*anyopaque;
extern "EGL" fn eglGetDisplay(?*anyopaque) ?*anyopaque;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;
pub export fn azk_host_egl_get_proc_address(name: [*:0]const u8) callconv(.c) ?*const anyopaque {
    return eglGetProcAddress(name);
}
pub export fn bionic_eglGetDisplay(display: ?*anyopaque) callconv(.c) ?*anyopaque {
    return if (display == null) azk_hwui_default_egl_display() else eglGetDisplay(display);
}

var g_io: std.Io = undefined;

pub export fn azk_platform_bind_io(
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void {
    g_io = .{ .userdata = userdata, .vtable = vtable };
    @import("selinux_backend.zig").bindIo(g_io);
}

const sqlite3 = opaque {};
const SqliteCollation = *const fn (?*anyopaque, c_int, ?*const anyopaque, c_int, ?*const anyopaque) callconv(.c) c_int;

extern "c" fn sqlite3_create_collation(
    database: ?*sqlite3,
    name: [*:0]const u8,
    encoding: c_int,
    context: ?*anyopaque,
    compare: SqliteCollation,
) c_int;

const sqlite_ok = 0;
const sqlite_utf8 = 1;

fn compareBytes(_: ?*anyopaque, left_len: c_int, left_raw: ?*const anyopaque, right_len: c_int, right_raw: ?*const anyopaque) callconv(.c) c_int {
    if (left_len < 0 or right_len < 0) return 0;
    const left_size: usize = @intCast(left_len);
    const right_size: usize = @intCast(right_len);
    const shared = @min(left_size, right_size);
    if (shared != 0) {
        const left: [*]const u8 = @ptrCast(left_raw orelse return -1);
        const right: [*]const u8 = @ptrCast(right_raw orelse return 1);
        const order = std.mem.order(u8, left[0..shared], right[0..shared]);
        if (order != .eq) return if (order == .lt) -1 else 1;
    }
    return if (left_size < right_size) -1 else if (left_size > right_size) 1 else 0;
}

pub export fn register_localized_collators(database: ?*sqlite3, _: [*c]const u8, _: c_int) callconv(.c) c_int {
    const localized = sqlite3_create_collation(database, "LOCALIZED", sqlite_utf8, null, compareBytes);
    if (localized != sqlite_ok) return localized;
    return sqlite3_create_collation(database, "UNICODE", sqlite_utf8, null, compareBytes);
}

pub export fn register_android_functions(database: ?*sqlite3, utf16_storage: c_int) callconv(.c) c_int {
    return register_localized_collators(database, "en_US", utf16_storage);
}

test "SQLite fallback collation is byte-wise and length-aware" {
    try std.testing.expectEqual(@as(c_int, -1), compareBytes(null, 2, "ab", 2, "ac"));
    try std.testing.expectEqual(@as(c_int, -1), compareBytes(null, 2, "ab", 3, "abc"));
    try std.testing.expectEqual(@as(c_int, 0), compareBytes(null, 2, "ab", 2, "ab"));
}

extern "c" fn __errno_location() *c_int;
// bionic android_dlopen_ext: ordinary shared objects use the host ELF loader.
// Android namespace/FD/RELRO extensions have no host equivalent and fail explicitly.
// No descriptor is borrowed or consumed when rejecting an extension.
extern "c" fn dlopen(filename: ?[*:0]const u8, flags: c_int) ?*anyopaque;
const AndroidDlextInfo = extern struct {
    flags: u64,
    reserved_addr: ?*anyopaque,
    reserved_size: usize,
    relro_fd: c_int,
    library_fd: c_int,
    library_fd_offset: i64,
    library_namespace: ?*anyopaque,
};
pub export fn android_dlopen_ext(filename: ?[*:0]const u8, flags: c_int, info: ?*const AndroidDlextInfo) callconv(.c) ?*anyopaque {
    if (info) |extension| {
        if (extension.flags != 0) {
            __errno_location().* = @backingInt(std.os.linux.E.OPNOTSUPP);
            return null;
        }
    }
    return dlopen(filename, flags);
}
