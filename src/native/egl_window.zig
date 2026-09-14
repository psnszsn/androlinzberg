//! System-Mesa GBM producer for stock Surface.attachBuffer/queueBuffer.
//! Android borrows the rendered DMA-BUF until detachNextBuffer returns it and
//! its release fence signals. Only then may GBM reuse it. No pixel transfer.
const std = @import("std");
const Io = std.Io;
const Handle = ?*anyopaque;
const Info = @import("graphics_types").Info;
const success = 0x3000;
const bad_alloc = 0x3003;
const bad_native_window = 0x300b;
extern "gbm" fn gbm_surface_lock_front_buffer(Handle) Handle;
extern "gbm" fn gbm_surface_release_buffer(Handle, Handle) void;
extern "gbm" fn gbm_surface_has_free_buffers(Handle) i32;
extern "gbm" fn gbm_bo_get_width(Handle) u32;
extern "gbm" fn gbm_bo_get_height(Handle) u32;
extern "gbm" fn gbm_bo_get_format(Handle) u32;
extern "gbm" fn gbm_bo_get_plane_count(Handle) i32;
extern "gbm" fn gbm_bo_get_fd(Handle) i32;
extern "gbm" fn gbm_bo_get_stride(Handle) u32;
extern "gbm" fn gbm_bo_get_offset(Handle, i32) u32;
extern "gbm" fn gbm_bo_get_modifier(Handle) u64;
extern fn azk_egl_window_requirements(Handle, *i32, *i32, *u32, *u64, *i32) callconv(.c) i32;
extern fn azk_egl_window_submit(Handle, *const Info, i32, *u64) callconv(.c) i32;
extern fn azk_egl_window_reclaim(Handle, *u64, *i32) callconv(.c) i32;
extern fn azk_egl_render_fence(*i32) callconv(.c) i32;

fn file(fd: i32) ?Io.File {
    return if (fd < 0) null else .{ .handle = fd, .flags = .{ .nonblocking = false } };
}
// Linux sync_file_info from linux/sync_file.h. Query without waiting; the
// producer's std.Io deadline owns the wait and every returned descriptor.
fn signaled(io: Io, fence: ?Io.File) !bool {
    const f = fence orelse return true;
    var info: extern struct { name: [32]u8 = @splat(0), status: i32 = 0, flags: u32 = 0, num_fences: u32 = 0, pad: u32 = 0, fences: u64 = 0 } = .{};
    const result = try io.operate(.{ .device_io_control = .{ .file = f, .code = 0xc0383e04, .arg = &info } });
    if (result.device_io_control != 0 or info.status < 0) return error.FenceFailed;
    return info.status > 0;
}
pub const Delivery = struct {
    const Lease = struct { bo: Handle, surface: Handle, id: u64 = 0, fence: ?Io.File = null, detached: bool = false };
    // Mesa owns a bounded surface pool. Reserve storage before locking a BO.
    leases: [4]?Lease = @splat(null),
    format: u32 = 0,
    usage: u64 = 0,
    failed: bool = false,
    last_id: u64 = 0,
    attached: usize = 0,
    capacity: i32 = 0,

    pub fn requirements(self: *Delivery, native: Handle, width: *i32, height: *i32) bool {
        return azk_egl_window_requirements(native, width, height, &self.format, &self.usage, &self.capacity) == 0;
    }
    fn reclaim(self: *Delivery, io: Io, native: Handle) !void {
        while (true) {
            var id: u64 = 0;
            var fd: i32 = -1;
            const status = azk_egl_window_reclaim(native, &id, &fd);
            if (status == -12) break; // NO_MEMORY: no free attached buffer.
            if (status != 0) return error.NativeWindowLost;
            self.attached -|= 1;
            var fence = file(fd);
            defer if (fence) |f| f.close(io);
            for (&self.leases) |*slot| if (slot.*) |*lease| {
                if (lease.id != id) continue;
                if (lease.fence) |f| f.close(io);
                lease.fence = fence;
                fence = null;
                lease.detached = true;
                break;
            };
        }
        for (&self.leases) |*slot| if (slot.*) |lease| {
            if (lease.detached and try signaled(io, lease.fence)) {
                gbm_surface_release_buffer(lease.surface, lease.bo);
                if (lease.fence) |f| f.close(io);
                slot.* = null;
            }
        };
    }
    pub fn present(self: *Delivery, io: Io, native: Handle, surface: Handle, width: *i32, height: *i32) i32 {
        if (self.failed) return bad_native_window;
        if (!self.requirements(native, width, height)) return bad_native_window;
        const slot_deadline = Io.Clock.awake.now(io).toMilliseconds() + 3000;
        while (true) {
            self.reclaim(io, native) catch return bad_native_window;
            if (self.capacity > 0 and self.attached < self.capacity) break;
            if (Io.Clock.awake.now(io).toMilliseconds() >= slot_deadline) return bad_alloc;
            Io.sleep(io, .fromMilliseconds(1), .awake) catch return bad_alloc;
            if (!self.requirements(native, width, height)) return bad_native_window;
        }
        const slot = for (&self.leases) |*candidate| {
            if (candidate.* == null) break candidate;
        } else return bad_alloc;
        const bo = gbm_surface_lock_front_buffer(surface) orelse return bad_alloc;
        slot.* = .{ .bo = bo, .surface = surface, .detached = true };
        // Every path after a lock retains it until rendering finishes or this
        // drawable is retired. An import/queue failure must not unlock early.
        var fence: i32 = -1;
        if (azk_egl_render_fence(&fence) != 0) {
            self.failed = true;
            return bad_alloc;
        }
        slot.*.?.fence = file(fence);
        if (gbm_bo_get_plane_count(bo) != 1) return bad_alloc;
        const fd = gbm_bo_get_fd(bo);
        const exported = file(fd) orelse return bad_alloc;
        defer exported.close(io);
        const info: Info = .{
            .width = gbm_bo_get_width(bo),
            .height = gbm_bo_get_height(bo),
            .format = self.format,
            .layers = 1,
            .usage = self.usage,
            .drm_format = gbm_bo_get_format(bo),
            .stride = gbm_bo_get_stride(bo),
            .offset = gbm_bo_get_offset(bo, 0),
            .modifier = gbm_bo_get_modifier(bo),
            .id = 0,
            .fd = fd,
        };
        const submitted = azk_egl_window_submit(native, &info, fence, &slot.*.?.id);
        if (slot.*.?.id != 0) {
            self.attached += 1;
            slot.*.?.detached = false;
        }
        if (submitted != 0) return bad_native_window;
        self.last_id = slot.*.?.id;
        // Surface refreshes default dimensions from QueueBufferOutput.
        if (!self.requirements(native, width, height)) return bad_native_window;
        // Resize replaces the drawable before returning to GL, so its old pool
        // need not have a spare buffer. Otherwise reserve room for the next draw.
        if (info.width != width.* or info.height != height.*) return success;
        const deadline = Io.Clock.awake.now(io).toMilliseconds() + 3000;
        while (true) {
            self.reclaim(io, native) catch return bad_native_window;
            if (gbm_surface_has_free_buffers(surface) != 0) return success;
            if (Io.Clock.awake.now(io).toMilliseconds() >= deadline) {
                self.failed = true;
                return bad_alloc;
            }
            Io.sleep(io, .fromMilliseconds(1), .awake) catch return bad_alloc;
        }
    }
    pub fn bufferInode(self: *Delivery, io: Io) u64 {
        for (self.leases) |slot| if (slot) |lease| {
            if (lease.id == self.last_id and self.last_id != 0) {
                const exported = file(gbm_bo_get_fd(lease.bo)) orelse return 0;
                defer exported.close(io);
                return (exported.stat(io) catch return 0).inode;
            }
        };
        return 0;
    }
    // Call only once GL can never draw into this pool again. Android's imported
    // handles independently retain storage, so retiring does not wait for a
    // consumer that may keep its last frame until another frame arrives.
    pub fn retire(self: *Delivery, io: Io, driver_alive: bool) void {
        for (&self.leases) |*slot| if (slot.*) |lease| {
            if (driver_alive) gbm_surface_release_buffer(lease.surface, lease.bo);
            if (lease.fence) |f| f.close(io);
            slot.* = null;
        };
    }
};
