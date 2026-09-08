//! Android native font paths use the same host font configuration as SystemFonts.
//! SkFontMgr_android_parser reads /system/etc/fonts.xml and appends filenames to
//! ANDROID_ROOT/fonts. The libc_bio path callback supplies the existing fontconfig
//! files without changing guest callers or creating a global /system directory.
const std = @import("std");
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;

pub fn override(path: *[*:0]u8) callconv(.c) bool {
    const original = std.mem.span(path.*);
    if (std.mem.eql(u8, original, "/system/etc/fonts.xml")) {
        const config = getenv("ARTZIG_FONTS_XML") orelse getenv("ARTZIG_FONT_FALLBACK_XML") orelse return false;
        path.* = @constCast(config);
        return false; // Environment storage is borrowed, never freed by libc_bio.
    }
    const directory = getenv("ARTZIG_FONT_DIR") orelse return false;
    const relative = fontRelative(original) orelse return false;
    const translated = std.fs.path.joinZ(std.heap.c_allocator, &.{ std.mem.span(directory), relative }) catch return false;
    path.* = translated.ptr;
    return true; // libc_bio frees the C-allocator buffer after open/fopen.
}

fn fontRelative(path: []const u8) ?[]const u8 {
    const system_prefix = "/system/fonts/";
    if (std.mem.startsWith(u8, path, system_prefix)) return path[system_prefix.len..];
    const root = std.mem.trimEnd(u8, std.mem.span(getenv("ANDROID_ROOT") orelse return null), "/");
    if (!std.mem.startsWith(u8, path, root)) return null;
    const suffix = path[root.len..];
    if (!std.mem.startsWith(u8, suffix, "/fonts/")) return null;
    return suffix["/fonts/".len..];
}
