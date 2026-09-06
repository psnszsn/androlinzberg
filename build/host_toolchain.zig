//! Host include roots discovered at configuration time and immediately turned
//! into graph-owned LazyPaths. Consumers never exchange pkg-config strings.
const std = @import("std");

pub const IncludeSet = struct {
    roots: []const std.Build.LazyPath,
};

pub const LibrarySet = struct {
    include_roots: []const std.Build.LazyPath,
    library_roots: []const std.Build.LazyPath,
    libraries: []const []const u8,
    link_flags: []const []const u8,
};

pub const Result = struct {
    island: LibrarySet,
    skia: IncludeSet,
    codec: IncludeSet,
    pipewire: IncludeSet,
};

const island_packages = [_][]const u8{
    "harfbuzz",     "harfbuzz-icu", "freetype2", "fontconfig", "icu-uc",  "icu-i18n",
    "libpng",       "zlib",         "openssl",   "sqlite3",    "egl",     "gl",
    "wayland-egl",  "gbm",          "libdrm",    "expat",      "libjpeg", "libwebp",
    "libwebpdemux", "libwebpmux",   "glesv2",    "libselinux",
};

pub fn discover(b: *std.Build) Result {
    return .{
        .island = librarySet(b, &island_packages),
        .skia = includeSet(b, &.{ "fontconfig", "expat", "freetype2", "libpng", "egl", "libjpeg", "glesv2", "libwebp", "libwebpdemux", "libwebpmux" }),
        .codec = includeSet(b, &.{ "libavcodec", "libavutil", "libva", "libdrm" }),
        .pipewire = includeSet(b, &.{"libpipewire-0.3"}),
    };
}

fn librarySet(b: *std.Build, packages: []const []const u8) LibrarySet {
    const includes = includeSet(b, packages);
    var roots: std.ArrayList(std.Build.LazyPath) = .empty;
    var libraries: std.ArrayList([]const u8) = .empty;
    var flags: std.ArrayList([]const u8) = .empty;
    var seen_roots: std.StringHashMapUnmanaged(void) = .empty;
    var seen_libraries: std.StringHashMapUnmanaged(void) = .empty;
    var seen_flags: std.StringHashMapUnmanaged(void) = .empty;
    for (packages) |package| {
        appendPkgTokens(b, package, "--libs-only-L", "-L", &seen_roots, &roots, null, null);
        appendPkgTokens(b, package, "--libs-only-l", "-l", null, null, &seen_libraries, &libraries);
        appendPkgTokens(b, package, "--libs-only-other", "", null, null, &seen_flags, &flags);
    }
    return .{
        .include_roots = includes.roots,
        .library_roots = roots.toOwnedSlice(b.allocator) catch @panic("oom"),
        .libraries = libraries.toOwnedSlice(b.allocator) catch @panic("oom"),
        .link_flags = flags.toOwnedSlice(b.allocator) catch @panic("oom"),
    };
}

fn appendPkgTokens(
    b: *std.Build,
    package: []const u8,
    query: []const u8,
    prefix: []const u8,
    path_seen: ?*std.StringHashMapUnmanaged(void),
    paths: ?*std.ArrayList(std.Build.LazyPath),
    value_seen: ?*std.StringHashMapUnmanaged(void),
    values: ?*std.ArrayList([]const u8),
) void {
    var exit_code: u8 = 0;
    const stdout = b.runAllowFail(&.{ "pkg-config", query, package }, &exit_code, .ignore) catch
        std.debug.panic("pkg-config {s} {s} failed", .{ query, package });
    if (exit_code != 0) std.debug.panic("pkg-config {s} {s} exited with {d}", .{ query, package, exit_code });
    var tokens = std.mem.tokenizeAny(u8, stdout, " \r\n\t");
    while (tokens.next()) |token| {
        if (!std.mem.startsWith(u8, token, prefix) or token.len == prefix.len) continue;
        const value = token[prefix.len..];
        if (paths) |output| {
            const seen = path_seen.?;
            if (seen.contains(value)) continue;
            seen.put(b.allocator, value, {}) catch @panic("oom");
            output.append(b.allocator, b.graph.cwdRelativePath(value)) catch @panic("oom");
        } else if (values) |output| {
            const seen = value_seen.?;
            if (seen.contains(value)) continue;
            seen.put(b.allocator, value, {}) catch @panic("oom");
            output.append(b.allocator, b.dupe(value)) catch @panic("oom");
        }
    }
}

fn includeSet(b: *std.Build, packages: []const []const u8) IncludeSet {
    var roots: std.ArrayList(std.Build.LazyPath) = .empty;
    var seen: std.StringHashMapUnmanaged(void) = .empty;
    for (packages) |package| {
        var exit_code: u8 = 0;
        const stdout = b.runAllowFail(
            &.{ "pkg-config", "--cflags-only-I", package },
            &exit_code,
            .ignore,
        ) catch std.debug.panic("pkg-config {s} failed", .{package});
        if (exit_code != 0) std.debug.panic("pkg-config {s} exited with {d}", .{ package, exit_code });
        var tokens = std.mem.tokenizeAny(u8, stdout, " \r\n\t");
        while (tokens.next()) |token| {
            if (!std.mem.startsWith(u8, token, "-I") or token.len == 2) continue;
            const path = token[2..];
            if (seen.contains(path)) continue;
            seen.put(b.allocator, path, {}) catch @panic("oom");
            roots.append(b.allocator, b.graph.cwdRelativePath(path)) catch @panic("oom");
        }
    }
    return .{ .roots = roots.toOwnedSlice(b.allocator) catch @panic("oom") };
}
