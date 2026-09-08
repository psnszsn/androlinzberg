//! Android EGL dispatch boundary. The pinned eglGetDisplayImpl uses Android's
//! single platform display; the host selects that display in its HWUI adapter.
//! Keep dynamically loaded clients on that same display, including proc lookup.
const std = @import("std");
extern fn bionic_eglGetDisplay(?*anyopaque) callconv(.c) ?*anyopaque;
extern fn azk_host_egl_get_proc_address([*:0]const u8) callconv(.c) ?*const anyopaque;

export fn eglGetDisplay(display: ?*anyopaque) callconv(.c) ?*anyopaque {
    return bionic_eglGetDisplay(display);
}

export fn eglGetProcAddress(name: [*:0]const u8) callconv(.c) ?*const anyopaque {
    if (std.mem.eql(u8, std.mem.span(name), "eglGetDisplay")) return @ptrCast(&eglGetDisplay);
    if (std.mem.eql(u8, std.mem.span(name), "eglGetProcAddress")) return @ptrCast(&eglGetProcAddress);
    return azk_host_egl_get_proc_address(name);
}
