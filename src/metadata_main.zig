const std = @import("std");
pub fn main(init: std.process.Init) !void {
    return @import("process_entry").main(.metadata, init);
}
