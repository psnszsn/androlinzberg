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
    pub extern fn xkb_keymap_unref(keymap: ?*Keymap) void;
    pub extern fn xkb_state_new(keymap: *Keymap) ?*State;
    pub extern fn xkb_state_unref(state: ?*State) void;
    pub extern fn xkb_state_update_mask(state: *State, depressed: u32, latched: u32, locked: u32, layout_depressed: u32, layout_latched: u32, layout_locked: u32) c_int;
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

pub const Event = union(enum) {
    close,
    configure: struct { width: i32, height: i32 },
    fullscreen_changed: bool,
    pointer_motion: struct { x: f64, y: f64 },
    pointer_enter: struct { x: f64, y: f64 },
    pointer_leave: struct { x: f64, y: f64 },
    pointer_button: struct { button: u32, pressed: bool, x: f64, y: f64 },
    pointer_axis: struct { horizontal: f64, vertical: f64 },
    pointer_cancel,
    pointer_relative: struct { dx: f64, dy: f64 },
    pointer_capture_changed: bool,
    key: struct { keycode: u32, pressed: bool, unicode: u32, keysym: u32 },
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
    frame_done: struct { time_ms: u32 },
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

/// One compositor connection, registry and seat for every task in the session.
/// Protocol callbacks and surface mutations run on the owning event thread.
/// Windows retain their own geometry, gestures and buffer leases.
pub const Session = struct {
    gpa: std.mem.Allocator,
    io: std.Io,
    windows: std.ArrayList(*Window) = .empty,
    pointer_focus: ?*Window = null,
    keyboard_focus: ?*Window = null,
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
    output: ?*wl.Output = null,
    pointer: ?*wl.Pointer = null,
    keyboard: ?*wl.Keyboard = null,
    touch_device: ?*wl.Touch = null,
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
    xkb_state: ?*xkb.State = null,
    keyboard_repeat_rate: i32 = 0,
    keyboard_repeat_delay_ms: i32 = 0,

    pub fn init(gpa: std.mem.Allocator, io: std.Io) !*Session {
        const self = try gpa.create(Session);
        errdefer gpa.destroy(self);
        const display = try wl.Display.connect(null);
        errdefer display.disconnect();
        self.* = .{ .gpa = gpa, .io = io, .display = display, .registry = try display.getRegistry() };
        errdefer self.destroyGlobals();
        self.registry.setListener(*Session, registryListener, self);
        if (display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        self.requireGlobals();
        return self;
    }

    pub fn createWindow(self: *Session, width: i32, height: i32, title: [:0]const u8, fullscreen: bool) !*Window {
        return Window.initInSession(self, width, height, title, fullscreen);
    }

    /// Dispatch the shared connection and consume eventfd notifications owned by
    /// the task presenter. Events remain queued per window until takeEvents().
    /// The caller must inspect every ready slot even when a Wayland event also
    /// arrives. Files are borrowed for this call; libwayland retains its socket.
    pub fn dispatch(self: *Session, wakes: []const std.Io.File, ready: []bool, timeout_ms: i32) !void {
        if (wakes.len == 0 or wakes.len > 64 or ready.len != wakes.len) return error.InvalidWakeSources;
        @memset(ready, false);
        if (self.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
        var timeout = timeout_ms;
        for (self.windows.items) |window| {
            _ = window.emitKeyRepeatIfDue();
            if (window.events.items.len != 0) timeout = 0;
            const repeat = window.keyRepeatTimeoutMs();
            if (repeat >= 0) timeout = if (timeout < 0) repeat else @min(timeout, repeat);
        }
        while (!self.display.prepareRead()) {
            if (self.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
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

        var storage: [65]std.Io.Operation.Storage = undefined;
        var batch = std.Io.Batch.init(storage[0 .. wakes.len + 1]);
        defer batch.cancel(self.io);
        // The pinned std.Io.Threaded batches file operations behind readiness,
        // including a zero-byte read. This borrows the libwayland socket without
        // consuming its wire bytes or SCM_RIGHTS. Avoid net_receive(MSG_PEEK):
        // this Zig version leaves an immediately completed receive linked in
        // Batch.submitted, corrupting cancellation when bytes are already ready.
        const borrowed: std.Io.File = .{
            .handle = self.display.getFd(), .flags = .{ .nonblocking = true },
        };
        var empty: [0]u8 = .{};
        batch.addAt(0, .{ .file_read_streaming = .{ .file = borrowed, .data = &.{&empty} } });
        var counters: [64]u64 = undefined;
        var vectors: [64][1][]u8 = undefined;
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
        }
        for (self.windows.items) |window| _ = window.emitKeyRepeatIfDue();
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
                self.xkb_state = xkb.xkb_state_new(keymap);
            },
            .modifiers => |m| {
                if (self.xkb_state) |st| {
                    _ = xkb.xkb_state_update_mask(st, m.mods_depressed, m.mods_latched, m.mods_locked, 0, 0, m.group);
                }
            },
            .enter => |entered| {
                self.keyboard_focus = self.surfaceWindow(entered.surface);
            },
            .leave => {
                if (self.keyboard_focus) |window| Window.keyboardListener(keyboard, event, window);
                self.keyboard_focus = null;
            },
            .repeat_info => |repeat| {
                self.keyboard_repeat_rate = repeat.rate;
                self.keyboard_repeat_delay_ms = repeat.delay;
                if (repeat.rate == 0) for (self.windows.items) |window| {
                    window.key_repeat = null;
                };
            },
            else => if (self.keyboard_focus) |window| Window.keyboardListener(keyboard, event, window),
        }
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
            window.setPointerCapture(false);
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
    }

    fn releaseTouch(self: *Session) void {
        for (self.windows.items) |window| window.cancelTouches();
        if (self.touch_device) |touch| touch.release();
        self.touch_device = null;
    }

    fn hasPointerCapture(self: *const Session) bool {
        for (self.windows.items) |window| if (window.pointer_capture_requested or window.pointer_capture_actual) return true;
        return false;
    }

    pub fn deinit(self: *Session) void {
        std.debug.assert(self.windows.items.len == 0);
        self.destroyGlobals();
        self.display.disconnect();
        self.gpa.destroy(self);
    }

    fn destroyGlobals(self: *Session) void {
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
        if (self.output) |output| {
            const proxy: *wl.Proxy = @ptrCast(output);
            // wl_output v2 has no protocol destructor.
            proxy.destroy();
        }
        if (self.compositor) |compositor| compositor.destroy();
        if (self.shm) |shm| shm.destroy();
        if (self.wm_base) |wm_base| wm_base.destroy();
        self.registry.destroy();
        self.dmabuf_formats.deinit(self.gpa);
        self.windows.deinit(self.gpa);
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
                    self.compositor = registry.bind(g.name, wl.Compositor, 4) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wl.Subcompositor.interface.name) == .eq) {
                    self.subcompositor = registry.bind(g.name, wl.Subcompositor, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wl.Shm.interface.name) == .eq) {
                    self.shm = registry.bind(g.name, wl.Shm, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wp.Viewporter.interface.name) == .eq) {
                    self.viewporter = registry.bind(g.name, wp.Viewporter, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, wp.FractionalScaleManagerV1.interface.name) == .eq) {
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
                } else if (std.mem.orderZ(u8, g.interface, wp.CursorShapeManagerV1.interface.name) == .eq) {
                    self.cursor_shape_manager = registry.bind(g.name, wp.CursorShapeManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwlr.VirtualPointerManagerV1.interface.name) == .eq) {
                    self.virtual_pointer_manager = registry.bind(g.name, zwlr.VirtualPointerManagerV1, @min(g.version, 2)) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwp.VirtualKeyboardManagerV1.interface.name) == .eq) {
                    self.virtual_keyboard_manager = registry.bind(g.name, zwp.VirtualKeyboardManagerV1, 1) catch return;
                } else if (std.mem.orderZ(u8, g.interface, zwp.PointerConstraintsV1.interface.name) == .eq and self.pointer_constraints == null) {
                    self.pointer_constraints = registry.bind(g.name, zwp.PointerConstraintsV1, 1) catch return;
                    self.pointer_constraints_global_name = g.name;
                } else if (std.mem.orderZ(u8, g.interface, zwp.RelativePointerManagerV1.interface.name) == .eq and self.relative_pointer_manager == null) {
                    self.relative_pointer_manager = registry.bind(g.name, zwp.RelativePointerManagerV1, 1) catch return;
                    self.relative_pointer_global_name = g.name;
                } else if (std.mem.orderZ(u8, g.interface, xdg.WmBase.interface.name) == .eq) {
                    self.wm_base = registry.bind(g.name, xdg.WmBase, 2) catch return;
                    self.wm_base.?.setListener(*Session, wmBaseListener, self);
                } else if (std.mem.orderZ(u8, g.interface, wl.Seat.interface.name) == .eq and self.seat == null) {
                    if (g.version < 8)
                        std.debug.panic("required wl_seat version 8 is unavailable (compositor advertises version {d}); framed high-resolution scrolling and touch shape require version 8", .{g.version});
                    self.seat = registry.bind(g.name, wl.Seat, 8) catch return;
                    self.seat_global_name = g.name;
                    self.seat.?.setListener(*Session, seatListener, self);
                } else if (std.mem.orderZ(u8, g.interface, wl.Output.interface.name) == .eq and self.output == null) {
                    self.output = registry.bind(g.name, wl.Output, @min(g.version, 2)) catch return;
                }
            },
            .global_remove => |removed| {
                if (self.seat_global_name == removed.name)
                    std.debug.panic("the compositor removed the required wl_seat global; Android input can no longer preserve device lifecycle", .{});
                if (self.pointer_constraints_global_name == removed.name) {
                    if (self.hasPointerCapture())
                        std.debug.panic("the compositor removed required zwp_pointer_constraints_v1 while Android pointer capture was active", .{});
                    if (self.pointer_constraints) |constraints| constraints.destroy();
                    self.pointer_constraints = null;
                    self.pointer_constraints_global_name = null;
                }
                if (self.relative_pointer_global_name == removed.name) {
                    if (self.hasPointerCapture())
                        std.debug.panic("the compositor removed required zwp_relative_pointer_manager_v1 while Android pointer capture was active", .{});
                    if (self.relative_pointer_manager) |manager| manager.destroy();
                    self.relative_pointer_manager = null;
                    self.relative_pointer_global_name = null;
                }
            },
        }
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
    pointer_enter_serial: u32 = 0,
    cursor_shape: u32 = 0, // last applied wp_cursor_shape_device_v1.shape (0 = none)

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
    fullscreen: FullscreenState = .{},
    configured: bool = false,
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

    fn initInSession(session: *Session, width: i32, height: i32, title: [:0]const u8, fullscreen: bool) !*Window {
        const gpa = session.gpa;
        const self = try gpa.create(Window);
        errdefer gpa.destroy(self);
        self.* = .{ .gpa = gpa, .io = session.io, .session = session, .width = width, .height = height, .fullscreen = FullscreenState.init(fullscreen) };
        try session.windows.append(gpa, self);
        errdefer session.removeWindow(self);
        errdefer self.events.deinit(gpa);
        errdefer if (self.frame_cb) |callback| callback.destroy();
        self.surface = try self.session.compositor.?.createSurface();
        errdefer self.surface.destroy();
        self.surface_created = true;
        // The xdg parent is only the opaque application background. Android
        // input is delivered through the UI child, so exclude the parent from
        // compositor hit testing.
        const empty_input_region = try self.session.compositor.?.createRegion();
        self.surface.setInputRegion(empty_input_region);
        empty_input_region.destroy();
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
        self.toplevel.setAppId("org.androlinzberg");
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
        errdefer self.ui_surface.destroy();
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

    fn xdgSurfaceListener(s: *xdg.Surface, event: xdg.Surface.Event, self: *Window) void {
        switch (event) {
            .configure => |c| {
                s.ackConfigure(c.serial);
                self.configured = true;
                // Apply parent viewport and sibling stacking changes only
                // after acknowledging the configure that supplied them.
                if (self.base_attached) self.presentation_dirty = true;
            },
        }
    }

    fn fractionalScaleListener(_: *wp.FractionalScaleV1, event: wp.FractionalScaleV1.Event, self: *Window) void {
        switch (event) {
            .preferred_scale => |preferred| {
                if (preferred.scale == 0 or preferred.scale == self.scale_120) return;
                self.scale_120 = preferred.scale;
                self.updateViewport();
                self.presentation_dirty = true;
                // A scale transition does not necessarily cause an xdg-shell
                // size configure. Notify EGL so it rebuilds at the new
                // physical pixel size.
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
                if (self.fullscreen.configure(fullscreen)) {
                    self.push(.{ .fullscreen_changed = fullscreen });
                }
                if (c.width > 0 and c.height > 0) {
                    if (c.width != self.width or c.height != self.height) {
                        self.width = c.width;
                        self.height = c.height;
                        self.updateViewport();
                        self.updateWindowGeometry();
                        self.updateOpaqueRegion();
                        self.push(.{ .configure = .{ .width = c.width, .height = c.height } });
                    }
                }
            },
            .close => self.push(.close),
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
                const shape = if (self.cursor_shape != 0) self.cursor_shape else 1; // default
                self.cursor_shape = 0;
                self.setCursorShape(shape);
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
            self.push(.{ .pointer_axis = .{ .horizontal = horizontal, .vertical = vertical } });
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
                self.push(.{ .key = .{
                    .keycode = k.key,
                    .pressed = k.state == .pressed,
                    .unicode = unicode,
                    .keysym = keysym,
                } });
                if (k.state == .pressed) {
                    const repeats = if (self.session.xkb_keymap) |keymap|
                        xkb.xkb_keymap_key_repeats(keymap, k.key + 8) != 0
                    else
                        false;
                    if (repeats and self.session.keyboard_repeat_rate > 0) {
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
        self.push(.{ .pointer_capture_changed = actual });
    }

    fn relativePointerListener(_: *zwp.RelativePointerV1, event: zwp.RelativePointerV1.Event, self: *Window) void {
        switch (event) {
            .relative_motion => |motion| if (self.pointer_capture_actual) {
                self.push(.{ .pointer_relative = .{
                    .dx = motion.dx.toDouble(),
                    .dy = motion.dy.toDouble(),
                } });
            },
        }
    }

    /// Host adaptation of WaydroidWindow.setPointerCapture(): Android's InputManager/WMS
    /// handshake is absent, so the hosted ViewRoot request is realized directly with the
    /// compositor protocols and acknowledged only by the locked/unlocked events.
    pub fn setPointerCapture(self: *Window, enabled: bool) void {
        if (enabled == self.pointer_capture_requested) return;
        self.pointer_capture_requested = enabled;
        if (!enabled) {
            if (self.locked_pointer) |locked| locked.destroy();
            if (self.relative_pointer) |relative| relative.destroy();
            self.locked_pointer = null;
            self.relative_pointer = null;
            if (self.pointer_capture_actual) {
                self.pointer_capture_actual = false;
                self.push(.{ .pointer_capture_changed = false });
            }
            return;
        }

        const pointer = self.session.pointer orelse
            std.debug.panic("Android requested pointer capture, but the active wl_seat has no pointer capability", .{});
        const relative_manager = self.session.relative_pointer_manager orelse
            std.debug.panic("Android requested pointer capture, but zwp_relative_pointer_manager_v1 is unavailable", .{});
        const constraints = self.session.pointer_constraints orelse
            std.debug.panic("Android requested pointer capture, but zwp_pointer_constraints_v1 is unavailable", .{});
        self.relative_pointer = relative_manager.getRelativePointer(pointer) catch
            std.debug.panic("failed to create zwp_relative_pointer_v1 for Android pointer capture", .{});
        self.relative_pointer.?.setListener(*Window, relativePointerListener, self);
        self.locked_pointer = constraints.lockPointer(
            self.ui_surface,
            pointer,
            null,
            .persistent,
        ) catch std.debug.panic("failed to create zwp_locked_pointer_v1 for Android pointer capture", .{});
        self.locked_pointer.?.setListener(*Window, lockedPointerListener, self);
        self.ui_surface.commit();
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
            if (self.ui_initialized) self.ui_viewport.setDestination(self.width, self.height);
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
        _ = try self.ensureVirtualPointer();
        _ = try self.ensureVirtualKeyboard();
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        if (self.session.display.roundtrip() != .SUCCESS) return error.RoundtripFailed;
        if (self.session.pointer == null or self.session.keyboard == null) return error.SeatCapabilitiesUnavailable;
    }

    /// Inject a real compositor-routed pointer click at an Android buffer
    /// coordinate. Cage sends the resulting wl_pointer events back through the
    /// normal input path; this does not call the Java host directly.
    pub fn testTap(self: *Window, buffer_x: i32, buffer_y: i32) !void {
        const pointer = try self.ensureVirtualPointer();
        const x: u32 = @intCast(std.math.clamp(self.bufferToSurfaceX(buffer_x), 0, @max(0, self.width - 1)));
        const y: u32 = @intCast(std.math.clamp(self.bufferToSurfaceY(buffer_y), 0, @max(0, self.height - 1)));
        const width: u32 = @intCast(@max(1, self.width));
        const height: u32 = @intCast(@max(1, self.height));
        const time = inputTimestamp(self.io);
        pointer.motionAbsolute(time, x, y, width, height);
        pointer.frame();
        pointer.button(time, 0x110, .pressed); // BTN_LEFT
        pointer.frame();
        pointer.button(time +% 1, 0x110, .released);
        pointer.frame();
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    /// Inject a compositor-routed primary-button drag between Android buffer
    /// coordinates. Each motion gets its own pointer frame so the client sees
    /// the same ordered gesture stream as it would from a physical mouse.
    pub fn testDrag(self: *Window, from_buffer_x: i32, from_buffer_y: i32, to_buffer_x: i32, to_buffer_y: i32) !void {
        const pointer = try self.ensureVirtualPointer();
        const from_x: u32 = @intCast(std.math.clamp(self.bufferToSurfaceX(from_buffer_x), 0, @max(0, self.width - 1)));
        const from_y: u32 = @intCast(std.math.clamp(self.bufferToSurfaceY(from_buffer_y), 0, @max(0, self.height - 1)));
        const to_x: u32 = @intCast(std.math.clamp(self.bufferToSurfaceX(to_buffer_x), 0, @max(0, self.width - 1)));
        const to_y: u32 = @intCast(std.math.clamp(self.bufferToSurfaceY(to_buffer_y), 0, @max(0, self.height - 1)));
        const width: u32 = @intCast(@max(1, self.width));
        const height: u32 = @intCast(@max(1, self.height));
        const time = inputTimestamp(self.io);
        pointer.motionAbsolute(time, from_x, from_y, width, height);
        pointer.frame();
        pointer.button(time, 0x110, .pressed); // BTN_LEFT
        pointer.frame();
        for (1..11) |step| {
            const x = interpolateCoordinate(from_x, to_x, step, 10);
            const y = interpolateCoordinate(from_y, to_y, step, 10);
            pointer.motionAbsolute(time +% @as(u32, @intCast(step)), x, y, width, height);
            pointer.frame();
        }
        pointer.button(time +% 11, 0x110, .released);
        pointer.frame();
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    /// Type UTF-8 text through Cage's virtual keyboard. The fixed US keymap
    /// intentionally accepts printable ASCII plus newline/tab/backspace;
    /// semantic set_text remains available for arbitrary Unicode input.
    pub fn testType(self: *Window, text: []const u8) !void {
        const keyboard = try self.ensureVirtualKeyboard();
        for (text) |byte| {
            const key = asciiKey(byte) orelse return error.UnsupportedText;
            self.sendVirtualKey(keyboard, key.code, key.shift);
        }
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    pub fn testKey(self: *Window, name: []const u8) !void {
        const code = namedKey(name) orelse return error.UnsupportedKey;
        const keyboard = try self.ensureVirtualKeyboard();
        self.sendVirtualKey(keyboard, code, false);
        if (self.session.display.flush() != .SUCCESS) return error.FlushFailed;
    }

    fn ensureVirtualPointer(self: *Window) !*zwlr.VirtualPointerV1 {
        if (self.session.virtual_pointer) |pointer| return pointer;
        const manager = self.session.virtual_pointer_manager orelse return error.VirtualPointerUnavailable;
        self.session.virtual_pointer = if (self.session.output) |output|
            try manager.createVirtualPointerWithOutput(self.session.seat, output)
        else
            try manager.createVirtualPointer(self.session.seat);
        return self.session.virtual_pointer.?;
    }

    fn ensureVirtualKeyboard(self: *Window) !*zwp.VirtualKeyboardV1 {
        if (self.session.virtual_keyboard) |keyboard| return keyboard;
        const manager = self.session.virtual_keyboard_manager orelse return error.VirtualKeyboardUnavailable;
        const seat = self.session.seat orelse return error.VirtualKeyboardUnavailable;
        const keyboard = try manager.createVirtualKeyboard(seat);
        errdefer keyboard.destroy();
        const keymap_fd = try createMemoryFile("azk-test-keymap");
        const keymap_file = std.Io.File{
            .handle = keymap_fd,
            .flags = .{ .nonblocking = false },
        };
        defer keymap_file.close(self.io);
        keymap_file.writeStreamingAll(self.io, test_keymap) catch return error.KeymapWriteFailed;
        keyboard.keymap(.xkb_v1, keymap_fd, @intCast(test_keymap.len));
        self.session.virtual_keyboard = keyboard;
        return keyboard;
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
            _ = self.emitKeyRepeatIfDue();
            if (self.events.items.len != 0) break;

            while (!self.session.display.prepareRead()) {
                if (self.session.display.dispatchPending() != .SUCCESS) return error.DispatchFailed;
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
        if (self.frame_cb) |callback| callback.destroy();
        self.frame_cb = null;
        self.setPointerCapture(false);
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
