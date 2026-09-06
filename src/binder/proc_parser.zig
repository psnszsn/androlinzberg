//! android_util_Process.cpp parseProcLineArray/strto* semantics. Linux /proc
//! remains a host seam; parsing stays shared by the two Process JNI entry points.
const std = @import("std");

pub const Field = struct { bytes: []u8, mode: i32 };
pub const Parser = struct {
    bytes: []u8,
    cursor: usize = 0,

    pub fn next(self: *Parser, original_mode: i32) ?Field {
        var mode = original_mode;
        if (mode & 0x200 != 0) {
            self.cursor += 1;
        } else if (mode & 0x400 != 0) {
            if (self.cursor < self.bytes.len and self.bytes[self.cursor] == '"') {
                self.cursor += 1;
            } else mode &= ~@as(i32, 0x400);
        }
        const start = self.cursor;
        if (start >= self.bytes.len) return null;
        var end: ?usize = null;
        const closing: ?u8 = if (mode & 0x200 != 0) ')' else if (mode & 0x400 != 0) '"' else null;
        if (closing) |c| {
            while (self.cursor < self.bytes.len and self.bytes[self.cursor] != c) self.cursor += 1;
            end = self.cursor;
            self.cursor += 1;
        }
        const term: u8 = @intCast(mode & 0xff);
        while (self.cursor < self.bytes.len and self.bytes[self.cursor] != term) self.cursor += 1;
        if (end == null) end = self.cursor;
        if (self.cursor < self.bytes.len) {
            self.cursor += 1;
            if (mode & 0x100 != 0) {
                while (self.cursor < self.bytes.len and self.bytes[self.cursor] == term) self.cursor += 1;
            }
        }
        return .{ .bytes = self.bytes[start..end.?], .mode = mode };
    }
};

pub fn long(bytes: []const u8) i64 {
    const s = std.mem.trimStart(u8, bytes, " \t\n\r\x0b\x0c");
    var i: usize = 0;
    const negative = s.len > 0 and s[0] == '-';
    if (s.len > 0 and (s[0] == '+' or negative)) i += 1;
    const limit: u64 = if (negative) @as(u64, 1) << 63 else std.math.maxInt(i64);
    var value: u64 = 0;
    while (i < s.len and std.ascii.isDigit(s[i])) : (i += 1) {
        const digit: u64 = s[i] - '0';
        if (value > (limit - digit) / 10) return if (negative) std.math.minInt(i64) else std.math.maxInt(i64);
        value = value * 10 + digit;
    }
    if (negative and value == limit) return std.math.minInt(i64);
    const signed: i64 = @intCast(value);
    return if (negative) -signed else signed;
}

pub fn float(bytes: []const u8) f32 {
    const s = std.mem.trimStart(u8, bytes, " \t\n\r\x0b\x0c");
    var i: usize = 0;
    const negative = s.len > 0 and s[0] == '-';
    if (s.len > 0 and (s[0] == '+' or negative)) i += 1;
    if (std.ascii.startsWithIgnoreCase(s[i..], "inf")) return if (negative) -std.math.inf(f32) else std.math.inf(f32);
    if (std.ascii.startsWithIgnoreCase(s[i..], "nan")) return if (negative) -std.math.nan(f32) else std.math.nan(f32);
    var hex = false;
    if (i + 2 < s.len and s[i] == '0' and (s[i + 1] == 'x' or s[i + 1] == 'X')) {
        const first = i + 2;
        if (std.ascii.isHex(s[first]) or (s[first] == '.' and first + 1 < s.len and std.ascii.isHex(s[first + 1]))) {
            hex = true;
            i += 2;
        }
    }
    var digits: usize = 0;
    var dot = false;
    while (i < s.len) : (i += 1) {
        if (if (hex) std.ascii.isHex(s[i]) else std.ascii.isDigit(s[i])) {
            digits += 1;
        } else if (s[i] == '.' and !dot) {
            dot = true;
        } else break;
    }
    if (digits == 0) return 0;
    if (i < s.len and (if (hex) s[i] == 'p' or s[i] == 'P' else s[i] == 'e' or s[i] == 'E')) {
        var exponent = i + 1;
        if (exponent < s.len and (s[exponent] == '+' or s[exponent] == '-')) exponent += 1;
        const first = exponent;
        while (exponent < s.len and std.ascii.isDigit(s[exponent])) exponent += 1;
        if (exponent > first) i = exponent;
    }
    return std.fmt.parseFloat(f32, s[0..i]) catch 0;
}

test "C numeric prefixes and saturation" {
    try std.testing.expectEqual(@as(i64, -17), long(" \t-17ms"));
    try std.testing.expectEqual(std.math.minInt(i64), long("-9223372036854775809"));
    try std.testing.expectEqual(std.math.maxInt(i64), long("9223372036854775808"));
    try std.testing.expectEqual(@as(f32, 1.25), float(" 1.25e+oops"));
    try std.testing.expectEqual(@as(f32, 3), float("0x1.8p+1junk"));
    try std.testing.expectEqual(@as(f32, 1.5), float("0x1.8junk"));
    try std.testing.expectEqual(@as(f32, 0), float("invalid"));
    try std.testing.expect(std.math.isInf(float("-INFINITY")));
    try std.testing.expect(std.math.isNan(float("NaN(payload)")));
}
