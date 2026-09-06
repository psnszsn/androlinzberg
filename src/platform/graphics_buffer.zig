//! Linux graphics buffer backend for AOSP GrallocMapper/Allocator adapters.
//! The wire handle contains only owned DMA-BUF descriptors and validated metadata.
//! No process-local GBM pointer is transported. OS resource ownership uses std.Io.
const std = @import("std");
const Io = std.Io;
const alloc = std.heap.c_allocator;
const Device = opaque {};
const Bo = opaque {};
extern "c" fn gbm_create_device(c_int) ?*Device;
extern "c" fn gbm_device_is_format_supported(*Device, u32, u32) c_int;
extern "c" fn gbm_bo_create(*Device, u32, u32, u32, u32) ?*Bo;
extern "c" fn gbm_bo_create_with_modifiers2(*Device, u32, u32, u32, [*]const u64, u32, u32) ?*Bo;
extern "c" fn gbm_bo_import(*Device, u32, *anyopaque, u32) ?*Bo;
extern "c" fn gbm_bo_destroy(*Bo) void;
extern "c" fn gbm_bo_get_fd(*Bo) c_int;
extern "c" fn gbm_bo_get_stride(*Bo) u32;
extern "c" fn gbm_bo_get_offset(*Bo, c_int) u32;
extern "c" fn gbm_bo_get_modifier(*Bo) u64;
extern "c" fn gbm_bo_get_plane_count(*Bo) c_int;
extern "c" fn gbm_bo_map(*Bo, u32, u32, u32, u32, u32, *u32, *?*anyopaque) ?*anyopaque;
extern "c" fn gbm_bo_unmap(*Bo, ?*anyopaque) void;
extern "c" fn native_handle_create(c_int, c_int) ?*Handle;
extern "c" fn native_handle_delete(*Handle) c_int;
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;

const magic: u32 = 0x415a4742; // AZGB
const integer_count = 16;
pub const Handle = extern struct {
    version: c_int,
    num_fds: c_int,
    num_ints: c_int,
    fn words(h: *const Handle) *const [17]u32 {
        return @ptrFromInt(@intFromPtr(h) + @sizeOf(Handle));
    }
    fn writable(h: *Handle) *[17]u32 {
        return @constCast(h.words());
    }
};
pub const Info = @import("graphics_types.zig").Info;
const Buffer = struct { handle: *Handle, bo: *Bo, file: Io.File, info: Info, map_token: ?*anyopaque = null, mapped: bool = false };
const Imported = extern struct { width: u32, height: u32, format: u32, num_fds: u32, fds: [4]c_int, strides: [4]c_int, offsets: [4]c_int, modifier: u64 };
const Format = struct { drm: u32, bpp: u32 };
fn formatInfo(format: u32) ?Format {
    return switch (format) {
        1 => .{ .drm = 0x34324241, .bpp = 4 }, // ABGR8888
        2 => .{ .drm = 0x34324258, .bpp = 4 }, // XBGR8888
        4 => .{ .drm = 0x36314752, .bpp = 2 }, // RGB565
        5 => .{ .drm = 0x34325241, .bpp = 4 }, // ARGB8888
        0x16 => .{ .drm = 0x48344241, .bpp = 8 },
        0x2b => .{ .drm = 0x30334241, .bpp = 4 },
        else => null,
    };
}
fn valid(width: u32, height: u32, format: u32, layers: u32, usage: u64) bool {
    // First production contract: single-plane RGB UI buffers. Reject protected,
    // sensor/direct-data and vendor-specific usage instead of guessing a layout.
    const supported_usage: u64 = 0xff | (1 << 8) | (1 << 9) | (1 << 11) | (1 << 12) | (1 << 16) | (1 << 17);
    const cpu_read = usage & 0xf;
    const cpu_write = (usage >> 4) & 0xf;
    if ((cpu_read != 0 and cpu_read != 2 and cpu_read != 3) or
        (cpu_write != 0 and cpu_write != 2 and cpu_write != 3)) return false;
    return width > 0 and height > 0 and width <= 16384 and height <= 16384 and
        layers == 1 and formatInfo(format) != null and usage & ~supported_usage == 0;
}
fn gbmUsage(usage: u64) u32 {
    return @as(u32, 1 << 2) | (if (usage & 0xff != 0) @as(u32, 1 << 4) else 0);
}
var initialized: std.atomic.Value(u8) = .init(0);
var threaded: Io.Threaded = undefined;
var io: Io = undefined;
var mutex: Io.Mutex = .init;
var device: ?*Device = null;
var device_file: ?Io.File = null;
var buffers: std.ArrayList(Buffer) = .empty;
fn ensureIo() void {
    if (initialized.load(.acquire) == 2) return;
    if (initialized.cmpxchgStrong(0, 1, .acq_rel, .acquire) == null) {
        threaded = Io.Threaded.init(alloc, .{});
        io = threaded.io();
        initialized.store(2, .release);
    } else while (initialized.load(.acquire) != 2) std.atomic.spinLoopHint();
}
fn lock() void {
    ensureIo();
    mutex.lockUncancelable(io);
}
fn unlock() void {
    mutex.unlock(io);
}
fn getDevice() ?*Device {
    if (device) |d| return d;
    const path = for ([_][*:0]const u8{ "ARTZIG_DRM_DEVICE", "ARTZIG_DRM_RENDER_NODE", "ARTZIG_VAAPI_DEVICE" }) |name| {
        if (getenv(name)) |value| if (value[0] != 0) break std.mem.span(value);
    } else "/dev/dri/renderD128";
    const file = Io.Dir.cwd().openFile(io, path, .{ .mode = .read_write }) catch return null;
    const d = gbm_create_device(file.handle) orelse {
        file.close(io);
        return null;
    };
    device_file = file;
    device = d;
    return d;
}
fn lookup(handle: *const Handle) ?*Buffer {
    for (buffers.items) |*buffer| if (buffer.handle == handle) return buffer;
    return null;
}
fn encode(handle: *Handle, info: Info) void {
    handle.writable().* = .{ @bitCast(info.fd), magic, 1, info.width, info.height, info.format, info.layers, @truncate(info.usage), @truncate(info.usage >> 32), info.stride, info.offset, @truncate(info.modifier), @truncate(info.modifier >> 32), @truncate(info.id), @truncate(info.id >> 32), @bitCast(info.dataspace), 0 };
}
fn decode(handle: *const Handle) ?Info {
    if (handle.version != @sizeOf(Handle) or handle.num_fds != 1 or handle.num_ints != integer_count) return null;
    const w = handle.words();
    if (w[1] != magic or w[2] != 1 or w[16] != 0 or @as(i32, @bitCast(w[0])) < 0) return null;
    const usage = @as(u64, w[7]) | (@as(u64, w[8]) << 32);
    if (!valid(w[3], w[4], w[5], w[6], usage)) return null;
    const format = formatInfo(w[5]).?;
    if (w[9] % format.bpp != 0 or w[9] < w[3] * format.bpp or w[9] > std.math.maxInt(i32) or w[10] > std.math.maxInt(i32)) return null;
    return .{ .width = w[3], .height = w[4], .format = w[5], .layers = w[6], .usage = usage, .drm_format = format.drm, .stride = w[9], .offset = w[10], .modifier = @as(u64, w[11]) | (@as(u64, w[12]) << 32), .id = @as(u64, w[13]) | (@as(u64, w[14]) << 32), .fd = @bitCast(w[0]), .dataspace = @bitCast(w[15]) };
}
// Takes ownership of bo on success and failure; serialized by mutex.
fn register(bo: *Bo, info_in: Info, output: *?*Handle) i32 {
    var info = info_in;
    if (gbm_bo_get_plane_count(bo) != 1) {
        gbm_bo_destroy(bo);
        return -38;
    }
    const fd = gbm_bo_get_fd(bo);
    if (fd < 0) {
        gbm_bo_destroy(bo);
        return -12;
    }
    const file: Io.File = .{ .handle = fd, .flags = .{ .nonblocking = false } };
    const handle = native_handle_create(1, integer_count) orelse {
        file.close(io);
        gbm_bo_destroy(bo);
        return -12;
    };
    info.fd = fd;
    info.stride = gbm_bo_get_stride(bo);
    info.offset = gbm_bo_get_offset(bo, 0);
    info.modifier = gbm_bo_get_modifier(bo);
    encode(handle, info);
    buffers.append(alloc, .{ .handle = handle, .bo = bo, .file = file, .info = info }) catch {
        _ = native_handle_delete(handle);
        file.close(io);
        gbm_bo_destroy(bo);
        return -12;
    };
    output.* = handle;
    return 0;
}
pub export fn azk_graphics_allocate(width: u32, height: u32, format: u32, layers: u32, usage: u64, output: *?*Handle) callconv(.c) i32 {
    output.* = null;
    if (!valid(width, height, format, layers, usage)) return -22;
    lock();
    defer unlock();
    const d = getDevice() orelse return -19;
    const f = formatInfo(format).?;
    // Legacy GBM allocation can report INVALID (implicit layout) even with
    // USE_LINEAR. Request the explicit linear modifier for CPU-visible buffers
    // so GraphicBuffer's plane-layout contract can report a real byte layout.
    const linear = [_]u64{0};
    const bo = (if (usage & 0xff != 0)
        gbm_bo_create_with_modifiers2(d, width, height, f.drm, &linear, 1, gbmUsage(usage) & ~@as(u32, 1 << 4))
    else
        gbm_bo_create(d, width, height, f.drm, gbmUsage(usage))) orelse return -12;
    var id: u64 = undefined;
    io.random(std.mem.asBytes(&id));
    return register(bo, .{ .width = width, .height = height, .format = format, .layers = layers, .usage = usage, .drm_format = f.drm, .stride = 0, .offset = 0, .modifier = 0, .id = id, .fd = -1 }, output);
}
pub export fn azk_graphics_import(raw: *const Handle, output: *?*Handle) callconv(.c) i32 {
    output.* = null;
    const info = decode(raw) orelse return -22;
    lock();
    defer unlock();
    const d = getDevice() orelse return -19;
    // DMA-BUF stat sizes may be unavailable. For a linear layout validate the
    // minimum last-row extent when the exporter supplies a nonzero size.
    const borrowed: Io.File = .{ .handle = info.fd, .flags = .{ .nonblocking = false } };
    const stat = borrowed.stat(io) catch return -22;
    if (info.modifier == 0 and stat.size != 0) {
        const extent = @as(u64, info.offset) + @as(u64, info.stride) * (info.height - 1) + @as(u64, info.width) * formatInfo(info.format).?.bpp;
        if (extent > stat.size) return -22;
    }
    var desc: Imported = .{ .width = info.width, .height = info.height, .format = info.drm_format, .num_fds = 1, .fds = .{ info.fd, -1, -1, -1 }, .strides = .{ @intCast(info.stride), 0, 0, 0 }, .offsets = .{ @intCast(info.offset), 0, 0, 0 }, .modifier = info.modifier };
    const bo = gbm_bo_import(d, 0x5504, &desc, gbmUsage(info.usage)) orelse return -22;
    return register(bo, info, output);
}
pub export fn azk_graphics_free(handle: *const Handle) callconv(.c) i32 {
    lock();
    defer unlock();
    for (buffers.items, 0..) |buffer, i| {
        if (buffer.handle != handle) continue;
        if (buffer.mapped) gbm_bo_unmap(buffer.bo, buffer.map_token);
        _ = buffers.swapRemove(i);
        buffer.file.close(io);
        gbm_bo_destroy(buffer.bo);
        _ = native_handle_delete(buffer.handle);
        return 0;
    }
    return -22;
}
pub export fn azk_graphics_describe(handle: *const Handle, output: *Info) callconv(.c) i32 {
    lock();
    defer unlock();
    output.* = (lookup(handle) orelse return -22).info;
    return 0;
}
pub export fn azk_graphics_supported(w: u32, h: u32, f: u32, layers: u32, usage: u64) callconv(.c) i32 {
    if (!valid(w, h, f, layers, usage)) return 0;
    lock();
    defer unlock();
    const d = getDevice() orelse return -19;
    return @intFromBool(gbm_device_is_format_supported(d, formatInfo(f).?.drm, gbmUsage(usage)) != 0);
}
pub export fn azk_graphics_lock(handle: *const Handle, usage: u64, left: i32, top: i32, right: i32, bottom: i32, address: *?*anyopaque, pixel_stride: *i32, row_stride: *i32) callconv(.c) i32 {
    address.* = null;
    lock();
    defer unlock();
    const b = lookup(handle) orelse return -22;
    if (b.mapped) return -16;
    // IMapper 4.0::lock defines the all-zero access region as the entire
    // buffer. Stock Surface.attachAndQueueBuffer/ImageReader use this sentinel.
    const entire_buffer = left == 0 and top == 0 and right == 0 and bottom == 0;
    if (usage & ~@as(u64, 0xff) != 0 or usage == 0 or left < 0 or top < 0 or
        (!entire_buffer and (right <= left or bottom <= top)) or
        right > b.info.width or bottom > b.info.height or (usage & 0xf != 0 and b.info.usage & 0xf == 0) or
        (usage & 0xf0 != 0 and b.info.usage & 0xf0 == 0)) return -22;
    var stride: u32 = 0;
    const flags: u32 = (if (usage & 0xf != 0) @as(u32, 1) else 0) | (if (usage & 0xf0 != 0) @as(u32, 2) else 0);
    const mapped = gbm_bo_map(b.bo, 0, 0, b.info.width, b.info.height, flags, &stride, &b.map_token) orelse return -5;
    b.mapped = true;
    address.* = mapped;
    pixel_stride.* = @intCast(formatInfo(b.info.format).?.bpp);
    row_stride.* = @intCast(stride);
    return 0;
}
pub export fn azk_graphics_unlock(handle: *const Handle) callconv(.c) i32 {
    lock();
    defer unlock();
    const b = lookup(handle) orelse return -22;
    if (!b.mapped) return -22;
    gbm_bo_unmap(b.bo, b.map_token);
    b.mapped = false;
    b.map_token = null;
    return 0;
}
pub export fn azk_graphics_dataspace(handle: *const Handle, dataspace: i32) callconv(.c) i32 {
    lock();
    defer unlock();
    const b = lookup(handle) orelse return -22;
    b.info.dataspace = dataspace;
    encode(b.handle, b.info);
    return 0;
}
pub export fn azk_graphics_live_buffers() callconv(.c) u64 {
    lock();
    defer unlock();
    return buffers.items.len;
}
pub export fn azk_graphics_gbm_device() callconv(.c) ?*Device {
    lock();
    defer unlock();
    return getDevice();
}

test "wire metadata rejects malformed and unsupported buffers without a device" {
    var raw: extern struct { h: Handle, words: [17]u32 } = .{ .h = .{ .version = 12, .num_fds = 1, .num_ints = 16 }, .words = @splat(0) };
    try std.testing.expect(decode(&raw.h) == null);
    const info: Info = .{ .width = 40, .height = 20, .format = 1, .layers = 1, .usage = 0x300, .drm_format = 0x34324241, .stride = 160, .offset = 0, .modifier = 0, .id = 5, .fd = 7 };
    encode(&raw.h, info);
    try std.testing.expectEqual(info, decode(&raw.h).?);
    raw.words[9] = 159;
    try std.testing.expect(decode(&raw.h) == null);
    encode(&raw.h, info);
    raw.words[7] |= 1 << 14;
    try std.testing.expect(decode(&raw.h) == null);
    encode(&raw.h, info);
    raw.h.num_fds = 2;
    try std.testing.expect(decode(&raw.h) == null);
}

// AOSP calls the KHR spellings directly; GLVND exposes these through
// eglGetProcAddress. EGL 1.5 promoted the identical sync-handle operations.
extern "c" fn eglClientWaitSync(?*anyopaque, ?*anyopaque, c_int, u64) c_int;
extern "c" fn eglDestroySync(?*anyopaque, ?*anyopaque) c_uint;
export fn eglClientWaitSyncKHR(display: ?*anyopaque, sync: ?*anyopaque, flags: c_int, timeout: u64) c_int {
    return eglClientWaitSync(display, sync, flags, timeout);
}
export fn eglDestroySyncKHR(display: ?*anyopaque, sync: ?*anyopaque) c_uint {
    return eglDestroySync(display, sync);
}
extern "c" fn eglGetProcAddress([*:0]const u8) ?*const fn () callconv(.c) void;
extern "c" fn eglDestroyImage(?*anyopaque, ?*anyopaque) c_uint;
extern "c" fn eglWaitSync(?*anyopaque, ?*anyopaque, c_int) c_uint;
export fn eglDestroyImageKHR(display: ?*anyopaque, image: ?*anyopaque) c_uint {
    return eglDestroyImage(display, image);
}
export fn eglWaitSyncKHR(display: ?*anyopaque, sync: ?*anyopaque, flags: c_int) c_uint {
    return eglWaitSync(display, sync, flags);
}
export fn eglCreateSyncKHR(display: ?*anyopaque, kind: c_uint, attributes: ?[*]const c_int) ?*anyopaque {
    const function: *const fn (?*anyopaque, c_uint, ?[*]const c_int) callconv(.c) ?*anyopaque =
        @ptrCast(eglGetProcAddress("eglCreateSyncKHR") orelse return null);
    return function(display, kind, attributes);
}
export fn eglCreateImageKHR(display: ?*anyopaque, context: ?*anyopaque, target: c_uint, buffer: ?*anyopaque, attributes: ?[*]const c_int) ?*anyopaque {
    const function: *const fn (?*anyopaque, ?*anyopaque, c_uint, ?*anyopaque, ?[*]const c_int) callconv(.c) ?*anyopaque =
        @ptrCast(eglGetProcAddress("eglCreateImageKHR") orelse return null);
    return function(display, context, target, buffer, attributes);
}
export fn eglDupNativeFenceFDANDROID(display: ?*anyopaque, sync: ?*anyopaque) c_int {
    const function: *const fn (?*anyopaque, ?*anyopaque) callconv(.c) c_int =
        @ptrCast(eglGetProcAddress("eglDupNativeFenceFDANDROID") orelse return -1);
    return function(display, sync);
}
export fn glEGLImageTargetTexture2DOES(target: c_uint, image: ?*anyopaque) void {
    const function: *const fn (c_uint, ?*anyopaque) callconv(.c) void =
        @ptrCast(eglGetProcAddress("glEGLImageTargetTexture2DOES") orelse @panic("GL_OES_EGL_image unavailable"));
    function(target, image);
}

comptime {
    _ = @import("graphics_egl.zig");
}
pub fn activeIo() Io {
    ensureIo();
    return io;
}
