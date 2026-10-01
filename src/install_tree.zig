//! Installed image paths shared by the CLI and private process entry points.
const std = @import("std");

pub fn root(init: std.process.Init) ![]const u8 {
    const a = init.arena.allocator();
    const tree = if (init.minimal.environ.getPosix("ARTZIG_INSTALL_ROOT")) |configured| blk: {
        if (!std.fs.path.isAbsolute(configured)) return error.InstallRootMustBeAbsolute;
        break :blk configured;
    } else blk: {
        const exe = try std.process.executablePathAlloc(init.io, a);
        const directory = std.fs.path.dirname(exe) orelse return error.NoInstallTree;
        const parent = std.fs.path.dirname(directory) orelse return error.NoInstallTree;
        break :blk if (std.mem.eql(u8, std.fs.path.basename(directory), "androlinzberg") and
            std.mem.eql(u8, std.fs.path.basename(parent), "libexec"))
            std.fs.path.dirname(parent) orelse return error.NoInstallTree
        else
            parent;
    };
    // Canonical image paths are persisted in ART class-loader contexts.
    const canonical = try std.Io.Dir.cwd().realPathFileAlloc(init.io, tree, a);
    try std.Io.Dir.accessAbsolute(init.io, try std.fs.path.join(a, &.{ canonical, "lib", "libandroid_platform.so" }), .{});
    return canonical;
}

pub fn executable(init: std.process.Init, name: []const u8) ![]const u8 {
    return std.fs.path.join(init.arena.allocator(), &.{ try root(init), "libexec", "androlinzberg", name });
}

/// ART runtime root discovery: ARTZIG_ROOT, otherwise the runtime installed
/// beside the executable by the app build.
pub fn runtimeRoot(init: std.process.Init, tree: []const u8) !?[]const u8 {
    if (init.minimal.environ.getPosix("ARTZIG_ROOT") != null) return null;
    const a = init.arena.allocator();
    const runtime = try std.fs.path.join(a, &.{ tree, "bin", "art" });
    for ([_][]const u8{
        "runtime.env",
        "lib64/libart.so",
        "apex/com.android.art/javalib/x86_64/boot.art",
    }) |probe| {
        try std.Io.Dir.accessAbsolute(init.io, try std.fs.path.join(a, &.{ runtime, probe }), .{});
    }
    return runtime;
}
