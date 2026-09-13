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
extern "c" fn azk_hwui_default_egl_display() ?*anyopaque;
extern "EGL" fn eglGetDisplay(?*anyopaque) ?*anyopaque;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const anyopaque;
pub export fn azk_host_egl_get_proc_address(name: [*:0]const u8) callconv(.c) ?*const anyopaque {
    return eglGetProcAddress(name);
}
pub export fn bionic_eglGetDisplay(display: ?*anyopaque) callconv(.c) ?*anyopaque {
    if (display != null) return eglGetDisplay(display);
    // Register the GBM display for pbuffer translation while preserving a
    // Wayland display selected by the legacy HWUI presentation path.
    _ = egl_backend.defaultDisplay(azk_graphics_gbm_device());
    return azk_hwui_default_egl_display();
}

comptime {
    _ = @import("legacy_gles.zig");
}

var g_io: std.Io = undefined;

pub export fn azk_platform_bind_io(
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void {
    g_io = .{ .userdata = userdata, .vtable = vtable };
    egl_backend.bindDisplayOwnership(&azk_graphics_egl_initialize, &azk_graphics_egl_release);
    @import("selinux_backend.zig").bindIo(g_io);
}

extern "c" fn __errno_location() *c_int;
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
    return egl_backend.presentationTime(display, surface, time);
}
pub export fn azk_egl_dupNativeFenceFD(display: Handle, sync: Handle) callconv(.c) i32 {
    return egl_backend.dupNativeFenceFD(display, sync);
}
