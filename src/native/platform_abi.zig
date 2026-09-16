//! Non-rendering C ABI required by AOSP components in libandroid_platform.
//! Keep these exports in Zig while the HWUI/Skia implementation remains C++.
const std = @import("std");

// Source: bionic's upstream-openbsd getprogname.c. The host libc owns the
// process-name storage and exposes the same __progname variable.
extern "c" var __progname: [*:0]const u8;
pub export fn getprogname() callconv(.c) [*:0]const u8 {
    return __progname;
}

// The pinned Android EGL client and HWUI use one default display. Mesa's
// implicit default may choose a different platform from the hosted window.
extern "EGL" fn eglGetDisplay(?*anyopaque) ?*anyopaque;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;
// One Android EGL dispatch table serves native Skia/HWUI and app-loaded libEGL.
// Unknown entry points fall through to the system Mesa driver dispatcher.
pub export fn azk_egl_getProcAddress(name: [*:0]const u8) callconv(.c) ?*const anyopaque {
    const adapted = .{
        .{ "eglGetDisplay", &bionic_eglGetDisplay },
        .{ "eglGetError", &azk_egl_getError },
        .{ "eglQueryString", &azk_egl_queryString },
        .{ "eglGetNativeClientBufferANDROID", &azk_egl_getNativeClientBuffer },
        .{ "eglCreateImageKHR", &azk_egl_createImageKHR },
        .{ "eglCreateImage", &azk_egl_createImage },
        .{ "eglDestroyImageKHR", &azk_egl_destroyImage },
        .{ "eglDestroyImage", &azk_egl_destroyImage },
        .{ "eglChooseConfig", &azk_egl_chooseConfig },
        .{ "eglGetConfigAttrib", &azk_egl_getConfigAttrib },
        .{ "eglCreatePbufferSurface", &azk_egl_createPbuffer },
        .{ "eglQuerySurface", &azk_egl_querySurface },
        .{ "eglSurfaceAttrib", &azk_egl_surfaceAttrib },
        .{ "eglSwapBuffers", &azk_egl_swapBuffers },
        .{ "eglDestroySurface", &azk_egl_destroySurface },
        .{ "eglMakeCurrent", &azk_egl_makeCurrent },
        .{ "eglReleaseThread", &azk_egl_releaseThread },
        .{ "eglInitialize", &azk_egl_initialize },
        .{ "eglTerminate", &azk_egl_terminate },
        .{ "eglPresentationTimeANDROID", &azk_egl_presentationTime },
        .{ "eglDupNativeFenceFDANDROID", &azk_egl_dupNativeFenceFD },
        .{ "eglCreateWindowSurface", &azk_egl_createWindow },
        .{ "eglGetCurrentSurface", &azk_egl_currentSurface },
        .{ "eglBindTexImage", &azk_egl_bindTexImage },
        .{ "eglReleaseTexImage", &azk_egl_releaseTexImage },
        .{ "eglGetProcAddress", &azk_egl_getProcAddress },
        .{ "eglCreateContext", &azk_egl_createContext },
        .{ "eglBeginFrame", &azk_egl_beginFrame },
        .{ "eglSwapBuffersWithDamageKHR", &azk_egl_swapBuffersWithDamage },
        .{ "eglSwapBuffersWithDamageEXT", &azk_egl_swapBuffersWithDamage },
        .{ "eglSetDamageRegionKHR", &azk_egl_setDamageRegion },
    };
    inline for (adapted) |entry| {
        if (std.mem.eql(u8, std.mem.span(name), entry[0])) return @ptrCast(entry[1]);
    }
    return eglGetProcAddress(name);
}
pub export fn bionic_eglGetDisplay(display: ?*anyopaque) callconv(.c) ?*anyopaque {
    if (display != null) return eglGetDisplay(display);
    return egl_backend.defaultDisplay(azk_graphics_gbm_device());
}

comptime {
    _ = @import("legacy_gles.zig");
}

var g_io: std.Io = undefined;

pub export fn azk_kernel_vmap_stack() callconv(.c) c_int {
    const mapped = @import("kernel_metadata.zig").runningKernelStackMapping(g_io, std.heap.c_allocator) catch |err| {
        std.log.err("running kernel stack metadata unavailable: {t}", .{err});
        return -1;
    };
    return if (mapped) 1 else 0;
}

pub export fn azk_platform_bind_io(
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void {
    g_io = .{ .userdata = userdata, .vtable = vtable };
    egl_backend.bindDisplayOwnership(&azk_graphics_egl_initialize, &azk_graphics_egl_release);
    @import("selinux_backend.zig").bindIo(g_io);
}

extern "c" fn __errno_location() *c_int;
// Pinned linker get_dlwarning consumes a deferred compatibility-warning journal.
// The hosted loader reports load errors immediately and has no such journal.
pub export fn android_dlwarning(context: ?*anyopaque, callback: *const fn (?*anyopaque, ?[*:0]const u8) callconv(.c) void) callconv(.c) void {
    callback(context, null);
}

// Bionic allocator control has no counterpart for glibc profiling, leak records,
// or zygote state. Match its documented failure contract without inventing heap
// records or reporting that profiling was initialized. M_PURGE uses the separate
// mallopt ABI below and does release unused heap pages.
pub export fn android_mallopt(_: c_int, _: ?*anyopaque, _: usize) callconv(.c) bool {
    __errno_location().* = @backingInt(std.os.linux.E.OPNOTSUPP);
    return false;
}
// bionic android_dlopen_ext: ordinary shared objects use the host ELF loader.
// Android namespace/FD/RELRO extensions have no host equivalent and fail explicitly.
// No descriptor is borrowed or consumed when rejecting an extension.
extern "c" fn dlopen(filename: ?[*:0]const u8, flags: c_int) ?*anyopaque;
extern "c" fn azk_host_path(original: [*:0]const u8) ?[*:0]const u8;
extern "c" fn azk_host_path_release(original: [*:0]const u8, mapped: ?[*:0]const u8) void;

// Follow ART's hosted NativeLoader search order for native Android callers too.
// System sonames resolve through the same private /system/lib64 aliases; loading
// the existing ELF by its real path avoids a duplicate platform C++ runtime.
pub export fn azk_android_dlopen(filename: ?[*:0]const u8, flags: c_int) callconv(.c) ?*anyopaque {
    const original = filename orelse return dlopen(null, flags);
    const mapped = azk_host_path(original) orelse return null;
    defer azk_host_path_release(original, mapped);
    if (dlopen(mapped, flags)) |handle| return handle;
    if (std.mem.indexOfScalar(u8, std.mem.span(original), '/') != null) return null;
    var buffer: [4096]u8 = undefined;
    const system_path = std.fmt.bufPrintSentinel(&buffer, "/system/lib64/{s}", .{std.mem.span(original)}, 0) catch {
        __errno_location().* = @backingInt(std.os.linux.E.NAMETOOLONG);
        return null;
    };
    const system_mapped = azk_host_path(system_path) orelse return null;
    defer azk_host_path_release(system_path, system_mapped);
    return dlopen(system_mapped, flags);
}

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
    return azk_android_dlopen(filename, flags);
}

// Shared EGL state serves Java EGL and dynamically loaded Android clients.
const egl_backend = @import("egl_backend.zig");
const Handle = ?*anyopaque;
extern fn azk_graphics_gbm_device() callconv(.c) Handle;
extern fn azk_graphics_egl_initialize(Handle, ?*i32, ?*i32) callconv(.c) u32;
extern fn azk_graphics_egl_release(Handle, ?*u64) callconv(.c) u32;
pub export fn azk_egl_getError() callconv(.c) i32 {
    return egl_backend.getError();
}
pub export fn azk_egl_queryString(display: Handle, name: i32) callconv(.c) ?[*:0]const u8 {
    return egl_backend.queryString(g_io, display, name);
}
pub export fn azk_egl_getNativeClientBuffer(buffer: Handle) callconv(.c) Handle {
    return egl_backend.nativeClientBuffer(buffer);
}
pub export fn azk_egl_createImageKHR(display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const i32) callconv(.c) Handle {
    return egl_backend.createImage(g_io, i32, display, context, target, buffer, attributes);
}
pub export fn azk_egl_createImage(display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const isize) callconv(.c) Handle {
    return egl_backend.createImage(g_io, isize, display, context, target, buffer, attributes);
}
pub export fn azk_egl_destroyImage(display: Handle, image: Handle) callconv(.c) u32 {
    return egl_backend.destroyImage(g_io, display, image);
}
pub export fn azk_egl_chooseConfig(display: Handle, attributes: ?[*]const i32, configs: ?[*]Handle, size: i32, count: *i32) callconv(.c) u32 {
    return egl_backend.chooseConfig(display, attributes, configs, size, count);
}
pub export fn azk_egl_getConfigAttrib(display: Handle, config: Handle, attribute: i32, value: *i32) callconv(.c) u32 {
    return egl_backend.getConfigAttrib(display, config, attribute, value);
}
pub export fn azk_egl_createPbuffer(display: Handle, config: Handle, attributes: ?[*]const i32) callconv(.c) Handle {
    return egl_backend.createPbuffer(g_io, display, config, attributes);
}
pub export fn azk_egl_querySurface(display: Handle, surface: Handle, attribute: i32, value: *i32) callconv(.c) u32 {
    return egl_backend.querySurface(g_io, display, surface, attribute, value);
}
pub export fn azk_egl_surfaceAttrib(display: Handle, surface: Handle, attribute: i32, value: i32) callconv(.c) u32 {
    return egl_backend.surfaceAttrib(g_io, display, surface, attribute, value);
}
pub export fn azk_egl_swapBuffers(display: Handle, surface: Handle) callconv(.c) u32 {
    return egl_backend.swapBuffers(g_io, display, surface);
}
pub export fn azk_egl_destroySurface(display: Handle, surface: Handle) callconv(.c) u32 {
    return egl_backend.destroySurface(g_io, display, surface);
}
pub export fn azk_egl_makeCurrent(display: Handle, draw: Handle, read: Handle, context: Handle) callconv(.c) u32 {
    return egl_backend.makeCurrent(g_io, display, draw, read, context);
}
pub export fn azk_egl_releaseThread() callconv(.c) u32 {
    return egl_backend.releaseThread(g_io);
}
pub export fn azk_egl_initialize(display: Handle, major: ?*i32, minor: ?*i32) callconv(.c) u32 {
    return egl_backend.initialize(g_io, display, major, minor);
}
pub export fn azk_egl_set_cache_filename(filename: [*:0]const u8) callconv(.c) void {
    @import("egl_cache.zig").setFilename(g_io, filename);
}
pub export fn azk_egl_terminate(display: Handle) callconv(.c) u32 {
    return egl_backend.terminate(g_io, display);
}
pub export fn azk_egl_defaultDisplay(device: Handle) callconv(.c) Handle {
    return egl_backend.defaultDisplay(device);
}
pub export fn azk_egl_initCount(display: Handle) callconv(.c) i32 {
    return egl_backend.initCount(g_io, display);
}

pub export fn azk_egl_presentationTime(display: Handle, surface: Handle, time: i64) callconv(.c) u32 {
    return egl_backend.presentationTime(g_io, display, surface, time);
}
pub export fn azk_egl_dupNativeFenceFD(display: Handle, sync: Handle) callconv(.c) i32 {
    return egl_backend.dupNativeFenceFD(display, sync);
}

pub export fn azk_egl_createWindow(display: Handle, config: Handle, window: Handle, attributes: ?[*]const i32) callconv(.c) Handle {
    return egl_backend.createWindow(g_io, display, config, window, attributes);
}
pub export fn azk_egl_currentSurface(which: i32) callconv(.c) Handle {
    return egl_backend.currentSurface(g_io, which);
}
pub export fn azk_egl_bindTexImage(display: Handle, surface: Handle, buffer: i32) callconv(.c) u32 {
    return egl_backend.bindTexImage(g_io, display, surface, buffer, true);
}
pub export fn azk_egl_releaseTexImage(display: Handle, surface: Handle, buffer: i32) callconv(.c) u32 {
    return egl_backend.bindTexImage(g_io, display, surface, buffer, false);
}

// dev.artzig EGL regression observation; no Android framework test hooks.
pub export fn azk_egl_last_buffer_inode(surface: Handle) callconv(.c) u64 {
    return egl_backend.lastBufferInode(g_io, surface);
}

pub export fn azk_egl_createContext(display: Handle, config: Handle, share: Handle, attributes: ?[*]const i32) callconv(.c) Handle {
    return egl_backend.createContext(display, config, share, attributes);
}
pub export fn azk_egl_beginFrame(display: Handle, surface: Handle) callconv(.c) void {
    egl_backend.beginFrame(g_io, display, surface);
}
pub export fn azk_egl_swapBuffersWithDamage(display: Handle, surface: Handle, rects: ?[*]const i32, count: i32) callconv(.c) u32 {
    return egl_backend.swapBuffersWithDamage(g_io, display, surface, rects, count);
}
pub export fn azk_egl_setDamageRegion(display: Handle, surface: Handle, rects: ?[*]const i32, count: i32) callconv(.c) u32 {
    return egl_backend.setDamageRegion(g_io, display, surface, rects, count);
}

// Pinned Bitmap destruction requests Bionic M_PURGE. glibc's equivalent asks
// the allocator to return unused pages; having no pages to return is success.
extern "c" fn malloc_trim(usize) c_int;
pub export fn azk_android_mallopt(option: c_int, _: c_int) callconv(.c) c_int {
    if (option != -101) return 0;
    _ = malloc_trim(0);
    return 1;
}
pub export fn azk_egl_render_fence(fd: *i32) callconv(.c) i32 {
    return egl_backend.renderFence(fd);
}
