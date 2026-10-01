//! Public command dispatch. ART and service ownership live in private entry points.
const std = @import("std");
const image = @import("install_tree.zig");

const usage =
    \\androlinzberg — run an Android APK as a Wayland application
    \\
    \\usage: androlinzberg [--standalone] [--join <session-dir>] <app.apk>
    \\  --standalone          use a private single-user Android instance (default)
    \\  --join <session-dir>  launch in an existing private instance
    \\
    \\The installed runtime is discovered relative to this executable.
    \\ARTZIG_INSTALL_ROOT selects an explicit absolute development image.
    \\Internal process flags are accepted for compatibility with older test drivers.
    \\
;

pub fn main(init: std.process.Init) !void {
    const a = init.arena.allocator();
    const args = try init.minimal.args.toSlice(a);
    // Session fixtures use this trampoline to acquire child ownership before exec.
    if (args.len >= 3 and std.mem.eql(u8, args[1], "--service-exec"))
        return std.process.replace(init.io, .{ .argv = args[2..] });
    if (args.len == 1 or std.mem.eql(u8, args[1], "--help") or std.mem.eql(u8, args[1], "-h")) {
        try std.Io.File.stdout().writeStreamingAll(init.io, usage);
        return;
    }
    var helper: []const u8 = "azk-standalone";
    var join: ?[]const u8 = null;
    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        const arg = args[i];
        if (std.mem.eql(u8, arg, "--join")) {
            if (i + 1 >= args.len) return error.MissingSessionDirectory;
            i += 1;
            join = args[i];
        } else if (std.mem.eql(u8, arg, "--system")) {
            // Never silently switch storage when the requested deployment is absent.
            return error.SystemDeploymentNotImplemented;
        } else if (std.mem.eql(u8, arg, "--system-server")) {
            helper = "azk-system-server";
            break;
        } else if (std.mem.eql(u8, arg, "--runtime-process") or std.mem.eql(u8, arg, "--app-process")) {
            helper = "azk-app-process";
            break;
        } else if (std.mem.eql(u8, arg, "--inspect-apk-package")) {
            helper = "azk-metadata";
            break;
        } else if (std.mem.eql(u8, arg, "--javatest")) {
            helper = "azk-jvm-test";
            break;
        }
    }
    // Normalize legacy --join ordering before passing Java's trailing arguments.
    var forwarded: std.ArrayList([]const u8) = .empty;
    try forwarded.append(a, try image.executable(init, helper));
    i = 1;
    while (i < args.len) : (i += 1) {
        if (std.mem.eql(u8, args[i], "--join")) {
            if (i + 1 >= args.len) return error.MissingSessionDirectory;
            i += 1;
            join = args[i];
        } else if (!std.mem.eql(u8, args[i], "--standalone")) {
            try forwarded.append(a, args[i]);
        }
    }
    if (join) |dir| try forwarded.insertSlice(a, 1, &.{ "--join", dir });
    return std.process.replace(init.io, .{ .argv = forwarded.items });
}
