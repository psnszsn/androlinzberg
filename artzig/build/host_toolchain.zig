const std = @import("std");

pub const Result = struct {
    libcap_include_roots: []const std.Build.LazyPath,
};

pub fn discover(b: *std.Build) Result {
    return .{ .libcap_include_roots = pkgIncludeRoots(b, "libcap") };
}

/// Zig's shared-library link does not supply the host C++ DSO finalizers.
/// Use the host compiler's matching CRT objects so dlclose runs destructors
/// against this library's __dso_handle, before its code is unmapped.
pub fn addSharedLibraryCrt(b: *std.Build, lib: *std.Build.Step.Compile) void {
    for ([_][]const u8{ "crtbeginS.o", "crtendS.o" }) |name| {
        var exit_code: u8 = 0;
        const stdout = b.runAllowFail(
            &.{ "cc", b.fmt("-print-file-name={s}", .{name}) },
            &exit_code,
            .ignore,
        ) catch std.debug.panic("cannot locate host {s}", .{name});
        const path = std.mem.trim(u8, stdout, " \r\n\t");
        if (exit_code != 0 or !std.fs.path.isAbsolute(path))
            std.debug.panic("host compiler did not locate {s}: {s}", .{ name, path });
        lib.root_module.addObjectFile(b.graph.cwdRelativePath(path));
    }
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
