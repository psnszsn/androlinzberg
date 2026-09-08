//! Immutable wl_shm cursor frames. The compositor owns its buffer references;
//! replacing an icon never reuses storage that it may still be reading.
const std = @import("std");
const wl = @import("wayland").client.wl;
const Self = @This();

surface: *wl.Surface,
buffers: [128]*wl.Buffer = undefined,
count: usize = 0,
width: i32,
height: i32,
hotspot_x: i32,
hotspot_y: i32,
frame_ms: i32,
index: usize = 0,
next_ms: i64 = 0,

pub fn init(io: std.Io, compositor: *wl.Compositor, shm: *wl.Shm, width: i32, height: i32, hotspot_x: i32, hotspot_y: i32, frame_ms: i32, pixels: []const i32) !Self {
    if (width < 1 or width > 256 or height < 1 or height > 256 or
        hotspot_x < 0 or hotspot_x > width or hotspot_y < 0 or hotspot_y > height or
        pixels.len == 0 or pixels.len > 128 * 1024) return error.InvalidCursorImage;
    const frame_pixels: usize = @intCast(width * height);
    const count = pixels.len / frame_pixels;
    if (pixels.len % frame_pixels != 0 or count == 0 or count > 128 or
        (count > 1 and (frame_ms < 1 or frame_ms > 60_000))) return error.InvalidCursorImage;
    const raw = std.os.linux.memfd_create("azk-cursor", std.os.linux.MFD.CLOEXEC);
    if (std.os.linux.errno(raw) != .SUCCESS) return error.CursorStorageFailed;
    const file: std.Io.File = .{ .handle = @intCast(raw), .flags = .{ .nonblocking = false } };
    defer file.close(io);
    try file.writeStreamingAll(io, std.mem.sliceAsBytes(pixels));
    const pool = try shm.createPool(file.handle, @intCast(pixels.len * 4));
    defer pool.destroy();
    const surface = try compositor.createSurface();
    surface.setListener(*wl.Surface, surfaceListener, surface);
    var self: Self = .{ .surface = surface, .width = width, .height = height, .hotspot_x = hotspot_x, .hotspot_y = hotspot_y, .frame_ms = frame_ms };
    errdefer self.deinit();
    for (0..count) |index| {
        self.buffers[index] = try pool.createBuffer(@intCast(index * frame_pixels * 4), width, height, width * 4, .argb8888);
        self.count += 1;
    }
    return self;
}

fn surfaceListener(_: *wl.Surface, _: wl.Surface.Event, _: *wl.Surface) void {
    // Cursor bitmaps are expressed in logical surface coordinates. Core scale
    // and transform preferences are optional quality hints, not new geometry.
}

pub fn deinit(self: *Self) void {
    self.surface.destroy();
    for (self.buffers[0..self.count]) |buffer| buffer.destroy();
}

pub fn apply(self: *Self, io: std.Io, pointer: *wl.Pointer, serial: u32) void {
    self.index = 0;
    self.next_ms = std.Io.Clock.awake.now(io).toMilliseconds() + self.frame_ms;
    pointer.setCursor(serial, self.surface, self.hotspot_x, self.hotspot_y);
    self.commit();
}

fn commit(self: *Self) void {
    self.surface.attach(self.buffers[self.index], 0, 0);
    self.surface.damageBuffer(0, 0, self.width, self.height);
    self.surface.commit();
}

pub fn tick(self: *Self, io: std.Io) void {
    if (self.count <= 1) return;
    const now = std.Io.Clock.awake.now(io).toMilliseconds();
    if (now < self.next_ms) return;
    const steps: usize = @intCast(1 + @divTrunc(now - self.next_ms, self.frame_ms));
    self.index = (self.index + steps % self.count) % self.count;
    self.next_ms += @as(i64, @intCast(steps)) * self.frame_ms;
    self.commit();
}

pub fn timeout(self: *const Self, io: std.Io) i32 {
    if (self.count <= 1) return -1;
    return @intCast(@max(0, self.next_ms - std.Io.Clock.awake.now(io).toMilliseconds()));
}
