//! Driver-side EGL_ANDROID_blob_cache over the original FileBlobCache.
//! Follows egl_cache_t filename, blob limits and termination persistence. The
//! stock EGL wrapper depends on Android's vendor loader; Mesa supplies the
//! driver here. Zig owns synchronization, callbacks and the four-second flush
//! interval at frame boundaries (plus display release). The original C++ class
//! owns the cache format and eviction policy.
const std = @import("std");
const Io = std.Io;
const Handle = ?*anyopaque;
extern fn azk_egl_cache_create([*:0]const u8) callconv(.c) Handle;
extern fn azk_egl_cache_destroy(Handle) callconv(.c) void;
extern fn azk_egl_cache_write(Handle) callconv(.c) void;
extern fn azk_egl_cache_put(Handle, *const anyopaque, usize, *const anyopaque, usize) callconv(.c) void;
extern fn azk_egl_cache_get(Handle, *const anyopaque, usize, ?*anyopaque, usize) callconv(.c) usize;
extern "EGL" fn eglGetProcAddress([*:0]const u8) ?*const fn () callconv(.c) void;
extern "EGL" fn eglGetError() i32;
extern "EGL" fn eglQueryString(Handle, i32) ?[*:0]const u8;
var mutex: Io.Mutex = .init;
// Written once before registering driver callbacks; retained for their lifetime.
var callback_io: ?Io = null;
var cache: Handle = null;
var dirty = false;
var last_write: i64 = 0;

pub fn setFilename(io: Io, filename: [*:0]const u8) void {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (callback_io == null) callback_io = io;
    if (cache != null) {
        if (dirty) azk_egl_cache_write(cache);
        azk_egl_cache_destroy(cache);
    }
    cache = azk_egl_cache_create(filename);
    dirty = false;
    last_write = Io.Clock.awake.now(io).toMilliseconds();
}

fn put(key: ?*const anyopaque, key_size: isize, value: ?*const anyopaque, value_size: isize) callconv(.c) void {
    if (key == null or value == null or key_size <= 0 or value_size <= 0) return;
    const io = callback_io.?;
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (cache == null) return;
    azk_egl_cache_put(cache, key.?, @intCast(key_size), value.?, @intCast(value_size));
    dirty = true;
}
fn get(key: ?*const anyopaque, key_size: isize, value: ?*anyopaque, value_size: isize) callconv(.c) isize {
    if (key == null or key_size <= 0 or value_size < 0 or (value == null and value_size != 0)) return 0;
    const io = callback_io.?;
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (cache == null) return 0;
    return @intCast(azk_egl_cache_get(cache, key.?, @intCast(key_size), value, @intCast(value_size)));
}

pub fn install(io: Io, display: Handle) void {
    const extensions = eglQueryString(display, 0x3055) orelse return;
    var tokens = std.mem.tokenizeScalar(u8, std.mem.span(extensions), ' ');
    while (tokens.next()) |token| {
        if (!std.mem.eql(u8, token, "EGL_ANDROID_blob_cache")) continue;
        const address = eglGetProcAddress("eglSetBlobCacheFuncsANDROID") orelse return;
        mutex.lockUncancelable(io);
        if (callback_io == null) callback_io = io;
        mutex.unlock(io);
        const bind: *const fn (Handle, @TypeOf(&put), @TypeOf(&get)) callconv(.c) void = @ptrCast(address);
        bind(display, &put, &get);
        // Like egl_cache_t::initialize, consume the private driver's error.
        // Mesa may already have these callbacks on a shared initialized display.
        const status = eglGetError();
        if (status != 0x3000 and status != 0x300c) std.log.warn("EGL blob cache registration: 0x{x}", .{status});
        return;
    }
}

// Flush after driver work, without holding the callback lock across EGL calls.
pub fn flush(io: Io, force: bool) void {
    mutex.lockUncancelable(io);
    defer mutex.unlock(io);
    if (cache == null or !dirty) return;
    const now = Io.Clock.awake.now(io).toMilliseconds();
    if (!force and now - last_write < 4000) return;
    azk_egl_cache_write(cache);
    dirty = false;
    last_write = now;
}
