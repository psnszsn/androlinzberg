//! Placement supplied by the headless harness, never inferred from Android's
//! synthetic desktop. Core Wayland does not expose global toplevel positions.
const std = @import("std");

pub const Placement = struct {
    left: i32,
    top: i32,
    output_width: i32,
    output_height: i32,

    pub fn parse(value: []const u8) !Placement {
        var fields = std.mem.splitScalar(u8, value, ',');
        var values: [4]i32 = undefined;
        for (&values) |*number| number.* = try std.fmt.parseInt(i32, fields.next() orelse return error.InvalidPlacement, 10);
        if (fields.next() != null or values[0] < 0 or values[1] < 0 or
            values[2] <= 0 or values[3] <= 0 or values[2] > 32768 or values[3] > 32768 or
            values[0] >= values[2] or values[1] >= values[3]) return error.InvalidPlacement;
        return .{ .left = values[0], .top = values[1], .output_width = values[2], .output_height = values[3] };
    }

    /// Task bounds are in Android units; the window size and harness placement
    /// are in compositor logical units. This matches the task output projection.
    pub fn point(self: Placement, bounds: [4]i32, size: [2]i32, android_x: i32, android_y: i32) ![2]u32 {
        if (bounds[2] <= 0 or bounds[3] <= 0 or size[0] <= 0 or size[1] <= 0) return error.InvalidGeometry;
        const x = @as(i64, android_x) - bounds[0];
        const y = @as(i64, android_y) - bounds[1];
        if (x < 0 or y < 0 or x >= bounds[2] or y >= bounds[3]) return error.OutsideTask;
        const desktop_x = self.left + @divFloor(x * size[0], bounds[2]);
        const desktop_y = self.top + @divFloor(y * size[1], bounds[3]);
        if (desktop_x < 0 or desktop_y < 0 or desktop_x >= self.output_width or desktop_y >= self.output_height)
            return error.OutsideOutput;
        return .{ @intCast(desktop_x), @intCast(desktop_y) };
    }
};

test "compositor placement is independent of synthetic task origins" {
    const placement = try Placement.parse("500,100,1280,720");
    try std.testing.expectEqual([2]u32{ 720, 250 }, try placement.point(.{ 8000, 4000, 880, 600 }, .{ 440, 300 }, 8440, 4300));
    try std.testing.expectEqual([2]u32{ 939, 399 }, try placement.point(.{ 8000, 4000, 880, 600 }, .{ 440, 300 }, 8879, 4599));
    try std.testing.expectError(error.OutsideTask, placement.point(.{ 8000, 4000, 880, 600 }, .{ 440, 300 }, 100, 100));
    try std.testing.expectError(error.OutsideOutput, placement.point(.{ 0, 0, 1000, 1000 }, .{ 1000, 1000 }, 999, 999));
    try std.testing.expectError(error.InvalidPlacement, Placement.parse("0,0,0,720"));
    try std.testing.expectError(error.InvalidPlacement, Placement.parse("0,0,1280,720,1"));
}
