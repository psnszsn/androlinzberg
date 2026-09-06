//! EGL display ownership and DMA-BUF import for stock graphics consumers.
//! Mesa does not reference-count eglInitialize. Keep AOSP's balanced image,
//! probe and context lifetimes in the shared Zig graphics backend.
const std = @import("std");
const graphics = @import("graphics_buffer.zig");
const Display = ?*anyopaque;
const Image = ?*anyopaque;
extern "c" fn eglInitialize(Display, *c_int, *c_int) c_uint;
extern "c" fn eglTerminate(Display) c_uint;
extern "c" fn eglQueryString(Display, c_int) ?[*:0]const u8;
extern "c" fn eglGetProcAddress([*:0]const u8) ?*const fn () callconv(.c) void;
const DisplayReference = struct { display: Display = null, count: u64 = 0, major: c_int = 0, minor: c_int = 0 };
var mutex: std.Io.Mutex = .init;
var displays: [16]DisplayReference = @splat(.{});

export fn azk_graphics_egl_initialize(display: Display, major: ?*c_int, minor: ?*c_int) c_uint {
    if (display == null) return 0;
    const io = graphics.activeIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    var slot: ?*DisplayReference = null;
    for (&displays) |*candidate| {
        if (candidate.display == display) {
            slot = candidate;
            break;
        }
        if (slot == null and candidate.count == 0) slot = candidate;
    }
    const entry = slot orelse return 0;
    if (entry.count == std.math.maxInt(u64)) return 0;
    if (entry.count == 0) {
        if (eglInitialize(display, &entry.major, &entry.minor) == 0) return 0;
        entry.display = display;
    }
    entry.count += 1;
    if (major) |value| value.* = entry.major;
    if (minor) |value| value.* = entry.minor;
    return 1;
}
export fn azk_graphics_egl_terminate(display: Display) c_uint {
    const io = graphics.activeIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    for (&displays) |*entry| {
        if (entry.display != display or entry.count == 0) continue;
        entry.count -= 1;
        if (entry.count != 0) return 1;
        entry.display = null;
        return eglTerminate(display);
    }
    return 0;
}
export fn azk_graphics_egl_display() Display {
    const device = graphics.azk_graphics_gbm_device() orelse return null;
    const get: *const fn (c_uint, *anyopaque, ?[*]const c_int) callconv(.c) Display =
        @ptrCast(eglGetProcAddress("eglGetPlatformDisplayEXT") orelse return null);
    return get(0x31d7, @ptrCast(device), null); // EGL_PLATFORM_GBM_KHR
}
fn extension(list: ?[*:0]const u8, name: []const u8) bool {
    var tokens = std.mem.tokenizeScalar(u8, std.mem.span(list orelse return false), ' ');
    while (tokens.next()) |token| if (std.mem.eql(u8, token, name)) return true;
    return false;
}
export fn azk_graphics_import_egl_image(display: Display, handle: ?*const graphics.Handle, output: ?*Image) c_int {
    const result = output orelse return -22;
    result.* = null;
    if (handle == null or display == null) return -22;
    var info: graphics.Info = undefined;
    if (graphics.azk_graphics_describe(handle.?, &info) != 0) return -22;
    if (info.usage & (1 << 14) != 0) return -1; // No protected storage on this host.
    const extensions = eglQueryString(display, 0x3055); // EGL_EXTENSIONS
    if (!extension(extensions, "EGL_EXT_image_dma_buf_import")) return -38;
    const modifiers = extension(extensions, "EGL_EXT_image_dma_buf_import_modifiers");
    const implicit = (1 << 56) - 1; // DRM_FORMAT_MOD_INVALID
    // An explicit tiled allocation must never be reinterpreted as implicit.
    if (info.modifier != implicit and info.modifier != 0 and !modifiers) return -38;
    const create: *const fn (Display, ?*anyopaque, c_uint, ?*anyopaque, [*]const c_int) callconv(.c) Image =
        @ptrCast(eglGetProcAddress("eglCreateImageKHR") orelse return -38);
    var attributes = [_]c_int{
        0x3057, @intCast(info.width), // EGL_WIDTH
        0x3056, @intCast(info.height), // EGL_HEIGHT
        0x3271, @bitCast(info.drm_format), // EGL_LINUX_DRM_FOURCC_EXT
        0x3272, info.fd, // EGL_DMA_BUF_PLANE0_FD_EXT
        0x3273, @intCast(info.offset), // EGL_DMA_BUF_PLANE0_OFFSET_EXT
        0x3274, @intCast(info.stride), // EGL_DMA_BUF_PLANE0_PITCH_EXT
        0x3038, 0, 0, 0, 0x3038, // EGL_NONE or the explicit modifier pair.
    };
    if (modifiers and info.modifier != implicit) {
        attributes[12] = 0x3443; // EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT
        attributes[13] = @bitCast(@as(u32, @truncate(info.modifier)));
        attributes[14] = 0x3444; // EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT
        attributes[15] = @bitCast(@as(u32, @truncate(info.modifier >> 32)));
    }
    result.* = create(display, null, 0x3270, null, &attributes); // EGL_LINUX_DMA_BUF_EXT
    return if (result.* != null) 0 else -22;
}
