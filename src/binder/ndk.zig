//! `libbinder_ndk` for the hosted session: the Android NDK binder C ABI
//! (`AIBinder_*`, `AParcel_*`, `AServiceManager_*`, `AStatus_*`,
//! `ABinderProcess_*`) implemented in Zig over the AZKB socket protocol, so
//! unmodified NDK/AIDL-cpp code links against it exactly as on Android.
//!
//! The library opens its own broker connection (a second peer of the process's
//! package — the broker assigns uid per package name, so it shares the JVM
//! runtime's uid). Java↔native calls compose through the broker: a service a
//! Java peer registered resolves to a remote `AIBinder`, and a service this
//! library registers is a broker object a Java peer can look up and call.
//!
//! Source note: the peer half mirrors
//! `android.os.HostedBinderRuntime` (Kotlin) — the same request-id/pending-call
//! map, per-remote delivery counting with `release`, local-export `uses` with
//! the `object_released`/`release_ack` handshake, nested transactions executed
//! on the thread parked on the originating request, and per-local oneway
//! serialization. The wire structs come from `Client.zig`/`Server.zig`.
const std = @import("std");
const Io = std.Io;
const net = Io.net;
const Connection = @import("Connection.zig");
const Client = @import("Client.zig");
const Server = @import("Server.zig");
const ObjectRef = Client.ObjectRef;
const ObjectEntry = Client.ObjectEntry;
const InHeader = Server.Message.Header;
const OutHeader = Client.Message.Header;
/// errno enum, used only for STATUS_* constant values (not for I/O calls).
const E = std.os.linux.E;
/// A raw descriptor as stored in a parcel's side table; all descriptor I/O
/// (attach/take/close/dup) goes through `std.Io.File`.
const fd_t = std.posix.fd_t;

const alloc = std.heap.c_allocator;

// A dlopen'd library gets no process `Init`, and unmodified NDK clients call
// no init hook, so the library provides its own `std.Io`: one `Io.Threaded`
// created lazily on first use (the process already has one from the Zig
// runtime, so this second instance only re-chains the identical SIGIO/SIGPIPE
// handlers). `std.Io` operations run blocking-inline on the calling thread, so
// ART threads may call in directly. Connection parameters come from the live
// environment the client already carries (AZK_BINDER_SOCKET etc., which the
// JVM runtime reads the same way).
var g_threaded: Io.Threaded = undefined;
var g_io: Io = undefined;
/// 0 = uninitialized, 1 = initializing, 2 = ready.
var g_io_state: std.atomic.Value(u8) = .init(0);

fn ensureIo() void {
    if (g_io_state.load(.acquire) == 2) return;
    if (g_io_state.cmpxchgStrong(0, 1, .acq_rel, .acquire) == null) {
        g_threaded = Io.Threaded.init(alloc, .{});
        g_io = g_threaded.io();
        g_io_state.store(2, .release);
        return;
    }
    while (g_io_state.load(.acquire) != 2) std.atomic.spinLoopHint();
}

/// The POSIX environment (reflects runtime `setenv`, unlike a captured
/// snapshot or /proc/self/environ). A direct extern, not the std.c namespace.
extern var environ: [*c]?[*:0]u8;

fn getenv(name: []const u8) ?[]const u8 {
    var i: usize = 0;
    while (environ[i]) |entry| : (i += 1) {
        const e = std.mem.span(entry);
        if (e.len > name.len and e[name.len] == '=' and std.mem.startsWith(u8, e, name)) {
            return e[name.len + 1 ..];
        }
    }
    return null;
}

/// Closes a raw descriptor through std.Io (descriptor ownership stays in Io).
fn closeFd(fd: fd_t) void {
    if (fd < 0) return;
    ensureIo();
    (Io.File{ .handle = fd, .flags = .{ .nonblocking = false } }).close(g_io);
}

/// NUL-terminated dup (this Zig's Allocator has no dupeZ); null on OOM.
fn dupz(bytes: []const u8) ?[:0]u8 {
    const buf = alloc.allocSentinel(u8, bytes.len, 0) catch return null;
    @memcpy(buf[0..bytes.len], bytes);
    return buf;
}

// ------------------------------------------------------------------- C ABI types

/// STATUS_* (binder_status.h). Negative values are -errno on Android; only the
/// ones the hosted protocol produces are enumerated.
const STATUS_OK: i32 = 0;
const STATUS_UNKNOWN_ERROR: i32 = std.math.minInt(i32);
const STATUS_BAD_VALUE: i32 = -@as(i32, @intCast(@intFromEnum(E.INVAL)));
const STATUS_NAME_NOT_FOUND: i32 = -@as(i32, @intCast(@intFromEnum(E.NOENT)));
const STATUS_DEAD_OBJECT: i32 = -@as(i32, @intCast(@intFromEnum(E.PIPE)));
const STATUS_FAILED_TRANSACTION: i32 = STATUS_UNKNOWN_ERROR + 2;
const STATUS_UNEXPECTED_NULL: i32 = STATUS_UNKNOWN_ERROR + 8;

const EX_NONE: i32 = 0;
const EX_TRANSACTION_FAILED: i32 = -129;

const FLAG_ONEWAY: u32 = 0x01;

// -------------------------------------------------------------------- AStatus

const AStatus = struct {
    exception: i32 = EX_NONE,
    service_specific: i32 = 0,
    status: i32 = STATUS_OK,
    /// Created by AStatus_fromStatus: a low-level transport status, written to
    /// a parcel as the status alone (no exception header).
    low_level: bool = false,
    message: ?[:0]u8 = null,

    fn create(exception: i32) *AStatus {
        const s = alloc.create(AStatus) catch @panic("oom");
        s.* = .{ .exception = exception };
        return s;
    }
    fn destroy(s: *AStatus) void {
        if (s.message) |m| alloc.free(m);
        alloc.destroy(s);
    }
};

// -------------------------------------------------------------------- AParcel

const Entry = struct {
    offset: u32,
    kind: ObjectEntry.Kind,
    value: u64,
    /// Owned duplicate fd for `file_descriptor` entries the parcel must close.
    fd: fd_t = -1,
};

const AParcel = struct {
    data: std.ArrayListUnmanaged(u8) = .empty,
    entries: std.ArrayListUnmanaged(Entry) = .empty,
    /// Received descriptors, indexed by an entry's ordinal `value`.
    fds: std.ArrayListUnmanaged(fd_t) = .empty,
    pos: usize = 0,
    /// A received parcel owns its fds and closes them on delete; a locally
    /// built parcel hands its fds to the frame and must not.
    owns_fds: bool = false,

    fn create() *AParcel {
        const p = alloc.create(AParcel) catch @panic("oom");
        p.* = .{};
        return p;
    }
    fn destroy(p: *AParcel) void {
        if (p.owns_fds) {
            for (p.fds.items) |fd| if (fd >= 0) closeFd(fd);
        }
        for (p.entries.items) |e| if (e.fd >= 0) closeFd(e.fd);
        p.data.deinit(alloc);
        p.entries.deinit(alloc);
        p.fds.deinit(alloc);
        alloc.destroy(p);
    }

    fn ensure(p: *AParcel, n: usize) void {
        if (p.pos + n > p.data.items.len) {
            p.data.resize(alloc, p.pos + n) catch @panic("oom");
        }
    }
    fn writeBytes(p: *AParcel, bytes: []const u8) void {
        p.ensure(bytes.len);
        @memcpy(p.data.items[p.pos .. p.pos + bytes.len], bytes);
        p.pos += bytes.len;
    }
    fn writeI32(p: *AParcel, v: i32) void {
        var buf: [4]u8 = undefined;
        std.mem.writeInt(i32, &buf, v, .little);
        p.writeBytes(&buf);
    }
    fn writeI64(p: *AParcel, v: i64) void {
        var buf: [8]u8 = undefined;
        std.mem.writeInt(i64, &buf, v, .little);
        p.writeBytes(&buf);
    }
    fn pad4(p: *AParcel) void {
        while (p.pos & 3 != 0) p.writeBytes(&[_]u8{0});
    }
    fn readSlice(p: *AParcel, n: usize) ?[]const u8 {
        if (p.pos + n > p.data.items.len) return null;
        const s = p.data.items[p.pos .. p.pos + n];
        p.pos += n;
        return s;
    }
    fn readI32(p: *AParcel) ?i32 {
        const s = p.readSlice(4) orelse return null;
        return std.mem.readInt(i32, s[0..4], .little);
    }
    fn readI64(p: *AParcel) ?i64 {
        const s = p.readSlice(8) orelse return null;
        return std.mem.readInt(i64, s[0..8], .little);
    }
    fn skipPad(p: *AParcel) void {
        while (p.pos & 3 != 0 and p.pos < p.data.items.len) p.pos += 1;
    }

    /// AOSP writeString16 layout the JVM Parcel uses: i32 char count, then
    /// UTF-16LE code units, a NUL unit, padded to 4. length<0 writes null.
    fn writeString(p: *AParcel, utf8: []const u8, length: i32) void {
        if (length < 0) {
            p.writeI32(-1);
            return;
        }
        // Transcode UTF-8 -> UTF-16.
        var units = std.ArrayListUnmanaged(u16).empty;
        defer units.deinit(alloc);
        var i: usize = 0;
        while (i < utf8.len) {
            const seq_len = std.unicode.utf8ByteSequenceLength(utf8[i]) catch 1;
            const cp = if (i + seq_len <= utf8.len)
                (std.unicode.utf8Decode(utf8[i .. i + seq_len]) catch utf8[i])
            else
                utf8[i];
            i += seq_len;
            if (cp <= 0xFFFF) {
                units.append(alloc, @intCast(cp)) catch @panic("oom");
            } else {
                const c = cp - 0x10000;
                units.append(alloc, @intCast(0xD800 + (c >> 10))) catch @panic("oom");
                units.append(alloc, @intCast(0xDC00 + (c & 0x3FF))) catch @panic("oom");
            }
        }
        p.writeI32(@intCast(units.items.len));
        for (units.items) |u| {
            var b: [2]u8 = undefined;
            std.mem.writeInt(u16, &b, u, .little);
            p.writeBytes(&b);
        }
        p.writeBytes(&[_]u8{ 0, 0 }); // NUL terminator unit
        p.pad4();
    }

    /// Reads the writeString16 layout into freshly allocated UTF-8. Returns
    /// null for the AOSP null marker (-1); caller frees a non-null result.
    fn readString(p: *AParcel) error{ Malformed, Null }![]u8 {
        const count = p.readI32() orelse return error.Malformed;
        if (count < 0) return error.Null;
        const n: usize = @intCast(count);
        var out = std.ArrayListUnmanaged(u8).empty;
        errdefer out.deinit(alloc);
        var read: usize = 0;
        while (read < n) : (read += 1) {
            const s = p.readSlice(2) orelse return error.Malformed;
            const unit = std.mem.readInt(u16, s[0..2], .little);
            var cp: u21 = unit;
            // Combine a high/low surrogate pair into one supplementary code
            // point (non-BMP characters such as emoji arrive as two units).
            if (unit >= 0xD800 and unit <= 0xDBFF and read + 1 < n) {
                const s2 = p.readSlice(2) orelse return error.Malformed;
                const low = std.mem.readInt(u16, s2[0..2], .little);
                if (low >= 0xDC00 and low <= 0xDFFF) {
                    cp = 0x10000 + ((@as(u21, unit) - 0xD800) << 10) + (@as(u21, low) - 0xDC00);
                    read += 1;
                } else {
                    // Not a valid pair; rewind the second unit.
                    p.pos -= 2;
                    cp = 0xFFFD;
                }
            } else if (unit >= 0xD800 and unit <= 0xDFFF) {
                cp = 0xFFFD; // lone surrogate
            }
            var buf: [4]u8 = undefined;
            const len = std.unicode.utf8Encode(cp, &buf) catch
                std.unicode.utf8Encode(0xFFFD, &buf) catch unreachable;
            out.appendSlice(alloc, buf[0..len]) catch @panic("oom");
        }
        _ = p.readSlice(2); // NUL unit
        p.skipPad();
        return out.toOwnedSlice(alloc) catch @panic("oom");
    }
};

// ------------------------------------------------------------ AIBinder + class

const AIBinder_Class = struct {
    descriptor: []u8,
    on_create: ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque,
    on_destroy: ?*const fn (?*anyopaque) callconv(.c) void,
    on_transact: ?*const fn (*AIBinder, u32, *const AParcel, *AParcel) callconv(.c) i32,
};

const AIBinder = struct {
    /// Strong refcount held by NDK callers (AIBinder_incStrong/decStrong).
    strong: std.atomic.Value(u32),
    kind: enum { local, remote },
    class: ?*AIBinder_Class = null,
    /// local: userdata from onCreate. remote: unused.
    user_data: ?*anyopaque = null,
    /// local: this peer's object id. remote: broker global id.
    id: u64,
    /// remote: set when the broker reports the object dead.
    dead: std.atomic.Value(bool),
    /// remote: number of times the broker has delivered this object to this
    /// peer (one per proxyFor). Released in full when the last NDK ref drops.
    deliveries: u32 = 0,

    fn createLocal(class: *AIBinder_Class, user_data: ?*anyopaque) *AIBinder {
        const b = alloc.create(AIBinder) catch @panic("oom");
        const rt = Runtime.get();
        rt.mu.lockUncancelable(g_io);
        const id = rt.next_local_id;
        rt.next_local_id += 1;
        b.* = .{ .strong = .init(1), .kind = .local, .class = class, .user_data = user_data, .id = id, .dead = .init(false) };
        // Not registered in `locals` until exported: the broker only routes
        // incoming transactions to objects this peer has sent or registered,
        // and `exportLocal` both records it and holds a ref for that lifetime.
        rt.mu.unlock(g_io);
        return b;
    }
};

const AIBinder_DeathRecipient = struct {
    on_died: ?*const fn (?*anyopaque) callconv(.c) void,
    on_unlinked: ?*const fn (?*anyopaque) callconv(.c) void = null,
};

// ------------------------------------------------------------------- Runtime

const PendingKind = enum { reply, service };

const PendingCall = struct {
    mu: Io.Mutex = .init,
    cv: Io.Condition = .init,
    kind: PendingKind,
    done: bool = false,
    failed: bool = false,
    // reply:
    reply: ?*AParcel = null,
    reply_status: Client.Message.Reply.Status = .ok,
    // service_result:
    svc_status: Server.Message.ServiceResult.Status = .ok,
    svc_ref: ObjectRef = ObjectRef.null_ref,
    svc_names: []u8 = &.{},
    // nested transactions the broker routed to the thread parked here:
    nested: std.ArrayListUnmanaged(*Incoming) = .empty,
};

const Incoming = struct {
    target: ObjectRef,
    code: u32,
    flags: u32,
    caller_pid: i32,
    caller_uid: u32,
    routing_id: u64,
    parcel: *AParcel,
};

const Runtime = struct {
    io: Io,
    conn: Connection,
    peer_id: u32 = 0,
    pid: i32 = 0,
    uid: u32 = 0,
    write_mu: Io.Mutex = .init,
    mu: Io.Mutex = .init,
    next_request_id: std.atomic.Value(u64) = .init(1),
    next_local_id: u64 = 1,
    pending: std.AutoHashMapUnmanaged(u64, *PendingCall) = .empty,
    locals: std.AutoHashMapUnmanaged(u64, *AIBinder) = .empty,
    export_uses: std.AutoHashMapUnmanaged(u64, u64) = .empty,
    proxies: std.AutoHashMapUnmanaged(u64, *AIBinder) = .empty,
    death_links: std.ArrayListUnmanaged(DeathLink) = .empty,
    disconnected: std.atomic.Value(bool) = .init(false),
    /// The routing id of the call each thread is currently servicing, for
    /// nested-transaction parent tracking.
    servicing: Io.Mutex = .init,

    const DeathLink = struct { global_id: u64, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque };

    var instance: ?*Runtime = null;
    var connect_mu: Io.Mutex = .init;
    threadlocal var current_routing: u64 = 0;
    /// Caller identity of the transaction the current thread is servicing
    /// (AIBinder_getCallingUid/Pid), valid only inside an onTransact callback.
    threadlocal var current_caller_uid: u32 = 0;
    threadlocal var current_caller_pid: i32 = 0;

    fn get() *Runtime {
        if (instance) |r| return r;
        ensureIo();
        connect_mu.lockUncancelable(g_io);
        defer connect_mu.unlock(g_io);
        if (instance) |r| return r;
        const r = connect() catch |e| std.debug.panic("libbinder_ndk: cannot connect to broker: {s}", .{@errorName(e)});
        instance = r;
        return r;
    }

    fn connect() !*Runtime {
        const io = g_io;
        const socket = getenv("AZK_BINDER_SOCKET") orelse return error.NoSocket;
        const cookie = getenv("AZK_BINDER_COOKIE") orelse "";
        const package = getenv("AZK_BINDER_PACKAGE") orelse "";
        const addr = try net.UnixAddress.init(socket);
        const stream = try addr.connect(io);
        errdefer stream.close(io);

        const read_buf = try alloc.alloc(u8, buffer_len);
        errdefer alloc.free(read_buf);
        const write_buf = try alloc.alloc(u8, buffer_len);
        errdefer alloc.free(write_buf);

        const r = try alloc.create(Runtime);
        r.* = .{ .io = io, .conn = Connection.init(io, stream, read_buf, write_buf) };

        // hello
        var hb = std.ArrayListUnmanaged(u8).empty;
        defer hb.deinit(alloc);
        const hello = Client.Message.Hello{ .cookie_len = @intCast(cookie.len), .package_len = @intCast(package.len) };
        hb.appendSlice(alloc, std.mem.asBytes(&hello)) catch @panic("oom");
        hb.appendSlice(alloc, cookie) catch @panic("oom");
        hb.appendSlice(alloc, package) catch @panic("oom");
        try r.sendFrame(.{ .tag = Client.Message.Tag.hello, .bytes_len = @intCast(hb.items.len) }, hb.items, &.{});

        // welcome (synchronous, before the reader thread starts)
        const client: Client = .{ .in = &r.conn.reader.interface, .out = &r.conn.writer.interface };
        const wh = client.receiveMessage() catch return error.BadHello;
        if (wh.tag != .welcome) return error.BadHello;
        const wbody = r.conn.reader.interface.readAlloc(alloc, wh.bytes_len) catch return error.BadHello;
        defer alloc.free(wbody);
        const welcome = std.mem.bytesToValue(Server.Message.Welcome, wbody[0..@sizeOf(Server.Message.Welcome)]);
        r.peer_id = welcome.peer_id;
        r.pid = welcome.pid;
        r.uid = welcome.uid;

        const thread = try std.Thread.spawn(.{}, readLoop, .{r});
        thread.detach();
        return r;
    }

    const buffer_len = 128 * 1024;

    // ----- framing: header + body through the std.Io Connection reader/writer;
    // descriptors ride via Connection.attachFds/takeFds (SCM_RIGHTS).

    fn sendFrame(r: *Runtime, header_partial: OutHeader, body: []const u8, fds: []const fd_t) !void {
        var header = header_partial;
        header.fd_count = @intCast(fds.len);
        r.write_mu.lockUncancelable(g_io);
        defer r.write_mu.unlock(g_io);
        const w = &r.conn.writer.interface;
        if (fds.len > 0) {
            var files: [Client.limits.max_fds]Io.File = undefined;
            for (fds, 0..) |fd, i| files[i] = .{ .handle = fd, .flags = .{ .nonblocking = false } };
            // attachFds requires an empty buffer; the header is the first byte
            // drained, so the descriptors land on the frame header.
            try r.conn.writer.attachFds(files[0..fds.len]);
        }
        try w.writeStruct(header, .little);
        try w.writeAll(body);
        try w.flush();
    }

    // -------------------------------------------------------------- reader

    fn readLoop(r: *Runtime) void {
        const client: Client = .{ .in = &r.conn.reader.interface, .out = undefined };
        while (true) {
            const header = client.receiveMessage() catch break;
            const body = r.conn.reader.interface.readAlloc(alloc, header.bytes_len) catch break;
            // Descriptors that arrived with this frame (the Connection queues
            // them in arrival order; a frame takes the count it announces).
            var fdbuf: [Client.limits.max_fds]fd_t = undefined;
            var fd_count: usize = 0;
            if (header.fd_count > 0) {
                if (r.conn.reader.queuedFdCount() < header.fd_count) {
                    alloc.free(body);
                    break;
                }
                var files: [Client.limits.max_fds]Io.File = undefined;
                r.conn.reader.takeFds(files[0..header.fd_count]) catch {
                    alloc.free(body);
                    break;
                };
                for (files[0..header.fd_count], 0..) |f, i| {
                    fdbuf[i] = f.handle;
                    fd_count += 1;
                }
            }
            const keep = r.handle(header, body, fdbuf[0..fd_count]);
            alloc.free(body);
            if (!keep) break;
        }
        r.onDisconnected();
    }

    fn handle(r: *Runtime, header: InHeader, body: []const u8, fds: []fd_t) bool {
        switch (header.tag) {
            .transact => {
                const t = std.mem.bytesToValue(Server.Message.Transact, body[0..@sizeOf(Server.Message.Transact)]);
                const rest = body[@sizeOf(Server.Message.Transact)..];
                const parcel = r.inflate(rest, t.object_count, t.data_len, fds) catch return true;
                const in = alloc.create(Incoming) catch @panic("oom");
                in.* = .{ .target = t.target, .code = t.code, .flags = t.flags, .caller_pid = t.caller_pid, .caller_uid = t.caller_uid, .routing_id = header.routing_id, .parcel = parcel };
                if (header.request_id != 0) {
                    // Nested call: hand to the thread parked on that request.
                    r.mu.lockUncancelable(g_io);
                    const call = r.pending.get(header.request_id);
                    r.mu.unlock(g_io);
                    if (call) |c| {
                        c.mu.lockUncancelable(g_io);
                        c.nested.append(alloc, in) catch @panic("oom");
                        c.cv.signal(g_io);
                        c.mu.unlock(g_io);
                        return true;
                    }
                }
                const th = std.Thread.spawn(.{}, dispatch, .{ r, in }) catch {
                    in.parcel.destroy();
                    alloc.destroy(in);
                    return true;
                };
                th.detach();
            },
            .reply => {
                const rep = std.mem.bytesToValue(Client.Message.Reply, body[0..@sizeOf(Client.Message.Reply)]);
                const rest = body[@sizeOf(Client.Message.Reply)..];
                r.mu.lockUncancelable(g_io);
                const call = r.pending.fetchRemove(header.request_id);
                r.mu.unlock(g_io);
                if (call) |kv| {
                    const c = kv.value;
                    const parcel = r.inflate(rest, rep.object_count, rep.data_len, fds) catch AParcel.create();
                    c.mu.lockUncancelable(g_io);
                    c.reply = parcel;
                    c.reply_status = rep.status;
                    c.done = true;
                    c.cv.signal(g_io);
                    c.mu.unlock(g_io);
                } else {
                    for (fds) |fd| if (fd >= 0) closeFd(fd);
                }
            },
            .service_result => {
                const sr = std.mem.bytesToValue(Server.Message.ServiceResult, body[0..@sizeOf(Server.Message.ServiceResult)]);
                const names = body[@sizeOf(Server.Message.ServiceResult)..];
                r.mu.lockUncancelable(g_io);
                const call = r.pending.fetchRemove(header.request_id);
                r.mu.unlock(g_io);
                if (call) |kv| {
                    const c = kv.value;
                    c.mu.lockUncancelable(g_io);
                    c.svc_status = sr.status;
                    c.svc_ref = sr.object;
                    c.svc_names = alloc.dupe(u8, names) catch @panic("oom");
                    c.done = true;
                    c.cv.signal(g_io);
                    c.mu.unlock(g_io);
                }
            },
            .object_dead => {
                const ref = std.mem.bytesToValue(ObjectRef, body[0..@sizeOf(ObjectRef)]);
                r.notifyDeath(ref.value);
            },
            .object_released => {
                const rel = std.mem.bytesToValue(Server.Message.ObjectReleased, body[0..@sizeOf(Server.Message.ObjectReleased)]);
                r.maybeRelease(rel.object.value, rel.uses);
            },
            .property_changed => {}, // NDK has no sysprop-change callback surface
            .protocol_error => return false,
            .shutdown => return false,
            else => {
                for (fds) |fd| if (fd >= 0) closeFd(fd);
            },
        }
        return true;
    }

    fn dispatch(r: *Runtime, in: *Incoming) void {
        defer {
            in.parcel.destroy();
            alloc.destroy(in);
        }
        const oneway = in.flags & FLAG_ONEWAY != 0;
        r.mu.lockUncancelable(g_io);
        const target = if (in.target.kind == .local) r.locals.get(in.target.value) else null;
        r.mu.unlock(g_io);
        var out = AParcel.create();
        defer out.destroy();
        var status = Client.Message.Reply.Status.ok;
        if (target == null or target.?.class == null or target.?.class.?.on_transact == null) {
            status = .failed;
        } else {
            const prev_routing = current_routing;
            const prev_uid = current_caller_uid;
            const prev_pid = current_caller_pid;
            current_routing = in.routing_id;
            current_caller_uid = in.caller_uid;
            // Oneway transactions have no waiting caller; AOSP reports pid 0.
            current_caller_pid = if (oneway) 0 else in.caller_pid;
            const rc = target.?.class.?.on_transact.?(target.?, in.code, in.parcel, out);
            current_routing = prev_routing;
            current_caller_uid = prev_uid;
            current_caller_pid = prev_pid;
            if (rc != STATUS_OK) status = .failed;
        }
        if (!oneway) r.sendReply(in.routing_id, status, out);
    }

    fn sendReply(r: *Runtime, routing_id: u64, status: Client.Message.Reply.Status, out: *AParcel) void {
        var body = std.ArrayListUnmanaged(u8).empty;
        defer body.deinit(alloc);
        var fds = std.ArrayListUnmanaged(fd_t).empty;
        defer fds.deinit(alloc);
        const wire = r.flatten(out, &fds);
        defer alloc.free(wire);
        const data: []const u8 = if (status == .ok) out.data.items else &.{};
        const rep = Client.Message.Reply{
            .status = status,
            .handled = 1,
            .object_count = if (status == .ok) @intCast(wire.len) else 0,
            .data_len = @intCast(data.len),
        };
        body.appendSlice(alloc, std.mem.asBytes(&rep)) catch @panic("oom");
        if (status == .ok) {
            for (wire) |e| body.appendSlice(alloc, std.mem.asBytes(&e)) catch @panic("oom");
            body.appendSlice(alloc, data) catch @panic("oom");
        }
        r.sendFrame(.{ .tag = Client.Message.Tag.reply, .bytes_len = @intCast(body.items.len), .routing_id = routing_id }, body.items, if (status == .ok) fds.items else &.{}) catch {};
    }

    // ------------------------------------------------- object marshalling

    /// Flattens a locally built parcel's side table into wire entries; local
    /// binders are exported, fds are appended to `fds`.
    fn flatten(_: *Runtime, p: *AParcel, fds: *std.ArrayListUnmanaged(fd_t)) []ObjectEntry {
        const out = alloc.alloc(ObjectEntry, p.entries.items.len) catch @panic("oom");
        for (p.entries.items, 0..) |e, i| {
            switch (e.kind) {
                .file_descriptor => {
                    fds.append(alloc, e.fd) catch @panic("oom");
                    out[i] = .{ .offset = e.offset, .kind = .file_descriptor, .value = @intCast(fds.items.len - 1) };
                },
                else => out[i] = .{ .offset = e.offset, .kind = e.kind, .value = e.value },
            }
        }
        return out;
    }

    /// Builds a received parcel from wire bytes; remote refs become proxy ids,
    /// fd ordinals resolve into the frame's descriptors.
    fn inflate(r: *Runtime, rest: []const u8, object_count: u32, data_len: u32, fds: []fd_t) !*AParcel {
        _ = r;
        const entries_len = @as(usize, object_count) * @sizeOf(ObjectEntry);
        if (rest.len != entries_len + data_len) return error.Malformed;
        const p = AParcel.create();
        p.owns_fds = true;
        p.fds.appendSlice(alloc, fds) catch @panic("oom");
        var off: usize = 0;
        while (off < entries_len) : (off += @sizeOf(ObjectEntry)) {
            const e = std.mem.bytesToValue(ObjectEntry, rest[off .. off + @sizeOf(ObjectEntry)]);
            p.entries.append(alloc, .{ .offset = e.offset, .kind = e.kind, .value = e.value }) catch @panic("oom");
        }
        p.data.appendSlice(alloc, rest[entries_len..]) catch @panic("oom");
        return p;
    }

    // ------------------------------------------------------- refcounting

    fn exportLocal(r: *Runtime, b: *AIBinder) u64 {
        r.mu.lockUncancelable(g_io);
        defer r.mu.unlock(g_io);
        const prev = r.export_uses.get(b.id) orelse 0;
        if (prev == 0) {
            // The first export holds one strong ref for the whole export
            // lifetime, so the object cannot be freed by the NDK caller's
            // decStrong while the broker (registry or a remote peer) can still
            // route to it. maybeRelease drops this ref when the export ends.
            _ = b.strong.fetchAdd(1, .monotonic);
            r.locals.put(alloc, b.id, b) catch @panic("oom");
        }
        r.export_uses.put(alloc, b.id, prev + 1) catch @panic("oom");
        return b.id;
    }

    fn proxyFor(r: *Runtime, global_id: u64) *AIBinder {
        r.mu.lockUncancelable(g_io);
        defer r.mu.unlock(g_io);
        if (r.proxies.get(global_id)) |b| {
            _ = b.strong.fetchAdd(1, .monotonic);
            b.deliveries += 1;
            return b;
        }
        const b = alloc.create(AIBinder) catch @panic("oom");
        b.* = .{ .strong = .init(1), .kind = .remote, .id = global_id, .dead = .init(false), .deliveries = 1 };
        r.proxies.put(alloc, global_id, b) catch @panic("oom");
        return b;
    }

    fn releaseProxy(r: *Runtime, b: *AIBinder) void {
        r.mu.lockUncancelable(g_io);
        if (r.proxies.get(b.id) == b) _ = r.proxies.remove(b.id);
        const count = b.deliveries;
        r.mu.unlock(g_io);
        if (count > 0) {
            var body: [@sizeOf(Client.Message.Release)]u8 = undefined;
            const rel = Client.Message.Release{ .object = ObjectRef.remote(b.id), .count = count };
            @memcpy(&body, std.mem.asBytes(&rel));
            r.sendFrame(.{ .tag = Client.Message.Tag.release, .bytes_len = body.len }, &body, &.{}) catch {};
        }
        alloc.destroy(b);
    }

    fn maybeRelease(r: *Runtime, id: u64, uses: u64) void {
        r.mu.lockUncancelable(g_io);
        const drop = (r.export_uses.get(id) orelse 0) == uses;
        var released: ?*AIBinder = null;
        if (drop) {
            _ = r.export_uses.remove(id);
            if (r.locals.fetchRemove(id)) |kv| released = kv.value;
        }
        r.mu.unlock(g_io);
        if (drop) {
            var body: [@sizeOf(ObjectRef)]u8 = undefined;
            @memcpy(&body, std.mem.asBytes(&ObjectRef.local(id)));
            r.sendFrame(.{ .tag = Client.Message.Tag.release_ack, .bytes_len = body.len }, &body, &.{}) catch {};
            // Drop the export's strong ref (exportLocal's first-export incStrong).
            if (released) |b| dropLocal(b);
        }
    }

    /// Releases one strong ref to a local object, running its onDestroy and
    /// freeing it when the last ref goes. Shared by AIBinder_decStrong and the
    /// export-release path.
    fn dropLocal(b: *AIBinder) void {
        const prev = b.strong.fetchSub(1, .acq_rel);
        if (prev != 1) return;
        if (b.class) |c| if (c.on_destroy) |cb| cb(b.user_data);
        alloc.destroy(b);
    }

    fn notifyDeath(r: *Runtime, global_id: u64) void {
        r.mu.lockUncancelable(g_io);
        if (r.proxies.get(global_id)) |b| b.dead.store(true, .monotonic);
        var links = std.ArrayListUnmanaged(DeathLink).empty;
        defer links.deinit(alloc);
        for (r.death_links.items) |l| {
            if (l.global_id == global_id) links.append(alloc, l) catch @panic("oom");
        }
        r.mu.unlock(g_io);
        for (links.items) |l| if (l.recipient.on_died) |cb| cb(l.cookie);
    }

    fn onDisconnected(r: *Runtime) void {
        r.disconnected.store(true, .monotonic);
        var links = std.ArrayListUnmanaged(DeathLink).empty;
        defer links.deinit(alloc);
        r.mu.lockUncancelable(g_io);
        var it = r.pending.valueIterator();
        while (it.next()) |cp| {
            const c = cp.*;
            c.mu.lockUncancelable(g_io);
            c.failed = true;
            c.done = true;
            c.cv.signal(g_io);
            c.mu.unlock(g_io);
        }
        // Every remote object is now unreachable: mark proxies dead so
        // AIBinder_isAlive reports false, and deliver each registered death
        // notification (the broker will send no more object_dead frames).
        var pit = r.proxies.valueIterator();
        while (pit.next()) |bp| bp.*.dead.store(true, .monotonic);
        for (r.death_links.items) |l| links.append(alloc, l) catch @panic("oom");
        r.mu.unlock(g_io);
        for (links.items) |l| if (l.recipient.on_died) |cb| cb(l.cookie);
    }

    // ---------------------------------------------------------- transact

    fn transact(r: *Runtime, target: *AIBinder, code: u32, in: *AParcel, out: ?*AParcel, flags: u32) i32 {
        // A local target is an in-process object (AOSP short-circuits these to
        // a direct call rather than a kernel/broker round-trip).
        if (target.kind == .local) return r.localTransact(target, code, in, out);
        if (target.dead.load(.monotonic) or r.disconnected.load(.monotonic)) return STATUS_DEAD_OBJECT;
        const oneway = flags & FLAG_ONEWAY != 0;
        const request_id: u64 = if (oneway) 0 else r.next_request_id.fetchAdd(1, .monotonic);

        var fds = std.ArrayListUnmanaged(fd_t).empty;
        defer fds.deinit(alloc);
        const wire = r.flatten(in, &fds);
        defer alloc.free(wire);

        var body = std.ArrayListUnmanaged(u8).empty;
        defer body.deinit(alloc);
        const tr = Client.Message.Transact{
            .target = ObjectRef.remote(target.id),
            .code = code,
            .flags = flags,
            .object_count = @intCast(wire.len),
            .data_len = @intCast(in.data.items.len),
        };
        body.appendSlice(alloc, std.mem.asBytes(&tr)) catch @panic("oom");
        for (wire) |e| body.appendSlice(alloc, std.mem.asBytes(&e)) catch @panic("oom");
        body.appendSlice(alloc, in.data.items) catch @panic("oom");

        var call: ?*PendingCall = null;
        if (!oneway) {
            call = alloc.create(PendingCall) catch @panic("oom");
            call.?.* = .{ .kind = .reply };
            r.mu.lockUncancelable(g_io);
            r.pending.put(alloc, request_id, call.?) catch @panic("oom");
            r.mu.unlock(g_io);
        }
        r.sendFrame(.{
            .tag = Client.Message.Tag.transact,
            .bytes_len = @intCast(body.items.len),
            .request_id = request_id,
            .routing_id = current_routing,
        }, body.items, fds.items) catch {
            if (call) |c| {
                r.mu.lockUncancelable(g_io);
                _ = r.pending.remove(request_id);
                r.mu.unlock(g_io);
                alloc.destroy(c);
            }
            return STATUS_DEAD_OBJECT;
        };
        if (oneway) return STATUS_OK;
        return r.awaitReply(call.?, out);
    }

    /// In-process dispatch to a local object (self-call short-circuit). The
    /// caller identity is this process; the reply parcel is left positioned at
    /// the start for the caller to read.
    fn localTransact(r: *Runtime, target: *AIBinder, code: u32, in: *AParcel, out: ?*AParcel) i32 {
        const class = target.class orelse return STATUS_UNEXPECTED_NULL;
        const on_transact = class.on_transact orelse return STATUS_UNEXPECTED_NULL;
        in.pos = 0;
        var scratch = AParcel.create();
        defer scratch.destroy();
        const reply = out orelse scratch;
        const prev_routing = current_routing;
        const prev_uid = current_caller_uid;
        const prev_pid = current_caller_pid;
        current_routing = std.math.maxInt(u64); // nonzero: "handling a transaction"
        current_caller_uid = r.uid;
        current_caller_pid = r.pid;
        const rc = on_transact(target, code, in, reply);
        current_routing = prev_routing;
        current_caller_uid = prev_uid;
        current_caller_pid = prev_pid;
        if (out) |o| o.pos = 0;
        return rc;
    }

    fn awaitReply(r: *Runtime, call: *PendingCall, out: ?*AParcel) i32 {
        defer {
            call.nested.deinit(alloc);
            alloc.destroy(call);
        }
        while (true) {
            call.mu.lockUncancelable(g_io);
            while (!call.done and call.nested.items.len == 0) call.cv.waitUncancelable(g_io, &call.mu);
            const nested: ?*Incoming = if (call.nested.items.len > 0) call.nested.orderedRemove(0) else null;
            const done = call.done and nested == null;
            call.mu.unlock(g_io);
            if (nested) |n| {
                r.dispatch(n);
                continue;
            }
            if (!done) continue;
            if (call.failed) return STATUS_DEAD_OBJECT;
            switch (call.reply_status) {
                .ok => {},
                .dead_object => {
                    if (call.reply) |p| p.destroy();
                    return STATUS_DEAD_OBJECT;
                },
                else => {
                    if (call.reply) |p| p.destroy();
                    return STATUS_FAILED_TRANSACTION;
                },
            }
            if (call.reply) |p| {
                if (out) |o| {
                    o.* = p.*;
                    alloc.destroy(p);
                } else p.destroy();
            }
            return STATUS_OK;
        }
    }

    // ------------------------------------------------------- service ops

    fn serviceRequest(r: *Runtime, tag: Client.Message.Tag, body_extra: []const u8) *PendingCall {
        const request_id = r.next_request_id.fetchAdd(1, .monotonic);
        const call = alloc.create(PendingCall) catch @panic("oom");
        call.* = .{ .kind = .service };
        if (r.disconnected.load(.monotonic)) {
            call.failed = true;
            call.done = true;
            return call;
        }
        r.mu.lockUncancelable(g_io);
        r.pending.put(alloc, request_id, call) catch @panic("oom");
        r.mu.unlock(g_io);
        r.sendFrame(.{ .tag = tag, .bytes_len = @intCast(body_extra.len), .request_id = request_id }, body_extra, &.{}) catch {
            r.mu.lockUncancelable(g_io);
            _ = r.pending.remove(request_id);
            r.mu.unlock(g_io);
            call.failed = true;
            call.done = true;
            return call;
        };
        // Wait, servicing nested transactions like awaitReply.
        while (true) {
            call.mu.lockUncancelable(g_io);
            while (!call.done and call.nested.items.len == 0) call.cv.waitUncancelable(g_io, &call.mu);
            const nested: ?*Incoming = if (call.nested.items.len > 0) call.nested.orderedRemove(0) else null;
            const done = call.done and nested == null;
            call.mu.unlock(g_io);
            if (nested) |n| {
                r.dispatch(n);
                continue;
            }
            if (done) return call;
        }
    }
};


// ================================================================= C exports


export fn AStatus_newOk() callconv(.c) *AStatus {
    return AStatus.create(EX_NONE);
}
export fn AStatus_fromExceptionCode(exception: i32) callconv(.c) *AStatus {
    return AStatus.create(exception);
}
export fn AStatus_fromExceptionCodeWithMessage(exception: i32, message: ?[*:0]const u8) callconv(.c) *AStatus {
    const s = AStatus.create(exception);
    if (message) |m| s.message = dupz(std.mem.span(m));
    return s;
}
export fn AStatus_fromServiceSpecificError(err: i32) callconv(.c) *AStatus {
    const s = AStatus.create(-8); // EX_SERVICE_SPECIFIC
    s.service_specific = err;
    return s;
}
export fn AStatus_fromServiceSpecificErrorWithMessage(err: i32, message: ?[*:0]const u8) callconv(.c) *AStatus {
    const s = AStatus.create(-8);
    s.service_specific = err;
    if (message) |m| s.message = dupz(std.mem.span(m));
    return s;
}
export fn AStatus_fromStatus(status: i32) callconv(.c) *AStatus {
    const s = AStatus.create(if (status == STATUS_OK) EX_NONE else EX_TRANSACTION_FAILED);
    s.status = status;
    s.low_level = true;
    return s;
}
export fn AStatus_isOk(status: *const AStatus) callconv(.c) bool {
    return status.exception == EX_NONE and status.status == STATUS_OK;
}
export fn AStatus_getExceptionCode(status: *const AStatus) callconv(.c) i32 {
    return status.exception;
}
export fn AStatus_getServiceSpecificError(status: *const AStatus) callconv(.c) i32 {
    return status.service_specific;
}
export fn AStatus_getStatus(status: *const AStatus) callconv(.c) i32 {
    return status.status;
}
export fn AStatus_getMessage(status: *const AStatus) callconv(.c) [*:0]const u8 {
    return if (status.message) |m| @ptrCast(m.ptr) else "";
}
export fn AStatus_delete(status: *AStatus) callconv(.c) void {
    status.destroy();
}

// ----- AParcel

export fn AParcel_delete(parcel: *AParcel) callconv(.c) void {
    parcel.destroy();
}
export fn AParcel_setDataPosition(parcel: *const AParcel, position: i32) callconv(.c) i32 {
    if (position < 0) return STATUS_BAD_VALUE;
    const p = @constCast(parcel);
    p.pos = @intCast(position);
    return STATUS_OK;
}
export fn AParcel_getDataPosition(parcel: *const AParcel) callconv(.c) i32 {
    return @intCast(parcel.pos);
}
export fn AParcel_writeInt32(parcel: *AParcel, value: i32) callconv(.c) i32 {
    parcel.writeI32(value);
    return STATUS_OK;
}
export fn AParcel_writeUint32(parcel: *AParcel, value: u32) callconv(.c) i32 {
    parcel.writeI32(@bitCast(value));
    return STATUS_OK;
}
export fn AParcel_writeInt64(parcel: *AParcel, value: i64) callconv(.c) i32 {
    parcel.writeI64(value);
    return STATUS_OK;
}
export fn AParcel_writeUint64(parcel: *AParcel, value: u64) callconv(.c) i32 {
    parcel.writeI64(@bitCast(value));
    return STATUS_OK;
}
export fn AParcel_writeBool(parcel: *AParcel, value: bool) callconv(.c) i32 {
    parcel.writeI32(if (value) 1 else 0);
    return STATUS_OK;
}
export fn AParcel_writeByte(parcel: *AParcel, value: i8) callconv(.c) i32 {
    parcel.writeI32(value);
    return STATUS_OK;
}
export fn AParcel_writeChar(parcel: *AParcel, value: u16) callconv(.c) i32 {
    parcel.writeI32(value);
    return STATUS_OK;
}
export fn AParcel_writeFloat(parcel: *AParcel, value: f32) callconv(.c) i32 {
    parcel.writeI32(@bitCast(value));
    return STATUS_OK;
}
export fn AParcel_writeDouble(parcel: *AParcel, value: f64) callconv(.c) i32 {
    parcel.writeI64(@bitCast(value));
    return STATUS_OK;
}
export fn AParcel_readInt32(parcel: *const AParcel, value: *i32) callconv(.c) i32 {
    value.* = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    return STATUS_OK;
}
const STATUS_NOT_ENOUGH_DATA: i32 = -@as(i32, @intCast(@intFromEnum(E.NODATA)));
export fn AParcel_readUint32(parcel: *const AParcel, value: *u32) callconv(.c) i32 {
    const v = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = @bitCast(v);
    return STATUS_OK;
}
export fn AParcel_readInt64(parcel: *const AParcel, value: *i64) callconv(.c) i32 {
    value.* = @constCast(parcel).readI64() orelse return STATUS_NOT_ENOUGH_DATA;
    return STATUS_OK;
}
export fn AParcel_readUint64(parcel: *const AParcel, value: *u64) callconv(.c) i32 {
    const v = @constCast(parcel).readI64() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = @bitCast(v);
    return STATUS_OK;
}
export fn AParcel_readBool(parcel: *const AParcel, value: *bool) callconv(.c) i32 {
    const v = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = v != 0;
    return STATUS_OK;
}
export fn AParcel_readByte(parcel: *const AParcel, value: *i8) callconv(.c) i32 {
    const v = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = @truncate(v);
    return STATUS_OK;
}
export fn AParcel_readChar(parcel: *const AParcel, value: *u16) callconv(.c) i32 {
    const v = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = @truncate(@as(u32, @bitCast(v)));
    return STATUS_OK;
}
export fn AParcel_readFloat(parcel: *const AParcel, value: *f32) callconv(.c) i32 {
    const v = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = @bitCast(v);
    return STATUS_OK;
}
export fn AParcel_readDouble(parcel: *const AParcel, value: *f64) callconv(.c) i32 {
    const v = @constCast(parcel).readI64() orelse return STATUS_NOT_ENOUGH_DATA;
    value.* = @bitCast(v);
    return STATUS_OK;
}

export fn AParcel_writeString(parcel: *AParcel, string: ?[*]const u8, length: i32) callconv(.c) i32 {
    if (string == null and length < 0) {
        parcel.writeString(&.{}, -1);
        return STATUS_OK;
    }
    const s = if (length <= 0) "" else string.?[0..@intCast(length)];
    parcel.writeString(s, if (length < 0) -1 else @intCast(s.len));
    return STATUS_OK;
}
export fn AParcel_readString(parcel: *const AParcel, string_data: ?*anyopaque, allocator: AParcel_stringAllocator) callconv(.c) i32 {
    const p = @constCast(parcel);
    const utf8 = p.readString() catch |e| switch (e) {
        error.Null => {
            _ = allocator(string_data, -1, null);
            return STATUS_OK;
        },
        error.Malformed => return STATUS_NOT_ENOUGH_DATA,
    };
    defer alloc.free(utf8);
    var buffer: ?[*]u8 = null;
    // The NDK contract: `length` includes the NUL terminator, and the standard
    // std::string allocator resizes to length-1. Pass len+1 and write the NUL.
    if (!allocator(string_data, @intCast(utf8.len + 1), &buffer)) return STATUS_NO_MEMORY;
    if (buffer) |b| {
        @memcpy(b[0..utf8.len], utf8);
        b[utf8.len] = 0;
    }
    return STATUS_OK;
}
const STATUS_NO_MEMORY: i32 = -@as(i32, @intCast(@intFromEnum(E.NOMEM)));
const AParcel_stringAllocator = *const fn (?*anyopaque, i32, ?*?[*]u8) callconv(.c) bool;

export fn AParcel_writeStrongBinder(parcel: *AParcel, binder: ?*AIBinder) callconv(.c) i32 {
    const off: u32 = @intCast(parcel.pos);
    if (binder) |b| {
        parcel.writeI32(1);
        if (b.kind == .local) {
            const id = Runtime.get().exportLocal(b);
            parcel.entries.append(alloc, .{ .offset = off, .kind = .binder_local, .value = id }) catch @panic("oom");
        } else {
            parcel.entries.append(alloc, .{ .offset = off, .kind = .binder_remote, .value = b.id }) catch @panic("oom");
        }
    } else {
        parcel.writeI32(0);
        parcel.entries.append(alloc, .{ .offset = off, .kind = .binder_null, .value = 0 }) catch @panic("oom");
    }
    return STATUS_OK;
}
export fn AParcel_readStrongBinder(parcel: *const AParcel, binder: *?*AIBinder) callconv(.c) i32 {
    const p = @constCast(parcel);
    const off: u32 = @intCast(p.pos);
    _ = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    for (p.entries.items) |e| {
        if (e.offset != off) continue;
        switch (e.kind) {
            .binder_null => binder.* = null,
            .binder_remote => binder.* = Runtime.get().proxyFor(e.value),
            .binder_local => {
                Runtime.get().mu.lockUncancelable(g_io);
                const local = Runtime.get().locals.get(e.value);
                Runtime.get().mu.unlock(g_io);
                if (local) |l| {
                    _ = l.strong.fetchAdd(1, .monotonic);
                    binder.* = l;
                } else binder.* = null;
            },
            else => binder.* = null,
        }
        return STATUS_OK;
    }
    binder.* = null;
    return STATUS_OK;
}

export fn AParcel_writeParcelFileDescriptor(parcel: *AParcel, fd: i32) callconv(.c) i32 {
    const off: u32 = @intCast(parcel.pos);
    if (fd < 0) {
        parcel.writeI32(0);
        return STATUS_OK;
    }
    const dup_rc = std.os.linux.dup(fd);
    if (std.os.linux.errno(dup_rc) != .SUCCESS) return STATUS_BAD_VALUE;
    const dup: fd_t = @intCast(dup_rc);
    parcel.writeI32(1);
    parcel.entries.append(alloc, .{ .offset = off, .kind = .file_descriptor, .value = 0, .fd = dup }) catch @panic("oom");
    return STATUS_OK;
}
export fn AParcel_readParcelFileDescriptor(parcel: *const AParcel, fd: *i32) callconv(.c) i32 {
    const p = @constCast(parcel);
    const off: u32 = @intCast(p.pos);
    const has = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    if (has == 0) {
        fd.* = -1;
        return STATUS_OK;
    }
    for (p.entries.items) |e| {
        if (e.offset == off and e.kind == .file_descriptor) {
            const ordinal: usize = @intCast(e.value);
            if (ordinal < p.fds.items.len) {
                fd.* = p.fds.items[ordinal];
                p.fds.items[ordinal] = -1; // ownership transfers to the caller
                return STATUS_OK;
            }
        }
    }
    fd.* = -1;
    return STATUS_OK;
}

export fn AParcel_writeStatusHeader(parcel: *AParcel, status: *const AStatus) callconv(.c) i32 {
    // A low-level status (AStatus_fromStatus) is reported to the caller and
    // written as nothing (AOSP AParcel_writeStatusHeader contract).
    if (status.low_level and status.status != STATUS_OK) return status.status;
    // AOSP status-header layout: exception code, then (for non-EX_NONE) a
    // message string and, for service-specific, the error code.
    parcel.writeI32(status.exception);
    if (status.exception != EX_NONE) {
        parcel.writeString(if (status.message) |m| m else "", @intCast(if (status.message) |m| m.len else 0));
        if (status.exception == -8) parcel.writeI32(status.service_specific);
    }
    return STATUS_OK;
}
export fn AParcel_readStatusHeader(parcel: *const AParcel, status: *?*AStatus) callconv(.c) i32 {
    const p = @constCast(parcel);
    const ex = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    const s = AStatus.create(ex);
    if (ex != EX_NONE) {
        if (p.readString()) |m| {
            defer alloc.free(m);
            s.message = dupz(m);
        } else |_| {}
        if (ex == -8) s.service_specific = p.readI32() orelse 0;
    }
    status.* = s;
    return STATUS_OK;
}

// ----- AIBinder_Class / AIBinder

export fn AIBinder_Class_define(
    interfaceDescriptor: [*:0]const u8,
    onCreate: ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque,
    onDestroy: ?*const fn (?*anyopaque) callconv(.c) void,
    onTransact: ?*const fn (*AIBinder, u32, *const AParcel, *AParcel) callconv(.c) i32,
) callconv(.c) *AIBinder_Class {
    const c = alloc.create(AIBinder_Class) catch @panic("oom");
    c.* = .{
        .descriptor = alloc.dupe(u8, std.mem.span(interfaceDescriptor)) catch @panic("oom"),
        .on_create = onCreate,
        .on_destroy = onDestroy,
        .on_transact = onTransact,
    };
    return c;
}
export fn AIBinder_new(clazz: *const AIBinder_Class, args: ?*anyopaque) callconv(.c) *AIBinder {
    const user = if (clazz.on_create) |cb| cb(args) else args;
    return AIBinder.createLocal(@constCast(clazz), user);
}
export fn AIBinder_getClass(binder: *AIBinder) callconv(.c) ?*AIBinder_Class {
    return binder.class;
}
export fn AIBinder_associateClass(binder: *AIBinder, clazz: *const AIBinder_Class) callconv(.c) bool {
    if (binder.class == null) {
        binder.class = @constCast(clazz);
        return true;
    }
    return binder.class == clazz or std.mem.eql(u8, binder.class.?.descriptor, clazz.descriptor);
}
export fn AIBinder_getUserData(binder: *AIBinder) callconv(.c) ?*anyopaque {
    return binder.user_data;
}
export fn AIBinder_isRemote(binder: *const AIBinder) callconv(.c) bool {
    return binder.kind == .remote;
}
export fn AIBinder_isAlive(binder: *const AIBinder) callconv(.c) bool {
    return !binder.dead.load(.monotonic);
}
export fn AIBinder_incStrong(binder: *AIBinder) callconv(.c) void {
    _ = binder.strong.fetchAdd(1, .monotonic);
}
export fn AIBinder_decStrong(binder: *AIBinder) callconv(.c) void {
    const prev = binder.strong.fetchSub(1, .acq_rel);
    if (prev != 1) return;
    switch (binder.kind) {
        .remote => Runtime.get().releaseProxy(binder),
        .local => {
            if (binder.class) |c| if (c.on_destroy) |cb| cb(binder.user_data);
            alloc.destroy(binder);
        },
    }
}
export fn AIBinder_debugGetRefCount(binder: *AIBinder) callconv(.c) i32 {
    return @intCast(binder.strong.load(.monotonic));
}
export fn AIBinder_prepareTransaction(binder: *AIBinder, in: *?*AParcel) callconv(.c) i32 {
    _ = binder;
    in.* = AParcel.create();
    return STATUS_OK;
}
export fn AIBinder_transact(binder: *AIBinder, code: u32, in: *?*AParcel, out: *?*AParcel, flags: u32) callconv(.c) i32 {
    const in_parcel = in.* orelse return STATUS_UNEXPECTED_NULL;
    defer {
        in_parcel.destroy();
        in.* = null;
    }
    const reply = if (flags & FLAG_ONEWAY == 0) AParcel.create() else null;
    const rc = Runtime.get().transact(binder, code, in_parcel, reply, flags);
    out.* = reply;
    return rc;
}
export fn AIBinder_ping(binder: *AIBinder) callconv(.c) i32 {
    _ = binder;
    return STATUS_OK;
}
export fn AIBinder_getCallingUid() callconv(.c) i32 {
    // Inside an onTransact callback, the incoming caller's uid; otherwise this
    // process's own (routing==0 means not servicing a transaction).
    if (Runtime.current_routing != 0) return @intCast(Runtime.current_caller_uid);
    return @intCast(Runtime.get().uid);
}
export fn AIBinder_getCallingPid() callconv(.c) i32 {
    if (Runtime.current_routing != 0) return Runtime.current_caller_pid;
    return Runtime.get().pid;
}
export fn AIBinder_isHandlingTransaction() callconv(.c) bool {
    return Runtime.current_routing != 0;
}

export fn AIBinder_DeathRecipient_new(onBinderDied: ?*const fn (?*anyopaque) callconv(.c) void) callconv(.c) *AIBinder_DeathRecipient {
    const d = alloc.create(AIBinder_DeathRecipient) catch @panic("oom");
    d.* = .{ .on_died = onBinderDied };
    return d;
}
export fn AIBinder_DeathRecipient_setOnUnlinked(recipient: *AIBinder_DeathRecipient, onUnlinked: ?*const fn (?*anyopaque) callconv(.c) void) callconv(.c) void {
    recipient.on_unlinked = onUnlinked;
}
export fn AIBinder_DeathRecipient_delete(recipient: *AIBinder_DeathRecipient) callconv(.c) void {
    // Remove every link this recipient still holds so a later object_dead
    // notification cannot dereference it, and run onUnlinked per removed link.
    const r = Runtime.get();
    var unlinked: usize = 0;
    r.mu.lockUncancelable(g_io);
    var i: usize = 0;
    while (i < r.death_links.items.len) {
        if (r.death_links.items[i].recipient == recipient) {
            _ = r.death_links.orderedRemove(i);
            unlinked += 1;
        } else i += 1;
    }
    r.mu.unlock(g_io);
    if (recipient.on_unlinked) |cb| {
        var k: usize = 0;
        while (k < unlinked) : (k += 1) cb(null);
    }
    alloc.destroy(recipient);
}
export fn AIBinder_linkToDeath(binder: *AIBinder, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque) callconv(.c) i32 {
    if (binder.kind != .remote) return STATUS_INVALID_OPERATION;
    const r = Runtime.get();
    r.mu.lockUncancelable(g_io);
    r.death_links.append(alloc, .{ .global_id = binder.id, .recipient = recipient, .cookie = cookie }) catch @panic("oom");
    r.mu.unlock(g_io);
    var body: [@sizeOf(ObjectRef)]u8 = undefined;
    @memcpy(&body, std.mem.asBytes(&ObjectRef.remote(binder.id)));
    r.sendFrame(.{ .tag = Client.Message.Tag.link_death, .bytes_len = body.len }, &body, &.{}) catch {};
    return STATUS_OK;
}
const STATUS_INVALID_OPERATION: i32 = -@as(i32, @intCast(@intFromEnum(E.NOSYS)));
export fn AIBinder_unlinkToDeath(binder: *AIBinder, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque) callconv(.c) i32 {
    const r = Runtime.get();
    r.mu.lockUncancelable(g_io);
    var i: usize = 0;
    var found = false;
    while (i < r.death_links.items.len) {
        const l = r.death_links.items[i];
        if (l.global_id == binder.id and l.recipient == recipient and l.cookie == cookie) {
            _ = r.death_links.orderedRemove(i);
            found = true;
            break;
        }
        i += 1;
    }
    r.mu.unlock(g_io);
    if (!found) return STATUS_NAME_NOT_FOUND;
    var body: [@sizeOf(ObjectRef)]u8 = undefined;
    @memcpy(&body, std.mem.asBytes(&ObjectRef.remote(binder.id)));
    r.sendFrame(.{ .tag = Client.Message.Tag.unlink_death, .bytes_len = body.len }, &body, &.{}) catch {};
    if (recipient.on_unlinked) |cb| cb(cookie);
    return STATUS_OK;
}

// ----- AServiceManager

export fn AServiceManager_addService(binder: *AIBinder, instance: [*:0]const u8) callconv(.c) i32 {
    const name = std.mem.span(instance);
    const r = Runtime.get();
    const id = r.exportLocal(binder);
    var body = std.ArrayListUnmanaged(u8).empty;
    defer body.deinit(alloc);
    const reg = Client.Message.RegisterService{ .object = ObjectRef.local(id), .name_len = @intCast(name.len) };
    body.appendSlice(alloc, std.mem.asBytes(&reg)) catch @panic("oom");
    body.appendSlice(alloc, name) catch @panic("oom");
    const call = r.serviceRequest(.register_service, body.items);
    defer {
        if (call.svc_names.len > 0) alloc.free(call.svc_names);
        call.nested.deinit(alloc);
        alloc.destroy(call);
    }
    if (call.failed) return EX_TRANSACTION_FAILED;
    return switch (call.svc_status) {
        .ok => EX_NONE,
        .denied => -1, // EX_SECURITY
        else => EX_ILLEGAL_STATE,
    };
}
const EX_ILLEGAL_STATE: i32 = -5;

fn lookup(instance: [*:0]const u8, wait: bool) ?*AIBinder {
    const name = std.mem.span(instance);
    const r = Runtime.get();
    var body = std.ArrayListUnmanaged(u8).empty;
    defer body.deinit(alloc);
    const lk = Client.Message.LookupService{ .flags = .{ .wait = wait }, .name_len = @intCast(name.len) };
    body.appendSlice(alloc, std.mem.asBytes(&lk)) catch @panic("oom");
    body.appendSlice(alloc, name) catch @panic("oom");
    const call = r.serviceRequest(.lookup_service, body.items);
    defer {
        if (call.svc_names.len > 0) alloc.free(call.svc_names);
        call.nested.deinit(alloc);
        alloc.destroy(call);
    }
    if (call.failed or call.svc_status != .ok or call.svc_ref.kind == .null) return null;
    // A service this peer registered itself resolves to a local ref (the broker
    // short-circuits ownership); returning a remote proxy would later send a
    // release for an object we never held remotely and trip a protocol error.
    if (call.svc_ref.kind == .local) {
        r.mu.lockUncancelable(g_io);
        const local = r.locals.get(call.svc_ref.value);
        if (local) |b| _ = b.strong.fetchAdd(1, .monotonic);
        r.mu.unlock(g_io);
        return local;
    }
    return r.proxyFor(call.svc_ref.value);
}
export fn AServiceManager_checkService(instance: [*:0]const u8) callconv(.c) ?*AIBinder {
    return lookup(instance, false);
}
export fn AServiceManager_getService(instance: [*:0]const u8) callconv(.c) ?*AIBinder {
    // Deprecated poll-and-return API: a single non-blocking lookup rather than
    // the broker's indefinite waiter, so an absent service cannot hang startup
    // forever (use AServiceManager_waitForService when a wait is intended).
    return lookup(instance, false);
}
export fn AServiceManager_waitForService(instance: [*:0]const u8) callconv(.c) ?*AIBinder {
    return lookup(instance, true);
}
export fn AServiceManager_isDeclared(instance: [*:0]const u8) callconv(.c) bool {
    const b = lookup(instance, false) orelse return false;
    AIBinder_decStrong(b);
    return true;
}

// ----- ABinderProcess (no dedicated binder thread pool; the reader thread and
// per-transaction workers handle incoming calls)

export fn ABinderProcess_startThreadPool() callconv(.c) void {
    _ = Runtime.get();
}
export fn ABinderProcess_setThreadPoolMaxThreadCount(count: u32) callconv(.c) bool {
    _ = count;
    return true;
}
export fn ABinderProcess_isThreadPoolStarted() callconv(.c) bool {
    return Runtime.instance != null;
}
var park_mu: Io.Mutex = .init;
var park_cv: Io.Condition = .init;
export fn ABinderProcess_joinThreadPool() callconv(.c) void {
    // The reader thread services incoming calls; a joiner parks forever.
    _ = Runtime.get();
    park_mu.lockUncancelable(g_io);
    while (true) park_cv.waitUncancelable(g_io, &park_mu);
}
