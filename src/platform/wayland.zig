//! Wayland client window: xdg_toplevel + direct EGL UI + input + vsync.
//! Video frames are imported as DMA-BUF-backed subsurfaces.
const std = @import("std");
const linux = std.os.linux;
const wayland = @import("wayland");
const wl = wayland.client.wl;
const xdg = wayland.client.xdg;
const wp = wayland.client.wp;
const zwp = wayland.client.zwp;
const zwlr = wayland.client.zwlr;
const CursorImage = @import("cursor_image.zig");
pub const TabletSeat = @import("tablet_seat.zig");

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
    /// Teardown without wl_buffer.release must abandon a queue lease, not make
    /// its storage available to a still-connected producer. Video owners may
    /// omit this when release only drops their independently owned frame.
    abandon_fn: ?*const fn (*anyopaque) void = null,
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

    pub fn abandon(self: DmabufFrame) void {
        (self.abandon_fn orelse self.release_fn)(self.context);
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
    pub extern fn xkb_keymap_ref(keymap: *Keymap) *Keymap;
    pub extern fn xkb_keymap_mod_get_index(keymap: *Keymap, name: [*:0]const u8) u32;
    pub extern fn xkb_keymap_unref(keymap: ?*Keymap) void;
    pub extern fn xkb_state_new(keymap: *Keymap) ?*State;
    pub extern fn xkb_state_unref(state: ?*State) void;
    pub extern fn xkb_state_update_mask(state: *State, depressed: u32, latched: u32, locked: u32, layout_depressed: u32, layout_latched: u32, layout_locked: u32) c_int;
    pub extern fn xkb_state_mod_name_is_active(state: *State, name: [*:0]const u8, component: c_int) c_int;
    pub extern fn xkb_state_key_get_utf32(state: *State, key: u32) u32;
    pub extern fn xkb_state_key_get_one_sym(state: *State, key: u32) u32;
    pub extern fn xkb_keymap_key_repeats(keymap: *Keymap, key: u32) c_int;
};

const test_keymap =
    \\xkb_keymap {
    \\ xkb_keycodes { include "evdev+aliases(qwerty)" };
    \\ xkb_types { include "complete" };
    \\ xkb_compatibility { include "complete" };
    \\ xkb_symbols { include "pc+us" };
    \\ xkb_geometry { include "pc(pc105)" };
    \\};
    \\
++ "\x00";

const TestKey = struct { code: u32, shift: bool = false };

fn asciiKey(byte: u8) ?TestKey {
    return switch (byte) {
        'a'...'z' => .{ .code = letterKey(byte) },
        'A'...'Z' => .{ .code = letterKey(byte + ('a' - 'A')), .shift = true },
        '1'...'9' => .{ .code = byte - '1' + 2 },
        '0' => .{ .code = 11 },
        ' ' => .{ .code = 57 },
        '\n', '\r' => .{ .code = 28 },
        '\t' => .{ .code = 15 },
        0x08, 0x7f => .{ .code = 14 },
        '-' => .{ .code = 12 },
        '=' => .{ .code = 13 },
        '[' => .{ .code = 26 },
        ']' => .{ .code = 27 },
        '\\' => .{ .code = 43 },
        ';' => .{ .code = 39 },
        '\'' => .{ .code = 40 },
        '`' => .{ .code = 41 },
        ',' => .{ .code = 51 },
        '.' => .{ .code = 52 },
        '/' => .{ .code = 53 },
        '!' => .{ .code = 2, .shift = true },
        '@' => .{ .code = 3, .shift = true },
        '#' => .{ .code = 4, .shift = true },
        '$' => .{ .code = 5, .shift = true },
        '%' => .{ .code = 6, .shift = true },
        '^' => .{ .code = 7, .shift = true },
        '&' => .{ .code = 8, .shift = true },
        '*' => .{ .code = 9, .shift = true },
        '(' => .{ .code = 10, .shift = true },
        ')' => .{ .code = 11, .shift = true },
        '_' => .{ .code = 12, .shift = true },
        '+' => .{ .code = 13, .shift = true },
        '{' => .{ .code = 26, .shift = true },
        '}' => .{ .code = 27, .shift = true },
        '|' => .{ .code = 43, .shift = true },
        ':' => .{ .code = 39, .shift = true },
        '"' => .{ .code = 40, .shift = true },
        '~' => .{ .code = 41, .shift = true },
        '<' => .{ .code = 51, .shift = true },
        '>' => .{ .code = 52, .shift = true },
        '?' => .{ .code = 53, .shift = true },
        else => null,
    };
}

fn letterKey(byte: u8) u32 {
    return switch (byte) {
        'a' => 30,
        'b' => 48,
        'c' => 46,
        'd' => 32,
        'e' => 18,
        'f' => 33,
        'g' => 34,
        'h' => 35,
        'i' => 23,
        'j' => 36,
        'k' => 37,
        'l' => 38,
        'm' => 50,
        'n' => 49,
        'o' => 24,
        'p' => 25,
        'q' => 16,
        'r' => 19,
        's' => 31,
        't' => 20,
        'u' => 22,
        'v' => 47,
        'w' => 17,
        'x' => 45,
        'y' => 21,
        'z' => 44,
        else => unreachable,
    };
}

fn namedKey(name: []const u8) ?u32 {
    const names = .{
        .{ "Enter", 28 },   .{ "Backspace", 14 }, .{ "Tab", 15 },
        .{ "Escape", 1 },   .{ "Left", 105 },     .{ "Right", 106 },
        .{ "Up", 103 },     .{ "Down", 108 },     .{ "Home", 102 },
        .{ "End", 107 },    .{ "PageUp", 104 },   .{ "PageDown", 109 },
        .{ "Delete", 111 }, .{ "Back", 158 },     .{ "F11", 87 },
    };
    inline for (names) |entry| if (std.ascii.eqlIgnoreCase(name, entry[0])) return entry[1];
    return null;
}

fn createMemoryFile(name: [*:0]const u8) !std.Io.File.Handle {
    const result = linux.memfd_create(name, linux.MFD.CLOEXEC);
    if (linux.errno(result) != .SUCCESS) return error.MemoryFileCreationFailed;
    return @intCast(result);
}

fn inputTimestamp(io: std.Io) u32 {
    return @truncate(@as(u64, @intCast(std.Io.Clock.awake.now(io).toMilliseconds())));
}

fn interpolateCoordinate(from: u32, to: u32, step: usize, steps: usize) u32 {
    const start: i64 = from;
    const delta: i64 = @as(i64, to) - start;
    return @intCast(start + @divTrunc(delta * @as(i64, @intCast(step)), @as(i64, @intCast(steps))));
}

fn monotonicNs(io: std.Io) u64 {
    return @intCast(std.Io.Clock.awake.now(io).nanoseconds);
}

pub const keyboard_scan_count = 768;

/// A queued layout retains the exact keymap/group that preceded its key
/// events, even if another keymap arrives before the presenter drains the seat.
pub const KeyboardLayout = struct {
    keymap: *xkb.Keymap,
    source_bytes: usize = 0,
    group: u32,
    generation: i32,

    /// Base XKB symbols describe non-character remaps such as Caps-as-Control.
    /// Character keys still use the KCM matrix, independently of physical layout.
    pub fn symbols(self: KeyboardLayout) ![keyboard_scan_count]i32 {
        const state = xkb.xkb_state_new(self.keymap) orelse return error.KeyboardStateAllocationFailed;
        defer xkb.xkb_state_unref(state);
        _ = xkb.xkb_state_update_mask(state, 0, 0, 0, 0, 0, self.group);
        var result: [keyboard_scan_count]i32 = undefined;
        for (&result, 0..) |*symbol, scan| symbol.* = @intCast(xkb.xkb_state_key_get_one_sym(state, @intCast(scan + 8)));
        return result;
    }

    pub fn characters(self: KeyboardLayout) ![keyboard_scan_count * 64]i32 {
        const state = xkb.xkb_state_new(self.keymap) orelse return error.KeyboardStateAllocationFailed;
        defer xkb.xkb_state_unref(state);
        const names = [_][*:0]const u8{ "Shift", "Lock", "Mod5", "Mod2", "Control", "Mod1" };
        var masks: [names.len]u32 = undefined;
        for (names, 0..) |name, i| {
            const index = xkb.xkb_keymap_mod_get_index(self.keymap, name);
            masks[i] = if (index < 32) @as(u32, 1) << @intCast(index) else 0;
        }
        var result: [keyboard_scan_count * 64]i32 = undefined;
        for (0..64) |combination| {
            var mask: u32 = 0;
            for (masks, 0..) |bit, i| if (combination & (@as(usize, 1) << @intCast(i)) != 0) {
                mask |= bit;
            };
            _ = xkb.xkb_state_update_mask(state, mask, 0, 0, 0, 0, self.group);
            for (0..keyboard_scan_count) |scan| {
                const key: u32 = @intCast(scan + 8);
                const unicode = xkb.xkb_state_key_get_utf32(state, key);
                result[scan * 64 + combination] = @intCast(if (unicode != 0) unicode else deadAccent(xkb.xkb_state_key_get_one_sym(state, key)));
            }
        }
        return result;
    }

    fn deadAccent(symbol: u32) u32 {
        // XKB_KEY_dead_grave..dead_belowcomma, from pinned xkbcommon-keysyms.h.
        const accents = [_]u32{ 0x0300, 0x0301, 0x0302, 0x0303, 0x0304, 0x0306, 0x0307, 0x0308, 0x030a, 0x030b, 0x030c, 0x0327, 0x0328, 0x0345, 0x3099, 0x309a, 0x0323, 0x0309, 0x031b, 0x0335, 0x0313, 0x0314, 0x030f, 0x0325, 0x0331, 0x032d, 0x0330, 0x032e, 0x0324, 0x0311, 0x0326 };
        if (symbol >= 0xfe50 and symbol <= 0xfe6e) return accents[symbol - 0xfe50];
        return switch (symbol) {
            0xfe90 => 0x0332,
            0xfe91 => 0x030d,
            0xfe92 => 0x0329,
            0xfe93 => 0x0338,
            else => 0,
        };
    }
};

pub const KeyboardFocus = struct {
    active: bool,
    held: [12]u64 = @splat(0),

    fn entered(scans: []const u32) KeyboardFocus {
        var result: KeyboardFocus = .{ .active = true };
        for (scans) |scan| {
            if (scan >= 768) continue;
            result.held[scan / 64] |= @as(u64, 1) << @intCast(scan % 64);
        }
        return result;
    }
};

pub const Event = union(enum) {
    seat_capabilities: struct { generation: i32, mask: i32 },
    close,
    configure: struct {
        width: i32,
        height: i32,
        buffer_width: i32 = 0,
        buffer_height: i32 = 0,
        generation: i64 = 0,
        serial: u32 = 0,
    },
    fullscreen_changed: bool,
    suspended_changed: bool,
    pointer_motion: struct { x: f64, y: f64 },
    pointer_enter: struct { x: f64, y: f64 },
    pointer_leave: struct { x: f64, y: f64 },
    pointer_button: struct { button: u32, pressed: bool, x: f64, y: f64 },
    pointer_axis: struct { x: f64, y: f64, horizontal: f64, vertical: f64 },
    pointer_cancel,
    pointer_relative: struct { dx: f64, dy: f64 },
    pointer_capture_changed: struct { enabled: bool, sequence: i32 },
    key: struct { keycode: u32, pressed: bool, unicode: u32, keysym: u32, repeatable: bool = true },
    keyboard_focus: KeyboardFocus,
    keyboard_layout: KeyboardLayout,
    keyboard_modifiers: struct { effective: u32, persistent: u32 },
    keyboard_repeat_configuration: struct { rate: i32, delay_ms: i32 },
    keyboard_reset,
    touch: struct {
        action: TouchAction,
        id: i32,
        x: f64,
        y: f64,
        major: f64,
        minor: f64,
        orientation: f64,
    },
    touch_cancel,
    tablet: TabletSeat.Event,
    frame_done: struct { time_ms: u32 },
};

/// Bounded session chronology: draining per-window queues would reorder a
/// keyboard leave/enter/key sequence whenever the new task has an earlier slot.
pub const OrderedEvents = struct {
    pub const Item = struct { window: ?*Window, event: Event };
    const limit = 4096;
    // XKB snapshots retain parsed maps, unlike ordinary key events. Charge
    // both borrowed/delivered and pending snapshots, including group changes
    // sharing a map, so neither chronology buffer escapes the budget.
    const layout_limit = 16;
    const layout_source_limit = 32 * 1024 * 1024;
    retained_layouts: usize = 0,
    retained_layout_source_bytes: usize = 0,
    pending: std.ArrayList(Item) = .empty,
    delivered: std.ArrayList(Item) = .empty,
    failed: bool = false,

    fn push(self: *OrderedEvents, gpa: std.mem.Allocator, window: ?*Window, event: Event) void {
        const layout_overflow = event == .keyboard_layout and
            (self.retained_layouts == layout_limit or
                event.keyboard_layout.source_bytes > layout_source_limit - self.retained_layout_source_bytes);
        if (self.failed or self.pending.items.len == limit or layout_overflow) {
            self.failed = true;
            release(event);
            return;
        }
        self.pending.append(gpa, .{ .window = window, .event = event }) catch {
            release(event);
            self.failed = true;
            return;
        };
        if (event == .keyboard_layout) {
            self.retained_layouts += 1;
            self.retained_layout_source_bytes += event.keyboard_layout.source_bytes;
        }
    }

    fn removeWindow(self: *OrderedEvents, window: *Window) void {
        inline for (.{ &self.pending, &self.delivered }) |items| {
            var index: usize = 0;
            while (index < items.items.len) {
                if (items.items[index].window == window) {
                    self.releaseRetained(items.orderedRemove(index).event);
                } else index += 1;
            }
        }
    }

    fn release(event: Event) void {
        if (event == .keyboard_layout) xkb.xkb_keymap_unref(event.keyboard_layout.keymap);
    }

    fn releaseRetained(self: *OrderedEvents, event: Event) void {
        if (event == .keyboard_layout) {
            self.retained_layouts -= 1;
            self.retained_layout_source_bytes -= event.keyboard_layout.source_bytes;
        }
        release(event);
    }

    pub fn take(self: *OrderedEvents) []const Item {
        for (self.delivered.items) |item| self.releaseRetained(item.event);
        self.delivered.clearRetainingCapacity();
        std.mem.swap(std.ArrayList(Item), &self.pending, &self.delivered);
        return self.delivered.items;
    }

    fn deinit(self: *OrderedEvents, gpa: std.mem.Allocator) void {
        for (self.pending.items) |item| self.releaseRetained(item.event);
        for (self.delivered.items) |item| self.releaseRetained(item.event);
        self.pending.deinit(gpa);
        self.delivered.deinit(gpa);
    }
};

pub const WakeSources = struct {
    video: ?std.posix.fd_t = null,
    looper: ?std.posix.fd_t = null,
    renderer: ?std.posix.fd_t = null,
    optimizer: ?std.posix.fd_t = null,
    control_listener: ?std.posix.fd_t = null,
    control_client: ?std.posix.fd_t = null,
    control_client_write: bool = false,
    /// Delay until the next Android MessageQueue item, or -1 when none is scheduled.
    timeout_ms: i32 = -1,
};

pub const WaitResult = struct {
    events: []const Event,
    video_ready: bool = false,
    looper_ready: bool = false,
    renderer_ready: bool = false,
    optimizer_ready: bool = false,
    control_ready: bool = false,
    timed_out: bool = false,
};

pub const TouchAction = enum(u8) { down, up, move, update };

const PointerAxisFrame = struct {
    horizontal: f64 = 0,
    vertical: f64 = 0,
    horizontal_discrete: ?i32 = null,
    vertical_discrete: ?i32 = null,
    horizontal_value120: ?i32 = null,
    vertical_value120: ?i32 = null,

    fn reset(self: *PointerAxisFrame) void {
        self.* = .{};
    }

    fn androidValue(continuous: f64, discrete: ?i32, value120: ?i32) f64 {
        if (value120) |value| return @as(f64, @floatFromInt(value)) / -120.0;
        if (discrete) |value| return -@as(f64, @floatFromInt(value));
        // Wayland continuous axis values are expressed in surface-coordinate units, while
        // Android scroll axes are dimensionless. This follows Waydroid's InputReader-facing
        // normalization but retains fractional deltas until a complete wl_pointer frame.
        return continuous / -10.0;
    }
};

const TouchPoint = struct {
    active: bool = false,
    id: i32 = -1,
    x: f64 = 0,
    y: f64 = 0,
    major: f64 = 0,
    minor: f64 = 0,
    orientation: f64 = 0,
};

const KeyRepeat = struct {
    keycode: u32,
    unicode: u32,
    keysym: u32,
    next_ns: u64,
    period_ns: u64,
};

const FullscreenState = struct {
    requested: bool = false,
    actual: bool = false,
    pending: bool = false,

    fn init(requested: bool) FullscreenState {
        return .{ .requested = requested, .pending = requested };
    }

    /// Returns true when an xdg-shell request must be sent.
    fn request(self: *FullscreenState, requested: bool) bool {
        if (requested == self.requested) return false;
        self.requested = requested;
        self.pending = true;
        return true;
    }

    /// Returns true when the compositor's effective state changed.
    fn configure(self: *FullscreenState, actual: bool) bool {
        const changed = actual != self.actual;
        self.actual = actual;
        if (self.pending) {
            // A configure for the old state may already be in flight when a request is sent.
            // Keep the requested state until the compositor acknowledges that transition.
            if (actual == self.requested) self.pending = false;
        } else {
            // With no client transition pending, the configure represents a compositor/F11
            // transition and becomes the new requested baseline.
            self.requested = actual;
        }
        return changed;
    }
};

/// wl_output records have stable addresses until the session ends. A removed
/// output's membership is cleared before its bounded slot can be reused.
pub const Output = struct {
    session: *Session = undefined,
    proxy: ?*wl.Output = null,
    global_name: u32 = 0,
    name_storage: [256]u8 = undefined,
    name_length: usize = 0,
    scale: i32 = 1,
    pending_scale: i32 = 1,
    width: i32 = 0,
    height: i32 = 0,
    refresh_millihz: i32 = 0,
    pending_width: i32 = 0,
    pending_height: i32 = 0,
    pending_refresh_millihz: i32 = 0,
    transform: wl.Output.Transform = .normal,
    pending_transform: wl.Output.Transform = .normal,
    revision: u64 = 0,

    fn commit(self: *Output) void {
        self.scale = self.pending_scale;
        self.width = self.pending_width;
        self.height = self.pending_height;
        self.refresh_millihz = self.pending_refresh_millihz;
        self.transform = self.pending_transform;
        self.revision += 1;
        for (self.session.windows.items) |window| window.updatePreferredScale();
    }

    fn listener(proxy: *wl.Output, event: wl.Output.Event, self: *Output) void {
        switch (event) {
            .geometry => |geometry| self.pending_transform = geometry.transform,
            .mode => |mode| if (mode.flags.current) {
                self.pending_width = mode.width;
                self.pending_height = mode.height;
                self.pending_refresh_millihz = mode.refresh;
            },
            .scale => |scale| {
                if (scale.factor < 1 or scale.factor > 128) {
                    self.session.output_failure = error.InvalidOutputScale;
                    return;
                }
                self.pending_scale = scale.factor;
            },
            .done => self.commit(),
            .name => |named| {
                const name = std.mem.span(named.name);
                if (name.len > self.name_storage.len) {
                    self.session.output_failure = error.OutputNameTooLong;
                    return;
                }
                @memcpy(self.name_storage[0..name.len], name);
                self.name_length = name.len;
            },
            .description => {},
        }
        // wl_output v1 has no done/scale; each update is complete on its own.
        if (proxy.getVersion() < 2) self.commit();
    }

    fn deinit(self: *Output) void {
        const proxy = self.proxy orelse return;
        if (proxy.getVersion() >= 3) proxy.release() else {
            const base: *wl.Proxy = @ptrCast(proxy);
            base.destroy();
        }
        self.proxy = null;
    }
};

/// One compositor connection, registry and seat for every task in the session.
/// Protocol callbacks and surface mutations run on the owning event thread.
/// Windows retain their own geometry, gestures and buffer leases.
pub const Session = struct {
    gpa: std.mem.Allocator,
    io: std.Io,
    windows: std.ArrayList(*Window) = .empty,
    pointer_focus: ?*Window = null,
    keyboard_focus: ?*Window = null,
    seat_generation: i32 = 1,
    seat_capabilities: i32 = 0,
    display: *wl.Display,
    registry: *wl.Registry,
    compositor: ?*wl.Compositor = null,
    subcompositor: ?*wl.Subcompositor = null,
    shm: ?*wl.Shm = null,
    viewporter: ?*wp.Viewporter = null,
    fractional_scale_manager: ?*wp.FractionalScaleManagerV1 = null,
    dmabuf: ?*zwp.LinuxDmabufV1 = null,
    dmabuf_feedback: ?*zwp.LinuxDmabufFeedbackV1 = null,
    dmabuf_table: ?[]align(@alignOf(DmabufTableEntry)) u8 = null,
    dmabuf_formats: std.ArrayList(DmabufFormat) = .empty,
    wm_base: ?*xdg.WmBase = null,
    seat: ?*wl.Seat = null,
    outputs: [64]Output = @splat(.{}),
    output_failure: ?anyerror = null,
    protocol_options: ProtocolOptions = .{},
    pointer: ?*wl.Pointer = null,
    keyboard: ?*wl.Keyboard = null,
    touch_device: ?*wl.Touch = null,
    tablet_manager: ?*zwp.TabletManagerV2 = null,
    tablet_manager_global: ?u32 = null,
    tablets: ?*TabletSeat = null,
    tablet_generation: i32 = 0,
    tablet_failure: ?anyerror = null,
    pointer_constraints: ?*zwp.PointerConstraintsV1 = null,
    relative_pointer_manager: ?*zwp.RelativePointerManagerV1 = null,
    seat_global_name: ?u32 = null,
    pointer_constraints_global_name: ?u32 = null,
    relative_pointer_global_name: ?u32 = null,
    cursor_shape_manager: ?*wp.CursorShapeManagerV1 = null,
    cursor_device: ?*wp.CursorShapeDeviceV1 = null,
    virtual_pointer_manager: ?*zwlr.VirtualPointerManagerV1 = null,
    virtual_pointer: ?*zwlr.VirtualPointerV1 = null,
    virtual_keyboard_manager: ?*zwp.VirtualKeyboardManagerV1 = null,
    virtual_keyboard: ?*zwp.VirtualKeyboardV1 = null,
    xkb_context: ?*xkb.Context = null,
    xkb_keymap: ?*xkb.Keymap = null,
    xkb_keymap_source_bytes: usize = 0,
    xkb_state: ?*xkb.State = null,
    keyboard_group: u32 = 0,
    keyboard_state: KeyboardFocus = .{ .active = false },
    keyboard_layout_generation: i32 = 0,
    keyboard_repeat_rate: i32 = 0,
    keyboard_repeat_delay_ms: i32 = 0,
    generate_key_repeats: bool = true,
    ordered_events: ?OrderedEvents = null,

    pub const ProtocolOptions = struct {
        virtual_pointer_max_version: u32 = 2,
        compositor_max_version: u32 = 6,
        xdg_shell_max_version: u32 = 6,
        tablet: bool = true,
        fractional_scale: bool = true,
        cursor_shape: bool = true,
    };

    pub fn init(gpa: std.mem.Allocator, io: std.Io) !*Session {
        return initWithOptions(gpa, io, .{});
    }

    /// Capability caps let the existing harness exercise older compositor APIs.
    pub fn initWithOptions(gpa: std.mem.Allocator, io: std.Io, options: ProtocolOptions) !*Session {
        if (options.virtual_pointer_max_version < 1 or options.virtual_pointer_max_version > 2 or
            options.compositor_max_version < 4 or options.compositor_max_version > 6)
            return error.InvalidProtocolOptions;
        const self = try gpa.create(Session);
        errdefer gpa.destroy(self);
        const display = try wl.Display.connect(null);
        errdefer display.disconnect();
        self.* = .{ .gpa = gpa, .io = io, .display = display, .registry = try display.getRegistry(), .protocol_options = options };
        errdefer self.destroyGlobals();
        self.registry.setListener(*Session, registryListener, self);
        if (display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        self.requireGlobals();
        if (self.output_failure) |failure| return failure;
        if (self.tablet_failure) |failure| return failure;
        if (self.tablets) |tablets| if (tablets.failed) |failure| return failure;
        return self;
    }

    pub fn createWindow(self: *Session, width: i32, height: i32, title: [:0]const u8, fullscreen: bool) !*Window {
        return self.createWindowWithAppId(width, height, title, "org.androlinzberg", fullscreen);
    }

    /// Compositors apply application rules at first map, before later metadata
    /// updates. Install the Android task's identity before the initial commit.
    pub fn createWindowWithAppId(self: *Session, width: i32, height: i32, title: [:0]const u8, app_id: [:0]const u8, fullscreen: bool) !*Window {
        return Window.initInSession(self, width, height, title, app_id, fullscreen);
    }

    /// Dispatch the shared connection and consume eventfd notifications owned by
    /// the task presenter. Events remain queued per window until takeEvents().
    /// The caller must inspect every ready slot even when a Wayland event also
    /// arrives. Files are borrowed for this call; libwayland retains its socket.
    pub fn dispatch(self: *Session, wakes: []const std.Io.File, ready: []bool, timeout_ms: i32) !void {
        if (wakes.len == 0 or wakes.len > 65 or ready.len != wakes.len) return error.InvalidWakeSources;
        @memset(ready, false);
        if (self.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
        if (self.output_failure) |failure| return failure;
        if (self.tablet_failure) |failure| return failure;
        if (self.tablets) |tablets| if (tablets.failed) |failure| return failure;
        var timeout = timeout_ms;
        if (self.pointer_focus) |window| {
            const cursor_timeout = window.updateCursorAnimation();
            if (cursor_timeout >= 0) timeout = if (timeout < 0) cursor_timeout else @min(timeout, cursor_timeout);
        }
        if (self.ordered_events) |*events| {
            if (events.failed) return error.SeatEventQueueOverflow;
            if (events.pending.items.len != 0) timeout = 0;
        }
        for (self.windows.items) |window| {
            const tablet_cursor_timeout = window.updateTabletCursorAnimations();
            if (tablet_cursor_timeout >= 0) timeout = if (timeout < 0) tablet_cursor_timeout else @min(timeout, tablet_cursor_timeout);
            _ = window.emitKeyRepeatIfDue();
            if (window.events.items.len != 0) timeout = 0;
            const repeat = window.keyRepeatTimeoutMs();
            if (repeat >= 0) timeout = if (timeout < 0) repeat else @min(timeout, repeat);
        }
        while (!self.display.prepareRead()) {
            if (self.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
            if (self.output_failure) |failure| return failure;
            if (self.tablet_failure) |failure| return failure;
            if (self.tablets) |tablets| if (tablets.failed) |failure| return failure;
            timeout = 0;
        }
        var read_prepared = true;
        defer if (read_prepared) self.display.cancelRead();
        while (true) switch (self.display.flush()) {
            .SUCCESS => break,
            .INTR => continue,
            .AGAIN => {
                // std.Io has no readiness-only writable operation. Retry a
                // congested libwayland flush on a bounded timer while continuing
                // to service input and producer notifications normally.
                timeout = if (timeout < 0) 1 else @min(timeout, 1);
                break;
            },
            else => return error.FlushFailed,
        };

        var storage: [66]std.Io.Operation.Storage = undefined;
        var batch = std.Io.Batch.init(storage[0 .. wakes.len + 1]);
        defer batch.cancel(self.io);
        // The pinned std.Io.Threaded batches file operations behind readiness,
        // including a zero-byte read. This borrows the libwayland socket without
        // consuming its wire bytes or SCM_RIGHTS. Avoid net_receive(MSG_PEEK):
        // this Zig version leaves an immediately completed receive linked in
        // Batch.submitted, corrupting cancellation when bytes are already ready.
        const borrowed: std.Io.File = .{
            .handle = self.display.getFd(),
            .flags = .{ .nonblocking = true },
        };
        var empty: [0]u8 = .{};
        batch.addAt(0, .{ .file_read_streaming = .{ .file = borrowed, .data = &.{&empty} } });
        var counters: [65]u64 = undefined;
        var vectors: [65][1][]u8 = undefined;
        for (wakes, 0..) |file, index| {
            vectors[index][0] = std.mem.asBytes(&counters[index]);
            batch.addAt(@intCast(index + 1), .{ .file_read_streaming = .{
                .file = file,
                .data = &vectors[index],
            } });
        }
        const deadline: std.Io.Timeout = if (timeout < 0) .none else .{ .duration = .{
            .raw = .fromMilliseconds(timeout),
            .clock = .awake,
        } };
        batch.awaitConcurrent(self.io, deadline) catch |err| switch (err) {
            error.Timeout => {},
            else => return err,
        };
        // Cancellation may race a completed eventfd read. Collect completions
        // after cancellation so no consumed notification is lost.
        batch.cancel(self.io);
        var readable = false;
        while (batch.next()) |completion| {
            if (completion.index == 0) {
                _ = try completion.result.file_read_streaming;
                readable = true;
            } else {
                const count = try completion.result.file_read_streaming;
                if (count != @sizeOf(u64)) return error.InvalidWakeNotification;
                ready[completion.index - 1] = true;
            }
        }
        if (readable) {
            read_prepared = false;
            if (self.display.readEvents() != .SUCCESS) return error.DispatchFailed;
            if (self.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
            if (self.output_failure) |failure| return failure;
            if (self.tablet_failure) |failure| return failure;
            if (self.tablets) |tablets| if (tablets.failed) |failure| return failure;
        }
        for (self.windows.items) |window| _ = window.emitKeyRepeatIfDue();
        if (self.ordered_events) |*events| if (events.failed) return error.SeatEventQueueOverflow;
    }

    fn surfaceWindow(self: *Session, surface: ?*wl.Surface) ?*Window {
        const target = surface orelse return null;
        for (self.windows.items) |window| {
            if ((window.surface_created and target == window.surface) or
                (window.ui_initialized and target == window.ui_surface)) return window;
        }
        return null;
    }

    fn removeWindow(self: *Session, window: *Window) void {
        if (self.tablets) |tablets| tablets.detachTarget(window);
        if (self.ordered_events) |*events| events.removeWindow(window);
        if (self.pointer_focus == window) self.pointer_focus = null;
        if (self.keyboard_focus == window) self.keyboard_focus = null;
        for (self.windows.items, 0..) |candidate, index| {
            if (candidate == window) {
                _ = self.windows.orderedRemove(index);
                break;
            }
        }
    }

    fn pointerListener(pointer: *wl.Pointer, event: wl.Pointer.Event, self: *Session) void {
        if (event == .enter) self.pointer_focus = self.surfaceWindow(event.enter.surface);
        if (self.pointer_focus) |window| Window.pointerListener(pointer, event, window);
        if (event == .leave) self.pointer_focus = null;
    }

    fn keyboardListener(keyboard: *wl.Keyboard, event: wl.Keyboard.Event, self: *Session) void {
        switch (event) {
            .keymap => |k| {
                defer (std.Io.File{
                    .handle = k.fd,
                    .flags = .{ .nonblocking = false },
                }).close(self.io);
                if (k.format != .xkb_v1) return;
                if (k.size == 0 or k.size > 16 * 1024 * 1024) return;
                const data = self.gpa.alloc(u8, k.size) catch return;
                defer self.gpa.free(data);
                const file: std.Io.File = .{ .handle = k.fd, .flags = .{ .nonblocking = false } };
                const count = file.readPositionalAll(self.io, data, 0) catch return;
                if (count != data.len or data[data.len - 1] != 0) return;
                if (self.xkb_context == null) self.xkb_context = xkb.xkb_context_new(0);
                const ctx = self.xkb_context orelse return;
                // data is a NUL-terminated keymap string
                const keymap = xkb.xkb_keymap_new_from_string(ctx, @ptrCast(data.ptr), 1, 0) orelse return; // 1 = XKB_KEYMAP_FORMAT_TEXT_V1
                xkb.xkb_state_unref(self.xkb_state);
                xkb.xkb_keymap_unref(self.xkb_keymap);
                self.xkb_keymap = keymap;
                self.xkb_keymap_source_bytes = k.size;
                self.xkb_state = xkb.xkb_state_new(keymap);
                self.keyboard_group = 0;
                self.layoutChanged();
            },
            .modifiers => |m| {
                if (self.keyboard_group != m.group) {
                    self.keyboard_group = m.group;
                    self.layoutChanged();
                }
                if (self.xkb_state) |st| {
                    _ = xkb.xkb_state_update_mask(st, m.mods_depressed, m.mods_latched, m.mods_locked, 0, 0, m.group);
                    self.queueModifiers();
                }
            },
            .enter => |entered| {
                if (self.keyboard_focus) |old| old.push(.keyboard_reset);
                self.keyboard_focus = self.surfaceWindow(entered.surface);
                self.keyboard_state = KeyboardFocus.entered(entered.keys.*.slice(u32));
                if (self.keyboard_focus) |window| window.push(.{ .keyboard_focus = self.keyboard_state });
            },
            .leave => {
                if (self.keyboard_focus) |window| Window.keyboardListener(keyboard, event, window);
                self.keyboard_focus = null;
                self.keyboard_state = .{ .active = false };
            },
            .key => |key| {
                if (key.key < 768) {
                    const mask = @as(u64, 1) << @intCast(key.key % 64);
                    if (key.state == .pressed) self.keyboard_state.held[key.key / 64] |= mask else self.keyboard_state.held[key.key / 64] &= ~mask;
                }
                if (self.keyboard_focus) |window| Window.keyboardListener(keyboard, event, window);
            },
            .repeat_info => |repeat| {
                self.keyboard_repeat_rate = repeat.rate;
                self.keyboard_repeat_delay_ms = repeat.delay;
                if (repeat.rate == 0) for (self.windows.items) |window| {
                    window.key_repeat = null;
                };
                self.queueRepeat();
            },
        }
    }

    pub fn keyboardModifiers(self: *Session) [2]i32 {
        const state = self.xkb_state orelse return .{ 0, 0 };
        // Match the modifier basis used by KeyboardLayout.characters. Android
        // right Alt represents XKB Level3; ordinary Alt is the left-Alt basis.
        const names = .{ "Shift", "Lock", "Mod5", "Mod2", "Control", "Mod1", "Mod4" };
        const bits = [_]u32{ 0x1, 0x100000, 0x22, 0x200000, 0x1000, 0x12, 0x10000 };
        var meta: u32 = 0;
        var persistent: u32 = 0;
        inline for (names, bits) |name, bit| {
            if (xkb.xkb_state_mod_name_is_active(state, name, 1 << 3) > 0) meta |= bit;
            if (xkb.xkb_state_mod_name_is_active(state, name, (1 << 1) | (1 << 2)) > 0) persistent |= bit;
        }
        return .{ @intCast(meta), @intCast(persistent) };
    }

    fn queueModifiers(self: *Session) void {
        const events = if (self.ordered_events) |*events| events else return;
        const meta = self.keyboardModifiers();
        events.push(self.gpa, null, .{ .keyboard_modifiers = .{ .effective = @intCast(meta[0]), .persistent = @intCast(meta[1]) } });
    }

    fn queueRepeat(self: *Session) void {
        if (self.ordered_events) |*events| events.push(self.gpa, null, .{ .keyboard_repeat_configuration = .{
            .rate = @max(0, self.keyboard_repeat_rate),
            .delay_ms = @max(0, self.keyboard_repeat_delay_ms),
        } });
    }

    pub fn seatSnapshot(self: *Session) ![8 + keyboard_scan_count * 64 + keyboard_scan_count]i32 {
        // Binding a seat can enqueue get_keyboard while the first roundtrip is
        // dispatching capabilities. The second receives its keymap/repeat info.
        if (self.display.roundtrip() != .SUCCESS or self.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        var result: [8 + keyboard_scan_count * 64 + keyboard_scan_count]i32 = @splat(0);
        const meta = self.keyboardModifiers();
        result[0..8].* = .{ 2, self.seat_generation, self.seat_capabilities, self.keyboard_layout_generation, @max(0, self.keyboard_repeat_rate), @max(0, self.keyboard_repeat_delay_ms), meta[0], meta[1] };
        if (self.xkb_keymap) |map| {
            const layout: KeyboardLayout = .{ .keymap = map, .generation = self.keyboard_layout_generation, .group = self.keyboard_group };
            const characters = try layout.characters();
            const symbols = try layout.symbols();
            @memcpy(result[8 .. 8 + keyboard_scan_count * 64], &characters);
            @memcpy(result[8 + keyboard_scan_count * 64 ..], &symbols);
        }
        return result;
    }

    /// Register a persistent keyboard for the existing headless harness. This
    /// creates a device/keymap only; UI actions still use the test-control API.
    pub fn prepareTestKeyboard(self: *Session) !void {
        _ = try self.ensureVirtualKeyboard();
        if (self.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    }

    /// Compositor shortcut injection for protocol/lifetime gates in tools/shot.sh.
    pub fn testKeyboardShortcut(self: *Session, scan: u32, modifiers: u32) !void {
        const keyboard = try self.ensureVirtualKeyboard();
        const time = inputTimestamp(self.io);
        keyboard.modifiers(modifiers, 0, 0, 0);
        keyboard.key(time, scan, 1);
        keyboard.key(time +% 1, scan, 0);
        keyboard.modifiers(0, 0, 0, 0);
        if (self.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    fn ensureVirtualKeyboard(self: *Session) !*zwp.VirtualKeyboardV1 {
        if (self.virtual_keyboard) |keyboard| return keyboard;
        const manager = self.virtual_keyboard_manager orelse return error.VirtualKeyboardUnavailable;
        const seat = self.seat orelse return error.VirtualKeyboardUnavailable;
        const keyboard = try manager.createVirtualKeyboard(seat);
        errdefer keyboard.destroy();
        try self.setTestKeymap(keyboard, test_keymap);
        self.virtual_keyboard = keyboard;
        return keyboard;
    }

    pub fn testKeyboardLayout(self: *Session, german: bool) !void {
        const keyboard = try self.ensureVirtualKeyboard();
        const source = try std.mem.replaceOwned(u8, self.gpa, test_keymap, "pc+us", if (german) "pc+de" else "pc+us");
        defer self.gpa.free(source);
        try self.setTestKeymap(keyboard, source);
        if (self.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    }

    fn setTestKeymap(self: *Session, keyboard: *zwp.VirtualKeyboardV1, source: []const u8) !void {
        const keymap_fd = try createMemoryFile("azk-test-keymap");
        const keymap_file = std.Io.File{
            .handle = keymap_fd,
            .flags = .{ .nonblocking = false },
        };
        defer keymap_file.close(self.io);
        keymap_file.writeStreamingAll(self.io, source) catch return error.KeymapWriteFailed;
        keyboard.keymap(.xkb_v1, keymap_fd, @intCast(source.len));
    }

    fn layoutChanged(self: *Session) void {
        self.keyboard_layout_generation = std.math.add(i32, self.keyboard_layout_generation, 1) catch {
            if (self.ordered_events) |*events| events.failed = true;
            return;
        };
        self.queueLayout();
    }

    fn queueLayout(self: *Session) void {
        const events = if (self.ordered_events) |*events| events else return;
        const map = self.xkb_keymap orelse return;
        events.push(self.gpa, null, .{ .keyboard_layout = .{
            .keymap = xkb.xkb_keymap_ref(map),
            .source_bytes = self.xkb_keymap_source_bytes,
            .group = self.keyboard_group,
            .generation = self.keyboard_layout_generation,
        } });
    }

    fn touchListener(touch: *wl.Touch, event: wl.Touch.Event, self: *Session) void {
        if (event == .down) {
            if (self.surfaceWindow(event.down.surface)) |window| Window.touchListener(touch, event, window);
        } else {
            // Touch IDs belong to the seat. Only the window that accepted DOWN
            // has that ID, and keeps it through motion outside its own bounds.
            for (self.windows.items) |window| Window.touchListener(touch, event, window);
        }
    }

    fn releasePointer(self: *Session) void {
        for (self.windows.items) |window| {
            window.setPointerCapture(false) catch unreachable;
            window.pointer_axis_frame.reset();
            window.push(.pointer_cancel);
        }
        if (self.cursor_device) |device| device.destroy();
        self.cursor_device = null;
        if (self.pointer) |pointer| pointer.release();
        self.pointer = null;
        self.pointer_focus = null;
    }

    fn releaseKeyboard(self: *Session) void {
        for (self.windows.items) |window| {
            window.key_repeat = null;
            window.push(.keyboard_reset);
        }
        if (self.keyboard) |keyboard| keyboard.release();
        self.keyboard = null;
        self.keyboard_focus = null;
        self.keyboard_state = .{ .active = false };
    }

    fn releaseTouch(self: *Session) void {
        for (self.windows.items) |window| window.cancelTouches();
        if (self.touch_device) |touch| touch.release();
        self.touch_device = null;
    }

    fn releasePointerCapture(self: *Session) void {
        for (self.windows.items) |window| window.setPointerCapture(false) catch unreachable;
    }

    pub fn deinit(self: *Session) void {
        std.debug.assert(self.windows.items.len == 0);
        self.destroyGlobals();
        self.display.disconnect();
        self.gpa.destroy(self);
    }

    fn destroyGlobals(self: *Session) void {
        if (self.tablets) |tablets| tablets.deinit();
        self.tablets = null;
        if (self.tablet_manager) |manager| manager.destroy();
        self.tablet_manager = null;
        self.releasePointer();
        self.releaseKeyboard();
        self.releaseTouch();
        xkb.xkb_state_unref(self.xkb_state);
        xkb.xkb_keymap_unref(self.xkb_keymap);
        xkb.xkb_context_unref(self.xkb_context);
        if (self.virtual_pointer) |pointer| pointer.destroy();
        if (self.virtual_keyboard) |keyboard| keyboard.destroy();
        if (self.virtual_pointer_manager) |manager| manager.destroy();
        if (self.virtual_keyboard_manager) |manager| manager.destroy();
        if (self.cursor_shape_manager) |manager| manager.destroy();
        if (self.relative_pointer_manager) |manager| manager.destroy();
        if (self.pointer_constraints) |constraints| constraints.destroy();
        if (self.seat) |seat| seat.release();
        if (self.dmabuf_feedback) |feedback| feedback.destroy();
        if (self.dmabuf_table) |table| self.gpa.free(table);
        if (self.dmabuf) |dmabuf| dmabuf.destroy();
        if (self.viewporter) |viewporter| viewporter.destroy();
        if (self.fractional_scale_manager) |manager| manager.destroy();
        if (self.subcompositor) |subcompositor| subcompositor.destroy();
        for (&self.outputs) |*output| output.deinit();
        if (self.compositor) |compositor| compositor.destroy();
        if (self.shm) |shm| shm.destroy();
        if (self.wm_base) |wm_base| wm_base.destroy();
        self.registry.destroy();
        self.dmabuf_formats.deinit(self.gpa);
        self.windows.deinit(self.gpa);
        if (self.ordered_events) |*events| events.deinit(self.gpa);
    }

    fn requireGlobals(self: *const Session) void {
        if (self.compositor == null)
            std.debug.panic("required Wayland protocol wl_compositor is unavailable; Android UI presentation cannot create surfaces", .{});
        if (self.wm_base == null)
            std.debug.panic("required Wayland protocol xdg_wm_base is unavailable; Android applications require an xdg_toplevel", .{});
        if (self.subcompositor == null)
            std.debug.panic("required Wayland protocol wl_subcompositor is unavailable; Android SurfaceView layers require synchronized subsurfaces", .{});
        if (self.viewporter == null)
            std.debug.panic("required Wayland protocol wp_viewporter is unavailable; Android buffers require logical-size and crop mapping", .{});
        if (self.shm == null)
            std.debug.panic("required Wayland protocol wl_shm is unavailable; the opaque application-window background cannot be created", .{});
        if (self.dmabuf == null)
            std.debug.panic("required Wayland protocol zwp_linux_dmabuf_v1 is unavailable; decoded Android video frames cannot be presented", .{});
        if (self.seat == null)
            std.debug.panic("required Wayland protocol wl_seat is unavailable; Android input cannot receive keyboard, pointer, or touch devices", .{});
    }

    fn registryListener(registry: *wl.Registry, event: wl.Registry.Event, self: *Session) void {
        switch (event) {
            .global => |g| {
                if (std.mem.orderZ(u8, g.interface, wl.Compositor.interface.name) == .eq) {
                    if (g.version < 4) std.debug.panic("required wl_compositor version 4 is unavailable", .{});
                    self.compositor = registry.bind(g.name, wl.Compositor, @min(g.version, self.protocol_options.compositor_max_version)) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wl.Subcompositor.interface.name) == .eq) {
                    self.subcompositor = registry.bind(g.name, wl.Subcompositor, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wl.Shm.interface.name) == .eq) {
                    self.shm = registry.bind(g.name, wl.Shm, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wp.Viewporter.interface.name) == .eq) {
                    self.viewporter = registry.bind(g.name, wp.Viewporter, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wp.FractionalScaleManagerV1.interface.name) == .eq and self.protocol_options.fractional_scale) {
                    self.fractional_scale_manager = registry.bind(g.name, wp.FractionalScaleManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwp.LinuxDmabufV1.interface.name) == .eq) {
                    const version = @min(g.version, 4);
                    self.dmabuf = registry.bind(g.name, zwp.LinuxDmabufV1, version) catch return;
                    if (version >= 4) {
                        self.dmabuf_feedback = self.dmabuf.?.getDefaultFeedback() catch return;
                        self.dmabuf_feedback.?.setListener(*Session, dmabufFeedbackListener, self);
                    } else {
                        self.dmabuf.?.setListener(*Session, dmabufListener, self);
                    }
                } else if (std.mem.orderZ(u8, g.interface, zwp.TabletManagerV2.interface.name) == .eq and self.protocol_options.tablet) {
                    self.tablet_manager = registry.bind(g.name, zwp.TabletManagerV2, 1) catch return;
                    self.tablet_manager_global = g.name;
                } else if (std.mem.orderZ(u8, g.interface, wp.CursorShapeManagerV1.interface.name) == .eq and self.protocol_options.cursor_shape) {
                    self.cursor_shape_manager = registry.bind(g.name, wp.CursorShapeManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwlr.VirtualPointerManagerV1.interface.name) == .eq) {
                    self.virtual_pointer_manager = registry.bind(g.name, zwlr.VirtualPointerManagerV1, @min(g.version, self.protocol_options.virtual_pointer_max_version)) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwp.VirtualKeyboardManagerV1.interface.name) == .eq) {
                    self.virtual_keyboard_manager = registry.bind(g.name, zwp.VirtualKeyboardManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwp.PointerConstraintsV1.interface.name) == .eq and self.pointer_constraints == null) {
                    self.pointer_constraints = registry.bind(g.name, zwp.PointerConstraintsV1, 1) catch return;
                    self.pointer_constraints_global_name = g.name;
                } else if (std.mem.orderZ(u8, g.interface, zwp.RelativePointerManagerV1.interface.name) == .eq and self.relative_pointer_manager == null) {
                    self.relative_pointer_manager = registry.bind(g.name, zwp.RelativePointerManagerV1, 1) catch return;
                    self.relative_pointer_global_name = g.name;
                } else if (std.mem.orderZ(u8, g.interface, xdg.WmBase.interface.name) == .eq) {
                    self.wm_base = registry.bind(g.name, xdg.WmBase, @min(g.version, self.protocol_options.xdg_shell_max_version)) catch return;
                    self.wm_base.?.setListener(*Session, wmBaseListener, self);
                } else if (std.mem.orderZ(u8, g.interface, wl.Seat.interface.name) == .eq and self.seat == null) {
                    if (g.version < 8)
                        std.debug.panic("required wl_seat version 8 is unavailable (compositor advertises version {d}); framed high-resolution scrolling and touch shape require version 8", .{g.version});
                    self.seat = registry.bind(g.name, wl.Seat, 8) catch return;
                    self.seat_global_name = g.name;
                    self.seat.?.setListener(*Session, seatListener, self);
                } else if (std.mem.orderZ(u8, g.interface, wl.Output.interface.name) == .eq) {
                    const output = for (&self.outputs) |*candidate| {
                        if (candidate.proxy == null) break candidate;
                    } else {
                        self.output_failure = error.OutputLimitExceeded;
                        return;
                    };
                    const proxy = registry.bind(g.name, wl.Output, @min(g.version, 4)) catch {
                        self.output_failure = error.OutputBindFailed;
                        return;
                    };
                    output.* = .{ .session = self, .proxy = proxy, .global_name = g.name };
                    proxy.setListener(*Output, Output.listener, output);
                }
            },
            .global_remove => |removed| {
                for (&self.outputs, 0..) |*output, index| {
                    if (output.proxy == null or output.global_name != removed.name) continue;
                    output.deinit();
                    for (self.windows.items) |window| {
                        window.output_membership &= ~(@as(u64, 1) << @intCast(index));
                        window.updatePreferredScale();
                    }
                    break;
                }
                if (self.tablet_manager_global == removed.name or self.seat_global_name == removed.name) {
                    if (self.tablets) |tablets| tablets.deinit();
                    self.tablets = null;
                }
                if (self.tablet_manager_global == removed.name) {
                    if (self.tablet_manager) |manager| manager.destroy();
                    self.tablet_manager = null;
                    self.tablet_manager_global = null;
                }
                if (self.seat_global_name == removed.name) {
                    self.releasePointer();
                    self.releaseKeyboard();
                    self.releaseTouch();
                    if (self.virtual_pointer) |pointer| pointer.destroy();
                    if (self.virtual_keyboard) |keyboard| keyboard.destroy();
                    self.virtual_pointer = null;
                    self.virtual_keyboard = null;
                    if (self.seat) |seat| seat.release();
                    self.seat = null;
                    self.seat_global_name = null;
                }
                if (self.pointer_constraints_global_name == removed.name) {
                    self.releasePointerCapture();
                    if (self.pointer_constraints) |constraints| constraints.destroy();
                    self.pointer_constraints = null;
                    self.pointer_constraints_global_name = null;
                }
                if (self.relative_pointer_global_name == removed.name) {
                    self.releasePointerCapture();
                    if (self.relative_pointer_manager) |manager| manager.destroy();
                    self.relative_pointer_manager = null;
                    self.relative_pointer_global_name = null;
                }
            },
        }
        if (self.tablets == null) if (self.tablet_manager) |manager| if (self.seat) |seat| {
            self.tablets = TabletSeat.init(self.gpa, manager, seat, &self.tablet_generation, .{ .context = self, .target = tabletTarget, .emit = tabletEvent }) catch |err| failed: {
                self.tablet_failure = err;
                break :failed null;
            };
        };
        self.publishSeatCapabilities();
    }
    fn tabletTarget(context: *anyopaque, surface: *wl.Surface) ?*anyopaque {
        const self: *Session = @ptrCast(@alignCast(context));
        return self.surfaceWindow(surface);
    }
    fn tabletEvent(context: *anyopaque, target: ?*anyopaque, event: TabletSeat.Event) void {
        const self: *Session = @ptrCast(@alignCast(context));
        const window: ?*Window = if (target) |value| @ptrCast(@alignCast(value)) else null;
        if (self.ordered_events) |*events| events.push(self.gpa, window, .{ .tablet = event });
    }

    fn dmabufListener(_: *zwp.LinuxDmabufV1, event: zwp.LinuxDmabufV1.Event, self: *Session) void {
        const pair: DmabufFormat = switch (event) {
            .format => |value| .{ .format = value.format, .modifier = drm_format_mod_invalid },
            .modifier => |value| .{
                .format = value.format,
                .modifier = (@as(u64, value.modifier_hi) << 32) | value.modifier_lo,
            },
        };
        self.dmabuf_formats.append(self.gpa, pair) catch {};
    }

    fn dmabufFeedbackListener(feedback: *zwp.LinuxDmabufFeedbackV1, event: zwp.LinuxDmabufFeedbackV1.Event, self: *Session) void {
        switch (event) {
            .format_table => |table| {
                if (self.dmabuf_table) |old| self.gpa.free(old);
                self.dmabuf_table = null;
                self.dmabuf_formats.clearRetainingCapacity();
                defer (std.Io.File{
                    .handle = table.fd,
                    .flags = .{ .nonblocking = false },
                }).close(self.io);
                if (table.size == 0 or table.size % @sizeOf(DmabufTableEntry) != 0) return;
                // Tranche indices are u16; only the first 65536 entries can
                // be referenced. Copy the immutable table through std.Io so no
                // mapped descriptor or compositor-owned storage outlives this event.
                const size = @min(table.size, 65536 * @sizeOf(DmabufTableEntry));
                const bytes = self.gpa.alignedAlloc(u8, .fromByteUnits(@alignOf(DmabufTableEntry)), size) catch return;
                const file: std.Io.File = .{ .handle = table.fd, .flags = .{ .nonblocking = false } };
                const count = file.readPositionalAll(self.io, bytes, 0) catch {
                    self.gpa.free(bytes);
                    return;
                };
                if (count != size) {
                    self.gpa.free(bytes);
                    return;
                }
                self.dmabuf_table = bytes;
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
                if (self.dmabuf_table) |table| self.gpa.free(table);
                self.dmabuf_table = null;
            },
            .main_device, .tranche_done, .tranche_target_device, .tranche_flags => {},
        }
    }

    fn wmBaseListener(wm_base: *xdg.WmBase, event: xdg.WmBase.Event, self: *Session) void {
        _ = self;
        switch (event) {
            .ping => |p| wm_base.pong(p.serial),
        }
    }

    fn publishSeatCapabilities(self: *Session) void {
        const mask: i32 = @as(i32, @intFromBool(self.pointer != null)) |
            (@as(i32, @intFromBool(self.keyboard != null)) << 1) |
            (@as(i32, @intFromBool(self.touch_device != null)) << 2) |
            (@as(i32, @intFromBool(self.pointer != null and self.relative_pointer_manager != null and self.pointer_constraints != null)) << 3);
        if (mask == self.seat_capabilities) return;
        self.seat_generation = std.math.add(i32, self.seat_generation, 1) catch
            std.debug.panic("Wayland seat generation exhausted", .{});
        self.seat_capabilities = mask;
        // Removal cancellation is queued first by releasePointer/Keyboard/Touch.
        // All task callbacks share one ordered Binder endpoint.
        self.queueCapabilities();
    }

    fn queueCapabilities(self: *Session) void {
        // Seat state survives removal of the last task. Keeping this in the
        // same queue preserves cancellation -> capability loss -> new input.
        if (self.ordered_events) |*events| events.push(self.gpa, null, .{ .seat_capabilities = .{ .generation = self.seat_generation, .mask = self.seat_capabilities } });
    }

    pub fn firstOutput(self: *const Session) ?*wl.Output {
        for (self.outputs) |output| if (output.proxy) |proxy| return proxy;
        return null;
    }

    fn outputIndex(self: *const Session, proxy: ?*wl.Output) ?u6 {
        if (proxy == null) return null;
        for (self.outputs, 0..) |output, index| if (output.proxy == proxy) return @intCast(index);
        return null;
    }

    fn ensureVirtualPointer(self: *Session) !*zwlr.VirtualPointerV1 {
        if (self.virtual_pointer) |pointer| return pointer;
        const seat = self.seat orelse return error.VirtualPointerUnavailable;
        const manager = self.virtual_pointer_manager orelse return error.VirtualPointerUnavailable;
        // create_virtual_pointer_with_output was introduced in version 2.
        // Version 1 lets the compositor choose the output for the seat.
        const output = self.firstOutput();
        self.virtual_pointer = if (manager.getVersion() >= 2 and output != null)
            try manager.createVirtualPointerWithOutput(seat, output.?)
        else
            try manager.createVirtualPointer(seat);
        return self.virtual_pointer.?;
    }

    /// Existing headless harness: publish/remove a virtual pointer without an
    /// application surface, so the normal seat capability lifecycle is tested.
    pub fn testPointerPresent(self: *Session, present: bool) !void {
        if (present) {
            _ = try self.ensureVirtualPointer();
        } else {
            if (self.virtual_pointer) |pointer| pointer.destroy();
            self.virtual_pointer = null;
        }
        if (self.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
    }

    fn seatListener(seat: *wl.Seat, event: wl.Seat.Event, self: *Session) void {
        switch (event) {
            .capabilities => |c| {
                if (c.capabilities.pointer and self.pointer == null) {
                    self.pointer = seat.getPointer() catch null;
                    if (self.pointer) |p| p.setListener(*Session, pointerListener, self);
                } else if (!c.capabilities.pointer and self.pointer != null) {
                    self.releasePointer();
                }
                if (c.capabilities.keyboard and self.keyboard == null) {
                    self.keyboard = seat.getKeyboard() catch null;
                    if (self.keyboard) |k| k.setListener(*Session, keyboardListener, self);
                } else if (!c.capabilities.keyboard and self.keyboard != null) {
                    self.releaseKeyboard();
                }
                if (c.capabilities.touch and self.touch_device == null) {
                    self.touch_device = seat.getTouch() catch null;
                    if (self.touch_device) |touch| touch.setListener(*Session, touchListener, self);
                } else if (!c.capabilities.touch and self.touch_device != null) {
                    self.releaseTouch();
                }
            },
            .name => {},
        }
        self.publishSeatCapabilities();
    }
};

pub const Window = struct {
    session: *Session,
    owns_session: bool = false,
    surface_created: bool = false,
    gpa: std.mem.Allocator,
    io: std.Io,
    relative_pointer: ?*zwp.RelativePointerV1 = null,
    locked_pointer: ?*zwp.LockedPointerV1 = null,
    pointer_capture_requested: bool = false,
    pointer_capture_actual: bool = false,
    pointer_capture_sequence: i32 = 0,
    pointer_enter_serial: u32 = 0,
    cursor_hidden: bool = false,
    cursor_shape: u32 = 0, // last applied wp_cursor_shape_device_v1.shape (0 = none)
    cursor_image: ?CursorImage = null,

    surface: *wl.Surface = undefined,
    viewport: *wp.Viewport = undefined,
    fractional_scale: ?*wp.FractionalScaleV1 = null,
    xdg_surface: *xdg.Surface = undefined,
    toplevel: *xdg.Toplevel = undefined,
    base_buffer: *wl.Buffer = undefined,
    opaque_region: *wl.Region = undefined,
    opaque_width: i32 = 0,
    opaque_height: i32 = 0,
    ui_surface: *wl.Surface = undefined,
    ui_subsurface: *wl.Subsurface = undefined,
    ui_viewport: *wp.Viewport = undefined,
    ui_dmabuf_buffers: std.ArrayList(*ImportedDmabuf) = .empty,

    width: i32,
    height: i32,
    /// Preferred surface scale in 120ths, as defined by fractional-scale-v1.
    scale_120: u32 = 120,
    output_membership: u64 = 0,
    fractional_preference: ?u32 = null,
    integer_preference: ?u32 = null,
    /// A rendering hint; buffers remain normal-oriented and the compositor
    /// applies the physical output transform.
    preferred_transform: wl.Output.Transform = .normal,
    fullscreen: FullscreenState = .{},
    configured: bool = false,
    configuration_generation: i64 = 0,
    configure_serial: u32 = 0,
    pending_toplevel: bool = false,
    pending_width: i32 = 0,
    pending_height: i32 = 0,
    pending_fullscreen: bool = false,
    pending_maximized: bool = false,
    pending_suspended: bool = false,
    suspended: bool = false,
    maximized: bool = false,
    base_attached: bool = false,
    ui_initialized: bool = false,
    presentation_dirty: bool = false,

    pointer_x: f64 = 0,
    pointer_y: f64 = 0,
    pointer_axis_frame: PointerAxisFrame = .{},

    touch_points: [10]TouchPoint = std.mem.zeroes([10]TouchPoint),

    key_repeat: ?KeyRepeat = null,

    // simple event queue filled by listeners, drained by the caller
    events: std.ArrayList(Event) = .empty,
    delivered_events: std.ArrayList(Event) = .empty,
    frame_cb: ?*wl.Callback = null,

    pub fn init(gpa: std.mem.Allocator, io: std.Io, width: i32, height: i32, title: [:0]const u8) !*Window {
        return initOpts(gpa, io, width, height, title, false);
    }

    pub fn initOpts(gpa: std.mem.Allocator, io: std.Io, width: i32, height: i32, title: [:0]const u8, fullscreen: bool) !*Window {
        const session = try Session.init(gpa, io);
        errdefer session.deinit();
        const window = try session.createWindow(width, height, title, fullscreen);
        window.owns_session = true;
        return window;
    }

    fn initInSession(session: *Session, width: i32, height: i32, title: [:0]const u8, app_id: [:0]const u8, fullscreen: bool) !*Window {
        const gpa = session.gpa;
        const self = try gpa.create(Window);
        errdefer gpa.destroy(self);
        self.* = .{ .gpa = gpa, .io = session.io, .session = session, .width = width, .height = height, .fullscreen = FullscreenState.init(fullscreen) };
        try session.windows.append(gpa, self);
        errdefer session.removeWindow(self);
        errdefer self.events.deinit(gpa);
        session.queueCapabilities();
        session.queueLayout();
        session.queueModifiers();
        session.queueRepeat();
        errdefer if (self.frame_cb) |callback| callback.destroy();
        self.surface = try self.session.compositor.?.createSurface();
        errdefer self.surface.destroy();
        self.surface_created = true;
        self.surface.setListener(*Window, surfaceListener, self);
        self.opaque_region = try self.session.compositor.?.createRegion();
        errdefer self.opaque_region.destroy();
        // fractional-scale-v1 deliberately leaves wl_surface.buffer_scale at
        // 1. A viewport maps the higher-resolution buffer back to the logical
        // surface size used by xdg-shell and input events.
        self.viewport = try self.session.viewporter.?.getViewport(self.surface);
        errdefer self.viewport.destroy();
        if (self.session.fractional_scale_manager != null) {
            self.fractional_scale = try self.session.fractional_scale_manager.?.getFractionalScale(self.surface);
            self.fractional_scale.?.setListener(*Window, fractionalScaleListener, self);
        }
        errdefer if (self.fractional_scale) |scale| scale.destroy();
        self.updateViewport();
        self.xdg_surface = try self.session.wm_base.?.getXdgSurface(self.surface);
        errdefer self.xdg_surface.destroy();
        self.toplevel = try self.xdg_surface.getToplevel();
        errdefer self.toplevel.destroy();
        self.xdg_surface.setListener(*Window, xdgSurfaceListener, self);
        self.toplevel.setListener(*Window, toplevelListener, self);
        self.toplevel.setTitle(title);
        self.toplevel.setAppId(app_id);
        if (fullscreen) self.toplevel.setFullscreen(null);

        // xdg-shell requires the initial configure to be acknowledged before
        // the first buffer is attached.
        self.surface.commit();
        while (!self.configured) {
            if (self.session.display.dispatch() != .SUCCESS) return error.DispatchFailed;
        }
        self.updateWindowGeometry();

        // Host adaptation of AOSP SurfaceView.performSurfaceTransaction():
        // SurfaceFlinger/SurfaceControl are unavailable here, so represent
        // their application-window and SurfaceView sibling layers with Wayland
        // subsurfaces. Keep the xdg_toplevel surface as an opaque base and
        // render Android's alpha-capable window above the video layers. Some
        // compositors flatten a fullscreen toplevel as opaque before
        // considering a child below it, which turns the valid SurfaceView hole
        // black.
        self.base_buffer = try self.createBaseBuffer();
        errdefer self.base_buffer.destroy();
        self.surface.attach(self.base_buffer, 0, 0);
        self.surface.damageBuffer(0, 0, 1, 1);
        self.base_attached = true;
        self.updateOpaqueRegion();
        self.surface.commit();

        // Some compositors refine the configured logical size only after a
        // client attaches its first buffer. A roundtrip observes that optional
        // second configure before the EGL child is sized.
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;

        self.ui_surface = try self.session.compositor.?.createSurface();
        self.ui_surface.setListener(*Window, childSurfaceListener, self);
        errdefer self.ui_surface.destroy();
        // The task's toplevel owns the seat, including keyboard focus and
        // pointer constraints. The rendered child shares its origin/viewport;
        // leave hit testing to the parent instead of splitting seat identity.
        // labwc 0.20.1 activates constraints against the active view's surface.
        const empty_input_region = try self.session.compositor.?.createRegion();
        self.ui_surface.setInputRegion(empty_input_region);
        empty_input_region.destroy();
        self.ui_subsurface = try self.session.subcompositor.?.getSubsurface(self.ui_surface, self.surface);
        errdefer self.ui_subsurface.destroy();
        self.ui_subsurface.placeAbove(self.surface);
        self.ui_viewport = try self.session.viewporter.?.getViewport(self.ui_surface);
        errdefer self.ui_viewport.destroy();
        self.ui_initialized = true;
        self.updateViewport();
        self.surface.commit();
        self.presentation_dirty = false;
        return self;
    }

    pub fn supportsVideoDmabuf(self: *Window, format: u32, modifier: u64) bool {
        return selectDmabufImportModifier(self.session.dmabuf_formats.items, format, modifier) != null;
    }

    fn videoDmabufImportModifier(self: *Window, format: u32, modifier: u64) ?u64 {
        return selectDmabufImportModifier(self.session.dmabuf_formats.items, format, modifier);
    }

    pub fn hasVideoDmabuf(self: *Window) bool {
        if (self.session.dmabuf == null) return false;
        for (self.session.dmabuf_formats.items) |supported| {
            if (supported.format == drm_format_nv12 or supported.format == drm_format_p010)
                return true;
        }
        return false;
    }

    fn publishConfiguration(self: *Window) void {
        if (self.configuration_generation == std.math.maxInt(i64)) {
            self.session.output_failure = error.ConfigurationGenerationExhausted;
            return;
        }
        self.configuration_generation += 1;
        self.push(.{ .configure = .{
            .width = self.width,
            .height = self.height,
            .buffer_width = self.bufferWidth(),
            .buffer_height = self.bufferHeight(),
            .generation = self.configuration_generation,
            .serial = self.configure_serial,
        } });
    }

    fn xdgSurfaceListener(surface: *xdg.Surface, event: xdg.Surface.Event, self: *Window) void {
        switch (event) {
            .configure => |configure| {
                // xdg_toplevel.configure is pending state until this serial.
                // Acknowledge and publish one coherent geometry snapshot.
                surface.ackConfigure(configure.serial);
                self.configure_serial = configure.serial;
                const old_width = self.width;
                const old_height = self.height;
                if (self.pending_toplevel) {
                    if (self.pending_width > 0) self.width = self.pending_width;
                    if (self.pending_height > 0) self.height = self.pending_height;
                    self.maximized = self.pending_maximized;
                    if (self.suspended != self.pending_suspended) {
                        self.suspended = self.pending_suspended;
                        if (self.suspended) self.key_repeat = null;
                        self.push(.{ .suspended_changed = self.suspended });
                    }
                    if (self.fullscreen.configure(self.pending_fullscreen))
                        self.push(.{ .fullscreen_changed = self.pending_fullscreen });
                    self.pending_toplevel = false;
                }
                const changed = !self.configured or old_width != self.width or old_height != self.height;
                self.configured = true;
                if (changed) {
                    self.updateViewport();
                    self.updateWindowGeometry();
                    self.updateOpaqueRegion();
                    self.publishConfiguration();
                }
                // State-only configures need acknowledgment, but do not retire
                // valid same-geometry content or restart Android draw lifecycles.
                if (self.base_attached) self.presentation_dirty = true;
            },
        }
    }

    fn updatePreferredScale(self: *Window) void {
        var output_scale: u32 = 1;
        for (self.session.outputs, 0..) |output, index| {
            if (output.proxy != null and self.output_membership & (@as(u64, 1) << @intCast(index)) != 0)
                output_scale = @max(output_scale, @as(u32, @intCast(output.scale)));
        }
        // wl_surface v6 defines the preferred integer scale as 1 until its
        // first hint. Output-derived scaling is the older protocol fallback.
        const integer_scale = if (self.surface_created and self.surface.getVersion() >= 6)
            self.integer_preference orelse 1
        else
            output_scale;
        const scale = self.fractional_preference orelse (integer_scale * 120);
        if (scale == self.scale_120) return;
        self.scale_120 = scale;
        self.updateViewport();
        self.presentation_dirty = true;
        if (self.configured) self.publishConfiguration();
    }

    // Children use the toplevel's logical geometry and chosen buffer mapping.
    // Scale/transform hints on their own proxies do not change that shared map.
    fn childSurfaceListener(_: *wl.Surface, _: wl.Surface.Event, _: *Window) void {}

    fn surfaceListener(_: *wl.Surface, event: wl.Surface.Event, self: *Window) void {
        switch (event) {
            .enter => |entered| if (self.session.outputIndex(entered.output)) |index| {
                self.output_membership |= @as(u64, 1) << index;
                self.updatePreferredScale();
            },
            .leave => |left| if (self.session.outputIndex(left.output)) |index| {
                self.output_membership &= ~(@as(u64, 1) << index);
                self.updatePreferredScale();
            },
            .preferred_buffer_scale => |preferred| {
                if (preferred.factor < 1 or preferred.factor > 128) {
                    self.session.output_failure = error.InvalidOutputScale;
                    return;
                }
                self.integer_preference = @intCast(preferred.factor);
                self.updatePreferredScale();
            },
            .preferred_buffer_transform => |preferred| self.preferred_transform = preferred.transform,
        }
    }

    fn fractionalScaleListener(_: *wp.FractionalScaleV1, event: wp.FractionalScaleV1.Event, self: *Window) void {
        switch (event) {
            .preferred_scale => |preferred| {
                if (preferred.scale == 0 or preferred.scale > 128 * 120) {
                    self.session.output_failure = error.InvalidOutputScale;
                    return;
                }
                self.fractional_preference = preferred.scale;
                self.updatePreferredScale();
            },
        }
    }

    fn toplevelListener(_: *xdg.Toplevel, event: xdg.Toplevel.Event, self: *Window) void {
        switch (event) {
            .configure => |configure| {
                self.pending_toplevel = true;
                self.pending_width = configure.width;
                self.pending_height = configure.height;
                self.pending_fullscreen = false;
                self.pending_maximized = false;
                self.pending_suspended = false;
                for (configure.states.*.slice(u32)) |state| {
                    if (state == @backingInt(xdg.Toplevel.State.fullscreen)) self.pending_fullscreen = true;
                    if (state == @backingInt(xdg.Toplevel.State.maximized)) self.pending_maximized = true;
                    if (state == @backingInt(xdg.Toplevel.State.suspended)) self.pending_suspended = true;
                }
            },
            .close => self.push(.close),
            .configure_bounds, .wm_capabilities => {},
        }
    }

    fn pointerListener(pointer: *wl.Pointer, event: wl.Pointer.Event, self: *Window) void {
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
            .axis => |a| switch (a.axis) {
                .vertical_scroll => self.pointer_axis_frame.vertical += a.value.toDouble(),
                .horizontal_scroll => self.pointer_axis_frame.horizontal += a.value.toDouble(),
                else => {},
            },
            .axis_discrete => |a| switch (a.axis) {
                .vertical_scroll => self.pointer_axis_frame.vertical_discrete = a.discrete,
                .horizontal_scroll => self.pointer_axis_frame.horizontal_discrete = a.discrete,
                else => {},
            },
            .axis_value120 => |a| switch (a.axis) {
                .vertical_scroll => self.pointer_axis_frame.vertical_value120 = a.value120,
                .horizontal_scroll => self.pointer_axis_frame.horizontal_value120 = a.value120,
                else => {},
            },
            .frame => self.flushPointerAxisFrame(),
            .enter => |e| {
                self.pointer_x = e.surface_x.toDouble();
                self.pointer_y = e.surface_y.toDouble();
                self.pointer_enter_serial = e.serial;
                // re-apply our cursor on every enter (the compositor resets it)
                if (self.cursor_hidden) {
                    pointer.setCursor(self.pointer_enter_serial, null, 0, 0);
                } else if (self.cursor_image) |*cursor| {
                    cursor.apply(self.io, pointer, self.pointer_enter_serial);
                } else {
                    const shape = if (self.cursor_shape != 0) self.cursor_shape else 1; // default
                    self.cursor_shape = 0;
                    self.setCursorShape(shape);
                }
                self.push(.{ .pointer_enter = .{ .x = self.pointer_x, .y = self.pointer_y } });
            },
            .leave => {
                self.flushPointerAxisFrame();
                self.push(.{ .pointer_leave = .{ .x = self.pointer_x, .y = self.pointer_y } });
                self.push(.pointer_cancel);
            },
            else => {},
        }
    }

    fn flushPointerAxisFrame(self: *Window) void {
        const horizontal = PointerAxisFrame.androidValue(
            self.pointer_axis_frame.horizontal,
            self.pointer_axis_frame.horizontal_discrete,
            self.pointer_axis_frame.horizontal_value120,
        );
        const vertical = PointerAxisFrame.androidValue(
            self.pointer_axis_frame.vertical,
            self.pointer_axis_frame.vertical_discrete,
            self.pointer_axis_frame.vertical_value120,
        );
        self.pointer_axis_frame.reset();
        if (horizontal != 0 or vertical != 0) {
            self.push(.{ .pointer_axis = .{ .x = self.pointer_x, .y = self.pointer_y, .horizontal = horizontal, .vertical = vertical } });
        }
    }

    fn keyboardListener(_: *wl.Keyboard, event: wl.Keyboard.Event, self: *Window) void {
        switch (event) {
            .key => |k| {
                var unicode: u32 = 0;
                var keysym: u32 = 0;
                if (self.session.xkb_state) |st| {
                    unicode = xkb.xkb_state_key_get_utf32(st, k.key + 8);
                    keysym = xkb.xkb_state_key_get_one_sym(st, k.key + 8);
                }
                const repeatable = if (self.session.xkb_keymap) |keymap|
                    xkb.xkb_keymap_key_repeats(keymap, k.key + 8) != 0
                else
                    false;
                self.push(.{ .key = .{
                    .repeatable = repeatable,
                    .keycode = k.key,
                    .pressed = k.state == .pressed,
                    .unicode = unicode,
                    .keysym = keysym,
                } });
                if (k.state == .pressed) {
                    if (repeatable and self.session.generate_key_repeats and self.session.keyboard_repeat_rate > 0) {
                        const period_ns = @divTrunc(@as(u64, std.time.ns_per_s), @as(u64, @intCast(self.session.keyboard_repeat_rate)));
                        self.key_repeat = .{
                            .keycode = k.key,
                            .unicode = unicode,
                            .keysym = keysym,
                            .next_ns = monotonicNs(self.io) +
                                @as(u64, @intCast(@max(self.session.keyboard_repeat_delay_ms, 0))) *
                                    std.time.ns_per_ms,
                            .period_ns = period_ns,
                        };
                    }
                } else if (self.key_repeat != null and self.key_repeat.?.keycode == k.key) {
                    self.key_repeat = null;
                }
            },
            .leave => {
                self.key_repeat = null;
                self.push(.keyboard_reset);
            },
            else => {},
        }
    }

    fn touchListener(_: *wl.Touch, event: wl.Touch.Event, self: *Window) void {
        switch (event) {
            .down => |down| {
                if (down.surface == null) return;
                const point = self.allocateTouchPoint(down.id) orelse {
                    std.debug.print("azk: ignoring touch id {d}: Android supports at most 10 hosted contacts\n", .{down.id});
                    return;
                };
                point.x = down.x.toDouble();
                point.y = down.y.toDouble();
                self.pushTouch(.down, point.*);
            },
            .motion => |motion| if (self.findTouchPoint(motion.id)) |point| {
                point.x = motion.x.toDouble();
                point.y = motion.y.toDouble();
                self.pushTouch(.move, point.*);
            },
            .up => |up| if (self.findTouchPoint(up.id)) |point| {
                self.pushTouch(.up, point.*);
                point.* = .{};
            },
            .shape => |shape| if (self.findTouchPoint(shape.id)) |point| {
                point.major = shape.major.toDouble();
                point.minor = shape.minor.toDouble();
                self.pushTouch(.update, point.*);
            },
            .orientation => |orientation| if (self.findTouchPoint(orientation.id)) |point| {
                point.orientation = orientation.orientation.toDouble() * std.math.pi / 180.0;
                self.pushTouch(.update, point.*);
            },
            .cancel => self.cancelTouches(),
            .frame => {},
        }
    }

    fn allocateTouchPoint(self: *Window, id: i32) ?*TouchPoint {
        if (self.findTouchPoint(id)) |point| return point;
        for (&self.touch_points) |*point| {
            if (!point.active) {
                point.* = .{ .active = true, .id = id };
                return point;
            }
        }
        return null;
    }

    fn findTouchPoint(self: *Window, id: i32) ?*TouchPoint {
        for (&self.touch_points) |*point| if (point.active and point.id == id) return point;
        return null;
    }

    fn pushTouch(self: *Window, action: TouchAction, point: TouchPoint) void {
        self.push(.{ .touch = .{
            .action = action,
            .id = point.id,
            .x = point.x,
            .y = point.y,
            .major = point.major,
            .minor = point.minor,
            .orientation = point.orientation,
        } });
    }

    fn cancelTouches(self: *Window) void {
        var had_touch = false;
        for (&self.touch_points) |*point| {
            had_touch = had_touch or point.active;
            point.* = .{};
        }
        if (had_touch) self.push(.touch_cancel);
    }

    fn lockedPointerListener(_: *zwp.LockedPointerV1, event: zwp.LockedPointerV1.Event, self: *Window) void {
        const actual = switch (event) {
            .locked => true,
            .unlocked => false,
        };
        if (actual == self.pointer_capture_actual) return;
        self.pointer_capture_actual = actual;
        if (self.session.tablets) |tablets| tablets.pointerCapture(actual);
        self.push(.{ .pointer_capture_changed = .{ .enabled = actual, .sequence = self.pointer_capture_sequence } });
    }

    fn relativePointerListener(_: *zwp.RelativePointerV1, event: zwp.RelativePointerV1.Event, self: *Window) void {
        switch (event) {
            .relative_motion => |motion| if (self.pointer_capture_actual) {
                self.push(.{ .pointer_relative = .{
                    .dx = motion.dx_unaccel.toDouble(),
                    .dy = motion.dy_unaccel.toDouble(),
                } });
            },
        }
    }

    /// InputReader's capture acknowledgement is supplied by the compositor lock.
    /// Keep the request sequence in each queued event so a later request cannot
    /// relabel an acknowledgement that is still waiting to be delivered.
    pub fn requestPointerCapture(self: *Window, enabled: bool, sequence: i32) !void {
        self.pointer_capture_sequence = sequence;
        const was_actual = self.pointer_capture_actual;
        try self.setPointerCapture(enabled);
        if (enabled == was_actual) {
            self.push(.{ .pointer_capture_changed = .{ .enabled = enabled, .sequence = sequence } });
        }
    }

    pub fn setPointerCapture(self: *Window, enabled: bool) !void {
        if (enabled == self.pointer_capture_requested) return;
        if (!enabled) {
            self.pointer_capture_requested = false;
            if (self.locked_pointer) |locked| locked.destroy();
            if (self.relative_pointer) |relative| relative.destroy();
            self.locked_pointer = null;
            self.relative_pointer = null;
            if (self.pointer_capture_actual) {
                self.pointer_capture_actual = false;
                if (self.session.tablets) |tablets| tablets.pointerCapture(false);
                self.push(.{ .pointer_capture_changed = .{ .enabled = false, .sequence = self.pointer_capture_sequence } });
            }
            return;
        }

        const pointer = self.session.pointer orelse return error.PointerCapabilityUnavailable;
        const relative_manager = self.session.relative_pointer_manager orelse return error.RelativePointerUnavailable;
        const constraints = self.session.pointer_constraints orelse return error.PointerConstraintsUnavailable;
        const relative = try relative_manager.getRelativePointer(pointer);
        errdefer relative.destroy();
        const locked = try constraints.lockPointer(self.surface, pointer, null, .persistent);
        relative.setListener(*Window, relativePointerListener, self);
        locked.setListener(*Window, lockedPointerListener, self);
        self.relative_pointer = relative;
        self.locked_pointer = locked;
        self.pointer_capture_requested = true;
        self.surface.commit();
    }

    pub fn hasPointerCapture(self: *const Window) bool {
        return self.pointer_capture_actual;
    }

    fn emitKeyRepeatIfDue(self: *Window) bool {
        var repeat = self.key_repeat orelse return false;
        const now = monotonicNs(self.io);
        if (now < repeat.next_ns) return false;
        if (self.session.xkb_state) |state| {
            repeat.unicode = xkb.xkb_state_key_get_utf32(state, repeat.keycode + 8);
            repeat.keysym = xkb.xkb_state_key_get_one_sym(state, repeat.keycode + 8);
        }
        self.push(.{ .key = .{
            .keycode = repeat.keycode,
            .pressed = true,
            .unicode = repeat.unicode,
            .keysym = repeat.keysym,
        } });
        repeat.next_ns +%= repeat.period_ns;
        if (repeat.next_ns <= now) repeat.next_ns = now + repeat.period_ns;
        self.key_repeat = repeat;
        return true;
    }

    fn keyRepeatTimeoutMs(self: *const Window) i32 {
        const repeat = self.key_repeat orelse return -1;
        const now = monotonicNs(self.io);
        if (now >= repeat.next_ns) return 0;
        const remaining = repeat.next_ns - now;
        return @intCast(@min(@as(u64, std.math.maxInt(i32)), @divTrunc(remaining + std.time.ns_per_ms - 1, std.time.ns_per_ms)));
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

    fn push(self: *Window, e: Event) void {
        if (self.session.ordered_events) |*events| {
            events.push(self.gpa, self, e);
            return;
        }
        self.events.append(self.gpa, e) catch {};
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
        if (self.width > 0 and self.height > 0) {
            self.viewport.setDestination(self.width, self.height);
            if (self.ui_initialized) {
                self.ui_viewport.setDestination(self.width, self.height);
                // Viewport state belongs to the child commit. Cache the new
                // destination for the next synchronized parent transaction,
                // including while SF has not produced a replacement buffer.
                self.ui_surface.commit();
            }
        }
    }

    fn updateWindowGeometry(self: *Window) void {
        if (self.width <= 0 or self.height <= 0) return;
        self.xdg_surface.setWindowGeometry(0, 0, self.width, self.height);
    }

    fn updateOpaqueRegion(self: *Window) void {
        if (self.opaque_width > 0 and self.opaque_height > 0) {
            self.opaque_region.subtract(0, 0, self.opaque_width, self.opaque_height);
        }
        if (self.width > 0 and self.height > 0) {
            self.opaque_region.add(0, 0, self.width, self.height);
            self.opaque_width = self.width;
            self.opaque_height = self.height;
        } else {
            self.opaque_width = 0;
            self.opaque_height = 0;
        }
        self.surface.setOpaqueRegion(self.opaque_region);
    }

    fn createBaseBuffer(self: *Window) !*wl.Buffer {
        const base_fd = try createMemoryFile("azk-window-base");
        const base_file = std.Io.File{
            .handle = base_fd,
            .flags = .{ .nonblocking = false },
        };
        defer base_file.close(self.io);
        const pixel = [_]u8{ 0, 0, 0, 0 };
        base_file.writeStreamingAll(self.io, &pixel) catch return error.BaseBufferWriteFailed;
        const pool = try self.session.shm.?.createPool(base_fd, @intCast(pixel.len));
        defer pool.destroy();
        return pool.createBuffer(0, 1, 1, 4, .xrgb8888);
    }

    pub fn renderSurface(self: *Window) *wl.Surface {
        return self.ui_surface;
    }

    const ImportedDmabuf = struct {
        win: *Window,
        pending: *std.ArrayList(*ImportedDmabuf),
        wl_buffer: *wl.Buffer,
        frame: DmabufFrame,

        fn listener(_: *wl.Buffer, event: wl.Buffer.Event, state: *ImportedDmabuf) void {
            switch (event) {
                .release => {
                    for (state.pending.items, 0..) |candidate, index| {
                        if (candidate == state) {
                            _ = state.pending.orderedRemove(index);
                            break;
                        }
                    }
                    state.wl_buffer.destroy();
                    state.frame.release();
                    state.win.gpa.destroy(state);
                },
            }
        }
    };

    /// Shared importer for decoded video and stock BufferQueue task output.
    /// The caller must complete its acquire fence off the Wayland event thread
    /// before transfer. Only wl_buffer.release permits normal queue reuse.
    fn importDmabuf(self: *Window, frame: DmabufFrame, pending: *std.ArrayList(*ImportedDmabuf)) !*wl.Buffer {
        errdefer frame.release();
        const factory = self.session.dmabuf orelse return error.DmabufUnavailable;
        if (frame.width <= 0 or frame.height <= 0 or frame.plane_count == 0 or frame.plane_count > frame.fds.len)
            return error.InvalidDmabuf;
        if (pending.items.len >= 64) return error.TooManyPendingBuffers;
        for (0..frame.plane_count) |index| {
            if (frame.fds[index] < 0 or frame.strides[index] == 0) return error.InvalidDmabuf;
            if (selectDmabufImportModifier(self.session.dmabuf_formats.items, frame.format, frame.modifiers[index]) == null)
                return error.UnsupportedDmabufFormat;
        }
        const params = try factory.createParams();
        defer params.destroy();
        for (0..frame.plane_count) |index| {
            const modifier = frame.modifiers[index];
            params.add(frame.fds[index], @intCast(index), frame.offsets[index], frame.strides[index], @truncate(modifier >> 32), @truncate(modifier));
        }
        const buffer = try params.createImmed(frame.width, frame.height, frame.format, .{});
        errdefer buffer.destroy();
        const state = try self.gpa.create(ImportedDmabuf);
        errdefer self.gpa.destroy(state);
        state.* = .{ .win = self, .pending = pending, .wl_buffer = buffer, .frame = frame };
        try pending.append(self.gpa, state);
        buffer.setListener(*ImportedDmabuf, ImportedDmabuf.listener, state);
        return buffer;
    }

    /// Present a completed task output on the existing UI child. Ownership
    /// transfers on every outcome. A queue owner must supply abandonment for
    /// disconnect/error teardown; a frame callback is never a release signal.
    pub fn pushUiDmabuf(self: *Window, frame: DmabufFrame) !void {
        if (frame.abandon_fn == null) {
            frame.release();
            return error.QueueAbandonmentRequired;
        }
        const buffer = try self.importDmabuf(frame, &self.ui_dmabuf_buffers);
        self.ui_surface.attach(buffer, 0, 0);
        self.ui_viewport.setSource(wl.Fixed.fromDouble(0), wl.Fixed.fromDouble(0), wl.Fixed.fromDouble(@floatFromInt(frame.width)), wl.Fixed.fromDouble(@floatFromInt(frame.height)));
        self.ui_viewport.setDestination(self.width, self.height);
        self.ui_surface.damageBuffer(0, 0, frame.width, frame.height);
        self.ui_surface.commit();
        self.ui_initialized = true;
        self.presentation_dirty = true;
    }

    /// Retire a producer after SF disconnected it. Keep the committed image on
    /// the server until its replacement arrives: destroying a wl_buffer proxy
    /// does not detach the surface. Abandonment prevents Android from reusing
    /// storage while the compositor retains its own DMA-BUF references.
    pub fn retireUiProducer(self: *Window) void {
        for (self.ui_dmabuf_buffers.items) |state| {
            state.wl_buffer.destroy();
            state.frame.abandon();
            self.gpa.destroy(state);
        }
        self.ui_dmabuf_buffers.clearRetainingCapacity();
    }

    /// Register a callback to pace the next application-window transaction.
    /// The parent commit always carries this callback, including when HWUI
    /// drops a child frame before producing a buffer.
    pub fn requestFrame(self: *Window) !void {
        if (self.frame_cb == null) {
            self.frame_cb = try self.surface.frame();
            self.frame_cb.?.setListener(*Window, frameListener, self);
        }
    }

    /// Arm the compositor vsync callback without publishing an Android child
    /// buffer. The empty parent transaction is the hosted DisplayEventReceiver
    /// request; traversal still runs only after frame_done.
    pub fn requestVsync(self: *Window) !void {
        try self.requestFrame();
        self.presentation_dirty = true;
    }

    /// Publish every synchronized Android child update as one Wayland
    /// application-window transaction. Child surface commits remain cached
    /// until this parent commit.
    pub fn present(self: *Window) !void {
        if (!self.presentation_dirty) return;
        self.presentation_dirty = false;
        self.surface.commit();
        switch (self.session.display.flush()) {
            .SUCCESS, .AGAIN => {},
            else => return error.FlushFailed,
        }
    }

    /// Called after EGL has committed a new buffer to the synchronized UI
    /// child. No Wayland object is allocated on this frame path.
    pub fn uiFrameReady(self: *Window) void {
        self.presentation_dirty = true;
    }

    /// Set the pointer cursor via cursor-shape-v1 (shape = wp_cursor_shape_device_v1.shape
    /// value, 1 = default). No-op if the compositor lacks the protocol.
    pub fn setCursorShape(self: *Window, shape: u32) void {
        if (shape == self.cursor_shape) return;
        const mgr = self.session.cursor_shape_manager orelse return;
        const ptr = self.session.pointer orelse return;
        if (self.session.cursor_device == null) {
            self.session.cursor_device = mgr.getPointer(ptr) catch return;
        }
        const dev = self.session.cursor_device orelse return;
        dev.setShape(self.pointer_enter_serial, @fromBackingInt(@intCast(shape)));
        self.cursor_shape = shape;
        _ = self.session.display.flush();
    }

    /// Apply a stock pointer request only while this task owns the compositor
    /// pointer. Android InputDispatcher validates the individual input window.
    pub fn setPointerIcon(self: *Window, icon: i32) bool {
        const pointer = self.session.pointer orelse return false;
        if (self.session.pointer_focus != self or self.pointer_enter_serial == 0) return false;
        if (icon == 0) {
            pointer.setCursor(self.pointer_enter_serial, null, 0, 0);
            self.clearCursorImage();
            self.cursor_hidden = true;
            _ = self.session.display.flush();
            return true;
        }
        const shape = @import("cursor_shape.zig").fromAndroid(icon) orelse return false;
        if (self.session.cursor_shape_manager == null) return false;
        self.clearCursorImage();
        if (self.cursor_hidden) {
            self.cursor_hidden = false;
            self.cursor_shape = 0;
        }
        self.setCursorShape(shape);
        return self.cursor_shape == shape;
    }

    fn clearCursorImage(self: *Window) void {
        if (self.cursor_image) |*cursor| cursor.deinit();
        self.cursor_image = null;
    }

    pub fn setCursorImage(self: *Window, width: i32, height: i32, hotspot_x: i32, hotspot_y: i32, frame_ms: i32, pixels: []const i32) !bool {
        const pointer = self.session.pointer orelse return false;
        if (self.session.pointer_focus != self or self.pointer_enter_serial == 0) return false;
        var cursor = try CursorImage.init(self.io, self.session.compositor.?, self.session.shm.?, width, height, hotspot_x, hotspot_y, frame_ms, pixels);
        cursor.apply(self.io, pointer, self.pointer_enter_serial);
        self.clearCursorImage();
        self.cursor_image = cursor;
        self.cursor_hidden = false;
        self.cursor_shape = 0;
        return true;
    }

    pub fn setTabletPointerIcon(self: *Window, device: i32, generation: i32, icon: i32) bool {
        const tablets = self.session.tablets orelse return false;
        const tool = tablets.findTool(self, device, generation) orelse return false;
        const applied = tool.setPointerIcon(self.session.cursor_shape_manager, icon);
        if (applied) _ = self.session.display.flush();
        return applied;
    }
    pub fn setTabletCursorImage(self: *Window, device: i32, generation: i32, width: i32, height: i32, hotspot_x: i32, hotspot_y: i32, frame_ms: i32, pixels: []const i32) !bool {
        const tablets = self.session.tablets orelse return false;
        const tool = tablets.findTool(self, device, generation) orelse return false;
        return tool.setCursorImage(self.io, self.session.compositor.?, self.session.shm.?, width, height, hotspot_x, hotspot_y, frame_ms, pixels);
    }
    fn updateTabletCursorAnimations(self: *Window) i32 {
        if (self.suspended) return -1;
        const tablets = self.session.tablets orelse return -1;
        var timeout: i32 = -1;
        for (tablets.tools) |slot| if (slot) |tool| {
            if (tool.target != @as(*anyopaque, self)) continue;
            const next = tool.updateCursorAnimation(self.io);
            if (next >= 0) timeout = if (timeout < 0) next else @min(timeout, next);
        };
        return timeout;
    }
    fn updateCursorAnimation(self: *Window) i32 {
        if (self.suspended or self.hasPointerCapture()) return -1;
        if (!self.cursor_hidden) if (self.cursor_image) |*cursor| {
            cursor.tick(self.io);
            return cursor.timeout(self.io);
        };
        return -1;
    }

    /// Ask the compositor to enter or leave xdg-shell fullscreen mode. The
    /// actual size arrives asynchronously in the following configure event.
    pub fn setFullscreen(self: *Window, fullscreen: bool) void {
        if (!self.fullscreen.request(fullscreen)) return;
        if (fullscreen) {
            self.toplevel.setFullscreen(null);
        } else {
            self.toplevel.unsetFullscreen();
        }
        self.presentation_dirty = true;
    }

    pub fn toggleFullscreen(self: *Window) void {
        self.setFullscreen(!self.fullscreen.actual);
    }

    pub fn isFullscreen(self: *const Window) bool {
        return self.fullscreen.actual;
    }

    /// True when the compositor exposes both wlroots virtual-input protocols.
    /// The devices themselves are created lazily, only for an enabled test API.
    pub fn testInputAvailable(self: *const Window) bool {
        return self.session.seat != null and self.session.virtual_pointer_manager != null and
            self.session.virtual_keyboard_manager != null;
    }

    /// Create the test devices and let the compositor advertise the resulting
    /// seat capabilities before the first injected event. Two roundtrips are
    /// intentional: the first delivers wl_seat.capabilities, whose listener
    /// requests wl_pointer/wl_keyboard; the second installs those objects.
    pub fn prepareTestInput(self: *Window) !void {
        _ = try self.session.ensureVirtualPointer();
        _ = try self.session.ensureVirtualKeyboard();
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        if (self.session.pointer == null or self.session.keyboard == null) return error.SeatCapabilitiesUnavailable;
    }

    /// Inject a real compositor-routed pointer click at an Android buffer
    /// coordinate. Cage sends the resulting wl_pointer events back through the
    /// normal input path; this does not call the Java host directly.
    pub fn testTap(self: *Window, buffer_x: i32, buffer_y: i32) !void {
        const x: u32 = @intCast(std.math.clamp(self.bufferToSurfaceX(buffer_x), 0, @max(0, self.width - 1)));
        const y: u32 = @intCast(std.math.clamp(self.bufferToSurfaceY(buffer_y), 0, @max(0, self.height - 1)));
        const width: u32 = @intCast(@max(1, self.width));
        const height: u32 = @intCast(@max(1, self.height));
        try self.testTapAtOutput(.{ x, y }, .{ width, height }, null);
    }

    /// The harness supplies output-local placement; Android synthetic origins
    /// must never be passed to the virtual pointer as desktop coordinates.
    pub fn testTapAtOutput(self: *Window, point: [2]u32, extent: [2]u32, local: ?[2]u32) !void {
        if (extent[0] == 0 or extent[1] == 0 or point[0] >= extent[0] or point[1] >= extent[1]) return error.InvalidTestPoint;
        const pointer = try self.session.ensureVirtualPointer();
        const time = inputTimestamp(self.io);
        pointer.motionAbsolute(time, point[0], point[1], extent[0], extent[1]);
        pointer.frame();
        if (local) |expected| try self.verifyTestPointer(expected);
        pointer.button(time, 0x110, .pressed); // BTN_LEFT
        pointer.frame();
        pointer.button(time +% 1, 0x110, .released);
        pointer.frame();
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    /// Relative virtual-device motion still travels through the compositor and
    /// its active pointer constraint before reaching the normal seat listener.
    pub fn testRelativeMotion(self: *Window, dx: f64, dy: f64) !void {
        if (!std.math.isFinite(dx) or !std.math.isFinite(dy)) return error.InvalidPointerDelta;
        const pointer = try self.session.ensureVirtualPointer();
        pointer.motion(inputTimestamp(self.io), wl.Fixed.fromDouble(dx), wl.Fixed.fromDouble(dy));
        pointer.frame();
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    /// Inject a compositor-routed primary-button drag between Android buffer
    /// coordinates. Each motion gets its own pointer frame so the client sees
    /// the same ordered gesture stream as it would from a physical mouse.
    pub fn testDrag(self: *Window, from_buffer_x: i32, from_buffer_y: i32, to_buffer_x: i32, to_buffer_y: i32) !void {
        const from_x: u32 = @intCast(std.math.clamp(self.bufferToSurfaceX(from_buffer_x), 0, @max(0, self.width - 1)));
        const from_y: u32 = @intCast(std.math.clamp(self.bufferToSurfaceY(from_buffer_y), 0, @max(0, self.height - 1)));
        const to_x: u32 = @intCast(std.math.clamp(self.bufferToSurfaceX(to_buffer_x), 0, @max(0, self.width - 1)));
        const to_y: u32 = @intCast(std.math.clamp(self.bufferToSurfaceY(to_buffer_y), 0, @max(0, self.height - 1)));
        const width: u32 = @intCast(@max(1, self.width));
        const height: u32 = @intCast(@max(1, self.height));
        try self.testDragAtOutput(.{ from_x, from_y }, .{ to_x, to_y }, .{ width, height }, null);
    }

    pub fn testDragAtOutput(self: *Window, from: [2]u32, to: [2]u32, extent: [2]u32, local: ?[2]u32) !void {
        if (extent[0] == 0 or extent[1] == 0 or from[0] >= extent[0] or from[1] >= extent[1] or
            to[0] >= extent[0] or to[1] >= extent[1]) return error.InvalidTestPoint;
        const pointer = try self.session.ensureVirtualPointer();
        const time = inputTimestamp(self.io);
        pointer.motionAbsolute(time, from[0], from[1], extent[0], extent[1]);
        pointer.frame();
        if (local) |expected| try self.verifyTestPointer(expected);
        pointer.button(time, 0x110, .pressed); // BTN_LEFT
        pointer.frame();
        for (1..11) |step| {
            const x = interpolateCoordinate(from[0], to[0], step, 10);
            const y = interpolateCoordinate(from[1], to[1], step, 10);
            pointer.motionAbsolute(time +% @as(u32, @intCast(step)), x, y, extent[0], extent[1]);
            pointer.frame();
        }
        pointer.button(time +% 11, 0x110, .released);
        pointer.frame();
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    fn verifyTestPointer(self: *Window, local: [2]u32) !void {
        // Roundtrip only the motion. Inspect the compositor's actual hit target
        // and surface coordinates before emitting any button edge.
        const size = [2]i32{ self.width, self.height };
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        if (size[0] != self.width or size[1] != self.height) return error.InputPlacementChanged;
        if (self.session.pointer_focus != self) return error.InputTargetOccluded;
        if (@abs(self.pointer_x - @as(f64, @floatFromInt(local[0]))) > 1 or
            @abs(self.pointer_y - @as(f64, @floatFromInt(local[1]))) > 1) return error.InputPlacementChanged;
    }

    pub fn validateTestText(text: []const u8) !void {
        for (text) |byte| _ = asciiKey(byte) orelse return error.UnsupportedText;
    }

    /// Type UTF-8 text through Cage's virtual keyboard. The fixed US keymap
    /// intentionally accepts printable ASCII plus newline/tab/backspace;
    /// semantic set_text remains available for arbitrary Unicode input.
    pub fn testType(self: *Window, text: []const u8) !void {
        // Reject the whole request before typing a prefix of unsupported text.
        try validateTestText(text);
        const keyboard = try self.session.ensureVirtualKeyboard();
        for (text) |byte| {
            const key = asciiKey(byte) orelse return error.UnsupportedText;
            self.sendVirtualKey(keyboard, key.code, key.shift);
        }
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    pub fn testKey(self: *Window, name: []const u8) !void {
        const code = namedKey(name) orelse return error.UnsupportedKey;
        const keyboard = try self.session.ensureVirtualKeyboard();
        self.sendVirtualKey(keyboard, code, false);
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    fn sendVirtualKey(self: *Window, keyboard: *zwp.VirtualKeyboardV1, code: u32, shift: bool) void {
        const time = inputTimestamp(self.io);
        if (shift) {
            keyboard.key(time, 42, 1); // KEY_LEFTSHIFT
            keyboard.modifiers(1, 0, 0, 0);
        }
        keyboard.key(time, code, 1);
        keyboard.key(time +% 1, code, 0);
        if (shift) {
            keyboard.modifiers(0, 0, 0, 0);
            keyboard.key(time +% 1, 42, 0);
        }
    }

    /// Testing hook: behave as if the compositor resized us.
    pub fn simulateResize(self: *Window, w: i32, h: i32) void {
        self.width = w;
        self.height = h;
        self.updateViewport();
        self.updateWindowGeometry();
        self.updateOpaqueRegion();
        self.presentation_dirty = true;
    }

    /// Block until Wayland, Android's main queue, RenderThread, or video work is ready. The
    /// prepare/read sequence follows libwayland's required ordering when polling other fds.
    pub fn waitEvents(self: *Window, sources: WakeSources) !WaitResult {
        self.events.clearRetainingCapacity();
        while (self.events.items.len == 0) {
            if (self.session.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
            if (self.session.output_failure) |failure| return failure;
            _ = self.emitKeyRepeatIfDue();
            if (self.events.items.len != 0) break;

            while (!self.session.display.prepareRead()) {
                if (self.session.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
                if (self.session.output_failure) |failure| return failure;
                if (self.events.items.len != 0) return .{ .events = self.events.items };
            }
            var wait_for_write = false;
            while (true) {
                switch (self.session.display.flush()) {
                    .SUCCESS => break,
                    .AGAIN => {
                        wait_for_write = true;
                        break;
                    },
                    .INTR => continue,
                    else => {
                        self.session.display.cancelRead();
                        return error.FlushFailed;
                    },
                }
            }

            var poll_fds = [_]std.posix.pollfd{
                .{
                    .fd = self.session.display.getFd(),
                    .events = std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP |
                        @as(i16, if (wait_for_write) std.posix.POLL.OUT else 0),
                    .revents = 0,
                },
                .{ .fd = sources.video orelse -1, .events = std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP, .revents = 0 },
                .{ .fd = sources.looper orelse -1, .events = std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP, .revents = 0 },
                .{ .fd = sources.renderer orelse -1, .events = std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP, .revents = 0 },
                .{ .fd = sources.optimizer orelse -1, .events = std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP, .revents = 0 },
                .{ .fd = sources.control_listener orelse -1, .events = std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP, .revents = 0 },
                .{ .fd = sources.control_client orelse -1, .events = controlClientPollEvents(sources.control_client_write), .revents = 0 },
            };
            const key_timeout = self.keyRepeatTimeoutMs();
            const timeout = if (key_timeout < 0)
                sources.timeout_ms
            else if (sources.timeout_ms < 0)
                key_timeout
            else
                @min(key_timeout, sources.timeout_ms);
            const ready_count = try std.posix.poll(&poll_fds, timeout);

            const wayland_readable = poll_fds[0].revents &
                (std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP) != 0;
            const video_ready = sources.video != null and poll_fds[1].revents != 0;
            const looper_ready = sources.looper != null and poll_fds[2].revents != 0;
            const renderer_ready = sources.renderer != null and poll_fds[3].revents != 0;
            const optimizer_ready = sources.optimizer != null and poll_fds[4].revents != 0;
            const control_ready = (sources.control_listener != null and poll_fds[5].revents != 0) or
                (sources.control_client != null and poll_fds[6].revents != 0);
            if (wayland_readable) {
                if (self.session.display.readEvents() != .SUCCESS) return error.DispatchFailed;
                if (self.session.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
                if (self.session.output_failure) |failure| return failure;
            } else {
                self.session.display.cancelRead();
            }
            _ = self.emitKeyRepeatIfDue();
            if (video_ready or looper_ready or renderer_ready or optimizer_ready or control_ready or ready_count == 0) {
                return .{
                    .events = self.events.items,
                    .video_ready = video_ready,
                    .looper_ready = looper_ready,
                    .renderer_ready = renderer_ready,
                    .optimizer_ready = optimizer_ready,
                    .control_ready = control_ready,
                    .timed_out = ready_count == 0,
                };
            }
        }
        return .{ .events = self.events.items };
    }

    fn controlClientPollEvents(wants_write: bool) i16 {
        return std.posix.POLL.IN | std.posix.POLL.ERR | std.posix.POLL.HUP |
            @as(i16, if (wants_write) std.posix.POLL.OUT else 0);
    }

    /// Return this window's events without clearing notifications belonging to
    /// peers. The returned slice is valid until the next takeEvents or deinit.
    pub fn takeEvents(self: *Window) []const Event {
        self.delivered_events.clearRetainingCapacity();
        std.mem.swap(std.ArrayList(Event), &self.events, &self.delivered_events);
        return self.delivered_events.items;
    }

    /// Non-blocking drain of pending events.
    pub fn pollEvents(self: *Window) ![]const Event {
        self.events.clearRetainingCapacity();
        if (self.session.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
        if (self.session.output_failure) |failure| return failure;
        _ = self.emitKeyRepeatIfDue();
        _ = self.session.display.flush();
        return self.events.items;
    }

    pub fn fd(self: *Window) std.posix.fd_t {
        return self.session.display.getFd();
    }

    /// Release all client-side resources before the process disconnects from
    /// the compositor. Video layers must be destroyed before calling this.
    pub fn deinit(self: *Window) void {
        self.clearCursorImage();
        if (self.frame_cb) |callback| callback.destroy();
        self.frame_cb = null;
        self.setPointerCapture(false) catch unreachable;
        self.session.removeWindow(self);
        // Tear down the child surface tree before destroying its xdg parent.
        for (self.ui_dmabuf_buffers.items) |state| state.wl_buffer.destroy();
        self.ui_viewport.destroy();
        self.ui_subsurface.destroy();
        self.ui_surface.destroy();
        self.base_buffer.destroy();
        self.opaque_region.destroy();
        if (self.fractional_scale) |fractional_scale| fractional_scale.destroy();
        self.viewport.destroy();
        self.toplevel.destroy();
        self.xdg_surface.destroy();
        self.surface.destroy();
        self.events.deinit(self.gpa);
        self.delivered_events.deinit(self.gpa);
        for (self.ui_dmabuf_buffers.items) |state| {
            state.frame.abandon();
            self.gpa.destroy(state);
        }
        self.ui_dmabuf_buffers.deinit(self.gpa);
        const session = self.session;
        const owns_session = self.owns_session;
        self.gpa.destroy(self);
        if (owns_session) session.deinit();
    }

    // ---------------------------------------------------------------------
    // Video layers: wl_subsurfaces between the toplevel base and Android UI,
    // scaled by the compositor via wp_viewport. The UI punches a transparent
    // hole where the SurfaceView sits, so the video shows through.
    pub const VideoLayer = struct {
        win: *Window,
        surface: *wl.Surface,
        subsurface: *wl.Subsurface,
        viewport: *wp.Viewport,
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
        dmabuf_buffers: std.ArrayList(*ImportedDmabuf) = .empty,

        fn configureViewport(self: *VideoLayer) void {
            const vp = self.viewport;
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
            self.win.presentation_dirty = true;
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
            const wl_buffer = try self.win.importDmabuf(frame, &self.dmabuf_buffers);

            self.src_w = frame.width;
            self.src_h = frame.height;
            self.surface.attach(wl_buffer, 0, 0);
            self.configureViewport();
            self.damageDestination(frame.width, frame.height);
            self.surface.commit();
            self.win.presentation_dirty = true;
        }

        pub fn destroy(self: *VideoLayer) void {
            for (self.dmabuf_buffers.items) |state| {
                state.wl_buffer.destroy();
                state.frame.abandon();
                self.win.gpa.destroy(state);
            }
            self.dmabuf_buffers.deinit(self.win.gpa);
            self.viewport.destroy();
            self.subsurface.destroy();
            self.surface.destroy();
            self.win.presentation_dirty = true;
            self.win.gpa.destroy(self);
        }
    };

    pub fn createVideoLayer(self: *Window) !*VideoLayer {
        const sub = self.session.subcompositor orelse return error.NoSubcompositor;
        const vsurf = try self.session.compositor.?.createSurface();
        vsurf.setListener(*Window, childSurfaceListener, self);
        const subsurf = try sub.getSubsurface(vsurf, self.surface);
        // State the complete parent < video < UI ordering explicitly. This is
        // the host equivalent of SurfaceView's SurfaceControl relative-layer
        // transaction. With only place_below(UI), Hyprland rendered a newly
        // synchronized video child behind the opaque parent after subsequent
        // invalidation-driven UI commits.
        subsurf.placeAbove(self.surface);
        self.ui_subsurface.placeAbove(vsurf);
        self.presentation_dirty = true;
        // input falls through to the main surface
        const region = try self.session.compositor.?.createRegion();
        vsurf.setInputRegion(region);
        region.destroy();
        const vp = try self.session.viewporter.?.getViewport(vsurf);
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

test "fullscreen request survives a stale compositor configure" {
    var state = FullscreenState.init(false);

    try std.testing.expect(state.request(true));
    try std.testing.expect(!state.configure(false));
    try std.testing.expect(state.requested);
    try std.testing.expect(state.pending);

    try std.testing.expect(state.configure(true));
    try std.testing.expect(state.actual);
    try std.testing.expect(state.requested);
    try std.testing.expect(!state.pending);
}

test "fullscreen exit survives a stale fullscreen configure" {
    var state = FullscreenState.init(false);
    try std.testing.expect(state.request(true));
    _ = state.configure(true);

    try std.testing.expect(state.request(false));
    try std.testing.expect(!state.configure(true));
    try std.testing.expect(!state.requested);
    try std.testing.expect(state.pending);

    try std.testing.expect(state.configure(false));
    try std.testing.expect(!state.actual);
    try std.testing.expect(!state.requested);
    try std.testing.expect(!state.pending);
}

test "compositor fullscreen change becomes the requested baseline" {
    var state = FullscreenState.init(false);

    try std.testing.expect(state.configure(true));
    try std.testing.expect(state.actual);
    try std.testing.expect(state.requested);
    try std.testing.expect(!state.pending);
    try std.testing.expect(!state.request(true));
}

test "virtual keyboard uses Linux evdev codes for the fixed US keymap" {
    try std.testing.expectEqual(TestKey{ .code = 30 }, asciiKey('a').?);
    try std.testing.expectEqual(TestKey{ .code = 30, .shift = true }, asciiKey('A').?);
    try std.testing.expectEqual(TestKey{ .code = 3, .shift = true }, asciiKey('@').?);
    try std.testing.expectEqual(@as(u32, 28), namedKey("enter").?);
    try std.testing.expectEqual(@as(u32, 158), namedKey("Back").?);
    try std.testing.expectEqual(@as(u32, 87), namedKey("F11").?);
    try std.testing.expect(asciiKey(0xc3) == null);
}

test "pointer axis frame prefers high resolution detents and Android direction" {
    try std.testing.expectEqual(@as(f64, -1), PointerAxisFrame.androidValue(15, null, 120));
    try std.testing.expectEqual(@as(f64, 0.5), PointerAxisFrame.androidValue(-15, null, -60));
    try std.testing.expectEqual(@as(f64, -2), PointerAxisFrame.androidValue(30, 2, null));
    try std.testing.expectEqual(@as(f64, -1.5), PointerAxisFrame.androidValue(15, null, null));
}

test "control client polls writable only while output is pending" {
    const idle = Window.controlClientPollEvents(false);
    const pending = Window.controlClientPollEvents(true);
    try std.testing.expect(idle & std.posix.POLL.OUT == 0);
    try std.testing.expect(pending & std.posix.POLL.OUT != 0);
    try std.testing.expect(pending & std.posix.POLL.IN != 0);
}

test "pointer capture rejects missing capability and snapshots acknowledgement sequences" {
    var session: Session = undefined;
    session.ordered_events = .{};
    session.pointer = null;
    defer session.ordered_events.?.deinit(std.testing.allocator);
    var window: Window = .{ .session = &session, .gpa = std.testing.allocator, .io = undefined, .width = 100, .height = 100 };
    try std.testing.expectError(error.PointerCapabilityUnavailable, window.requestPointerCapture(true, 1));
    try std.testing.expect(!window.pointer_capture_requested);
    try std.testing.expect(!window.pointer_capture_actual);
    try window.requestPointerCapture(false, 2);
    try window.requestPointerCapture(false, 3);
    const events = session.ordered_events.?.take();
    try std.testing.expectEqual(@as(usize, 2), events.len);
    try std.testing.expectEqual(@as(i32, 2), events[0].event.pointer_capture_changed.sequence);
    try std.testing.expectEqual(@as(i32, 3), events[1].event.pointer_capture_changed.sequence);
    try std.testing.expect(!events[0].event.pointer_capture_changed.enabled);
}

test "queued scroll retains its surface position across later pointer motion" {
    var session: Session = undefined;
    session.ordered_events = .{};
    defer session.ordered_events.?.deinit(std.testing.allocator);
    var window: Window = .{ .session = &session, .gpa = std.testing.allocator, .io = undefined, .width = 100, .height = 100 };
    window.pointer_x = 12;
    window.pointer_y = 13;
    window.pointer_axis_frame.vertical_value120 = 120;
    window.flushPointerAxisFrame();
    window.pointer_x = 99;
    window.pointer_y = 98;
    const events = session.ordered_events.?.take();
    try std.testing.expectEqual(@as(usize, 1), events.len);
    const axis = events[0].event.pointer_axis;
    try std.testing.expectEqual(@as(f64, 12), axis.x);
    try std.testing.expectEqual(@as(f64, 13), axis.y);
    try std.testing.expectEqual(@as(f64, -1), axis.vertical);
}

test "session input preserves cross-window order, removal and bounded admission" {
    var events: OrderedEvents = .{};
    defer events.deinit(std.testing.allocator);
    var first: Window = undefined;
    var second: Window = undefined;
    events.push(std.testing.allocator, &second, .keyboard_reset);
    events.push(std.testing.allocator, &first, .{ .keyboard_focus = .{ .active = true } });
    events.push(std.testing.allocator, &first, .{ .key = .{ .keycode = 30, .pressed = true, .unicode = 97, .keysym = 97 } });
    const delivered = events.take();
    try std.testing.expectEqual(@as(usize, 3), delivered.len);
    try std.testing.expect(delivered[0].window == &second and delivered[0].event == .keyboard_reset);
    try std.testing.expect(delivered[1].window == &first and delivered[1].event == .keyboard_focus);
    try std.testing.expect(delivered[2].event == .key);
    events.push(std.testing.allocator, &second, .close);
    events.push(std.testing.allocator, &first, .close);
    events.removeWindow(&second);
    try std.testing.expectEqual(@as(usize, 1), events.take().len);
    for (0..OrderedEvents.limit) |_| events.push(std.testing.allocator, &first, .keyboard_reset);
    try std.testing.expect(!events.failed);
    events.push(std.testing.allocator, &first, .keyboard_reset);
    try std.testing.expect(events.failed);
    try std.testing.expectEqual(OrderedEvents.limit, events.pending.items.len);
}

test "queued XKB layouts retain groups and produce Android character states" {
    const context = xkb.xkb_context_new(0) orelse return error.KeyboardContextAllocationFailed;
    defer xkb.xkb_context_unref(context);
    const source = try std.mem.replaceOwned(u8, std.testing.allocator, test_keymap, "pc+us", "pc+us(intl)+de:2");
    defer std.testing.allocator.free(source);
    const map = xkb.xkb_keymap_new_from_string(context, @ptrCast(source.ptr), 1, 0) orelse return error.KeyboardMapParseFailed;
    var events: OrderedEvents = .{};
    defer events.deinit(std.testing.allocator);
    var window: Window = undefined;
    events.push(std.testing.allocator, &window, .{ .keyboard_layout = .{
        .keymap = xkb.xkb_keymap_ref(map),
        .group = 0,
        .generation = 1,
    } });
    events.push(std.testing.allocator, &window, .{ .keyboard_layout = .{
        .keymap = xkb.xkb_keymap_ref(map),
        .group = 1,
        .generation = 2,
    } });
    xkb.xkb_keymap_unref(map); // Queue retains both immutable snapshots.
    const snapshots = events.take();
    const us = try snapshots[0].event.keyboard_layout.characters();
    const de = try snapshots[1].event.keyboard_layout.characters();
    try std.testing.expectEqual(@as(i32, 'a'), us[30 * 64]);
    try std.testing.expectEqual(@as(i32, 'A'), us[30 * 64 + 1]);
    try std.testing.expectEqual(@as(i32, 'A'), us[30 * 64 + 2]);
    try std.testing.expectEqual(@as(i32, 'a'), us[30 * 64 + 3]);
    try std.testing.expectEqual(@as(i32, 0x0301), us[40 * 64]);
    try std.testing.expectEqual(@as(i32, 'y'), us[21 * 64]);
    try std.testing.expectEqual(@as(i32, 'z'), de[21 * 64]);
    try std.testing.expectEqual(@as(i32, 0x20ac), de[18 * 64 + 4]); // AltGr+E.
}

test "XKB modifier remaps retain non-character key identity" {
    const context = xkb.xkb_context_new(0) orelse return error.KeyboardContextAllocationFailed;
    defer xkb.xkb_context_unref(context);
    const source = try std.mem.replaceOwned(u8, std.testing.allocator, test_keymap, "pc+us", "pc+us+ctrl(nocaps)");
    defer std.testing.allocator.free(source);
    const keymap = xkb.xkb_keymap_new_from_string(context, @ptrCast(source.ptr), 1, 0) orelse return error.KeyboardMapFailed;
    defer xkb.xkb_keymap_unref(keymap);
    const layout: KeyboardLayout = .{ .keymap = keymap, .group = 0, .generation = 1 };
    const symbols = try layout.symbols();
    try std.testing.expectEqual(@as(i32, 0xffe3), symbols[58]); // Caps now Control_L.
    try std.testing.expectEqual(@as(i32, 0xffe3), symbols[29]); // Original Control_L remains.
    try std.testing.expectEqual(@as(i32, 'a'), symbols[30]);
    const characters = try layout.characters();
    try std.testing.expectEqual(@as(i32, 'a'), characters[30 * 64]);
    try std.testing.expectEqual(@as(i32, 1), characters[30 * 64 + 16]); // Control-A.
}

test "XKB snapshots include the last supported evdev scan position" {
    const context = xkb.xkb_context_new(0) orelse return error.KeyboardContextAllocationFailed;
    defer xkb.xkb_context_unref(context);
    const source =
        \\xkb_keymap {
        \\  xkb_keycodes { minimum = 8; maximum = 775; <HIGH> = 775; };
        \\  xkb_types { include "complete" };
        \\  xkb_compatibility { include "complete" };
        \\  xkb_symbols { key <HIGH> { [ a, A ] }; };
        \\};
    ;
    const keymap = xkb.xkb_keymap_new_from_string(context, source, 1, 0) orelse return error.KeyboardMapFailed;
    defer xkb.xkb_keymap_unref(keymap);
    const layout: KeyboardLayout = .{ .keymap = keymap, .group = 0, .generation = 1 };
    const symbols = try layout.symbols();
    const characters = try layout.characters();
    try std.testing.expectEqual(@as(i32, 'a'), symbols[767]);
    try std.testing.expectEqual(@as(i32, 'a'), characters[767 * 64]);
    try std.testing.expectEqual(@as(i32, 'A'), characters[767 * 64 + 1]);
}

test "keyboard enter snapshots held keys and bounds unknown scan codes" {
    const focus = KeyboardFocus.entered(&.{ 29, 42, 54, 767, 42, 768, 0xffffffff });
    try std.testing.expect(focus.active);
    try std.testing.expectEqual((@as(u64, 1) << 29) | (@as(u64, 1) << 42) | (@as(u64, 1) << 54), focus.held[0]);
    try std.testing.expectEqual(@as(u64, 1) << 63, focus.held[11]);
}

test "keyboard modifier snapshots distinguish held keys from persistent Shift" {
    const context = xkb.xkb_context_new(0) orelse return error.KeyboardContextAllocationFailed;
    defer xkb.xkb_context_unref(context);
    const keymap = xkb.xkb_keymap_new_from_string(context, test_keymap, 1, 0) orelse return error.KeyboardMapFailed;
    defer xkb.xkb_keymap_unref(keymap);
    const state = xkb.xkb_state_new(keymap) orelse return error.KeyboardStateAllocationFailed;
    defer xkb.xkb_state_unref(state);
    const index = xkb.xkb_keymap_mod_get_index(keymap, "Shift");
    try std.testing.expect(index < 32);
    const shift = @as(u32, 1) << @intCast(index);
    var session: Session = undefined;
    session.ordered_events = .{};
    session.gpa = std.testing.allocator;
    session.xkb_state = state;
    defer session.ordered_events.?.deinit(std.testing.allocator);
    for (0..3) |kind| {
        _ = xkb.xkb_state_update_mask(state, if (kind == 0) shift else 0, if (kind == 1) shift else 0, if (kind == 2) shift else 0, 0, 0, 0);
        session.queueModifiers();
        const snapshot = session.ordered_events.?.take()[0].event.keyboard_modifiers;
        try std.testing.expect(snapshot.effective & 1 != 0);
        try std.testing.expectEqual(kind != 0, snapshot.persistent & 1 != 0);
    }
}

test "stock pointer styles retain named compositor shapes" {
    const shapes = @import("cursor_shape.zig");
    try std.testing.expectEqual(@as(u32, 1), shapes.fromAndroid(1000).?);
    try std.testing.expectEqual(@as(u32, 4), shapes.fromAndroid(1002).?);
    try std.testing.expectEqual(@as(u32, 9), shapes.fromAndroid(1008).?);
    try std.testing.expectEqual(@as(u32, 26), shapes.fromAndroid(1014).?);
    try std.testing.expect(shapes.fromAndroid(-1) == null);
    try std.testing.expect(shapes.fromAndroid(0) == null);
}

test "seat capability chronology survives removal of the last task" {
    var events: OrderedEvents = .{};
    defer events.deinit(std.testing.allocator);
    var window: Window = undefined;
    events.push(std.testing.allocator, &window, .pointer_cancel);
    events.push(std.testing.allocator, null, .{ .seat_capabilities = .{ .generation = 7, .mask = 2 } });
    const before = events.take();
    try std.testing.expect(before[0].event == .pointer_cancel);
    try std.testing.expect(before[1].window == null);
    events.removeWindow(&window);
    try std.testing.expectEqual(@as(usize, 1), events.delivered.items.len);
    try std.testing.expectEqual(@as(i32, 7), events.delivered.items[0].event.seat_capabilities.generation);
    events.push(std.testing.allocator, null, .{ .seat_capabilities = .{ .generation = 8, .mask = 3 } });
    events.removeWindow(&window);
    const next = events.take();
    try std.testing.expectEqual(@as(usize, 1), next.len);
    try std.testing.expectEqual(@as(i32, 8), next[0].event.seat_capabilities.generation);
}

test "test input rejects a removed seat without choosing another compositor seat" {
    var session: Session = undefined;
    session.seat = null;
    session.virtual_pointer = null;
    session.virtual_keyboard = null;
    session.virtual_keyboard_manager = null;
    try std.testing.expectError(error.VirtualPointerUnavailable, session.testPointerPresent(true));
    try std.testing.expectError(error.VirtualKeyboardUnavailable, session.prepareTestKeyboard());
}

test "test text validates the complete request before any key can be sent" {
    try Window.validateTestText("aAZ09 !@#$%^&*()_+-=\n\t");
    try std.testing.expectError(error.UnsupportedText, Window.validateTestText("supported prefix\xc3\xa9"));
    try std.testing.expectError(error.UnsupportedText, Window.validateTestText("supported prefix\x00"));
}

test "queued keyboard layout pressure includes borrowed snapshots and releases on drain" {
    const context = xkb.xkb_context_new(0) orelse return error.KeyboardContextAllocationFailed;
    defer xkb.xkb_context_unref(context);
    const map = xkb.xkb_keymap_new_from_string(context, test_keymap, 1, 0) orelse return error.KeyboardMapParseFailed;
    defer xkb.xkb_keymap_unref(map);
    var events: OrderedEvents = .{};
    defer events.deinit(std.testing.allocator);
    for (0..OrderedEvents.layout_limit) |index| {
        events.push(std.testing.allocator, null, .{ .keyboard_layout = .{
            .keymap = xkb.xkb_keymap_ref(map),
            .group = 0,
            .generation = @intCast(index + 1),
            .source_bytes = test_keymap.len,
        } });
        events.push(std.testing.allocator, null, .keyboard_reset);
    }
    const retained = events.take();
    try std.testing.expectEqual(2 * OrderedEvents.layout_limit, retained.len);
    try std.testing.expect(!events.failed);
    // Taking a batch borrows its snapshots; it cannot make their budget free.
    events.push(std.testing.allocator, null, .{ .keyboard_layout = .{
        .keymap = xkb.xkb_keymap_ref(map),
        .group = 0,
        .generation = 99,
    } });
    try std.testing.expect(events.failed);
    try std.testing.expectEqual(@as(usize, 0), events.pending.items.len);
    for (0..OrderedEvents.layout_limit) |index|
        try std.testing.expectEqual(@as(i32, @intCast(index + 1)), retained[index * 2].event.keyboard_layout.generation);
    _ = events.take();
    try std.testing.expectEqual(@as(usize, 0), events.retained_layouts);
    try std.testing.expectEqual(@as(usize, 0), events.retained_layout_source_bytes);
}

test "queued keyboard layout source budget and window removal" {
    const context = xkb.xkb_context_new(0) orelse return error.KeyboardContextAllocationFailed;
    defer xkb.xkb_context_unref(context);
    const map = xkb.xkb_keymap_new_from_string(context, test_keymap, 1, 0) orelse return error.KeyboardMapParseFailed;
    defer xkb.xkb_keymap_unref(map);
    var events: OrderedEvents = .{};
    defer events.deinit(std.testing.allocator);
    var window: Window = undefined;
    for (0..2) |index| events.push(std.testing.allocator, &window, .{ .keyboard_layout = .{
        .keymap = xkb.xkb_keymap_ref(map),
        .group = 0,
        .generation = @intCast(index + 1),
        .source_bytes = OrderedEvents.layout_source_limit / 2,
    } });
    _ = events.take();
    events.removeWindow(&window);
    try std.testing.expectEqual(@as(usize, 0), events.retained_layout_source_bytes);
    // Removal makes capacity available without losing the next layout.
    events.push(std.testing.allocator, null, .{ .keyboard_layout = .{
        .keymap = xkb.xkb_keymap_ref(map),
        .group = 0,
        .generation = 3,
        .source_bytes = OrderedEvents.layout_source_limit,
    } });
    try std.testing.expect(!events.failed);
    events.push(std.testing.allocator, null, .{ .keyboard_layout = .{
        .keymap = xkb.xkb_keymap_ref(map),
        .group = 0,
        .generation = 4,
        .source_bytes = 1,
    } });
    try std.testing.expect(events.failed);
    const retained = events.take();
    try std.testing.expectEqual(@as(usize, 1), retained.len);
    try std.testing.expectEqual(@as(i32, 3), retained[0].event.keyboard_layout.generation);
    const chars = try retained[0].event.keyboard_layout.characters();
    try std.testing.expectEqual(@as(i32, 'a'), chars[30 * 64]);
}
