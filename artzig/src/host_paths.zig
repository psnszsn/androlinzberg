//! Resolve Android's fixed platform-resource paths at native file boundaries.
//! Java keeps the pinned filenames; the launcher supplies its existing font,
//! framework-resource and data locations before initializing the runtime.
const std = @import("std");
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;

fn configured(name: [*:0]const u8) ?[]const u8 {
    const value = std.mem.span(getenv(name) orelse return null);
    return if (value.len == 0) null else value;
}

fn fontRelative(path: []const u8) ?[]const u8 {
    if (relative(path, "/system/fonts")) |suffix| return suffix;
    const root = configured("ANDROID_ROOT") orelse return null;
    const suffix = relative(path, std.mem.trimEnd(u8, root, "/")) orelse return null;
    return relative(suffix, "/fonts");
}

fn relative(path: []const u8, root: []const u8) ?[]const u8 {
    if (std.mem.eql(u8, path, root)) return "";
    if (path.len > root.len and std.mem.startsWith(u8, path, root) and path[root.len] == '/')
        return path[root.len..];
    return null;
}

/// Unchanged paths are borrowed; mapped paths are C-allocator buffers released
/// with azk_host_path_release. Null denotes allocation failure, never fallback.
pub export fn azk_host_path(original: [*:0]const u8) callconv(.c) ?[*:0]const u8 {
    const path = std.mem.span(original);
    const replacement: ?[]const u8 = if (std.mem.eql(u8, path, "/system/etc/font_fallback.xml"))
        configured("ARTZIG_FONT_FALLBACK_XML")
    else if (std.mem.eql(u8, path, "/system/etc/fonts.xml"))
        configured("ARTZIG_FONTS_XML") orelse configured("ARTZIG_FONT_FALLBACK_XML")
    else if (std.mem.eql(u8, path, "/system/framework/framework-res.apk"))
        configured("ARTZIG_FRAMEWORK_RES")
    else if (std.mem.eql(u8, path, "/data"))
        configured("ANDROID_DATA") orelse configured("ARTZIG_DATA_DIR")
    else
        null;
    if (replacement) |value|
        return (std.heap.c_allocator.dupeSentinel(u8, value, 0) catch return null).ptr;
    if (fontRelative(path)) |suffix| {
        const root = configured("ARTZIG_FONT_DIR") orelse return original;
        const translated = std.fmt.allocPrintSentinel(std.heap.c_allocator, "{s}/{s}", .{
            std.mem.trimEnd(u8, root, "/"), std.mem.trimStart(u8, suffix, "/"),
        }, 0) catch return null;
        return translated.ptr;
    }
    return original;
}

pub export fn azk_host_path_release(original: [*:0]const u8, mapped: ?[*:0]const u8) callconv(.c) void {
    if (mapped) |value| {
        if (value != original) std.heap.c_allocator.free(std.mem.span(value));
    }
}
