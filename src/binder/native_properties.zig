//! Bionic-loader hooks for system_properties.cpp Find/Get/Read/ReadCallback.
//! Keep opaque property handles in one representation: stable slots in the same
//! broker-owned area used by Java. The upstream bionic adapter's name-pointer
//! handles must never enter libbase's unrelated process-local std::string map.
const std = @import("std");
const area = @import("prop_area.zig");
const framework = @import("framework_jni.zig");
const ndk = @import("ndk.zig");
const Info = opaque {};
const Callback = *const fn (?*anyopaque, [*:0]const u8, [*:0]const u8, u32) callconv(.c) void;
const long_legacy = "Must use __system_property_read_callback() to read";

fn slotOf(reader: *const area.Reader, info: ?*const Info) ?usize {
    const pointer = @intFromPtr(info orelse return null);
    const base = @intFromPtr(reader.map.ptr);
    if (pointer < base + area.header_len) return null;
    const slot = pointer - base;
    if (slot >= area.header_len + area.slot_capacity * area.slot_len or (slot - area.header_len) % area.slot_len != 0) return null;
    return slot;
}
fn androidSerial(value: []const u8, sequence: u32) u32 {
    const long = value.len >= area.value_max;
    const length = if (long) long_legacy.len else value.len;
    return (@as(u32, @intCast(length)) << 24) | (sequence & 0x00fffffe) | (if (long) @as(u32, 1 << 16) else 0);
}
fn copyZ(output: [*]u8, value: []const u8, capacity: usize) void {
    const len = @min(value.len, capacity - 1);
    @memcpy(output[0..len], value[0..len]);
    output[len] = 0;
}
export fn bionic___system_property_find(name: ?[*:0]const u8) callconv(.c) ?*const Info {
    const key = name orelse return null;
    const reader = framework.props() orelse return null;
    const slot = reader.find(std.mem.span(key)) orelse return null;
    return @ptrCast(reader.map.ptr + slot);
}
export fn bionic___system_property_read(info: ?*const Info, name: ?[*]u8, value: ?[*]u8) callconv(.c) c_int {
    const reader = framework.props() orelse return 0;
    const slot = slotOf(reader, info) orelse return 0;
    if (name) |out| copyZ(out, reader.nameAt(slot), 32); // legacy PROP_NAME_MAX
    var buffer: [area.long_value_max]u8 = undefined;
    const full = reader.getAt(slot, &buffer);
    const text = if (full.len >= area.value_max) long_legacy else full;
    if (value) |out| copyZ(out, text, area.value_max);
    return @intCast(text.len);
}
export fn bionic___system_property_get(name: ?[*:0]const u8, value: [*]u8) callconv(.c) c_int {
    const info = bionic___system_property_find(name) orelse { value[0] = 0; return 0; };
    return bionic___system_property_read(info, null, value);
}
export fn bionic___system_property_read_callback(info: ?*const Info, callback: Callback, cookie: ?*anyopaque) callconv(.c) void {
    const reader = framework.props() orelse return;
    const slot = slotOf(reader, info) orelse return;
    var buffer: [area.long_value_max]u8 = undefined;
    const snapshot = reader.snapshotAt(slot, &buffer);
    callback(cookie, @ptrCast(reader.map.ptr + slot + 4), @ptrCast(&buffer), androidSerial(snapshot.value, snapshot.serial));
}
export fn bionic___system_property_serial(info: ?*const Info) callconv(.c) u32 {
    const reader = framework.props() orelse return 0;
    const slot = slotOf(reader, info) orelse return 0;
    var buffer: [area.long_value_max]u8 = undefined;
    const snapshot = reader.snapshotAt(slot, &buffer);
    return androidSerial(snapshot.value, snapshot.serial);
}
export fn bionic___system_property_area_serial() callconv(.c) u32 {
    return if (framework.props()) |reader| reader.globalSerial() else 0;
}
export fn bionic___system_property_set(key: ?[*:0]const u8, value: ?[*:0]const u8) callconv(.c) c_int {
    const name = std.mem.span(key orelse return -1);
    const text = if (value) |v| std.mem.span(v) else "";
    if (name.len == 0 or name.len >= area.name_max or text.len >= area.long_value_max or
        (!std.mem.startsWith(u8, name, "ro.") and text.len >= area.value_max)) return -1;
    return if (ndk.setSystemProperty(name, text) == .ok) 0 else -1;
}
