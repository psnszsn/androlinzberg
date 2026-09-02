//! Session-wide system property area (source note: bionic `prop_area` /
//! `prop_info`, simplified to a flat slot table).
//!
//! One file per session (`<session-dir>/props`), created and written only by
//! the broker; every other process maps it read-only and reads lock-free.
//! Writes reach the broker as `set_property` messages, mirroring Android
//! where only init writes the property area and `property_service` accepts
//! set requests over a socket.
//!
//! Layout (little-endian, offsets fixed for the JVM and bionic readers):
//!   header: magic u32, version u32, global_serial u32, count u32,
//!           slot_capacity u32, pad to 32 bytes
//!   slots:  [slot_capacity] x { serial u32, name [name_max]u8 (NUL-padded),
//!           value [value_max]u8 (NUL-padded) }
//!   long values: [slot_capacity] x [long_value_max]u8 (NUL-padded)
//!
//! The legacy slot stays ABI-compatible with `__system_property_get`. Its
//! value is truncated for a long property, while Zig/JVM readers use the
//! parallel full-value field when the slot serial carries `serial_long`.
//!
//! Torn-read protocol per bionic: a slot's serial is odd while its value is
//! being rewritten; readers retry until they observe the same even serial
//! before and after copying. Slots are append-only: a name never moves and is
//! never removed, so readers may cache slot offsets forever. `count` is
//! store-released only after a new slot is fully written.
const std = @import("std");
const Io = std.Io;
const assert = std.debug.assert;

pub const magic: u32 = 0x504B5A41; // "AZKP"
pub const version: u32 = 2;
/// Name capacity including the terminating NUL.
pub const name_max = 128;
/// Value capacity including the terminating NUL (PROP_VALUE_MAX 91 + NUL).
pub const value_max = 92;
/// Capacity for read-only values, including the terminating NUL. Source note:
/// follows bionic `prop_info::is_long` / `__system_property_read_callback`;
/// the hosted broker needs a flat shared-file equivalent because Android's
/// init-owned property area is not present in this process model.
pub const long_value_max = 512;
pub const slot_capacity = 512;

pub const header_len = 32;
pub const slot_len = 4 + name_max + value_max;
const legacy_file_len = header_len + slot_capacity * slot_len;
pub const file_len = legacy_file_len + slot_capacity * long_value_max;

const off_global_serial = 8;
const off_count = 12;
const serial_long: u32 = 1 << 31;
const serial_sequence_mask: u32 = ~serial_long;

comptime {
    assert(slot_len % 4 == 0);
    assert(long_value_max > value_max);
}

pub const Error = error{ Full, NameTooLong, ValueTooLong };

/// The broker's read-write view. Only one process may ever hold this.
pub const Area = struct {
    map: []align(std.heap.page_size_min) u8,

    /// Creates (truncating) and maps the area file, writing the header.
    pub fn create(io: Io, path: []const u8) !Area {
        const file = try Io.Dir.createFileAbsolute(io, path, .{
            .read = true,
            .truncate = true,
            .permissions = @fromBackingInt(@intCast(0o600)),
        });
        defer file.close(io);
        try file.setLength(io, file_len);
        const map = try std.posix.mmap(
            null,
            file_len,
            .{ .READ = true, .WRITE = true },
            .{ .TYPE = .SHARED },
            file.handle,
            0,
        );
        var a: Area = .{ .map = map };
        std.mem.writeInt(u32, a.map[0..4], magic, .little);
        std.mem.writeInt(u32, a.map[4..8], version, .little);
        atomicPtr(a.map, off_global_serial).* = 0;
        atomicPtr(a.map, off_count).* = 0;
        std.mem.writeInt(u32, a.map[16..20], slot_capacity, .little);
        return a;
    }

    pub fn deinit(a: *Area) void {
        std.posix.munmap(a.map);
        a.* = undefined;
    }

    /// Sets `name` to `value`, appending a slot for a new name. The value may
    /// be empty; a property is never removed.
    pub fn set(a: *Area, name: []const u8, value: []const u8) Error!void {
        if (name.len == 0 or name.len >= name_max) return error.NameTooLong;
        if (value.len >= long_value_max) return error.ValueTooLong;
        const new_long = value.len >= value_max;
        if (a.find(name)) |slot| {
            const serial = atomicPtr(a.map, slot);
            const previous = @atomicLoad(u32, serial, .monotonic);
            const dirty = nextSequence(previous) | (previous & serial_long);
            @atomicStore(u32, serial, dirty, .release);
            writeValue(a.map, slot, value);
            @atomicStore(u32, serial, nextSequence(dirty) | if (new_long) serial_long else 0, .release);
        } else {
            const count = atomicPtr(a.map, off_count);
            const n = @atomicLoad(u32, count, .monotonic);
            if (n >= slot_capacity) return error.Full;
            const slot = header_len + n * slot_len;
            writeField(a.map[slot + 4 ..][0..name_max], name);
            writeValue(a.map, slot, value);
            @atomicStore(u32, atomicPtr(a.map, slot), if (new_long) serial_long else 0, .release);
            @atomicStore(u32, count, n + 1, .release);
        }
        const global = atomicPtr(a.map, off_global_serial);
        @atomicStore(u32, global, @atomicLoad(u32, global, .monotonic) +% 1, .release);
    }

    /// Reads a property; `buf` must hold `long_value_max` bytes. Returns null for
    /// an unknown name.
    pub fn get(a: *const Area, name: []const u8, buf: *[long_value_max]u8) ?[]const u8 {
        return read(a.map, name, buf);
    }

    fn find(a: *const Area, name: []const u8) ?usize {
        return findSlot(a.map, name);
    }
};

/// Reader used by tests and other Zig-side consumers; maps read-only.
pub const Reader = struct {
    map: []align(std.heap.page_size_min) u8,

    pub fn open(io: Io, path: []const u8) !Reader {
        const file = try Io.Dir.openFileAbsolute(io, path, .{});
        defer file.close(io);
        const map = try std.posix.mmap(
            null,
            file_len,
            .{ .READ = true },
            .{ .TYPE = .SHARED },
            file.handle,
            0,
        );
        if (std.mem.readInt(u32, map[0..4], .little) != magic) {
            std.posix.munmap(map);
            return error.BadPropertyArea;
        }
        return .{ .map = map };
    }

    pub fn deinit(r: *Reader) void {
        std.posix.munmap(r.map);
        r.* = undefined;
    }

    pub fn get(r: *const Reader, name: []const u8, buf: *[long_value_max]u8) ?[]const u8 {
        return read(r.map, name, buf);
    }

    /// Slot byte offset for `name`, or null. Slots are append-only and never
    /// move, so the offset is a stable handle (SystemProperties native_find).
    pub fn find(r: *const Reader, name: []const u8) ?usize {
        return findSlot(r.map, name);
    }

    /// Serial-protocol read of a known slot (from `find`).
    pub fn getAt(r: *const Reader, slot: usize, buf: *[long_value_max]u8) []const u8 {
        return readSlot(r.map, slot, buf);
    }

    pub fn globalSerial(r: *const Reader) u32 {
        return @atomicLoad(u32, atomicPtr(r.map, off_global_serial), .acquire);
    }
};

fn atomicPtr(map: []align(std.heap.page_size_min) u8, offset: usize) *u32 {
    return @ptrCast(@alignCast(map.ptr + offset));
}

/// NUL-terminates and zero-pads so readers never see stale bytes of a longer
/// previous value.
fn writeField(field: []u8, text: []const u8) void {
    @memcpy(field[0..text.len], text);
    @memset(field[text.len..], 0);
}

fn nextSequence(serial: u32) u32 {
    return ((serial & serial_sequence_mask) +% 1) & serial_sequence_mask;
}

fn longValueOffset(slot: usize) usize {
    const index = (slot - header_len) / slot_len;
    return legacy_file_len + index * long_value_max;
}

fn writeValue(map: []align(std.heap.page_size_min) u8, slot: usize, value: []const u8) void {
    const legacy = map[slot + 4 + name_max ..][0..value_max];
    writeField(legacy, value[0..@min(value.len, value_max - 1)]);
    const offset = longValueOffset(slot);
    const long = map[offset..][0..long_value_max];
    if (value.len >= value_max) writeField(long, value) else @memset(long, 0);
}

fn fieldText(field: []const u8) []const u8 {
    const end = std.mem.indexOfScalar(u8, field, 0) orelse field.len;
    return field[0..end];
}

fn findSlot(map: []align(std.heap.page_size_min) u8, name: []const u8) ?usize {
    const count = @atomicLoad(u32, atomicPtr(map, off_count), .acquire);
    var i: u32 = 0;
    while (i < count) : (i += 1) {
        const slot = header_len + i * slot_len;
        if (std.mem.eql(u8, fieldText(map[slot + 4 ..][0..name_max]), name)) return slot;
    }
    return null;
}

fn read(map: []align(std.heap.page_size_min) u8, name: []const u8, buf: *[long_value_max]u8) ?[]const u8 {
    const slot = findSlot(map, name) orelse return null;
    return readSlot(map, slot, buf);
}

fn readSlot(map: []align(std.heap.page_size_min) u8, slot: usize, buf: *[long_value_max]u8) []const u8 {
    const serial = atomicPtr(map, slot);
    // Bounded: a writer killed mid-write leaves the serial odd forever; a
    // possibly-torn (still NUL-terminated) value beats a hang.
    var attempt: u32 = 0;
    while (true) : (attempt += 1) {
        const before = @atomicLoad(u32, serial, .acquire);
        if ((before & serial_sequence_mask) % 2 != 0 and attempt < 1000) {
            std.atomic.spinLoopHint();
            continue;
        }
        if (before & serial_long != 0) {
            const offset = longValueOffset(slot);
            @memcpy(buf, map[offset..][0..long_value_max]);
        } else {
            @memcpy(buf[0..value_max], map[slot + 4 + name_max ..][0..value_max]);
            @memset(buf[value_max..], 0);
        }
        if (@atomicLoad(u32, serial, .acquire) == before or attempt >= 1000) return fieldText(buf);
    }
}

test "area set/get, update, append, torn-write serial" {
    const testing = std.testing;
    const io = testing.io;
    var path_buf: [128]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buf, "/tmp/azk-prop-area-test-{d}", .{std.os.linux.getpid()});
    var area = try Area.create(io, path);
    defer {
        area.deinit();
        Io.Dir.deleteFileAbsolute(io, path) catch {};
    }
    var buf: [long_value_max]u8 = undefined;
    try testing.expectEqual(null, area.get("ro.missing", &buf));
    try area.set("ro.build.version.sdk", "35");
    try testing.expectEqualStrings("35", area.get("ro.build.version.sdk", &buf).?);
    try area.set("debug.example", "first-value");
    try area.set("debug.example", "x");
    try testing.expectEqualStrings("x", area.get("debug.example", &buf).?);
    // The shortened value must not expose stale tail bytes.
    try testing.expectEqual(1, area.get("debug.example", &buf).?.len);
    try area.set("debug.empty", "");
    try testing.expectEqualStrings("", area.get("debug.empty", &buf).?);
    const known_codenames = "Base,Base11,Cupcake,Donut,Eclair,Eclair01,EclairMr1,Froyo,Gingerbread,GingerbreadMr1,Honeycomb,HoneycombMr1,HoneycombMr2,IceCreamSandwich,IceCreamSandwichMr1,JellyBean,JellyBeanMr1,JellyBeanMr2,Kitkat,KitkatWatch,Lollipop,LollipopMr1,M,N,NMr1,O,OMr1,P,Q,R,S,Sv2,Tiramisu,UpsideDownCake,VanillaIceCream";
    try area.set("ro.build.version.known_codenames", known_codenames);
    try testing.expectEqualStrings(known_codenames, area.get("ro.build.version.known_codenames", &buf).?);
    const long_value: [long_value_max]u8 = @splat('x');
    try testing.expectError(error.ValueTooLong, area.set("debug.long", &long_value));
    var long_name: [name_max + 2]u8 = @splat('n');
    long_name[0] = 'd';
    long_name[1] = '.';
    try testing.expectError(error.NameTooLong, area.set(&long_name, "v"));

    var reader = try Reader.open(io, path);
    defer reader.deinit();
    try testing.expectEqualStrings("x", reader.get("debug.example", &buf).?);
    const serial_before = reader.globalSerial();
    try area.set("debug.example", "y");
    try testing.expectEqualStrings("y", reader.get("debug.example", &buf).?);
    try testing.expect(reader.globalSerial() != serial_before);
}
