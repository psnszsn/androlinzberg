//! Exercise the original NDK format C ABI loaded from the native platform library.
const std = @import("std");
const Format = opaque {};
const Name = ?[*:0]const u8;

fn load(comptime name: [:0]const u8, comptime T: type, library: *std.DynLib) !*const T {
    return library.lookup(*const T, name) orelse error.MissingMediaFormatSymbol;
}

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len != 2) return error.InvalidArguments;
    var library = try std.DynLib.open(args[1]);
    defer library.close();
    const create = try load("AMediaFormat_new", fn () callconv(.c) ?*Format, &library);
    const destroy = try load("AMediaFormat_delete", fn (?*Format) callconv(.c) i32, &library);
    const copy = try load("AMediaFormat_copy", fn (?*Format, ?*Format) callconv(.c) i32, &library);
    const clear = try load("AMediaFormat_clear", fn (*Format) callconv(.c) void, &library);
    const describe = try load("AMediaFormat_toString", fn (*Format) callconv(.c) [*:0]const u8, &library);
    const set_string = try load("AMediaFormat_setString", fn (?*Format, Name, Name) callconv(.c) void, &library);
    const get_string = try load("AMediaFormat_getString", fn (?*Format, Name, *?[*:0]const u8) callconv(.c) bool, &library);
    const set_buffer = try load("AMediaFormat_setBuffer", fn (?*Format, Name, ?*const anyopaque, usize) callconv(.c) void, &library);
    const get_buffer = try load("AMediaFormat_getBuffer", fn (?*Format, Name, *?*anyopaque, *usize) callconv(.c) bool, &library);
    const format = create() orelse return error.OutOfMemory;
    defer _ = destroy(format);
    const duplicate = create() orelse return error.OutOfMemory;
    defer _ = destroy(duplicate);

    inline for (.{ .{ "Int32", i32, -37 }, .{ "Int64", i64, 1 << 40 }, .{ "Size", usize, 12345 }, .{ "Float", f32, 3.25 }, .{ "Double", f64, 9.5 } }) |scalar| {
        const T = scalar[1];
        const setter = try load("AMediaFormat_set" ++ scalar[0], fn (?*Format, Name, T) callconv(.c) void, &library);
        const getter = try load("AMediaFormat_get" ++ scalar[0], fn (?*Format, Name, *T) callconv(.c) bool, &library);
        var result: T = 17;
        try std.testing.expect(!getter(format, "missing", &result));
        try std.testing.expectEqual(@as(T, 17), result);
        setter(format, scalar[0], scalar[2]);
        try std.testing.expect(getter(format, scalar[0], &result));
        try std.testing.expectEqual(@as(T, scalar[2]), result);
        try std.testing.expect(!getter(null, scalar[0], &result));
        try std.testing.expect(!getter(format, null, &result));
        setter(null, scalar[0], scalar[2]);
        setter(format, null, scalar[2]);
    }
    var mime = [_:0]u8{ 'v', 'i', 'd', 'e', 'o', '/', 'a', 'v', 'c' };
    set_string(format, "mime", &mime);
    mime[0] = 'x';
    var text: ?[*:0]const u8 = null;
    try std.testing.expect(get_string(format, "mime", &text));
    try std.testing.expectEqualStrings("video/avc", std.mem.span(text.?));
    try std.testing.expect(!get_string(format, "Int32", &text));
    set_string(format, "mime", null); // The stock setter ignores null values.
    try std.testing.expect(get_string(format, "mime", &text));
    try std.testing.expectEqualStrings("video/avc", std.mem.span(text.?));

    var bytes = [_]u8{ 1, 2, 3, 4 };
    set_buffer(format, "csd-0", &bytes, bytes.len);
    bytes[0] = 99;
    var data: ?*anyopaque = null;
    var size: usize = 0;
    try std.testing.expect(get_buffer(format, "csd-0", &data, &size));
    try std.testing.expectEqualSlices(u8, &.{ 1, 2, 3, 4 }, @as([*]const u8, @ptrCast(data.?))[0..size]);
    const set_rect = try load("AMediaFormat_setRect", fn (?*Format, Name, i32, i32, i32, i32) callconv(.c) void, &library);
    const get_rect = try load("AMediaFormat_getRect", fn (?*Format, Name, *i32, *i32, *i32, *i32) callconv(.c) bool, &library);
    set_rect(format, "crop", -1, 2, 30, 40);
    var rect: [4]i32 = undefined;
    try std.testing.expect(get_rect(format, "crop", &rect[0], &rect[1], &rect[2], &rect[3]));
    try std.testing.expectEqualSlices(i32, &.{ -1, 2, 30, 40 }, &rect);
    try std.testing.expect(std.mem.indexOf(u8, std.mem.span(describe(format)), "video/avc") != null);
    const mime_key = library.lookup(*const [*:0]const u8, "AMEDIAFORMAT_KEY_MIME") orelse return error.MissingMediaFormatSymbol;
    try std.testing.expectEqualStrings("mime", std.mem.span(mime_key.*));

    set_string(duplicate, "old", "discarded");
    try std.testing.expectEqual(@as(i32, 0), copy(duplicate, format));
    try std.testing.expect(!get_string(duplicate, "old", &text));
    clear(format);
    try std.testing.expect(!get_string(format, "mime", &text));
    try std.testing.expect(get_string(duplicate, "mime", &text));
    try std.testing.expectEqualStrings("video/avc", std.mem.span(text.?));
    try std.testing.expect(get_buffer(duplicate, "csd-0", &data, &size));
    try std.testing.expectEqualSlices(u8, &.{ 1, 2, 3, 4 }, @as([*]const u8, @ptrCast(data.?))[0..size]);
    try std.testing.expectEqual(@as(i32, -10004), copy(null, duplicate));
    try std.testing.expectEqual(@as(i32, -10004), copy(duplicate, null));
    try std.testing.expectEqual(@as(i32, 0), destroy(null));
    try std.Io.File.stdout().writeStreamingAll(init.io, "stock-media-format: PASS typed values, owned buffers, copy, clear and C ABI\n");
}
