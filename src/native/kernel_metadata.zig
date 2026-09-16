//! Linux's task_struct.stack_vm_area exists only under CONFIG_VMAP_STACK.
//! Read the running kernel's type metadata when its config file is unavailable.
//! Source: Linux v7.0 include/linux/sched.h; UAPI linux/btf.h kinds/layout.
const std = @import("std");
const Error = error{ InvalidBtf, MissingTaskType };

fn bytes(data: []const u8, offset: usize, length: usize) Error![]const u8 {
    if (offset > data.len or length > data.len - offset) return error.InvalidBtf;
    return data[offset..][0..length];
}
fn word(data: []const u8, offset: usize, endian: std.builtin.Endian) Error!u32 {
    return std.mem.readInt(u32, (try bytes(data, offset, 4))[0..4], endian);
}
fn name(strings: []const u8, offset: usize) Error![]const u8 {
    if (offset >= strings.len) return error.InvalidBtf;
    const end = std.mem.indexOfScalar(u8, strings[offset..], 0) orelse return error.InvalidBtf;
    return strings[offset..][0..end];
}

pub fn stackMapping(data: []const u8) Error!bool {
    const header = try bytes(data, 0, 24);
    const endian: std.builtin.Endian = if (header[0] == 0x9f and header[1] == 0xeb) .little else if (header[0] == 0xeb and header[1] == 0x9f) .big else return error.InvalidBtf;
    if (header[2] != 1 or header[3] != 0) return error.InvalidBtf;
    const header_length: usize = try word(header, 4, endian);
    if (header_length < 24) return error.InvalidBtf;
    const type_offset: usize = try word(header, 8, endian);
    const types = try bytes(data, header_length + type_offset, try word(header, 12, endian));
    const string_offset: usize = try word(header, 16, endian);
    const strings = try bytes(data, header_length + string_offset, try word(header, 20, endian));
    if (strings.len == 0 or strings[0] != 0) return error.InvalidBtf;
    var offset: usize = 0;
    var result: ?bool = null;
    while (offset < types.len) {
        const entry = try bytes(types, offset, 12);
        const type_name = try name(strings, try word(entry, 0, endian));
        const info = try word(entry, 4, endian);
        const kind = (info >> 24) & 31;
        const count: usize = info & 0xffff;
        const payload: usize = switch (kind) {
            0, 2, 7, 8, 9, 10, 11, 12, 16, 18 => 0,
            1, 14, 17 => 4,
            3 => 12,
            4, 5, 15, 19 => count * 12,
            6, 13 => count * 8,
            else => return error.InvalidBtf,
        };
        const members = try bytes(types, offset + 12, payload);
        if (kind == 4 and std.mem.eql(u8, type_name, "task_struct")) {
            if (count == 0 or try word(entry, 8, endian) == 0) return error.InvalidBtf;
            var mapped = false;
            for (0..count) |index| {
                const member_name = try name(strings, try word(members, index * 12, endian));
                if (std.mem.eql(u8, member_name, "stack_vm_area")) mapped = true;
            }
            if (result) |previous| if (previous != mapped) return error.InvalidBtf;
            result = mapped;
        }
        offset += 12 + payload;
    }
    return result orelse error.MissingTaskType;
}

pub fn runningKernelStackMapping(io: std.Io, allocator: std.mem.Allocator) !bool {
    const file = try std.Io.Dir.cwd().openFile(io, "/sys/kernel/btf/vmlinux", .{});
    defer file.close(io);
    // Do not trust sysfs st_size. Positional reads return zero at EOF, avoiding
    // a streaming EndOfStream error crossing the borrowed Io vtable's DSO ABI.
    var buffer: [4096]u8 = undefined;
    var reader = file.reader(io, &buffer);
    const data = reader.interface.allocRemaining(allocator, .limited(64 * 1024 * 1024)) catch |err|
        return reader.err orelse err;
    defer allocator.free(data);
    return stackMapping(data);
}

fn fixture(endian: std.builtin.Endian, mapped: bool) [90]u8 {
    var data: [90]u8 = @splat(0);
    std.mem.writeInt(u16, data[0..2], 0xeb9f, endian);
    data[2] = 1;
    const type_len: u32 = 24;
    std.mem.writeInt(u32, data[4..8], 24, endian);
    std.mem.writeInt(u32, data[12..16], type_len, endian);
    std.mem.writeInt(u32, data[16..20], type_len, endian);
    std.mem.writeInt(u32, data[20..24], 42, endian);
    // One structure with one member; both field names are in the string table.
    std.mem.writeInt(u32, data[24..28], 1, endian);
    std.mem.writeInt(u32, data[28..32], (4 << 24) | 1, endian);
    std.mem.writeInt(u32, data[32..36], 8, endian);
    std.mem.writeInt(u32, data[36..40], if (mapped) 13 else 27, endian);
    @memcpy(data[48..][0..33], "\x00task_struct\x00stack_vm_area\x00stack\x00");
    return data;
}

test "kernel stack metadata handles both byte orders and disabled configuration" {
    for ([_]std.builtin.Endian{ .little, .big }) |endian| {
        const enabled = fixture(endian, true);
        const disabled = fixture(endian, false);
        try std.testing.expect(try stackMapping(&enabled));
        try std.testing.expect(!try stackMapping(&disabled));
    }
}

test "truncated or unknown metadata never becomes a disabled capability" {
    var data = fixture(.little, true);
    for (0..data.len) |length| try std.testing.expectError(error.InvalidBtf, stackMapping(data[0..length]));
    std.mem.writeInt(u32, data[28..32], 31 << 24, .little);
    try std.testing.expectError(error.InvalidBtf, stackMapping(&data));
    data = fixture(.little, true);
    std.mem.writeInt(u32, data[36..40], 1000, .little);
    try std.testing.expectError(error.InvalidBtf, stackMapping(&data));
    data = fixture(.little, true);
    std.mem.writeInt(u32, data[32..36], 0, .little);
    try std.testing.expectError(error.InvalidBtf, stackMapping(&data));
    data = fixture(.little, true);
    std.mem.writeInt(u32, data[24..28], 27, .little);
    try std.testing.expectError(error.MissingTaskType, stackMapping(&data));
}

test "running kernel exposes a complete task type" {
    _ = runningKernelStackMapping(std.testing.io, std.testing.allocator) catch |err| switch (err) {
        error.FileNotFound, error.AccessDenied => return error.SkipZigTest,
        else => return err,
    };
}
