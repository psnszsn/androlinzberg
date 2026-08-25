//! Video layer manager: bridges android.view.ArtzigVideoLayer natives (called
//! from ExoPlayer's playback threads) to Wayland subsurfaces (main thread
//! only). All cross-thread state goes through per-layer mailboxes; frames are
//! latest-wins.
const std = @import("std");
const wayland = @import("wayland.zig");
const codec = @import("azk_codec");
const art = @import("art");
const jni = art.jni;

const Record = struct {
    layer: ?*wayland.Window.VideoLayer = null,
    frames: u64 = 0,
    geometry: [8]i32 = .{ 0, 0, 0, 0, 0, 0, 0, 0 },
    geometry_dirty: bool = false,
    frame: std.ArrayList(u8) = .empty,
    frame_w: i32 = 0,
    frame_h: i32 = 0,
    frame_fresh: bool = false,
    hardware_frame: ?*codec.HardwareFrame = null,
    hardware_fresh: bool = false,
    destroyed: bool = false,
    dead: bool = false,
};

var g_io: std.Io = undefined;
var g_mutex_impl: std.Io.Mutex = .init;
const g_mutex = struct {
    fn lock() void {
        g_mutex_impl.lockUncancelable(g_io);
    }
    fn unlock() void {
        g_mutex_impl.unlock(g_io);
    }
};
var g_records: std.ArrayList(*Record) = .empty;
var g_gpa: std.mem.Allocator = undefined;
var g_win: ?*wayland.Window = null;
var g_android_physical_pixels = false;

fn record(id: jni.jint) ?*Record {
    if (id < 1 or id > g_records.items.len) return null;
    const r = g_records.items[@intCast(id - 1)];
    return if (r.dead) null else r;
}

// ------------------------------------------------------------------ natives

fn nativeCreate(_: [*c]jni.JNIEnv, _: jni.jclass) callconv(.c) jni.jint {
    g_mutex.lock();
    defer g_mutex.unlock();
    const r = g_gpa.create(Record) catch return 0;
    r.* = .{};
    g_records.append(g_gpa, r) catch {
        g_gpa.destroy(r);
        return 0;
    };
    return @intCast(g_records.items.len);
}

fn nativeSetGeometry(
    _: [*c]jni.JNIEnv,
    _: jni.jclass,
    id: jni.jint,
    x: jni.jint,
    y: jni.jint,
    w: jni.jint,
    h: jni.jint,
    visible_x: jni.jint,
    visible_y: jni.jint,
    visible_w: jni.jint,
    visible_h: jni.jint,
) callconv(.c) void {
    g_mutex.lock();
    defer g_mutex.unlock();
    const r = record(id) orelse return;
    r.geometry = .{ x, y, w, h, visible_x, visible_y, visible_w, visible_h };
    r.geometry_dirty = true;
}

fn nativePush(env: [*c]jni.JNIEnv, _: jni.jclass, id: jni.jint, buf: jni.jobject, w: jni.jint, h: jni.jint) callconv(.c) void {
    const base_raw = env.*.*.GetDirectBufferAddress.?(env, buf) orelse return;
    const base: [*]const u8 = @ptrCast(base_raw);
    const bytes: usize = @intCast(w * 4 * h);
    g_mutex.lock();
    defer g_mutex.unlock();
    const r = record(id) orelse return;
    if (r.hardware_frame) |hardware| codec.releaseHardwareFrame(hardware);
    r.hardware_frame = null;
    r.hardware_fresh = false;
    r.frame.resize(g_gpa, bytes) catch return;
    @memcpy(r.frame.items[0..bytes], base[0..bytes]);
    r.frame_w = w;
    r.frame_h = h;
    r.frame_fresh = true;
}

fn nativePushHardware(_: [*c]jni.JNIEnv, _: jni.jclass, id: jni.jint, handle: jni.jlong) callconv(.c) void {
    if (handle == 0) return;
    const frame: *codec.HardwareFrame = @ptrFromInt(@as(usize, @intCast(handle)));
    g_mutex.lock();
    defer g_mutex.unlock();
    const r = record(id) orelse {
        codec.releaseHardwareFrame(frame);
        return;
    };
    if (r.hardware_frame) |old| codec.releaseHardwareFrame(old);
    r.hardware_frame = frame;
    r.hardware_fresh = true;
    r.frame_fresh = false;
}

fn nativeReleaseHardware(_: [*c]jni.JNIEnv, _: jni.jclass, handle: jni.jlong) callconv(.c) void {
    if (handle == 0) return;
    const frame: *codec.HardwareFrame = @ptrFromInt(@as(usize, @intCast(handle)));
    codec.releaseHardwareFrame(frame);
}

fn nativeDestroy(_: [*c]jni.JNIEnv, _: jni.jclass, id: jni.jint) callconv(.c) void {
    g_mutex.lock();
    defer g_mutex.unlock();
    const r = record(id) orelse return;
    r.destroyed = true;
}

pub fn register(gpa: std.mem.Allocator, io: std.Io, win: *wayland.Window, env: [*c]jni.JNIEnv, android_physical_pixels: bool) !void {
    g_gpa = gpa;
    g_io = io;
    g_win = win;
    g_android_physical_pixels = android_physical_pixels;
    const cls = env.*.*.FindClass.?(env, "android/view/ArtzigVideoLayer") orelse return error.NoClass;
    const methods = [_]jni.JNINativeMethod{
        .{ .name = "nativeCreate", .signature = "()I", .fnPtr = @ptrCast(@constCast(&nativeCreate)) },
        .{ .name = "nativeSetGeometry", .signature = "(IIIIIIIII)V", .fnPtr = @ptrCast(@constCast(&nativeSetGeometry)) },
        .{ .name = "nativePush", .signature = "(ILjava/nio/ByteBuffer;II)V", .fnPtr = @ptrCast(@constCast(&nativePush)) },
        .{ .name = "nativePushHardware", .signature = "(IJ)V", .fnPtr = @ptrCast(@constCast(&nativePushHardware)) },
        .{ .name = "nativeReleaseHardware", .signature = "(J)V", .fnPtr = @ptrCast(@constCast(&nativeReleaseHardware)) },
        .{ .name = "nativeDestroy", .signature = "(I)V", .fnPtr = @ptrCast(@constCast(&nativeDestroy)) },
    };
    if (env.*.*.RegisterNatives.?(env, cls, &methods, methods.len) != 0) return error.RegisterFailed;
}

/// Queried by the codec before opening VA-API. The advertised DMA-BUF formats
/// are immutable after Window.init's registry roundtrip.
pub export fn azk_video_dmabuf_available() c_int {
    const win = g_win orelse return 0;
    return @intFromBool(win.hasVideoDmabuf());
}

/// Validate every plane modifier before the codec transfers ownership of an
/// exported VA-API surface to Wayland.
pub export fn azk_video_dmabuf_supports(format: u32, plane_count: c_int, modifiers: [*]const u64) c_int {
    const win = g_win orelse return 0;
    if (plane_count <= 0 or plane_count > 4) return 0;
    for (0..@intCast(plane_count)) |index| {
        if (!win.supportsVideoDmabuf(format, modifiers[index])) return 0;
    }
    return 1;
}

fn releaseDmabuf(context: *anyopaque) void {
    const frame: *codec.HardwareFrame = @ptrCast(@alignCast(context));
    codec.releaseHardwareFrame(frame);
}

/// Main-thread tick: apply pending rects, frames, and destroys.
pub fn process() void {
    const win = g_win orelse return;
    g_mutex.lock();
    defer g_mutex.unlock();
    for (g_records.items) |r| {
        if (r.dead) continue;
        if (r.destroyed) {
            if (r.layer) |l| l.destroy();
            if (r.hardware_frame) |hardware| codec.releaseHardwareFrame(hardware);
            r.frame.deinit(g_gpa);
            r.* = .{ .dead = true };
            continue;
        }
        if (r.layer == null and (r.geometry_dirty or r.frame_fresh or r.hardware_fresh)) {
            r.layer = win.createVideoLayer() catch |e| {
                std.debug.print("video layer create failed: {}\n", .{e});
                r.dead = true;
                continue;
            };
        }
        const layer = r.layer orelse continue;
        if (r.geometry_dirty) {
            const full_x = r.geometry[0];
            const full_y = r.geometry[1];
            const full_w = r.geometry[2];
            const full_h = r.geometry[3];
            const visible_x = r.geometry[4];
            const visible_y = r.geometry[5];
            const visible_w = r.geometry[6];
            const visible_h = r.geometry[7];
            var dst_x = visible_x;
            var dst_y = visible_y;
            var dst_w = visible_w;
            var dst_h = visible_h;
            if (g_android_physical_pixels) {
                const right = win.bufferToSurfaceX(visible_x + visible_w);
                const bottom = win.bufferToSurfaceY(visible_y + visible_h);
                dst_x = win.bufferToSurfaceX(visible_x);
                dst_y = win.bufferToSurfaceY(visible_y);
                dst_w = @max(0, right - dst_x);
                dst_h = @max(0, bottom - dst_y);
            }
            layer.setGeometry(
                dst_x,
                dst_y,
                dst_w,
                dst_h,
                visible_x - full_x,
                visible_y - full_y,
                visible_w,
                visible_h,
                full_w,
                full_h,
            );
            r.geometry_dirty = false;
        }
        if (r.frame_fresh) {
            layer.pushFrame(r.frame.items, r.frame_w, r.frame_h) catch {};
            r.frame_fresh = false;
            r.frames += 1;
            if (r.frames == 1 or r.frames % 300 == 0) {
                std.debug.print("azk: video layer frame #{d} {d}x{d} at rect {d},{d} {d}x{d}\n", .{ r.frames, r.frame_w, r.frame_h, r.geometry[0], r.geometry[1], r.geometry[2], r.geometry[3] });
            }
        }
        if (r.hardware_fresh) {
            const hardware = r.hardware_frame.?;
            const frame_w = hardware.width;
            const frame_h = hardware.height;
            const frame_format = hardware.format;
            r.hardware_frame = null;
            r.hardware_fresh = false;
            var frame: wayland.DmabufFrame = .{
                .context = hardware,
                .release_fn = releaseDmabuf,
                .width = hardware.width,
                .height = hardware.height,
                .format = hardware.format,
                .plane_count = @intCast(hardware.plane_count),
                .fds = undefined,
                .offsets = undefined,
                .strides = undefined,
                .modifiers = undefined,
            };
            for (0..4) |index| {
                frame.fds[index] = hardware.fds[index];
                frame.offsets[index] = hardware.offsets[index];
                frame.strides[index] = hardware.strides[index];
                frame.modifiers[index] = hardware.modifiers[index];
            }
            layer.pushDmabuf(frame) catch |err| {
                std.debug.print("azk: DMA-BUF video import failed: {}\n", .{err});
            };
            r.frames += 1;
            if (r.frames == 1 or r.frames % 300 == 0) {
                std.debug.print("azk: video layer DMA-BUF frame #{d} {d}x{d} format=0x{x}\n", .{ r.frames, frame_w, frame_h, frame_format });
            }
        }
    }
}

/// Stop accepting frames and release every mailbox and compositor layer.
/// Called on the main thread before the owning Wayland window is destroyed.
pub fn deinit() void {
    if (g_win == null) return;
    g_mutex.lock();
    defer g_mutex.unlock();
    g_win = null;
    for (g_records.items) |r| {
        if (!r.dead) {
            if (r.layer) |layer| layer.destroy();
            if (r.hardware_frame) |hardware| codec.releaseHardwareFrame(hardware);
            r.frame.deinit(g_gpa);
        }
        g_gpa.destroy(r);
    }
    g_records.deinit(g_gpa);
    g_records = .empty;
}
