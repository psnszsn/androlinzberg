//! State and software display clock for SurfaceFlinger's GPU-only host composer.
//! This clock requests Android work; it never reports Wayland presentation.
//! Every layer is client-composited except the CURSOR layer, which the
//! composer hands to the cursor plane (cursor_plane.zig).
const std = @import("std");
comptime {
    _ = @import("cursor_plane.zig");
}
const Io = std.Io;
const alloc = std.heap.c_allocator;
const max_layers = 1024;
const client = 1; // pinned composer3.Composition.CLIENT
const cursor = 4; // pinned composer3.Composition.CURSOR
const Layer = struct { id: u64 = 0, composition: i32 = client };
const Callback = *const fn (?*anyopaque, i64) callconv(.c) void;
const Context = struct {
    threaded: Io.Threaded,
    mutex: Io.Mutex = .init,
    changed: Io.Condition = .init,
    group: Io.Group = .init,
    callback: Callback,
    cookie: ?*anyopaque,
    period: i64,
    enabled: bool = false,
    powered: bool = true,
    stopping: bool = false,
    layers: [max_layers]Layer = @splat(.{}),
    next_id: u64 = 1,
    validated: bool = false,
    accepted: bool = false,
    fn io(self: *Context) Io {
        return self.threaded.io();
    }
    fn lock(self: *Context) void {
        self.mutex.lockUncancelable(self.io());
    }
    fn unlock(self: *Context) void {
        self.mutex.unlock(self.io());
    }
    fn invalidate(self: *Context) void {
        self.validated = false;
        self.accepted = false;
    }
    fn layer(self: *Context, id: u64) ?*Layer {
        if (id == 0) return null;
        for (&self.layers) |*entry| if (entry.id == id) return entry;
        return null;
    }
    fn clock(self: *Context) void {
        self.lock();
        defer self.unlock();
        while (!self.stopping) {
            if (!self.enabled or !self.powered) {
                self.changed.waitUncancelable(self.io(), &self.mutex);
                continue;
            }
            const deadline = Io.Clock.Timestamp.fromNow(self.io(), .{ .clock = .awake, .raw = .fromNanoseconds(self.period) });
            while (!self.stopping and self.enabled and self.powered) {
                self.changed.waitTimeout(self.io(), &self.mutex, .{ .deadline = deadline }) catch |err| switch (err) {
                    error.Timeout => {},
                    error.Canceled => return,
                };
                if (Io.Clock.now(.awake, self.io()).nanoseconds >= deadline.raw.nanoseconds) break;
            }
            if (self.stopping or !self.enabled or !self.powered) continue;
            const timestamp: i64 = @intCast(Io.Clock.now(.awake, self.io()).nanoseconds);
            // Callbacks can reenter setVsyncEnabled. Never hold the state lock.
            self.unlock();
            self.callback(self.cookie, timestamp);
            self.lock();
        }
    }
};

/// Layers this composer turns into client composition at validation.
fn changes(entry: Layer) bool {
    return entry.id != 0 and entry.composition != client and entry.composition != cursor;
}

pub export fn azk_host_composer_create(period: i64, callback: Callback, cookie: ?*anyopaque) callconv(.c) ?*Context {
    if (period < std.time.ns_per_ms or period > std.time.ns_per_s) return null;
    const ctx = alloc.create(Context) catch return null;
    ctx.* = .{ .threaded = Io.Threaded.init(alloc, .{}), .period = period, .callback = callback, .cookie = cookie };
    ctx.group.concurrent(ctx.io(), Context.clock, .{ctx}) catch {
        ctx.threaded.deinit();
        alloc.destroy(ctx);
        return null;
    };
    return ctx;
}
pub export fn azk_host_composer_destroy(ctx: *Context) callconv(.c) void {
    ctx.lock();
    ctx.stopping = true;
    ctx.changed.broadcast(ctx.io());
    ctx.unlock();
    ctx.group.await(ctx.io()) catch unreachable;
    ctx.threaded.deinit();
    alloc.destroy(ctx);
}
pub export fn azk_host_composer_vsync(ctx: *Context, enabled: bool) callconv(.c) void {
    ctx.lock();
    defer ctx.unlock();
    ctx.enabled = enabled;
    ctx.changed.broadcast(ctx.io());
}
pub export fn azk_host_composer_power(ctx: *Context, powered: bool) callconv(.c) void {
    ctx.lock();
    defer ctx.unlock();
    ctx.powered = powered;
    ctx.invalidate();
    ctx.changed.broadcast(ctx.io());
}
pub export fn azk_host_composer_layer_create(ctx: *Context, out: *u64) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    if (ctx.next_id == std.math.maxInt(u64)) return 6;
    for (&ctx.layers) |*entry| if (entry.id == 0) {
        entry.* = .{ .id = ctx.next_id };
        out.* = entry.id;
        ctx.next_id += 1;
        ctx.invalidate();
        return 0;
    };
    return 6;
}
pub export fn azk_host_composer_layer_destroy(ctx: *Context, id: u64) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    const entry = ctx.layer(id) orelse return 3;
    entry.* = .{};
    ctx.invalidate();
    return 0;
}
pub export fn azk_host_composer_layer_check(ctx: *Context, id: u64, geometry: bool) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    _ = ctx.layer(id) orelse return 3;
    if (geometry) ctx.invalidate();
    return 0;
}
pub export fn azk_host_composer_layer_composition(ctx: *Context, id: u64, composition: i32) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    const entry = ctx.layer(id) orelse return 3;
    // SIDEBAND cannot be sampled by RenderEngine and must fail explicitly.
    if (composition == 5 or composition == 6) return 8;
    if (composition < 1 or composition > 7) return 4;
    entry.composition = composition;
    ctx.invalidate();
    return 0;
}
pub export fn azk_host_composer_validate(ctx: *Context, count: *u32) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    count.* = 0;
    for (ctx.layers) |entry| if (changes(entry)) {
        count.* += 1;
    };
    ctx.validated = true;
    ctx.accepted = count.* == 0;
    return if (count.* == 0) 0 else 5; // ERROR_HAS_CHANGES in HWC2.h
}
pub export fn azk_host_composer_changes(ctx: *Context, ids: [*]u64, capacity: u32, count: *u32) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    if (!ctx.validated) return 7;
    var needed: u32 = 0;
    for (ctx.layers) |entry| if (changes(entry)) {
        needed += 1;
    };
    count.* = needed;
    if (capacity < needed) return 6;
    var index: usize = 0;
    for (ctx.layers) |entry| if (changes(entry)) {
        ids[index] = entry.id;
        index += 1;
    };
    return 0;
}
pub export fn azk_host_composer_accept(ctx: *Context) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    if (!ctx.validated) return 7;
    for (&ctx.layers) |*entry| if (changes(entry.*)) {
        entry.composition = client;
    };
    ctx.accepted = true;
    return 0;
}
pub export fn azk_host_composer_present(ctx: *Context, has_target: bool) callconv(.c) i32 {
    ctx.lock();
    defer ctx.unlock();
    if (!ctx.validated or !ctx.accepted) return 7;
    // HWC permits an empty validated display without a client target. A target
    // is required when our GPU-only composer has actual client layers.
    if (!has_target) for (ctx.layers) |entry| {
        if (entry.id != 0 and entry.composition != cursor) return 4;
    };
    ctx.invalidate();
    return 0;
}
