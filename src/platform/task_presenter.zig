//! Session-owned Wayland presentation. AZKB workers submit bounded requests;
//! only the main thread touches protocol objects or native consumer ownership.
const std = @import("std");
const Io = std.Io;
const Queue = @import("surface_queue");
const Binder = opaque {};
const Class = opaque {};
const Parcel = opaque {};
const Death = opaque {};
extern "c" fn AIBinder_Class_define([*:0]const u8, ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque, ?*const fn (?*anyopaque) callconv(.c) void, ?*const fn (*Binder, u32, *const Parcel, *Parcel) callconv(.c) i32) *Class;
extern "c" fn AIBinder_new(*const Class, ?*anyopaque) *Binder;
extern "c" fn AIBinder_associateClass(*Binder, *const Class) bool;
extern "c" fn AIBinder_incStrong(*Binder) void;
extern "c" fn AIBinder_decStrong(*Binder) void;
extern "c" fn AIBinder_getCallingUid() i32;
extern "c" fn AIBinder_getCallingPid() i32;
extern "c" fn AIBinder_prepareTransaction(*Binder, *?*Parcel) i32;
extern "c" fn AIBinder_transact(*Binder, u32, *?*Parcel, *?*Parcel, u32) i32;
extern "c" fn AIBinder_DeathRecipient_new(*const fn (?*anyopaque) callconv(.c) void) *Death;
extern "c" fn AIBinder_DeathRecipient_delete(*Death) void;
extern "c" fn AIBinder_linkToDeath(*Binder, *Death, ?*anyopaque) i32;
extern "c" fn AIBinder_unlinkToDeath(*Binder, *Death, ?*anyopaque) i32;
extern "c" fn AServiceManager_addService(*Binder, [*:0]const u8) i32;
extern "c" fn AParcel_readInt32(*const Parcel, *i32) i32;
extern "c" fn AParcel_readInt64(*const Parcel, *i64) i32;
extern "c" fn AParcel_getDataPosition(*const Parcel) i32;
extern "c" fn AParcel_getDataSize(*const Parcel) i32;
extern "c" fn AParcel_readString(*const Parcel, ?*anyopaque, *const fn (?*anyopaque, i32, *?[*]u8) callconv(.c) bool) i32;
extern "c" fn AParcel_readStrongBinder(*const Parcel, *?*Binder) i32;
extern "c" fn AParcel_writeInt32(*Parcel, i32) i32;
extern "c" fn AParcel_writeInt64(*Parcel, i64) i32;
extern "c" fn AParcel_writeFloat(*Parcel, f32) i32;
extern "c" fn AParcel_writeInt32Array(*Parcel, [*]const i32, i32) i32;
extern "c" fn AParcel_delete(*Parcel) void;
extern "c" fn azk_graphics_live_buffers() u64;

const max_tasks = 64;
const Key = struct { id: i32, generation: i64 };
const Entry = struct {
    key: Key,
    window: *Queue.Window,
    queue: *Queue,
    events: *Binder,
    bounds: [6]i32 = @splat(0),
    fn deinit(self: *Entry) void {
        self.window.deinit();
        self.queue.deinit();
        AIBinder_decStrong(self.events);
    }
};
const Request = struct {
    code: u32,
    pid: i32,
    input: *const Parcel,
    output: *Parcel,
    status: i32 = -32,
    done: Io.Event = .unset,
};
const Server = struct {
    gpa: std.mem.Allocator,
    io: Io,
    session: *Queue.Session,
    wake: Io.File,
    mutex: Io.Mutex = .init,
    requests: [16]*Request = undefined,
    request_count: usize = 0,
    entries: [max_tasks]?Entry = @splat(null),
    owner: ?*Binder = null,
    owner_pid: i32 = 0,
    death: *Death,
    events_class: *Class,
    fn signal(self: *Server) void {
        const one: u64 = 1;
        self.wake.writeStreamingAll(self.io, std.mem.asBytes(&one)) catch {
            disconnected.store(true, .release);
        };
    }
    fn find(self: *Server, key: Key) ?*Entry {
        for (&self.entries) |*slot| if (slot.*) |*entry| {
            if (std.meta.eql(entry.key, key)) return entry;
        };
        return null;
    }
    fn drain(self: *Server, execute: bool) void {
        var pending: [16]*Request = undefined;
        self.mutex.lockUncancelable(self.io);
        const count = self.request_count;
        @memcpy(pending[0..count], self.requests[0..count]);
        self.request_count = 0;
        self.mutex.unlock(self.io);
        for (pending[0..count]) |request| {
            request.status = if (execute) self.handle(request) catch |err| status: {
                std.log.err("task presenter request {d}: {s}", .{ request.code, @errorName(err) });
                break :status -22;
            } else -32;
            request.done.set(self.io);
        }
    }
    fn handle(self: *Server, request: *Request) !i32 {
        if (self.owner_pid != 0 and self.owner_pid != request.pid) return -1;
        const input = request.input;
        const output = request.output;
        const key: Key = .{ .id = try readInt(input), .generation = try readLong(input) };
        if (key.id < 0 or key.generation <= 0) return error.InvalidTask;
        switch (request.code) {
            1 => {
                const width = try readInt(input);
                const height = try readInt(input);
                if (!dimension(width) or !dimension(height)) return error.InvalidSize;
                var app_id: Text = .{};
                var title: Text = .{};
                try app_id.read(input);
                try title.read(input);
                var events: ?*Binder = null;
                try ok(AParcel_readStrongBinder(input, &events));
                const callback = events orelse return error.MissingCallback;
                errdefer AIBinder_decStrong(callback);
                if (!AIBinder_associateClass(callback, self.events_class)) return error.InvalidCallback;
                if (try readInt(input) != 2) return error.InvalidConfigurationArray;
                try end(input);
                var free: ?*?Entry = null;
                for (&self.entries) |*slot| {
                    if (slot.*) |entry| {
                        if (entry.key.id == key.id) return error.DuplicateTask;
                    } else if (free == null) {
                        free = slot;
                    }
                }
                const slot = free orelse return error.TaskCapacity;
                const window = try self.session.createWindowWithAppId(width, height, title.slice(), app_id.slice(), false);
                errdefer window.deinit();
                const queue = try Queue.init(self.gpa, self.io, @intCast(window.width), @intCast(window.height));
                errdefer queue.deinit();
                // Hold the first callback independently of its window. Its
                // process death terminates this owner generation and all queues.
                if (self.owner == null) {
                    try ok(AIBinder_linkToDeath(callback, self.death, null));
                    AIBinder_incStrong(callback);
                    self.owner = callback;
                    self.owner_pid = request.pid;
                }
                try ok(AParcel_writeInt32(output, 0)); // Status::ok
                try ok(AParcel_writeInt32(output, 1)); // non-null Surface
                try ok(queue.writeSurface(output));
                try ok(AParcel_writeInt32Array(output, &.{ window.width, window.height }, 2));
                slot.* = .{ .key = key, .window = window, .queue = queue, .events = callback };
            },
            2 => {
                try end(input);
                for (&self.entries) |*slot| if (slot.*) |*entry| {
                    if (std.meta.eql(entry.key, key)) {
                        entry.deinit();
                        slot.* = null;
                        break;
                    }
                };
                try ok(AParcel_writeInt32(output, 0));
            },
            3 => {
                var app_id: Text = .{};
                var title: Text = .{};
                try app_id.read(input);
                try title.read(input);
                try end(input);
                const entry = self.find(key) orelse return error.StaleTask;
                entry.window.toplevel.setAppId(app_id.slice());
                entry.window.toplevel.setTitle(title.slice());
                try ok(AParcel_writeInt32(output, 0));
            },
            4 => {
                var bounds: [6]i32 = undefined;
                for (&bounds) |*value| value.* = try readInt(input);
                try end(input);
                if (bounds[0] < 0 or bounds[1] < 0 or !dimension(bounds[2]) or !dimension(bounds[3]) or
                    !dimension(bounds[4]) or !dimension(bounds[5]) or
                    @as(i64, bounds[0]) + bounds[2] > 16384 or @as(i64, bounds[1]) + bounds[3] > 16384)
                    return error.InvalidBounds;
                const entry = self.find(key) orelse return error.StaleTask;
                entry.bounds = bounds;
                try ok(AParcel_writeInt32(output, 0));
            },
            else => return -38,
        }
        return 0;
    }
};
// Keep callback state at a stable address until process exit. Once accepting is
// false, new Binder calls touch only atomics; shutdown drains admitted requests
// before closing any std.Io resources.
var server: Server = undefined;
var accepting: std.atomic.Value(bool) = .init(false);
var callbacks: std.atomic.Value(u32) = .init(0);
var disconnected: std.atomic.Value(bool) = .init(false);
fn onDeath(_: ?*anyopaque) callconv(.c) void {
    _ = callbacks.fetchAdd(1, .acq_rel);
    defer _ = callbacks.fetchSub(1, .acq_rel);
    if (!accepting.load(.acquire)) return;
    disconnected.store(true, .release);
    server.signal();
}
fn transact(_: *Binder, code: u32, input: *const Parcel, output: *Parcel) callconv(.c) i32 {
    _ = callbacks.fetchAdd(1, .acq_rel);
    defer _ = callbacks.fetchSub(1, .acq_rel);
    if (!accepting.load(.acquire)) return -32;
    if (AIBinder_getCallingUid() != 1000) return -1;
    if (code < 1 or code > 4) return -38;
    const io = server.io;
    var request: Request = .{ .code = code, .pid = AIBinder_getCallingPid(), .input = input, .output = output };
    server.mutex.lockUncancelable(io);
    if (!accepting.load(.acquire) or server.request_count == server.requests.len) {
        server.mutex.unlock(io);
        return -11;
    }
    server.requests[server.request_count] = &request;
    server.request_count += 1;
    server.signal();
    server.mutex.unlock(io);
    request.done.waitUncancelable(io);
    return request.status;
}
fn ok(status: i32) !void {
    if (status != 0) return error.BinderOperationFailed;
}
fn readInt(parcel: *const Parcel) !i32 {
    var value: i32 = undefined;
    try ok(AParcel_readInt32(parcel, &value));
    return value;
}
fn readLong(parcel: *const Parcel) !i64 {
    var value: i64 = undefined;
    try ok(AParcel_readInt64(parcel, &value));
    return value;
}
fn dimension(value: i32) bool {
    return value > 0 and value <= 16384;
}
fn end(parcel: *const Parcel) !void {
    if (AParcel_getDataPosition(parcel) != AParcel_getDataSize(parcel)) return error.TrailingData;
}
const Text = struct {
    bytes: [1025]u8 = undefined,
    len: usize = 0,
    fn allocate(raw: ?*anyopaque, length: i32, out: *?[*]u8) callconv(.c) bool {
        const self: *Text = @ptrCast(@alignCast(raw.?));
        if (length < 1 or length > self.bytes.len) return false;
        self.len = @intCast(length - 1);
        out.* = &self.bytes;
        return true;
    }
    fn read(self: *Text, parcel: *const Parcel) !void {
        try ok(AParcel_readString(parcel, self, allocate));
        if (self.bytes[self.len] != 0 or std.mem.indexOfScalar(u8, self.bytes[0..self.len], 0) != null)
            return error.InvalidText;
    }
    fn slice(self: *const Text) [:0]const u8 {
        return self.bytes[0..self.len :0];
    }
};
fn sendLayout(entry: *Entry, layout: Queue.KeyboardLayout) !void {
    const characters = try layout.characters();
    var input: ?*Parcel = null;
    try ok(AIBinder_prepareTransaction(entry.events, &input));
    defer if (input) |parcel| AParcel_delete(parcel);
    try ok(AParcel_writeInt32(input.?, entry.key.id));
    try ok(AParcel_writeInt64(input.?, entry.key.generation));
    try ok(AParcel_writeInt32(input.?, layout.generation));
    try ok(AParcel_writeInt32Array(input.?, &characters, characters.len));
    var output: ?*Parcel = null;
    defer if (output) |parcel| AParcel_delete(parcel);
    try ok(AIBinder_transact(entry.events, 10, &input, &output, 1));
}

fn send(entry: *Entry, code: u32, values: anytype) !void {
    var input: ?*Parcel = null;
    var output: ?*Parcel = null;
    try ok(AIBinder_prepareTransaction(entry.events, &input));
    defer if (input) |parcel| AParcel_delete(parcel);
    try ok(AParcel_writeInt32(input.?, entry.key.id));
    try ok(AParcel_writeInt64(input.?, entry.key.generation));
    defer if (output) |parcel| AParcel_delete(parcel);
    inline for (values) |value| switch (@typeInfo(@TypeOf(value))) {
        .bool => try ok(AParcel_writeInt32(input.?, @intFromBool(value))),
        .int, .comptime_int => try ok(AParcel_writeInt32(input.?, @intCast(value))),
        .float, .comptime_float => try ok(AParcel_writeFloat(input.?, @floatCast(value))),
        else => @compileError("Unsupported task callback field"),
    };
    try ok(AIBinder_transact(entry.events, code, &input, &output, 1));
}
pub fn main(init: std.process.Init) !void {
    try run(init);
    if (azk_graphics_live_buffers() != 0) return error.LeakedGraphicsBuffers;
    // The supervisor may have stopped draining stdout before orderly shutdown.
    // Resource verification belongs on stderr with the other native diagnostics.
    try Io.File.stderr().writeStreamingAll(init.io, "task presenter released all buffers\n");
}

fn run(init: std.process.Init) !void {
    const io = init.io;
    const raw = std.os.linux.eventfd(0, std.os.linux.EFD.CLOEXEC | std.os.linux.EFD.NONBLOCK);
    if (std.os.linux.errno(raw) != .SUCCESS) return error.EventfdFailed;
    const wake: Io.File = .{ .handle = @intCast(raw), .flags = .{ .nonblocking = true } };
    defer wake.close(io);
    const session = try Queue.Session.init(init.gpa, io);
    session.ordered_events = .{};
    session.generate_key_repeats = false; // Stock InputDispatcher owns the timer.
    defer session.deinit();
    const death = AIBinder_DeathRecipient_new(onDeath);
    defer AIBinder_DeathRecipient_delete(death);
    server = .{ .gpa = init.gpa, .io = io, .session = session, .wake = wake, .death = death, .events_class = AIBinder_Class_define("dev.artzig.window.IWaylandTaskEvents", null, null, null) };
    const class = AIBinder_Class_define("dev.artzig.window.IWaylandTaskPresenter", null, null, transact);
    const binder = AIBinder_new(class, null);
    defer AIBinder_decStrong(binder);
    accepting.store(true, .release);
    defer {
        accepting.store(false, .release);
        server.drain(false);
        while (callbacks.load(.acquire) != 0) Io.sleep(io, .fromMilliseconds(1), .awake) catch {};
        if (server.owner) |owner| {
            _ = AIBinder_unlinkToDeath(owner, death, null);
            AIBinder_decStrong(owner);
        }
        for (&server.entries) |*slot| if (slot.*) |*entry| {
            entry.deinit();
            slot.* = null;
        };
    }
    try ok(AServiceManager_addService(binder, "dev.artzig.window.presenter"));
    var stdin_group: Io.Group = .init;
    try stdin_group.concurrent(io, watchStdin, .{io});
    defer stdin_group.cancel(io);
    try Io.File.stdout().writeStreamingAll(io, "ready\n");
    while (!disconnected.load(.acquire)) {
        var files: [max_tasks + 1]Io.File = undefined;
        var ready: [max_tasks + 1]bool = undefined;
        var entries: [max_tasks]*Entry = undefined;
        files[0] = wake;
        var count: usize = 1;
        for (&server.entries) |*slot| if (slot.*) |*entry| {
            files[count] = entry.queue.notify_file;
            entries[count - 1] = entry;
            count += 1;
        };
        try session.dispatch(files[0..count], ready[0..count], -1);
        // Consume existing windows before requests can destroy/reuse a slot.
        for (entries[0 .. count - 1], 1..) |entry, index| {
            if (ready[index]) _ = try entry.queue.presentNotified(entry.window);
        }
        for (session.ordered_events.?.take()) |item| {
            const entry = for (entries[0 .. count - 1]) |candidate| {
                if (candidate.window == item.window) break candidate;
            } else continue;
            switch (item.event) {
                .configure => |size| try send(entry, 1, .{ size.width, size.height }),
                .close => try send(entry, 2, .{}),
                .keyboard_modifiers => |meta| try send(entry, 11, .{meta}),
                .keyboard_layout => |layout| try sendLayout(entry, layout),
                .keyboard_focus => |focused| try send(entry, 3, .{focused}),
                .keyboard_reset => try send(entry, 3, .{false}),
                .pointer_motion => |p| try send(entry, 4, .{ 0, 0, false, p.x, p.y }),
                .pointer_enter => |p| try send(entry, 4, .{ 1, 0, false, p.x, p.y }),
                .pointer_leave => |p| try send(entry, 4, .{ 2, 0, false, p.x, p.y }),
                .pointer_button => |p| try send(entry, 4, .{ 3, p.button, p.pressed, p.x, p.y }),
                .pointer_cancel => try send(entry, 4, .{ 4, 0, false, 0.0, 0.0 }),
                .pointer_axis => |axis| try send(entry, 5, .{ entry.window.pointer_x, entry.window.pointer_y, axis.horizontal, axis.vertical }),
                .key => |key| try send(entry, 6, .{ key.keycode, key.pressed, key.repeatable }),
                .touch => |touch| try send(entry, 7, .{ @backingInt(touch.action), touch.id, touch.x, touch.y, touch.major, touch.minor, touch.orientation }),
                .touch_cancel => try send(entry, 8, .{}),
                .keyboard_repeat_configuration => |configuration| try send(entry, 9, .{ configuration.rate, configuration.delay_ms }),
                else => {},
            }
        }
        if (ready[0]) server.drain(!disconnected.load(.acquire));
    }
}

fn watchStdin(io: Io) void {
    var bytes: [16]u8 = undefined;
    while (true) {
        const count = Io.File.stdin().readStreaming(io, &.{&bytes}) catch break;
        if (count == 0) break;
    }
    disconnected.store(true, .release);
    server.signal();
}
