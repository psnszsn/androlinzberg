//! Android library names backed by the installed compatibility implementation.
//! AOSP File/Os/NIO and Runtime.load keep their original paths and contracts;
//! ART's private system layout supplies aliases to these real host ELF files.
const std = @import("std");
const art = @import("art");

pub fn resolve(a: std.mem.Allocator, io: std.Io, tree: []const u8) ![]const art.SystemLibrary {
    var entries: std.ArrayList(art.SystemLibrary) = .empty;
    for ([_][2][]const u8{
        .{ "libc.so", "lib/art/libc_bio.so" },
        .{ "libdl.so", "lib/art/libdl_bio.so" },
        .{ "libstdc++.so", "lib/art/libstdc++_bio.so" },
        .{ "libEGL.so", "lib/art/libazk_egl.so" },
        .{ "libbinder.so", "lib/art/libbinder.so" },
        .{ "libbinder_ndk.so", "lib/art/libbinder_ndk.so" },
        .{ "libaaudio.so", "lib/libandroid_platform.so" },
        .{ "libandroid.so", "lib/libandroid_platform.so" },
        .{ "libstatssocket.so", "lib/libandroid_platform.so" },
        .{ "libmediandk.so", "lib/libandroid_platform.so" },
        .{ "libvintf_jni.so", "lib/libandroid_platform.so" },
        .{ "libjnigraphics.so", "lib/libandroid_platform.so" },
        .{ "liblog.so", "lib/libandroid_platform.so" },
    }) |entry| {
        try entries.append(a, .{ .name = entry[0], .path = try std.fs.path.join(a, &.{ tree, entry[1] }) });
    }
    // These C ABIs are provided by existing host dependencies. Ask the dynamic
    // loader for the actual ELF, avoiding development linker scripts and guessed
    // distro paths. This does not load graphics drivers or open a display.
    for ([_]struct { name: []const u8, soname: [:0]const u8 }{
        .{ .name = "libm.so", .soname = "libm.so.6" },
        .{ .name = "libz.so", .soname = "libz.so.1" },
        .{ .name = "libGLESv2.so", .soname = "libGLESv2.so.2" },
        .{ .name = "libGLESv3.so", .soname = "libGLESv2.so.2" },
    }) |entry| {
        const handle = dlopen(entry.soname, 2) orelse return error.PlatformLibraryLoadFailed;
        defer _ = dlclose(handle);
        var map: ?*const LinkMap = null;
        if (dlinfo(handle, 2, &map) != 0) return error.PlatformLibraryPathUnavailable; // RTLD_DI_LINKMAP
        const loaded = map orelse return error.PlatformLibraryPathUnavailable;
        const path = try std.Io.Dir.cwd().realPathFileAlloc(io, std.mem.span(loaded.name), a);
        try entries.append(a, .{ .name = entry.name, .path = path });
    }
    return entries.toOwnedSlice(a);
}

// The public glibc link_map prefix; only the native dynamic-loader ABI is used.
const LinkMap = extern struct { address: usize, name: [*:0]const u8 };
extern "c" fn dlopen(path: [*:0]const u8, flags: c_int) ?*anyopaque;
extern "c" fn dlinfo(handle: *anyopaque, request: c_int, result: *?*const LinkMap) c_int;
extern "c" fn dlclose(handle: *anyopaque) c_int;
