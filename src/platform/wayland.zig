//! Wayland client window: xdg_toplevel + wl_shm double buffer + input + vsync.
//! Built on zig-wayland (pinned next-zig revision). The pixel format is
//! argb8888 (== Skia kBGRA_8888 premul on little-endian).
const std = @import("std");
const wayland = @import("wayland");
const wl = wayland.client.wl;
const xdg = wayland.client.xdg;
const wp = wayland.client.wp;
const zwp = wayland.client.zwp;

const drm_format_nv12: u32 = 0x3231564e;
const drm_format_p010: u32 = 0x30313050;
const drm_format_mod_invalid: u64 = 0x00ffffffffffffff;

const DmabufFormat = struct {
    format: u32,
    modifier: u64,
};

const DmabufTableEntry = extern struct {
    format: u32,
    padding: u32,
    modifier: u64,
};

fn selectDmabufImportModifier(formats: []const DmabufFormat, format: u32, modifier: u64) ?u64 {
    for (formats) |supported| {
        if (supported.format == format and supported.modifier == modifier) return modifier;
    }
    return null;
}

/// One owned decoded frame at the video-transport seam. Ownership transfers
/// to pushDmabuf and is returned through release_fn after wl_buffer.release.
pub const DmabufFrame = struct {
    context: *anyopaque,
    release_fn: *const fn (*anyopaque) void,
    width: i32,
    height: i32,
    format: u32,
    plane_count: u32,
    fds: [4]i32,
    offsets: [4]u32,
    strides: [4]u32,
    modifiers: [4]u64,

    pub fn release(self: DmabufFrame) void {
        self.release_fn(self.context);
    }
};

// --- libxkbcommon (minimal extern surface) ---
const xkb = struct {
    pub const Context = opaque {};
    pub const Keymap = opaque {};
    pub const State = opaque {};
    pub extern fn xkb_context_new(flags: c_int) ?*Context;
    pub extern fn xkb_context_unref(ctx: ?*Context) void;
    pub extern fn xkb_keymap_new_from_string(ctx: *Context, str: [*:0]const u8, format: c_int, flags: c_int) ?*Keymap;
    pub extern fn xkb_keymap_unref(keymap: ?*Keymap) void;
    pub extern fn xkb_state_new(keymap: *Keymap) ?*State;
    pub extern fn xkb_state_unref(state: ?*State) void;
    pub extern fn xkb_state_update_mask(state: *State, depressed: u32, latched: u32, locked: u32, layout_depressed: u32, layout_latched: u32, layout_locked: u32) c_int;
    pub extern fn xkb_state_key_get_utf32(state: *State, key: u32) u32;
    pub extern fn xkb_state_key_get_one_sym(state: *State, key: u32) u32;
};

pub const Buffer = struct {
    wl_buffer: *wl.Buffer,
    pixels: []align(std.heap.page_size_min) u8,
    width: i32,
    height: i32,
    stride: usize,
    busy: bool = false,
};

pub const Event = union(enum) {
    close,
    configure: struct { width: i32, height: i32 },
    fullscreen_changed: bool,
    pointer_motion: struct { x: f64, y: f64 },
    pointer_button: struct { button: u32, pressed: bool, x: f64, y: f64 },
    pointer_axis: struct { horizontal: f64, vertical: f64 },
    key: struct { keycode: u32, pressed: bool, unicode: u32, keysym: u32 },
    frame_done: struct { time_ms: u32 },
};

pub const Window = struct {
    gpa: std.mem.Allocator,
    display: *wl.Display,
    registry: *wl.Registry,
    compositor: ?*wl.Compositor = null,
    subcompositor: ?*wl.Subcompositor = null,
    viewporter: ?*wp.Viewporter = null,
    fractional_scale_manager: ?*wp.FractionalScaleManagerV1 = null,
    dmabuf: ?*zwp.LinuxDmabufV1 = null,
    dmabuf_feedback: ?*zwp.LinuxDmabufFeedbackV1 = null,
    dmabuf_table: ?[]align(std.heap.page_size_min) u8 = null,
    dmabuf_formats: std.ArrayList(DmabufFormat) = .empty,
    shm: ?*wl.Shm = null,
    wm_base: ?*xdg.WmBase = null,
    seat: ?*wl.Seat = null,
    pointer: ?*wl.Pointer = null,
    keyboard: ?*wl.Keyboard = null,
    cursor_shape_manager: ?*wp.CursorShapeManagerV1 = null,
    cursor_device: ?*wp.CursorShapeDeviceV1 = null,
    pointer_enter_serial: u32 = 0,
    cursor_shape: u32 = 0, // last applied wp_cursor_shape_device_v1.shape (0 = none)

    surface: *wl.Surface = undefined,
    viewport: ?*wp.Viewport = null,
    fractional_scale: ?*wp.FractionalScaleV1 = null,
    xdg_surface: *xdg.Surface = undefined,
    toplevel: *xdg.Toplevel = undefined,

    width: i32,
    height: i32,
    /// Preferred surface scale in 120ths, as defined by fractional-scale-v1.
    scale_120: u32 = 120,
    fullscreen_requested: bool = false,
    fullscreen_actual: bool = false,
    configured: bool = false,
    buffers: [2]?Buffer = .{ null, null },

    pointer_x: f64 = 0,
    pointer_y: f64 = 0,

    xkb_context: ?*xkb.Context = null,
    xkb_keymap: ?*xkb.Keymap = null,
    xkb_state: ?*xkb.State = null,

    // simple event queue filled by listeners, drained by the caller
    events: std.ArrayList(Event) = .empty,
    frame_cb: ?*wl.Callback = null,

    pub fn init(gpa: std.mem.Allocator, width: i32, height: i32, title: [:0]const u8) !*Window {
        return initOpts(gpa, width, height, title, false);
    }

    pub fn initOpts(gpa: std.mem.Allocator, width: i32, height: i32, title: [:0]const u8, fullscreen: bool) !*Window {
        const self = try gpa.create(Window);
        self.* = .{
            .gpa = gpa,
            .display = try wl.Display.connect(null),
            .registry = undefined,
            .width = width,
            .height = height,
            .fullscreen_requested = fullscreen,
        };
        self.registry = try self.display.getRegistry();
        self.registry.setListener(*Window, registryListener, self);
        if (self.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        if (self.compositor == null or self.shm == null or self.wm_base == null)
            return error.MissingGlobals;

        self.surface = try self.compositor.?.createSurface();
        // fractional-scale-v1 deliberately leaves wl_surface.buffer_scale at
        // 1. A viewport maps the higher-resolution buffer back to the logical
        // surface size used by xdg-shell and input events.
        if (self.viewporter != null and self.fractional_scale_manager != null) {
            self.viewport = try self.viewporter.?.getViewport(self.surface);
            self.fractional_scale = try self.fractional_scale_manager.?.getFractionalScale(self.surface);
            self.fractional_scale.?.setListener(*Window, fractionalScaleListener, self);
            self.updateViewport();
        }
        self.xdg_surface = try self.wm_base.?.getXdgSurface(self.surface);
        self.toplevel = try self.xdg_surface.getToplevel();
        self.xdg_surface.setListener(*Window, xdgSurfaceListener, self);
        self.toplevel.setListener(*Window, toplevelListener, self);
        self.toplevel.setTitle(title);
        self.toplevel.setAppId("org.androlinzberg");
        if (fullscreen) self.toplevel.setFullscreen(null);
        self.surface.commit();
        // wait for the first configure
        while (!self.configured) {
            if (self.display.dispatch() != .SUCCESS) return error.DispatchFailed;
        }
        return self;
    }

    fn registryListener(registry: *wl.Registry, event: wl.Registry.Event, self: *Window) void {
        switch (event) {
            .global => |g| {
                if (std.mem.orderZ(u8, g.interface, wl.Compositor.interface.name) == .eq) {
                    self.compositor = registry.bind(g.name, wl.Compositor, 4) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wl.Subcompositor.interface.name) == .eq) {
                    self.subcompositor = registry.bind(g.name, wl.Subcompositor, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wp.Viewporter.interface.name) == .eq) {
                    self.viewporter = registry.bind(g.name, wp.Viewporter, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wp.FractionalScaleManagerV1.interface.name) == .eq) {
                    self.fractional_scale_manager = registry.bind(g.name, wp.FractionalScaleManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwp.LinuxDmabufV1.interface.name) == .eq) {
                    const version = @min(g.version, 4);
                    self.dmabuf = registry.bind(g.name, zwp.LinuxDmabufV1, version) catch return;
                    if (version >= 4) {
                        self.dmabuf_feedback = self.dmabuf.?.getDefaultFeedback() catch return;
                        self.dmabuf_feedback.?.setListener(*Window, dmabufFeedbackListener, self);
                    } else {
                        self.dmabuf.?.setListener(*Window, dmabufListener, self);
                    }
                } else if (std.mem.orderZ(u8, g.interface, wp.CursorShapeManagerV1.interface.name) == .eq) {
                    self.cursor_shape_manager = registry.bind(g.name, wp.CursorShapeManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wl.Shm.interface.name) == .eq) {
                    self.shm = registry.bind(g.name, wl.Shm, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, xdg.WmBase.interface.name) == .eq) {
                    self.wm_base = registry.bind(g.name, xdg.WmBase, 2) catch return;
                    self.wm_base.?.setListener(*Window, wmBaseListener, self);
                } else if (std.mem.orderZ(u8, g.interface, wl.Seat.interface.name) == .eq) {
                    self.seat = registry.bind(g.name, wl.Seat, 5) catch return;
                    self.seat.?.setListener(*Window, seatListener, self);
                }
            },
            .global_remove => {},
        }
    }

    fn dmabufListener(_: *zwp.LinuxDmabufV1, event: zwp.LinuxDmabufV1.Event, self: *Window) void {
        const pair: DmabufFormat = switch (event) {
            .format => |value| .{ .format = value.format, .modifier = drm_format_mod_invalid },
            .modifier => |value| .{
                .format = value.format,
                .modifier = (@as(u64, value.modifier_hi) << 32) | value.modifier_lo,
            },
        };
        self.dmabuf_formats.append(self.gpa, pair) catch {};
    }

    fn dmabufFeedbackListener(feedback: *zwp.LinuxDmabufFeedbackV1, event: zwp.LinuxDmabufFeedbackV1.Event, self: *Window) void {
        switch (event) {
            .format_table => |table| {
                if (self.dmabuf_table) |old| std.posix.munmap(old);
                self.dmabuf_table = null;
                self.dmabuf_formats.clearRetainingCapacity();
                defer _ = std.c.close(table.fd);
                if (table.size == 0 or table.size % @sizeOf(DmabufTableEntry) != 0) return;
                self.dmabuf_table = std.posix.mmap(
                    null,
                    table.size,
                    .{ .READ = true },
                    .{ .TYPE = .PRIVATE },
                    table.fd,
                    0,
                ) catch null;
            },
            .tranche_formats => |formats| {
                const table = self.dmabuf_table orelse return;
                const entries = @as([*]const DmabufTableEntry, @ptrCast(@alignCast(table.ptr)))[0 .. table.len / @sizeOf(DmabufTableEntry)];
                for (formats.indices.*.slice(u16)) |index| {
                    if (index >= entries.len) continue;
                    const entry = entries[index];
                    self.dmabuf_formats.append(self.gpa, .{
                        .format = entry.format,
                        .modifier = entry.modifier,
                    }) catch {};
                }
            },
            .done => {
                // Import support is immutable for this Window. Stop feedback
                // updates so codec threads can read dmabuf_formats lock-free.
                feedback.destroy();
                self.dmabuf_feedback = null;
                if (self.dmabuf_table) |table| std.posix.munmap(table);
                self.dmabuf_table = null;
            },
            .main_device, .tranche_done, .tranche_target_device, .tranche_flags => {},
        }
    }

    pub fn supportsVideoDmabuf(self: *Window, format: u32, modifier: u64) bool {
        return selectDmabufImportModifier(self.dmabuf_formats.items, format, modifier) != null;
    }

    fn videoDmabufImportModifier(self: *Window, format: u32, modifier: u64) ?u64 {
        return selectDmabufImportModifier(self.dmabuf_formats.items, format, modifier);
    }

    pub fn hasVideoDmabuf(self: *Window) bool {
        if (self.dmabuf == null) return false;
        for (self.dmabuf_formats.items) |supported| {
            if (supported.format == drm_format_nv12 or supported.format == drm_format_p010)
                return true;
        }
        return false;
    }

    fn wmBaseListener(wm_base: *xdg.WmBase, event: xdg.WmBase.Event, self: *Window) void {
        _ = self;
        switch (event) {
            .ping => |p| wm_base.pong(p.serial),
        }
    }

    fn xdgSurfaceListener(s: *xdg.Surface, event: xdg.Surface.Event, self: *Window) void {
        switch (event) {
            .configure => |c| {
                s.ackConfigure(c.serial);
                self.configured = true;
            },
        }
    }

    fn fractionalScaleListener(_: *wp.FractionalScaleV1, event: wp.FractionalScaleV1.Event, self: *Window) void {
        switch (event) {
            .preferred_scale => |preferred| {
                if (preferred.scale == 0 or preferred.scale == self.scale_120) return;
                self.scale_120 = preferred.scale;
                self.dropBuffers();
                self.updateViewport();
                // A scale transition does not necessarily cause an xdg-shell
                // size configure. Notify the renderer so it rebuilds its SHM
                // or EGL target at the new physical pixel size.
                self.push(.{ .configure = .{ .width = self.width, .height = self.height } });
            },
        }
    }

    fn toplevelListener(_: *xdg.Toplevel, event: xdg.Toplevel.Event, self: *Window) void {
        switch (event) {
            .configure => |c| {
                var fullscreen = false;
                for (c.states.*.slice(u32)) |state| {
                    if (state == @backingInt(xdg.Toplevel.State.fullscreen)) {
                        fullscreen = true;
                        break;
                    }
                }
                if (fullscreen != self.fullscreen_actual) {
                    self.fullscreen_actual = fullscreen;
                    self.push(.{ .fullscreen_changed = fullscreen });
                }
                // Configure states are authoritative. This also reconciles a
                // compositor-side fullscreen toggle with the client request.
                self.fullscreen_requested = fullscreen;
                if (c.width > 0 and c.height > 0) {
                    if (c.width != self.width or c.height != self.height) {
                        self.width = c.width;
                        self.height = c.height;
                        self.dropBuffers();
                        self.updateViewport();
                        self.push(.{ .configure = .{ .width = c.width, .height = c.height } });
                    }
                }
            },
            .close => self.push(.close),
        }
    }

    fn seatListener(seat: *wl.Seat, event: wl.Seat.Event, self: *Window) void {
        switch (event) {
            .capabilities => |c| {
                if (c.capabilities.pointer and self.pointer == null) {
                    self.pointer = seat.getPointer() catch null;
                    if (self.pointer) |p| p.setListener(*Window, pointerListener, self);
                }
                if (c.capabilities.keyboard and self.keyboard == null) {
                    self.keyboard = seat.getKeyboard() catch null;
                    if (self.keyboard) |k| k.setListener(*Window, keyboardListener, self);
                }
            },
            .name => {},
        }
    }

    fn pointerListener(_: *wl.Pointer, event: wl.Pointer.Event, self: *Window) void {
        switch (event) {
            .motion => |m| {
                self.pointer_x = m.surface_x.toDouble();
                self.pointer_y = m.surface_y.toDouble();
                self.push(.{ .pointer_motion = .{ .x = self.pointer_x, .y = self.pointer_y } });
            },
            .button => |b| self.push(.{ .pointer_button = .{
                .button = b.button,
                .pressed = b.state == .pressed,
                .x = self.pointer_x,
                .y = self.pointer_y,
            } }),
            .axis => |a| {
                const v = a.value.toDouble();
                self.push(.{ .pointer_axis = switch (a.axis) {
                    .vertical_scroll => .{ .horizontal = 0, .vertical = v },
                    .horizontal_scroll => .{ .horizontal = v, .vertical = 0 },
                    else => return,
                } });
            },
            .enter => |e| {
                self.pointer_x = e.surface_x.toDouble();
                self.pointer_y = e.surface_y.toDouble();
                self.pointer_enter_serial = e.serial;
                // re-apply our cursor on every enter (the compositor resets it)
                const shape = if (self.cursor_shape != 0) self.cursor_shape else 1; // default
                self.cursor_shape = 0;
                self.setCursorShape(shape);
                self.push(.{ .pointer_motion = .{ .x = self.pointer_x, .y = self.pointer_y } });
            },
            else => {},
        }
    }

    fn keyboardListener(_: *wl.Keyboard, event: wl.Keyboard.Event, self: *Window) void {
        switch (event) {
            .keymap => |k| {
                defer _ = std.c.close(k.fd);
                if (k.format != .xkb_v1) return;
                const data = std.posix.mmap(null, k.size, .{ .READ = true }, .{ .TYPE = .PRIVATE }, k.fd, 0) catch return;
                defer std.posix.munmap(data);
                if (self.xkb_context == null) self.xkb_context = xkb.xkb_context_new(0);
                const ctx = self.xkb_context orelse return;
                // data is a NUL-terminated keymap string
                const keymap = xkb.xkb_keymap_new_from_string(ctx, @ptrCast(data.ptr), 1, 0) orelse return; // 1 = XKB_KEYMAP_FORMAT_TEXT_V1
                xkb.xkb_state_unref(self.xkb_state);
                xkb.xkb_keymap_unref(self.xkb_keymap);
                self.xkb_keymap = keymap;
                self.xkb_state = xkb.xkb_state_new(keymap);
            },
            .modifiers => |m| {
                if (self.xkb_state) |st| {
                    _ = xkb.xkb_state_update_mask(st, m.mods_depressed, m.mods_latched, m.mods_locked, 0, 0, m.group);
                }
            },
            .key => |k| {
                var unicode: u32 = 0;
                var keysym: u32 = 0;
                if (self.xkb_state) |st| {
                    unicode = xkb.xkb_state_key_get_utf32(st, k.key + 8);
                    keysym = xkb.xkb_state_key_get_one_sym(st, k.key + 8);
                }
                self.push(.{ .key = .{
                    .keycode = k.key,
                    .pressed = k.state == .pressed,
                    .unicode = unicode,
                    .keysym = keysym,
                } });
            },
            else => {},
        }
    }

    fn frameListener(cb: *wl.Callback, event: wl.Callback.Event, self: *Window) void {
        switch (event) {
            .done => |d| {
                cb.destroy();
                self.frame_cb = null;
                self.push(.{ .frame_done = .{ .time_ms = d.callback_data } });
            },
        }
    }

    fn bufferListener(_: *wl.Buffer, event: wl.Buffer.Event, buf: *Buffer) void {
        switch (event) {
            .release => buf.busy = false,
        }
    }

    fn push(self: *Window, e: Event) void {
        self.events.append(self.gpa, e) catch {};
    }

    fn dropBuffers(self: *Window) void {
        for (&self.buffers) |*b| {
            if (b.*) |*buf| {
                buf.wl_buffer.destroy();
                std.posix.munmap(buf.pixels);
                b.* = null;
            }
        }
    }

    fn scaledDimension(logical: i32, scale_120: u32) i32 {
        if (logical <= 0) return 1;
        const scaled = @as(u64, @intCast(logical)) * scale_120;
        return @intCast(@max(@as(u64, 1), (scaled + 119) / 120));
    }

    /// Physical buffer dimensions for the current logical surface size.
    pub fn bufferWidth(self: *const Window) i32 {
        return scaledDimension(self.width, self.scale_120);
    }

    pub fn bufferHeight(self: *const Window) i32 {
        return scaledDimension(self.height, self.scale_120);
    }

    /// Convert compositor surface-local coordinates to Android buffer pixels.
    /// Use the realized buffer ratio rather than scale_120 so rounded buffer
    /// dimensions and input stay aligned at the right and bottom edges.
    pub fn surfaceToBufferX(self: *const Window, x: f64) f64 {
        if (self.width <= 0) return x;
        return x * @as(f64, @floatFromInt(self.bufferWidth())) /
            @as(f64, @floatFromInt(self.width));
    }

    pub fn surfaceToBufferY(self: *const Window, y: f64) f64 {
        if (self.height <= 0) return y;
        return y * @as(f64, @floatFromInt(self.bufferHeight())) /
            @as(f64, @floatFromInt(self.height));
    }

    pub fn bufferToSurfaceX(self: *const Window, x: i32) i32 {
        return scaleIntegerCoordinate(x, self.width, self.bufferWidth());
    }

    pub fn bufferToSurfaceY(self: *const Window, y: i32) i32 {
        return scaleIntegerCoordinate(y, self.height, self.bufferHeight());
    }

    fn scaleIntegerCoordinate(value: i32, destination: i32, source: i32) i32 {
        if (source <= 0) return value;
        const numerator = @as(i64, value) * destination;
        const half = @divTrunc(@as(i64, source), 2);
        return @intCast(@divTrunc(numerator + (if (numerator >= 0) half else -half), source));
    }

    fn updateViewport(self: *Window) void {
        if (self.viewport) |viewport| {
            if (self.width > 0 and self.height > 0) {
                viewport.setDestination(self.width, self.height);
            }
        }
    }

    fn createBuffer(self: *Window) !Buffer {
        const width = self.bufferWidth();
        const height = self.bufferHeight();
        const stride: usize = @intCast(width * 4);
        const size: usize = stride * @as(usize, @intCast(height));
        const memfd = try std.posix.memfd_create("artzig-shm", 0);
        defer _ = std.c.close(memfd);
        if (std.c.ftruncate(memfd, @intCast(size)) != 0) return error.Truncate;
        const pixels = try std.posix.mmap(null, size, .{ .READ = true, .WRITE = true }, .{ .TYPE = .SHARED }, memfd, 0);
        const pool = try self.shm.?.createPool(memfd, @intCast(size));
        defer pool.destroy();
        const wl_buffer = try pool.createBuffer(0, width, height, @intCast(stride), .argb8888);
        return .{ .wl_buffer = wl_buffer, .pixels = pixels, .width = width, .height = height, .stride = stride };
        // NOTE: the release listener is attached in acquire() once the Buffer has
        // its final address inside self.buffers.
    }

    /// Get a free buffer to draw into (creates lazily; double-buffered).
    pub fn acquire(self: *Window) !*Buffer {
        for (&self.buffers) |*slot| {
            if (slot.*) |*buf| {
                if (!buf.busy) return buf;
            } else {
                slot.* = try self.createBuffer();
                const buf = &slot.*.?;
                buf.wl_buffer.setListener(*Buffer, bufferListener, buf);
                return buf;
            }
        }
        // both busy: block on events until one is released
        while (true) {
            if (self.display.dispatch() != .SUCCESS) return error.DispatchFailed;
            for (&self.buffers) |*slot| {
                if (slot.*) |*buf| if (!buf.busy) return buf;
            }
        }
    }

    /// Attach+commit `buf` and request a frame callback (vsync).
    pub fn present(self: *Window, buf: *Buffer) !void {
        buf.busy = true;
        self.surface.attach(buf.wl_buffer, 0, 0);
        self.surface.damage(0, 0, self.width, self.height);
        try self.requestFrame();
        self.surface.commit();
        if (self.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    /// Register a callback to pace the next commit. The direct EGL presenter
    /// calls this before RenderThread's eglSwapBuffers commits the surface.
    pub fn requestFrame(self: *Window) !void {
        if (self.frame_cb == null) {
            self.frame_cb = try self.surface.frame();
            self.frame_cb.?.setListener(*Window, frameListener, self);
        }
    }

    /// Set the pointer cursor via cursor-shape-v1 (shape = wp_cursor_shape_device_v1.shape
    /// value, 1 = default). No-op if the compositor lacks the protocol.
    pub fn setCursorShape(self: *Window, shape: u32) void {
        if (shape == self.cursor_shape) return;
        const mgr = self.cursor_shape_manager orelse return;
        const ptr = self.pointer orelse return;
        if (self.cursor_device == null) {
            self.cursor_device = mgr.getPointer(ptr) catch return;
        }
        const dev = self.cursor_device orelse return;
        dev.setShape(self.pointer_enter_serial, @fromBackingInt(@intCast(shape)));
        self.cursor_shape = shape;
        _ = self.display.flush();
    }

    /// Ask the compositor to enter or leave xdg-shell fullscreen mode. The
    /// actual size arrives asynchronously in the following configure event.
    pub fn setFullscreen(self: *Window, fullscreen: bool) void {
        if (fullscreen == self.fullscreen_requested) return;
        self.fullscreen_requested = fullscreen;
        if (fullscreen) {
            self.toplevel.setFullscreen(null);
        } else {
            self.toplevel.unsetFullscreen();
        }
        self.surface.commit();
        _ = self.display.flush();
    }

    pub fn toggleFullscreen(self: *Window) void {
        self.setFullscreen(!self.fullscreen_actual);
    }

    /// Testing hook: behave as if the compositor resized us.
    pub fn simulateResize(self: *Window, w: i32, h: i32) void {
        self.width = w;
        self.height = h;
        self.dropBuffers();
        self.updateViewport();
    }

    /// Block until at least one event arrives; drain into `out`.
    pub fn waitEvents(self: *Window) ![]const Event {
        self.events.clearRetainingCapacity();
        while (self.events.items.len == 0) {
            if (self.display.dispatch() != .SUCCESS) return error.DispatchFailed;
        }
        return self.events.items;
    }

    /// Non-blocking drain of pending events.
    pub fn pollEvents(self: *Window) ![]const Event {
        self.events.clearRetainingCapacity();
        if (self.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
        _ = self.display.flush();
        return self.events.items;
    }

    pub fn fd(self: *Window) std.posix.fd_t {
        return self.display.getFd();
    }

    /// Release all client-side resources before the process disconnects from
    /// the compositor. Video layers must be destroyed before calling this.
    pub fn deinit(self: *Window) void {
        if (self.frame_cb) |callback| callback.destroy();
        self.frame_cb = null;
        self.dropBuffers();

        xkb.xkb_state_unref(self.xkb_state);
        xkb.xkb_keymap_unref(self.xkb_keymap);
        xkb.xkb_context_unref(self.xkb_context);
        self.xkb_state = null;
        self.xkb_keymap = null;
        self.xkb_context = null;

        if (self.cursor_device) |device| device.destroy();
        if (self.cursor_shape_manager) |manager| manager.destroy();
        if (self.pointer) |pointer| pointer.release();
        if (self.keyboard) |keyboard| keyboard.release();
        if (self.seat) |seat| seat.release();
        self.toplevel.destroy();
        self.xdg_surface.destroy();
        if (self.fractional_scale) |fractional_scale| fractional_scale.destroy();
        if (self.viewport) |viewport| viewport.destroy();
        self.surface.destroy();
        if (self.dmabuf_feedback) |feedback| feedback.destroy();
        if (self.dmabuf_table) |table| std.posix.munmap(table);
        if (self.dmabuf) |dmabuf| dmabuf.destroy();
        if (self.viewporter) |viewporter| viewporter.destroy();
        if (self.fractional_scale_manager) |manager| manager.destroy();
        if (self.subcompositor) |subcompositor| subcompositor.destroy();
        if (self.wm_base) |wm_base| wm_base.destroy();

        self.events.deinit(self.gpa);
        self.dmabuf_formats.deinit(self.gpa);
        self.display.disconnect();
        self.gpa.destroy(self);
    }

    // ---------------------------------------------------------------------
    // Video layers: a wl_subsurface below the main surface, scaled by the
    // compositor via wp_viewport. The UI punches a transparent hole where the
    // SurfaceView sits, so the video shows through.
    pub const VideoLayer = struct {
        const DmabufBuffer = struct {
            layer: *VideoLayer,
            wl_buffer: *wl.Buffer,
            frame: DmabufFrame,
        };

        win: *Window,
        surface: *wl.Surface,
        subsurface: *wl.Subsurface,
        viewport: ?*wp.Viewport,
        src_w: i32 = 0,
        src_h: i32 = 0,
        dst_x: i32 = 0,
        dst_y: i32 = 0,
        dst_w: i32 = 0,
        dst_h: i32 = 0,
        crop_x: i32 = 0,
        crop_y: i32 = 0,
        crop_w: i32 = 0,
        crop_h: i32 = 0,
        full_w: i32 = 0,
        full_h: i32 = 0,
        geometry_visible: bool = false,
        visible: bool = false,
        dmabuf_buffers: std.ArrayList(*DmabufBuffer) = .empty,

        fn dmabufBufferListener(_: *wl.Buffer, event: wl.Buffer.Event, state: *DmabufBuffer) void {
            switch (event) {
                .release => {
                    for (state.layer.dmabuf_buffers.items, 0..) |candidate, index| {
                        if (candidate == state) {
                            _ = state.layer.dmabuf_buffers.orderedRemove(index);
                            break;
                        }
                    }
                    state.wl_buffer.destroy();
                    state.frame.release();
                    state.layer.win.gpa.destroy(state);
                },
            }
        }

        fn configureViewport(self: *VideoLayer) void {
            const vp = self.viewport orelse return;
            if (!self.geometry_visible) {
                if (self.src_w > 0 and self.src_h > 0) {
                    vp.setSource(
                        wl.Fixed.fromDouble(0),
                        wl.Fixed.fromDouble(0),
                        wl.Fixed.fromDouble(@floatFromInt(self.src_w)),
                        wl.Fixed.fromDouble(@floatFromInt(self.src_h)),
                    );
                }
                vp.setDestination(1, 1);
                return;
            }
            if (self.src_w > 0 and self.src_h > 0 and self.full_w > 0 and self.full_h > 0) {
                const source_x = @as(f64, @floatFromInt(self.src_w)) * @as(f64, @floatFromInt(self.crop_x)) / @as(f64, @floatFromInt(self.full_w));
                const source_y = @as(f64, @floatFromInt(self.src_h)) * @as(f64, @floatFromInt(self.crop_y)) / @as(f64, @floatFromInt(self.full_h));
                const source_w = @as(f64, @floatFromInt(self.src_w)) * @as(f64, @floatFromInt(self.crop_w)) / @as(f64, @floatFromInt(self.full_w));
                const source_h = @as(f64, @floatFromInt(self.src_h)) * @as(f64, @floatFromInt(self.crop_h)) / @as(f64, @floatFromInt(self.full_h));
                vp.setSource(
                    wl.Fixed.fromDouble(source_x),
                    wl.Fixed.fromDouble(source_y),
                    wl.Fixed.fromDouble(source_w),
                    wl.Fixed.fromDouble(source_h),
                );
            }
            vp.setDestination(self.dst_w, self.dst_h);
        }

        pub fn setGeometry(
            self: *VideoLayer,
            x: i32,
            y: i32,
            w: i32,
            h: i32,
            crop_x: i32,
            crop_y: i32,
            crop_w: i32,
            crop_h: i32,
            full_w: i32,
            full_h: i32,
        ) void {
            self.dst_x = x;
            self.dst_y = y;
            self.dst_w = w;
            self.dst_h = h;
            self.crop_x = crop_x;
            self.crop_y = crop_y;
            self.crop_w = crop_w;
            self.crop_h = crop_h;
            self.full_w = full_w;
            self.full_h = full_h;
            self.geometry_visible = w > 0 and h > 0 and crop_w > 0 and crop_h > 0 and full_w > 0 and full_h > 0;
            self.subsurface.setPosition(
                if (self.geometry_visible) x else -32768,
                if (self.geometry_visible) y else -32768,
            );
            self.configureViewport();
            self.surface.commit();
            self.win.surface.commit();
            _ = self.win.display.flush();
        }

        fn damageDestination(self: *VideoLayer, fallback_w: i32, fallback_h: i32) void {
            // wl_surface.damage uses surface-local coordinates.  A viewport
            // can make those dimensions differ substantially from the source
            // buffer dimensions (notably for fullscreen fractional scaling).
            // Damaging only the source-sized rectangle leaves the rest of the
            // scaled destination displaying pixels from an older frame.
            const width = if (self.dst_w > 0) self.dst_w else fallback_w;
            const height = if (self.dst_h > 0) self.dst_h else fallback_h;
            self.surface.damage(0, 0, width, height);
        }

        /// Import and present an owned decoded DMA-BUF frame. The frame remains
        /// alive until the compositor sends wl_buffer.release.
        pub fn pushDmabuf(self: *VideoLayer, frame: DmabufFrame) !void {
            errdefer frame.release();
            const factory = self.win.dmabuf orelse return error.DmabufUnavailable;
            if (frame.plane_count == 0 or frame.plane_count > frame.fds.len)
                return error.InvalidDmabuf;

            for (0..frame.plane_count) |index| {
                if (self.win.videoDmabufImportModifier(frame.format, frame.modifiers[index]) == null)
                    return error.UnsupportedDmabufFormat;
            }

            const params = try factory.createParams();
            defer params.destroy();
            for (0..frame.plane_count) |index| {
                const modifier = self.win.videoDmabufImportModifier(frame.format, frame.modifiers[index]).?;
                params.add(frame.fds[index], @intCast(index), frame.offsets[index], frame.strides[index], @truncate(modifier >> 32), @truncate(modifier));
            }
            const wl_buffer = try params.createImmed(frame.width, frame.height, frame.format, .{});
            errdefer wl_buffer.destroy();

            const state = try self.win.gpa.create(DmabufBuffer);
            errdefer self.win.gpa.destroy(state);
            state.* = .{ .layer = self, .wl_buffer = wl_buffer, .frame = frame };
            try self.dmabuf_buffers.append(self.win.gpa, state);
            wl_buffer.setListener(*DmabufBuffer, dmabufBufferListener, state);

            self.src_w = frame.width;
            self.src_h = frame.height;
            self.surface.attach(wl_buffer, 0, 0);
            self.configureViewport();
            self.damageDestination(frame.width, frame.height);
            self.surface.commit();
            if (!self.visible) {
                self.visible = true;
                self.win.surface.commit();
            }
            _ = self.win.display.flush();
        }

        pub fn destroy(self: *VideoLayer) void {
            for (self.dmabuf_buffers.items) |state| {
                state.wl_buffer.destroy();
                state.frame.release();
                self.win.gpa.destroy(state);
            }
            self.dmabuf_buffers.deinit(self.win.gpa);
            if (self.viewport) |vp| vp.destroy();
            self.subsurface.destroy();
            self.surface.destroy();
            self.win.gpa.destroy(self);
        }
    };

    pub fn createVideoLayer(self: *Window) !*VideoLayer {
        const sub = self.subcompositor orelse return error.NoSubcompositor;
        const vsurf = try self.compositor.?.createSurface();
        const subsurf = try sub.getSubsurface(vsurf, self.surface);
        subsurf.setDesync();
        subsurf.placeBelow(self.surface);
        // input falls through to the main surface
        const region = try self.compositor.?.createRegion();
        vsurf.setInputRegion(region);
        region.destroy();
        const vp: ?*wp.Viewport = if (self.viewporter) |v| try v.getViewport(vsurf) else null;
        const layer = try self.gpa.create(VideoLayer);
        layer.* = .{ .win = self, .surface = vsurf, .subsurface = subsurf, .viewport = vp };
        return layer;
    }
};

test "DMA-BUF import modifier requires an exact advertised layout" {
    const format = drm_format_nv12;
    const tiled: u64 = 0x0200000010401b04;
    const linear: u64 = 0;
    const supported = [_]DmabufFormat{
        .{ .format = format, .modifier = drm_format_mod_invalid },
        .{ .format = format, .modifier = linear },
    };

    try std.testing.expectEqual(linear, selectDmabufImportModifier(&supported, format, linear).?);
    try std.testing.expect(selectDmabufImportModifier(&supported, format, tiled) == null);
    try std.testing.expect(selectDmabufImportModifier(&supported, drm_format_p010, tiled) == null);
}
