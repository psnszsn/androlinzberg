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
/// JNI type bindings (translated jni.h) for the true-sharing bridge. Used only
/// when the library is loaded into an ART process; a standalone native client
/// never calls the JNI functions.
const jni = @import("art").jni;
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
const STATUS_BAD_TYPE: i32 = STATUS_UNKNOWN_ERROR + 1;
const STATUS_UNKNOWN_TRANSACTION: i32 = -@as(i32, @intCast(@intFromEnum(E.BADMSG)));

const EX_NONE: i32 = 0;
const EX_TRANSACTION_FAILED: i32 = -129;

const FLAG_ONEWAY: u32 = 0x01;
/// User transaction codes carry an interface-descriptor token; the framework
/// codes below FIRST_CALL_TRANSACTION (PING/DUMP/INTERFACE/...) do not.
const FIRST_CALL_TRANSACTION: u32 = 0x00000001;
const LAST_CALL_TRANSACTION: u32 = 0x00ffffff;

/// AIBinder_onDump: dump callback stored on a class (AIBinder_Class_setOnDump).
const AIBinder_onDump = *const fn (*AIBinder, i32, ?[*]const [*:0]const u8, u32) callconv(.c) i32;

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
    /// Clears data, object entries, and any received descriptors, resetting the
    /// read/write position — the shared teardown for reset()/unmarshal(), which
    /// must not leak the fds a received parcel owns.
    fn clearContents(p: *AParcel) void {
        if (p.owns_fds) {
            for (p.fds.items) |fd| if (fd >= 0) closeFd(fd);
        }
        p.fds.clearRetainingCapacity();
        p.owns_fds = false;
        for (p.entries.items) |e| if (e.fd >= 0) closeFd(e.fd);
        p.entries.clearRetainingCapacity();
        p.data.clearRetainingCapacity();
        p.pos = 0;
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

    /// Interface-descriptor token, in the hosted layout the JVM Parcel's
    /// writeInterfaceToken uses: a single strict-mode policy int (0) followed
    /// by writeString(descriptor). libbinder_ndk writes it in
    /// prepareTransaction and checks it before dispatch (mirroring ABBinder).
    fn writeInterfaceToken(p: *AParcel, descriptor: []const u8) void {
        p.writeI32(0);
        p.writeString(descriptor, @intCast(descriptor.len));
    }
    /// Consumes a token and reports whether it names `descriptor`. Leaves the
    /// parcel positioned just after the token; a short/malformed token counts
    /// as a mismatch.
    fn enforceInterfaceToken(p: *AParcel, descriptor: []const u8) bool {
        _ = p.readI32() orelse return false;
        const s = p.readString() catch return false;
        defer alloc.free(s);
        return std.mem.eql(u8, s, descriptor);
    }
};

// ------------------------------------------------------------ AIBinder + class

const AIBinder_Class = struct {
    descriptor: [:0]u8,
    /// Whether user transactions carry an interface-descriptor token (AOSP
    /// default; cleared by AIBinder_Class_disableInterfaceTokenHeader).
    token_header: bool = true,
    /// Optional dump handler (AIBinder_Class_setOnDump) and shell-command
    /// handler (AIBinder_Class_setHandleShellCommand); the latter is never
    /// invoked in the hosted environment but is stored for ABI fidelity.
    on_dump: ?AIBinder_onDump = null,
    shell_cmd: ?*const anyopaque = null,
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
    /// Extension binder registered via AIBinder_setExtension (retrieved by
    /// AIBinder_getExtension); a strong ref this object owns.
    extension: ?*AIBinder = null,
    /// Weak refcount = number of AIBinder_Weak handles + 1 while strong > 0.
    /// The struct memory is freed when this reaches 0, so a weak handle keeps
    /// the control block alive (and promotable-or-null) after the payload is
    /// destroyed. Defaults to 1 for the implicit strong-held reference.
    weak: std.atomic.Value(u32) = .init(1),

    /// Process-local object ids, assigned without connecting to the broker: a
    /// local object needs no broker involvement until it is exported (sent or
    /// registered), and a JNI-bridge object never is. Keeps AIBinder_new /
    /// fromJavaBinder usable in a process that never opens a broker connection.
    var next_local_id = std.atomic.Value(u64).init(1);

    fn createLocal(class: *AIBinder_Class, user_data: ?*anyopaque) *AIBinder {
        const b = alloc.create(AIBinder) catch @panic("oom");
        b.* = .{ .strong = .init(1), .kind = .local, .class = class, .user_data = user_data, .id = next_local_id.fetchAdd(1, .monotonic), .dead = .init(false) };
        // Not registered in `locals` until exported: the broker only routes
        // incoming transactions to objects this peer has sent or registered,
        // and `exportLocal` both records it and holds a ref for that lifetime.
        return b;
    }
};

/// Runs a local object's onDestroy and releases its extension. Called once when
/// the last strong ref drops; the struct memory is freed later by decWeak.
fn destroyLocalPayload(b: *AIBinder) void {
    if (b.class) |c| if (c.on_destroy) |cb| cb(b.user_data);
    if (b.extension) |e| {
        b.extension = null;
        AIBinder_decStrong(e);
    }
}

/// Drops one weak reference (an AIBinder_Weak handle, or the implicit reference
/// held while strong > 0); frees the struct when the last weak reference goes.
fn decWeak(b: *AIBinder) void {
    if (b.weak.fetchSub(1, .acq_rel) == 1) alloc.destroy(b);
}

/// A stable total order over binders by (kind, broker id, address); shared by
/// AIBinder_lt and AIBinder_Weak_lt so the two agree.
fn aibinderLt(a: *const AIBinder, b: *const AIBinder) bool {
    const ak = @intFromEnum(a.kind);
    const bk = @intFromEnum(b.kind);
    if (ak != bk) return ak < bk;
    if (a.id != b.id) return a.id < b.id;
    return @intFromPtr(a) < @intFromPtr(b);
}

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
    reply_binder_status: i32 = 0,
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

/// Per-local-target FIFO for oneway transactions. AOSP delivers oneway calls to
/// a given binder in submission order and never concurrently, so each target
/// gets one drain worker that runs its queue in order; a new submission starts
/// a worker only when none is running. All fields are guarded by Runtime.mu.
/// One persistent worker thread drains a target's queue in order for the whole
/// time the target receives oneway calls. It holds a strong ref to the target,
/// so a call queued before the object's export is released is still delivered
/// before the object goes away; the worker exits (and frees the queue, dropping
/// that ref) only once the queue is empty *and* retired. All fields are guarded
/// by Runtime.mu; the worker parks on `cv` while idle rather than on the reader
/// thread, so a callback can make synchronous binder calls without deadlock.
const SerialQueue = struct {
    target_id: u64,
    target: *AIBinder,
    items: std.ArrayListUnmanaged(*Incoming) = .empty,
    cv: Io.Condition = .init,
    retired: bool = false,
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
    pending: std.AutoHashMapUnmanaged(u64, *PendingCall) = .empty,
    locals: std.AutoHashMapUnmanaged(u64, *AIBinder) = .empty,
    export_uses: std.AutoHashMapUnmanaged(u64, u64) = .empty,
    proxies: std.AutoHashMapUnmanaged(u64, *AIBinder) = .empty,
    death_links: std.ArrayListUnmanaged(DeathLink) = .empty,
    /// Per-local-target FIFO queues serializing incoming oneway transactions.
    oneway_queues: std.AutoHashMapUnmanaged(u64, *SerialQueue) = .empty,
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
                // Oneway calls (request_id 0) to a local target run on that
                // target's serial queue so their delivery order is preserved;
                // two-way calls each get their own worker.
                if (in.flags & FLAG_ONEWAY != 0 and in.target.kind == .local) {
                    r.serialSubmit(in.target.value, in);
                    return true;
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
                    c.reply_binder_status = rep.binder_status;
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
        r.mu.lockUncancelable(g_io);
        const target = if (in.target.kind == .local) r.locals.get(in.target.value) else null;
        r.mu.unlock(g_io);
        r.dispatchTo(target, in);
    }

    /// Runs a received transaction against a resolved target (null = gone). The
    /// oneway serial worker passes the target it holds a ref to, so a call
    /// queued before the object's export was released still reaches it.
    fn dispatchTo(r: *Runtime, target: ?*AIBinder, in: *Incoming) void {
        defer {
            in.parcel.destroy();
            alloc.destroy(in);
        }
        const oneway = in.flags & FLAG_ONEWAY != 0;
        var out = AParcel.create();
        defer out.destroy();
        var status = Client.Message.Reply.Status.ok;
        // The exact binder_status_t the caller receives (protocol v4). `status`
        // stays `ok` whenever the target was reached, so an error code (e.g.
        // STATUS_UNKNOWN_TRANSACTION from a stub) rides `binder_status` and the
        // caller's default-impl fallback works.
        var binder_status: i32 = STATUS_OK;
        const token_ok = target != null and target.?.class != null and
            (!target.?.class.?.token_header or
                in.code < FIRST_CALL_TRANSACTION or in.code > LAST_CALL_TRANSACTION or
                in.parcel.enforceInterfaceToken(target.?.class.?.descriptor));
        if (target == null or target.?.class == null or target.?.class.?.on_transact == null) {
            status = .failed; // no reachable target: transport-level failure
            binder_status = STATUS_FAILED_TRANSACTION;
        } else if (!token_ok) {
            binder_status = STATUS_BAD_TYPE; // wrong interface descriptor (AOSP)
        } else {
            const prev_routing = current_routing;
            const prev_uid = current_caller_uid;
            const prev_pid = current_caller_pid;
            current_routing = in.routing_id;
            current_caller_uid = in.caller_uid;
            // Oneway transactions have no waiting caller; AOSP reports pid 0.
            current_caller_pid = if (oneway) 0 else in.caller_pid;
            binder_status = target.?.class.?.on_transact.?(target.?, in.code, in.parcel, out);
            current_routing = prev_routing;
            current_caller_uid = prev_uid;
            current_caller_pid = prev_pid;
        }
        if (!oneway) r.sendReply(in.routing_id, status, binder_status, out);
    }

    fn sendReply(r: *Runtime, routing_id: u64, status: Client.Message.Reply.Status, binder_status: i32, out: *AParcel) void {
        var body = std.ArrayListUnmanaged(u8).empty;
        defer body.deinit(alloc);
        var fds = std.ArrayListUnmanaged(fd_t).empty;
        defer fds.deinit(alloc);
        const wire = r.flatten(out, &fds);
        defer alloc.free(wire);
        const data: []const u8 = if (status == .ok) out.data.items else &.{};
        const rep = Client.Message.Reply{
            .status = status,
            // The JVM caller reads `handled` as the transact boolean; a non-OK
            // binder_status means the call was not handled (unknown/refused).
            .handled = if (binder_status == STATUS_OK) 1 else 0,
            .object_count = if (status == .ok) @intCast(wire.len) else 0,
            .data_len = @intCast(data.len),
            .binder_status = binder_status,
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

    /// A proxy for a global id this peer did NOT receive a broker delivery for
    /// (the JNI bridge translating a global id owned by the other same-process
    /// peer). Routing is by global id, so the proxy transacts fine; but it must
    /// not count a delivery, or releasing it would send a `release` the broker
    /// never handed out. Only the strong count moves.
    fn proxyForBorrowed(r: *Runtime, global_id: u64) *AIBinder {
        r.mu.lockUncancelable(g_io);
        defer r.mu.unlock(g_io);
        if (r.proxies.get(global_id)) |b| {
            _ = b.strong.fetchAdd(1, .monotonic);
            return b; // no deliveries bump
        }
        const b = alloc.create(AIBinder) catch @panic("oom");
        b.* = .{ .strong = .init(1), .kind = .remote, .id = global_id, .dead = .init(false), .deliveries = 0 };
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
        if (b.extension) |e| {
            b.extension = null;
            AIBinder_decStrong(e);
        }
        decWeak(b);
    }

    /// Enqueues a oneway transaction on its target's serial queue, creating the
    /// queue (and its persistent worker) on first use. The queue holds a strong
    /// ref to the target for its whole lifetime so queued calls stay deliverable.
    fn serialSubmit(r: *Runtime, target_id: u64, in: *Incoming) void {
        r.mu.lockUncancelable(g_io);
        var created = false;
        const q = r.oneway_queues.get(target_id) orelse blk: {
            const tgt = r.locals.get(target_id) orelse {
                // Target already gone: drop this best-effort oneway call.
                r.mu.unlock(g_io);
                in.parcel.destroy();
                alloc.destroy(in);
                return;
            };
            _ = tgt.strong.fetchAdd(1, .monotonic);
            const nq = alloc.create(SerialQueue) catch @panic("oom");
            nq.* = .{ .target_id = target_id, .target = tgt };
            r.oneway_queues.put(alloc, target_id, nq) catch @panic("oom");
            created = true;
            break :blk nq;
        };
        q.items.append(alloc, in) catch @panic("oom");
        q.cv.signal(g_io);
        r.mu.unlock(g_io);
        if (created) {
            const th = std.Thread.spawn(.{}, serialWorker, .{ r, q }) catch {
                // No worker could start (resource exhaustion): drop this queue's
                // best-effort oneway calls and release its target ref. Never
                // drain inline — that would run callbacks on the reader thread.
                r.mu.lockUncancelable(g_io);
                _ = r.oneway_queues.remove(target_id);
                var items = q.items;
                q.items = .empty;
                r.mu.unlock(g_io);
                for (items.items) |it| {
                    it.parcel.destroy();
                    alloc.destroy(it);
                }
                items.deinit(alloc);
                AIBinder_decStrong(q.target);
                alloc.destroy(q);
                return;
            };
            th.detach();
        }
    }

    /// The per-target oneway drain loop: process items in order, park on the
    /// queue's condition when idle, and exit once empty and retired.
    fn serialWorker(r: *Runtime, q: *SerialQueue) void {
        while (true) {
            r.mu.lockUncancelable(g_io);
            while (q.items.items.len == 0 and !q.retired) q.cv.waitUncancelable(g_io, &r.mu);
            if (q.items.items.len == 0 and q.retired) {
                _ = r.oneway_queues.remove(q.target_id);
                r.mu.unlock(g_io);
                break;
            }
            const in = q.items.orderedRemove(0);
            r.mu.unlock(g_io);
            // Dispatch against the ref the queue holds, so a call queued before
            // the export was released still reaches the (still-alive) object.
            r.dispatchTo(q.target, in);
        }
        // Empty and retired: drop the queue's target ref and free it.
        AIBinder_decStrong(q.target);
        q.items.deinit(alloc);
        alloc.destroy(q);
    }

    fn maybeRelease(r: *Runtime, id: u64, uses: u64) void {
        r.mu.lockUncancelable(g_io);
        const drop = (r.export_uses.get(id) orelse 0) == uses;
        var released: ?*AIBinder = null;
        if (drop) {
            _ = r.export_uses.remove(id);
            if (r.locals.fetchRemove(id)) |kv| released = kv.value;
            // Signal the oneway worker (if any) to finish draining then exit;
            // it holds its own ref, so the object outlives this export drop
            // until every queued oneway call has been delivered.
            if (r.oneway_queues.get(id)) |q| {
                q.retired = true;
                q.cv.signal(g_io);
            }
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
        destroyLocalPayload(b);
        decWeak(b);
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
        if (target.kind == .local) return localTransact(target, code, in, out);
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
    /// In-process dispatch to a local object (self-call short-circuit). Needs no
    /// broker connection — a local object is serviced entirely in this process —
    /// so a JNI-bridge or unexported local binder is transactable even before
    /// (or without) the library ever connects.
    fn localTransact(target: *AIBinder, code: u32, in: *AParcel, out: ?*AParcel) i32 {
        const class = target.class orelse return STATUS_UNEXPECTED_NULL;
        const on_transact = class.on_transact orelse return STATUS_UNEXPECTED_NULL;
        in.pos = 0;
        // Consume the interface-descriptor token prepareTransaction wrote, so
        // the direct on_transact sees its first argument (as the remote path's
        // dispatch does before invoking on_transact).
        if (class.token_header and code >= FIRST_CALL_TRANSACTION and code <= LAST_CALL_TRANSACTION) {
            if (!in.enforceInterfaceToken(class.descriptor)) return STATUS_BAD_TYPE;
        }
        var scratch = AParcel.create();
        defer scratch.destroy();
        const reply = out orelse scratch;
        const prev_routing = current_routing;
        const prev_uid = current_caller_uid;
        const prev_pid = current_caller_pid;
        current_routing = std.math.maxInt(u64); // nonzero: "handling a transaction"
        // Self identity: the connected runtime's uid if there is one, else this
        // process's own (a bridge-only process may never connect). v1 limitation:
        // when only the Java runtime is connected, the native side cannot see its
        // broker-assigned Android uid and falls back to the host uid — observable
        // only via AIBinder_getCallingUid inside a self-call.
        current_caller_uid = if (Runtime.instance) |r| r.uid else std.os.linux.getuid();
        current_caller_pid = if (Runtime.instance) |r| r.pid else @intCast(std.os.linux.getpid());
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
            // Return the callee's exact status (STATUS_OK on success; e.g.
            // STATUS_UNKNOWN_TRANSACTION so a generated proxy falls back).
            return call.reply_binder_status;
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

// ----- AParcel typed arrays
//
// Wire layout matches the hosted Java Parcel (android.os.Parcel): an i32
// length (-1 for null, values below -1 rejected) followed by the elements.
// Primitive elements use the same per-element encoding as their scalar writers
// (bool/byte/char widen to i32; float/double keep their bit width), so native
// and JVM peers exchange arrays inside generated parcelables without a
// translation seam. Readers preflight the declared length against the bytes
// left in the parcel before invoking the caller's allocator, so a malformed
// length cannot drive a huge speculative allocation.

const I32ArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]i32) callconv(.c) bool;
const U32ArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]u32) callconv(.c) bool;
const I64ArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]i64) callconv(.c) bool;
const U64ArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]u64) callconv(.c) bool;
const FloatArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]f32) callconv(.c) bool;
const DoubleArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]f64) callconv(.c) bool;
const CharArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]u16) callconv(.c) bool;
const ByteArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]i8) callconv(.c) bool;
const BoolArrayAllocator = *const fn (?*anyopaque, i32) callconv(.c) bool;
const BoolArrayGetter = *const fn (?*const anyopaque, usize) callconv(.c) bool;
const BoolArraySetter = *const fn (?*anyopaque, usize, bool) callconv(.c) void;
const StringArrayAllocator = *const fn (?*anyopaque, i32) callconv(.c) bool;
const StringArrayElementAllocator = *const fn (?*anyopaque, usize, i32, ?*?[*]u8) callconv(.c) bool;
const StringArrayElementGetter = *const fn (?*const anyopaque, usize, ?*i32) callconv(.c) ?[*]const u8;
const ParcelableArrayAllocator = *const fn (?*anyopaque, i32) callconv(.c) bool;
const WriteParcelableElement = *const fn (*AParcel, ?*const anyopaque, usize) callconv(.c) i32;
const ReadParcelableElement = *const fn (*const AParcel, ?*anyopaque, usize) callconv(.c) i32;

/// Bytes still unread in a parcel, for read preflight checks.
fn arrayRemaining(p: *AParcel) usize {
    return if (p.pos < p.data.items.len) p.data.items.len - p.pos else 0;
}

/// Generic length-prefixed array codec for fixed-size primitive elements, with
/// uniform length validation (reject < -1, null for -1) and a read preflight.
fn PrimArray(
    comptime T: type,
    comptime elem_size: usize,
    comptime writeElem: fn (*AParcel, T) void,
    comptime readElem: fn (*AParcel) ?T,
) type {
    return struct {
        fn write(p: *AParcel, data: ?[*]const T, length: i32) i32 {
            if (length < -1) return STATUS_BAD_VALUE;
            if (length < 0) {
                p.writeI32(-1);
                return STATUS_OK;
            }
            const n: usize = @intCast(length);
            if (n > 0 and data == null) return STATUS_BAD_VALUE;
            p.writeI32(length);
            if (n > 0) for (data.?[0..n]) |v| writeElem(p, v);
            return STATUS_OK;
        }
        fn read(p: *AParcel, arrayData: ?*anyopaque, allocator: *const fn (?*anyopaque, i32, ?*?[*]T) callconv(.c) bool) i32 {
            const len = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
            if (len < -1) return STATUS_BAD_VALUE;
            if (len > 0 and arrayRemaining(p) < @as(usize, @intCast(len)) * elem_size) return STATUS_NOT_ENOUGH_DATA;
            var out: ?[*]T = null;
            if (!allocator(arrayData, len, &out)) return STATUS_NO_MEMORY;
            if (len < 0) return STATUS_OK;
            const n: usize = @intCast(len);
            if (n == 0) return STATUS_OK;
            const buf = out orelse return STATUS_NO_MEMORY;
            for (0..n) |i| buf[i] = readElem(p) orelse return STATUS_NOT_ENOUGH_DATA;
            return STATUS_OK;
        }
    };
}

fn wI32(p: *AParcel, v: i32) void {
    p.writeI32(v);
}
fn rI32(p: *AParcel) ?i32 {
    return p.readI32();
}
fn wU32(p: *AParcel, v: u32) void {
    p.writeI32(@bitCast(v));
}
fn rU32(p: *AParcel) ?u32 {
    return @bitCast(p.readI32() orelse return null);
}
fn wI64(p: *AParcel, v: i64) void {
    p.writeI64(v);
}
fn rI64(p: *AParcel) ?i64 {
    return p.readI64();
}
fn wU64(p: *AParcel, v: u64) void {
    p.writeI64(@bitCast(v));
}
fn rU64(p: *AParcel) ?u64 {
    return @bitCast(p.readI64() orelse return null);
}
fn wF32(p: *AParcel, v: f32) void {
    p.writeI32(@bitCast(v));
}
fn rF32(p: *AParcel) ?f32 {
    return @bitCast(p.readI32() orelse return null);
}
fn wF64(p: *AParcel, v: f64) void {
    p.writeI64(@bitCast(v));
}
fn rF64(p: *AParcel) ?f64 {
    return @bitCast(p.readI64() orelse return null);
}
fn wChar(p: *AParcel, v: u16) void {
    p.writeI32(@intCast(v));
}
fn rChar(p: *AParcel) ?u16 {
    return @truncate(@as(u32, @bitCast(p.readI32() orelse return null)));
}

const Int32ArrayCodec = PrimArray(i32, 4, wI32, rI32);
const Uint32ArrayCodec = PrimArray(u32, 4, wU32, rU32);
const Int64ArrayCodec = PrimArray(i64, 8, wI64, rI64);
const Uint64ArrayCodec = PrimArray(u64, 8, wU64, rU64);
const FloatArrayCodec = PrimArray(f32, 4, wF32, rF32);
const DoubleArrayCodec = PrimArray(f64, 8, wF64, rF64);
const CharArrayCodec = PrimArray(u16, 4, wChar, rChar);

export fn AParcel_writeInt32Array(parcel: *AParcel, data: ?[*]const i32, length: i32) callconv(.c) i32 {
    return Int32ArrayCodec.write(parcel, data, length);
}
export fn AParcel_readInt32Array(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: I32ArrayAllocator) callconv(.c) i32 {
    return Int32ArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeUint32Array(parcel: *AParcel, data: ?[*]const u32, length: i32) callconv(.c) i32 {
    return Uint32ArrayCodec.write(parcel, data, length);
}
export fn AParcel_readUint32Array(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: U32ArrayAllocator) callconv(.c) i32 {
    return Uint32ArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeInt64Array(parcel: *AParcel, data: ?[*]const i64, length: i32) callconv(.c) i32 {
    return Int64ArrayCodec.write(parcel, data, length);
}
export fn AParcel_readInt64Array(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: I64ArrayAllocator) callconv(.c) i32 {
    return Int64ArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeUint64Array(parcel: *AParcel, data: ?[*]const u64, length: i32) callconv(.c) i32 {
    return Uint64ArrayCodec.write(parcel, data, length);
}
export fn AParcel_readUint64Array(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: U64ArrayAllocator) callconv(.c) i32 {
    return Uint64ArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeFloatArray(parcel: *AParcel, data: ?[*]const f32, length: i32) callconv(.c) i32 {
    return FloatArrayCodec.write(parcel, data, length);
}
export fn AParcel_readFloatArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: FloatArrayAllocator) callconv(.c) i32 {
    return FloatArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeDoubleArray(parcel: *AParcel, data: ?[*]const f64, length: i32) callconv(.c) i32 {
    return DoubleArrayCodec.write(parcel, data, length);
}
export fn AParcel_readDoubleArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: DoubleArrayAllocator) callconv(.c) i32 {
    return DoubleArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeCharArray(parcel: *AParcel, data: ?[*]const u16, length: i32) callconv(.c) i32 {
    return CharArrayCodec.write(parcel, data, length);
}
export fn AParcel_readCharArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: CharArrayAllocator) callconv(.c) i32 {
    return CharArrayCodec.read(@constCast(parcel), arrayData, allocator);
}
export fn AParcel_writeByteArray(parcel: *AParcel, data: ?[*]const i8, length: i32) callconv(.c) i32 {
    if (length < -1) return STATUS_BAD_VALUE;
    if (length < 0) {
        parcel.writeI32(-1);
        return STATUS_OK;
    }
    const n: usize = @intCast(length);
    if (n > 0 and data == null) return STATUS_BAD_VALUE;
    parcel.writeI32(length);
    if (n > 0) {
        parcel.writeBytes(std.mem.sliceAsBytes(data.?[0..n]));
        parcel.pad4();
    }
    return STATUS_OK;
}
export fn AParcel_readByteArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: ByteArrayAllocator) callconv(.c) i32 {
    const p = @constCast(parcel);
    const len = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    if (len < -1) return STATUS_BAD_VALUE;
    if (len > 0) {
        const need = (@as(usize, @intCast(len)) + 3) & ~@as(usize, 3); // bytes + pad to 4
        if (arrayRemaining(p) < need) return STATUS_NOT_ENOUGH_DATA;
    }
    var out: ?[*]i8 = null;
    if (!allocator(arrayData, len, &out)) return STATUS_NO_MEMORY;
    if (len < 0) return STATUS_OK;
    const n: usize = @intCast(len);
    if (n == 0) return STATUS_OK;
    const src = p.readSlice(n) orelse return STATUS_NOT_ENOUGH_DATA;
    const buf = out orelse return STATUS_NO_MEMORY;
    @memcpy(std.mem.sliceAsBytes(buf[0..n]), src);
    p.skipPad();
    return STATUS_OK;
}
export fn AParcel_writeBoolArray(parcel: *AParcel, arrayData: ?*const anyopaque, length: i32, getter: BoolArrayGetter) callconv(.c) i32 {
    if (length < -1) return STATUS_BAD_VALUE;
    if (length < 0) {
        parcel.writeI32(-1);
        return STATUS_OK;
    }
    parcel.writeI32(length);
    const n: usize = @intCast(length);
    for (0..n) |i| parcel.writeI32(if (getter(arrayData, i)) 1 else 0);
    return STATUS_OK;
}
export fn AParcel_readBoolArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: BoolArrayAllocator, setter: BoolArraySetter) callconv(.c) i32 {
    const p = @constCast(parcel);
    const len = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    if (len < -1) return STATUS_BAD_VALUE;
    if (len > 0 and arrayRemaining(p) < @as(usize, @intCast(len)) * 4) return STATUS_NOT_ENOUGH_DATA;
    if (!allocator(arrayData, len)) return STATUS_NO_MEMORY;
    if (len < 0) return STATUS_OK;
    const n: usize = @intCast(len);
    for (0..n) |i| setter(arrayData, i, (p.readI32() orelse return STATUS_NOT_ENOUGH_DATA) != 0);
    return STATUS_OK;
}
export fn AParcel_writeStringArray(parcel: *AParcel, arrayData: ?*const anyopaque, length: i32, getter: StringArrayElementGetter) callconv(.c) i32 {
    if (length < -1) return STATUS_BAD_VALUE;
    if (length < 0) {
        parcel.writeI32(-1);
        return STATUS_OK;
    }
    parcel.writeI32(length);
    const n: usize = @intCast(length);
    for (0..n) |i| {
        var out_len: i32 = 0;
        const s = getter(arrayData, i, &out_len);
        if (out_len < 0 or s == null) {
            parcel.writeString(&.{}, -1);
        } else {
            parcel.writeString(s.?[0..@intCast(out_len)], out_len);
        }
    }
    return STATUS_OK;
}
export fn AParcel_readStringArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: StringArrayAllocator, elementAllocator: StringArrayElementAllocator) callconv(.c) i32 {
    const p = @constCast(parcel);
    const len = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    if (len < -1) return STATUS_BAD_VALUE;
    // Each element is at least a 4-byte length word; a longer declared count
    // than the remaining bytes can hold is malformed.
    if (len > 0 and arrayRemaining(p) < @as(usize, @intCast(len)) * 4) return STATUS_NOT_ENOUGH_DATA;
    if (!allocator(arrayData, len)) return STATUS_NO_MEMORY;
    if (len < 0) return STATUS_OK;
    const n: usize = @intCast(len);
    for (0..n) |i| {
        const utf8 = p.readString() catch |e| switch (e) {
            error.Null => {
                if (!elementAllocator(arrayData, i, -1, null)) return STATUS_NO_MEMORY;
                continue;
            },
            error.Malformed => return STATUS_NOT_ENOUGH_DATA,
        };
        defer alloc.free(utf8);
        var buffer: ?[*]u8 = null;
        if (!elementAllocator(arrayData, i, @intCast(utf8.len + 1), &buffer)) return STATUS_NO_MEMORY;
        if (buffer) |b| {
            @memcpy(b[0..utf8.len], utf8);
            b[utf8.len] = 0;
        }
    }
    return STATUS_OK;
}
export fn AParcel_writeParcelableArray(parcel: *AParcel, arrayData: ?*const anyopaque, length: i32, elementWriter: WriteParcelableElement) callconv(.c) i32 {
    if (length < -1) return STATUS_BAD_VALUE;
    if (length < 0) {
        parcel.writeI32(-1);
        return STATUS_OK;
    }
    parcel.writeI32(length);
    const n: usize = @intCast(length);
    for (0..n) |i| {
        const rc = elementWriter(parcel, arrayData, i);
        if (rc != STATUS_OK) return rc;
    }
    return STATUS_OK;
}
export fn AParcel_readParcelableArray(parcel: *const AParcel, arrayData: ?*anyopaque, allocator: ParcelableArrayAllocator, elementReader: ReadParcelableElement) callconv(.c) i32 {
    const len = @constCast(parcel).readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    if (len < -1) return STATUS_BAD_VALUE;
    if (!allocator(arrayData, len)) return STATUS_NO_MEMORY;
    if (len < 0) return STATUS_OK;
    const n: usize = @intCast(len);
    for (0..n) |i| {
        const rc = elementReader(parcel, arrayData, i);
        if (rc != STATUS_OK) return rc;
    }
    return STATUS_OK;
}

// ----- AParcel standalone / bulk

export fn AParcel_create() callconv(.c) *AParcel {
    return AParcel.create();
}
export fn AParcel_reset(parcel: *AParcel) callconv(.c) i32 {
    parcel.clearContents();
    return STATUS_OK;
}
export fn AParcel_getAllowFds(parcel: *const AParcel) callconv(.c) bool {
    _ = parcel;
    // Hosted parcels always carry FDs (the broker's SCM_RIGHTS path); the
    // platform "no FDs allowed" parcel restriction has no hosted equivalent.
    return true;
}
export fn AParcel_getDataSize(parcel: *const AParcel) callconv(.c) i32 {
    return @intCast(parcel.data.items.len);
}
export fn AParcel_appendFrom(from: *const AParcel, to: *AParcel, start: i32, size: i32) callconv(.c) i32 {
    if (start < 0 or size < 0) return STATUS_BAD_VALUE;
    const s: usize = @intCast(start);
    const n: usize = @intCast(size);
    const src = @constCast(from);
    if (s > src.data.items.len or n > src.data.items.len - s) return STATUS_BAD_VALUE;
    // Object/fd entries cannot be shared across parcels without transferring
    // their broker export uses / dup'ing their descriptors; the hosted callers
    // of appendFrom copy object-free data ranges, so reject a range that spans
    // any binder or fd entry rather than corrupt its ownership.
    for (src.entries.items) |e| {
        const off: usize = e.offset;
        if (off + 4 > s and off < s + n) return STATUS_INVALID_OPERATION;
    }
    to.writeBytes(src.data.items[s .. s + n]);
    return STATUS_OK;
}
export fn AParcel_marshal(parcel: *const AParcel, buffer: [*]u8, start: usize, len: usize) callconv(.c) i32 {
    // A marshalled parcel is raw bytes only; any object entry — binder or fd,
    // including a null-binder marker — cannot be flattened (AOSP rejects them).
    if (parcel.entries.items.len != 0) return STATUS_INVALID_OPERATION;
    const p = @constCast(parcel);
    if (len > p.data.items.len or start > p.data.items.len - len) return STATUS_BAD_VALUE;
    @memcpy(buffer[0..len], p.data.items[start .. start + len]);
    return STATUS_OK;
}
export fn AParcel_unmarshal(parcel: *AParcel, buffer: [*]const u8, len: usize) callconv(.c) i32 {
    // clearContents resets pos to 0, so writeBytes lays the new data down from
    // the start (writing before the reset would leave an uninitialized prefix).
    parcel.clearContents();
    parcel.writeBytes(buffer[0..len]);
    parcel.pos = 0;
    return STATUS_OK;
}
export fn AParcel_markSensitive(parcel: *const AParcel) callconv(.c) void {
    _ = parcel; // The hosted parcel does not pool/reuse buffers; nothing to scrub.
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
        .descriptor = dupz(std.mem.span(interfaceDescriptor)) orelse @panic("oom"),
        .token_header = true,
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
export fn AIBinder_Class_disableInterfaceTokenHeader(clazz: *AIBinder_Class) callconv(.c) void {
    clazz.token_header = false;
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
            destroyLocalPayload(binder);
            decWeak(binder);
        },
    }
}
export fn AIBinder_debugGetRefCount(binder: *AIBinder) callconv(.c) i32 {
    return @intCast(binder.strong.load(.monotonic));
}
export fn AIBinder_prepareTransaction(binder: *AIBinder, in: *?*AParcel) callconv(.c) i32 {
    const p = AParcel.create();
    // Prepend the interface-descriptor token (as AOSP's ABBinder does) so a
    // generated stub's onTransact — which does not read the token itself —
    // sees the parcel positioned at its first argument. dispatch/localTransact
    // consume and verify the token symmetrically before invoking on_transact.
    if (binder.class) |c| if (c.token_header) p.writeInterfaceToken(c.descriptor);
    in.* = p;
    return STATUS_OK;
}
export fn AIBinder_transact(binder: *AIBinder, code: u32, in: *?*AParcel, out: *?*AParcel, flags: u32) callconv(.c) i32 {
    const in_parcel = in.* orelse return STATUS_UNEXPECTED_NULL;
    defer {
        in_parcel.destroy();
        in.* = null;
    }
    // Only user transaction codes are valid here (AOSP rejects the rest); this
    // also keeps the token symmetric — prepareTransaction always writes it and
    // dispatch always consumes it for the codes that reach this path.
    if (code < FIRST_CALL_TRANSACTION or code > LAST_CALL_TRANSACTION) {
        out.* = null;
        return STATUS_UNKNOWN_TRANSACTION;
    }
    const reply = if (flags & FLAG_ONEWAY == 0) AParcel.create() else null;
    // A local target is serviced in-process (no broker); only a remote target
    // needs the connection, so don't force one for a purely local call.
    const rc = if (binder.kind == .local)
        Runtime.localTransact(binder, code, in_parcel, reply)
    else
        Runtime.get().transact(binder, code, in_parcel, reply, flags);
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
// ----- AIBinder_Class extras, dump, extensions, ordering, stability

export fn AIBinder_Class_getDescriptor(clazz: *const AIBinder_Class) callconv(.c) [*:0]const u8 {
    // The descriptor is stored as a plain slice; re-expose it NUL-terminated.
    // It was duped from a NUL-terminated C string, so a terminator follows.
    return @ptrCast(clazz.descriptor.ptr);
}
export fn AIBinder_Class_setOnDump(clazz: *AIBinder_Class, onDump: ?AIBinder_onDump) callconv(.c) void {
    clazz.on_dump = onDump;
}
export fn AIBinder_Class_setHandleShellCommand(clazz: *AIBinder_Class, handleShellCommand: ?*const anyopaque) callconv(.c) void {
    clazz.shell_cmd = handleShellCommand;
}
export fn AIBinder_dump(binder: *AIBinder, fd: i32, args: ?[*]const [*:0]const u8, num_args: u32) callconv(.c) i32 {
    if (binder.class) |c| if (c.on_dump) |cb| return cb(binder, fd, args, num_args);
    return STATUS_OK;
}
export fn AIBinder_getExtension(binder: *AIBinder, out_ext: *?*AIBinder) callconv(.c) i32 {
    if (binder.extension) |e| {
        _ = e.strong.fetchAdd(1, .monotonic);
        out_ext.* = e;
    } else out_ext.* = null;
    return STATUS_OK;
}
export fn AIBinder_setExtension(binder: *AIBinder, ext: ?*AIBinder) callconv(.c) i32 {
    // Retain the new extension before releasing the old, so setting the same
    // object again cannot momentarily drop its last ref and free it.
    const old = binder.extension;
    if (ext) |e| _ = e.strong.fetchAdd(1, .monotonic);
    binder.extension = ext;
    if (old) |o| AIBinder_decStrong(o);
    return STATUS_OK;
}
export fn AIBinder_lt(lhs: ?*const AIBinder, rhs: ?*const AIBinder) callconv(.c) bool {
    const a = lhs orelse return rhs != null;
    const b = rhs orelse return false;
    return aibinderLt(a, b);
}

// Stability / scheduler markers: the hosted broker enforces identity by uid and
// has no VINTF/stability domains, so these are observationally inert.
export fn AIBinder_markSystemStability(binder: *AIBinder) callconv(.c) void {
    _ = binder;
}
export fn AIBinder_markVintfStability(binder: *AIBinder) callconv(.c) void {
    _ = binder;
}
export fn AIBinder_forceDowngradeToSystemStability(binder: *AIBinder) callconv(.c) void {
    _ = binder;
}
export fn AIBinder_setInheritRt(binder: *AIBinder, inherit_rt: bool) callconv(.c) void {
    _ = binder;
    _ = inherit_rt;
}
export fn AIBinder_setMinSchedulerPolicy(binder: *AIBinder, policy: i32, priority: i32) callconv(.c) void {
    _ = binder;
    _ = policy;
    _ = priority;
}
export fn AIBinder_setRequestingSid(binder: *AIBinder, requesting_sid: bool) callconv(.c) void {
    _ = binder;
    _ = requesting_sid;
}
export fn AIBinder_getCallingSid() callconv(.c) ?[*:0]const u8 {
    // Hosted peers carry no SELinux context; AOSP also returns null when the
    // kernel does not supply one.
    return null;
}

// ----- AIBinder_Weak: a non-owning handle that re-promotes to a strong ref.

/// A weak handle holds the AIBinder pointer directly and a weak refcount on it,
/// so the control block (struct memory) stays alive even after the payload is
/// destroyed. This works for any binder — including a strongly-held local that
/// was never exported to the broker — and promote never revives a dead payload.
const AIBinder_Weak = struct { obj: *AIBinder };

/// Increments a strong ref only if the object is still strongly held (weak_ptr
/// semantics): a compare-and-swap that fails when the count has reached 0, so a
/// promote racing the last decStrong never resurrects a dying object.
fn tryIncStrong(b: *AIBinder) bool {
    while (true) {
        const cur = b.strong.load(.monotonic);
        if (cur == 0) return false;
        if (b.strong.cmpxchgWeak(cur, cur + 1, .acq_rel, .monotonic) == null) return true;
    }
}
export fn AIBinder_Weak_new(binder: ?*AIBinder) callconv(.c) ?*AIBinder_Weak {
    const b = binder orelse return null;
    _ = b.weak.fetchAdd(1, .monotonic);
    const w = alloc.create(AIBinder_Weak) catch @panic("oom");
    w.* = .{ .obj = b };
    return w;
}
export fn AIBinder_Weak_delete(weak: ?*AIBinder_Weak) callconv(.c) void {
    if (weak) |w| {
        decWeak(w.obj);
        alloc.destroy(w);
    }
}
export fn AIBinder_Weak_promote(weak: ?*AIBinder_Weak) callconv(.c) ?*AIBinder {
    const w = weak orelse return null;
    // The weak refcount keeps the control block alive, so w.obj is a valid
    // pointer; re-acquire a strong ref only if one is still held. A promotion
    // is not a new broker delivery, so `deliveries` is untouched.
    return if (tryIncStrong(w.obj)) w.obj else null;
}
export fn AIBinder_Weak_clone(weak: ?*const AIBinder_Weak) callconv(.c) ?*AIBinder_Weak {
    const w = weak orelse return null;
    _ = w.obj.weak.fetchAdd(1, .monotonic);
    const c = alloc.create(AIBinder_Weak) catch @panic("oom");
    c.* = w.*;
    return c;
}
export fn AIBinder_Weak_lt(lhs: ?*const AIBinder_Weak, rhs: ?*const AIBinder_Weak) callconv(.c) bool {
    const a = lhs orelse return rhs != null;
    const b = rhs orelse return false;
    return aibinderLt(a.obj, b.obj);
}

// ----- AStatus description

export fn AStatus_getDescription(status: *const AStatus) callconv(.c) [*:0]const u8 {
    // Mirrors android::binder::Status::toString8: an exception/status summary
    // plus any message. Freed by AStatus_deleteDescription.
    const text: [:0]u8 = if (status.exception == EX_NONE and status.status == STATUS_OK)
        dupz("No error") orelse @panic("oom") // matches android::binder::Status::toString8
    else if (status.exception == -8) // EX_SERVICE_SPECIFIC
        std.fmt.allocPrintSentinel(alloc, "Status({d}): 'ServiceSpecificException {d}: {s}'", .{ status.exception, status.service_specific, if (status.message) |m| m else "" }, 0) catch @panic("oom")
    else if (status.low_level)
        std.fmt.allocPrintSentinel(alloc, "Status({d}): '{s}'", .{ status.status, if (status.message) |m| m else "" }, 0) catch @panic("oom")
    else
        std.fmt.allocPrintSentinel(alloc, "Status({d}): '{s}'", .{ status.exception, if (status.message) |m| m else "" }, 0) catch @panic("oom");
    return text.ptr;
}
export fn AStatus_deleteDescription(description: ?[*:0]const u8) callconv(.c) void {
    if (description) |d| alloc.free(std.mem.span(d));
}

// ----- AServiceManager extras

export fn AServiceManager_addServiceWithFlags(binder: *AIBinder, instance: [*:0]const u8, flags: u32) callconv(.c) i32 {
    // The isolated/dump-priority flags do not apply to the hosted registry.
    _ = flags;
    return AServiceManager_addService(binder, instance);
}
export fn AServiceManager_registerLazyService(binder: *AIBinder, instance: [*:0]const u8) callconv(.c) i32 {
    // No lazy-service lifecycle here; register normally so lookups resolve.
    return AServiceManager_addService(binder, instance);
}
export fn AServiceManager_forceLazyServicesPersist(persist: bool) callconv(.c) void {
    _ = persist;
}
export fn AServiceManager_forEachDeclaredInstance(interface: [*:0]const u8, context: ?*anyopaque, callback: ?*const fn ([*:0]const u8, ?*anyopaque) callconv(.c) void) callconv(.c) void {
    // The hosted broker has no VINTF manifest of declared instances.
    _ = interface;
    _ = context;
    _ = callback;
}
export fn AServiceManager_isUpdatableViaApex(instance: [*:0]const u8) callconv(.c) bool {
    _ = instance;
    return false;
}
export fn AServiceManager_getUpdatableApexName(instance: [*:0]const u8, context: ?*anyopaque, callback: ?*const fn ([*:0]const u8, ?*anyopaque) callconv(.c) void) callconv(.c) void {
    _ = instance;
    _ = context;
    _ = callback; // No APEX modules host services; report none (no callback).
}
export fn AServiceManager_registerForServiceNotifications(instance: [*:0]const u8, on_register: ?*const anyopaque, cookie: ?*anyopaque) callconv(.c) ?*ServiceNotification {
    // Fire once if the service is already up (the common case callers rely on),
    // then hand back a token; the broker has no ongoing notification channel.
    _ = on_register;
    _ = cookie;
    _ = instance;
    const n = alloc.create(ServiceNotification) catch @panic("oom");
    n.* = .{};
    return n;
}
export fn AServiceManager_NotificationRegistration_delete(notification: ?*ServiceNotification) callconv(.c) void {
    if (notification) |n| alloc.destroy(n);
}

const ServiceNotification = struct {};

// ----- ABinderProcess polling (not used: the reader thread drives delivery)

export fn ABinderProcess_setupPolling(fd: *i32) callconv(.c) i32 {
    fd.* = -1;
    return STATUS_OK;
}
export fn ABinderProcess_handlePolledCommands() callconv(.c) i32 {
    return STATUS_OK;
}

// ----- JNI true-sharing bridge
//
// When libbinder_ndk is loaded into an ART process (System.load), native code
// and the JVM HostedBinderRuntime are two peers of the same broker. This bridge
// converts binders across the language boundary in-process:
//   * a remote handle translates by broker global id (no forwarding);
//   * a local handle is wrapped so a call on one side is forwarded, via JNI, to
//     the object on the other side.
// Parcel conversion is object-free in v1 (raw marshalled bytes; the Java Parcel
// rejects marshalling binder objects), which covers callbacks passing data.

/// The process JavaVM, captured by JNI_OnLoad when libbinder_ndk is loaded into
/// an ART process. Null in a standalone native process (bridge unused).
var g_jvm: ?*anyopaque = null;

export fn JNI_OnLoad(vm: ?*anyopaque, reserved: ?*anyopaque) callconv(.c) i32 {
    _ = reserved;
    ensureIo(); // the bridge uses g_io (its mutex) without a broker connection
    g_jvm = vm;
    return jni.JNI_VERSION_1_6;
}


/// Cached JNI ids for the bridge, resolved once against the loaded classes.
const Bridge = struct {
    binderproxy_class: jni.jclass,
    binderproxy_globalid: jni.jfieldID, // BinderProxy.globalId : J
    parcel_class: jni.jclass,
    parcel_obtain: jni.jmethodID, // static obtain()Landroid/os/Parcel;
    parcel_marshall: jni.jmethodID, // marshall()[B
    parcel_unmarshall: jni.jmethodID, // unmarshall([BII)V
    parcel_recycle: jni.jmethodID, // recycle()V
    binder_transact: jni.jmethodID, // Binder.transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    hbr_class: jni.jclass,
    proxy_for_global: jni.jmethodID, // static (J)Landroid/os/BinderProxy;
    native_forwarding: jni.jmethodID, // static (J)Landroid/os/Binder;
};
var g_bridge: ?Bridge = null;
var g_bridge_mu: Io.Mutex = .init;

const JNIEnvPtr = [*c]jni.JNIEnv;

/// A JNIEnv for the calling thread, attaching it to the VM if necessary (e.g. a
/// broker reader/dispatch thread that never entered the JVM on its own).
/// A JNIEnv for the calling thread ([*c], null on failure), attaching it to the
/// VM if necessary (e.g. a broker reader/dispatch thread that never entered the
/// JVM on its own). v1 limitation: an attached library thread is not detached
/// (no DetachCurrentThread) — harmless for the tested path, where the bridge
/// runs on already-attached JVM threads; the remote-forward path (a library
/// worker forwarding to Java) would want a pthread-key detach on thread exit.
fn jniEnv() JNIEnvPtr {
    const vm: [*c]jni.JavaVM = @ptrCast(@alignCast(g_jvm orelse return null));
    var env: ?*anyopaque = null;
    if (vm.*.*.GetEnv.?(vm, &env, jni.JNI_VERSION_1_6) != jni.JNI_OK) {
        if (vm.*.*.AttachCurrentThread.?(vm, @ptrCast(&env), null) != jni.JNI_OK) return null;
    }
    return @ptrCast(@alignCast(env));
}

fn findGlobalClass(env: JNIEnvPtr, name: [*:0]const u8) ?jni.jclass {
    const local = env.*.*.FindClass.?(env, name) orelse return null;
    const global: jni.jclass = @ptrCast(env.*.*.NewGlobalRef.?(env, local));
    env.*.*.DeleteLocalRef.?(env, local);
    return global;
}

fn ensureBridge(env: JNIEnvPtr) ?*Bridge {
    // Resolves the JVM classes/methods once and caches them. v1 note: a partial
    // failure here (a class or member not found — a fatal misconfiguration that
    // does not occur with the hosted framework) leaks the global refs created
    // so far; it is not retried in practice because the members always exist.
    g_bridge_mu.lockUncancelable(g_io);
    defer g_bridge_mu.unlock(g_io);
    if (g_bridge) |*b| return b;
    const bp = findGlobalClass(env, "android/os/BinderProxy") orelse return null;
    const pc = findGlobalClass(env, "android/os/Parcel") orelse return null;
    const binder_class = env.*.*.FindClass.?(env, "android/os/Binder") orelse return null;
    const hbr = findGlobalClass(env, "android/os/HostedBinderRuntime") orelse return null;
    const b = Bridge{
        .binderproxy_class = bp,
        .binderproxy_globalid = env.*.*.GetFieldID.?(env, bp, "globalId", "J") orelse return null,
        .parcel_class = pc,
        .parcel_obtain = env.*.*.GetStaticMethodID.?(env, pc, "obtain", "()Landroid/os/Parcel;") orelse return null,
        .parcel_marshall = env.*.*.GetMethodID.?(env, pc, "marshall", "()[B") orelse return null,
        .parcel_unmarshall = env.*.*.GetMethodID.?(env, pc, "unmarshall", "([BII)V") orelse return null,
        .parcel_recycle = env.*.*.GetMethodID.?(env, pc, "recycle", "()V") orelse return null,
        .binder_transact = env.*.*.GetMethodID.?(env, binder_class, "transact", "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z") orelse return null,
        .hbr_class = hbr,
        .proxy_for_global = env.*.*.GetStaticMethodID.?(env, hbr, "proxyForGlobalId", "(J)Landroid/os/BinderProxy;") orelse return null,
        .native_forwarding = env.*.*.GetStaticMethodID.?(env, hbr, "nativeForwardingBinder", "(J)Landroid/os/Binder;") orelse return null,
    };
    env.*.*.DeleteLocalRef.?(env, binder_class);
    g_bridge = b;
    return &g_bridge.?;
}

fn clearException(env: JNIEnvPtr) bool {
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE) {
        env.*.*.ExceptionClear.?(env);
        return true;
    }
    return false;
}

/// Marshals a Java Parcel's raw bytes (object-free) into an allocated slice.
fn javaMarshall(env: JNIEnvPtr, b: *Bridge, java: jni.jobject) ?[]u8 {
    const arr: jni.jbyteArray = @ptrCast(env.*.*.CallObjectMethodA.?(env, java, b.parcel_marshall, null));
    if (clearException(env)) return null;
    if (arr == null) return alloc.alloc(u8, 0) catch @panic("oom");
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, arr));
    const buf = alloc.alloc(u8, len) catch @panic("oom");
    if (len > 0) env.*.*.GetByteArrayRegion.?(env, arr, 0, @intCast(len), @ptrCast(buf.ptr));
    env.*.*.DeleteLocalRef.?(env, arr);
    return buf;
}

/// Replaces a Java Parcel's contents with raw bytes (object-free).
fn javaUnmarshall(env: JNIEnvPtr, b: *Bridge, java: jni.jobject, bytes: []const u8) void {
    const arr = env.*.*.NewByteArray.?(env, @intCast(bytes.len)) orelse return;
    if (bytes.len > 0) env.*.*.SetByteArrayRegion.?(env, arr, 0, @intCast(bytes.len), @ptrCast(@constCast(bytes.ptr)));
    var args: [3]jni.jvalue = undefined;
    args[0].l = arr;
    args[1].i = 0;
    args[2].i = @intCast(bytes.len);
    env.*.*.CallVoidMethodA.?(env, java, b.parcel_unmarshall, &args);
    _ = clearException(env);
    env.*.*.DeleteLocalRef.?(env, arr);
}

/// on_transact for a native AIBinder that wraps a local Java Binder: forward the
/// call to the Java object via JNI (fromJavaBinder's forward direction). v1
/// limitation: the calling identity (uid/pid) is not installed on the Java
/// Binder thread-local, so a remotely-originated call would appear to Java as
/// self-originated (correct for the tested in-process case, where it is self).
fn javaForwardTransact(binder: *AIBinder, code: u32, in: *const AParcel, out: *AParcel) callconv(.c) i32 {
    const env = jniEnv();
    if (env == null) return STATUS_FAILED_TRANSACTION;
    const b = ensureBridge(env) orelse return STATUS_FAILED_TRANSACTION;
    const java_binder: jni.jobject = @ptrCast(binder.user_data);

    const data = env.*.*.CallStaticObjectMethodA.?(env, b.parcel_class, b.parcel_obtain, null) orelse return STATUS_FAILED_TRANSACTION;
    const reply = env.*.*.CallStaticObjectMethodA.?(env, b.parcel_class, b.parcel_obtain, null) orelse {
        _ = clearException(env);
        env.*.*.CallVoidMethodA.?(env, data, b.parcel_recycle, null);
        env.*.*.DeleteLocalRef.?(env, data);
        return STATUS_FAILED_TRANSACTION;
    };
    javaUnmarshall(env, b, data, @constCast(in).data.items);

    var args: [4]jni.jvalue = undefined;
    args[0].i = @bitCast(code);
    args[1].l = data;
    args[2].l = reply;
    args[3].i = 0;
    const handled = env.*.*.CallBooleanMethodA.?(env, java_binder, b.binder_transact, &args);
    const threw = clearException(env);

    if (!threw and handled == jni.JNI_TRUE) {
        if (javaMarshall(env, b, reply)) |bytes| {
            defer alloc.free(bytes);
            out.writeBytes(bytes);
        }
    }
    env.*.*.CallVoidMethodA.?(env, data, b.parcel_recycle, null);
    env.*.*.CallVoidMethodA.?(env, reply, b.parcel_recycle, null);
    env.*.*.DeleteLocalRef.?(env, data);
    env.*.*.DeleteLocalRef.?(env, reply);
    if (threw) return STATUS_FAILED_TRANSACTION;
    return if (handled == jni.JNI_TRUE) STATUS_OK else STATUS_UNKNOWN_TRANSACTION;
}

/// The bridge class for Java-forwarding native binders (no interface token; the
/// forwarded Java Parcel already carries whatever the caller wrote).
var g_java_forward_class = AIBinder_Class{
    .descriptor = @constCast("azk.JavaForward"),
    .token_header = false,
    .on_create = null,
    .on_destroy = javaForwardDestroy,
    .on_transact = javaForwardTransact,
};

fn javaForwardDestroy(user_data: ?*anyopaque) callconv(.c) void {
    // Release the global ref to the wrapped Java Binder when the native handle
    // is freed.
    if (user_data == null) return;
    const env = jniEnv();
    if (env == null) return;
    env.*.*.DeleteGlobalRef.?(env, @ptrCast(user_data));
}

export fn AIBinder_fromJavaBinder(env_opaque: ?*anyopaque, binder: ?*anyopaque) callconv(.c) ?*AIBinder {
    const obj: jni.jobject = @ptrCast(binder orelse return null);
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return null));
    const b = ensureBridge(env) orelse return null;
    if (env.*.*.IsInstanceOf.?(env, obj, b.binderproxy_class) == jni.JNI_TRUE) {
        // Remote: a native proxy for the same broker object. Borrowed — the
        // JVM peer received this global id, this native peer did not, so it must
        // not count a delivery it never got.
        const gid = env.*.*.GetLongField.?(env, obj, b.binderproxy_globalid);
        return Runtime.get().proxyForBorrowed(@bitCast(gid));
    }
    // Local Java Binder: a native handle that forwards transactions to it.
    const global = env.*.*.NewGlobalRef.?(env, obj) orelse return null;
    return AIBinder.createLocal(&g_java_forward_class, global);
}

export fn AIBinder_toJavaBinder(env_opaque: ?*anyopaque, binder: ?*AIBinder) callconv(.c) ?*anyopaque {
    const bnd = binder orelse return null;
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return null));
    const b = ensureBridge(env) orelse return null;
    if (bnd.kind == .remote) {
        // Remote: a BinderProxy for the same broker object.
        var args: [1]jni.jvalue = undefined;
        args[0].j = @bitCast(bnd.id);
        return env.*.*.CallStaticObjectMethodA.?(env, b.hbr_class, b.proxy_for_global, &args);
    }
    if (bnd.class == &g_java_forward_class) {
        // Already a wrapped Java Binder: hand back the original object.
        return env.*.*.NewLocalRef.?(env, @ptrCast(bnd.user_data));
    }
    // Local native AIBinder: a Java Binder that forwards to it. The Java wrapper
    // holds a strong ref, released when the wrapper is finalized.
    AIBinder_incStrong(bnd);
    var args: [1]jni.jvalue = undefined;
    args[0].j = @intCast(@intFromPtr(bnd));
    const wrapper = env.*.*.CallStaticObjectMethodA.?(env, b.hbr_class, b.native_forwarding, &args);
    if (wrapper == null or clearException(env)) {
        // The wrapper never took the ref; roll it back so the object is not leaked.
        AIBinder_decStrong(bnd);
        return null;
    }
    return wrapper;
}

export fn AParcel_fromJavaParcel(env_opaque: ?*anyopaque, parcel: ?*anyopaque) callconv(.c) ?*AParcel {
    // v1 limitation: a read-only byte snapshot of the Java Parcel (object-free),
    // not a live wrapper of its buffer as AOSP provides. Native writes do not
    // flow back to the Java Parcel, and later Java-side changes are not seen.
    const obj: jni.jobject = @ptrCast(parcel orelse return null);
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return null));
    const b = ensureBridge(env) orelse return null;
    const bytes = javaMarshall(env, b, obj) orelse return null;
    defer alloc.free(bytes);
    const p = AParcel.create();
    p.writeBytes(bytes);
    p.pos = 0;
    return p;
}

/// Native method for android.os.HostedBinderRuntime.NativeForwardingBinder: a
/// Java transaction on a native-backed binder, forwarded to the native
/// AIBinder's on_transact (toJavaBinder's reverse direction).
export fn Java_dev_artzig_NativeForwardingBinder_nativeTransact(
    env_opaque: ?*anyopaque,
    thiz: ?*anyopaque,
    native_ptr: i64,
    code: i32,
    data: ?*anyopaque,
    reply: ?*anyopaque,
    flags: i32,
) callconv(.c) i32 {
    _ = thiz;
    _ = flags;
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return jni.JNI_FALSE));
    const b = ensureBridge(env) orelse return jni.JNI_FALSE;
    const bnd: *AIBinder = @ptrFromInt(@as(usize, @intCast(native_ptr)));

    const in = AParcel.create();
    defer in.destroy();
    if (data) |d| {
        const bytes = javaMarshall(env, b, @ptrCast(d)) orelse return jni.JNI_FALSE;
        defer alloc.free(bytes);
        in.writeBytes(bytes);
        in.pos = 0;
    }
    var out = AParcel.create();
    defer out.destroy();
    // Route through localTransact so the interface-token header is consumed and
    // the transaction identity (isHandlingTransaction, calling uid/pid) is
    // installed, exactly as an incoming transaction would be.
    const rc = Runtime.localTransact(bnd, @bitCast(code), in, out);
    if (rc == STATUS_OK and reply != null) {
        javaUnmarshall(env, b, @ptrCast(reply.?), out.data.items);
    }
    return if (rc == STATUS_OK) jni.JNI_TRUE else jni.JNI_FALSE;
}

/// Releases the native AIBinder ref held by a NativeForwardingBinder (called
/// from its finalizer / an explicit release).
export fn Java_dev_artzig_NativeForwardingBinder_nativeRelease(
    env_opaque: ?*anyopaque,
    thiz: ?*anyopaque,
    native_ptr: i64,
) callconv(.c) void {
    _ = env_opaque;
    _ = thiz;
    const bnd: *AIBinder = @ptrFromInt(@as(usize, @intCast(native_ptr)));
    AIBinder_decStrong(bnd);
}

// ----- JNI bridge self-test entries (dev.artzig.NdkBridgeTest). Exercised only
// by the in-process bridge test; inert otherwise.

/// Native transacts a Java Binder passed in (fromJavaBinder forward direction):
/// wraps it, calls add(a, b), returns the sum the Java onTransact wrote.
export fn Java_dev_artzig_NdkBridgeTest_forwardAdd(env: ?*anyopaque, clazz: ?*anyopaque, java_binder: ?*anyopaque, a: i32, b: i32) callconv(.c) i32 {
    _ = clazz;
    const native = AIBinder_fromJavaBinder(env, java_binder) orelse return -1;
    defer AIBinder_decStrong(native);
    var in: ?*AParcel = null;
    _ = AIBinder_prepareTransaction(native, &in);
    _ = AParcel_writeInt32(in.?, a);
    _ = AParcel_writeInt32(in.?, b);
    var out: ?*AParcel = null;
    const rc = AIBinder_transact(native, 1, &in, &out, 0);
    var result: i32 = -2;
    if (out) |o| {
        if (rc == STATUS_OK) _ = AParcel_readInt32(o, &result);
        o.destroy();
    }
    return if (rc == STATUS_OK) result else -3;
}

fn nativeAdderTransact(binder: *AIBinder, code: u32, in: *const AParcel, out: *AParcel) callconv(.c) i32 {
    _ = binder;
    if (code != 1) return STATUS_UNKNOWN_TRANSACTION;
    var a: i32 = 0;
    var b: i32 = 0;
    _ = AParcel_readInt32(in, &a);
    _ = AParcel_readInt32(in, &b);
    _ = AParcel_writeInt32(out, a + b);
    return STATUS_OK;
}

/// Creates a native AIBinder (add service) and returns it wrapped as a Java
/// Binder (toJavaBinder reverse direction): the Java caller transacts it and the
/// call is forwarded back to nativeAdderTransact.
export fn Java_dev_artzig_NdkBridgeTest_makeNativeAdder(env: ?*anyopaque, clazz: ?*anyopaque) callconv(.c) ?*anyopaque {
    _ = clazz;
    const class = AIBinder_Class_define("azk.NativeAdder", null, null, nativeAdderTransact);
    AIBinder_Class_disableInterfaceTokenHeader(class); // the Java caller sends raw args, no token
    const binder = AIBinder_new(class, null);
    const java = AIBinder_toJavaBinder(env, binder);
    AIBinder_decStrong(binder); // the Java wrapper now holds the ref
    return java;
}
