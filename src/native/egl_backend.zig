//! EGL platform seam for stock EGL14 and legacy EGL clients. The pinned Android EGL
//! entry points delegate configuration/surface ownership to the platform driver.
//! Mesa GBM offers window configs but no pbuffers; provide offscreen drawables
//! using GBM buffers, preserving pbuffer dimensions, no-op swaps, and deferred
//! destruction. This owns no Wayland surface or presentation connection.
const std = @import("std");
const Io = std.Io;
pub const Handle = ?*anyopaque;
const alloc = std.heap.c_allocator;
const none = 0x3038;
const surface_type = 0x3033;
const window_bit = 4;
const pbuffer_bit = 1;
const width_key = 0x3057;
const height_key = 0x3056;
const largest_key = 0x3058;
const texture_format = 0x3080;
const texture_target = 0x3081;
const mipmap_texture = 0x3082;
const no_texture = 0x305c;
const success = 0x3000;
const bad_alloc = 0x3003;
const bad_attribute = 0x3004;
const bad_match = 0x3009;
const bad_parameter = 0x300c;
const bad_surface = 0x300d;
const bad_native_window = 0x300b;

extern "EGL" fn eglGetPlatformDisplay(u32, Handle, ?[*]const isize) Handle;
extern "EGL" fn eglGetError() i32;
extern "EGL" fn eglChooseConfig(Handle, ?[*]const i32, ?[*]Handle, i32, *i32) u32;
extern "EGL" fn eglGetConfigAttrib(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglCreatePbufferSurface(Handle, Handle, ?[*]const i32) Handle;
extern "EGL" fn eglCreateWindowSurface(Handle, Handle, Handle, ?[*]const i32) Handle;
extern "EGL" fn eglDestroySurface(Handle, Handle) u32;
extern "EGL" fn eglQuerySurface(Handle, Handle, i32, *i32) u32;
extern "EGL" fn eglSurfaceAttrib(Handle, Handle, i32, i32) u32;
extern "EGL" fn eglSwapBuffers(Handle, Handle) u32;
extern "EGL" fn eglGetCurrentSurface(i32) Handle;
extern "EGL" fn eglGetCurrentContext() Handle;
extern "EGL" fn eglBindTexImage(Handle, Handle, i32) u32;
extern "EGL" fn eglReleaseTexImage(Handle, Handle, i32) u32;
extern fn azk_egl_window_connect(Handle, Handle, Handle, ?[*]const i32, *i32, *i32, *i32) callconv(.c) Handle;
extern fn azk_egl_window_disconnect(Handle, bool) callconv(.c) void;
extern fn azk_egl_window_present(Handle, Handle, Handle, *i32, *i32) callconv(.c) i32;
extern fn azk_egl_window_timestamp(Handle, i64) callconv(.c) i32;
extern "EGL" fn eglMakeCurrent(Handle, Handle, Handle, Handle) u32;
extern "EGL" fn eglReleaseThread() u32;
extern "EGL" fn eglInitialize(Handle, ?*i32, ?*i32) u32;
extern "EGL" fn eglTerminate(Handle) u32;
extern "EGL" fn eglQueryString(Handle, i32) ?[*:0]const u8;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;
extern fn azk_egl_native_client_buffer(Handle) callconv(.c) Handle;
extern fn azk_egl_native_buffer_handle(Handle) callconv(.c) Handle;
extern fn azk_egl_native_buffer_acquire(Handle) callconv(.c) void;
extern fn azk_egl_native_buffer_release(Handle) callconv(.c) void;
extern fn azk_graphics_import_egl_image(Handle, Handle, *Handle) callconv(.c) i32;
extern "gbm" fn gbm_surface_create(Handle, u32, u32, u32, u32) Handle;
extern "gbm" fn gbm_surface_destroy(Handle) void;

var gbm_display: std.atomic.Value(usize) = .init(0);
var gbm_device: std.atomic.Value(usize) = .init(0);
threadlocal var host_error: i32 = success;
var mutex: Io.Mutex = .init;
var surfaces: std.AutoHashMapUnmanaged(usize, Surface) = .empty;
const NativeImage = struct { display: Handle, buffer: Handle, release: *const fn (Handle) callconv(.c) void };
var native_images: std.AutoHashMapUnmanaged(usize, NativeImage) = .empty;
var extension_strings: std.AutoHashMapUnmanaged(usize, [:0]u8) = .empty;
const Display = struct { refs: i32, major: i32, minor: i32 };
var displays: std.AutoHashMapUnmanaged(usize, Display) = .empty;
const InitializeDisplay = *const fn (Handle, ?*i32, ?*i32) callconv(.c) u32;
const ReleaseDisplay = *const fn (Handle, ?*u64) callconv(.c) u32;
var shared_display_ownership: ?struct { initialize: InitializeDisplay, release: ReleaseDisplay } = null;

/// Bind the common graphics display owner before clients enter EGL.
pub fn bindDisplayOwnership(initialize_display: InitializeDisplay, release_display: ReleaseDisplay) void {
    shared_display_ownership = .{ .initialize = initialize_display, .release = release_display };
}
const Surface = struct {
    display: Handle,
    buffer: Handle,
    width: i32,
    height: i32,
    largest: i32,
    bindings: u32 = 0,
    presenting: u32 = 0,
    destroyed: bool = false,
    window: Handle = null,
    driver: Handle = null,
    config: Handle = null,
    visual: u32 = 0,
    attributes: []const i32 = &.{},
    display_alive: bool = true,
};

pub fn defaultDisplay(device: Handle) Handle {
    if (device) |ptr| {
        const display = eglGetPlatformDisplay(0x31d7, ptr, null); // EGL_PLATFORM_GBM_KHR
        gbm_device.store(@intFromPtr(ptr), .release);
        gbm_display.store(@intFromPtr(display), .release);
        return display;
    }
    return eglGetPlatformDisplay(0x31dd, null, null); // EGL_PLATFORM_SURFACELESS_MESA
}
fn isGbm(display: Handle) bool {
    return display != null and @intFromPtr(display) == gbm_display.load(.acquire);
}
fn fail(code: i32) void {
    _ = eglGetError();
    host_error = code;
}
pub fn getError() i32 {
    const native = eglGetError();
    const saved = host_error;
    host_error = success;
    return if (native != success) native else saved;
}

pub fn queryString(io: Io, display: Handle, name: i32) ?[*:0]const u8 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const native = eglQueryString(display, name) orelse return null;
    if (display == null or name != 0x3055) return native; // EGL_EXTENSIONS
    const extensions = std.mem.span(native);
    if (!hasExtension(extensions, "EGL_EXT_image_dma_buf_import")) return native;
    if (extension_strings.get(@intFromPtr(display))) |cached| return cached.ptr;
    const combined = std.fmt.allocPrintSentinel(alloc, "{s}{s}{s}{s}", .{
        extensions,
        if (!isGbm(display) or hasExtension(extensions, "EGL_ANDROID_presentation_time")) "" else " EGL_ANDROID_presentation_time",
        if (hasExtension(extensions, "EGL_ANDROID_image_native_buffer")) "" else " EGL_ANDROID_image_native_buffer",
        if (hasExtension(extensions, "EGL_ANDROID_get_native_client_buffer")) "" else " EGL_ANDROID_get_native_client_buffer",
    }, 0) catch {
        fail(bad_alloc);
        return null;
    };
    extension_strings.put(alloc, @intFromPtr(display), combined) catch {
        alloc.free(combined);
        fail(bad_alloc);
        return null;
    };
    return combined.ptr;
}

fn hasExtension(extensions: []const u8, name: []const u8) bool {
    var tokens = std.mem.tokenizeScalar(u8, extensions, ' ');
    while (tokens.next()) |token| if (std.mem.eql(u8, token, name)) return true;
    return false;
}

// Pinned eglGetNativeClientBufferANDROIDImpl delegates to the original
// AHardwareBuffer conversion. The EGL driver imports its existing DMA-BUF,
// retaining the Android buffer for every associated EGLImage's lifetime.
pub fn nativeClientBuffer(buffer: Handle) Handle {
    if (buffer == null) {
        fail(bad_parameter);
        return null;
    }
    return azk_egl_native_client_buffer(buffer);
}

pub fn createImage(io: Io, comptime Attribute: type, display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const Attribute) Handle {
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
    if (azk_graphics_import_egl_image(display, handle, &image) != 0) {
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

pub fn destroyImage(io: Io, display: Handle, image: Handle) u32 {
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
pub fn chooseConfig(display: Handle, attributes: ?[*]const i32, configs: ?[*]Handle, size: i32, count: *i32) u32 {
    if (!isGbm(display) or attributes == null) return eglChooseConfig(display, attributes, configs, size, count);
    const attrs = attributes.?;
    var length: usize = 0;
    var translate = false;
    while (attrs[length] != none) : (length += 2) {
        if (attrs[length] == surface_type and attrs[length + 1] != -1 and attrs[length + 1] & pbuffer_bit != 0) translate = true;
    }
    if (!translate) return eglChooseConfig(display, attributes, configs, size, count);
    const native = alloc.dupe(i32, attrs[0 .. length + 1]) catch {
        fail(bad_alloc);
        return 0;
    };
    defer alloc.free(native);
    var i: usize = 0;
    while (i < length) : (i += 2) {
        if (native[i] == surface_type and native[i + 1] != -1 and native[i + 1] & pbuffer_bit != 0)
            native[i + 1] = (native[i + 1] & ~@as(i32, pbuffer_bit)) | window_bit;
    }
    return eglChooseConfig(display, native.ptr, configs, size, count);
}
pub fn getConfigAttrib(display: Handle, config: Handle, attribute: i32, value: *i32) u32 {
    const result = eglGetConfigAttrib(display, config, attribute, value);
    if (result != 0 and isGbm(display)) {
        if (attribute == surface_type and value.* & window_bit != 0) value.* |= pbuffer_bit;
        // GBM has no native pbuffers, but its drawable dimension limits apply.
        if (attribute == 0x302b) { // EGL_MAX_PBUFFER_PIXELS
            var width: i32 = 0;
            var height: i32 = 0;
            if (eglGetConfigAttrib(display, config, 0x302c, &width) != 0 and eglGetConfigAttrib(display, config, 0x302a, &height) != 0)
                value.* = @intCast(@min(@as(i64, width) * height, std.math.maxInt(i32)));
        }
    }
    return result;
}
pub fn createPbuffer(io: Io, display: Handle, config: Handle, attributes: ?[*]const i32) Handle {
    if (!isGbm(display)) return eglCreatePbufferSurface(display, config, attributes);
    var visual: i32 = 0;
    var types: i32 = 0;
    var max_width: i32 = 0;
    var max_height: i32 = 0;
    if (eglGetConfigAttrib(display, config, 0x302e, &visual) == 0 or
        eglGetConfigAttrib(display, config, surface_type, &types) == 0 or
        eglGetConfigAttrib(display, config, 0x302c, &max_width) == 0 or
        eglGetConfigAttrib(display, config, 0x302a, &max_height) == 0) return null;
    if (types & window_bit == 0) {
        fail(bad_match);
        return null;
    }
    var width: i32 = 0;
    var height: i32 = 0;
    var largest: i32 = 0;
    var format: i32 = no_texture;
    var target: i32 = no_texture;
    var mipmap: i32 = 0;
    if (attributes) |attrs| {
        var i: usize = 0;
        while (attrs[i] != none) : (i += 2) switch (attrs[i]) {
            width_key => width = attrs[i + 1],
            height_key => height = attrs[i + 1],
            largest_key => largest = attrs[i + 1],
            texture_format => format = attrs[i + 1],
            texture_target => target = attrs[i + 1],
            mipmap_texture => mipmap = attrs[i + 1],
            else => {
                fail(bad_attribute);
                return null;
            },
        };
    }
    if (width < 0 or height < 0) {
        fail(bad_parameter);
        return null;
    }
    if ((largest != 0 and largest != 1) or (mipmap != 0 and mipmap != 1) or
        (format != no_texture and format != 0x305d and format != 0x305e) or
        (target != no_texture and target != 0x305f))
    {
        fail(bad_attribute);
        return null;
    }
    // GBM configs report EGL_BIND_TO_TEXTURE_{RGB,RGBA}=false. Preserve that
    // limitation; these buffers are drawables, not EGL texture-binding images.
    if (format != no_texture or target != no_texture) {
        fail(bad_match);
        return null;
    }
    if (width > max_width or height > max_height) {
        if (largest == 0) {
            fail(bad_alloc);
            return null;
        }
        width = @min(width, max_width);
        height = @min(height, max_height);
    }
    const buffer = gbm_surface_create(@ptrFromInt(gbm_device.load(.acquire)), @intCast(@max(width, 1)), @intCast(@max(height, 1)), @bitCast(visual), 4) orelse {
        fail(bad_alloc);
        return null;
    };
    const surface = eglCreateWindowSurface(display, config, buffer, null) orelse {
        gbm_surface_destroy(buffer);
        return null;
    };
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    surfaces.put(alloc, @intFromPtr(surface), .{ .display = display, .buffer = buffer, .width = width, .height = height, .largest = largest }) catch {
        _ = eglDestroySurface(display, surface);
        gbm_surface_destroy(buffer);
        fail(bad_alloc);
        return null;
    };
    return surface;
}
// Window handles remain stable while their Mesa drawable is replaced on resize.
// These translations run under mutex and leave foreign/native HWUI surfaces alone.
fn driverSurface(surface: Handle) Handle {
    if (surfaces.get(@intFromPtr(surface))) |owned| if (owned.window != null) return owned.driver;
    return surface;
}
fn publicSurface(driver: Handle) Handle {
    var entries = surfaces.iterator();
    while (entries.next()) |entry| if (entry.value_ptr.window != null and entry.value_ptr.driver == driver)
        return @ptrFromInt(entry.key_ptr.*);
    return driver;
}
pub fn currentSurface(io: Io, which: i32) Handle {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    return publicSurface(eglGetCurrentSurface(which));
}
pub fn bindTexImage(io: Io, display: Handle, surface: Handle, buffer: i32, bind: bool) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    return if (bind) eglBindTexImage(display, driverSurface(surface), buffer) else eglReleaseTexImage(display, driverSurface(surface), buffer);
}
pub fn createWindow(io: Io, display: Handle, config: Handle, native: Handle, attributes: ?[*]const i32) Handle {
    if (eglQueryString(display, 0x3054) == null) return null; // Validate EGL_VERSION first.
    if (!isGbm(display)) {
        fail(bad_native_window);
        return null;
    }
    var visual: i32 = 0;
    var types: i32 = 0;
    if (eglGetConfigAttrib(display, config, 0x302e, &visual) == 0 or
        eglGetConfigAttrib(display, config, surface_type, &types) == 0) return null;
    if (types & window_bit == 0) {
        fail(bad_match);
        return null;
    }
    var length: usize = 0;
    if (attributes) |attrs| while (attrs[length] != none) : (length += 2) {};
    const attrs = alloc.dupe(i32, if (attributes) |list| list[0 .. length + 1] else &.{none}) catch {
        fail(bad_alloc);
        return null;
    };
    var committed = false;
    defer if (!committed) alloc.free(attrs);
    var width: i32 = 0;
    var height: i32 = 0;
    var window_error: i32 = bad_native_window;
    const window = azk_egl_window_connect(native, display, config, attrs.ptr, &width, &height, &window_error) orelse {
        fail(window_error);
        return null;
    };
    defer if (!committed) azk_egl_window_disconnect(window, true);
    const buffer = gbm_surface_create(@ptrFromInt(gbm_device.load(.acquire)), @intCast(width), @intCast(height), @bitCast(visual), 4) orelse {
        fail(bad_alloc);
        return null;
    };
    defer if (!committed) gbm_surface_destroy(buffer);
    const driver = eglCreateWindowSurface(display, config, buffer, attrs.ptr) orelse return null;
    defer if (!committed) {
        _ = eglDestroySurface(display, driver);
    };
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    surfaces.put(alloc, @intFromPtr(window), .{
        .display = display,
        .buffer = buffer,
        .width = width,
        .height = height,
        .largest = 0,
        .window = window,
        .driver = driver,
        .config = config,
        .visual = @bitCast(visual),
        .attributes = attrs,
    }) catch {
        fail(bad_alloc);
        return null;
    };
    committed = true;
    return window;
}
fn resizeWindow(owned: *Surface, width: i32, height: i32) bool {
    const draw = eglGetCurrentSurface(0x3059);
    const read = eglGetCurrentSurface(0x305a);
    if (draw != owned.driver and read != owned.driver) {
        fail(0x3002);
        return false;
    } // EGL_BAD_ACCESS
    const buffer = gbm_surface_create(@ptrFromInt(gbm_device.load(.acquire)), @intCast(width), @intCast(height), owned.visual, 4) orelse {
        fail(bad_alloc);
        return false;
    };
    const driver = eglCreateWindowSurface(owned.display, owned.config, buffer, owned.attributes.ptr) orelse {
        gbm_surface_destroy(buffer);
        return false;
    };
    if (eglMakeCurrent(owned.display, if (draw == owned.driver) driver else draw, if (read == owned.driver) driver else read, eglGetCurrentContext()) == 0) {
        _ = eglDestroySurface(owned.display, driver);
        gbm_surface_destroy(buffer);
        return false;
    }
    _ = eglDestroySurface(owned.display, owned.driver);
    gbm_surface_destroy(owned.buffer);
    owned.driver = driver;
    owned.buffer = buffer;
    owned.width = width;
    owned.height = height;
    return true;
}
pub fn querySurface(io: Io, display: Handle, surface: Handle, attribute: i32, value: *i32) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const owned = surfaces.get(@intFromPtr(surface)) orelse return eglQuerySurface(display, surface, attribute, value);
    // Validate through EGL even for properties owned here: a deleted or foreign
    // surface must not become valid merely because its GBM buffer is still bound.
    var ignored: i32 = 0;
    if (eglQuerySurface(display, driverSurface(surface), width_key, &ignored) == 0) return 0;
    value.* = switch (attribute) {
        width_key => owned.width,
        height_key => owned.height,
        largest_key => owned.largest,
        texture_format, texture_target => no_texture,
        mipmap_texture, 0x3083 => 0, // EGL_MIPMAP_LEVEL
        else => return eglQuerySurface(display, driverSurface(surface), attribute, value),
    };
    return 1;
}
pub fn surfaceAttrib(io: Io, display: Handle, surface: Handle, attribute: i32, value: i32) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const owned = surfaces.get(@intFromPtr(surface)) orelse return eglSurfaceAttrib(display, surface, attribute, value);
    if (owned.window != null) return eglSurfaceAttrib(display, owned.driver, attribute, value);
    var ignored: i32 = 0;
    if (eglQuerySurface(display, driverSurface(surface), width_key, &ignored) == 0) return 0;
    // EGL_MIPMAP_LEVEL is ignored for surfaces without a texture.
    if (attribute == 0x3083) return 1;
    return eglSurfaceAttrib(display, surface, attribute, value);
}
pub fn swapBuffers(io: Io, display: Handle, surface: Handle) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const owned = surfaces.getPtr(@intFromPtr(surface)) orelse return eglSwapBuffers(display, surface);
    if (owned.window) |window| {
        if (owned.destroyed) {
            fail(bad_surface);
            return 0;
        }
        // Dequeue may wait for a consumer that needs EGL on another thread.
        // Keep the window alive, but never hold the display map lock across it.
        owned.presenting += 1;
        const driver = owned.driver;
        const buffer = owned.buffer;
        var width = owned.width;
        var height = owned.height;
        mutex.unlock(io);
        const swapped = eglSwapBuffers(display, driver);
        const status = if (swapped != 0) azk_egl_window_present(window, display, buffer, &width, &height) else success;
        mutex.lockUncancelable(io);
        const live = surfaces.getPtr(@intFromPtr(surface)).?;
        live.presenting -= 1;
        defer collect(surface);
        if (swapped == 0) return 0;
        if (status != success) {
            fail(status);
            return 0;
        }
        if (!live.destroyed and (width != live.width or height != live.height) and !resizeWindow(live, width, height)) return 0;
        return 1;
    }
    var ignored: i32 = 0;
    // EGL defines swapping a pbuffer as having no effect. Do not rotate GBM's
    // window queue, which would both lose pbuffer contents and require a consumer.
    return eglQuerySurface(display, surface, width_key, &ignored);
}
fn collect(handle: Handle) void {
    if (surfaces.get(@intFromPtr(handle))) |owned| if (owned.destroyed and owned.bindings == 0 and owned.presenting == 0) {
        gbm_surface_destroy(owned.buffer);
        if (owned.window) |window| {
            azk_egl_window_disconnect(window, owned.display_alive);
            alloc.free(owned.attributes);
        }
        _ = surfaces.remove(@intFromPtr(handle));
    };
}
pub fn destroySurface(io: Io, display: Handle, surface: Handle) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const result = eglDestroySurface(display, driverSurface(surface));
    if (result != 0) if (surfaces.getPtr(@intFromPtr(surface))) |owned| {
        owned.destroyed = true;
        collect(surface);
    };
    return result;
}
fn releaseBindings(old: [2]Handle) void {
    for (old) |handle| {
        if (surfaces.getPtr(@intFromPtr(handle))) |owned| {
            std.debug.assert(owned.bindings > 0);
            owned.bindings -= 1;
        }
        collect(handle);
    }
}
pub fn makeCurrent(io: Io, display: Handle, draw: Handle, read: Handle, context: Handle) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const old = [2]Handle{ publicSurface(eglGetCurrentSurface(0x3059)), publicSurface(eglGetCurrentSurface(0x305a)) };
    const result = eglMakeCurrent(display, driverSurface(draw), driverSurface(read), context);
    if (result != 0) {
        for ([_]Handle{ draw, read }) |handle| if (surfaces.getPtr(@intFromPtr(handle))) |owned| {
            owned.bindings += 1;
        };
        releaseBindings(old);
    }
    return result;
}
pub fn releaseThread(io: Io) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const old = [2]Handle{ publicSurface(eglGetCurrentSurface(0x3059)), publicSurface(eglGetCurrentSurface(0x305a)) };
    const result = eglReleaseThread();
    if (result != 0) releaseBindings(old);
    return result;
}
// Pinned egl_display_t::initialize/terminate reference-count Android client
// initializations. Serialize the driver transition under the existing Io mutex
// so repeated initializers see the established version and only the last
// terminator destroys display resources. GLVND itself does not count these calls.
pub fn initialize(io: Io, display: Handle, major: ?*i32, minor: ?*i32) u32 {
    if (display == null) {
        fail(0x3008); // EGL_BAD_DISPLAY
        return 0;
    }
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    const entry = displays.getOrPut(alloc, @intFromPtr(display)) catch {
        fail(bad_alloc);
        return 0;
    };
    if (!entry.found_existing) {
        var version_major: i32 = 0;
        var version_minor: i32 = 0;
        const initialize_display = if (shared_display_ownership) |owner| owner.initialize else &eglInitialize;
        if (initialize_display(display, &version_major, &version_minor) == 0) {
            _ = displays.remove(@intFromPtr(display));
            return 0;
        }
        entry.value_ptr.* = .{ .refs = 0, .major = version_major, .minor = version_minor };
    }
    entry.value_ptr.refs += 1;
    if (major) |value| value.* = entry.value_ptr.major;
    if (minor) |value| value.* = entry.value_ptr.minor;
    return 1;
}
pub fn initCount(io: Io, display: Handle) i32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    return if (displays.get(@intFromPtr(display))) |state| state.refs else 0;
}
pub fn terminate(io: Io, display: Handle) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (displays.getPtr(@intFromPtr(display))) |state| {
        state.refs -= 1;
        if (state.refs > 0) return 1;
        _ = displays.remove(@intFromPtr(display));
    } else if (shared_display_ownership != null) {
        // No Java initialization is outstanding. Match Android egl_display_t's
        // idempotent termination without consuming another owner's reference.
        if (eglQueryString(display, 0x3054) == null) { // EGL_VERSION
            const code = eglGetError();
            if (code != 0x3001) { // A valid but uninitialized display is allowed.
                fail(code);
                return 0;
            }
        }
        return 1;
    }
    var remaining: u64 = 0;
    const result = if (shared_display_ownership) |owner| owner.release(display, &remaining) else eglTerminate(display);
    if (result != 0 and remaining == 0) {
        if (extension_strings.fetchRemove(@intFromPtr(display))) |entry| alloc.free(entry.value);
        while (true) {
            var images = native_images.iterator();
            var removed = false;
            while (images.next()) |entry| if (entry.value_ptr.display == display) {
                const retained = entry.value_ptr.*;
                _ = native_images.remove(entry.key_ptr.*);
                retained.release(retained.buffer);
                removed = true;
                break;
            };
            if (!removed) break;
        }
        while (true) {
            // Deletion invalidates hash-map iterators; collect one entry and
            // restart only when an unbound buffer has actually been removed.
            var entries = surfaces.iterator();
            var removed = false;
            while (entries.next()) |entry| if (entry.value_ptr.display == display) {
                entry.value_ptr.destroyed = true;
                entry.value_ptr.display_alive = false;
                if (entry.value_ptr.bindings == 0 and entry.value_ptr.presenting == 0) {
                    collect(@ptrFromInt(entry.key_ptr.*));
                    removed = true;
                    break;
                }
            };
            if (!removed) break;
        }
    }
    return result;
}

test "GBM pbuffers render, preserve pixels across swaps, and survive deferred destruction" {
    const api = struct {
        extern "gbm" fn gbm_create_device(c_int) Handle;
        extern "gbm" fn gbm_device_destroy(Handle) void;
        extern "EGL" fn eglBindAPI(u32) u32;
        extern "EGL" fn eglCreateContext(Handle, Handle, Handle, ?[*]const i32) Handle;
        extern "EGL" fn eglDestroyContext(Handle, Handle) u32;
        extern "GLESv2" fn glClearColor(f32, f32, f32, f32) void;
        extern "GLESv2" fn glClear(u32) void;
        extern "GLESv2" fn glReadPixels(i32, i32, i32, i32, u32, u32, *[4]u8) void;
        extern "GLESv2" fn glGetError() u32;
    };
    const io = std.testing.io;
    const file = Io.Dir.cwd().openFile(io, "/dev/dri/renderD128", .{ .mode = .read_write }) catch return error.SkipZigTest;
    defer file.close(io);
    const device = api.gbm_create_device(file.handle) orelse return error.SkipZigTest;
    defer api.gbm_device_destroy(device);
    const display = defaultDisplay(device);
    if (initialize(io, display, null, null) == 0) return error.SkipZigTest;
    defer _ = terminate(io, display);
    try std.testing.expectEqual(@as(i32, 1), initCount(io, display));
    var version_major: i32 = -1;
    var version_minor: i32 = -1;
    try std.testing.expectEqual(@as(u32, 1), initialize(io, display, &version_major, &version_minor));
    try std.testing.expect(version_major >= 1 and version_minor >= 0);
    try std.testing.expectEqual(@as(i32, 2), initCount(io, display));
    try std.testing.expectEqual(@as(u32, 1), terminate(io, display));
    try std.testing.expectEqual(@as(i32, 1), initCount(io, display));
    var config: Handle = null;
    var count: i32 = 0;
    const attrs = [_]i32{ 0x3040, 4, 0x3024, 8, 0x3021, 8, surface_type, window_bit | pbuffer_bit, none };
    try std.testing.expectEqual(@as(u32, 1), chooseConfig(display, &attrs, @ptrCast(&config), 1, &count));
    try std.testing.expectEqual(@as(i32, 1), count);
    var value: i32 = 0;
    try std.testing.expectEqual(@as(u32, 1), getConfigAttrib(display, config, surface_type, &value));
    try std.testing.expectEqual(window_bit | pbuffer_bit, value & (window_bit | pbuffer_bit));
    try std.testing.expectEqual(@as(u32, 1), api.eglBindAPI(0x30a0)); // EGL_OPENGL_ES_API
    const context = api.eglCreateContext(display, config, null, &.{ 0x3098, 2, none });
    try std.testing.expect(context != null);
    defer _ = api.eglDestroyContext(display, context);
    const surface = createPbuffer(io, display, config, &.{ width_key, 16, height_key, 8, none });
    try std.testing.expect(surface != null);
    errdefer _ = destroySurface(io, display, surface);
    try std.testing.expectEqual(@as(u32, 1), querySurface(io, display, surface, width_key, &value));
    try std.testing.expectEqual(@as(i32, 16), value);
    try std.testing.expectEqual(@as(u32, 1), querySurface(io, display, surface, height_key, &value));
    try std.testing.expectEqual(@as(i32, 8), value);
    try std.testing.expectEqual(@as(u32, 1), makeCurrent(io, display, surface, surface, context));
    defer _ = releaseThread(io);
    api.glClearColor(1, 0, 0, 1);
    api.glClear(0x4000);
    var pixel: [4]u8 = undefined;
    api.glReadPixels(8, 4, 1, 1, 0x1908, 0x1401, &pixel);
    try std.testing.expectEqual(@as(u32, 0), api.glGetError());
    try std.testing.expectEqualSlices(u8, &.{ 255, 0, 0, 255 }, &pixel);
    for (0..5) |_| try std.testing.expectEqual(@as(u32, 1), swapBuffers(io, display, surface));
    api.glReadPixels(8, 4, 1, 1, 0x1908, 0x1401, &pixel);
    try std.testing.expectEqualSlices(u8, &.{ 255, 0, 0, 255 }, &pixel);
    try std.testing.expectEqual(@as(u32, 1), destroySurface(io, display, surface));
    api.glClearColor(0, 1, 0, 1);
    api.glClear(0x4000);
    api.glReadPixels(8, 4, 1, 1, 0x1908, 0x1401, &pixel);
    try std.testing.expectEqual(@as(u32, 0), api.glGetError());
    try std.testing.expectEqualSlices(u8, &.{ 0, 255, 0, 255 }, &pixel);
    try std.testing.expectEqual(@as(u32, 1), releaseThread(io));
    try std.testing.expect(createPbuffer(io, display, config, &.{ width_key, -1, none }) == null);
    try std.testing.expectEqual(bad_parameter, getError());
    try std.testing.expectEqual(success, getError());
    const empty = createPbuffer(io, display, config, null);
    try std.testing.expect(empty != null);
    defer _ = destroySurface(io, display, empty);
    try std.testing.expectEqual(@as(u32, 1), querySurface(io, display, empty, width_key, &value));
    try std.testing.expectEqual(@as(i32, 0), value);
}

// Android exports these entry points from libEGL; GLVND publishes extension
// entry points through its driver dispatcher. Keep this ABI difference below
// the original EGLExt JNI, including the driver's error and fence ownership.
pub fn presentationTime(io: Io, display: Handle, surface: Handle, time: i64) u32 {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    // Pinned eglPresentationTimeANDROIDImpl validates the display and window,
    // then sets its producer timestamp (including AUTO); it has no driver call.
    if (eglQueryString(display, 0x3054) == null) return 0;
    if (surfaces.get(@intFromPtr(surface))) |owned| {
        if (owned.display == display and !owned.destroyed and owned.window != null) {
            _ = azk_egl_window_timestamp(owned.window, time);
            return 1;
        }
    }
    fail(bad_surface);
    return 0;
}
pub fn dupNativeFenceFD(display: Handle, sync: Handle) i32 {
    const Function = *const fn (Handle, Handle) callconv(.c) i32;
    const function: Function = @ptrCast(eglGetProcAddress("eglDupNativeFenceFDANDROID") orelse {
        fail(bad_parameter);
        return -1;
    });
    return function(display, sync);
}
