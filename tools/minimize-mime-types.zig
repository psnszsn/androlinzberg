//! Soong's mime.types minimization (frameworks/base/mime/Android.bp and
//! external/mime-support/Android.bp), with vendor non-overriding prefixes.
const std = @import("std");
const Io = std.Io;

fn minimize(allocator: std.mem.Allocator, input: []const u8, vendor: bool) ![]u8 {
    var output: std.ArrayList(u8) = .empty;
    errdefer output.deinit(allocator);
    var lines = std.mem.splitScalar(u8, input, '\n');
    while (lines.next()) |line| {
        const content = line[0 .. std.mem.indexOfScalar(u8, line, '#') orelse line.len];
        var tokens = std.mem.tokenizeAny(u8, content, " \t\r\x0b\x0c");
        const mime = tokens.next() orelse continue;
        var token = tokens.next() orelse continue;
        if (vendor and mime[0] != '?') try output.append(allocator, '?');
        try output.appendSlice(allocator, mime);
        while (true) {
            try output.append(allocator, ' ');
            if (vendor and token[0] != '?') try output.append(allocator, '?');
            try output.appendSlice(allocator, token);
            token = tokens.next() orelse break;
        }
        try output.append(allocator, '\n');
    }
    return output.toOwnedSlice(allocator);
}

pub fn main(init: std.process.Init) !void {
    const allocator = init.arena.allocator();
    const args = try init.minimal.args.toSlice(allocator);
    if (args.len != 4 or (!std.mem.eql(u8, args[3], "platform") and !std.mem.eql(u8, args[3], "vendor"))) {
        try Io.File.stderr().writeStreamingAll(init.io, "usage: minimize-mime-types <input> <output> <platform|vendor>\n");
        return error.InvalidArguments;
    }
    const input = try Io.Dir.cwd().readFileAlloc(init.io, args[1], allocator, .limited(16 * 1024 * 1024));
    const minimized = try minimize(allocator, input, std.mem.eql(u8, args[3], "vendor"));
    const output = try Io.Dir.cwd().createFile(init.io, args[2], .{});
    defer output.close(init.io);
    try output.writeStreamingAll(init.io, minimized);
}

test "Soong normalization drops comments and entries without extensions" {
    const output = try minimize(std.testing.allocator, "# comment\n application/octet-stream\n text/plain\t txt  text # aliases\n\n?image/jpeg ?jpg jpeg\r\n", false);
    defer std.testing.allocator.free(output);
    try std.testing.expectEqualStrings("text/plain txt text\n?image/jpeg ?jpg jpeg\n", output);
}

test "vendor declarations retain existing defaults in both mapping directions" {
    const output = try minimize(std.testing.allocator, "text/plain txt ?text\n?image/jpeg ?jpg jpeg\n", true);
    defer std.testing.allocator.free(output);
    try std.testing.expectEqualStrings("?text/plain ?txt ?text\n?image/jpeg ?jpg ?jpeg\n", output);
}
