//! Android's mount layout for one session: `/data` over the session's data
//! partition and `/storage/emulated/<user>` over the host directory that backs
//! the emulated volume. On a device init and vold supply these mounts; here a user and mount namespace owned by the host
//! user carries them, built once by the session owner and inherited by every
//! process it starts. A launcher joining from a foreign shell enters the
//! owner's namespaces, so paths that the stock StorageManagerService
//! validates and canonicalizes (`/storage/<volume>/<user>/Android/...`)
//! resolve identically in every session process.
//!
//! Stock code that names `/data/...` literally (InputManagerService's
//! PersistentDataStore) reaches the same files as Environment's data
//! directory, which stays the partition's host path.
//!
//! The view is the host root with `/data` and `/storage` added: a tmpfs root receives a
//! recursive bind of each top-level host entry, then becomes the root by
//! pivot_root. Nothing is hidden; the per-app sandbox is a later phase.
const std = @import("std");
const Io = std.Io;
const linux = std.os.linux;

/// Exported to every process inside a view. A process that inherits it is
/// already in the session's namespaces and neither builds nor joins one.
pub const env_marker = "AZK_STORAGE_VIEW";
/// The system user's directory of vold's EmulatedVolume (`/storage/emulated`).
pub const primary = "/storage/emulated/0";

/// Android's mount point of the data partition.
pub const data_mount = "/data";

/// Enters the view, binding `data_root` at `/data` and `external_root` at
/// `/storage/emulated/0`. Must run while the process is single-threaded (a
/// user namespace requirement).
pub fn enter(io: Io, a: std.mem.Allocator, environ: std.process.Environ, data_root: []const u8, external_root: []const u8) !void {
    if (environ.getPosix(env_marker) != null) return;
    const uid = linux.getuid();
    const gid = linux.getgid();
    // Namespace constructors only; the mounts below are kernel objects that
    // std.Io does not model, and descriptors stay in std.Io.
    if (linux.unshare(linux.CLONE.NEWUSER | linux.CLONE.NEWNS) != 0) return error.StorageViewUnavailable;
    try writeMap(io, "/proc/self/setgroups", "deny\n");
    var buffer: [80]u8 = undefined;
    try writeMap(io, "/proc/self/uid_map", try std.fmt.bufPrint(&buffer, "{d} {d} 1\n", .{ uid, uid }));
    try writeMap(io, "/proc/self/gid_map", try std.fmt.bufPrint(&buffer, "{d} {d} 1\n", .{ gid, gid }));
    // Nothing here propagates back to the host's mount table.
    try mount(null, "/", null, linux.MS.REC | linux.MS.PRIVATE);
    // The root is assembled in a tmpfs on a private directory (a path stacked
    // over would resolve onto the covering mount, hiding the host directory).
    // Recursive binds of the host's top-level entries carry that directory
    // along; it is detached and removed once the new root is in place.
    const runtime = environ.getPosix("XDG_RUNTIME_DIR");
    const base = if (runtime) |dir|
        try std.fmt.allocPrintSentinel(a, "{s}/androlinzberg/view-{d}", .{ dir, linux.getpid() }, 0)
    else
        try std.fmt.allocPrintSentinel(a, "/tmp/androlinzberg-view-{d}", .{linux.getpid()}, 0);
    const cwd = Io.Dir.cwd();
    // The working directory survives the pivot by path: callers resolve
    // relative arguments against it after joining.
    const working_directory = try std.fmt.allocPrintSentinel(a, "{s}", .{try cwd.realPathFileAlloc(io, ".", a)}, 0);
    try cwd.createDirPath(io, base);
    const external_dir = try cwd.openDir(io, external_root, .{});
    defer external_dir.close(io);
    const data_dir = try cwd.openDir(io, data_root, .{});
    defer data_dir.close(io);
    var host_root = try Io.Dir.openDirAbsolute(io, "/", .{ .iterate = true });
    defer host_root.close(io);
    try mount("tmpfs", base, "tmpfs", linux.MS.NOSUID | linux.MS.NODEV);
    const new_root = try std.fmt.allocPrintSentinel(a, "{s}/newroot", .{base}, 0);
    try cwd.createDirPath(io, try std.fmt.allocPrint(a, "{s}/oldroot", .{new_root}));
    var entries = host_root.iterate();
    while (try entries.next(io)) |entry| {
        // The view owns /data and /storage; host entries of those names are not visible.
        if (std.mem.eql(u8, entry.name, "storage") or std.mem.eql(u8, entry.name, data_mount[1..])) continue;
        const source = try std.fmt.allocPrintSentinel(a, "/{s}", .{entry.name}, 0);
        const target = try std.fmt.allocPrintSentinel(a, "{s}/{s}", .{ new_root, entry.name }, 0);
        switch (entry.kind) {
            .directory => {
                try cwd.createDir(io, target, .fromMode(0o755));
                try mount(source, target, null, linux.MS.BIND | linux.MS.REC);
            },
            .sym_link => {
                var link_buffer: [std.fs.max_path_bytes]u8 = undefined;
                const size = try host_root.readLink(io, entry.name, &link_buffer);
                try cwd.symLink(io, link_buffer[0..size], target, .{});
            },
            .file => {
                const file = try cwd.createFile(io, target, .{});
                file.close(io);
                try mount(source, target, null, linux.MS.BIND);
            },
            else => {},
        }
    }
    const primary_target = try std.fmt.allocPrintSentinel(a, "{s}{s}", .{ new_root, primary }, 0);
    try cwd.createDirPath(io, primary_target);
    try mount(try std.fmt.allocPrintSentinel(a, "/proc/self/fd/{d}", .{external_dir.handle}, 0), primary_target, null, linux.MS.BIND | linux.MS.REC);
    const data_target = try std.fmt.allocPrintSentinel(a, "{s}{s}", .{ new_root, data_mount }, 0);
    try cwd.createDirPath(io, data_target);
    try mount(try std.fmt.allocPrintSentinel(a, "/proc/self/fd/{d}", .{data_dir.handle}, 0), data_target, null, linux.MS.BIND | linux.MS.REC);
    // pivot_root takes a mount point: bind the assembled root over itself.
    try mount(new_root, new_root, null, linux.MS.BIND | linux.MS.REC);
    try check(linux.chdir(new_root));
    try check(linux.pivot_root(".", "oldroot"));
    try check(linux.umount2("/oldroot", linux.MNT.DETACH));
    try check(linux.rmdir("/oldroot"));
    try check(linux.chdir(working_directory));
    // The assembly directory, seen through the host's bound parent.
    try check(linux.umount2(base, linux.MNT.DETACH));
    try check(linux.rmdir(base));
    try dropCapabilities();
}

/// Joins the view of the session owner `pid` (its user and mount namespaces),
/// for a launcher started from a foreign shell. Same single-thread requirement.
pub fn join(io: Io, a: std.mem.Allocator, environ: std.process.Environ, pid: i32) !void {
    if (environ.getPosix(env_marker) != null) return;
    const cwd = Io.Dir.cwd();
    const working_directory = try std.fmt.allocPrintSentinel(a, "{s}", .{try cwd.realPathFileAlloc(io, ".", a)}, 0);
    const user = try cwd.openFile(io, try std.fmt.allocPrint(a, "/proc/{d}/ns/user", .{pid}), .{});
    defer user.close(io);
    const mnt = try cwd.openFile(io, try std.fmt.allocPrint(a, "/proc/{d}/ns/mnt", .{pid}), .{});
    defer mnt.close(io);
    try check(linux.setns(user.handle, linux.CLONE.NEWUSER));
    try check(linux.setns(mnt.handle, linux.CLONE.NEWNS));
    try check(linux.chdir(working_directory));
    try dropCapabilities();
}

/// unshare and setns leave the caller holding every capability in the
/// session's user namespace, while session children lose them when they exec
/// as a non-root user. Peers must match: the broker, holding none, can resolve
/// a peer's /proc namespace link only when the peer holds no capability it
/// lacks. Nothing after the view is built needs them.
fn dropCapabilities() !void {
    // linux/capability.h, _LINUX_CAPABILITY_VERSION_3: two 32-bit data words.
    const Header = extern struct { version: u32, pid: i32 };
    const Data = extern struct { effective: u32, permitted: u32, inheritable: u32 };
    var header: Header = .{ .version = 0x20080522, .pid = 0 };
    const none: Data = .{ .effective = 0, .permitted = 0, .inheritable = 0 };
    const data = [2]Data{ none, none };
    try check(linux.syscall2(.capset, @intFromPtr(&header), @intFromPtr(&data)));
}

fn mount(source: ?[*:0]const u8, target: [*:0]const u8, fstype: ?[*:0]const u8, flags: u32) error{StorageViewFailed}!void {
    const rc = linux.mount(source, target, fstype, flags, 0);
    if (linux.errno(rc) != .SUCCESS) {
        std.debug.print("azk: storage view: mount {s} -> {s} failed: {s}\n", .{ source orelse "none", target, @tagName(linux.errno(rc)) });
        return error.StorageViewFailed;
    }
}

fn check(rc: usize) error{StorageViewFailed}!void {
    if (linux.errno(rc) != .SUCCESS) {
        std.debug.print("azk: storage view: {s}\n", .{@tagName(linux.errno(rc))});
        return error.StorageViewFailed;
    }
}

fn writeMap(io: Io, path: []const u8, contents: []const u8) !void {
    const file = try Io.Dir.cwd().openFile(io, path, .{ .mode = .write_only });
    defer file.close(io);
    try file.writeStreamingAll(io, contents);
}
