//! Remove generated @FlaggedApi annotations whose flag classes are not hosted.
const std = @import("std");
const Io = std.Io;

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len != 3) {
        try Io.File.stderr().writeStreamingAll(init.io, "usage: filter-flagged-api <input.java> <output.java>\n");
        return error.InvalidArguments;
    }

    const input = try Io.Dir.cwd().openFile(init.io, args[1], .{});
    defer input.close(init.io);
    var read_buffer: [4096]u8 = undefined;
    var reader = input.reader(init.io, &read_buffer);
    const source = try reader.interface.allocRemaining(init.gpa, .unlimited);
    defer init.gpa.free(source);

    const marker = "@android.annotation.FlaggedApi(";
    var filtered = std.ArrayList(u8).empty;
    defer filtered.deinit(init.gpa);
    var cursor: usize = 0;
    while (std.mem.indexOfPos(u8, source, cursor, marker)) |start| {
        try filtered.appendSlice(init.gpa, source[cursor..start]);
        const close = std.mem.indexOfScalarPos(u8, source, start + marker.len, ')') orelse
            return error.UnterminatedFlaggedApi;
        cursor = close + 1;
    }
    try filtered.appendSlice(init.gpa, source[cursor..]);

    const output = try Io.Dir.cwd().createFile(init.io, args[2], .{});
    defer output.close(init.io);
    try output.writeStreamingAll(init.io, filtered.items);
}
