//! Immutable Android identity below the original Process JNI. The identity
//! authority remains libjavacore; proc snapshots only translate its known peers.
const std = @import("std");
const Io = std.Io;
extern "c" fn __errno_location() *c_int;
extern "c" fn fdopen(c_int, [*:0]const u8) ?*anyopaque;
var g_io: Io = undefined;
var identity: std.DynLib = undefined;
const Check = *const fn (u32, u32, c_int) callconv(.c) c_int;
const Map = *const fn (i32, *u32, *u32) callconv(.c) void;
var check_uid: Check = undefined;
var check_gid: Check = undefined;
var map_credentials: Map = undefined;
pub fn bindIo(io: Io) void {
    g_io = io;
}
export fn azk_process_backend_initialize() c_int {
    identity = std.DynLib.open("libjavacore.so") catch return -1;
    check_uid = identity.lookup(Check, "azk_process_check_uid_change") orelse return -1;
    check_gid = identity.lookup(Check, "azk_process_check_gid_change") orelse return -1;
    map_credentials = identity.lookup(Map, "azk_process_map_credentials") orelse return -1;
    return 0;
}
export fn azk_process_uid_change(uid: u32) c_int {
    return check_uid(uid, uid, 0);
}
export fn azk_process_gid_change(gid: u32) c_int {
    return check_gid(gid, gid, 0);
}

fn procStatus(path: []const u8) bool {
    if (path.len < 14 or !std.mem.startsWith(u8, path, "/proc/") or !std.mem.endsWith(u8, path, "/status")) return false;
    var parts = std.mem.splitScalar(u8, path[6 .. path.len - 7], '/');
    const leader = parts.next() orelse return false;
    if (!std.mem.eql(u8, leader, "self") and !std.mem.eql(u8, leader, "thread-self"))
        _ = std.fmt.parseInt(u32, leader, 10) catch return false;
    if (parts.next()) |task| {
        if (!std.mem.eql(u8, task, "task")) return false;
        _ = std.fmt.parseInt(u32, parts.next() orelse return false, 10) catch return false;
    }
    return parts.next() == null;
}
fn firstNumber(line: []const u8, key: []const u8) ?u32 {
    if (!std.mem.startsWith(u8, line, key)) return null;
    var fields = std.mem.tokenizeAny(u8, line[key.len..], " \t");
    return std.fmt.parseInt(u32, fields.next() orelse return null, 10) catch null;
}
// Obtain the Tgid from the opened kernel file, so /proc/tid and task/tid use
// the authenticated process identity instead of querying a thread as a PID.
fn translate(allocator: std.mem.Allocator, data: []const u8, mapper: Map) !?[]u8 {
    var tgid: ?u32 = null;
    var uid: ?u32 = null;
    var gid: ?u32 = null;
    var lines = std.mem.splitScalar(u8, data, '\n');
    while (lines.next()) |line| {
        if (firstNumber(line, "Tgid:")) |n| tgid = n;
        if (firstNumber(line, "Uid:")) |n| uid = n;
        if (firstNumber(line, "Gid:")) |n| gid = n;
    }
    const pid = tgid orelse return null;
    if (pid == 0 or pid > std.math.maxInt(i32) or uid == null or gid == null) return null;
    var mapped_uid = uid.?;
    var mapped_gid = gid.?;
    mapper(@intCast(pid), &mapped_uid, &mapped_gid);
    if (mapped_uid == uid.? and mapped_gid == gid.?) return null;
    var output: Io.Writer.Allocating = .init(allocator);
    errdefer output.deinit();
    var pos: usize = 0;
    while (pos < data.len) {
        const end = if (std.mem.indexOfScalarPos(u8, data, pos, '\n')) |n| n + 1 else data.len;
        const line = data[pos..end];
        if (std.mem.startsWith(u8, line, "Uid:") or std.mem.startsWith(u8, line, "Gid:")) {
            const value = if (line[0] == 'U') mapped_uid else mapped_gid;
            try output.writer.print("{s}\t{d}\t{d}\t{d}\t{d}{s}", .{ line[0..4], value, value, value, value, if (line[line.len - 1] == '\n') "\n" else "" });
        } else try output.writer.writeAll(line);
        pos = end;
    }
    return try output.toOwnedSlice();
}
fn statusFile(io: Io, file: Io.File, mapper: Map) !?Io.File {
    var buffer: [4096]u8 = undefined;
    var reader = file.reader(io, &buffer);
    // procfs reports size zero. Skip sendFile's stat-size optimization while
    // retaining positional reads so an unchanged descriptor stays at offset 0.
    reader.mode = .positional_simple;
    const allocator = std.heap.c_allocator;
    const data = reader.interface.allocRemaining(allocator, .limited(64 * 1024 * 1024)) catch |err| return reader.err orelse err;
    defer allocator.free(data);
    const mapped = try translate(allocator, data, mapper) orelse return null;
    defer allocator.free(mapped);
    const raw = std.os.linux.memfd_create("android-process-status", std.os.linux.MFD.CLOEXEC);
    if (std.os.linux.errno(raw) != .SUCCESS) return error.SystemResources;
    const snapshot: Io.File = .{ .handle = @intCast(raw), .flags = .{ .nonblocking = false } };
    errdefer snapshot.close(io);
    try snapshot.writePositionalAll(io, mapped, 0);
    return snapshot;
}
export fn azk_process_open(path: [*:0]const u8, flags: c_int) c_int {
    if (flags != 0 and flags != 0x80000) return fail(.INVAL); // read-only with optional CLOEXEC
    const name = std.mem.span(path);
    const file = Io.Dir.cwd().openFile(g_io, name, .{}) catch |err| return fail(openErrno(err));
    if (!procStatus(name)) return file.handle;
    const mapped = statusFile(g_io, file, map_credentials) catch |err| {
        file.close(g_io);
        return fail(openErrno(err));
    };
    if (mapped) |snapshot| {
        file.close(g_io);
        return snapshot.handle;
    }
    // Positional reads leave the original descriptor's offset at zero.
    return file.handle;
}
export fn azk_process_fopen(path: [*:0]const u8, mode: [*:0]const u8) ?*anyopaque {
    if (!std.mem.eql(u8, std.mem.span(mode), "r") and !std.mem.eql(u8, std.mem.span(mode), "re")) {
        _ = fail(.INVAL);
        return null;
    }
    const fd = azk_process_open(path, 0x80000);
    if (fd < 0) return null;
    if (fdopen(fd, mode)) |stream| return stream; // original fclose owns it
    const saved = __errno_location().*;
    const file: Io.File = .{ .handle = fd, .flags = .{ .nonblocking = false } };
    file.close(g_io);
    __errno_location().* = saved;
    return null;
}
fn openErrno(err: anyerror) std.os.linux.E {
    return switch (err) {
        error.FileNotFound => .NOENT,
        error.AccessDenied => .ACCES,
        error.PermissionDenied => .PERM,
        error.SymLinkLoop => .LOOP,
        error.ProcessFdQuotaExceeded => .MFILE,
        error.SystemFdQuotaExceeded => .NFILE,
        error.NotDir => .NOTDIR,
        error.IsDir => .ISDIR,
        error.NameTooLong => .NAMETOOLONG,
        error.OutOfMemory, error.SystemResources => .NOMEM,
        else => .IO,
    };
}
fn fail(err: std.os.linux.E) c_int {
    __errno_location().* = @backingInt(err);
    return -1;
}
fn testMap(pid: i32, uid: *u32, gid: *u32) callconv(.c) void {
    if (pid == 42) {
        uid.* = 10042;
        gid.* = 10043;
    }
}
test "proc status path matching includes threads without treating arbitrary files as proc" {
    for ([_][]const u8{ "/proc/self/status", "/proc/thread-self/status", "/proc/42/status", "/proc/42/task/73/status" }) |path| try std.testing.expect(procStatus(path));
    for ([_][]const u8{ "/proc/status", "/tmp/proc/42/status", "/proc/42/stat", "/proc/42/other/status", "/proc/42/task/x/status" }) |path| try std.testing.expect(!procStatus(path));
}
test "authenticated proc identity preserves all other kernel fields and unknown peers" {
    const input = "Name:\tworker\nTgid:\t42\nPid:\t73\nUid:\t1000\t1000\t1000\t1000\nGid:\t1000\t1000\t1000\t1000\nVmRSS:\t250 kB\n";
    const mapped = (try translate(std.testing.allocator, input, testMap)).?;
    defer std.testing.allocator.free(mapped);
    try std.testing.expectEqualStrings("Name:\tworker\nTgid:\t42\nPid:\t73\nUid:\t10042\t10042\t10042\t10042\nGid:\t10043\t10043\t10043\t10043\nVmRSS:\t250 kB\n", mapped);
    try std.testing.expectEqual(null, try translate(std.testing.allocator, "Tgid:\t9\nUid:\t2\nGid:\t3\n", testMap));
    try std.testing.expectEqual(null, try translate(std.testing.allocator, "Uid:\t2\nGid:\t3\n", testMap));
}

fn testProcMap(_: i32, uid: *u32, gid: *u32) callconv(.c) void {
    uid.* = 424242;
    gid.* = 424243;
}
fn testUnknownMap(_: i32, _: *u32, _: *u32) callconv(.c) void {}
test "zero-size procfs snapshots retain kernel fields and descriptor positions" {
    const io = std.testing.io;
    const file = try Io.Dir.cwd().openFile(io, "/proc/self/status", .{});
    defer file.close(io);
    try std.testing.expectEqual(@as(u64, 0), (try file.stat(io)).size);
    const snapshot = (try statusFile(io, file, testProcMap)).?;
    defer snapshot.close(io);
    var bytes: [4096]u8 = undefined;
    const length = try snapshot.readStreaming(io, &.{&bytes});
    const data = bytes[0..length];
    try std.testing.expect(std.mem.startsWith(u8, data, "Name:"));
    try std.testing.expect(std.mem.indexOf(u8, data, "Uid:\t424242\t424242\t424242\t424242\n") != null);
    try std.testing.expect(std.mem.indexOf(u8, data, "Gid:\t424243\t424243\t424243\t424243\n") != null);
    try std.testing.expectEqual(null, try statusFile(io, file, testUnknownMap));
    const original_length = try file.readStreaming(io, &.{&bytes});
    try std.testing.expect(std.mem.startsWith(u8, bytes[0..original_length], "Name:"));
}
