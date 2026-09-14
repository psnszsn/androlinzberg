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
const Buffer = struct { handle: *Handle, bo: *Bo, file: Io.File, info: Info, charged_bytes: u64, map_token: ?*anyopaque = null, mapped: bool = false, mapped_address: ?[*]u8 = null, mapped_stride: u32 = 0, cpu_view: ?[]u8 = null, cpu_write: bool = false };
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
// Bound retained GBM objects and their DMA-BUF storage independently. Imports
// are charged in full even when they alias another handle: this also bounds
// driver import metadata and descriptors without trusting caller-supplied IDs.
const Budget = struct {
    const default_bytes: u64 = 2 * 1024 * 1024 * 1024;
    const default_count: usize = 1024;
    const max_buffer_bytes: u64 = 256 * 1024 * 1024;
    bytes_limit: u64 = default_bytes,
    count_limit: usize = default_count,
    bytes: u64 = 0,
    count: usize = 0,

    fn fits(b: Budget, cost: u64) bool {
        return cost <= max_buffer_bytes and cost <= b.bytes_limit - b.bytes and b.count < b.count_limit;
    }
    fn retain(b: *Budget, cost: u64) bool {
        if (!b.fits(cost)) return false;
        b.bytes += cost;
        b.count += 1;
        return true;
    }
    fn release(b: *Budget, cost: u64) void {
        std.debug.assert(b.count > 0 and cost <= b.bytes);
        b.count -= 1;
        b.bytes -= cost;
    }
};
var budget: Budget = .{};
fn configuredLimit(comptime T: type, name: [*:0]const u8, maximum: T) T {
    const value = getenv(name) orelse return maximum;
    // Administrative limits can only reduce the built-in ceiling. Invalid
    // values deny allocations, rather than silently removing a resource limit.
    return @min(std.fmt.parseInt(T, std.mem.span(value), 10) catch return 0, maximum);
}
fn layoutBytes(info: Info) u64 {
    return @as(u64, info.offset) + @as(u64, info.stride) * info.height;
}
fn ensureIo() void {
    if (initialized.load(.acquire) == 2) return;
    if (initialized.cmpxchgStrong(0, 1, .acq_rel, .acquire) == null) {
        threaded = Io.Threaded.init(alloc, .{});
        io = threaded.io();
        budget.bytes_limit = configuredLimit(u64, "AZK_GRAPHICS_MAX_BYTES", Budget.default_bytes);
        budget.count_limit = configuredLimit(usize, "AZK_GRAPHICS_MAX_BUFFERS", Budget.default_count);
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
    handle.writable().* = .{ @bitCast(info.fd), magic, 2, info.width, info.height, info.format, info.layers, @truncate(info.usage), @truncate(info.usage >> 32), info.stride, info.offset, @truncate(info.modifier), @truncate(info.modifier >> 32), @truncate(info.id), @truncate(info.id >> 32), @bitCast(info.dataspace), info.drm_format };
}
// Requested Android channel order may differ from the system EGL drawable's
// DRM storage order. GraphicBufferMapper exposes the actual FourCC for GPU
// imports; CPU locks expose the requested Android format (like tiled mappings).
fn swappedChannels(info: Info) bool {
    return (info.format == 1 and info.drm_format == 0x34325241) or
        (info.format == 2 and info.drm_format == 0x34325258) or
        (info.format == 0x2b and info.drm_format == 0x30335241);
}
fn validDescription(info: Info) bool {
    if (!valid(info.width, info.height, info.format, info.layers, info.usage) or info.fd < 0 or info.reserved != 0) return false;
    const format = formatInfo(info.format).?;
    return (info.drm_format == format.drm or swappedChannels(info)) and info.stride % format.bpp == 0 and
        info.stride >= info.width * format.bpp and info.stride <= std.math.maxInt(i32) and info.offset <= std.math.maxInt(i32);
}
fn decode(handle: *const Handle) ?Info {
    if (handle.version != @sizeOf(Handle) or handle.num_fds != 1 or handle.num_ints != integer_count) return null;
    const w = handle.words();
    if (w[1] != magic or (w[2] != 1 and w[2] != 2) or (w[2] == 1 and w[16] != 0) or @as(i32, @bitCast(w[0])) < 0) return null;
    const usage = @as(u64, w[7]) | (@as(u64, w[8]) << 32);
    if (!valid(w[3], w[4], w[5], w[6], usage)) return null;
    const format = formatInfo(w[5]).?;
    const info: Info = .{ .width = w[3], .height = w[4], .format = w[5], .layers = w[6], .usage = usage, .drm_format = if (w[2] == 1) format.drm else w[16], .stride = w[9], .offset = w[10], .modifier = @as(u64, w[11]) | (@as(u64, w[12]) << 32), .id = @as(u64, w[13]) | (@as(u64, w[14]) << 32), .fd = @bitCast(w[0]), .dataspace = @bitCast(w[15]) };
    return if (validDescription(info)) info else null;
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
    info.fd = fd;
    info.stride = gbm_bo_get_stride(bo);
    info.offset = gbm_bo_get_offset(bo, 0);
    info.modifier = gbm_bo_get_modifier(bo);
    // DMA-BUF stat size includes driver padding when reported. Otherwise retain
    // the full pitch/height extent, as the pinned allocator's size estimate does.
    const storage = file.length(io) catch {
        file.close(io);
        gbm_bo_destroy(bo);
        return -22;
    };
    const cost = @max(storage, layoutBytes(info));
    if (!budget.retain(cost)) {
        file.close(io);
        gbm_bo_destroy(bo);
        return -12;
    }
    const handle = native_handle_create(1, integer_count) orelse {
        budget.release(cost);
        file.close(io);
        gbm_bo_destroy(bo);
        return -12;
    };
    encode(handle, info);
    buffers.append(alloc, .{ .handle = handle, .bo = bo, .file = file, .info = info, .charged_bytes = cost }) catch {
        budget.release(cost);
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
    const f = formatInfo(format).?;
    if (!budget.fits(@as(u64, width) * height * f.bpp)) return -12;
    const d = getDevice() orelse return -19;
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
    return importBuffer(info, output);
}
/// Adopt a foreign single-plane RGB DMA-BUF into the stock gralloc contract.
/// Input FDs remain borrowed; registration owns a new descriptor and buffer ID.
pub export fn azk_graphics_import_external(raw: *const Info, output: *?*Handle) callconv(.c) i32 {
    output.* = null;
    if (!validDescription(raw.*)) return -22;
    lock();
    defer unlock();
    var info = raw.*;
    io.random(std.mem.asBytes(&info.id));
    return importBuffer(info, output);
}
fn importBuffer(info: Info, output: *?*Handle) i32 {
    const d = getDevice() orelse return -19;
    // DMA-BUF stat sizes may be unavailable. For a linear layout validate the
    // minimum last-row extent when the exporter supplies a nonzero size.
    const borrowed: Io.File = .{ .handle = info.fd, .flags = .{ .nonblocking = false } };
    const stat = borrowed.stat(io) catch return -22;
    if (!budget.fits(@max(stat.size, layoutBytes(info)))) return -12;
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
        if (buffer.cpu_view) |view| alloc.free(view);
        _ = buffers.swapRemove(i);
        buffer.file.close(io);
        gbm_bo_destroy(buffer.bo);
        _ = native_handle_delete(buffer.handle);
        budget.release(buffer.charged_bytes);
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
    const flags: u32 = (if (usage & 0xf != 0 or swappedChannels(b.info)) @as(u32, 1) else 0) | (if (usage & 0xf0 != 0) @as(u32, 2) else 0);
    const mapped = gbm_bo_map(b.bo, 0, 0, b.info.width, b.info.height, flags, &stride, &b.map_token) orelse return -5;
    if (swappedChannels(b.info) or stride != b.info.stride) {
        const cost = @as(usize, b.info.stride) * b.info.height;
        if (!budget.fits(cost)) {
            gbm_bo_unmap(b.bo, b.map_token);
            b.map_token = null;
            return -12;
        }
        const view = alloc.alloc(u8, cost) catch {
            gbm_bo_unmap(b.bo, b.map_token);
            b.map_token = null;
            return -12;
        };
        @memset(view, 0);
        budget.bytes += cost;
        b.charged_bytes += cost;
        b.cpu_view = view;
        b.mapped_address = @ptrCast(mapped);
        b.mapped_stride = stride;
        b.cpu_write = usage & 0xf0 != 0;
        convertChannels(b.info, view, b.info.stride, b.mapped_address.?[0 .. @as(usize, stride) * b.info.height], stride);
        address.* = view.ptr;
    } else address.* = mapped;
    b.mapped = true;
    pixel_stride.* = @intCast(formatInfo(b.info.format).?.bpp);
    row_stride.* = @intCast(if (b.cpu_view != null) b.info.stride else stride);
    return 0;
}
fn convertChannels(info: Info, dest: []u8, dest_stride: u32, source: []const u8, stride: u32) void {
    if (!swappedChannels(info)) {
        const bytes = info.width * formatInfo(info.format).?.bpp;
        for (0..info.height) |y| @memcpy(dest[y * dest_stride ..][0..bytes], source[y * stride ..][0..bytes]);
        return;
    }
    for (0..info.height) |y| for (0..info.width) |x| {
        const offset = y * stride + x * 4;
        const pixel = std.mem.readInt(u32, source[offset..][0..4], .little);
        const mask: u32 = if (info.format == 0x2b) 0x3ff else 0xff;
        const shift: u5 = if (info.format == 0x2b) 20 else 16;
        const converted = (pixel & ~(mask | (mask << shift))) | ((pixel & mask) << shift) | ((pixel >> shift) & mask);
        std.mem.writeInt(u32, dest[y * dest_stride + x * 4 ..][0..4], converted, .little);
    };
}
pub export fn azk_graphics_unlock(handle: *const Handle) callconv(.c) i32 {
    lock();
    defer unlock();
    const b = lookup(handle) orelse return -22;
    if (!b.mapped) return -22;
    if (b.cpu_view) |view| {
        if (b.cpu_write) convertChannels(b.info, b.mapped_address.?[0 .. @as(usize, b.mapped_stride) * b.info.height], b.mapped_stride, view, b.info.stride);
        budget.bytes -= view.len;
        b.charged_bytes -= view.len;
        alloc.free(view);
        b.cpu_view = null;
        b.mapped_address = null;
    }
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
pub export fn azk_graphics_live_bytes() callconv(.c) u64 {
    lock();
    defer unlock();
    return budget.bytes;
}
pub export fn azk_graphics_gbm_device() callconv(.c) ?*Device {
    lock();
    defer unlock();
    return getDevice();
}

test "graphics admission bounds bytes and handles independently and reuses released capacity" {
    var b: Budget = .{ .bytes_limit = 100, .count_limit = 3 };
    try std.testing.expect(b.retain(60));
    try std.testing.expect(!b.retain(41));
    try std.testing.expect(b.retain(20));
    try std.testing.expect(b.retain(20));
    try std.testing.expect(!b.retain(0));
    b.release(60);
    try std.testing.expect(b.retain(60));
    b.release(20);
    b.release(60);
    b.release(20);
    try std.testing.expectEqual(@as(u64, 0), b.bytes);
    try std.testing.expectEqual(@as(usize, 0), b.count);
    var full: Budget = .{};
    try std.testing.expect(!full.retain(Budget.max_buffer_bytes + 1));
    try std.testing.expect(full.retain(Budget.max_buffer_bytes));
    full.release(Budget.max_buffer_bytes);
    b.count_limit = 0;
    try std.testing.expect(!b.retain(1));
    b.count_limit = 3;
    b.bytes_limit = 0;
    try std.testing.expect(!b.retain(1));
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

test "CPU locks translate DRM channel order and independent row strides bidirectionally" {
    const info: Info = .{ .width = 1, .height = 2, .format = 1, .layers = 1, .usage = 0x33, .drm_format = 0x34325241, .stride = 8, .offset = 0, .modifier = 0, .id = 1, .fd = 3 };
    const storage = [_]u8{ 0x10, 0x20, 0x30, 0xff, 0x40, 0x50, 0x60, 0x80 };
    var android_view: [16]u8 = @splat(0xee);
    convertChannels(info, &android_view, 8, &storage, 4);
    try std.testing.expectEqualSlices(u8, &.{ 0x30, 0x20, 0x10, 0xff }, android_view[0..4]);
    try std.testing.expectEqualSlices(u8, &.{ 0x60, 0x50, 0x40, 0x80 }, android_view[8..12]);
    try std.testing.expectEqual(@as(u8, 0xee), android_view[4]);
    var roundtrip: [8]u8 = undefined;
    convertChannels(info, &roundtrip, 4, &android_view, 8);
    try std.testing.expectEqualSlices(u8, &storage, &roundtrip);
    var hdr = info;
    hdr.format = 0x2b;
    hdr.drm_format = 0x30335241;
    hdr.height = 1;
    var hdr_pixel: [4]u8 = undefined;
    std.mem.writeInt(u32, &hdr_pixel, (3 << 30) | (123 << 20) | (456 << 10) | 789, .little);
    var converted: [4]u8 = undefined;
    convertChannels(hdr, &converted, 4, &hdr_pixel, 4);
    try std.testing.expectEqual(@as(u32, (3 << 30) | (789 << 20) | (456 << 10) | 123), std.mem.readInt(u32, &converted, .little));
}
