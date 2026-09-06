//! Linux policy backend for the reused android_os_SELinux JNI and libincfs.
//! Android's file-context backend selects partition policy files; this host
//! selects libselinux's installed policy instead. JNI ordering and disabled
//! SELinux behavior remain in the pinned C++ implementation.
const std = @import("std");

const LabelHandle = opaque {};
extern "c" fn selinux_restorecon_default_handle() ?*LabelHandle;
extern "c" fn selinux_restorecon(path: [*:0]const u8, flags: c_uint) c_int;

var active_io: std.Io = undefined;

pub fn bindIo(io: std.Io) void {
    active_io = io;
}

pub export fn selinux_android_file_context_handle() callconv(.c) ?*LabelHandle {
    return selinux_restorecon_default_handle();
}

pub export fn selinux_android_restorecon(path: [*:0]const u8, flags: c_uint) callconv(.c) c_int {
    // SELINUX_ANDROID_RESTORECON_* and SELINUX_RESTORECON_* use different
    // bit assignments. Host file-context policy owns labels, including files
    // in hosted application storage; Android seapp_contexts is not installed.
    var host_flags: c_uint = 0;
    if (flags & 1 != 0) host_flags |= 0x00002; // NOCHANGE
    if (flags & 2 != 0) host_flags |= 0x00010; // VERBOSE
    if (flags & 4 != 0) host_flags |= 0x00008; // RECURSE
    if (flags & 8 != 0) host_flags |= 0x00001; // FORCE / IGNORE_DIGEST
    if (flags & 64 == 0) host_flags |= 0x00080; // stay on the filesystem
    if (flags & 128 != 0) host_flags |= 0x08000; // SKIP_SEHASH / SKIP_DIGEST
    return selinux_restorecon(path, host_flags);
}

pub export fn get_genfs_labels_version() callconv(.c) i32 {
    // Pinned system/sepolicy/compat/libgenfslabelsversion/src/lib.rs. The
    // Rust library is not linked into the host island; preserve its path,
    // whitespace handling and fallback through std.Io.
    const contents = std.Io.Dir.cwd().readFileAlloc(
        active_io,
        "/vendor/etc/selinux/genfs_labels_version.txt",
        std.heap.c_allocator,
        .unlimited,
    ) catch return 202404;
    defer std.heap.c_allocator.free(contents);
    const view = std.unicode.Utf8View.init(contents) catch return 202404;
    var iterator = view.iterator();
    var first: ?usize = null;
    var last: usize = 0;
    while (true) {
        const start = iterator.i;
        const codepoint = iterator.nextCodepoint() orelse break;
        // Rust str::trim uses Unicode White_Space, including nonbreaking spaces.
        const whitespace = switch (codepoint) {
            9...13,
            0x20,
            0x85,
            0xa0,
            0x1680,
            0x2000...0x200a,
            0x2028,
            0x2029,
            0x202f,
            0x205f,
            0x3000,
            => true,
            else => false,
        };
        if (!whitespace) {
            if (first == null) first = start;
            last = iterator.i;
        }
    }
    const text = contents[first orelse return 202404 .. last];
    const digits = if (text[0] == '+' or text[0] == '-') text[1..] else text;
    if (digits.len == 0) return 202404;
    for (digits) |digit| if (!std.ascii.isDigit(digit)) return 202404;
    return std.fmt.parseInt(i32, text, 10) catch 202404;
}
