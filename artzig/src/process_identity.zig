//! Immutable authenticated identity, installed before ART. CriticalNative
//! getters only read memory. Ordinary peer-credential JNI may call the mapper.
const std = @import("std");
const Mapper = *const fn (i32, *u32, *u32) callconv(.c) void;
var state: std.atomic.Value(u8) = .init(0); // unset, initializing, ready
var own_uid: u32 = undefined;
var own_gid: u32 = undefined;
var peer_mapper: ?Mapper = null;

pub export fn azk_process_identity_initialize(uid: u32, gid: u32, mapper: ?Mapper) callconv(.c) c_int {
    if (uid == std.math.maxInt(u32) or gid == std.math.maxInt(u32)) return -1;
    if (state.cmpxchgStrong(0, 1, .acq_rel, .acquire)) |previous| {
        return if (previous == 2 and own_uid == uid and own_gid == gid and peer_mapper == mapper) 0 else -1;
    }
    own_uid = uid;
    own_gid = gid;
    peer_mapper = mapper;
    state.store(2, .release);
    return 0;
}

pub export fn azk_process_getuid() callconv(.c) u32 {
    return if (state.load(.acquire) == 2) own_uid else std.os.linux.getuid();
}
pub export fn azk_process_geteuid() callconv(.c) u32 {
    return if (state.load(.acquire) == 2) own_uid else std.os.linux.geteuid();
}
pub export fn azk_process_getgid() callconv(.c) u32 {
    return if (state.load(.acquire) == 2) own_gid else std.os.linux.getgid();
}
pub export fn azk_process_getegid() callconv(.c) u32 {
    return if (state.load(.acquire) == 2) own_gid else std.os.linux.getegid();
}
pub export fn azk_process_map_credentials(pid: i32, uid: *u32, gid: *u32) callconv(.c) void {
    if (state.load(.acquire) != 2) return;
    if (pid == std.os.linux.getpid()) {
        uid.* = own_uid;
        gid.* = own_gid;
    } else if (peer_mapper) |map| map(pid, uid, gid);
}

// A trusted hosted VM may have a different outer Linux identity. Its
// authenticated identity is immutable: same-ID setters are already satisfied;
// changing it cannot acquire another broker identity. Real namespace children
// and standalone ART retain the original kernel setter and errno behavior.
fn checkChange(current: u32, real: u32, effective: u32, unchanged: bool) c_int {
    const skip = std.math.maxInt(u32);
    if ((real == current or (unchanged and real == skip)) and
        (effective == current or (unchanged and effective == skip))) return 1;
    if (!unchanged and (real == skip or effective == skip)) return -@as(c_int, @backingInt(std.os.linux.E.INVAL));
    return -@as(c_int, @backingInt(std.os.linux.E.PERM));
}
pub export fn azk_process_check_uid_change(real: u32, effective: u32, unchanged: c_int) callconv(.c) c_int {
    if (state.load(.acquire) != 2 or (own_uid == std.os.linux.getuid() and own_uid == std.os.linux.geteuid())) return 0;
    return checkChange(own_uid, real, effective, unchanged != 0);
}
pub export fn azk_process_check_gid_change(real: u32, effective: u32, unchanged: c_int) callconv(.c) c_int {
    if (state.load(.acquire) != 2 or (own_gid == std.os.linux.getgid() and own_gid == std.os.linux.getegid())) return 0;
    return checkChange(own_gid, real, effective, unchanged != 0);
}

test "fixed identity setter decisions preserve unchanged IDs and reject other identities" {
    const skip = std.math.maxInt(u32);
    try std.testing.expectEqual(@as(c_int, 1), checkChange(10042, 10042, 10042, false));
    try std.testing.expectEqual(@as(c_int, 1), checkChange(10042, skip, 10042, true));
    try std.testing.expectEqual(@as(c_int, 1), checkChange(10042, 10042, skip, true));
    try std.testing.expectEqual(@as(c_int, 1), checkChange(10042, skip, skip, true));
    try std.testing.expectEqual(-@as(c_int, @backingInt(std.os.linux.E.PERM)), checkChange(10042, 1000, 10042, true));
    try std.testing.expectEqual(-@as(c_int, @backingInt(std.os.linux.E.PERM)), checkChange(10042, 10042, 1000, true));
    try std.testing.expectEqual(-@as(c_int, @backingInt(std.os.linux.E.INVAL)), checkChange(10042, skip, skip, false));
}

fn testMap(pid: i32, uid: *u32, gid: *u32) callconv(.c) void {
    if (pid == std.math.maxInt(i32)) {
        uid.* = 12345;
        gid.* = 12346;
    }
}

test "identity and peer credentials preserve native defaults and cannot be reconfigured" {
    try std.testing.expectEqual(std.os.linux.getuid(), azk_process_getuid());
    try std.testing.expectEqual(std.os.linux.geteuid(), azk_process_geteuid());
    try std.testing.expectEqual(std.os.linux.getgid(), azk_process_getgid());
    try std.testing.expectEqual(std.os.linux.getegid(), azk_process_getegid());
    var uid: u32 = 44;
    var gid: u32 = 45;
    azk_process_map_credentials(std.os.linux.getpid(), &uid, &gid);
    try std.testing.expect(uid == 44 and gid == 45);
    try std.testing.expectEqual(@as(c_int, -1), azk_process_identity_initialize(std.math.maxInt(u32), 10042, null));
    try std.testing.expectEqual(@as(c_int, 0), azk_process_identity_initialize(10042, 10043, testMap));
    try std.testing.expectEqual(@as(c_int, 0), azk_process_identity_initialize(10042, 10043, testMap));
    try std.testing.expectEqual(@as(c_int, -1), azk_process_identity_initialize(10042, 10043, null));
    try std.testing.expectEqual(@as(c_int, -1), azk_process_identity_initialize(1000, 1000, testMap));
    try std.testing.expectEqual(@as(u32, 10042), azk_process_getuid());
    try std.testing.expectEqual(@as(u32, 10042), azk_process_geteuid());
    try std.testing.expectEqual(@as(u32, 10043), azk_process_getgid());
    try std.testing.expectEqual(@as(u32, 10043), azk_process_getegid());
    azk_process_map_credentials(std.os.linux.getpid(), &uid, &gid);
    try std.testing.expect(uid == 10042 and gid == 10043);
    azk_process_map_credentials(std.math.maxInt(i32), &uid, &gid);
    try std.testing.expect(uid == 12345 and gid == 12346);
    azk_process_map_credentials(-1, &uid, &gid);
    try std.testing.expect(uid == 12345 and gid == 12346);
}
