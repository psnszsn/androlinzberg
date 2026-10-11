//! Android Environment's data partition over persistent storage. Application
//! data and the package database that owns it (/data/system) persist together,
//! as on a device's single data partition: the persistent root is the
//! partition itself, so the canonical path of every file below it stays
//! inside Environment.getDataDirectory(), which stock StorageManager and
//! PackageInstaller rely on when they resolve a path to its volume. Only ART's
//! shared dalvik-cache and the legacy `data` alias are links. Android's mount
//! layout is supplied before Java starts, without changing any framework path
//! getter.
const std = @import("std");
const Io = std.Io;

pub const Options = struct {
    persistent_root: []const u8,
};

pub const Partition = struct {
    path: []const u8,
    /// The partition is the caller's persistent root: nothing to remove.
    pub fn release(self: Partition, io: Io) void {
        _ = self;
        _ = io;
    }
};

const persistent_children = [_][]const u8{
    "user",      "user_de",   "app",      "app-staging", "misc",   "misc_ce",   "misc_de",
    "vendor_ce", "vendor_de", "preloads", "local",       "system", "system_ce", "system_de",
};
/// Application data of a root that predates its package database.
pub const legacy_app_data = "legacy-app-data";
const app_data_roots = [_][]const u8{ "user/0", "user_de/0" };

pub fn create(a: std.mem.Allocator, io: Io, cache_root: []const u8, options: Options) !Partition {
    const cwd = Io.Dir.cwd();
    try cwd.createDirPath(io, options.persistent_root);
    try cwd.createDirPath(io, cache_root);
    const persistent = try cwd.realPathFileAlloc(io, options.persistent_root, a);
    const cache = try cwd.realPathFileAlloc(io, cache_root, a);
    if (std.mem.eql(u8, persistent, cache)) return error.OverlappingDataRoots;
    try adoptPackageDatabase(a, io, persistent);
    for (persistent_children) |child| {
        try cwd.createDirPath(io, try std.fs.path.join(a, &.{ persistent, child }));
    }
    const legacy = try std.fs.path.join(a, &.{ persistent, "user", "0" });
    try cwd.createDirPath(io, legacy);
    try link(a, io, persistent, "data", legacy, false);
    const compiled = try std.fs.path.join(a, &.{ cache, "dalvik-cache" });
    try cwd.createDirPath(io, compiled);
    // The cache root may move between sessions; compiled code is disposable.
    try link(a, io, persistent, "dalvik-cache", compiled, true);
    return .{ .path = persistent };
}

/// PackageManagerService destroys application data that no installed package
/// claims (AppDataHelper.reconcileAppsDataLI). Data written before this root
/// had a package database was owned by none, so move it under
/// `legacy-app-data` before the database's first boot instead. The database
/// directory is created last: an interrupted move resumes on the next start.
fn adoptPackageDatabase(a: std.mem.Allocator, io: Io, persistent: []const u8) !void {
    const cwd = Io.Dir.cwd();
    const system = try std.fs.path.join(a, &.{ persistent, "system" });
    if (cwd.access(io, system, .{})) |_| return else |err| switch (err) {
        error.FileNotFound => {},
        else => return err,
    }
    for (app_data_roots) |relative| {
        const source_path = try std.fs.path.join(a, &.{ persistent, relative });
        var source = cwd.openDir(io, source_path, .{ .iterate = true }) catch |err| switch (err) {
            error.FileNotFound => continue,
            else => return err,
        };
        defer source.close(io);
        const target_path = try std.fs.path.join(a, &.{ persistent, legacy_app_data, relative });
        var target: ?Io.Dir = null;
        defer if (target) |*dir| dir.close(io);
        var entries = source.iterate();
        while (try entries.next(io)) |entry| {
            if (target == null) {
                try cwd.createDirPath(io, target_path);
                target = try cwd.openDir(io, target_path, .{});
            }
            var attempt: usize = 0;
            while (true) : (attempt += 1) {
                const name = if (attempt == 0) entry.name else try std.fmt.allocPrint(a, "{s}.{d}", .{ entry.name, attempt });
                Io.Dir.renamePreserve(source, entry.name, target.?, name, io) catch |err| switch (err) {
                    error.PathAlreadyExists => continue,
                    else => return err,
                };
                break;
            }
        }
    }
    try cwd.createDirPath(io, system);
}

fn link(a: std.mem.Allocator, io: Io, directory: []const u8, name: []const u8, target: []const u8, replaceable: bool) !void {
    const path = try std.fs.path.join(a, &.{ directory, name });
    Io.Dir.cwd().symLink(io, target, path, .{}) catch |err| switch (err) {
        error.PathAlreadyExists => {
            // Concurrent app startup may encounter the system process's layout.
            // Accept that exact layout; never replace another storage target.
            var buffer: [std.fs.max_path_bytes]u8 = undefined;
            const size = try Io.Dir.cwd().readLink(io, path, &buffer);
            if (std.mem.eql(u8, buffer[0..size], target)) return;
            if (!replaceable) return error.DataPartitionTargetMismatch;
            try Io.Dir.cwd().deleteFile(io, path);
            try Io.Dir.cwd().symLink(io, target, path, .{});
        },
        else => return err,
    };
}

test "data layouts retain application bytes, package database and cache across sessions" {
    const io = std.testing.io;
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    var nonce: [16]u8 = undefined;
    io.random(&nonce);
    const temporary = try std.fmt.allocPrint(a, "/tmp/artzig-data-test-{s}", .{std.fmt.bytesToHex(nonce, .lower)});
    try Io.Dir.cwd().createDir(io, temporary, .fromMode(0o700));
    defer Io.Dir.cwd().deleteTree(io, temporary) catch {};
    const persistent = try std.fs.path.join(a, &.{ temporary, "persistent" });
    const cache = try std.fs.path.join(a, &.{ temporary, "cache" });
    const first = try create(a, io, cache, .{ .persistent_root = persistent });
    const user = try std.fs.path.join(a, &.{ first.path, "user", "0", "example" });
    try Io.Dir.cwd().createDirPath(io, user);
    try testWrite(io, try std.fs.path.join(a, &.{ user, "saved" }), "persistent application state");
    try testWrite(io, try std.fs.path.join(a, &.{ first.path, "system", "policy" }), "session one");
    try testWrite(io, try std.fs.path.join(a, &.{ first.path, "dalvik-cache", "compiled" }), "shared cache");
    first.release(io);
    // The partition is the persistent root itself: every canonical path stays inside it.
    try std.testing.expectEqualStrings(try Io.Dir.cwd().realPathFileAlloc(io, persistent, a), first.path);
    const canonical_user = try Io.Dir.cwd().realPathFileAlloc(io, user, a);
    try std.testing.expect(std.mem.startsWith(u8, canonical_user, first.path));
    const second = try create(a, io, cache, .{ .persistent_root = persistent });
    try testRead(a, io, try std.fs.path.join(a, &.{ second.path, "user", "0", "example", "saved" }), "persistent application state");
    try testRead(a, io, try std.fs.path.join(a, &.{ second.path, "data", "example", "saved" }), "persistent application state");
    try testRead(a, io, try std.fs.path.join(a, &.{ second.path, "dalvik-cache", "compiled" }), "shared cache");
    // The package database persists with the application data it owns.
    try testRead(a, io, try std.fs.path.join(a, &.{ second.path, "system", "policy" }), "session one");
    // A moved cache root replaces the disposable compiled-code link only.
    const other_cache = try std.fs.path.join(a, &.{ temporary, "other-cache" });
    const third = try create(a, io, other_cache, .{ .persistent_root = persistent });
    try testWrite(io, try std.fs.path.join(a, &.{ third.path, "dalvik-cache", "fresh" }), "new cache");
    try testRead(a, io, try std.fs.path.join(a, &.{ other_cache, "dalvik-cache", "fresh" }), "new cache");
    try testRead(a, io, try std.fs.path.join(a, &.{ third.path, "data", "example", "saved" }), "persistent application state");
    // A persistent root whose entries are not directories is rejected.
    const blocked = try std.fs.path.join(a, &.{ temporary, "blocked" });
    try Io.Dir.cwd().createDirPath(io, blocked);
    try testWrite(io, try std.fs.path.join(a, &.{ blocked, "user" }), "not a directory");
    try std.testing.expectError(error.NotDir, create(a, io, cache, .{ .persistent_root = blocked }));
}

test "application data that predates the package database moves to legacy storage once" {
    const io = std.testing.io;
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();
    var nonce: [16]u8 = undefined;
    io.random(&nonce);
    const temporary = try std.fmt.allocPrint(a, "/tmp/artzig-data-test-{s}", .{std.fmt.bytesToHex(nonce, .lower)});
    try Io.Dir.cwd().createDir(io, temporary, .fromMode(0o700));
    defer Io.Dir.cwd().deleteTree(io, temporary) catch {};
    const persistent = try std.fs.path.join(a, &.{ temporary, "persistent" });
    const cache = try std.fs.path.join(a, &.{ temporary, "cache" });
    for ([_][]const u8{ "user/0/org.example", "user_de/0/org.example" }) |relative| {
        const directory = try std.fs.path.join(a, &.{ persistent, relative });
        try Io.Dir.cwd().createDirPath(io, directory);
        try testWrite(io, try std.fs.path.join(a, &.{ directory, "saved" }), relative);
    }
    // An earlier interrupted move left a same-named entry behind.
    const previous = try std.fs.path.join(a, &.{ persistent, legacy_app_data, "user/0/org.example" });
    try Io.Dir.cwd().createDirPath(io, previous);
    const first = try create(a, io, cache, .{ .persistent_root = persistent });
    defer first.release(io);
    try std.testing.expectEqual(@as(usize, 0), try testEntryCount(io, try std.fs.path.join(a, &.{ persistent, "user/0" })));
    try std.testing.expectEqual(@as(usize, 0), try testEntryCount(io, try std.fs.path.join(a, &.{ persistent, "user_de/0" })));
    try testRead(a, io, try std.fs.path.join(a, &.{ persistent, legacy_app_data, "user/0/org.example.1/saved" }), "user/0/org.example");
    try testRead(a, io, try std.fs.path.join(a, &.{ persistent, legacy_app_data, "user_de/0/org.example/saved" }), "user_de/0/org.example");
    // Data written once the package database exists stays in place.
    const current = try std.fs.path.join(a, &.{ first.path, "user", "0", "org.example" });
    try Io.Dir.cwd().createDirPath(io, current);
    try testWrite(io, try std.fs.path.join(a, &.{ current, "saved" }), "current");
    const second = try create(a, io, cache, .{ .persistent_root = persistent });
    defer second.release(io);
    try testRead(a, io, try std.fs.path.join(a, &.{ second.path, "data", "org.example", "saved" }), "current");
}

fn testEntryCount(io: Io, path: []const u8) !usize {
    var dir = try Io.Dir.cwd().openDir(io, path, .{ .iterate = true });
    defer dir.close(io);
    var entries = dir.iterate();
    var count: usize = 0;
    while (try entries.next(io)) |_| count += 1;
    return count;
}

fn testWrite(io: Io, path: []const u8, bytes: []const u8) !void {
    var file = try Io.Dir.cwd().createFile(io, path, .{ .exclusive = true });
    defer file.close(io);
    try file.writeStreamingAll(io, bytes);
}

fn testRead(a: std.mem.Allocator, io: Io, path: []const u8, expected: []const u8) !void {
    const bytes = try Io.Dir.cwd().readFileAlloc(io, path, a, .limited(4096));
    try std.testing.expectEqualStrings(expected, bytes);
}
