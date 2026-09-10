//! Android EGL dispatch. All adapted entry points share the native platform's
//! display and pbuffer ownership with Java EGL, including dynamic proc lookup.
const std = @import("std");
const Handle = ?*anyopaque;
extern fn bionic_eglGetDisplay(Handle) callconv(.c) Handle;
extern fn azk_host_egl_get_proc_address([*:0]const u8) callconv(.c) ?*const anyopaque;
export fn eglGetDisplay(display: Handle) callconv(.c) Handle {
    return bionic_eglGetDisplay(display);
}
extern fn azk_egl_getError() callconv(.c) i32;
export fn eglGetError() callconv(.c) i32 {
    return azk_egl_getError();
}
extern fn azk_egl_queryString(Handle, i32) callconv(.c) ?[*:0]const u8;
export fn eglQueryString(display: Handle, name: i32) callconv(.c) ?[*:0]const u8 {
    return azk_egl_queryString(display, name);
}
extern fn azk_egl_getNativeClientBuffer(Handle) callconv(.c) Handle;
export fn eglGetNativeClientBufferANDROID(buffer: Handle) callconv(.c) Handle {
    return azk_egl_getNativeClientBuffer(buffer);
}
extern fn azk_egl_createImageKHR(Handle, Handle, u32, Handle, ?[*]const i32) callconv(.c) Handle;
export fn eglCreateImageKHR(display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const i32) callconv(.c) Handle {
    return azk_egl_createImageKHR(display, context, target, buffer, attributes);
}
extern fn azk_egl_createImage(Handle, Handle, u32, Handle, ?[*]const isize) callconv(.c) Handle;
export fn eglCreateImage(display: Handle, context: Handle, target: u32, buffer: Handle, attributes: ?[*]const isize) callconv(.c) Handle {
    return azk_egl_createImage(display, context, target, buffer, attributes);
}
extern fn azk_egl_destroyImage(Handle, Handle) callconv(.c) u32;
export fn eglDestroyImageKHR(display: Handle, image: Handle) callconv(.c) u32 {
    return azk_egl_destroyImage(display, image);
}
export fn eglDestroyImage(display: Handle, image: Handle) callconv(.c) u32 {
    return azk_egl_destroyImage(display, image);
}
extern fn azk_egl_chooseConfig(Handle, ?[*]const i32, ?[*]Handle, i32, *i32) callconv(.c) u32;
export fn eglChooseConfig(display: Handle, attributes: ?[*]const i32, configs: ?[*]Handle, size: i32, count: *i32) callconv(.c) u32 {
    return azk_egl_chooseConfig(display, attributes, configs, size, count);
}
extern fn azk_egl_getConfigAttrib(Handle, Handle, i32, *i32) callconv(.c) u32;
export fn eglGetConfigAttrib(display: Handle, config: Handle, attribute: i32, value: *i32) callconv(.c) u32 {
    return azk_egl_getConfigAttrib(display, config, attribute, value);
}
extern fn azk_egl_createPbuffer(Handle, Handle, ?[*]const i32) callconv(.c) Handle;
export fn eglCreatePbufferSurface(display: Handle, config: Handle, attributes: ?[*]const i32) callconv(.c) Handle {
    return azk_egl_createPbuffer(display, config, attributes);
}
extern fn azk_egl_querySurface(Handle, Handle, i32, *i32) callconv(.c) u32;
export fn eglQuerySurface(display: Handle, surface: Handle, attribute: i32, value: *i32) callconv(.c) u32 {
    return azk_egl_querySurface(display, surface, attribute, value);
}
extern fn azk_egl_surfaceAttrib(Handle, Handle, i32, i32) callconv(.c) u32;
export fn eglSurfaceAttrib(display: Handle, surface: Handle, attribute: i32, value: i32) callconv(.c) u32 {
    return azk_egl_surfaceAttrib(display, surface, attribute, value);
}
extern fn azk_egl_swapBuffers(Handle, Handle) callconv(.c) u32;
export fn eglSwapBuffers(display: Handle, surface: Handle) callconv(.c) u32 {
    return azk_egl_swapBuffers(display, surface);
}
extern fn azk_egl_destroySurface(Handle, Handle) callconv(.c) u32;
export fn eglDestroySurface(display: Handle, surface: Handle) callconv(.c) u32 {
    return azk_egl_destroySurface(display, surface);
}
extern fn azk_egl_makeCurrent(Handle, Handle, Handle, Handle) callconv(.c) u32;
export fn eglMakeCurrent(display: Handle, draw: Handle, read: Handle, context: Handle) callconv(.c) u32 {
    return azk_egl_makeCurrent(display, draw, read, context);
}
extern fn azk_egl_releaseThread() callconv(.c) u32;
export fn eglReleaseThread() callconv(.c) u32 {
    return azk_egl_releaseThread();
}
extern fn azk_egl_initialize(Handle, ?*i32, ?*i32) callconv(.c) u32;
export fn eglInitialize(display: Handle, major: ?*i32, minor: ?*i32) callconv(.c) u32 {
    return azk_egl_initialize(display, major, minor);
}
extern fn azk_egl_terminate(Handle) callconv(.c) u32;
export fn eglTerminate(display: Handle) callconv(.c) u32 {
    return azk_egl_terminate(display);
}

export fn eglGetProcAddress(name: [*:0]const u8) callconv(.c) ?*const anyopaque {
    if (std.mem.eql(u8, std.mem.span(name), "eglGetDisplay")) return @ptrCast(&eglGetDisplay);
    if (std.mem.eql(u8, std.mem.span(name), "eglGetProcAddress")) return @ptrCast(&eglGetProcAddress);
    if (std.mem.eql(u8, std.mem.span(name), "eglGetError")) return @ptrCast(&eglGetError);
    if (std.mem.eql(u8, std.mem.span(name), "eglQueryString")) return @ptrCast(&eglQueryString);
    if (std.mem.eql(u8, std.mem.span(name), "eglGetNativeClientBufferANDROID")) return @ptrCast(&eglGetNativeClientBufferANDROID);
    if (std.mem.eql(u8, std.mem.span(name), "eglCreateImageKHR")) return @ptrCast(&eglCreateImageKHR);
    if (std.mem.eql(u8, std.mem.span(name), "eglCreateImage")) return @ptrCast(&eglCreateImage);
    if (std.mem.eql(u8, std.mem.span(name), "eglDestroyImageKHR")) return @ptrCast(&eglDestroyImageKHR);
    if (std.mem.eql(u8, std.mem.span(name), "eglDestroyImage")) return @ptrCast(&eglDestroyImage);
    if (std.mem.eql(u8, std.mem.span(name), "eglChooseConfig")) return @ptrCast(&eglChooseConfig);
    if (std.mem.eql(u8, std.mem.span(name), "eglGetConfigAttrib")) return @ptrCast(&eglGetConfigAttrib);
    if (std.mem.eql(u8, std.mem.span(name), "eglCreatePbufferSurface")) return @ptrCast(&eglCreatePbufferSurface);
    if (std.mem.eql(u8, std.mem.span(name), "eglQuerySurface")) return @ptrCast(&eglQuerySurface);
    if (std.mem.eql(u8, std.mem.span(name), "eglSurfaceAttrib")) return @ptrCast(&eglSurfaceAttrib);
    if (std.mem.eql(u8, std.mem.span(name), "eglSwapBuffers")) return @ptrCast(&eglSwapBuffers);
    if (std.mem.eql(u8, std.mem.span(name), "eglDestroySurface")) return @ptrCast(&eglDestroySurface);
    if (std.mem.eql(u8, std.mem.span(name), "eglMakeCurrent")) return @ptrCast(&eglMakeCurrent);
    if (std.mem.eql(u8, std.mem.span(name), "eglReleaseThread")) return @ptrCast(&eglReleaseThread);
    if (std.mem.eql(u8, std.mem.span(name), "eglInitialize")) return @ptrCast(&eglInitialize);
    if (std.mem.eql(u8, std.mem.span(name), "eglTerminate")) return @ptrCast(&eglTerminate);
    return azk_host_egl_get_proc_address(name);
}
