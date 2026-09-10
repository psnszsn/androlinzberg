//! JNI binding to the common native EGL display and pbuffer owner.
const std = @import("std");
pub const Handle = ?*anyopaque;
pub var getError: *const fn () callconv(.c) i32 = undefined;
pub var queryString: *const fn (Handle, i32) callconv(.c) ?[*:0]const u8 = undefined;
pub var chooseConfig: *const fn (Handle, ?[*]const i32, ?[*]Handle, i32, *i32) callconv(.c) u32 = undefined;
pub var getConfigAttrib: *const fn (Handle, Handle, i32, *i32) callconv(.c) u32 = undefined;
pub var createPbuffer: *const fn (Handle, Handle, ?[*]const i32) callconv(.c) Handle = undefined;
pub var querySurface: *const fn (Handle, Handle, i32, *i32) callconv(.c) u32 = undefined;
pub var surfaceAttrib: *const fn (Handle, Handle, i32, i32) callconv(.c) u32 = undefined;
pub var swapBuffers: *const fn (Handle, Handle) callconv(.c) u32 = undefined;
pub var destroySurface: *const fn (Handle, Handle) callconv(.c) u32 = undefined;
pub var makeCurrent: *const fn (Handle, Handle, Handle, Handle) callconv(.c) u32 = undefined;
pub var releaseThread: *const fn () callconv(.c) u32 = undefined;
pub var initialize: *const fn (Handle, ?*i32, ?*i32) callconv(.c) u32 = undefined;
pub var terminate: *const fn (Handle) callconv(.c) u32 = undefined;
pub var defaultDisplay: *const fn (Handle) callconv(.c) Handle = undefined;
pub var initCount: *const fn (Handle) callconv(.c) i32 = undefined;

pub fn bind(platform: *std.DynLib) !void {
    getError = platform.lookup(@TypeOf(getError), "azk_egl_getError") orelse return error.NoMethod;
    queryString = platform.lookup(@TypeOf(queryString), "azk_egl_queryString") orelse return error.NoMethod;
    chooseConfig = platform.lookup(@TypeOf(chooseConfig), "azk_egl_chooseConfig") orelse return error.NoMethod;
    getConfigAttrib = platform.lookup(@TypeOf(getConfigAttrib), "azk_egl_getConfigAttrib") orelse return error.NoMethod;
    createPbuffer = platform.lookup(@TypeOf(createPbuffer), "azk_egl_createPbuffer") orelse return error.NoMethod;
    querySurface = platform.lookup(@TypeOf(querySurface), "azk_egl_querySurface") orelse return error.NoMethod;
    surfaceAttrib = platform.lookup(@TypeOf(surfaceAttrib), "azk_egl_surfaceAttrib") orelse return error.NoMethod;
    swapBuffers = platform.lookup(@TypeOf(swapBuffers), "azk_egl_swapBuffers") orelse return error.NoMethod;
    destroySurface = platform.lookup(@TypeOf(destroySurface), "azk_egl_destroySurface") orelse return error.NoMethod;
    makeCurrent = platform.lookup(@TypeOf(makeCurrent), "azk_egl_makeCurrent") orelse return error.NoMethod;
    releaseThread = platform.lookup(@TypeOf(releaseThread), "azk_egl_releaseThread") orelse return error.NoMethod;
    initialize = platform.lookup(@TypeOf(initialize), "azk_egl_initialize") orelse return error.NoMethod;
    terminate = platform.lookup(@TypeOf(terminate), "azk_egl_terminate") orelse return error.NoMethod;
    defaultDisplay = platform.lookup(@TypeOf(defaultDisplay), "azk_egl_defaultDisplay") orelse return error.NoMethod;
    initCount = platform.lookup(@TypeOf(initCount), "azk_egl_initCount") orelse return error.NoMethod;
}
