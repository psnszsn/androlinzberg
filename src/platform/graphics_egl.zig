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
    return azk_graphics_egl_release(display, null);
}
// Return the remaining ownership count in the same critical section as release.
// Java EGL must only discard its emulated pbuffers when the shared display dies.
export fn azk_graphics_egl_release(display: Display, remaining: ?*u64) c_uint {
    const io = graphics.activeIo();
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    for (&displays) |*entry| {
        if (entry.display != display or entry.count == 0) continue;
        entry.count -= 1;
        if (remaining) |value| value.* = entry.count;
        if (entry.count != 0) return 1;
        entry.display = null;
        const result = eglTerminate(display);
        if (result != 0) {
            while (true) {
                var images = native_images.iterator();
                var removed = false;
                while (images.next()) |image_entry| if (image_entry.value_ptr.display == display) {
                    const retained = image_entry.value_ptr.*;
                    _ = native_images.remove(image_entry.key_ptr.*);
                    retained.release(retained.buffer);
                    removed = true;
                    break;
                };
                if (!removed) break;
            }
        }
        return result;
    }
    if (remaining) |value| value.* = 0;
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

const Io = std.Io;
const Handle = Display;
const alloc = std.heap.c_allocator;
const none = 0x3038;
const success = 0x3000;
const bad_alloc = 0x3003;
const bad_parameter = 0x300c;
threadlocal var host_error: i32 = success;
extern "c" fn eglGetError() i32;
extern fn azk_egl_native_client_buffer(Handle) callconv(.c) Handle;
extern fn azk_egl_native_buffer_handle(Handle) callconv(.c) Handle;
extern fn azk_egl_native_buffer_acquire(Handle) callconv(.c) void;
extern fn azk_egl_native_buffer_release(Handle) callconv(.c) void;
const NativeImage = struct { display: Handle, buffer: Handle, release: *const fn (Handle) callconv(.c) void };
var native_images: std.AutoHashMapUnmanaged(usize, NativeImage) = .empty;
fn fail(code: i32) void {
    _ = eglGetError();
    host_error = code;
}
export fn azk_graphics_egl_get_error() i32 {
    const native = eglGetError();
    const saved = host_error;
    host_error = success;
    return if (native != success) native else saved;
}

export fn azk_graphics_egl_error(code: i32) void {
    fail(code);
}

// Pinned eglGetNativeClientBufferANDROIDImpl delegates to the original
// AHardwareBuffer conversion. The EGL driver imports its existing DMA-BUF,
// retaining the Android buffer for every associated EGLImage's lifetime.
export fn azk_graphics_egl_native_client_buffer(buffer: Handle) Handle {
    if (buffer == null) {
        fail(bad_parameter);
        return null;
    }
    return azk_egl_native_client_buffer(buffer);
}

fn createImage(io: Io, comptime Attribute: type, display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const Attribute) Handle {
    const Create = *const fn (Handle, Handle, u32, Handle, ?[*]const Attribute) callconv(.c) Handle;
    const create: Create = @ptrCast(eglGetProcAddress(if (Attribute == i32) "eglCreateImageKHR" else "eglCreateImage") orelse {
        fail(bad_parameter);
        return null;
    });
    if (target != 0x3140) return create(display, context, target, buffer, attributes);
    // Publish the image and its Android reference atomically with respect to
    // display termination, which destroys the driver's remaining images.
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (eglQueryString(display, 0x3054) == null) return null; // EGL_VERSION validates the display.
    if (context != null) {
        fail(0x3006); // EGL_BAD_CONTEXT
        return null;
    }
    const handle = azk_egl_native_buffer_handle(buffer) orelse {
        fail(bad_parameter);
        return null;
    };
    // ANDROID_image_native_buffer ignores attributes other than
    // EGL_IMAGE_PRESERVED_KHR. DMA-BUF import always preserves the contents.
    if (attributes) |attrs| {
        var index: usize = 0;
        while (attrs[index] != none) : (index += 2) {
            if (attrs[index] == 0x30d2 and attrs[index + 1] != 0 and attrs[index + 1] != 1) {
                fail(bad_parameter);
                return null;
            }
        }
    }
    azk_egl_native_buffer_acquire(buffer);
    var image: Handle = null;
    if (azk_graphics_import_egl_image(display, @ptrCast(@alignCast(handle)), &image) != 0) {
        azk_egl_native_buffer_release(buffer);
        fail(bad_parameter);
        return null;
    }
    native_images.put(alloc, @intFromPtr(image), .{ .display = display, .buffer = buffer, .release = &azk_egl_native_buffer_release }) catch {
        const destroy: *const fn (Handle, Handle) callconv(.c) u32 = @ptrCast(eglGetProcAddress("eglDestroyImageKHR").?);
        _ = destroy(display, image);
        azk_egl_native_buffer_release(buffer);
        fail(bad_alloc);
        return null;
    };
    return image;
}

export fn azk_graphics_egl_destroy_image(display: Handle, image: Handle) u32 {
    const io = graphics.activeIo();
    const destroy: *const fn (Handle, Handle) callconv(.c) u32 = @ptrCast(eglGetProcAddress("eglDestroyImageKHR") orelse {
        fail(bad_parameter);
        return 0;
    });
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (destroy(display, image) == 0) return 0;
    if (native_images.fetchRemove(@intFromPtr(image))) |entry| {
        entry.value.release(entry.value.buffer);
    }
    return 1;
}
export fn azk_graphics_egl_create_image_khr(display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const i32) Handle {
    return createImage(graphics.activeIo(), i32, display, context, target, buffer, attributes);
}
export fn azk_graphics_egl_create_image(display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const isize) Handle {
    return createImage(graphics.activeIo(), isize, display, context, target, buffer, attributes);
}
