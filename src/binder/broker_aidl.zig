//! Zig AIDL runtime over raw AZKB parcel frames (aidl_core.zig's second
//! transport). The broker serves `android.os.IServiceManager` at the reserved
//! context object without being a libbinder_ndk client itself: a transaction
//! reaches it as parcel bytes plus an object side table, and its Binder values
//! are per-peer object references, not `AIBinder` handles.
//!
//! Generated code built for the broker imports this file as `aidl`, so the
//! broker decodes and answers with the same generated tables every other
//! backend uses. It only serves: `call`, `Server` and `associate` have no
//! meaning on a frame.
//!
//! Layout follows the pinned libbinder Parcel, as src/binder/ndk.zig does:
//! little-endian, 4-byte aligned, UTF-16 strings, and a strong Binder as an
//! int32 presence word whose offset names a side-table entry.
const std = @import("std");
const core = @import("aidl_core.zig");
const runtime = core.Runtime(@This());
pub const Allocator = std.mem.Allocator;
pub const Exception = core.Exception;
pub const Error = core.Error;
pub const Status = core.Status;
pub const codec = runtime.codec;
pub const defaultValue = runtime.defaultValue;
pub const ReplyOf = runtime.ReplyOf;
pub const encodeArgs = runtime.encodeArgs;
pub const decodeArgs = runtime.decodeArgs;
pub const encodeReply = runtime.encodeReply;
pub const releaseArgs = runtime.releaseArgs;
pub const exceptionOf = core.exceptionOf;

/// 'SYST': the libbinder interface-token header marker.
const interface_header: i32 = 0x53595354;

/// An object reference as one peer expresses it: `local` is that peer's own
/// object id, `remote` a broker global id. The broker resolves and delivers.
pub const Binder = struct {
    kind: enum { local, remote },
    value: u64,

    pub fn deinit(_: Binder) void {}
};

/// The broker forwards descriptors; its own interface carries none.
pub const File = struct {
    pub fn close(_: File, _: std.Io) void {}
};

pub const ObjectEntry = struct { offset: u32, binder: Binder };

/// One parcel: either the bytes of a received transaction, or a buffer being
/// written. `gpa` owns `data` and `entries` of a written frame.
pub const Frame = struct {
    gpa: Allocator,
    data: std.ArrayList(u8) = .empty,
    entries: std.ArrayList(ObjectEntry) = .empty,
    /// Received bytes and side table; null while writing.
    input: ?[]const u8 = null,
    input_entries: []const ObjectEntry = &.{},
    cursor: usize = 0,

    pub fn reading(gpa: Allocator, bytes: []const u8, entries: []const ObjectEntry) Frame {
        return .{ .gpa = gpa, .input = bytes, .input_entries = entries };
    }
    pub fn deinit(self: *Frame) void {
        self.data.deinit(self.gpa);
        self.entries.deinit(self.gpa);
    }
    pub fn parcel(self: *Frame) Parcel {
        return .{ .frame = self };
    }
};

pub const Parcel = struct {
    frame: *Frame,

    pub fn position(self: Parcel) i32 {
        return @intCast(self.frame.cursor);
    }
    pub fn setPosition(self: Parcel, value: i32) Error!void {
        const limit = if (self.frame.input) |bytes| bytes.len else self.frame.data.items.len;
        if (value < 0 or @as(usize, @intCast(value)) > limit) return error.BadParcelable;
        self.frame.cursor = @intCast(value);
    }
    pub fn expectEnd(self: Parcel) Error!void {
        if (self.frame.cursor != (self.frame.input orelse return error.BadParcelable).len) return error.BadParcelable;
    }

    /// Overwrites at the cursor (a parcelable's size is patched in place) or
    /// appends at the end.
    fn put(self: Parcel, bytes: []const u8) Error!void {
        const frame = self.frame;
        if (frame.cursor == frame.data.items.len) {
            try frame.data.appendSlice(frame.gpa, bytes);
        } else {
            if (frame.cursor + bytes.len > frame.data.items.len) return error.BadParcelable;
            @memcpy(frame.data.items[frame.cursor..][0..bytes.len], bytes);
        }
        frame.cursor += bytes.len;
    }
    fn take(self: Parcel, count: usize) Error![]const u8 {
        const bytes = self.frame.input orelse return error.BadParcelable;
        if (count > bytes.len - @min(self.frame.cursor, bytes.len)) return error.BadParcelable;
        defer self.frame.cursor += count;
        return bytes[self.frame.cursor..][0..count];
    }
    fn pad(self: Parcel) Error!void {
        const zeros: [4]u8 = @splat(0);
        try self.put(zeros[0 .. std.mem.alignForward(usize, self.frame.cursor, 4) - self.frame.cursor]);
    }
    fn skipPad(self: Parcel) Error!void {
        _ = try self.take(std.mem.alignForward(usize, self.frame.cursor, 4) - self.frame.cursor);
    }
    fn putInt(self: Parcel, comptime T: type, value: T) Error!void {
        var bytes: [@sizeOf(T)]u8 = undefined;
        std.mem.writeInt(T, &bytes, value, .little);
        return self.put(&bytes);
    }
    fn takeInt(self: Parcel, comptime T: type) Error!T {
        return std.mem.readInt(T, (try self.take(@sizeOf(T)))[0..@sizeOf(T)], .little);
    }

    pub fn writeInt32(self: Parcel, value: i32) Error!void {
        return self.putInt(i32, value);
    }
    pub fn readInt32(self: Parcel) Error!i32 {
        return self.takeInt(i32);
    }
    pub fn writeInt64(self: Parcel, value: i64) Error!void {
        return self.putInt(i64, value);
    }
    pub fn readInt64(self: Parcel) Error!i64 {
        return self.takeInt(i64);
    }
    pub fn writeBool(self: Parcel, value: bool) Error!void {
        return self.writeInt32(@intFromBool(value));
    }
    pub fn readBool(self: Parcel) Error!bool {
        return try self.readInt32() != 0;
    }
    pub fn writeByte(self: Parcel, value: i8) Error!void {
        return self.writeInt32(value);
    }
    pub fn readByte(self: Parcel) Error!i8 {
        return @truncate(try self.readInt32());
    }
    pub fn writeChar(self: Parcel, value: u16) Error!void {
        return self.writeInt32(value);
    }
    pub fn readChar(self: Parcel) Error!u16 {
        return @truncate(@as(u32, @bitCast(try self.readInt32())));
    }
    pub fn writeFloat(self: Parcel, value: f32) Error!void {
        return self.putInt(u32, @bitCast(value));
    }
    pub fn readFloat(self: Parcel) Error!f32 {
        return @bitCast(try self.takeInt(u32));
    }
    pub fn writeDouble(self: Parcel, value: f64) Error!void {
        return self.putInt(u64, @bitCast(value));
    }
    pub fn readDouble(self: Parcel) Error!f64 {
        return @bitCast(try self.takeInt(u64));
    }

    /// writeString16: a code-unit count, the UTF-16LE units and a NUL unit,
    /// padded to four bytes; -1 is null. Malformed UTF-8 becomes U+FFFD.
    pub fn writeString(self: Parcel, value: ?[]const u8) Error!void {
        const text = value orelse return self.writeInt32(-1);
        const count_at = self.frame.cursor;
        try self.writeInt32(0);
        var units: i32 = 0;
        var index: usize = 0;
        while (index < text.len) {
            const length = std.unicode.utf8ByteSequenceLength(text[index]) catch 1;
            const code_point: u21 = if (index + length <= text.len)
                std.unicode.utf8Decode(text[index..][0..length]) catch 0xFFFD
            else
                0xFFFD;
            index += length;
            if (code_point <= 0xFFFF) {
                try self.putInt(u16, @intCast(code_point));
                units += 1;
            } else {
                const supplementary = code_point - 0x10000;
                try self.putInt(u16, @intCast(0xD800 + (supplementary >> 10)));
                try self.putInt(u16, @intCast(0xDC00 + (supplementary & 0x3FF)));
                units += 2;
            }
        }
        try self.putInt(u16, 0);
        try self.pad();
        const end = self.frame.cursor;
        self.frame.cursor = count_at;
        try self.writeInt32(units);
        self.frame.cursor = end;
    }
    pub fn readString(self: Parcel, allocator: Allocator) Error!?[]const u8 {
        const count = try self.readInt32();
        if (count < 0) return null;
        var text: std.ArrayList(u8) = .empty;
        errdefer text.deinit(allocator);
        var index: i32 = 0;
        while (index < count) : (index += 1) {
            const unit = try self.takeInt(u16);
            var code_point: u21 = unit;
            if (unit >= 0xD800 and unit <= 0xDBFF and index + 1 < count) {
                const low = try self.takeInt(u16);
                index += 1;
                code_point = if (low >= 0xDC00 and low <= 0xDFFF)
                    0x10000 + ((@as(u21, unit) - 0xD800) << 10) + (@as(u21, low) - 0xDC00)
                else
                    0xFFFD;
            } else if (unit >= 0xD800 and unit <= 0xDFFF) code_point = 0xFFFD;
            var encoded: [4]u8 = undefined;
            const length = std.unicode.utf8Encode(code_point, &encoded) catch unreachable;
            try text.appendSlice(allocator, encoded[0..length]);
        }
        _ = try self.takeInt(u16);
        try self.skipPad();
        return try text.toOwnedSlice(allocator);
    }

    pub fn writeByteArray(self: Parcel, value: ?[]const u8) Error!void {
        const bytes = value orelse return self.writeInt32(-1);
        if (bytes.len > std.math.maxInt(i32)) return error.BadParcelable;
        try self.writeInt32(@intCast(bytes.len));
        try self.put(bytes);
        return self.pad();
    }
    pub fn readByteArray(self: Parcel, allocator: Allocator) Error!?[]const u8 {
        const count = try self.readInt32();
        if (count < 0) return null;
        const bytes = try allocator.dupe(u8, try self.take(@intCast(count)));
        errdefer allocator.free(bytes);
        try self.skipPad();
        return bytes;
    }

    pub fn writeBinder(self: Parcel, value: ?Binder) Error!void {
        const binder = value orelse return self.writeInt32(0);
        try self.frame.entries.append(self.frame.gpa, .{ .offset = @intCast(self.frame.cursor), .binder = binder });
        return self.writeInt32(1);
    }
    pub fn readBinder(self: Parcel) Error!?Binder {
        const offset = self.frame.cursor;
        _ = try self.readInt32();
        for (self.frame.input_entries) |entry| if (entry.offset == offset) return entry.binder;
        return null;
    }

    pub fn writeFile(_: Parcel, _: ?File) Error!void {
        return error.UnsupportedOperation;
    }
    pub fn readFile(_: Parcel) Error!?File {
        return error.UnsupportedOperation;
    }

    /// libbinder Status::writeToParcel.
    pub fn writeStatus(self: Parcel, status: *const Status) Error!void {
        try self.writeInt32(@backingInt(status.exception));
        if (status.exception == .none) return;
        try self.writeString(status.message());
        try self.writeInt32(0); // empty remote stack trace header
        if (status.exception == .service_specific) try self.writeInt32(status.service_specific);
        if (status.exception == .parcelable) try self.writeInt32(0); // no Parcelable blob
    }
    pub fn readStatus(_: Parcel) Error!Status {
        return error.UnsupportedOperation;
    }

    /// Consume the interface token and report whether it names `descriptor`.
    pub fn enforceInterface(self: Parcel, allocator: Allocator, descriptor: []const u8) bool {
        _ = self.readInt32() catch return false; // strict-mode policy
        _ = self.readInt32() catch return false; // work source
        if ((self.readInt32() catch return false) != interface_header) return false;
        const named = (self.readString(allocator) catch return false) orelse return false;
        defer allocator.free(named);
        return std.mem.eql(u8, named, descriptor);
    }
    /// The token a proxy writes before its arguments.
    pub fn writeInterfaceToken(self: Parcel, descriptor: []const u8) Error!void {
        try self.writeInt32(0); // strict-mode policy
        try self.writeInt32(-1); // work source
        try self.writeInt32(interface_header);
        return self.writeString(descriptor);
    }
};

/// A reply parcel holding only the exception `status` describes.
pub fn encodeException(parcel: Parcel, exception: Exception, message: []const u8) Error!void {
    var status: Status = .{ .exception = exception };
    status.setMessage(message);
    return parcel.writeStatus(&status);
}

/// Generated `Client.fromBinder` is never used on a frame.
pub fn associate(comptime _: type, _: Binder) Error!void {}

test "frame parcels follow the libbinder layout" {
    const gpa = std.testing.allocator;
    var written: Frame = .{ .gpa = gpa };
    defer written.deinit();
    const out = written.parcel();
    try out.writeInterfaceToken("a.I");
    try out.writeString("hé😀");
    try out.writeString(null);
    try out.writeBinder(.{ .kind = .remote, .value = 7 });
    try out.writeBinder(null);
    try out.writeByteArray(&.{ 1, 2, 3 });
    try out.writeInt64(-5);
    try std.testing.expectEqual(@as(usize, 0), written.data.items.len % 4);
    try std.testing.expectEqual(@as(usize, 1), written.entries.items.len);

    var received = Frame.reading(gpa, written.data.items, written.entries.items);
    const in = received.parcel();
    try std.testing.expect(in.enforceInterface(gpa, "a.I"));
    const text = (try in.readString(gpa)).?;
    defer gpa.free(text);
    try std.testing.expectEqualStrings("hé😀", text);
    try std.testing.expectEqual(@as(?[]const u8, null), try in.readString(gpa));
    try std.testing.expectEqual(@as(u64, 7), (try in.readBinder()).?.value);
    try std.testing.expectEqual(@as(?Binder, null), try in.readBinder());
    const bytes = (try in.readByteArray(gpa)).?;
    defer gpa.free(bytes);
    try std.testing.expectEqualSlices(u8, &.{ 1, 2, 3 }, bytes);
    try std.testing.expectEqual(@as(i64, -5), try in.readInt64());
    try in.expectEnd();
}
