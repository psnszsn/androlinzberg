//! Non-rendering C ABI required by AOSP components in libandroid_platform.
//! Keep these exports in Zig while the HWUI/Skia implementation remains C++.
const std = @import("std");
const art = @import("art");
const jni = art.jni;

// Source: bionic's upstream-openbsd getprogname.c. The host libc owns the
// process-name storage and exposes the same __progname variable.
extern "c" var __progname: [*:0]const u8;
pub export fn getprogname() callconv(.c) [*:0]const u8 {
    return __progname;
}

// The pinned Android EGL client and HWUI use one default display. Mesa's
// implicit default may choose a different platform from the hosted window.
extern "c" fn azk_hwui_default_egl_display() ?*anyopaque;
extern "EGL" fn eglGetDisplay(?*anyopaque) ?*anyopaque;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;
pub export fn azk_host_egl_get_proc_address(name: [*:0]const u8) callconv(.c) ?*const anyopaque {
    return eglGetProcAddress(name);
}
pub export fn bionic_eglGetDisplay(display: ?*anyopaque) callconv(.c) ?*anyopaque {
    return if (display == null) azk_hwui_default_egl_display() else eglGetDisplay(display);
}

var g_io: std.Io = undefined;

const NativeMutex = struct {
    raw: std.Io.Mutex = .init,

    fn lock(self: *NativeMutex) void {
        self.raw.lockUncancelable(g_io);
    }

    fn unlock(self: *NativeMutex) void {
        self.raw.unlock(g_io);
    }
};

pub export fn azk_platform_bind_io(
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void {
    g_io = .{ .userdata = userdata, .vtable = vtable };
    @import("selinux_backend.zig").bindIo(g_io);
}

const sqlite3 = opaque {};
const SqliteCollation = *const fn (?*anyopaque, c_int, ?*const anyopaque, c_int, ?*const anyopaque) callconv(.c) c_int;

extern "c" fn sqlite3_create_collation(
    database: ?*sqlite3,
    name: [*:0]const u8,
    encoding: c_int,
    context: ?*anyopaque,
    compare: SqliteCollation,
) c_int;
extern "c" fn __xpg_strerror_r(error_number: c_int, buffer: [*]u8, buffer_length: usize) c_int;

const sqlite_ok = 0;
const sqlite_utf8 = 1;

fn compareBytes(_: ?*anyopaque, left_len: c_int, left_raw: ?*const anyopaque, right_len: c_int, right_raw: ?*const anyopaque) callconv(.c) c_int {
    if (left_len < 0 or right_len < 0) return 0;
    const left_size: usize = @intCast(left_len);
    const right_size: usize = @intCast(right_len);
    const shared = @min(left_size, right_size);
    if (shared != 0) {
        const left: [*]const u8 = @ptrCast(left_raw orelse return -1);
        const right: [*]const u8 = @ptrCast(right_raw orelse return 1);
        const order = std.mem.order(u8, left[0..shared], right[0..shared]);
        if (order != .eq) return if (order == .lt) -1 else 1;
    }
    return if (left_size < right_size) -1 else if (left_size > right_size) 1 else 0;
}

pub export fn register_localized_collators(database: ?*sqlite3, _: [*c]const u8, _: c_int) callconv(.c) c_int {
    const localized = sqlite3_create_collation(database, "LOCALIZED", sqlite_utf8, null, compareBytes);
    if (localized != sqlite_ok) return localized;
    return sqlite3_create_collation(database, "UNICODE", sqlite_utf8, null, compareBytes);
}

pub export fn register_android_functions(database: ?*sqlite3, utf16_storage: c_int) callconv(.c) c_int {
    return register_localized_collators(database, "en_US", utf16_storage);
}

pub export fn posix_strerror_r(error_number: c_int, buffer: [*]u8, buffer_length: usize) callconv(.c) c_int {
    return __xpg_strerror_r(error_number, buffer, buffer_length);
}

// --------------------------------------------------------------------------
// AHardwareBuffer storage and JNI. C++ only imports these buffers into the
// current EGL/Skia context; allocation, mapping, lifetime, and Java handles
// belong here.

const GbmDevice = opaque {};
const GbmBo = opaque {};

extern "c" fn gbm_create_device(fd: c_int) ?*GbmDevice;
extern "c" fn gbm_device_is_format_supported(device: *GbmDevice, format: u32, usage: u32) c_int;
extern "c" fn gbm_bo_create(device: *GbmDevice, width: u32, height: u32, format: u32, usage: u32) ?*GbmBo;
extern "c" fn gbm_bo_import(device: *GbmDevice, kind: u32, data: *const anyopaque, usage: u32) ?*GbmBo;
extern "c" fn gbm_bo_destroy(bo: *GbmBo) void;
extern "c" fn gbm_bo_get_fd(bo: *GbmBo) c_int;
extern "c" fn gbm_bo_get_stride(bo: *GbmBo) u32;
extern "c" fn gbm_bo_get_modifier(bo: *GbmBo) u64;
extern "c" fn gbm_bo_get_offset(bo: *GbmBo, plane: c_int) u32;
extern "c" fn gbm_bo_map(bo: *GbmBo, x: u32, y: u32, width: u32, height: u32, flags: u32, stride: *u32, map_data: *?*anyopaque) ?*anyopaque;
extern "c" fn gbm_bo_unmap(bo: *GbmBo, map_data: ?*anyopaque) void;
extern "c" fn open(path: [*:0]const u8, flags: c_int, ...) c_int;
extern "c" fn close(fd: c_int) c_int;
extern "c" fn poll(items: [*]PollFd, count: usize, timeout_ms: c_int) c_int;
extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
extern "c" fn __errno_location() *c_int;

const PollFd = extern struct { fd: c_int, events: i16, revents: i16 };
const HardwareBufferDesc = extern struct {
    width: u32,
    height: u32,
    layers: u32,
    format: u32,
    usage: u64,
    stride: u32,
    rfu0: u32,
    rfu1: u64,
};
const Rect = extern struct { left: i32, top: i32, right: i32, bottom: i32 };
const HardwareBufferPlane = extern struct { data: ?*anyopaque, pixel_stride: u32, row_stride: u32 };
const HardwareBufferPlanes = extern struct { plane_count: u32, planes: [4]HardwareBufferPlane };
const DmabufPlane = extern struct { fd: c_int, offset: u32, stride: u32, modifier: u64 };
const DmabufDescription = extern struct {
    width: u32,
    height: u32,
    drm_format: u32,
    plane_count: u32,
    planes: [4]DmabufPlane,
};

const HardwareBuffer = struct {
    references: std.atomic.Value(u32) = .init(1),
    description: HardwareBufferDesc,
    bo: *GbmBo,
    dma_buf_file: std.Io.File,
    drm_format: u32,
    byte_stride: u32,
    modifier: u64,
    id: u64,
    map_mutex: NativeMutex = .{},
    map_data: ?*anyopaque = null,
    map_token: ?*anyopaque = null,
    map_stride: u32 = 0,
};

const Format = struct { drm: u32, bytes_per_pixel: u32 };

fn fourcc(a: u8, b: u8, c: u8, d: u8) u32 {
    return @as(u32, a) | (@as(u32, b) << 8) | (@as(u32, c) << 16) | (@as(u32, d) << 24);
}

fn formatInfo(format: u32) ?Format {
    return switch (format) {
        1 => .{ .drm = fourcc('A', 'B', '2', '4'), .bytes_per_pixel = 4 },
        2 => .{ .drm = fourcc('X', 'B', '2', '4'), .bytes_per_pixel = 4 },
        4 => .{ .drm = fourcc('R', 'G', '1', '6'), .bytes_per_pixel = 2 },
        0x16 => .{ .drm = fourcc('A', 'B', '4', 'H'), .bytes_per_pixel = 8 },
        0x2b => .{ .drm = fourcc('A', 'B', '3', '0'), .bytes_per_pixel = 4 },
        else => null,
    };
}

const usage_cpu_read_mask: u64 = 0xf;
const usage_cpu_write_mask: u64 = 0xf << 4;
const usage_gpu_sampled: u64 = 1 << 8;
const usage_gpu_color: u64 = 1 << 9;
const usage_composer_overlay: u64 = 1 << 11;
const unsupported_usage: u64 = (1 << 14) | (1 << 24) | (1 << 25) | (1 << 26);
const gbm_use_scanout: u32 = 1 << 0;
const gbm_use_rendering: u32 = 1 << 2;
const gbm_use_linear: u32 = 1 << 4;
const gbm_transfer_read: u32 = 1 << 0;
const gbm_transfer_write: u32 = 1 << 1;
const drm_modifier_invalid: u64 = 0x00ffffffffffffff;

fn validDescription(desc: HardwareBufferDesc) bool {
    return desc.width != 0 and desc.height != 0 and desc.layers == 1 and
        formatInfo(desc.format) != null and desc.rfu0 == 0 and desc.rfu1 == 0 and
        desc.usage & unsupported_usage == 0;
}

fn gbmUsage(usage: u64) u32 {
    var flags: u32 = 0;
    if (usage & (usage_gpu_sampled | usage_gpu_color) != 0) flags |= gbm_use_rendering;
    if (usage & usage_composer_overlay != 0) flags |= gbm_use_scanout;
    if (usage & (usage_cpu_read_mask | usage_cpu_write_mask) != 0) flags |= gbm_use_linear;
    return flags;
}

var gbm_mutex: NativeMutex = .{};
var gbm_initialized = false;
var gbm_device: ?*GbmDevice = null;
var gbm_fd: c_int = -1;

fn newHardwareBufferId() u64 {
    while (true) {
        var bytes: [8]u8 = undefined;
        g_io.random(&bytes);
        const id = std.mem.readInt(u64, &bytes, .little);
        if (id != 0) return id;
    }
}

fn getGbmDevice() ?*GbmDevice {
    gbm_mutex.lock();
    defer gbm_mutex.unlock();
    if (gbm_initialized) return gbm_device;
    gbm_initialized = true;
    var path = getenv("ARTZIG_DRM_DEVICE");
    if (path == null or path.?[0] == 0) path = getenv("ARTZIG_VAAPI_DEVICE");
    if (path == null or path.?[0] == 0) path = "/dev/dri/renderD128";
    gbm_fd = open(path.?, 2 | 0x80000); // O_RDWR | O_CLOEXEC
    if (gbm_fd < 0) return null;
    gbm_device = gbm_create_device(gbm_fd);
    if (gbm_device == null) {
        _ = close(gbm_fd);
        gbm_fd = -1;
    }
    return gbm_device;
}

fn waitFence(fd: c_int) bool {
    if (fd < 0) return true;
    defer _ = close(fd);
    var item = PollFd{ .fd = fd, .events = 1, .revents = 0 }; // POLLIN
    while (true) {
        const result = poll(@ptrCast(&item), 1, -1);
        if (result > 0) return true;
        if (result == 0 or __errno_location().* != 4) return false; // EINTR
    }
}

pub export fn AHardwareBuffer_allocate(desc: ?*const HardwareBufferDesc, out_buffer: ?*?*HardwareBuffer) callconv(.c) c_int {
    const output = out_buffer orelse return -22;
    output.* = null;
    const description = (desc orelse return -22).*;
    if (!validDescription(description)) return -22;
    const device = getGbmDevice() orelse return -19;
    const info = formatInfo(description.format).?;
    const flags = gbmUsage(description.usage);
    if (gbm_device_is_format_supported(device, info.drm, flags) == 0) return -22;
    const bo = gbm_bo_create(device, description.width, description.height, info.drm, flags) orelse return -12;
    var adopted = false;
    defer if (!adopted) gbm_bo_destroy(bo);
    const fd = gbm_bo_get_fd(bo);
    if (fd < 0) {
        const error_number: c_int = __errno_location().*;
        return if (error_number > 0) -error_number else -1;
    }
    const file: std.Io.File = .{ .handle = fd, .flags = .{ .nonblocking = false } };
    defer if (!adopted) file.close(g_io);
    const buffer = std.heap.c_allocator.create(HardwareBuffer) catch return -12;
    var stored = description;
    const byte_stride = gbm_bo_get_stride(bo);
    stored.stride = byte_stride / info.bytes_per_pixel;
    stored.rfu0 = 0;
    stored.rfu1 = 0;
    buffer.* = .{
        .description = stored,
        .bo = bo,
        .dma_buf_file = file,
        .drm_format = info.drm,
        .byte_stride = byte_stride,
        .modifier = gbm_bo_get_modifier(bo),
        // IDs are opaque and must survive cross-process import. A process-local
        // counter alone collided with buffers allocated by another hosted peer.
        .id = newHardwareBufferId(),
    };
    adopted = true;
    output.* = buffer;
    return 0;
}

pub export fn AHardwareBuffer_acquire(buffer: ?*HardwareBuffer) callconv(.c) void {
    if (buffer) |value| _ = value.references.fetchAdd(1, .monotonic);
}

pub export fn AHardwareBuffer_release(buffer: ?*HardwareBuffer) callconv(.c) void {
    const value = buffer orelse return;
    if (value.references.fetchSub(1, .acq_rel) != 1) return;
    if (value.map_token != null) gbm_bo_unmap(value.bo, value.map_token);
    value.dma_buf_file.close(g_io);
    gbm_bo_destroy(value.bo);
    std.heap.c_allocator.destroy(value);
}

pub export fn AHardwareBuffer_describe(buffer: ?*const HardwareBuffer, out_desc: ?*HardwareBufferDesc) callconv(.c) void {
    if (out_desc) |output| output.* = if (buffer) |value| value.description else std.mem.zeroes(HardwareBufferDesc);
}

pub export fn AHardwareBuffer_isSupported(desc: ?*const HardwareBufferDesc) callconv(.c) c_int {
    const description = (desc orelse return 0).*;
    if (!validDescription(description)) return 0;
    const device = getGbmDevice() orelse return 0;
    return @intFromBool(gbm_device_is_format_supported(device, formatInfo(description.format).?.drm, gbmUsage(description.usage)) != 0);
}

pub export fn AHardwareBuffer_lock(buffer: ?*HardwareBuffer, usage: u64, fence: i32, rect: ?*const Rect, out_address: ?*?*anyopaque) callconv(.c) c_int {
    const value = buffer orelse return -22;
    const output = out_address orelse return -22;
    if (value.description.layers != 1) return -22;
    const cpu_mask = usage_cpu_read_mask | usage_cpu_write_mask;
    if (usage & cpu_mask == 0 or usage & ~cpu_mask != 0 or usage & value.description.usage != usage) return -22;
    if (!waitFence(fence)) return -5;
    value.map_mutex.lock();
    defer value.map_mutex.unlock();
    if (value.map_token != null) return -16;
    const width = value.description.width;
    const height = value.description.height;
    if (rect) |area| {
        if (area.left < 0 or area.top < 0 or area.right <= area.left or area.bottom <= area.top or area.right > width or area.bottom > height) return -22;
    }
    var transfer: u32 = 0;
    if (usage & usage_cpu_read_mask != 0) transfer |= gbm_transfer_read;
    if (usage & usage_cpu_write_mask != 0) transfer |= gbm_transfer_write;
    var stride: u32 = 0;
    // AHardwareBuffer returns the buffer origin even when a dirty subrectangle
    // is supplied. GBM's subrectangle pointer would shift Canvas's full image.
    value.map_data = gbm_bo_map(value.bo, 0, 0, width, height, transfer, &stride, &value.map_token);
    if (value.map_data == null) {
        value.map_token = null;
        return -12;
    }
    value.map_stride = stride;
    output.* = value.map_data;
    return 0;
}

pub export fn AHardwareBuffer_unlock(buffer: ?*HardwareBuffer, fence: ?*i32) callconv(.c) c_int {
    const value = buffer orelse return -22;
    value.map_mutex.lock();
    defer value.map_mutex.unlock();
    if (value.map_token == null) return -22;
    gbm_bo_unmap(value.bo, value.map_token);
    value.map_data = null;
    value.map_token = null;
    value.map_stride = 0;
    if (fence) |output| output.* = -1;
    return 0;
}

pub export fn AHardwareBuffer_lockPlanes(buffer: ?*HardwareBuffer, usage: u64, fence: i32, rect: ?*const Rect, out_planes: ?*HardwareBufferPlanes) callconv(.c) c_int {
    const planes = out_planes orelse return -22;
    planes.* = std.mem.zeroes(HardwareBufferPlanes);
    var address: ?*anyopaque = null;
    const result = AHardwareBuffer_lock(buffer, usage, fence, rect, &address);
    if (result != 0) return result;
    const value = buffer.?;
    planes.plane_count = 1;
    planes.planes[0] = .{ .data = address, .pixel_stride = formatInfo(value.description.format).?.bytes_per_pixel, .row_stride = value.map_stride };
    return 0;
}

pub export fn AHardwareBuffer_lockAndGetInfo(buffer: ?*HardwareBuffer, usage: u64, fence: i32, rect: ?*const Rect, out_address: ?*?*anyopaque, out_bytes_per_pixel: ?*i32, out_bytes_per_stride: ?*i32) callconv(.c) c_int {
    const result = AHardwareBuffer_lock(buffer, usage, fence, rect, out_address);
    if (result != 0) return result;
    const value = buffer.?;
    if (out_bytes_per_pixel) |output| output.* = @intCast(formatInfo(value.description.format).?.bytes_per_pixel);
    if (out_bytes_per_stride) |output| output.* = @intCast(value.map_stride);
    return 0;
}

pub export fn AHardwareBuffer_getId(buffer: ?*const HardwareBuffer, out_id: ?*u64) callconv(.c) c_int {
    const value = buffer orelse return -22;
    const output = out_id orelse return -22;
    output.* = value.id;
    return 0;
}

pub export fn AHardwareBuffer_sendHandleToUnixSocket(_: ?*const HardwareBuffer, _: c_int) callconv(.c) c_int {
    return -38;
}

pub export fn AHardwareBuffer_recvHandleFromUnixSocket(_: c_int, _: ?*?*HardwareBuffer) callconv(.c) c_int {
    return -38;
}

pub export fn azk_hardware_buffer_describe_dmabuf(buffer: ?*const HardwareBuffer, output: ?*DmabufDescription) callconv(.c) bool {
    const value = buffer orelse return false;
    const description = output orelse return false;
    description.* = std.mem.zeroes(DmabufDescription);
    description.width = value.description.width;
    description.height = value.description.height;
    description.drm_format = value.drm_format;
    description.plane_count = 1;
    description.planes[0] = .{ .fd = value.dma_buf_file.handle, .offset = gbm_bo_get_offset(value.bo, 0), .stride = value.byte_stride, .modifier = value.modifier };
    return true;
}

pub export fn azk_hardware_buffer_gbm_device() callconv(.c) ?*GbmDevice {
    return getGbmDevice();
}

var hardware_buffer_class: jni.jclass = null;
var hardware_buffer_native_object: jni.jfieldID = null;
var hardware_buffer_constructor: jni.jmethodID = null;

// Pinned GraphicBuffer GB01 flattening plus a mapper-specific native handle.
// The stock header's dimensions, usage, ID, generation and transport counts
// remain intact. GBM consumes the handle's DMA-BUF, stride, offset and modifier.
const HardwareBufferTransport = struct {
    const magic = 0x47423031; // 'GB01'
    const mapper_magic = 0x415a4742; // 'AZGB'
    const word_count = 20;
    var cls: jni.jclass = null;
    var write_method: jni.jmethodID = null;
    var read_method: jni.jmethodID = null;

    const ImportFd = extern struct { fd: c_int, width: u32, height: u32, stride: u32, format: u32 };
    const ImportModifier = extern struct {
        width: u32,
        height: u32,
        format: u32,
        num_fds: u32,
        fds: [4]c_int,
        strides: [4]c_int,
        offsets: [4]c_int,
        modifier: u64,
    };

    fn low(value: u64) i32 {
        return @bitCast(@as(u32, @truncate(value)));
    }

    fn combine(lo: i32, hi: i32) u64 {
        return @as(u64, @as(u32, @bitCast(lo))) | (@as(u64, @as(u32, @bitCast(hi))) << 32);
    }

    fn describe(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jintArray {
        const buffer = fromHandle(handle) orelse return null;
        const d = buffer.description;
        const words = [_]i32{
            magic,                       @intCast(d.width),            @intCast(d.height),   @intCast(d.stride),         @intCast(d.format),
            @intCast(d.layers),          low(d.usage),                 low(buffer.id >> 32), low(buffer.id),             0,
            1,                           7,                            low(d.usage >> 32),   mapper_magic,               1,
            @bitCast(buffer.drm_format), @intCast(buffer.byte_stride), low(buffer.modifier), low(buffer.modifier >> 32), @intCast(gbm_bo_get_offset(buffer.bo, 0)),
            buffer.dma_buf_file.handle,
        };
        const array = env.*.*.NewIntArray.?(env, words.len) orelse return null;
        env.*.*.SetIntArrayRegion.?(env, array, 0, words.len, &words);
        return array;
    }

    fn importBuffer(env: [*c]jni.JNIEnv, _: jni.jclass, array: jni.jintArray, fd: jni.jint) callconv(.c) jni.jlong {
        if (array == null or fd < 0 or env.*.*.GetArrayLength.?(env, array) != word_count) return 0;
        var words: [word_count]i32 = undefined;
        env.*.*.GetIntArrayRegion.?(env, array, 0, word_count, &words);
        if (env.*.*.ExceptionCheck.?(env) != 0) return 0;
        if (words[0] != magic or words[10] != 1 or words[11] != 7 or
            words[13] != mapper_magic or words[14] != 1 or words[1] <= 0 or words[2] <= 0 or
            words[3] <= 0 or words[4] < 0 or words[5] != 1 or words[16] <= 0 or words[19] < 0) return 0;
        var desc = HardwareBufferDesc{
            .width = @intCast(words[1]),
            .height = @intCast(words[2]),
            .stride = @intCast(words[3]),
            .format = @intCast(words[4]),
            .layers = 1,
            .usage = combine(words[6], words[12]),
            .rfu0 = 0,
            .rfu1 = 0,
        };
        if (!validDescription(desc)) return 0;
        const info = formatInfo(desc.format).?;
        const byte_stride: u32 = @intCast(words[16]);
        if (info.drm != @as(u32, @bitCast(words[15])) or
            @as(u64, desc.width) * info.bytes_per_pixel > byte_stride or
            @as(u64, desc.stride) * info.bytes_per_pixel != byte_stride) return 0;
        const device = getGbmDevice() orelse return 0;
        const modifier = combine(words[17], words[18]);
        const imported = if (modifier == drm_modifier_invalid) implicit: {
            if (words[19] != 0) return 0;
            const data = ImportFd{ .fd = fd, .width = desc.width, .height = desc.height, .stride = byte_stride, .format = info.drm };
            break :implicit gbm_bo_import(device, 0x5503, &data, gbmUsage(desc.usage));
        } else explicit: {
            const data = ImportModifier{
                .width = desc.width,
                .height = desc.height,
                .format = info.drm,
                .num_fds = 1,
                .fds = .{ fd, -1, -1, -1 },
                .strides = .{ words[16], 0, 0, 0 },
                .offsets = .{ words[19], 0, 0, 0 },
                .modifier = modifier,
            };
            break :explicit gbm_bo_import(device, 0x5504, &data, gbmUsage(desc.usage));
        };
        const bo = imported orelse return 0;
        var adopted = false;
        defer if (!adopted) gbm_bo_destroy(bo);
        const imported_fd = gbm_bo_get_fd(bo);
        if (imported_fd < 0) return 0;
        const file: std.Io.File = .{ .handle = imported_fd, .flags = .{ .nonblocking = false } };
        defer if (!adopted) file.close(g_io);
        const stride = gbm_bo_get_stride(bo);
        if (stride != byte_stride) return 0;
        desc.stride = stride / info.bytes_per_pixel;
        const buffer = std.heap.c_allocator.create(HardwareBuffer) catch return 0;
        buffer.* = .{
            .description = desc,
            .bo = bo,
            .dma_buf_file = file,
            .drm_format = info.drm,
            .byte_stride = stride,
            .modifier = gbm_bo_get_modifier(bo),
            .id = combine(words[8], words[7]),
        };
        adopted = true;
        return @intCast(@intFromPtr(buffer));
    }

    fn write(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, parcel: jni.jobject) callconv(.c) void {
        env.*.*.CallStaticVoidMethod.?(env, cls, write_method, handle, parcel);
    }

    fn read(env: [*c]jni.JNIEnv, _: jni.jclass, parcel: jni.jobject) callconv(.c) jni.jlong {
        return env.*.*.CallStaticLongMethod.?(env, cls, read_method, parcel);
    }

    fn register(env: [*c]jni.JNIEnv) bool {
        const local = env.*.*.FindClass.?(env, "dev/artzig/HardwareBufferParcel") orelse return false;
        defer env.*.*.DeleteLocalRef.?(env, local);
        cls = env.*.*.NewGlobalRef.?(env, local) orelse return false;
        write_method = env.*.*.GetStaticMethodID.?(env, cls, "write", "(JLandroid/os/Parcel;)V") orelse return false;
        read_method = env.*.*.GetStaticMethodID.?(env, cls, "read", "(Landroid/os/Parcel;)J") orelse return false;
        const methods = [_]jni.JNINativeMethod{
            .{ .name = "nativeDescribe", .signature = "(J)[I", .fnPtr = @ptrCast(@constCast(&describe)) },
            .{ .name = "nativeImport", .signature = "([II)J", .fnPtr = @ptrCast(@constCast(&importBuffer)) },
        };
        return env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) == 0;
    }
};

fn fromHandle(handle: jni.jlong) ?*HardwareBuffer {
    if (handle == 0) return null;
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

fn nativeHardwareBufferCreate(_: [*c]jni.JNIEnv, _: jni.jclass, width: jni.jint, height: jni.jint, format: jni.jint, layers: jni.jint, usage: jni.jlong) callconv(.c) jni.jlong {
    if (width <= 0 or height <= 0 or layers <= 0 or format < 0) return 0;
    const desc = HardwareBufferDesc{ .width = @intCast(width), .height = @intCast(height), .layers = @intCast(layers), .format = @intCast(format), .usage = @bitCast(usage), .stride = 0, .rfu0 = 0, .rfu1 = 0 };
    var buffer: ?*HardwareBuffer = null;
    return if (AHardwareBuffer_allocate(&desc, &buffer) == 0) @intCast(@intFromPtr(buffer.?)) else 0;
}

fn nativeHardwareBufferIsSupported(_: [*c]jni.JNIEnv, _: jni.jclass, width: jni.jint, height: jni.jint, format: jni.jint, layers: jni.jint, usage: jni.jlong) callconv(.c) jni.jboolean {
    if (width <= 0 or height <= 0 or layers <= 0 or format < 0) return 0;
    const desc = HardwareBufferDesc{ .width = @intCast(width), .height = @intCast(height), .layers = @intCast(layers), .format = @intCast(format), .usage = @bitCast(usage), .stride = 0, .rfu0 = 0, .rfu1 = 0 };
    return @intFromBool(AHardwareBuffer_isSupported(&desc) != 0);
}

fn nativeHardwareBufferRelease(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    AHardwareBuffer_release(fromHandle(handle));
}

fn descriptionField(handle: jni.jlong, comptime field: enum { width, height, format, layers }) jni.jint {
    const buffer = fromHandle(handle) orelse return 0;
    return @intCast(@field(buffer.description, @tagName(field)));
}

fn nativeHardwareBufferWidth(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jint {
    return descriptionField(handle, .width);
}
fn nativeHardwareBufferHeight(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jint {
    return descriptionField(handle, .height);
}
fn nativeHardwareBufferFormat(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jint {
    return descriptionField(handle, .format);
}
fn nativeHardwareBufferLayers(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jint {
    return descriptionField(handle, .layers);
}
fn nativeHardwareBufferUsage(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) jni.jlong {
    return if (fromHandle(handle)) |buffer| @bitCast(buffer.description.usage) else 0;
}
fn nativeHardwareBufferId(handle: jni.jlong) callconv(.c) jni.jlong {
    return if (fromHandle(handle)) |buffer| @bitCast(buffer.id) else 0;
}

fn nativeHardwareBufferEstimateSize(handle: jni.jlong) callconv(.c) jni.jlong {
    const buffer = fromHandle(handle) orelse return 0;
    return @intCast(@as(u64, buffer.description.height) * buffer.byte_stride);
}

fn nativeHardwareBufferFinalizer(_: [*c]jni.JNIEnv, _: jni.jclass) callconv(.c) jni.jlong {
    return @intCast(@intFromPtr(&AHardwareBuffer_release));
}

const GraphicBufferJni = struct {
    var cls: jni.jclass = null;
    var constructor: jni.jmethodID = null;
    var native_object: jni.jfieldID = null;
    var rect_fields: [4]jni.jfieldID = @splat(null);

    const Canvas = opaque {};
    const NativeWindowBuffer = extern struct {
        width: i32,
        height: i32,
        stride: i32,
        format: i32,
        bits: ?*anyopaque,
        reserved: [6]u32 = @splat(0),
    };
    extern "c" fn ACanvas_getNativeHandleFromJava(env: [*c]jni.JNIEnv, canvas: jni.jobject) ?*Canvas;
    extern "c" fn ACanvas_setBuffer(canvas: ?*Canvas, buffer: ?*const NativeWindowBuffer, dataspace: i32) bool;
    extern "c" fn ACanvas_clipRect(canvas: ?*Canvas, rect: *const Rect, anti_alias: bool) void;

    fn create(env: [*c]jni.JNIEnv, clazz: jni.jclass, width: jni.jint, height: jni.jint, format: jni.jint, usage: jni.jint) callconv(.c) jni.jlong {
        return nativeHardwareBufferCreate(env, clazz, width, height, format, 1, @as(u32, @bitCast(usage)));
    }

    fn fromHardwareBuffer(env: [*c]jni.JNIEnv, _: jni.jclass, object: jni.jobject) callconv(.c) jni.jobject {
        const buffer = azk_hardware_buffer_get_native(env, object) orelse return null;
        const desc = buffer.description;
        const arguments = [_]jni.jvalue{
            .{ .i = @intCast(desc.width) },                    .{ .i = @intCast(desc.height) },         .{ .i = @intCast(desc.format) },
            .{ .i = HardwareBufferTransport.low(desc.usage) }, .{ .j = @intCast(@intFromPtr(buffer)) },
        };
        AHardwareBuffer_acquire(buffer);
        const result = env.*.*.NewObjectA.?(env, cls, constructor, &arguments);
        if (result == null) AHardwareBuffer_release(buffer);
        return result;
    }

    fn toHardwareBuffer(env: [*c]jni.JNIEnv, _: jni.jclass, object: jni.jobject) callconv(.c) jni.jlong {
        if (object == null) return 0;
        const handle = env.*.*.GetLongField.?(env, object, native_object);
        const buffer = fromHandle(handle) orelse return 0;
        AHardwareBuffer_acquire(buffer);
        return handle;
    }

    // Pinned android_graphics_GraphicBuffer_lockCanvas/unlockCanvasAndPost,
    // with the existing AHardwareBuffer mapper replacing Android gralloc.
    // Canvas's pixel-format conversion, backing store and clipping are AOSP.
    fn lock(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, canvas_object: jni.jobject, dirty: jni.jobject) callconv(.c) jni.jboolean {
        const buffer = fromHandle(handle) orelse return 0;
        var rect = Rect{ .left = 0, .top = 0, .right = @intCast(buffer.description.width), .bottom = @intCast(buffer.description.height) };
        if (dirty != null) {
            rect = .{
                .left = env.*.*.GetIntField.?(env, dirty, rect_fields[0]),
                .top = env.*.*.GetIntField.?(env, dirty, rect_fields[1]),
                .right = env.*.*.GetIntField.?(env, dirty, rect_fields[2]),
                .bottom = env.*.*.GetIntField.?(env, dirty, rect_fields[3]),
            };
        }
        var bits: ?*anyopaque = null;
        var pixel_bytes: i32 = 0;
        var stride_bytes: i32 = 0;
        if (AHardwareBuffer_lockAndGetInfo(buffer, 3 | (3 << 4), -1, &rect, &bits, &pixel_bytes, &stride_bytes) != 0) return 0;
        const canvas = ACanvas_getNativeHandleFromJava(env, canvas_object);
        if (canvas == null or bits == null) {
            _ = AHardwareBuffer_unlock(buffer, null);
            return 0;
        }
        const target = NativeWindowBuffer{
            .width = @intCast(buffer.description.width),
            .height = @intCast(buffer.description.height),
            .stride = @divTrunc(stride_bytes, pixel_bytes),
            .format = @intCast(buffer.description.format),
            .bits = bits,
        };
        if (!ACanvas_setBuffer(canvas, &target, 0)) {
            _ = AHardwareBuffer_unlock(buffer, null);
            return 0;
        }
        ACanvas_clipRect(canvas, &rect, false);
        return 1;
    }

    fn unlock(env: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong, canvas_object: jni.jobject) callconv(.c) jni.jboolean {
        const canvas = ACanvas_getNativeHandleFromJava(env, canvas_object);
        if (canvas != null) _ = ACanvas_setBuffer(canvas, null, 0);
        return @intFromBool(AHardwareBuffer_unlock(fromHandle(handle), null) == 0);
    }

    fn register(env: [*c]jni.JNIEnv) bool {
        const local = env.*.*.FindClass.?(env, "android/graphics/GraphicBuffer") orelse return false;
        defer env.*.*.DeleteLocalRef.?(env, local);
        cls = env.*.*.NewGlobalRef.?(env, local) orelse return false;
        constructor = env.*.*.GetMethodID.?(env, cls, "<init>", "(IIIIJ)V") orelse return false;
        native_object = env.*.*.GetFieldID.?(env, cls, "mNativeObject", "J") orelse return false;
        const rect = env.*.*.FindClass.?(env, "android/graphics/Rect") orelse return false;
        defer env.*.*.DeleteLocalRef.?(env, rect);
        inline for (.{ "left", "top", "right", "bottom" }, 0..) |name, i| {
            rect_fields[i] = env.*.*.GetFieldID.?(env, rect, name, "I") orelse return false;
        }
        const methods = [_]jni.JNINativeMethod{
            .{ .name = "nCreateGraphicBuffer", .signature = "(IIII)J", .fnPtr = @ptrCast(@constCast(&create)) },
            .{ .name = "nDestroyGraphicBuffer", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferRelease)) },
            .{ .name = "nWriteGraphicBufferToParcel", .signature = "(JLandroid/os/Parcel;)V", .fnPtr = @ptrCast(@constCast(&HardwareBufferTransport.write)) },
            .{ .name = "nReadGraphicBufferFromParcel", .signature = "(Landroid/os/Parcel;)J", .fnPtr = @ptrCast(@constCast(&HardwareBufferTransport.read)) },
            .{ .name = "nLockCanvas", .signature = "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)Z", .fnPtr = @ptrCast(@constCast(&lock)) },
            .{ .name = "nUnlockCanvasAndPost", .signature = "(JLandroid/graphics/Canvas;)Z", .fnPtr = @ptrCast(@constCast(&unlock)) },
            .{ .name = "nCreateFromHardwareBuffer", .signature = "(Landroid/hardware/HardwareBuffer;)Landroid/graphics/GraphicBuffer;", .fnPtr = @ptrCast(@constCast(&fromHardwareBuffer)) },
        };
        return env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) == 0;
    }
};

pub export fn azk_register_hardware_buffer(env: [*c]jni.JNIEnv) callconv(.c) c_int {
    const cls = env.*.*.FindClass.?(env, "android/hardware/HardwareBuffer") orelse return -1;
    if (!HardwareBufferTransport.register(env) or !GraphicBufferJni.register(env)) return -1;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nCreateHardwareBuffer", .signature = "(IIIIJ)J", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferCreate)) },
        .{ .name = "nIsSupported", .signature = "(IIIIJ)Z", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferIsSupported)) },
        .{ .name = "nCreateFromGraphicBuffer", .signature = "(Landroid/graphics/GraphicBuffer;)J", .fnPtr = @ptrCast(@constCast(&GraphicBufferJni.toHardwareBuffer)) },
        .{ .name = "nGetNativeFinalizer", .signature = "()J", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferFinalizer)) },
        .{ .name = "nEstimateSize", .signature = "(J)J", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferEstimateSize)) },
        .{ .name = "nWriteHardwareBufferToParcel", .signature = "(JLandroid/os/Parcel;)V", .fnPtr = @ptrCast(@constCast(&HardwareBufferTransport.write)) },
        .{ .name = "nReadHardwareBufferFromParcel", .signature = "(Landroid/os/Parcel;)J", .fnPtr = @ptrCast(@constCast(&HardwareBufferTransport.read)) },
        .{ .name = "nGetWidth", .signature = "(J)I", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferWidth)) },
        .{ .name = "nGetHeight", .signature = "(J)I", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferHeight)) },
        .{ .name = "nGetFormat", .signature = "(J)I", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferFormat)) },
        .{ .name = "nGetLayers", .signature = "(J)I", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferLayers)) },
        .{ .name = "nGetUsage", .signature = "(J)J", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferUsage)) },
        .{ .name = "nGetId", .signature = "(J)J", .fnPtr = @ptrCast(@constCast(&nativeHardwareBufferId)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return -1;
    hardware_buffer_class = env.*.*.NewGlobalRef.?(env, cls);
    if (hardware_buffer_class == null) return -1;
    hardware_buffer_native_object = env.*.*.GetFieldID.?(env, hardware_buffer_class, "mNativeObject", "J");
    hardware_buffer_constructor = env.*.*.GetMethodID.?(env, hardware_buffer_class, "<init>", "(J)V");
    return if (hardware_buffer_native_object != null and hardware_buffer_constructor != null) 0 else -1;
}

pub export fn azk_hardware_buffer_get_native(env: [*c]jni.JNIEnv, object: jni.jobject) callconv(.c) ?*HardwareBuffer {
    if (object == null or hardware_buffer_class == null or hardware_buffer_native_object == null) return null;
    if (env.*.*.IsInstanceOf.?(env, object, hardware_buffer_class) == 0) return null;
    return fromHandle(env.*.*.GetLongField.?(env, object, hardware_buffer_native_object));
}

pub export fn azk_hardware_buffer_create_java(env: [*c]jni.JNIEnv, buffer: ?*HardwareBuffer) callconv(.c) jni.jobject {
    const value = buffer orelse return null;
    if (hardware_buffer_class == null or hardware_buffer_constructor == null) return null;
    AHardwareBuffer_acquire(value);
    const arguments = [_]jni.jvalue{.{ .j = @intCast(@intFromPtr(value)) }};
    const object = env.*.*.NewObjectA.?(env, hardware_buffer_class, hardware_buffer_constructor, &arguments);
    if (object == null) AHardwareBuffer_release(value);
    return object;
}

pub export fn AHardwareBuffer_fromHardwareBuffer(env: [*c]jni.JNIEnv, object: jni.jobject) callconv(.c) ?*HardwareBuffer {
    return azk_hardware_buffer_get_native(env, object);
}

pub export fn AHardwareBuffer_toHardwareBuffer(env: [*c]jni.JNIEnv, buffer: ?*HardwareBuffer) callconv(.c) jni.jobject {
    return azk_hardware_buffer_create_java(env, buffer);
}

test "SQLite fallback collation is byte-wise and length-aware" {
    try std.testing.expectEqual(@as(c_int, -1), compareBytes(null, 2, "ab", 2, "ac"));
    try std.testing.expectEqual(@as(c_int, -1), compareBytes(null, 2, "ab", 3, "abc"));
    try std.testing.expectEqual(@as(c_int, 0), compareBytes(null, 2, "ab", 2, "ab"));
}

test "hardware buffer formats map to the expected byte widths" {
    try std.testing.expectEqual(@as(u32, 4), formatInfo(1).?.bytes_per_pixel);
    try std.testing.expectEqual(@as(u32, 2), formatInfo(4).?.bytes_per_pixel);
    try std.testing.expect(formatInfo(3) == null);
}

comptime {
    _ = @import("legacy_gles.zig");
}

// bionic android_dlopen_ext: ordinary shared objects use the host ELF loader.
// Android namespace/FD/RELRO extensions have no host equivalent and fail explicitly.
// No descriptor is borrowed or consumed when rejecting an extension.
extern "c" fn dlopen(filename: ?[*:0]const u8, flags: c_int) ?*anyopaque;
const AndroidDlextInfo = extern struct {
    flags: u64,
    reserved_addr: ?*anyopaque,
    reserved_size: usize,
    relro_fd: c_int,
    library_fd: c_int,
    library_fd_offset: i64,
    library_namespace: ?*anyopaque,
};
pub export fn android_dlopen_ext(filename: ?[*:0]const u8, flags: c_int, info: ?*const AndroidDlextInfo) callconv(.c) ?*anyopaque {
    if (info) |extension| {
        if (extension.flags != 0) {
            __errno_location().* = @backingInt(std.os.linux.E.OPNOTSUPP);
            return null;
        }
    }
    return dlopen(filename, flags);
}
