//! Linux user namespace creation for a supervised APK before ART or worker
//! threads start. The UID is a launch request, authenticated against the broker
//! grant before application code runs; an environment value alone is no grant.
//! This preserves kernel UID/GID behavior. It does not provide a mount, network
//! or SELinux sandbox: all file descriptors and mappings still need their policy.
const std = @import("std");
const Io = std.Io;
const linux = std.os.linux;

pub fn enter(io: Io, uid: u32) !void {
    const host_uid = linux.getuid();
    const host_gid = linux.getgid();
    // Namespace constructor only; procfs descriptors below belong to std.Io.
    if (linux.unshare(linux.CLONE.NEWUSER) != 0) return error.UserNamespaceUnavailable;
    try writeMap(io, "/proc/self/setgroups", "deny\n");
    var buffer: [80]u8 = undefined;
    try writeMap(io, "/proc/self/uid_map", try std.fmt.bufPrint(&buffer, "{d} {d} 1\n", .{ uid, host_uid }));
    try writeMap(io, "/proc/self/gid_map", try std.fmt.bufPrint(&buffer, "{d} {d} 1\n", .{ uid, host_gid }));
    if (linux.getuid() != uid or linux.getgid() != uid) return error.UserNamespaceIdentityMismatch;
}

fn writeMap(io: Io, path: []const u8, contents: []const u8) !void {
    const file = try Io.Dir.cwd().openFile(io, path, .{ .mode = .write_only });
    defer file.close(io);
    try file.writeStreamingAll(io, contents);
}

pub fn identity(io: Io, pid: i32) !Io.File.INode {
    var buffer: [64]u8 = undefined;
    return (try Io.Dir.cwd().statFile(io, try std.fmt.bufPrint(&buffer, "/proc/{d}/ns/user", .{pid}), .{})).inode;
}
