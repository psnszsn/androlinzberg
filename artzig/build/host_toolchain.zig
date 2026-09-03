const std = @import("std");

pub const Result = struct {
    libcap_include_roots: []const std.Build.LazyPath,
};

pub fn discover(b: *std.Build) Result {
    return .{ .libcap_include_roots = pkgIncludeRoots(b, "libcap") };
}

fn pkgIncludeRoots(b: *std.Build, package: []const u8) []const std.Build.LazyPath {
    var exit_code: u8 = 0;
    const stdout = b.runAllowFail(
        &.{ "pkg-config", "--cflags-only-I", package },
        &exit_code,
        .ignore,
    ) catch std.debug.panic("pkg-config {s} failed", .{package});
    if (exit_code != 0) std.debug.panic("pkg-config {s} exited with {d}", .{ package, exit_code });

    var roots: std.ArrayList(std.Build.LazyPath) = .empty;
    var tokens = std.mem.tokenizeAny(u8, stdout, " \r\n\t");
    while (tokens.next()) |token| {
        if (!std.mem.startsWith(u8, token, "-I") or token.len == 2) continue;
        roots.append(b.allocator, b.graph.cwdRelativePath(token[2..])) catch @panic("OOM");
    }
    if (roots.items.len == 0) std.debug.panic("pkg-config {s}: no include roots", .{package});
    return roots.toOwnedSlice(b.allocator) catch @panic("OOM");
}
