//! Private Varlink test-control module.
//!
//! The module is deliberately polled from the existing render loop. That keeps
//! every Android tree read and action on the ART main thread while the socket
//! remains non-blocking. A bounded std.Io accept task only hands owned sockets
//! to this thread; it never enters ART.
const std = @import("std");
const art = @import("art");
const Io = std.Io;
const Acceptor = @import("test_control_acceptor.zig");

const interface_name = "dev.artzig.androlinzberg.test1";
const interface_description = @embedFile("test_control.varlink");
const max_message_size = 1024 * 1024;
const max_response_size = 16 * 1024 * 1024;
const transfer_timeout_ms = 5000;

pub const Android = struct {
    env: [*c]art.jni.JNIEnv,
    class: art.jni.jclass,
    snapshot_method: art.jni.jmethodID,
    notifications_method: art.jni.jmethodID,
    diagnostics_method: art.jni.jmethodID,
    runtime_diagnostics_method: art.jni.jmethodID,
    log_sequence_method: art.jni.jmethodID,
    issue_sequence_method: art.jni.jmethodID,
    revision_method: art.jni.jmethodID,
    idle_method: art.jni.jmethodID,
    perform_method: art.jni.jmethodID,

    pub fn init(env: [*c]art.jni.JNIEnv, class: art.jni.jclass) !Android {
        const e = art.Env{ .env = env };
        return .{
            .env = env,
            .class = class,
            .snapshot_method = try e.getStaticMethodID(class, "accessibilitySnapshot", "()Ljava/lang/String;"),
            .notifications_method = try e.getStaticMethodID(class, "notificationsSnapshot", "()Ljava/lang/String;"),
            .diagnostics_method = try e.getStaticMethodID(class, "accessibilityDiagnostics", "(J)Ljava/lang/String;"),
            .runtime_diagnostics_method = try e.getStaticMethodID(class, "testDiagnostics", "(JJ)Ljava/lang/String;"),
            .log_sequence_method = try e.getStaticMethodID(class, "diagnosticLogSequence", "()J"),
            .issue_sequence_method = try e.getStaticMethodID(class, "diagnosticIssueSequence", "()J"),
            .revision_method = try e.getStaticMethodID(class, "accessibilityRevision", "()J"),
            .idle_method = try e.getStaticMethodID(class, "accessibilityIdle", "()Z"),
            .perform_method = try e.getStaticMethodID(class, "accessibilityPerform", "(JJIIILjava/lang/String;)Z"),
        };
    }

    pub fn snapshot(self: *const Android, allocator: std.mem.Allocator) ![]u8 {
        return self.callString0(allocator, self.snapshot_method);
    }

    /// The package's posted notifications as the hosted notification service holds them.
    pub fn notifications(self: *const Android, allocator: std.mem.Allocator) ![]u8 {
        return self.callString0(allocator, self.notifications_method);
    }

    pub fn diagnostics(self: *const Android, allocator: std.mem.Allocator, after_revision: u64) ![]u8 {
        const object = self.env.*.*.CallStaticObjectMethod.?(
            self.env,
            self.class,
            self.diagnostics_method,
            @as(art.jni.jlong, @intCast(after_revision)),
        );
        return self.copyJavaString(allocator, object);
    }

    pub fn runtimeDiagnostics(
        self: *const Android,
        allocator: std.mem.Allocator,
        after_log_sequence: u64,
        after_issue_sequence: u64,
    ) ![]u8 {
        const object = self.env.*.*.CallStaticObjectMethod.?(
            self.env,
            self.class,
            self.runtime_diagnostics_method,
            @as(art.jni.jlong, @intCast(after_log_sequence)),
            @as(art.jni.jlong, @intCast(after_issue_sequence)),
        );
        return self.copyJavaString(allocator, object);
    }

    fn callString0(self: *const Android, allocator: std.mem.Allocator, method: art.jni.jmethodID) ![]u8 {
        const object = self.env.*.*.CallStaticObjectMethod.?(self.env, self.class, method);
        return self.copyJavaString(allocator, object);
    }

    fn copyJavaString(self: *const Android, allocator: std.mem.Allocator, maybe_object: art.jni.jobject) ![]u8 {
        const object = maybe_object orelse
            return error.JavaReturnedNull;
        defer self.env.*.*.DeleteLocalRef.?(self.env, object);
        try (art.Env{ .env = self.env }).check();
        const string: art.jni.jstring = @ptrCast(object);
        const chars = self.env.*.*.GetStringUTFChars.?(self.env, string, null) orelse
            return error.OutOfMemory;
        defer self.env.*.*.ReleaseStringUTFChars.?(self.env, string, chars);
        return allocator.dupe(u8, std.mem.span(chars));
    }

    pub fn logSequence(self: *const Android) !u64 {
        return self.callSequence(self.log_sequence_method);
    }

    pub fn issueSequence(self: *const Android) !u64 {
        return self.callSequence(self.issue_sequence_method);
    }

    fn callSequence(self: *const Android, method: art.jni.jmethodID) !u64 {
        const value = self.env.*.*.CallStaticLongMethod.?(self.env, self.class, method);
        try (art.Env{ .env = self.env }).check();
        return @intCast(value);
    }

    pub fn revision(self: *const Android) !u64 {
        const value = self.env.*.*.CallStaticLongMethod.?(self.env, self.class, self.revision_method);
        try (art.Env{ .env = self.env }).check();
        return @intCast(value);
    }

    pub fn idle(self: *const Android) !bool {
        const value = self.env.*.*.CallStaticBooleanMethod.?(self.env, self.class, self.idle_method);
        try (art.Env{ .env = self.env }).check();
        return value != 0;
    }

    pub fn perform(
        self: *const Android,
        allocator: std.mem.Allocator,
        expected_revision: u64,
        window_id: i64,
        view_id: i32,
        virtual_id: i32,
        action: i32,
        text: ?[]const u8,
    ) !bool {
        var jtext: art.jni.jstring = null;
        if (text) |value| {
            const value_z = try allocator.dupeSentinel(u8, value, 0);
            defer allocator.free(value_z);
            jtext = try (art.Env{ .env = self.env }).newString(value_z.ptr);
        }
        defer if (jtext != null) self.env.*.*.DeleteLocalRef.?(self.env, jtext);
        const result = self.env.*.*.CallStaticBooleanMethod.?(
            self.env,
            self.class,
            self.perform_method,
            @as(art.jni.jlong, @intCast(expected_revision)),
            @as(art.jni.jlong, @intCast(window_id)),
            @as(art.jni.jint, view_id),
            @as(art.jni.jint, virtual_id),
            @as(art.jni.jint, action),
            jtext,
        );
        try (art.Env{ .env = self.env }).check();
        return result != 0;
    }
};

/// Narrow adapter implemented by the Wayland window module. Keeping protocol
/// and compositor details out of this module makes the Varlink service usable
/// in unit tests without constructing a display connection.
pub const Input = struct {
    context: *anyopaque,
    tap_fn: *const fn (*anyopaque, i32, i32) anyerror!void,
    drag_fn: *const fn (*anyopaque, i32, i32, i32, i32) anyerror!void,
    type_fn: *const fn (*anyopaque, []const u8) anyerror!void,
    key_fn: *const fn (*anyopaque, []const u8) anyerror!void,
    fullscreen_fn: *const fn (*anyopaque) bool,
    type_at_fn: ?*const fn (*anyopaque, i32, i32, []const u8) anyerror!void = null,
    key_at_fn: ?*const fn (*anyopaque, i32, i32, []const u8) anyerror!void = null,
    select_fn: ?*const fn (*anyopaque, u64, i64, i32, i32) anyerror!void = null,

    pub fn select(self: Input, revision: u64, window_id: i64, view_id: i32, virtual_id: i32) !void {
        if (self.select_fn) |callback| try callback(self.context, revision, window_id, view_id, virtual_id);
    }

    pub fn tap(self: Input, x: i32, y: i32) !void {
        try self.tap_fn(self.context, x, y);
    }

    pub fn drag(self: Input, from_x: i32, from_y: i32, to_x: i32, to_y: i32) !void {
        try self.drag_fn(self.context, from_x, from_y, to_x, to_y);
    }

    pub fn typeText(self: Input, value: []const u8) !void {
        try self.type_fn(self.context, value);
    }

    pub fn key(self: Input, value: []const u8) !void {
        try self.key_fn(self.context, value);
    }

    pub fn typeAt(self: Input, x: i32, y: i32, value: []const u8) !void {
        if (self.type_at_fn) |callback| return callback(self.context, x, y, value);
        try self.tap(x, y);
        try self.typeText(value);
    }

    pub fn keyAt(self: Input, x: i32, y: i32, value: []const u8) !void {
        if (self.key_at_fn) |callback| return callback(self.context, x, y, value);
        try self.tap(x, y);
        try self.key(value);
    }

    pub fn fullscreen(self: Input) bool {
        return self.fullscreen_fn(self.context);
    }
};

const WaitKind = enum {
    now,
    ready,
    idle,
    exists,
    absent,
    tree_changed,
    frame_presented,
};

const PendingMethod = enum { observe, perform };

const Pending = struct {
    message: []u8,
    method: PendingMethod,
    wait_kind: WaitKind,
    deadline_ms: i64,
    stable_required: u32,
    stable_count: u32 = 0,
    last_revision: u64 = 0,
    last_presented: u64 = 0,
    applied: bool = false,
    oneway: bool = false,
};

pub const Server = struct {
    gpa: std.mem.Allocator,
    io: Io,
    socket_path: []u8,
    listener: *Acceptor,
    client: ?Io.File = null,
    input: std.ArrayList(u8) = .empty,
    output: std.ArrayList(u8) = .empty,
    input_deadline: ?i64 = null,
    output_deadline: ?i64 = null,
    pending: ?Pending = null,
    epoch_buf: [64]u8 = undefined,
    epoch_len: usize = 0,
    command_seq: u64 = 0,
    input_seq: u64 = 0,
    frame_seq: u64 = 0,
    presented_frame_seq: u64 = 0,
    ui_input: ?Input = null,

    pub fn init(gpa: std.mem.Allocator, io: Io, address: []const u8) !Server {
        const path = if (std.mem.startsWith(u8, address, "unix:")) address[5..] else address;
        if (path.len == 0 or path.len >= 108) return error.InvalidSocketPath;
        const owned_path = try gpa.dupe(u8, path);
        errdefer gpa.free(owned_path);
        const listener = try Acceptor.init(gpa, io, path);
        var result = Server{ .gpa = gpa, .io = io, .socket_path = owned_path, .listener = listener };
        // A process/restart epoch is opaque; random bytes avoid PID reuse and
        // remove any dependency on raw process or clock syscalls.
        var nonce: [16]u8 = undefined;
        io.random(&nonce);
        const hex = std.fmt.bytesToHex(nonce, .lower);
        @memcpy(result.epoch_buf[0..hex.len], &hex);
        result.epoch_len = hex.len;
        return result;
    }

    pub fn deinit(self: *Server) void {
        if (self.pending) |pending| self.gpa.free(pending.message);
        if (self.client) |file| file.close(self.io);
        self.listener.deinit();
        Io.Dir.cwd().deleteFile(self.io, self.socket_path) catch {};
        self.input.deinit(self.gpa);
        self.output.deinit(self.gpa);
        self.gpa.free(self.socket_path);
    }

    pub fn noteInput(self: *Server) void {
        self.input_seq += 1;
    }

    pub fn setInput(self: *Server, input: Input) void {
        self.ui_input = input;
    }

    /// File descriptors which must participate in the host event loop. Test control used to
    /// make progress accidentally because the UI rendered continuously; an on-demand renderer
    /// must instead wake explicitly when a client connects or sends another request.
    pub fn listenerFd(self: *const Server) std.posix.fd_t {
        return self.listener.wake.fd();
    }

    pub fn clientFd(self: *const Server) ?std.posix.fd_t {
        return if (self.client) |file| file.handle else null;
    }

    pub fn clientWantsWrite(self: *const Server) bool {
        return self.client != null and self.output.items.len != 0;
    }

    /// Pending Android waits and stalled socket transfers share the owner wake.
    pub fn waitTimeoutMs(self: *const Server) i32 {
        var deadline: ?i64 = if (self.pending) |pending| pending.deadline_ms else null;
        for ([_]?i64{ self.input_deadline, self.output_deadline }) |candidate| {
            if (candidate) |value| deadline = if (deadline) |old| @min(old, value) else value;
        }
        const remaining = (deadline orelse return -1) - monotonicMs(self.io);
        if (remaining <= 0) return 0;
        return @intCast(@min(remaining, std.math.maxInt(i32)));
    }

    /// Idle/frame waits deliberately advance presentation even when the application has no
    /// dirty UI. This is test-only pacing used to establish consecutive stable frames.
    pub fn needsFrame(self: *const Server) bool {
        const pending = self.pending orelse return false;
        return pending.wait_kind == .idle or pending.wait_kind == .frame_presented;
    }

    pub fn tick(
        self: *Server,
        android: *const Android,
        frame_seq: u64,
        presented_frame_seq: u64,
    ) void {
        self.frame_seq = frame_seq;
        self.presented_frame_seq = presented_frame_seq;
        const now = monotonicMs(self.io);
        for ([_]?i64{ self.input_deadline, self.output_deadline }) |deadline| {
            if (deadline) |value| if (now >= value) {
                self.closeClient();
                return;
            };
        }
        self.acceptClient();
        self.readClient();
        self.flushOutput();
        if (self.client == null) return;

        if (self.pending != null) {
            self.evaluatePending(android) catch |err| self.internalFailure(err);
        } else if (self.output.items.len == 0) {
            self.processOne(android) catch |err| self.internalFailure(err);
        }
        self.flushOutput();
    }

    fn acceptClient(self: *Server) void {
        if (self.client != null) return;
        const result = self.listener.take() orelse return;
        self.client = result catch |err| {
            std.log.err("test control listener stopped: {s}", .{@errorName(err)});
            return;
        };
        self.input.clearRetainingCapacity();
        self.output.clearRetainingCapacity();
    }

    fn closeClient(self: *Server) void {
        if (self.client) |file| file.close(self.io);
        self.client = null;
        self.input.clearRetainingCapacity();
        self.output.clearRetainingCapacity();
        if (self.pending) |pending| self.gpa.free(pending.message);
        self.pending = null;
        self.input_deadline = null;
        self.output_deadline = null;
        self.listener.again();
    }

    fn readClient(self: *Server) void {
        const file = self.client orelse return;
        var buf: [8192]u8 = undefined;
        while (true) {
            const count = file.readStreaming(self.io, &.{&buf}) catch |err| switch (err) {
                error.WouldBlock => return,
                else => {
                    self.closeClient();
                    return;
                },
            };
            if (count == 0) return;
            if (count > max_message_size - self.input.items.len) {
                self.closeClient();
                return;
            }
            self.input.appendSlice(self.gpa, buf[0..count]) catch {
                self.closeClient();
                return;
            };
            // A complete NUL-delimited message may wait for an Android result;
            // only an incomplete tail has a transport deadline. A trickling
            // peer cannot extend the lifetime of that partial message.
            if (std.mem.lastIndexOfScalar(u8, buf[0..count], 0)) |last| {
                self.input_deadline = if (last + 1 == count) null else monotonicMs(self.io) + transfer_timeout_ms;
            } else if (self.input_deadline == null) {
                self.input_deadline = monotonicMs(self.io) + transfer_timeout_ms;
            }
        }
    }

    fn flushOutput(self: *Server) void {
        const file = self.client orelse return;
        while (self.output.items.len != 0) {
            const count = file.writeStreaming(self.io, &.{}, &.{self.output.items}, 1) catch |err| switch (err) {
                error.WouldBlock => return,
                else => {
                    self.closeClient();
                    return;
                },
            };
            if (count == 0) return;
            const remaining = self.output.items.len - count;
            std.mem.copyForwards(u8, self.output.items[0..remaining], self.output.items[count..]);
            self.output.items.len = remaining;
        }
        self.output_deadline = null;
    }

    fn processOne(self: *Server, android: *const Android) !void {
        const nul = std.mem.indexOfScalar(u8, self.input.items, 0) orelse return;
        const message = try self.gpa.dupe(u8, self.input.items[0..nul]);
        defer self.gpa.free(message);
        const remaining = self.input.items.len - nul - 1;
        std.mem.copyForwards(u8, self.input.items[0..remaining], self.input.items[nul + 1 ..]);
        self.input.items.len = remaining;

        var parsed = std.json.parseFromSlice(std.json.Value, self.gpa, message, .{}) catch {
            try self.varlinkServiceError("InvalidParameter", "parameter", "message");
            return;
        };
        defer parsed.deinit();
        const call = parsed.value;
        const method = stringField(call, "method") orelse {
            try self.varlinkServiceError("InvalidParameter", "parameter", "method");
            return;
        };
        const oneway = boolField(call, "oneway") orelse false;

        if (std.mem.eql(u8, method, "org.varlink.service.GetInfo")) {
            if (!oneway) try self.serviceInfo();
            return;
        }
        if (std.mem.eql(u8, method, "org.varlink.service.GetInterfaceDescription")) {
            const parameters = valueField(call, "parameters") orelse .null;
            const requested = stringField(parameters, "interface") orelse "";
            if (!std.mem.eql(u8, requested, interface_name)) {
                if (!oneway) try self.varlinkServiceError("InterfaceNotFound", "interface", requested);
            } else if (!oneway) {
                try self.interfaceDescription();
            }
            return;
        }

        if (std.mem.eql(u8, method, interface_name ++ ".Diagnose")) {
            self.command_seq += 1;
            if (!oneway) try self.respondDiagnose(android, call);
            return;
        }
        if (std.mem.eql(u8, method, interface_name ++ ".Observe")) {
            self.command_seq += 1;
            try self.startRequest(android, message, .observe, oneway);
            return;
        }
        if (std.mem.eql(u8, method, interface_name ++ ".Perform")) {
            self.command_seq += 1;
            try self.startPerform(android, message, call, oneway);
            return;
        }
        if (!oneway) try self.varlinkServiceError("MethodNotFound", "method", method);
    }

    fn startRequest(
        self: *Server,
        android: *const Android,
        message: []const u8,
        method: PendingMethod,
        oneway: bool,
    ) !void {
        var parsed = try std.json.parseFromSlice(std.json.Value, self.gpa, message, .{});
        defer parsed.deinit();
        const wait = requestWait(parsed.value, method) orelse return error.InvalidWait;
        var pending = Pending{
            .message = try self.gpa.dupe(u8, message),
            .method = method,
            .wait_kind = wait.kind,
            .deadline_ms = monotonicMs(self.io) + wait.timeout_ms,
            .stable_required = wait.stable_frames,
            .last_revision = try android.revision(),
            .last_presented = self.presented_frame_seq,
            .oneway = oneway,
        };
        if (try self.conditionMet(android, parsed.value, &pending)) {
            if (!oneway) try self.respondObservation(android, parsed.value, false);
            self.gpa.free(pending.message);
        } else {
            self.pending = pending;
        }
    }

    fn startPerform(
        self: *Server,
        android: *const Android,
        message: []const u8,
        call: std.json.Value,
        oneway: bool,
    ) !void {
        const parameters = valueField(call, "parameters") orelse return error.InvalidParameters;
        const selector = valueField(parameters, "selector") orelse return error.InvalidSelector;
        const action = valueField(parameters, "action") orelse return error.InvalidAction;
        const via = stringField(action, "via") orelse "semantic";
        const kind = stringField(action, "kind") orelse return error.InvalidAction;
        const text = stringField(action, "text");

        const snapshot_json = try android.snapshot(self.gpa);
        defer self.gpa.free(snapshot_json);
        var snapshot = try std.json.parseFromSlice(std.json.Value, self.gpa, snapshot_json, .{});
        defer snapshot.deinit();
        if (boolField(snapshot.value, "truncated") orelse false) {
            if (!oneway) try self.interfaceErrorNoParams("TreeTooLarge");
            return;
        }
        var matches: std.ArrayList(std.json.Value) = .empty;
        defer matches.deinit(self.gpa);
        try selectNodes(self.gpa, snapshot.value, selector, &matches);
        applyIndex(selector, &matches);
        if (matches.items.len == 0) {
            if (!oneway) try self.interfaceErrorNoParams("NoMatch");
            return;
        }
        if (matches.items.len != 1) {
            if (!oneway) try self.interfaceErrorInt("AmbiguousMatch", "count", @intCast(matches.items.len));
            return;
        }
        const node = matches.items[0];
        if (!(boolField(node, "visible") orelse false) or !(boolField(node, "enabled") orelse false)) {
            if (!oneway) try self.interfaceErrorNoParams("InactiveWindow");
            return;
        }
        const revision: u64 = @intCast(intField(snapshot.value, "revision") orelse 0);
        const applied = if (std.mem.eql(u8, via, "semantic")) blk: {
            const action_id = semanticAction(kind) orelse {
                if (!oneway) try self.interfaceError("UnsupportedAction", "action", kind);
                return;
            };
            break :blk try android.perform(
                self.gpa,
                revision,
                intField(node, "window_id") orelse return error.InvalidNode,
                @intCast(intField(node, "view_id") orelse return error.InvalidNode),
                @intCast(intField(node, "virtual_id") orelse return error.InvalidNode),
                action_id,
                text,
            );
        } else if (std.mem.eql(u8, via, "wayland")) blk: {
            const input = self.ui_input orelse {
                if (!oneway) try self.interfaceError("InputUnavailable", "reason", "compositor does not expose virtual input");
                return;
            };
            input.select(revision, intField(node, "window_id") orelse return error.InvalidNode, @intCast(intField(node, "view_id") orelse return error.InvalidNode), @intCast(intField(node, "virtual_id") orelse return error.InvalidNode)) catch |err| {
                if (!oneway) try self.interfaceError("InputUnavailable", "reason", @errorName(err));
                return;
            };
            const bounds = valueField(node, "bounds") orelse return error.InvalidNode;
            const left = intField(bounds, "left") orelse return error.InvalidNode;
            const top = intField(bounds, "top") orelse return error.InvalidNode;
            const right = intField(bounds, "right") orelse return error.InvalidNode;
            const bottom = intField(bounds, "bottom") orelse return error.InvalidNode;
            if (right <= left or bottom <= top) {
                if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                return;
            }
            const x: i32 = @intCast(left + @divTrunc(right - left, 2));
            const y: i32 = @intCast(top + @divTrunc(bottom - top, 2));
            if (std.mem.eql(u8, kind, "click") or std.mem.eql(u8, kind, "tap")) {
                // Optional position within the node (from_x/from_y fractions);
                // the centre otherwise. A present but invalid fraction rejects.
                const tap_x = if (floatField(action, "from_x_fraction") != null)
                    fractionCoordinate(action, "from_x_fraction", left, right) orelse {
                        if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                        return;
                    }
                else
                    x;
                const tap_y = if (floatField(action, "from_y_fraction") != null)
                    fractionCoordinate(action, "from_y_fraction", top, bottom) orelse {
                        if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                        return;
                    }
                else
                    y;
                input.tap(tap_x, tap_y) catch |err| {
                    if (!oneway) try self.interfaceError("InputUnavailable", "reason", @errorName(err));
                    return;
                };
            } else if (std.mem.eql(u8, kind, "drag")) {
                const from_x = fractionCoordinate(action, "from_x_fraction", left, right) orelse {
                    if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                    return;
                };
                const from_y = fractionCoordinate(action, "from_y_fraction", top, bottom) orelse {
                    if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                    return;
                };
                const to_x = fractionCoordinate(action, "to_x_fraction", left, right) orelse {
                    if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                    return;
                };
                const to_y = fractionCoordinate(action, "to_y_fraction", top, bottom) orelse {
                    if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                    return;
                };
                input.drag(from_x, from_y, to_x, to_y) catch |err| {
                    if (!oneway) try self.interfaceError("InputUnavailable", "reason", @errorName(err));
                    return;
                };
            } else if (std.mem.eql(u8, kind, "type_keys") or std.mem.eql(u8, kind, "set_text")) {
                const value = text orelse {
                    if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                    return;
                };
                input.typeAt(x, y, value) catch |err| {
                    if (!oneway) try self.interfaceError("InputUnavailable", "reason", @errorName(err));
                    return;
                };
            } else if (std.mem.eql(u8, kind, "key")) {
                const value = stringField(action, "key") orelse {
                    if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
                    return;
                };
                input.keyAt(x, y, value) catch |err| {
                    if (!oneway) try self.interfaceError("InputUnavailable", "reason", @errorName(err));
                    return;
                };
            } else {
                if (!oneway) try self.interfaceError("UnsupportedAction", "action", kind);
                return;
            }
            break :blk true;
        } else {
            if (!oneway) try self.interfaceError("InputUnavailable", "reason", "unknown input adapter");
            return;
        };
        if (!applied) {
            if (!oneway) try self.interfaceError("ActionRejected", "action", kind);
            return;
        }

        try self.startRequest(android, message, .perform, oneway);
        if (self.pending) |*pending| pending.applied = true;
    }

    fn evaluatePending(self: *Server, android: *const Android) !void {
        const pending = &(self.pending orelse return);
        var parsed = try std.json.parseFromSlice(std.json.Value, self.gpa, pending.message, .{});
        defer parsed.deinit();
        if (try self.conditionMet(android, parsed.value, pending)) {
            if (!pending.oneway) try self.respondObservation(android, parsed.value, pending.applied);
            const message = pending.message;
            self.pending = null;
            self.gpa.free(message);
            return;
        }
        if (monotonicMs(self.io) >= pending.deadline_ms) {
            if (!pending.oneway) try self.interfaceErrorBool("Timeout", "applied", pending.applied);
            const message = pending.message;
            self.pending = null;
            self.gpa.free(message);
        }
    }

    fn conditionMet(
        self: *Server,
        android: *const Android,
        call: std.json.Value,
        pending: *Pending,
    ) !bool {
        switch (pending.wait_kind) {
            .now => return true,
            .ready => {
                if (self.presented_frame_seq == 0) return false;
                const snapshot_json = try android.snapshot(self.gpa);
                defer self.gpa.free(snapshot_json);
                var snapshot = try std.json.parseFromSlice(std.json.Value, self.gpa, snapshot_json, .{});
                defer snapshot.deinit();
                const windows = arrayField(snapshot.value, "windows") orelse return false;
                return windows.len != 0;
            },
            .idle => {
                const revision = try android.revision();
                if (self.presented_frame_seq != pending.last_presented) {
                    if (revision == pending.last_revision and try android.idle()) {
                        pending.stable_count += 1;
                    } else {
                        pending.stable_count = 0;
                    }
                    pending.last_revision = revision;
                    pending.last_presented = self.presented_frame_seq;
                }
                return pending.stable_count >= pending.stable_required;
            },
            .tree_changed => {
                const wait = requestWaitValue(call, pending.method) orelse return false;
                const after = valueField(wait, "after") orelse return false;
                return try android.revision() > @as(u64, @intCast(intField(after, "tree_revision") orelse 0));
            },
            .frame_presented => {
                const wait = requestWaitValue(call, pending.method) orelse return false;
                const after = valueField(wait, "after") orelse return false;
                const first = @as(u64, @intCast(intField(after, "presented_frame_seq") orelse 0));
                return self.presented_frame_seq >= first + pending.stable_required;
            },
            .exists, .absent => {
                const parameters = valueField(call, "parameters") orelse return false;
                const selector = valueField(parameters, "selector") orelse return false;
                const snapshot_json = try android.snapshot(self.gpa);
                defer self.gpa.free(snapshot_json);
                var snapshot = try std.json.parseFromSlice(std.json.Value, self.gpa, snapshot_json, .{});
                defer snapshot.deinit();
                var matches: std.ArrayList(std.json.Value) = .empty;
                defer matches.deinit(self.gpa);
                try selectNodes(self.gpa, snapshot.value, selector, &matches);
                applyIndex(selector, &matches);
                return if (pending.wait_kind == .exists) matches.items.len != 0 else matches.items.len == 0;
            },
        }
    }

    fn respondObservation(
        self: *Server,
        android: *const Android,
        call: std.json.Value,
        applied: bool,
    ) !void {
        const snapshot_json = try android.snapshot(self.gpa);
        defer self.gpa.free(snapshot_json);
        var snapshot = try std.json.parseFromSlice(std.json.Value, self.gpa, snapshot_json, .{});
        defer snapshot.deinit();
        const notifications_json = try android.notifications(self.gpa);
        defer self.gpa.free(notifications_json);
        var notifications = try std.json.parseFromSlice(std.json.Value, self.gpa, notifications_json, .{});
        defer notifications.deinit();
        const parameters = valueField(call, "parameters") orelse .null;
        const selector = valueField(parameters, "selector");
        const include_tree = boolField(parameters, "include_tree") orelse false;
        var matches: std.ArrayList(std.json.Value) = .empty;
        defer matches.deinit(self.gpa);
        if (selector) |value| {
            try selectNodes(self.gpa, snapshot.value, value, &matches);
            applyIndex(value, &matches);
        }

        var allocating: std.Io.Writer.Allocating = .init(self.gpa);
        defer allocating.deinit();
        var json: std.json.Stringify = .{ .writer = &allocating.writer, .options = .{} };
        try json.beginObject();
        try json.objectField("parameters");
        try json.beginObject();
        if (std.mem.endsWith(u8, stringField(call, "method") orelse "", ".Perform")) {
            try json.objectField("result");
            try json.beginObject();
            try json.objectField("applied");
            try json.write(applied);
            try json.endObject();
        }
        try json.objectField("observation");
        try json.beginObject();
        try self.writeMark(&json, android, try android.revision());
        try json.objectField("ready");
        try json.write(self.presented_frame_seq != 0);
        try json.objectField("idle");
        try json.write(try android.idle());
        try json.objectField("fullscreen");
        try json.write(if (self.ui_input) |input| input.fullscreen() else false);
        try json.objectField("matches");
        try json.beginArray();
        for (matches.items) |node| try json.write(node);
        try json.endArray();
        try json.objectField("notifications");
        try json.write(notifications.value);
        try json.objectField("tree");
        if (include_tree) try json.write(snapshot.value) else try json.write(null);
        try json.endObject();
        try json.endObject();
        try json.endObject();
        try self.queue(allocating.written());
    }

    fn respondDiagnose(self: *Server, android: *const Android, call: std.json.Value) !void {
        const parameters = valueField(call, "parameters") orelse .null;
        const since = valueField(parameters, "since") orelse .null;
        const since_epoch = stringField(since, "epoch");
        const cursor_matches = if (since_epoch) |epoch|
            std.mem.eql(u8, epoch, self.epoch_buf[0..self.epoch_len])
        else
            false;
        const after_revision: u64 = if (cursor_matches)
            @intCast(@max(0, intField(since, "tree_revision") orelse 0))
        else
            0;
        const after_log_sequence: u64 = if (cursor_matches)
            @intCast(@max(0, intField(since, "log_seq") orelse 0))
        else
            0;
        const after_issue_sequence: u64 = if (cursor_matches)
            @intCast(@max(0, intField(since, "issue_seq") orelse 0))
        else
            0;
        const snapshot_json = try android.snapshot(self.gpa);
        defer self.gpa.free(snapshot_json);
        const diagnostics_json = try android.diagnostics(self.gpa, after_revision);
        defer self.gpa.free(diagnostics_json);
        const runtime_json = try android.runtimeDiagnostics(
            self.gpa,
            after_log_sequence,
            after_issue_sequence,
        );
        defer self.gpa.free(runtime_json);
        var snapshot = try std.json.parseFromSlice(std.json.Value, self.gpa, snapshot_json, .{});
        defer snapshot.deinit();
        var diagnostics = try std.json.parseFromSlice(std.json.Value, self.gpa, diagnostics_json, .{});
        defer diagnostics.deinit();
        var runtime = try std.json.parseFromSlice(std.json.Value, self.gpa, runtime_json, .{});
        defer runtime.deinit();

        var allocating: std.Io.Writer.Allocating = .init(self.gpa);
        defer allocating.deinit();
        var json: std.json.Stringify = .{ .writer = &allocating.writer, .options = .{} };
        try json.beginObject();
        try json.objectField("parameters");
        try json.beginObject();
        try json.objectField("diagnostics");
        try json.beginObject();
        try self.writeMark(&json, android, try android.revision());
        try json.objectField("cursor_reset");
        try json.write(since_epoch != null and !cursor_matches);
        try json.objectField("android");
        try json.write(diagnostics.value);
        try json.objectField("runtime");
        try json.write(runtime.value);
        try json.objectField("tree");
        try json.write(snapshot.value);
        try json.endObject();
        try json.endObject();
        try json.endObject();
        try self.queue(allocating.written());
    }

    fn writeMark(
        self: *const Server,
        json: *std.json.Stringify,
        android: *const Android,
        revision: u64,
    ) !void {
        try json.objectField("mark");
        try json.beginObject();
        try json.objectField("epoch");
        try json.write(self.epoch_buf[0..self.epoch_len]);
        try json.objectField("command_seq");
        try json.write(self.command_seq);
        try json.objectField("input_seq");
        try json.write(self.input_seq);
        try json.objectField("tree_revision");
        try json.write(revision);
        try json.objectField("log_seq");
        try json.write(try android.logSequence());
        try json.objectField("issue_seq");
        try json.write(try android.issueSequence());
        try json.objectField("frame_seq");
        try json.write(self.frame_seq);
        try json.objectField("presented_frame_seq");
        try json.write(self.presented_frame_seq);
        try json.endObject();
    }

    fn serviceInfo(self: *Server) !void {
        const response =
            "{\"parameters\":{\"vendor\":\"Artzig\",\"product\":\"Androlinzberg\"," ++
            "\"version\":\"1\",\"url\":\"https://github.com\",\"interfaces\":[" ++
            "\"org.varlink.service\",\"" ++ interface_name ++ "\"]}}";
        try self.queue(response);
    }

    fn interfaceDescription(self: *Server) !void {
        var allocating: std.Io.Writer.Allocating = .init(self.gpa);
        defer allocating.deinit();
        var json: std.json.Stringify = .{ .writer = &allocating.writer, .options = .{} };
        try json.beginObject();
        try json.objectField("parameters");
        try json.beginObject();
        try json.objectField("description");
        try json.write(interface_description);
        try json.endObject();
        try json.endObject();
        try self.queue(allocating.written());
    }

    fn varlinkServiceError(self: *Server, name: []const u8, field_name: []const u8, value: []const u8) !void {
        try self.errorString("org.varlink.service", name, field_name, value);
    }

    fn interfaceError(self: *Server, name: []const u8, field_name: []const u8, value: []const u8) !void {
        try self.errorString(interface_name, name, field_name, value);
    }

    fn errorString(self: *Server, namespace: []const u8, name: []const u8, field_name: []const u8, value: []const u8) !void {
        var allocating: std.Io.Writer.Allocating = .init(self.gpa);
        defer allocating.deinit();
        var json: std.json.Stringify = .{ .writer = &allocating.writer, .options = .{} };
        try json.beginObject();
        try json.objectField("error");
        const error_name = try std.fmt.allocPrint(self.gpa, "{s}.{s}", .{ namespace, name });
        defer self.gpa.free(error_name);
        try json.write(error_name);
        try json.objectField("parameters");
        try json.beginObject();
        try json.objectField(field_name);
        try json.write(value);
        try json.endObject();
        try json.endObject();
        try self.queue(allocating.written());
    }

    fn interfaceErrorNoParams(self: *Server, name: []const u8) !void {
        const response = try std.fmt.allocPrint(self.gpa, "{{\"error\":\"{s}.{s}\",\"parameters\":{{}}}}", .{ interface_name, name });
        defer self.gpa.free(response);
        try self.queue(response);
    }

    fn interfaceErrorInt(self: *Server, name: []const u8, field_name: []const u8, value: i64) !void {
        const response = try std.fmt.allocPrint(self.gpa, "{{\"error\":\"{s}.{s}\",\"parameters\":{{\"{s}\":{d}}}}}", .{ interface_name, name, field_name, value });
        defer self.gpa.free(response);
        try self.queue(response);
    }

    fn interfaceErrorBool(self: *Server, name: []const u8, field_name: []const u8, value: bool) !void {
        const response = try std.fmt.allocPrint(self.gpa, "{{\"error\":\"{s}.{s}\",\"parameters\":{{\"{s}\":{s}}}}}", .{ interface_name, name, field_name, if (value) "true" else "false" });
        defer self.gpa.free(response);
        try self.queue(response);
    }

    fn internalFailure(self: *Server, err: anyerror) void {
        const message = std.fmt.allocPrint(self.gpa, "{s}", .{@errorName(err)}) catch return;
        defer self.gpa.free(message);
        self.interfaceError("InternalFailure", "message", message) catch self.closeClient();
    }

    fn queue(self: *Server, bytes: []const u8) !void {
        if (bytes.len >= max_response_size - self.output.items.len) return error.ResponseTooLarge;
        if (self.output.items.len == 0) self.output_deadline = monotonicMs(self.io) + transfer_timeout_ms;
        try self.output.appendSlice(self.gpa, bytes);
        try self.output.append(self.gpa, 0);
    }
};

const WaitSpec = struct {
    kind: WaitKind,
    timeout_ms: i64,
    stable_frames: u32,
};

fn requestWait(call: std.json.Value, method: PendingMethod) ?WaitSpec {
    const wait = requestWaitValue(call, method) orelse return null;
    const kind_name = stringField(wait, "kind") orelse "now";
    const kind = std.meta.stringToEnum(WaitKind, kind_name) orelse return null;
    const timeout = intField(wait, "timeout_ms") orelse 5000;
    const stable = intField(wait, "stable_frames") orelse 2;
    return .{
        .kind = kind,
        .timeout_ms = @max(0, timeout),
        .stable_frames = @intCast(@max(1, stable)),
    };
}

fn requestWaitValue(call: std.json.Value, method: PendingMethod) ?std.json.Value {
    const parameters = valueField(call, "parameters") orelse return null;
    return valueField(parameters, if (method == .observe) "wait" else "settle");
}

fn semanticAction(name: []const u8) ?i32 {
    if (std.mem.eql(u8, name, "focus")) return 1;
    if (std.mem.eql(u8, name, "click")) return 1 << 4;
    if (std.mem.eql(u8, name, "long_click")) return 1 << 5;
    if (std.mem.eql(u8, name, "scroll_forward")) return 1 << 12;
    if (std.mem.eql(u8, name, "scroll_backward")) return 1 << 13;
    if (std.mem.eql(u8, name, "set_text")) return 1 << 21;
    return null;
}

fn selectNodes(
    allocator: std.mem.Allocator,
    snapshot: std.json.Value,
    selector: std.json.Value,
    out: *std.ArrayList(std.json.Value),
) !void {
    const nodes = arrayField(snapshot, "nodes") orelse return;
    var active_window: ?i64 = null;
    if (arrayField(snapshot, "windows")) |windows| {
        for (windows) |window| {
            if (boolField(window, "active") orelse false) {
                active_window = intField(window, "id");
                break;
            }
        }
    }
    const all_windows = if (stringField(selector, "window")) |window|
        std.mem.eql(u8, window, "all")
    else
        intField(selector, "task_id") != null or intField(selector, "window_id") != null or
            intField(selector, "pid") != null or intField(selector, "process_generation") != null;
    for (nodes) |node| {
        if (!matchesInt(selector, node, "task_id")) continue;
        if (!matchesInt(selector, node, "window_id")) continue;
        if (!matchesInt(selector, node, "pid")) continue;
        if (!matchesInt(selector, node, "process_generation")) continue;
        if (!all_windows and active_window != null and intField(node, "window_id") != active_window) continue;
        if (!matchesString(selector, node, "resource_id")) continue;
        if (!matchesString(selector, node, "text")) continue;
        if (!matchesString(selector, node, "content_description")) continue;
        if (!matchesString(selector, node, "class_name")) continue;
        if (!matchesString(selector, node, "role")) continue;
        if (!matchesString(selector, node, "state_description")) continue;
        if (!matchesBool(selector, node, "visible")) continue;
        if (!matchesBool(selector, node, "enabled")) continue;
        if (!matchesBool(selector, node, "focused")) continue;
        try out.append(allocator, node);
    }
}

fn applyIndex(selector: std.json.Value, matches: *std.ArrayList(std.json.Value)) void {
    const index_value = intField(selector, "index") orelse return;
    if (index_value < 0 or index_value >= matches.items.len) {
        matches.clearRetainingCapacity();
        return;
    }
    const selected = matches.items[@intCast(index_value)];
    matches.clearRetainingCapacity();
    matches.appendAssumeCapacity(selected);
}

fn matchesString(selector: std.json.Value, node: std.json.Value, name: []const u8) bool {
    const expected = stringField(selector, name) orelse return true;
    const actual = stringField(node, name) orelse return false;
    return std.mem.eql(u8, expected, actual);
}

fn matchesInt(selector: std.json.Value, node: std.json.Value, name: []const u8) bool {
    const field = valueField(selector, name) orelse return true;
    if (field == .null) return true;
    const expected = intField(selector, name) orelse return false;
    const actual = intField(node, name) orelse return false;
    return expected == actual;
}

fn matchesBool(selector: std.json.Value, node: std.json.Value, name: []const u8) bool {
    const expected = boolField(selector, name) orelse return true;
    const actual = boolField(node, name) orelse return false;
    return expected == actual;
}

fn valueField(value: std.json.Value, name: []const u8) ?std.json.Value {
    return switch (value) {
        .object => |object| object.get(name),
        else => null,
    };
}

fn stringField(value: std.json.Value, name: []const u8) ?[]const u8 {
    const field = valueField(value, name) orelse return null;
    return switch (field) {
        .string => |string| string,
        else => null,
    };
}

fn intField(value: std.json.Value, name: []const u8) ?i64 {
    const field = valueField(value, name) orelse return null;
    return switch (field) {
        .integer => |integer| integer,
        else => null,
    };
}

fn floatField(value: std.json.Value, name: []const u8) ?f64 {
    const field = valueField(value, name) orelse return null;
    return switch (field) {
        .float => |number| number,
        .integer => |integer| @floatFromInt(integer),
        else => null,
    };
}

fn fractionCoordinate(value: std.json.Value, name: []const u8, start: i64, end: i64) ?i32 {
    const fraction = floatField(value, name) orelse return null;
    if (!std.math.isFinite(fraction) or fraction < 0 or fraction > 1 or end <= start) return null;
    const span: f64 = @floatFromInt(end - start - 1);
    const offset: i64 = @intFromFloat(@round(span * fraction));
    return @intCast(start + offset);
}

fn boolField(value: std.json.Value, name: []const u8) ?bool {
    const field = valueField(value, name) orelse return null;
    return switch (field) {
        .bool => |boolean| boolean,
        else => null,
    };
}

fn arrayField(value: std.json.Value, name: []const u8) ?[]const std.json.Value {
    const field = valueField(value, name) orelse return null;
    return switch (field) {
        .array => |array| array.items,
        else => null,
    };
}

fn monotonicMs(io: Io) i64 {
    return Io.Clock.awake.now(io).toMilliseconds();
}

test "structured selectors default to the active window" {
    const json =
        \\{"windows":[{"id":1,"active":false},{"id":2,"active":true}],"nodes":[
        \\{"window_id":1,"text":"Go","visible":true},
        \\{"window_id":2,"text":"Go","visible":true}]}
    ;
    var snapshot = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, json, .{});
    defer snapshot.deinit();
    var selector = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, "{\"text\":\"Go\"}", .{});
    defer selector.deinit();
    var matches: std.ArrayList(std.json.Value) = .empty;
    defer matches.deinit(std.testing.allocator);
    try selectNodes(std.testing.allocator, snapshot.value, selector.value, &matches);
    try std.testing.expectEqual(@as(usize, 1), matches.items.len);
    try std.testing.expectEqual(@as(i64, 2), intField(matches.items[0], "window_id").?);
}

test "control server advertises pending nonblocking output" {
    var server: Server = undefined;
    server.client = .{ .handle = 7, .flags = .{ .nonblocking = true } };
    server.output = .empty;
    defer server.output.deinit(std.testing.allocator);
    try std.testing.expect(!server.clientWantsWrite());
    try server.output.append(std.testing.allocator, 1);
    try std.testing.expect(server.clientWantsWrite());
    server.client = null;
    try std.testing.expect(!server.clientWantsWrite());
}

test "absent pointer fraction reads as null, present ones map" {
    var action = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, "{\"from_y_fraction\":0.25}", .{});
    defer action.deinit();
    try std.testing.expect(floatField(action.value, "from_x_fraction") == null);
    try std.testing.expectEqual(@as(i32, 25), fractionCoordinate(action.value, "from_y_fraction", 0, 100).?);
}

test "pointer fractions map within selected bounds" {
    var action = try std.json.parseFromSlice(
        std.json.Value,
        std.testing.allocator,
        "{\"from_x_fraction\":0.25,\"to_x_fraction\":1}",
        .{},
    );
    defer action.deinit();
    try std.testing.expectEqual(@as(i32, 125), fractionCoordinate(action.value, "from_x_fraction", 100, 201).?);
    try std.testing.expectEqual(@as(i32, 200), fractionCoordinate(action.value, "to_x_fraction", 100, 201).?);
}

// Explicit process/task identities select independently of current desktop focus.
// A missing identity must never broaden the selector to a different app.
test "task selectors preserve identity across duplicate labels and focus" {
    const json =
        \\{"windows":[{"id":1,"active":false},{"id":2,"active":true}],"nodes":[
        \\{"window_id":1,"task_id":10,"pid":100,"process_generation":3,"text":"Go"},
        \\{"window_id":2,"task_id":20,"pid":200,"process_generation":4,"text":"Go"}]}
    ;
    var snapshot = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, json, .{});
    defer snapshot.deinit();
    const cases = .{
        .{ "{\"text\":\"Go\",\"task_id\":10}", 1 },
        .{ "{\"text\":\"Go\",\"window_id\":1}", 1 },
        .{ "{\"pid\":100,\"process_generation\":3}", 1 },
        .{ "{\"pid\":100,\"process_generation\":4}", 0 },
        .{ "{\"task_id\":10,\"window_id\":2}", 0 },
        .{ "{\"task_id\":10,\"window\":\"active\"}", 0 },
        .{ "{\"task_id\":999}", 0 },
        .{ "{\"task_id\":\"10\"}", 0 },
    };
    inline for (cases) |case| {
        var selector = try std.json.parseFromSlice(std.json.Value, std.testing.allocator, case[0], .{});
        defer selector.deinit();
        var matches: std.ArrayList(std.json.Value) = .empty;
        defer matches.deinit(std.testing.allocator);
        try selectNodes(std.testing.allocator, snapshot.value, selector.value, &matches);
        try std.testing.expectEqual(@as(usize, case[1]), matches.items.len);
        if (case[1] != 0) try std.testing.expectEqual(@as(i64, 1), intField(matches.items[0], "window_id").?);
    }
}

test "control transport frames fragmented and pipelined requests across reconnects" {
    const io = std.testing.io;
    const gpa = std.testing.allocator;
    var nonce: [12]u8 = undefined;
    io.random(&nonce);
    var path_buffer: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buffer, "/tmp/azk-control-{s}.sock", .{std.fmt.bytesToHex(nonce, .lower)});
    var server = try Server.init(gpa, io, path);
    defer server.deinit();
    // Failed initialization must preserve an existing endpoint and close the
    // unpublished listener; the original server must remain usable below.
    const descriptors = try controlTestDescriptorCount(io);
    try std.testing.expectError(error.PathAlreadyExists, Server.init(gpa, io, path));
    try std.testing.expectEqual(descriptors, try controlTestDescriptorCount(io));
    const address = try Io.net.UnixAddress.init(path);
    const android: Android = undefined; // Varlink introspection must not enter ART.
    const request = "{\"method\":\"org.varlink.service.GetInfo\"}\x00";
    for (0..16) |_| {
        const stream = try address.connect(io);
        const peer = try Acceptor.nonblocking(io, .{ .handle = stream.socket.handle, .flags = .{ .nonblocking = false } });
        defer peer.close(io);
        try peer.writeStreamingAll(io, request[0..12]);
        for (0..3) |_| {
            server.tick(&android, 0, 0);
            try io.sleep(.fromMilliseconds(1), .awake);
        }
        var buffer: [2048]u8 = undefined;
        try std.testing.expectError(error.WouldBlock, peer.readStreaming(io, &.{&buffer}));
        try peer.writeStreamingAll(io, request[12..]);
        try peer.writeStreamingAll(io, request);
        var replies: std.ArrayList(u8) = .empty;
        defer replies.deinit(gpa);
        const deadline = monotonicMs(io) + 3000;
        while (std.mem.count(u8, replies.items, "\x00") < 2) {
            try std.testing.expect(monotonicMs(io) < deadline);
            server.tick(&android, 0, 0);
            const n = peer.readStreaming(io, &.{&buffer}) catch |err| switch (err) {
                error.WouldBlock => {
                    try io.sleep(.fromMilliseconds(1), .awake);
                    continue;
                },
                else => return err,
            };
            try replies.appendSlice(gpa, buffer[0..n]);
        }
        var framed = std.mem.splitScalar(u8, replies.items, 0);
        for (0..2) |_| {
            var response = try std.json.parseFromSlice(std.json.Value, gpa, framed.next().?, .{});
            defer response.deinit();
            const parameters = valueField(response.value, "parameters").?;
            const interfaces = arrayField(parameters, "interfaces").?;
            const found = for (interfaces) |value| {
                if (std.mem.eql(u8, value.string, interface_name)) break true;
            } else false;
            try std.testing.expect(found);
        }
    }
    // The final defer cancels an accept or demand wait and closes every queued
    // descriptor. Repeated reconnects above also cover EOF retirement/rearming.
}

fn controlTestDescriptorCount(io: Io) !usize {
    var dir = try Io.Dir.cwd().openDir(io, "/proc/self/fd", .{ .iterate = true });
    defer dir.close(io);
    var iterator = dir.iterate();
    var count: usize = 0;
    while (try iterator.next(io)) |_| count += 1;
    return count;
}

test "control shutdown cancels blocked accept and releases sockets and paths" {
    const io = std.testing.io;
    const before = try controlTestDescriptorCount(io);
    var nonce: [12]u8 = undefined;
    io.random(&nonce);
    var path_buffer: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buffer, "/tmp/azk-control-stop-{s}.sock", .{std.fmt.bytesToHex(nonce, .lower)});
    for (0..16) |_| {
        var server = try Server.init(std.testing.allocator, io, path);
        const stat = try Io.Dir.cwd().statFile(io, path, .{});
        try std.testing.expectEqual(@as(u32, 0o600), stat.permissions.toMode() & 0o777);
        // Leave the accept worker blocked with no connecting peer. Cancellation
        // must join it before either the listener or its wake descriptor closes.
        try io.sleep(.fromMilliseconds(2), .awake);
        server.deinit();
        try std.testing.expectError(error.FileNotFound, Io.Dir.cwd().statFile(io, path, .{}));
    }
    try std.testing.expectEqual(before, try controlTestDescriptorCount(io));
}

test "control socket staging preserves endpoints near the Unix path limit" {
    const io = std.testing.io;
    const gpa = std.testing.allocator;
    var nonce: [12]u8 = undefined;
    io.random(&nonce);
    var directory_buffer: [100]u8 = undefined;
    const directory = try std.fmt.bufPrint(&directory_buffer, "/tmp/azk-control-{s}-abcdefghijklmnopqrstuvwxyz-abcdefghijklmnopqrstuvwxyz", .{std.fmt.bytesToHex(nonce, .lower)});
    try Io.Dir.cwd().createDir(io, directory, .fromMode(0o700));
    defer Io.Dir.cwd().deleteDir(io, directory) catch {};
    const path = try std.fs.path.join(gpa, &.{ directory, "socket" });
    defer gpa.free(path);
    try std.testing.expect(path.len < 108 and directory.len + 24 >= 108);
    var server = try Server.init(gpa, io, path);
    defer server.deinit();
    const address = try Io.net.UnixAddress.init(path);
    const peer = try address.connect(io);
    defer peer.close(io);
}

test "control retires partial senders and nonreading peers without starving reconnection" {
    const io = std.testing.io;
    const gpa = std.testing.allocator;
    const android: Android = undefined;
    var nonce: [12]u8 = undefined;
    io.random(&nonce);
    var path_buffer: [96]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buffer, "/tmp/azk-control-pressure-{s}.sock", .{std.fmt.bytesToHex(nonce, .lower)});
    var server = try Server.init(gpa, io, path);
    defer server.deinit();
    const address = try Io.net.UnixAddress.init(path);
    for ([_]bool{ false, true }) |nonreading| {
        const stream = try address.connect(io);
        const peer = try Acceptor.nonblocking(io, .{ .handle = stream.socket.handle, .flags = .{ .nonblocking = false } });
        defer peer.close(io);
        var requests: std.ArrayList(u8) = .empty;
        defer requests.deinit(gpa);
        if (nonreading) {
            const request = "{\"method\":\"org.varlink.service.GetInterfaceDescription\",\"parameters\":{\"interface\":\"" ++ interface_name ++ "\"}}\x00";
            for (0..300) |_| try requests.appendSlice(gpa, request);
        } else try requests.appendSlice(gpa, "{");
        try peer.writeStreamingAll(io, requests.items);
        const deadline = monotonicMs(io) + transfer_timeout_ms + 3000;
        var connected = false;
        var observed_write_pressure = false;
        while (true) {
            try std.testing.expect(monotonicMs(io) < deadline);
            server.tick(&android, 0, 0);
            if (server.clientFd() != null) connected = true;
            if (nonreading and server.clientWantsWrite()) observed_write_pressure = true;
            if (connected and server.clientFd() == null) break;
            try io.sleep(.fromMilliseconds(1), .awake);
        }
        if (nonreading) try std.testing.expect(observed_write_pressure);
    }
    // A fresh client can still use the public protocol after either timeout.
    const stream = try address.connect(io);
    const peer = try Acceptor.nonblocking(io, .{ .handle = stream.socket.handle, .flags = .{ .nonblocking = false } });
    defer peer.close(io);
    try peer.writeStreamingAll(io, "{\"method\":\"org.varlink.service.GetInfo\"}\x00");
    var buffer: [2048]u8 = undefined;
    const deadline = monotonicMs(io) + 3000;
    while (true) {
        try std.testing.expect(monotonicMs(io) < deadline);
        server.tick(&android, 0, 0);
        const count = peer.readStreaming(io, &.{&buffer}) catch |err| switch (err) {
            error.WouldBlock => {
                try io.sleep(.fromMilliseconds(1), .awake);
                continue;
            },
            else => return err,
        };
        try std.testing.expect(std.mem.indexOf(u8, buffer[0..count], interface_name) != null);
        break;
    }
}
