//! Supervised process identities at the ProcessList/host-exec boundary.
//! The broker supplies the authenticated supervisor and package UID. A child
//! receives a one-use secret, bound to a kernel PID/start time before exec is
//! released. Revocation never makes an isolated UID reusable while its process
//! can still execute. All calls run under the broker's existing state lock.
const LaunchGrants = @This();
const std = @import("std");
const Io = std.Io;
const Allocator = std.mem.Allocator;

pub const Token = [64]u8;
pub const Kind = enum(u32) { app, isolated, system, webview_controller };
pub const Failure = error{ Denied, Invalid, Exhausted, Stale, OutOfMemory };
pub const Ticket = struct { id: u64, uid: u32, token: Token };
pub const Claim = struct {
    id: u64,
    uid: u32,
    package_uid: u32,
    kind: Kind,
    package: []const u8,
    process: []const u8,
    sequence: u64,
};
const KernelProcess = struct { pid: i32, parent: i32, start: u64, alive: bool };
const Grant = struct {
    ticket: Ticket,
    supervisor: u32,
    supervisor_pid: i32,
    kind: Kind,
    package_uid: u32,
    package: []u8,
    process: []u8,
    sequence: u64,
    child: ?KernelProcess = null,
    claimed_peer: ?u32 = null,
    revoked: bool = false,
};

gpa: Allocator,
io: Io,
limit: usize = 1024,
next_id: u64 = 1,
grants: std.ArrayList(Grant) = .empty,

pub fn deinit(self: *LaunchGrants) void {
    for (self.grants.items) |grant| self.free(grant);
    self.grants.deinit(self.gpa);
}

fn free(self: *LaunchGrants, grant: Grant) void {
    self.gpa.free(grant.package);
    self.gpa.free(grant.process);
}

pub fn reserve(self: *LaunchGrants, supervisor: u32, supervisor_pid: i32, kind: Kind, package: []const u8, process: []const u8, package_uid: u32, sequence: u64) Failure!Ticket {
    return self.reserveForUid(supervisor, supervisor_pid, kind, package, process, package_uid, sequence, null);
}

/// Process.start specifies its UID; package_uid must come from the broker's
/// own package registry. Ordinary services can still request automatic allocation.
pub fn reserveForUid(self: *LaunchGrants, supervisor: u32, supervisor_pid: i32, kind: Kind, package: []const u8, process: []const u8, package_uid: u32, sequence: u64, requested_uid: ?u32) Failure!Ticket {
    if (supervisor == 0 or supervisor_pid <= 0 or package.len == 0 or process.len == 0 or
        package.len > 4096 or process.len > 4096 or std.mem.indexOfScalar(u8, package, 0) != null or
        std.mem.indexOfScalar(u8, process, 0) != null) return error.Invalid;
    if (package_uid > std.math.maxInt(i32)) return error.Invalid;
    const app_id = package_uid % 100000;
    if (kind == .system) {
        if (package_uid != 1000) return error.Invalid;
    } else if (app_id < 10000 or app_id > 19999) return error.Invalid;
    self.collect();
    if (self.grants.items.len >= self.limit or self.next_id == std.math.maxInt(u64)) return error.Exhausted;
    const uid: u32 = switch (kind) {
        .app, .system => uid: {
            if (requested_uid) |requested| if (requested != package_uid) return error.Denied;
            break :uid package_uid;
        },
        .isolated => uid: {
            if (requested_uid) |requested| {
                const id = requested % 100000;
                if (requested > std.math.maxInt(i32) or requested / 100000 != package_uid / 100000 or
                    id < 90000 or id > 99999) return error.Denied;
                if (self.uidReserved(requested)) return error.Exhausted;
                break :uid requested;
            }
            break :uid try self.isolatedUid(package_uid / 100000);
        },
        .webview_controller => uid: {
            // WebViewZygote is a global UID 1053 supervisor, never a system
            // identity. Its code belongs to the selected user-0 provider.
            if (package_uid / 100000 != 0) return error.Denied;
            if (requested_uid) |requested| if (requested != 1053) return error.Denied;
            break :uid 1053;
        },
    };
    var random: [32]u8 = undefined;
    self.io.random(&random);
    const ticket: Ticket = .{ .id = self.next_id, .uid = uid, .token = std.fmt.bytesToHex(random, .lower) };
    const owned_package = try self.gpa.dupe(u8, package);
    errdefer self.gpa.free(owned_package);
    const owned_process = try self.gpa.dupe(u8, process);
    errdefer self.gpa.free(owned_process);
    try self.grants.append(self.gpa, .{
        .ticket = ticket,
        .supervisor = supervisor,
        .supervisor_pid = supervisor_pid,
        .kind = kind,
        .package_uid = package_uid,
        .package = owned_package,
        .process = owned_process,
        .sequence = sequence,
    });
    self.next_id += 1;
    return ticket;
}

fn uidReserved(self: *LaunchGrants, uid: u32) bool {
    for (self.grants.items) |grant| if (grant.ticket.uid == uid) return true;
    return false;
}

/// The WebView controller has only the child-UID authority of ChildZygoteInit;
/// it cannot issue ordinary app, system or further controller identities.
pub fn isIsolatedSupervisor(self: *LaunchGrants, peer: u32) bool {
    for (self.grants.items) |grant| {
        if (grant.kind != .webview_controller or grant.revoked or grant.claimed_peer != peer) continue;
        const child = grant.child orelse continue;
        const start = self.liveStartTime(child.pid) orelse continue;
        if (start == child.start) return true;
    }
    return false;
}

pub fn mayDelegate(self: *LaunchGrants, peer: u32, kind: Kind, uid: ?u32) bool {
    if (kind != .isolated or !self.isIsolatedSupervisor(peer)) return false;
    if (uid) |requested| {
        // Pinned WebViewZygote passes FIRST_ISOLATED_UID..Integer.MAX_VALUE.
        const app_id = requested % 100000;
        return requested >= 99000 and requested <= std.math.maxInt(i32) and app_id >= 90000 and app_id <= 99999;
    }
    return true;
}

fn isolatedUid(self: *LaunchGrants, user: u32) Failure!u32 {
    // ProcessList's global isolated range. App-zygote ranges (90000..98999)
    // belong to their own allocator; host exec does not consume them.
    var app_id: u32 = 99000;
    while (app_id <= 99999) : (app_id += 1) {
        const uid = user * 100000 + app_id;
        if (uid > std.math.maxInt(i32)) return error.Exhausted;
        if (!self.uidReserved(uid)) return uid;
    }
    return error.Exhausted;
}

/// The supervisor obtains child_pid from its process handle, never from a
/// message sent by the child. PPid and start time independently pin that handle.
pub fn bind(self: *LaunchGrants, supervisor: u32, id: u64, child_pid: i32) Failure!void {
    const grant = self.find(id) orelse return error.Stale;
    if (grant.supervisor != supervisor) return error.Denied;
    if (grant.revoked or grant.child != null or child_pid <= 0) return error.Stale;
    const child = self.inspect(child_pid) catch return error.Invalid;
    if (!child.alive or child.parent != grant.supervisor_pid) return error.Denied;
    // A kernel process has one immutable Android identity, including while a
    // revoked grant keeps its UID quarantined until that process exits.
    for (self.grants.items) |other| {
        if (other.child) |bound| if (bound.pid == child.pid and bound.start == child.start) return error.Denied;
    }
    grant.child = child;
}

pub fn claim(self: *LaunchGrants, token: []const u8, package: []const u8, child_pid: i32, peer: u32) Failure!Claim {
    if (token.len != 64 or peer == 0) return error.Denied;
    for (self.grants.items) |*grant| {
        if (!std.crypto.timing_safe.eql(Token, grant.ticket.token, token[0..64].*)) continue;
        if (grant.revoked or grant.claimed_peer != null or !std.mem.eql(u8, package, grant.package)) return error.Denied;
        const bound = grant.child orelse return error.Denied;
        if (bound.pid != child_pid) return error.Denied;
        const current = self.inspect(child_pid) catch return error.Denied;
        if (!current.alive or current.start != bound.start) return error.Denied;
        grant.claimed_peer = peer;
        return .{ .id = grant.ticket.id, .uid = grant.ticket.uid, .package_uid = grant.package_uid, .kind = grant.kind, .package = grant.package, .process = grant.process, .sequence = grant.sequence };
    }
    return error.Denied;
}

/// Returns the connected peer to retire, if any. Its UID remains quarantined
/// until collect observes process death, even when Binder disconnects earlier.
pub fn revoke(self: *LaunchGrants, supervisor: u32, id: u64) Failure!?u32 {
    const grant = self.find(id) orelse return error.Stale;
    if (grant.supervisor != supervisor) return error.Denied;
    grant.revoked = true;
    return grant.claimed_peer;
}

pub fn isBoundProcess(self: *LaunchGrants, pid: i32) bool {
    for (self.grants.items) |grant| {
        if (grant.child) |child| if (child.pid == pid and self.stillAlive(child)) return true;
    }
    return false;
}

/// Positive liveness evidence for peer identity lookups (not UID quarantine).
pub fn liveStartTime(self: *LaunchGrants, pid: i32) ?u64 {
    const current = self.inspect(pid) catch return null;
    return if (current.alive) current.start else null;
}

/// Translate only authenticated kernel processes. Unlike UID quarantine,
/// a metadata query needs positive evidence of a live matching start time.
/// Revocation does not change the UID of a child which can still execute.
pub fn liveUid(self: *LaunchGrants, pid: i32) ?u32 {
    for (self.grants.items) |grant| {
        if (grant.claimed_peer == null) continue;
        const child = grant.child orelse continue;
        if (child.pid != pid) continue;
        const current = self.inspect(pid) catch return null;
        if (current.alive and current.start == child.start) return grant.ticket.uid;
    }
    return null;
}

pub fn disconnected(self: *LaunchGrants, peer: u32) void {
    for (self.grants.items) |*grant| {
        if (grant.supervisor == peer or grant.claimed_peer == peer) grant.revoked = true;
    }
    self.collect();
}

fn find(self: *LaunchGrants, id: u64) ?*Grant {
    for (self.grants.items) |*grant| if (grant.ticket.id == id) return grant;
    return null;
}

fn collect(self: *LaunchGrants) void {
    var i: usize = 0;
    while (i < self.grants.items.len) {
        const grant = self.grants.items[i];
        const dead = if (grant.child) |child| !self.stillAlive(child) else grant.revoked;
        if (dead) {
            self.free(self.grants.swapRemove(i));
        } else i += 1;
    }
}

fn stillAlive(self: *LaunchGrants, child: KernelProcess) bool {
    const current = self.inspect(child.pid) catch |err| return switch (err) {
        error.FileNotFound => false,
        // An unreadable /proc entry is not proof that a UID can be recycled.
        else => true,
    };
    return current.alive and current.start == child.start;
}

fn inspect(self: *LaunchGrants, pid: i32) !KernelProcess {
    var path: [64]u8 = undefined;
    const file = try Io.Dir.cwd().openFile(self.io, try std.fmt.bufPrint(&path, "/proc/{d}/stat", .{pid}), .{});
    defer file.close(self.io);
    // procfs reports size zero; streaming reads must not infer EOF from stat.
    var buffer: [512]u8 = undefined;
    var reader = file.readerStreaming(self.io, &buffer);
    const text = try reader.interface.allocRemaining(self.gpa, .limited(8192));
    defer self.gpa.free(text);
    // comm may contain spaces and ')' characters; Linux emits its closing ')'
    // before the remaining scalar fields (proc_pid_stat(5), fields 3, 4 and 22).
    const end = std.mem.lastIndexOfScalar(u8, text, ')') orelse return error.Invalid;
    var fields = std.mem.tokenizeAny(u8, text[end + 1 ..], " \n");
    const state = fields.next() orelse return error.Invalid;
    const parent = try std.fmt.parseInt(i32, fields.next() orelse return error.Invalid, 10);
    var field: usize = 5;
    while (field < 22) : (field += 1) _ = fields.next() orelse return error.Invalid;
    const start = try std.fmt.parseInt(u64, fields.next() orelse return error.Invalid, 10);
    return .{ .pid = pid, .parent = parent, .start = start, .alive = !std.mem.eql(u8, state, "Z") and !std.mem.eql(u8, state, "X") };
}

test "PID binding, one-use claim, revocation and isolated UID quarantine" {
    const testing = std.testing;
    const io = testing.io;
    var authority: LaunchGrants = .{ .gpa = testing.allocator, .io = io };
    defer authority.deinit();
    var child = try std.process.spawn(io, .{
        .argv = &.{ "/bin/sh", "-c", "read release" },
        .stdin = .pipe,
        .stdout = .ignore,
        .stderr = .ignore,
    });
    defer child.kill(io);
    const child_pid = child.id.?;
    const supervisor_pid = @as(i32, @intCast(std.os.linux.getpid()));
    const ticket = try authority.reserve(1, supervisor_pid, .isolated, "fixture.package", "fixture.package:service:one", 10042, 71);
    try testing.expectEqual(@as(u32, 99000), ticket.uid);
    try testing.expectError(error.Denied, authority.claim(&ticket.token, "fixture.package", child_pid, 3));
    try testing.expectError(error.Denied, authority.bind(2, ticket.id, child_pid));
    try testing.expectError(error.Denied, authority.bind(1, ticket.id, supervisor_pid));
    try authority.bind(1, ticket.id, child_pid);
    try testing.expectError(error.Stale, authority.bind(1, ticket.id, child_pid));
    try testing.expectError(error.Denied, authority.claim(&ticket.token, "other.package", child_pid, 3));
    try testing.expectError(error.Denied, authority.claim(&ticket.token, "fixture.package", supervisor_pid, 3));
    const claim_result = try authority.claim(&ticket.token, "fixture.package", child_pid, 3);
    try testing.expectEqual(@as(u64, 71), claim_result.sequence);
    try testing.expectEqual(@as(u32, 10042), claim_result.package_uid);
    try testing.expectEqualStrings("fixture.package:service:one", claim_result.process);
    try testing.expectError(error.Denied, authority.claim(&ticket.token, "fixture.package", child_pid, 4));
    try testing.expectError(error.Denied, authority.revoke(2, ticket.id));
    try testing.expectEqual(@as(?u32, 3), try authority.revoke(1, ticket.id));
    authority.disconnected(3);
    const replacement = try authority.reserve(1, supervisor_pid, .isolated, "fixture.package", "fixture.package:service:two", 10042, 72);
    try testing.expect(replacement.uid != ticket.uid); // revoked child still alive
    child.kill(io);
    const reused = try authority.reserve(1, supervisor_pid, .isolated, "fixture.package", "fixture.package:service:one", 10042, 73);
    try testing.expectEqual(ticket.uid, reused.uid);
    try testing.expect(reused.id != ticket.id and !std.mem.eql(u8, &reused.token, &ticket.token));
    try testing.expectError(error.Stale, authority.revoke(1, ticket.id));
    try testing.expectError(error.Denied, authority.claim(&ticket.token, "fixture.package", child_pid, 5));
    _ = try authority.revoke(1, reused.id);
    _ = try authority.revoke(1, replacement.id);
    authority.collect();
    try testing.expectEqual(@as(usize, 0), authority.grants.items.len);
}

test "requested UIDs preserve package/user ownership and reserve pending isolated IDs" {
    var authority: LaunchGrants = .{ .gpa = std.testing.allocator, .io = std.testing.io };
    defer authority.deinit();
    const pid = std.os.linux.getpid();
    const normal = try authority.reserveForUid(1, pid, .app, "fixture", "fixture", 110042, 1, 110042);
    try std.testing.expectEqual(@as(u32, 110042), normal.uid);
    try std.testing.expectError(error.Denied, authority.reserveForUid(1, pid, .app, "fixture", "fixture", 110042, 2, 110043));
    try std.testing.expectError(error.Denied, authority.reserveForUid(1, pid, .app, "fixture", "fixture", 110042, 2, 10042));
    const isolated = try authority.reserveForUid(1, pid, .isolated, "fixture", "fixture:one", 110042, 3, 199321);
    try std.testing.expectEqual(@as(u32, 199321), isolated.uid);
    try std.testing.expectError(error.Exhausted, authority.reserveForUid(1, pid, .isolated, "fixture", "fixture:two", 110042, 4, 199321));
    try std.testing.expectError(error.Denied, authority.reserveForUid(1, pid, .isolated, "fixture", "fixture:two", 110042, 4, 99321));
    try std.testing.expectError(error.Denied, authority.reserveForUid(1, pid, .isolated, "fixture", "fixture:two", 110042, 4, 110042));
    _ = try authority.revoke(1, isolated.id);
    const reused = try authority.reserveForUid(1, pid, .isolated, "fixture", "fixture:two", 110042, 4, 199321);
    try std.testing.expect(reused.id != isolated.id);
    try std.testing.expectError(error.Denied, authority.reserveForUid(1, pid, .webview_controller, "provider", "webview_zygote", 10042, 5, 1000));
    try std.testing.expectError(error.Denied, authority.reserveForUid(1, pid, .webview_controller, "provider", "webview_zygote", 110042, 5, 1053));
}

test "controller authority is scoped, authenticated and revoked with its process" {
    const io = std.testing.io;
    var authority: LaunchGrants = .{ .gpa = std.testing.allocator, .io = io };
    defer authority.deinit();
    var child = try std.process.spawn(io, .{
        .argv = &.{ "/bin/sh", "-c", "read release" },
        .stdin = .pipe,
        .stdout = .ignore,
        .stderr = .ignore,
    });
    defer child.kill(io);
    const ticket = try authority.reserveForUid(1, std.os.linux.getpid(), .webview_controller, "provider", "webview_zygote", 10042, 1, 1053);
    try authority.bind(1, ticket.id, child.id.?);
    try std.testing.expect(!authority.mayDelegate(3, .isolated, 99001));
    const claim_result = try authority.claim(&ticket.token, "provider", child.id.?, 3);
    try std.testing.expectEqual(@as(u32, 1053), claim_result.uid);
    try std.testing.expectEqual(@as(u32, 10042), claim_result.package_uid);
    try std.testing.expect(authority.mayDelegate(3, .isolated, null));
    try std.testing.expect(authority.mayDelegate(3, .isolated, 99001));
    try std.testing.expect(authority.mayDelegate(3, .isolated, 199001));
    for ([_]Kind{ .app, .system, .webview_controller }) |kind|
        try std.testing.expect(!authority.mayDelegate(3, kind, null));
    try std.testing.expect(!authority.mayDelegate(3, .isolated, 10042));
    try std.testing.expect(!authority.mayDelegate(3, .isolated, 90000));
    try std.testing.expect(!authority.mayDelegate(4, .isolated, 99001));
    const other = try authority.reserve(1, std.os.linux.getpid(), .isolated, "provider", "other", 10042, 2);
    try std.testing.expectError(error.Denied, authority.bind(1, other.id, child.id.?));
    _ = try authority.revoke(1, ticket.id);
    try std.testing.expect(!authority.mayDelegate(3, .isolated, 99001));
    // Revocation changes authority, not the identity of a still-living process.
    try std.testing.expectEqual(@as(?u32, 1053), authority.liveUid(child.id.?));
}
