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
//! Source note: the peer half follows libbinder's IPCThreadState/BpBinder
//! shape over the AZKB protocol — a request-id/pending-call
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

/// The library-owned Io for framework JNI code (framework_jni.zig).
pub fn frameworkIo() Io {
    ensureIo();
    return g_io;
}

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

pub fn getenv(name: []const u8) ?[]const u8 {
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

/// Duplicates a descriptor (used when a parcel range carrying an fd entry is
/// copied into another parcel); returns -1 on failure.
fn dupFd(fd: fd_t) fd_t {
    if (fd < 0) return -1;
    const rc = std.os.linux.dup(fd);
    return if (std.os.linux.errno(rc) != .SUCCESS) -1 else @intCast(rc);
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

pub const AParcel = struct {
    data: std.ArrayListUnmanaged(u8) = .empty,
    entries: std.ArrayListUnmanaged(Entry) = .empty,
    /// Received descriptors, indexed by an entry's ordinal `value`.
    fds: std.ArrayListUnmanaged(fd_t) = .empty,
    pos: usize = 0,
    /// A received parcel owns its fds and closes them on delete; a locally
    /// built parcel hands its fds to the frame and must not.
    owns_fds: bool = false,
    /// Whether writing a file descriptor into this parcel is permitted
    /// (Parcel.pushAllowFds/restoreAllowFds). Defaults to allowed, matching a
    /// fresh AOSP Parcel.
    allow_fds: bool = true,

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

    /// Interface-descriptor token in the exact AOSP libbinder layout the JVM
    /// Parcel's writeInterfaceToken uses: strict-mode policy int, work-source
    /// int, the 'SYST' interface header marker, then writeString16(descriptor).
    /// This is the single wire format shared by every peer (NDK, JVM Parcel, and
    /// the broker's IServiceManager decoder) after the android.os flip.
    fn writeInterfaceToken(p: *AParcel, descriptor: []const u8) void {
        p.writeI32(0); // strict-mode policy
        p.writeI32(-1); // work source (kUnsetWorkSource)
        p.writeI32(interface_header); // 'SYST'
        p.writeString(descriptor, @intCast(descriptor.len));
    }
    /// Consumes a token and reports whether it names `descriptor`. Leaves the
    /// parcel positioned just after the token; a short/malformed token counts
    /// as a mismatch.
    fn enforceInterfaceToken(p: *AParcel, descriptor: []const u8) bool {
        _ = p.readI32() orelse return false; // strict-mode policy
        _ = p.readI32() orelse return false; // work source
        _ = p.readI32() orelse return false; // interface header marker
        const s = p.readString() catch return false;
        defer alloc.free(s);
        return std.mem.eql(u8, s, descriptor);
    }

    /// AOSP writeString8 layout: i32 byte count, the UTF-8 bytes, a NUL byte,
    /// padded to 4. length<0 writes the null marker.
    fn writeString8(p: *AParcel, utf8: []const u8, length: i32) void {
        if (length < 0) {
            p.writeI32(-1);
            return;
        }
        p.writeI32(@intCast(utf8.len));
        p.writeBytes(utf8);
        p.writeBytes(&[_]u8{0});
        p.pad4();
    }
    /// Reads the writeString8 layout into freshly allocated UTF-8 (caller frees);
    /// `error.Null` for the -1 marker.
    fn readString8(p: *AParcel) error{ Malformed, Null }![]u8 {
        const count = p.readI32() orelse return error.Malformed;
        if (count < 0) return error.Null;
        const n: usize = @intCast(count);
        const s = p.readSlice(n) orelse return error.Malformed;
        const out = alloc.dupe(u8, s) catch @panic("oom");
        _ = p.readSlice(1); // NUL byte
        p.skipPad();
        return out;
    }

    /// Appends `len` bytes from `other` starting at `offset`, copying any object
    /// entries that fall in that range (offsets rebased). Used by nativeAppendFrom.
    fn appendRange(p: *AParcel, other: *AParcel, offset: usize, len: usize) void {
        if (offset > other.data.items.len) return;
        const end = @min(offset + len, other.data.items.len);
        const base: u32 = @intCast(p.pos);
        p.writeBytes(other.data.items[offset..end]);
        for (other.entries.items) |e| {
            if (e.offset >= offset and e.offset < end) {
                const new_off = base + (e.offset - @as(u32, @intCast(offset)));
                var copy = e;
                copy.offset = new_off;
                // A copied fd entry must dup so both parcels can close it.
                if (e.kind == .file_descriptor and e.fd >= 0) copy.fd = dupFd(e.fd);
                p.entries.append(alloc, copy) catch @panic("oom");
            }
        }
    }
};

/// The libbinder interface header marker, B_PACK_CHARS('S','Y','S','T').
const interface_header: i32 = 0x53595354;

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
    /// Two-way inbound transactions are dispatched by a small elastic pool of
    /// persistent worker threads rather than a fresh detached thread per call.
    /// Each worker attaches to the JVM once and is reused, so the per-transaction
    /// attach leak and unbounded OS-thread spawn of the old path are both gone.
    /// The pool grows by one worker whenever a transaction arrives with no idle
    /// worker (bounded by `max_dispatch_workers`); reentrant nested calls are
    /// routed to the parked caller thread, not the pool, so they never starve it.
    /// All three fields are guarded by `mu`.
    dispatch_queue: std.ArrayListUnmanaged(*Incoming) = .empty,
    dispatch_cv: Io.Condition = .init,
    dispatch_idle: usize = 0,
    dispatch_total: usize = 0,
    disconnected: std.atomic.Value(bool) = .init(false),
    /// BinderInternal.joinThreadPool parks a thread here until the broker
    /// connection drops (onDisconnected broadcasts it), so a service process
    /// that joins the pool stays alive without a busy loop.
    join_mu: Io.Mutex = .init,
    join_cv: Io.Condition = .init,
    /// The routing id of the call each thread is currently servicing, for
    /// nested-transaction parent tracking.
    servicing: Io.Mutex = .init,

    const DeathLink = struct { global_id: u64, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque };

    /// Upper bound on persistent two-way dispatch workers. Generous so reentrant
    /// call chains cannot deadlock the pool; a steady app uses only a few.
    const max_dispatch_workers = 32;

    var instance: ?*Runtime = null;
    var connect_mu: Io.Mutex = .init;
    threadlocal var current_routing: u64 = 0;
    /// True while this thread runs an incoming on_transact (IPCThreadState::isServingCall):
    /// the calling identity is the caller's, even for oneway calls that carry no routing id.
    threadlocal var current_in_transaction: bool = false;
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
        const system_cookie = getenv("AZK_BINDER_SYSTEM_COOKIE");
        const app_cookie = getenv("AZK_BINDER_COOKIE") orelse "";
        var anon_buf: [64]u8 = undefined;
        var cookie: []const u8 = undefined;
        var package: []const u8 = undefined;
        if (g_has_identity_override) {
            // dev.artzig.BinderSession.setIdentity chose the package and role.
            package = g_identity_pkg orelse "";
            cookie = if (g_identity_is_system) (system_cookie orelse app_cookie) else app_cookie;
        } else {
            // A JVM process is an app unless BinderSession.setIdentity(_, true)
            // claimed the system role (the hosted runtime's rule: the test
            // harnesses hand every peer both cookies). A pure native peer has no
            // way to declare itself, so there the system cookie's presence IS the
            // role (the launcher strips it from spawned app children).
            cookie = if (g_jvm != null) app_cookie else (system_cookie orelse app_cookie);
            package = getenv("AZK_BINDER_PACKAGE") orelse
                std.fmt.bufPrint(&anon_buf, "anonymous.{d}", .{std.os.linux.getpid()}) catch unreachable;
        }
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
        detachCurrentThread();
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
                r.dispatchSubmit(in);
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
            .property_changed => @import("framework_jni.zig").onPropertyChanged(),
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

    /// Enqueues a two-way inbound transaction for the dispatch pool, growing the
    /// pool by one persistent worker when no worker is idle (up to
    /// `max_dispatch_workers`). Guarded by `mu`.
    fn dispatchSubmit(r: *Runtime, in: *Incoming) void {
        r.mu.lockUncancelable(g_io);
        var spawn_worker = false;
        if (r.dispatch_idle == 0 and r.dispatch_total < max_dispatch_workers) {
            r.dispatch_total += 1;
            spawn_worker = true;
        }
        r.dispatch_queue.append(alloc, in) catch @panic("oom");
        r.dispatch_cv.signal(g_io);
        r.mu.unlock(g_io);
        if (spawn_worker) {
            const th = std.Thread.spawn(.{}, dispatchWorker, .{r}) catch {
                r.mu.lockUncancelable(g_io);
                r.dispatch_total -= 1;
                const no_workers = r.dispatch_total == 0;
                r.mu.unlock(g_io);
                // Could not grow the pool. A live worker will pick the item up;
                // if none exists, run a detached one-shot so the two-way caller
                // is never left without a reply.
                if (no_workers) {
                    const th2 = std.Thread.spawn(.{}, dispatchOneShot, .{r}) catch return;
                    th2.detach();
                }
                return;
            };
            th.detach();
        }
    }

    /// A persistent dispatch-pool worker: attaches to the JVM once (a no-op in a
    /// native-only process) and then drains two-way transactions in a loop,
    /// parking on `dispatch_cv` while the queue is empty.
    fn dispatchWorker(r: *Runtime) void {
        _ = jniEnv(); // attach this worker thread to the JVM once, if one exists
        while (true) {
            r.mu.lockUncancelable(g_io);
            r.dispatch_idle += 1;
            while (r.dispatch_queue.items.len == 0) r.dispatch_cv.waitUncancelable(g_io, &r.mu);
            r.dispatch_idle -= 1;
            const in = r.dispatch_queue.orderedRemove(0);
            r.mu.unlock(g_io);
            r.dispatch(in);
        }
    }

    /// Best-effort fallback used only when the pool cannot grow (thread-spawn
    /// exhaustion) and no worker exists: dispatches one queued transaction, then
    /// exits.
    fn dispatchOneShot(r: *Runtime) void {
        r.mu.lockUncancelable(g_io);
        if (r.dispatch_queue.items.len == 0) {
            r.mu.unlock(g_io);
            return;
        }
        const in = r.dispatch_queue.orderedRemove(0);
        r.mu.unlock(g_io);
        r.dispatch(in);
        detachCurrentThread();
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
        } else if (answerBinderControl(target.?, in.code, out)) {
            // INTERFACE_TRANSACTION / PING_TRANSACTION answered for native
            // objects (a Java Binder answers them itself in execTransact).
        } else if (!token_ok) {
            binder_status = STATUS_BAD_TYPE; // wrong interface descriptor (AOSP)
        } else {
            const prev_routing = current_routing;
            const prev_uid = current_caller_uid;
            const prev_pid = current_caller_pid;
            const prev_in_transaction = current_in_transaction;
            current_routing = in.routing_id;
            current_in_transaction = true;
            current_caller_uid = in.caller_uid;
            // Oneway transactions have no waiting caller; AOSP reports pid 0.
            current_caller_pid = if (oneway) 0 else in.caller_pid;
            binder_status = target.?.class.?.on_transact.?(target.?, in.code, in.parcel, out);
            current_in_transaction = prev_in_transaction;
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
        detachCurrentThread();
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
        // Wake any thread parked in BinderInternal.joinThreadPool.
        r.join_mu.lockUncancelable(g_io);
        r.join_cv.broadcast(g_io);
        r.join_mu.unlock(g_io);
        // A windowless service host exits when its session's broker drops.
        if (g_exit_on_disconnect.load(.monotonic)) std.process.exit(0);
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
        if (out) |o| {
            if (answerBinderControl(target, code, o)) {
                o.pos = 0;
                return STATUS_OK;
            }
        }
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
        const prev_in_transaction = current_in_transaction;
        current_routing = std.math.maxInt(u64); // nonzero: "handling a transaction"
        current_in_transaction = true;
        // Self identity: the connected runtime's uid if there is one, else this
        // process's own (a bridge-only process may never connect). v1 limitation:
        // when only the Java runtime is connected, the native side cannot see its
        // broker-assigned Android uid and falls back to the host uid — observable
        // only via AIBinder_getCallingUid inside a self-call.
        current_caller_uid = if (Runtime.instance) |r| r.uid else std.os.linux.getuid();
        current_caller_pid = if (Runtime.instance) |r| r.pid else @intCast(std.os.linux.getpid());
        const rc = on_transact(target, code, in, reply);
        current_in_transaction = prev_in_transaction;
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
            if (e.fd >= 0) {
                // Written by this process (not received): hand out a dup so the
                // parcel keeps its own copy for sending.
                fd.* = dupFd(e.fd);
                return STATUS_OK;
            }
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

// ----- azk_parcel_*: small C shim for the island's libbinder Parcel stand-in
// (src/native/AospParcelAbi.cpp), which reaches the Java Parcel's AParcel
// through mNativePtr. String8 is the AOSP layout CursorWindow writes.

export fn azk_parcel_writeString8(parcel: *AParcel, str: ?[*]const u8, len: usize) callconv(.c) i32 {
    if (str) |s| parcel.writeString8(s[0..len], @intCast(len)) else parcel.writeString8("", -1);
    return STATUS_OK;
}
/// Reads a String8 into a buffer owned by this library (free with
/// azk_parcel_freeString8). A null marker yields a null pointer and STATUS_OK.
export fn azk_parcel_readString8(parcel: *const AParcel, out: *?[*]u8, out_len: *usize) callconv(.c) i32 {
    const p = @constCast(parcel);
    const s = p.readString8() catch |e| switch (e) {
        error.Null => {
            out.* = null;
            out_len.* = 0;
            return STATUS_OK;
        },
        error.Malformed => return STATUS_NOT_ENOUGH_DATA,
    };
    out.* = s.ptr;
    out_len.* = s.len;
    return STATUS_OK;
}
export fn azk_parcel_freeString8(ptr: ?[*]u8, len: usize) callconv(.c) void {
    if (ptr) |q| alloc.free(q[0..len]);
}
/// libbinder Parcel::readFileDescriptor semantics: the descriptor stays owned
/// by the parcel (the caller dups to keep it), unlike the NDK read which
/// transfers ownership.
export fn azk_parcel_readFileDescriptorBorrowed(parcel: *const AParcel, fd: *i32) callconv(.c) i32 {
    const p = @constCast(parcel);
    const off: u32 = @intCast(p.pos);
    const has = p.readI32() orelse return STATUS_NOT_ENOUGH_DATA;
    fd.* = -1;
    if (has == 0) return STATUS_OK;
    for (p.entries.items) |e| {
        if (e.offset == off and e.kind == .file_descriptor) {
            if (e.fd >= 0) {
                fd.* = e.fd; // written by this process: the entry owns it
                return STATUS_OK;
            }
            const ordinal: usize = @intCast(e.value);
            if (ordinal < p.fds.items.len) fd.* = p.fds.items[ordinal];
            return STATUS_OK;
        }
    }
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
    if (Runtime.current_in_transaction) return @intCast(Runtime.current_caller_uid);
    return @intCast(Runtime.get().uid);
}
export fn AIBinder_getCallingPid() callconv(.c) i32 {
    if (Runtime.current_in_transaction) return Runtime.current_caller_pid;
    return Runtime.get().pid;
}
export fn AIBinder_isHandlingTransaction() callconv(.c) bool {
    return Runtime.current_in_transaction;
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

/// Broker set_property round-trip (SystemProperties.native_set).
pub const SetPropertyResult = enum { ok, denied, failed };
pub fn setSystemProperty(name: []const u8, value: []const u8) SetPropertyResult {
    const r = Runtime.get();
    var body = std.ArrayListUnmanaged(u8).empty;
    defer body.deinit(alloc);
    const sp = Client.Message.SetProperty{ .name_len = @intCast(name.len), .value_len = @intCast(value.len) };
    body.appendSlice(alloc, std.mem.asBytes(&sp)) catch @panic("oom");
    body.appendSlice(alloc, name) catch @panic("oom");
    body.appendSlice(alloc, value) catch @panic("oom");
    const call = r.serviceRequest(.set_property, body.items);
    defer {
        if (call.svc_names.len > 0) alloc.free(call.svc_names);
        call.nested.deinit(alloc);
        alloc.destroy(call);
    }
    if (call.failed) return .failed;
    return switch (call.svc_status) {
        .ok => .ok,
        .denied => .denied,
        else => .failed,
    };
}

comptime {
    _ = @import("framework_jni.zig"); // emit the framework JNI exports
}

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

const JNIEnvPtr = [*c]jni.JNIEnv;

/// A JNIEnv for the calling thread, attaching it to the VM if necessary (e.g. a
/// broker reader/dispatch thread that never entered the JVM on its own).
/// A JNIEnv for the calling thread ([*c], null on failure), attaching it to the
/// VM if necessary (e.g. a broker reader/dispatch thread that never entered the
/// JVM on its own). v1 limitation: an attached library thread is not detached
/// (no DetachCurrentThread) — harmless for the tested path, where the bridge
/// runs on already-attached JVM threads; the remote-forward path (a library
/// worker forwarding to Java) would want a pthread-key detach on thread exit.
/// Capture the JavaVM from a live env when the library was dlopen'd without
/// System.load (no JNI_OnLoad) — the framework-JNI registration entry calls
/// this first so later attached threads can reach the VM.
pub fn ensureJavaVm(env: JNIEnvPtr) void {
    if (g_jvm != null) return;
    var vm: [*c]jni.JavaVM = null;
    if (env.*.*.GetJavaVM.?(env, &vm) == jni.JNI_OK) g_jvm = @ptrCast(vm);
}

pub fn jniEnv() JNIEnvPtr {
    const vm: [*c]jni.JavaVM = @ptrCast(@alignCast(g_jvm orelse return null));
    var env: ?*anyopaque = null;
    if (vm.*.*.GetEnv.?(vm, &env, jni.JNI_VERSION_1_6) != jni.JNI_OK) {
        if (vm.*.*.AttachCurrentThread.?(vm, @ptrCast(&env), null) != jni.JNI_OK) return null;
    }
    return @ptrCast(@alignCast(env));
}

/// Detaches the calling thread from the JVM if jniEnv() attached it. Every
/// library thread that may have run Java (oneway serial workers, the reader
/// thread, one-shot dispatchers) calls this before exiting: ART aborts a
/// process whose attached native thread exits without detaching.
fn detachCurrentThread() void {
    const vm: [*c]jni.JavaVM = @ptrCast(@alignCast(g_jvm orelse return));
    var env: ?*anyopaque = null;
    if (vm.*.*.GetEnv.?(vm, &env, jni.JNI_VERSION_1_6) != jni.JNI_OK) return; // not attached
    _ = vm.*.*.DetachCurrentThread.?(vm);
}

pub fn findGlobalClass(env: JNIEnvPtr, name: [*:0]const u8) ?jni.jclass {
    const local = env.*.*.FindClass.?(env, name) orelse return null;
    const global: jni.jclass = @ptrCast(env.*.*.NewGlobalRef.?(env, local));
    env.*.*.DeleteLocalRef.?(env, local);
    return global;
}

fn clearException(env: JNIEnvPtr) bool {
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE) {
        env.*.*.ExceptionClear.?(env);
        return true;
    }
    return false;
}

/// on_transact for a local Java Binder's AIBinder (JavaBBinderHolder path):
/// promotes the holder's weak ref and calls Binder.execTransact with the native
/// AParcel pointers directly (true sharing — no marshalling). The reply is
/// written straight into `out`, which the reply path then serializes.
fn javaForwardTransact(binder: *AIBinder, code: u32, in: *const AParcel, out: *AParcel) callconv(.c) i32 {
    const fw = g_fw orelse return STATUS_FAILED_TRANSACTION;
    const env = jniEnv();
    if (env == null) return STATUS_FAILED_TRANSACTION;
    const h: *JavaBBinderHolder = @ptrCast(@alignCast(binder.user_data));
    const local = env.*.*.NewLocalRef.?(env, h.java_ref) orelse return STATUS_DEAD_OBJECT;
    defer env.*.*.DeleteLocalRef.?(env, local);
    var args: [4]jni.jvalue = undefined;
    args[0].i = @bitCast(code);
    args[1].j = @intCast(@intFromPtr(in));
    args[2].j = @intCast(@intFromPtr(out));
    args[3].i = 0; // flags (the on_transact seam does not carry them; oneway is handled by the caller)
    const handled = env.*.*.CallBooleanMethodA.?(env, local, fw.binder_exec_transact, &args);
    if (env.*.*.ExceptionCheck.?(env) == jni.JNI_TRUE) {
        // Binder.execTransact rethrows what Parcel.writeException cannot encode; the
        // caller only sees FAILED_TRANSACTION, so describe it here (AOSP logs the same
        // "Caught a RuntimeException from the binder stub implementation").
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
        return STATUS_FAILED_TRANSACTION;
    }
    return if (handled == jni.JNI_TRUE) STATUS_OK else STATUS_UNKNOWN_TRANSACTION;
}

/// The class for AIBinders that back a local Java Binder. No interface token is
/// consumed natively; execTransact reads it on the Java side. user_data is the
/// *JavaBBinderHolder.
var g_java_forward_class = AIBinder_Class{
    .descriptor = @constCast("azk.JavaForward"),
    .token_header = false,
    .on_create = null,
    .on_destroy = javaForwardDestroy,
    .on_transact = javaForwardTransact,
};

fn javaForwardDestroy(user_data: ?*anyopaque) callconv(.c) void {
    // The AIBinder's last strong ref is gone: release the strong global ref so
    // the Java Binder becomes collectable. The holder itself is freed by the
    // Binder's finalizer (it still holds a weak handle on this object).
    const h: *JavaBBinderHolder = @ptrCast(@alignCast(user_data orelse return));
    const env = jniEnv();
    g_holder_mu.lockUncancelable(g_io);
    const ref = h.java_ref;
    h.java_ref = null;
    g_holder_mu.unlock(g_io);
    if (env) |e| if (ref != null) e.*.*.DeleteGlobalRef.?(e, ref);
}

export fn AIBinder_fromJavaBinder(env_opaque: ?*anyopaque, binder: ?*anyopaque) callconv(.c) ?*AIBinder {
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return null));
    return javaBinderToNative(env, @ptrCast(binder));
}

export fn AIBinder_toJavaBinder(env_opaque: ?*anyopaque, binder: ?*AIBinder) callconv(.c) ?*anyopaque {
    const bnd = binder orelse return null;
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return null));
    const fw = g_fw orelse return null;
    // NDK contract: the caller keeps its own reference; anything created here
    // takes its own.
    if (bnd.class == &g_java_forward_class) {
        // A local Java Binder read back in its own process: return the object.
        const h: *JavaBBinderHolder = @ptrCast(@alignCast(bnd.user_data));
        return env.*.*.NewLocalRef.?(env, h.java_ref);
    }
    // A remote proxy or a native-local AIBinder handed to Java: build (or reuse)
    // a BinderProxy whose mNativeData owns a strong ref. getInstance dedups by
    // the AIBinder address, which is stable per broker object (proxies are
    // gid-keyed).
    AIBinder_incStrong(bnd);
    const nd = alloc.create(BinderProxyNativeData) catch @panic("oom");
    nd.* = .{ .aibinder = bnd };
    var args: [2]jni.jvalue = undefined;
    args[0].j = @intCast(@intFromPtr(nd));
    args[1].j = @intCast(@intFromPtr(bnd));
    const proxy = env.*.*.CallStaticObjectMethodA.?(env, fw.binderproxy_class, fw.binderproxy_get_instance, &args);
    if (proxy == null or clearException(env)) {
        alloc.destroy(nd);
        AIBinder_decStrong(bnd);
        return null;
    }
    // If getInstance deduped to an existing proxy, it did not adopt our data.
    const adopted = env.*.*.GetLongField.?(env, proxy, fw.binderproxy_native_data);
    if (adopted != @as(jni.jlong, @intCast(@intFromPtr(nd)))) {
        alloc.destroy(nd);
        AIBinder_decStrong(bnd);
    }
    return proxy;
}

export fn AParcel_fromJavaParcel(env_opaque: ?*anyopaque, parcel: ?*anyopaque) callconv(.c) ?*AParcel {
    // True sharing: the Java Parcel's mNativePtr IS the AParcel. The returned
    // pointer is borrowed (owned by the Java Parcel); the NDK caller must not
    // delete it.
    const obj: jni.jobject = @ptrCast(parcel orelse return null);
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return null));
    const fw = g_fw orelse return null;
    const ptr = env.*.*.GetLongField.?(env, obj, fw.parcel_native_ptr);
    if (ptr == 0) return null;
    return parcelOf(ptr);
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

// ===========================================================================
// android.os framework natives: Parcel, Binder, BinderProxy, BinderInternal.
//
// Registered by azk_register_binder_jni (framework_jni.zig) via
// registerBinderFrameworkNatives. The real AOSP Java classes run over this: a
// Java Parcel's mNativePtr IS an *AParcel; a Java Binder's mObject is a
// *JavaBBinderHolder; a BinderProxy's mNativeData is a *BinderProxyNativeData.
// This replaces the hosted pure-Java Parcel/Binder and HostedBinderRuntime.
// ===========================================================================

/// Lazily-created native backing for a local Java Binder (Binder.mObject),
/// modelled on AOSP's JavaBBinderHolder/JavaBBinder: the holder keeps only a
/// WEAK handle on `aibinder`, while the aibinder (class g_java_forward_class)
/// owns a STRONG global ref to the Java Binder for as long as anything holds
/// the native object (an export, a remote, a reader). So a Binder that was
/// handed out stays alive exactly while it is referenced natively, and becomes
/// collectable once the last native ref drops (javaForwardDestroy releases the
/// global ref). The Binder's NativeAllocationRegistry finalizer then drops the
/// holder's weak handle and frees the holder.
const JavaBBinderHolder = struct {
    java_ref: jni.jobject = null,
    aibinder: ?*AIBinder = null,
};

/// Native backing for a BinderProxy (BinderProxy.mNativeData): one strong ref to
/// a remote `aibinder`. Freed (and the ref dropped) by the proxy finalizer.
const BinderProxyNativeData = struct {
    aibinder: *AIBinder,
};

/// Cached class/field/method ids for the framework binder classes, resolved once
/// by registerBinderFrameworkNatives.
const FrameworkIds = struct {
    parcel_native_ptr: jni.jfieldID, // Parcel.mNativePtr : J
    binder_class: jni.jclass,
    binder_object: jni.jfieldID, // Binder.mObject : J
    binder_exec_transact: jni.jmethodID, // Binder.execTransact(IJJI)Z
    binderproxy_class: jni.jclass,
    binderproxy_native_data: jni.jfieldID, // BinderProxy.mNativeData : J
    binderproxy_get_instance: jni.jmethodID, // static BinderProxy.getInstance(JJ)LBinderProxy;
    fd_class: jni.jclass,
    fd_ctor: jni.jmethodID, // FileDescriptor()
    fd_get_int: jni.jmethodID, // FileDescriptor.getInt$()I
    fd_set_int: jni.jmethodID, // FileDescriptor.setInt$(I)V
    death_binder_died: jni.jmethodID, // IBinder$DeathRecipient.binderDied()V
};
var g_fw: ?FrameworkIds = null;

inline fn parcelOf(handle: jni.jlong) *AParcel {
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

// ----- Parcel @CriticalNative (no JNIEnv*/jclass, no object params) ---------

fn pMarkSensitive(handle: jni.jlong) callconv(.c) void {
    _ = handle;
}
fn pIsForRpc(handle: jni.jlong) callconv(.c) jni.jboolean {
    _ = handle;
    return jni.JNI_FALSE;
}
fn pDataSize(handle: jni.jlong) callconv(.c) jni.jint {
    return @intCast(parcelOf(handle).data.items.len);
}
fn pDataAvail(handle: jni.jlong) callconv(.c) jni.jint {
    const p = parcelOf(handle);
    return if (p.pos >= p.data.items.len) 0 else @intCast(p.data.items.len - p.pos);
}
fn pDataPosition(handle: jni.jlong) callconv(.c) jni.jint {
    return @intCast(parcelOf(handle).pos);
}
fn pDataCapacity(handle: jni.jlong) callconv(.c) jni.jint {
    return @intCast(parcelOf(handle).data.capacity);
}
fn pSetDataPosition(handle: jni.jlong, pos: jni.jint) callconv(.c) void {
    parcelOf(handle).pos = @intCast(pos);
}
fn pPushAllowFds(handle: jni.jlong, allow: jni.jboolean) callconv(.c) jni.jboolean {
    const p = parcelOf(handle);
    const old = p.allow_fds;
    p.allow_fds = allow != jni.JNI_FALSE;
    return if (old) jni.JNI_TRUE else jni.JNI_FALSE;
}
fn pRestoreAllowFds(handle: jni.jlong, last: jni.jboolean) callconv(.c) void {
    parcelOf(handle).allow_fds = last != jni.JNI_FALSE;
}
fn pWriteInt(handle: jni.jlong, val: jni.jint) callconv(.c) jni.jint {
    parcelOf(handle).writeI32(val);
    return STATUS_OK;
}
fn pWriteLong(handle: jni.jlong, val: jni.jlong) callconv(.c) jni.jint {
    parcelOf(handle).writeI64(val);
    return STATUS_OK;
}
fn pWriteFloat(handle: jni.jlong, val: jni.jfloat) callconv(.c) jni.jint {
    parcelOf(handle).writeI32(@bitCast(val));
    return STATUS_OK;
}
fn pWriteDouble(handle: jni.jlong, val: jni.jdouble) callconv(.c) jni.jint {
    parcelOf(handle).writeI64(@bitCast(val));
    return STATUS_OK;
}
fn pReadInt(handle: jni.jlong) callconv(.c) jni.jint {
    return parcelOf(handle).readI32() orelse 0;
}
fn pReadLong(handle: jni.jlong) callconv(.c) jni.jlong {
    return parcelOf(handle).readI64() orelse 0;
}
fn pReadFloat(handle: jni.jlong) callconv(.c) jni.jfloat {
    return @bitCast(parcelOf(handle).readI32() orelse 0);
}
fn pReadDouble(handle: jni.jlong) callconv(.c) jni.jdouble {
    return @bitCast(parcelOf(handle).readI64() orelse 0);
}
fn pHasFileDescriptors(handle: jni.jlong) callconv(.c) jni.jboolean {
    for (parcelOf(handle).entries.items) |e| if (e.kind == .file_descriptor) return jni.JNI_TRUE;
    return jni.JNI_FALSE;
}
fn pReplaceCallingWorkSourceUid(handle: jni.jlong, uid: jni.jint) callconv(.c) jni.jboolean {
    _ = handle;
    _ = uid;
    return jni.JNI_FALSE;
}
fn pReadCallingWorkSourceUid(handle: jni.jlong) callconv(.c) jni.jint {
    _ = handle;
    return -1; // kUnsetWorkSource
}
fn pGetOpenAshmemSize(handle: jni.jlong) callconv(.c) jni.jlong {
    _ = handle;
    return 0;
}

// ----- Parcel @FastNative / normal (JNIEnv*, jclass, ...) -------------------

fn pSetDataSize(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, size: jni.jint) callconv(.c) void {
    _ = env;
    _ = cls;
    const p = parcelOf(handle);
    const n: usize = @intCast(@max(size, 0));
    p.data.resize(alloc, n) catch @panic("oom");
}
fn pSetDataCapacity(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, size: jni.jint) callconv(.c) void {
    _ = env;
    _ = cls;
    if (size > 0) parcelOf(handle).data.ensureTotalCapacity(alloc, @intCast(size)) catch @panic("oom");
}
fn pWriteString8(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, str: jni.jstring) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    if (str == null) {
        p.writeString8("", -1);
        return;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, str, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, str, chars);
    p.writeString8(std.mem.span(chars), @intCast(std.mem.len(chars)));
}
fn pWriteString16(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, str: jni.jstring) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    if (str == null) {
        p.writeString("", -1);
        return;
    }
    const chars = env.*.*.GetStringUTFChars.?(env, str, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, str, chars);
    const span = std.mem.span(chars);
    p.writeString(span, @intCast(span.len));
}
fn pReadString8(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jstring {
    _ = cls;
    const s = parcelOf(handle).readString8() catch return null;
    defer alloc.free(s);
    const z = dupz(s) orelse return null;
    defer alloc.free(z);
    return env.*.*.NewStringUTF.?(env, z);
}
fn pReadString16(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jstring {
    _ = cls;
    const s = parcelOf(handle).readString() catch return null;
    defer alloc.free(s);
    const z = dupz(s) orelse return null;
    defer alloc.free(z);
    return env.*.*.NewStringUTF.?(env, z);
}
fn pWriteStrongBinder(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, binder: jni.jobject) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    const native = javaBinderToNative(env, binder); // owns a ref, or null
    defer if (native) |n| AIBinder_decStrong(n);
    _ = AParcel_writeStrongBinder(p, native);
}
fn pWriteFileDescriptor(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, fd_obj: jni.jobject) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    const fw = g_fw orelse return;
    if (fd_obj == null) {
        p.writeI32(0);
        return;
    }
    const fd = env.*.*.CallIntMethod.?(env, fd_obj, fw.fd_get_int);
    _ = AParcel_writeParcelFileDescriptor(p, fd);
}
fn pReadStrongBinder(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jobject {
    _ = cls;
    var native: ?*AIBinder = null;
    _ = AParcel_readStrongBinder(parcelOf(handle), &native);
    const n = native orelse return null;
    defer AIBinder_decStrong(n); // readStrongBinder's ref; the Java object took its own
    return @ptrCast(AIBinder_toJavaBinder(@ptrCast(env), n));
}
fn pReadFileDescriptor(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jobject {
    _ = cls;
    const fw = g_fw orelse return null;
    var fd: i32 = -1;
    _ = AParcel_readParcelFileDescriptor(parcelOf(handle), &fd);
    if (fd < 0) return null;
    const obj = env.*.*.NewObject.?(env, fw.fd_class, fw.fd_ctor) orelse return null;
    env.*.*.CallVoidMethod.?(env, obj, fw.fd_set_int, fd);
    return obj;
}
fn pMarkForBinder(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, binder: jni.jobject) callconv(.c) void {
    _ = env;
    _ = cls;
    _ = handle;
    _ = binder;
}
fn pWriteByteArray(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, arr: jni.jbyteArray, offset: jni.jint, len: jni.jint) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    if (arr == null or len < 0) {
        p.writeI32(-1);
        return;
    }
    p.writeI32(len);
    const n: usize = @intCast(len);
    const buf = alloc.alloc(u8, n) catch @panic("oom");
    defer alloc.free(buf);
    env.*.*.GetByteArrayRegion.?(env, arr, offset, len, @ptrCast(buf.ptr));
    p.writeBytes(buf);
    p.pad4();
}
/// Parcel::writeBlob layout: after the Java-written length, an i32 kind, then
/// either the bytes inline (BLOB_INPLACE, padded) or a descriptor to a sealed
/// memfd holding them (BLOB_ASHMEM_IMMUTABLE). AOSP inlines up to
/// BLOB_INPLACE_LIMIT; spilling larger blobs keeps a Parcel under the broker's
/// frame cap, since the descriptor rides SCM_RIGHTS instead of the data.
const BLOB_INPLACE_LIMIT: usize = 16 * 1024;
const BLOB_INPLACE: i32 = 0;
const BLOB_ASHMEM_IMMUTABLE: i32 = 1;
const F_ADD_SEALS: i32 = 1033;
const F_SEAL_SHRINK: usize = 0x2;
const F_SEAL_GROW: usize = 0x4;
const F_SEAL_WRITE: usize = 0x8;

fn pWriteBlob(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, arr: jni.jbyteArray, offset: jni.jint, len: jni.jint) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    if (arr == null or len < 0) {
        p.writeI32(-1);
        return;
    }
    p.writeI32(len);
    const n: usize = @intCast(len);
    const buf = alloc.alloc(u8, n) catch @panic("oom");
    defer alloc.free(buf);
    if (n > 0) env.*.*.GetByteArrayRegion.?(env, arr, offset, len, @ptrCast(buf.ptr));
    if (!p.allow_fds or n <= BLOB_INPLACE_LIMIT) {
        p.writeI32(BLOB_INPLACE);
        p.writeBytes(buf);
        p.pad4();
        return;
    }
    const fd = blobMemfd(buf) orelse {
        throwRuntime(env, "cannot create a memfd for a Parcel blob");
        return;
    };
    defer closeFd(fd);
    p.writeI32(BLOB_ASHMEM_IMMUTABLE);
    _ = AParcel_writeParcelFileDescriptor(p, fd); // dups the descriptor into the parcel
}

/// A sealed, read-only memfd holding `bytes` (the host stand-in for an
/// immutable ashmem blob region). Returns the descriptor, or null on failure.
fn blobMemfd(bytes: []const u8) ?fd_t {
    const linux = std.os.linux;
    const rc = linux.memfd_create("Parcel Blob", 0x0001 | 0x0002); // MFD_CLOEXEC | MFD_ALLOW_SEALING
    if (linux.errno(rc) != .SUCCESS) return null;
    const fd: fd_t = @intCast(rc);
    if (linux.errno(linux.ftruncate(fd, @intCast(bytes.len))) != .SUCCESS) {
        closeFd(fd);
        return null;
    }
    var done: usize = 0;
    while (done < bytes.len) {
        const w = linux.pwrite(fd, bytes[done..].ptr, bytes.len - done, @intCast(done));
        if (linux.errno(w) != .SUCCESS) {
            closeFd(fd);
            return null;
        }
        done += w;
    }
    _ = linux.fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE);
    return fd;
}

fn pCreateByteArray(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jbyteArray {
    _ = cls;
    const p = parcelOf(handle);
    const count = p.readI32() orelse return null;
    if (count < 0) return null;
    const n: usize = @intCast(count);
    const s = p.readSlice(n) orelse return null;
    p.skipPad();
    const arr = env.*.*.NewByteArray.?(env, count) orelse return null;
    env.*.*.SetByteArrayRegion.?(env, arr, 0, count, @ptrCast(s.ptr));
    return arr;
}
fn pReadByteArray(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, dest: jni.jbyteArray, dest_len: jni.jint) callconv(.c) jni.jboolean {
    _ = cls;
    const p = parcelOf(handle);
    const count = p.readI32() orelse return jni.JNI_FALSE;
    if (count != dest_len) return jni.JNI_FALSE;
    if (count < 0) return jni.JNI_FALSE;
    const n: usize = @intCast(count);
    const s = p.readSlice(n) orelse return jni.JNI_FALSE;
    p.skipPad();
    if (dest == null) return jni.JNI_FALSE;
    env.*.*.SetByteArrayRegion.?(env, dest, 0, count, @ptrCast(s.ptr));
    return jni.JNI_TRUE;
}
fn pReadBlob(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jbyteArray {
    _ = cls;
    const p = parcelOf(handle);
    const len = p.readI32() orelse return null;
    if (len < 0) return null;
    const n: usize = @intCast(len);
    const kind = p.readI32() orelse return null;
    const arr = env.*.*.NewByteArray.?(env, len) orelse return null;
    if (kind == BLOB_INPLACE) {
        const s = p.readSlice(n) orelse return null;
        p.skipPad();
        if (n > 0) env.*.*.SetByteArrayRegion.?(env, arr, 0, len, @ptrCast(s.ptr));
        return arr;
    }
    // A descriptor-backed blob: the parcel keeps the descriptor; read it out.
    var fd: i32 = -1;
    _ = azk_parcel_readFileDescriptorBorrowed(p, &fd);
    if (fd < 0) {
        throwRuntime(env, "Parcel blob descriptor missing");
        return null;
    }
    const buf = alloc.alloc(u8, n) catch @panic("oom");
    defer alloc.free(buf);
    var done: usize = 0;
    while (done < n) {
        const r = std.os.linux.pread(fd, buf[done..].ptr, n - done, @intCast(done));
        if (std.os.linux.errno(r) != .SUCCESS or r == 0) {
            throwRuntime(env, "cannot read a Parcel blob descriptor");
            return null;
        }
        done += r;
    }
    if (n > 0) env.*.*.SetByteArrayRegion.?(env, arr, 0, len, @ptrCast(buf.ptr));
    return arr;
}
fn pSignalExceptionForError(env: JNIEnvPtr, cls: jni.jclass, err: jni.jint) callconv(.c) void {
    _ = cls;
    throwRuntime(env, "parcel error");
    _ = err;
}
fn pCreate(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jlong {
    _ = env;
    _ = cls;
    return @intCast(@intFromPtr(AParcel.create()));
}
fn pFreeBuffer(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) void {
    _ = env;
    _ = cls;
    parcelOf(handle).clearContents();
}
fn pDestroy(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) void {
    _ = env;
    _ = cls;
    parcelOf(handle).destroy();
}
fn pMarshall(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jbyteArray {
    _ = cls;
    const p = parcelOf(handle);
    const n: jni.jint = @intCast(p.data.items.len);
    const arr = env.*.*.NewByteArray.?(env, n) orelse return null;
    if (n > 0) env.*.*.SetByteArrayRegion.?(env, arr, 0, n, @ptrCast(p.data.items.ptr));
    return arr;
}
fn pUnmarshall(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, data: jni.jbyteArray, offset: jni.jint, len: jni.jint) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    if (data == null or len < 0) return;
    const n: usize = @intCast(len);
    const buf = alloc.alloc(u8, n) catch @panic("oom");
    defer alloc.free(buf);
    env.*.*.GetByteArrayRegion.?(env, data, offset, len, @ptrCast(buf.ptr));
    p.clearContents();
    p.writeBytes(buf);
    p.pos = 0;
}
fn pMarshallArray(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, data: jni.jbyteArray, offset: jni.jint, length: jni.jint) callconv(.c) jni.jint {
    _ = cls;
    const p = parcelOf(handle);
    const avail: usize = p.data.items.len;
    const want: usize = @intCast(@max(length, 0));
    const n = @min(avail, want);
    if (n > 0) env.*.*.SetByteArrayRegion.?(env, data, offset, @intCast(n), @ptrCast(p.data.items.ptr));
    return @intCast(n);
}
fn pMarshallBuffer(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, buffer: jni.jobject, offset: jni.jint, length: jni.jint) callconv(.c) jni.jint {
    _ = cls;
    const p = parcelOf(handle);
    const base = env.*.*.GetDirectBufferAddress.?(env, buffer) orelse return 0;
    const dst: [*]u8 = @ptrCast(base);
    const want: usize = @intCast(@max(length, 0));
    const n = @min(p.data.items.len, want);
    if (n > 0) @memcpy(dst[@intCast(offset)..][0..n], p.data.items[0..n]);
    return @intCast(n);
}
fn pUnmarshallBuffer(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, buffer: jni.jobject, offset: jni.jint, length: jni.jint) callconv(.c) void {
    _ = cls;
    const p = parcelOf(handle);
    const base = env.*.*.GetDirectBufferAddress.?(env, buffer) orelse return;
    const src: [*]const u8 = @ptrCast(base);
    const n: usize = @intCast(@max(length, 0));
    p.clearContents();
    p.writeBytes(src[@intCast(offset)..][0..n]);
    p.pos = 0;
}
fn pCompareData(env: JNIEnvPtr, cls: jni.jclass, a: jni.jlong, b: jni.jlong) callconv(.c) jni.jint {
    _ = env;
    _ = cls;
    const pa = parcelOf(a);
    const pb = parcelOf(b);
    if (std.mem.eql(u8, pa.data.items, pb.data.items)) return 0;
    return 1;
}
fn pCompareDataInRange(env: JNIEnvPtr, cls: jni.jclass, a: jni.jlong, off_a: jni.jint, b: jni.jlong, off_b: jni.jint, length: jni.jint) callconv(.c) jni.jboolean {
    _ = env;
    _ = cls;
    const pa = parcelOf(a);
    const pb = parcelOf(b);
    const oa: usize = @intCast(off_a);
    const ob: usize = @intCast(off_b);
    const n: usize = @intCast(@max(length, 0));
    if (oa + n > pa.data.items.len or ob + n > pb.data.items.len) return jni.JNI_FALSE;
    return if (std.mem.eql(u8, pa.data.items[oa .. oa + n], pb.data.items[ob .. ob + n])) jni.JNI_TRUE else jni.JNI_FALSE;
}
fn pAppendFrom(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, other: jni.jlong, offset: jni.jint, length: jni.jint) callconv(.c) void {
    _ = env;
    _ = cls;
    parcelOf(handle).appendRange(parcelOf(other), @intCast(@max(offset, 0)), @intCast(@max(length, 0)));
}
fn pHasFileDescriptorsInRange(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, offset: jni.jint, length: jni.jint) callconv(.c) jni.jboolean {
    _ = env;
    _ = cls;
    const o: u32 = @intCast(@max(offset, 0));
    const end: u32 = o + @as(u32, @intCast(@max(length, 0)));
    for (parcelOf(handle).entries.items) |e| {
        if (e.kind == .file_descriptor and e.offset >= o and e.offset < end) return jni.JNI_TRUE;
    }
    return jni.JNI_FALSE;
}
fn pHasBinders(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong) callconv(.c) jni.jboolean {
    _ = env;
    _ = cls;
    for (parcelOf(handle).entries.items) |e| {
        if (e.kind == .binder_local or e.kind == .binder_remote) return jni.JNI_TRUE;
    }
    return jni.JNI_FALSE;
}
fn pHasBindersInRange(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, offset: jni.jint, length: jni.jint) callconv(.c) jni.jboolean {
    _ = env;
    _ = cls;
    const o: u32 = @intCast(@max(offset, 0));
    const end: u32 = o + @as(u32, @intCast(@max(length, 0)));
    for (parcelOf(handle).entries.items) |e| {
        if ((e.kind == .binder_local or e.kind == .binder_remote) and e.offset >= o and e.offset < end) return jni.JNI_TRUE;
    }
    return jni.JNI_FALSE;
}
fn pWriteInterfaceToken(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, name: jni.jstring) callconv(.c) void {
    _ = cls;
    if (name == null) return;
    const chars = env.*.*.GetStringUTFChars.?(env, name, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, name, chars);
    parcelOf(handle).writeInterfaceToken(std.mem.span(chars));
}
fn pEnforceInterface(env: JNIEnvPtr, cls: jni.jclass, handle: jni.jlong, name: jni.jstring) callconv(.c) void {
    _ = cls;
    if (name == null) return;
    const chars = env.*.*.GetStringUTFChars.?(env, name, null) orelse return;
    defer env.*.*.ReleaseStringUTFChars.?(env, name, chars);
    if (!parcelOf(handle).enforceInterfaceToken(std.mem.span(chars)))
        throwException(env, "java/lang/SecurityException", "Binder invocation to an incorrect interface");
}
fn pGetGlobalAllocSize(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jlong {
    _ = env;
    _ = cls;
    return 0;
}
fn pGetGlobalAllocCount(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jlong {
    _ = env;
    _ = cls;
    return 0;
}

// ----- transaction identity threadlocals (Binder static natives) -----------

threadlocal var tl_strict_policy: i32 = 0;
threadlocal var tl_work_source: i32 = -1; // kUnsetWorkSource
threadlocal var tl_has_identity: bool = false;
var g_holder_mu: Io.Mutex = .init;

// Process identity override set by dev.artzig.BinderSession before the first
// binder use (replaces HostedBinderRuntime.bootstrap): connect() prefers these
// over the AZK_BINDER_* env when present, so a process can name itself and pick
// the system vs app role explicitly.
var g_identity_pkg: ?[]u8 = null;
var g_identity_is_system: bool = false;
var g_has_identity_override: bool = false;
/// When set, a lost broker connection exits the process (a windowless service
/// host has no reason to outlive its session).
var g_exit_on_disconnect = std.atomic.Value(bool).init(false);

fn throwException(env: JNIEnvPtr, class_name: [*:0]const u8, msg: [*:0]const u8) void {
    const c = env.*.*.FindClass.?(env, class_name) orelse return;
    _ = env.*.*.ThrowNew.?(env, c, msg);
    env.*.*.DeleteLocalRef.?(env, c);
}
fn throwRuntime(env: JNIEnvPtr, msg: [*:0]const u8) void {
    throwException(env, "java/lang/RuntimeException", msg);
}

// ----- local Java Binder <-> native AIBinder (the JavaBBinderHolder path) ---

/// Returns a strong-ref'd AIBinder for a Java IBinder (Binder or BinderProxy),
/// or null. The caller owns the returned reference.
fn javaBinderToNative(env: JNIEnvPtr, obj: jni.jobject) ?*AIBinder {
    if (obj == null) return null;
    const fw = g_fw orelse return null;
    if (env.*.*.IsInstanceOf.?(env, obj, fw.binderproxy_class) == jni.JNI_TRUE) {
        const nd_ptr = env.*.*.GetLongField.?(env, obj, fw.binderproxy_native_data);
        if (nd_ptr == 0) return null;
        const nd: *BinderProxyNativeData = @ptrFromInt(@as(usize, @intCast(nd_ptr)));
        _ = nd.aibinder.strong.fetchAdd(1, .monotonic);
        return nd.aibinder;
    }
    return holderAIBinder(env, obj);
}

/// Gets (or lazily creates) the local AIBinder backing a Java Binder, via its
/// mObject holder. Returns a new strong ref the caller owns.
fn holderAIBinder(env: JNIEnvPtr, obj: jni.jobject) ?*AIBinder {
    const fw = g_fw orelse return null;
    const holder_ptr = env.*.*.GetLongField.?(env, obj, fw.binder_object);
    if (holder_ptr == 0) return null;
    const h: *JavaBBinderHolder = @ptrFromInt(@as(usize, @intCast(holder_ptr)));
    g_holder_mu.lockUncancelable(g_io);
    defer g_holder_mu.unlock(g_io);
    if (h.aibinder) |b| {
        if (tryIncStrong(b)) return b;
        // The previous native object died (last ref dropped); make a fresh one.
        decWeak(b);
        h.aibinder = null;
    }
    h.java_ref = env.*.*.NewGlobalRef.?(env, obj);
    const b = AIBinder.createLocal(&g_java_forward_class, h); // strong=1: the caller's ref
    _ = b.weak.fetchAdd(1, .monotonic); // the holder's weak handle
    h.aibinder = b;
    return b;
}

/// NativeAllocationRegistry free function for a Java Binder's mObject holder.
/// Runs only once the Java Binder is unreachable, i.e. after the aibinder (if
/// any) released its strong global ref; drop the weak handle and free the holder.
fn binderHolderFinalize(ptr: ?*anyopaque) callconv(.c) void {
    const h: *JavaBBinderHolder = @ptrCast(@alignCast(ptr orelse return));
    g_holder_mu.lockUncancelable(g_io);
    const b = h.aibinder;
    h.aibinder = null;
    g_holder_mu.unlock(g_io);
    if (b) |bb| decWeak(bb);
    alloc.destroy(h);
}

// ----- Binder @CriticalNative --------------------------------------------

fn bGetCallingPid() callconv(.c) jni.jint {
    if (Runtime.current_in_transaction) return Runtime.current_caller_pid;
    return Runtime.get().pid;
}
fn bGetCallingUid() callconv(.c) jni.jint {
    if (Runtime.current_in_transaction) return @intCast(Runtime.current_caller_uid);
    return @intCast(Runtime.get().uid);
}
fn bIsDirectlyHandlingTransaction() callconv(.c) jni.jboolean {
    return if (Runtime.current_in_transaction) jni.JNI_TRUE else jni.JNI_FALSE;
}
fn bHasExplicitIdentity() callconv(.c) jni.jboolean {
    return if (tl_has_identity) jni.JNI_TRUE else jni.JNI_FALSE;
}
fn bClearCallingIdentity() callconv(.c) jni.jlong {
    const uid: u64 = if (Runtime.current_in_transaction) Runtime.current_caller_uid else Runtime.get().uid;
    const pid_u: u32 = @bitCast(if (Runtime.current_in_transaction) Runtime.current_caller_pid else Runtime.get().pid);
    const token: i64 = @bitCast((uid << 32) | pid_u);
    Runtime.current_caller_uid = Runtime.get().uid;
    Runtime.current_caller_pid = Runtime.get().pid;
    tl_has_identity = true;
    return token;
}
fn bRestoreCallingIdentity(token: jni.jlong) callconv(.c) void {
    const t: u64 = @bitCast(token);
    Runtime.current_caller_uid = @intCast(t >> 32);
    Runtime.current_caller_pid = @bitCast(@as(u32, @truncate(t)));
}
fn bSetThreadStrictModePolicy(policy: jni.jint) callconv(.c) void {
    tl_strict_policy = policy;
}
fn bGetThreadStrictModePolicy() callconv(.c) jni.jint {
    return tl_strict_policy;
}
fn bSetCallingWorkSourceUid(ws: jni.jint) callconv(.c) jni.jlong {
    const old = tl_work_source;
    tl_work_source = ws;
    return @intCast(@as(u32, @bitCast(old)));
}
fn bGetCallingWorkSourceUid() callconv(.c) jni.jint {
    return tl_work_source;
}
fn bClearCallingWorkSource() callconv(.c) jni.jlong {
    const old = tl_work_source;
    tl_work_source = -1;
    return @intCast(@as(u32, @bitCast(old)));
}
fn bRestoreCallingWorkSource(token: jni.jlong) callconv(.c) void {
    tl_work_source = @bitCast(@as(u32, @truncate(@as(u64, @bitCast(token)))));
}

// ----- Binder normal natives -----------------------------------------------

fn bGetNativeFinalizer(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jlong {
    _ = env;
    _ = cls;
    return @intCast(@intFromPtr(&binderHolderFinalize));
}
fn bGetNativeBBinderHolder(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jlong {
    _ = env;
    _ = cls;
    const h = alloc.create(JavaBBinderHolder) catch @panic("oom");
    h.* = .{};
    return @intCast(@intFromPtr(h));
}
fn bMarkVintfStability(env: JNIEnvPtr, this: jni.jobject) callconv(.c) void {
    _ = env;
    _ = this;
}
fn bForceDowngradeToSystemStability(env: JNIEnvPtr, this: jni.jobject) callconv(.c) void {
    _ = env;
    _ = this;
}
fn bFlushPendingCommands(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) void {
    _ = env;
    _ = cls;
}
fn bBlockUntilThreadAvailable(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) void {
    _ = env;
    _ = cls;
}
fn bSetExtensionNative(env: JNIEnvPtr, this: jni.jobject, ext: jni.jobject) callconv(.c) void {
    // Set the extension on this Binder's holder AIBinder (used by getExtension
    // on the remote side). Rare; a missing extension reads back as null.
    const native = javaBinderToNative(env, this) orelse return;
    defer AIBinder_decStrong(native);
    const e = javaBinderToNative(env, ext); // owned ref, transferred to the object
    if (native.extension) |old| AIBinder_decStrong(old);
    native.extension = e;
}

// ----- BinderProxy natives (all take env + this) ---------------------------

inline fn proxyData(env: JNIEnvPtr, this: jni.jobject) ?*BinderProxyNativeData {
    const fw = g_fw orelse return null;
    const nd_ptr = env.*.*.GetLongField.?(env, this, fw.binderproxy_native_data);
    if (nd_ptr == 0) return null;
    return @ptrFromInt(@as(usize, @intCast(nd_ptr)));
}

fn bpPingBinder(env: JNIEnvPtr, this: jni.jobject) callconv(.c) jni.jboolean {
    const nd = proxyData(env, this) orelse return jni.JNI_FALSE;
    return if (nd.aibinder.dead.load(.monotonic)) jni.JNI_FALSE else jni.JNI_TRUE;
}
fn bpIsBinderAlive(env: JNIEnvPtr, this: jni.jobject) callconv(.c) jni.jboolean {
    const nd = proxyData(env, this) orelse return jni.JNI_FALSE;
    return if (nd.aibinder.dead.load(.monotonic)) jni.JNI_FALSE else jni.JNI_TRUE;
}
fn bpGetExtension(env: JNIEnvPtr, this: jni.jobject) callconv(.c) jni.jobject {
    const nd = proxyData(env, this) orelse return null;
    const ext = nd.aibinder.extension orelse return null;
    return @ptrCast(AIBinder_toJavaBinder(@ptrCast(env), ext));
}
/// IBinder.INTERFACE_TRANSACTION ('_NTF'): the callee replies writeString16(descriptor).
const INTERFACE_TRANSACTION: u32 = ('_' << 24) | ('N' << 16) | ('T' << 8) | 'F';
/// IBinder.PING_TRANSACTION ('_PNG'): answered with an empty OK reply.
const PING_TRANSACTION: u32 = ('_' << 24) | ('P' << 16) | ('N' << 8) | 'G';

/// The control transactions BBinder::onTransact answers for every native
/// object (a Java Binder handles them in execTransact instead). Returns true
/// when `code` was answered into `out`.
fn answerBinderControl(target: *AIBinder, code: u32, out: *AParcel) bool {
    const class = target.class orelse return false;
    if (class == &g_java_forward_class) return false;
    switch (code) {
        INTERFACE_TRANSACTION => {
            out.writeString(class.descriptor, @intCast(class.descriptor.len));
            return true;
        },
        PING_TRANSACTION => return true,
        else => return false,
    }
}

fn bpGetInterfaceDescriptor(env: JNIEnvPtr, this: jni.jobject) callconv(.c) jni.jstring {
    const nd = proxyData(env, this) orelse return null;
    const in = AParcel.create();
    defer in.destroy();
    var out = AParcel.create();
    defer out.destroy();
    const rc = Runtime.get().transact(nd.aibinder, INTERFACE_TRANSACTION, in, out, 0);
    if (rc != STATUS_OK) {
        throwException(env, if (rc == STATUS_DEAD_OBJECT) "android/os/DeadObjectException" else "android/os/RemoteException", "");
        return null;
    }
    const s = out.readString() catch return null;
    defer alloc.free(s);
    const z = dupz(s) orelse return null;
    defer alloc.free(z);
    return env.*.*.NewStringUTF.?(env, z);
}
fn bpGetNativeFinalizer(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jlong {
    _ = env;
    _ = cls;
    return @intCast(@intFromPtr(&binderProxyFinalize));
}
fn binderProxyFinalize(ptr: ?*anyopaque) callconv(.c) void {
    const nd: *BinderProxyNativeData = @ptrCast(@alignCast(ptr orelse return));
    AIBinder_decStrong(nd.aibinder);
    alloc.destroy(nd);
}
fn bpTransactNative(env: JNIEnvPtr, this: jni.jobject, code: jni.jint, data: jni.jobject, reply: jni.jobject, flags: jni.jint) callconv(.c) jni.jboolean {
    const fw = g_fw orelse return jni.JNI_FALSE;
    const nd = proxyData(env, this) orelse return jni.JNI_FALSE;
    const in = parcelOf(env.*.*.GetLongField.?(env, data, fw.parcel_native_ptr));
    const out: ?*AParcel = if (reply == null) null else parcelOf(env.*.*.GetLongField.?(env, reply, fw.parcel_native_ptr));
    const rc = Runtime.get().transact(nd.aibinder, @bitCast(code), in, out, @bitCast(flags));
    if (rc == STATUS_OK or rc == STATUS_UNKNOWN_TRANSACTION) {
        // A callee that returned UNKNOWN_TRANSACTION still yields false here so
        // the proxy's default-impl fallback can run.
        return if (rc == STATUS_OK) jni.JNI_TRUE else jni.JNI_FALSE;
    }
    if (rc == STATUS_DEAD_OBJECT) {
        throwException(env, "android/os/DeadObjectException", "");
    } else {
        throwException(env, "android/os/RemoteException", "");
    }
    return jni.JNI_FALSE;
}

/// Shared native DeathRecipient: its cookie is a global ref to the Java
/// IBinder.DeathRecipient, invoked when the linked object dies.
var g_java_death_recipient = AIBinder_DeathRecipient{ .on_died = javaDeathDied };
fn javaDeathDied(cookie: ?*anyopaque) callconv(.c) void {
    const fw = g_fw orelse return;
    const env = jniEnv();
    if (env == null) return;
    const recipient: jni.jobject = @ptrCast(cookie orelse return);
    env.*.*.CallVoidMethod.?(env, recipient, fw.death_binder_died);
    _ = clearException(env);
    // A death notification is delivered once; like BpBinder::sendObituary the
    // link is gone afterwards, so a later unlinkToDeath reports it missing.
    const r = Runtime.get();
    r.mu.lockUncancelable(g_io);
    var i: usize = 0;
    while (i < r.death_links.items.len) : (i += 1) {
        const l = r.death_links.items[i];
        if (l.recipient == &g_java_death_recipient and l.cookie == cookie) {
            _ = r.death_links.orderedRemove(i);
            break;
        }
    }
    r.mu.unlock(g_io);
    env.*.*.DeleteGlobalRef.?(env, recipient);
}
fn bpLinkToDeathNative(env: JNIEnvPtr, this: jni.jobject, recipient: jni.jobject, flags: jni.jint) callconv(.c) void {
    _ = flags;
    const nd = proxyData(env, this) orelse return;
    const cookie = env.*.*.NewGlobalRef.?(env, recipient) orelse return;
    _ = AIBinder_linkToDeath(nd.aibinder, &g_java_death_recipient, cookie);
}
fn bpUnlinkToDeathNative(env: JNIEnvPtr, this: jni.jobject, recipient: jni.jobject, flags: jni.jint) callconv(.c) jni.jboolean {
    _ = flags;
    const nd = proxyData(env, this) orelse return jni.JNI_FALSE;
    const r = Runtime.get();
    r.mu.lockUncancelable(g_io);
    defer r.mu.unlock(g_io);
    var i: usize = 0;
    while (i < r.death_links.items.len) : (i += 1) {
        const l = r.death_links.items[i];
        if (l.global_id != nd.aibinder.id or l.recipient != &g_java_death_recipient) continue;
        const cookie: jni.jobject = @ptrCast(l.cookie);
        if (env.*.*.IsSameObject.?(env, cookie, recipient) != jni.JNI_TRUE) continue;
        env.*.*.DeleteGlobalRef.?(env, cookie);
        _ = r.death_links.orderedRemove(i);
        return jni.JNI_TRUE;
    }
    return jni.JNI_FALSE;
}
fn bpAddFrozenStateChangeCallbackNative(env: JNIEnvPtr, this: jni.jobject, cb: jni.jobject) callconv(.c) void {
    _ = this;
    _ = cb;
    throwRuntime(env, "frozen state change callbacks are not supported");
}
fn bpRemoveFrozenStateChangeCallbackNative(env: JNIEnvPtr, this: jni.jobject, cb: jni.jobject) callconv(.c) jni.jboolean {
    _ = env;
    _ = this;
    _ = cb;
    return jni.JNI_FALSE;
}
fn bpIsFrozenStateChangeCallbackSupportedNative(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jboolean {
    _ = env;
    _ = cls;
    return jni.JNI_FALSE;
}

// ----- BinderInternal natives ----------------------------------------------

fn biJoinThreadPool(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) void {
    _ = env;
    _ = cls;
    const r = Runtime.get();
    r.join_mu.lockUncancelable(g_io);
    defer r.join_mu.unlock(g_io);
    while (!r.disconnected.load(.acquire)) r.join_cv.waitUncancelable(g_io, &r.join_mu);
}
fn biGetContextObject(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jobject {
    _ = cls;
    const proxy = Runtime.get().proxyFor(Client.context_object_id);
    defer AIBinder_decStrong(proxy);
    return @ptrCast(AIBinder_toJavaBinder(@ptrCast(env), proxy));
}
fn biDisableBackgroundScheduling(env: JNIEnvPtr, cls: jni.jclass, disable: jni.jboolean) callconv(.c) void {
    _ = env;
    _ = cls;
    _ = disable;
}
fn biSetMaxThreads(env: JNIEnvPtr, cls: jni.jclass, n: jni.jint) callconv(.c) void {
    _ = env;
    _ = cls;
    _ = n;
}
fn biHandleGc(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) void {
    _ = env;
    _ = cls;
}
fn biSetBinderProxyCountEnabled(env: JNIEnvPtr, cls: jni.jclass, enabled: jni.jboolean) callconv(.c) void {
    _ = env;
    _ = cls;
    _ = enabled;
}
fn biGetBinderProxyPerUidCounts(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jobject {
    _ = env;
    _ = cls;
    return null;
}
fn biGetBinderProxyCount(env: JNIEnvPtr, cls: jni.jclass, uid: jni.jint) callconv(.c) jni.jint {
    _ = env;
    _ = cls;
    _ = uid;
    return 0;
}
fn biSetBinderProxyCountWatermarks(env: JNIEnvPtr, cls: jni.jclass, high: jni.jint, low: jni.jint, warning: jni.jint) callconv(.c) void {
    _ = env;
    _ = cls;
    _ = high;
    _ = low;
    _ = warning;
}

// ----- ServiceManager / ServiceManagerProxy natives ------------------------

fn smWaitForServiceNative(env: JNIEnvPtr, cls: jni.jclass, name: jni.jstring) callconv(.c) jni.jobject {
    _ = cls;
    if (name == null) return null;
    const chars = env.*.*.GetStringUTFChars.?(env, name, null) orelse return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, name, chars);
    const b = lookup(@ptrCast(chars), true) orelse return null;
    defer AIBinder_decStrong(b);
    return @ptrCast(AIBinder_toJavaBinder(@ptrCast(env), b));
}
fn smpGetNativeServiceManager(env: JNIEnvPtr, this: jni.jobject) callconv(.c) jni.jobject {
    _ = this;
    const proxy = Runtime.get().proxyFor(Client.context_object_id);
    defer AIBinder_decStrong(proxy);
    return @ptrCast(AIBinder_toJavaBinder(@ptrCast(env), proxy));
}

// ----- dev.artzig.BinderSession (process identity / lifecycle) --------------

fn bsSetIdentity(env: JNIEnvPtr, cls: jni.jclass, pkg: jni.jstring, is_system: jni.jboolean) callconv(.c) void {
    _ = cls;
    if (g_identity_pkg) |old| alloc.free(old);
    g_identity_pkg = null;
    if (pkg != null) {
        const chars = env.*.*.GetStringUTFChars.?(env, pkg, null);
        if (chars) |c| {
            defer env.*.*.ReleaseStringUTFChars.?(env, pkg, c);
            g_identity_pkg = alloc.dupe(u8, std.mem.span(c)) catch null;
        }
    }
    g_identity_is_system = is_system != jni.JNI_FALSE;
    g_has_identity_override = true;
}
fn bsSetExitOnDisconnect(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) void {
    _ = env;
    _ = cls;
    g_exit_on_disconnect.store(true, .monotonic);
}
fn bsAssignedUid(env: JNIEnvPtr, cls: jni.jclass) callconv(.c) jni.jint {
    _ = env;
    _ = cls;
    if (Runtime.instance) |r| return @intCast(r.uid);
    // No broker configured for this process (e.g. a standalone JVM test): report
    // -1 so Process.myUid falls back to its constant. Otherwise connect (a
    // broker-backed process's uid is broker-assigned) and return the uid.
    if (getenv("AZK_BINDER_SOCKET") == null) return -1;
    return @intCast(Runtime.get().uid);
}

// ----- registration --------------------------------------------------------

fn jm(name: [*:0]const u8, sig: [*:0]const u8, ptr: anytype) jni.JNINativeMethod {
    return .{ .name = @constCast(name), .signature = @constCast(sig), .fnPtr = @ptrCast(@constCast(ptr)) };
}

/// Registers the Parcel/Binder/BinderProxy/BinderInternal natives against the
/// real AOSP framework classes and caches the field/method ids the native side
/// calls back through. Called once per process by azk_register_binder_jni.
pub fn registerBinderFrameworkNatives(env_opaque: ?*anyopaque) c_int {
    const env: JNIEnvPtr = @ptrCast(@alignCast(env_opaque orelse return -1));
    ensureJavaVm(env);

    const parcel_cls = findGlobalClass(env, "android/os/Parcel") orelse return -1;
    const binder_cls = findGlobalClass(env, "android/os/Binder") orelse return -1;
    const proxy_cls = findGlobalClass(env, "android/os/BinderProxy") orelse return -1;
    const internal_cls = findGlobalClass(env, "com/android/internal/os/BinderInternal") orelse return -1;
    const fd_cls = findGlobalClass(env, "java/io/FileDescriptor") orelse return -1;
    const dr_cls = findGlobalClass(env, "android/os/IBinder$DeathRecipient") orelse return -1;
    defer env.*.*.DeleteGlobalRef.?(env, @ptrCast(dr_cls));

    g_fw = .{
        .parcel_native_ptr = env.*.*.GetFieldID.?(env, parcel_cls, "mNativePtr", "J") orelse return -1,
        .binder_class = binder_cls,
        .binder_object = env.*.*.GetFieldID.?(env, binder_cls, "mObject", "J") orelse return -1,
        .binder_exec_transact = env.*.*.GetMethodID.?(env, binder_cls, "execTransact", "(IJJI)Z") orelse return -1,
        .binderproxy_class = proxy_cls,
        .binderproxy_native_data = env.*.*.GetFieldID.?(env, proxy_cls, "mNativeData", "J") orelse return -1,
        .binderproxy_get_instance = env.*.*.GetStaticMethodID.?(env, proxy_cls, "getInstance", "(JJ)Landroid/os/BinderProxy;") orelse return -1,
        .fd_class = fd_cls,
        .fd_ctor = env.*.*.GetMethodID.?(env, fd_cls, "<init>", "()V") orelse return -1,
        .fd_get_int = env.*.*.GetMethodID.?(env, fd_cls, "getInt$", "()I") orelse return -1,
        .fd_set_int = env.*.*.GetMethodID.?(env, fd_cls, "setInt$", "(I)V") orelse return -1,
        .death_binder_died = env.*.*.GetMethodID.?(env, dr_cls, "binderDied", "()V") orelse return -1,
    };

    const parcel_methods = [_]jni.JNINativeMethod{
        jm("nativeMarkSensitive", "(J)V", &pMarkSensitive),
        jm("nativeMarkForBinder", "(JLandroid/os/IBinder;)V", &pMarkForBinder),
        jm("nativeIsForRpc", "(J)Z", &pIsForRpc),
        jm("nativeDataSize", "(J)I", &pDataSize),
        jm("nativeDataAvail", "(J)I", &pDataAvail),
        jm("nativeDataPosition", "(J)I", &pDataPosition),
        jm("nativeDataCapacity", "(J)I", &pDataCapacity),
        jm("nativeSetDataSize", "(JI)V", &pSetDataSize),
        jm("nativeSetDataPosition", "(JI)V", &pSetDataPosition),
        jm("nativeSetDataCapacity", "(JI)V", &pSetDataCapacity),
        jm("nativePushAllowFds", "(JZ)Z", &pPushAllowFds),
        jm("nativeRestoreAllowFds", "(JZ)V", &pRestoreAllowFds),
        jm("nativeWriteByteArray", "(J[BII)V", &pWriteByteArray),
        jm("nativeWriteBlob", "(J[BII)V", &pWriteBlob),
        jm("nativeWriteInt", "(JI)I", &pWriteInt),
        jm("nativeWriteLong", "(JJ)I", &pWriteLong),
        jm("nativeWriteFloat", "(JF)I", &pWriteFloat),
        jm("nativeWriteDouble", "(JD)I", &pWriteDouble),
        jm("nativeSignalExceptionForError", "(I)V", &pSignalExceptionForError),
        jm("nativeWriteString8", "(JLjava/lang/String;)V", &pWriteString8),
        jm("nativeWriteString16", "(JLjava/lang/String;)V", &pWriteString16),
        jm("nativeWriteStrongBinder", "(JLandroid/os/IBinder;)V", &pWriteStrongBinder),
        jm("nativeWriteFileDescriptor", "(JLjava/io/FileDescriptor;)V", &pWriteFileDescriptor),
        jm("nativeCreateByteArray", "(J)[B", &pCreateByteArray),
        jm("nativeReadByteArray", "(J[BI)Z", &pReadByteArray),
        jm("nativeReadBlob", "(J)[B", &pReadBlob),
        jm("nativeReadInt", "(J)I", &pReadInt),
        jm("nativeReadLong", "(J)J", &pReadLong),
        jm("nativeReadFloat", "(J)F", &pReadFloat),
        jm("nativeReadDouble", "(J)D", &pReadDouble),
        jm("nativeReadString8", "(J)Ljava/lang/String;", &pReadString8),
        jm("nativeReadString16", "(J)Ljava/lang/String;", &pReadString16),
        jm("nativeReadStrongBinder", "(J)Landroid/os/IBinder;", &pReadStrongBinder),
        jm("nativeReadFileDescriptor", "(J)Ljava/io/FileDescriptor;", &pReadFileDescriptor),
        jm("nativeCreate", "()J", &pCreate),
        jm("nativeFreeBuffer", "(J)V", &pFreeBuffer),
        jm("nativeDestroy", "(J)V", &pDestroy),
        jm("nativeMarshall", "(J)[B", &pMarshall),
        jm("nativeMarshallArray", "(J[BII)I", &pMarshallArray),
        jm("nativeMarshallBuffer", "(JLjava/nio/ByteBuffer;II)I", &pMarshallBuffer),
        jm("nativeUnmarshall", "(J[BII)V", &pUnmarshall),
        jm("nativeUnmarshallBuffer", "(JLjava/nio/ByteBuffer;II)V", &pUnmarshallBuffer),
        jm("nativeCompareData", "(JJ)I", &pCompareData),
        jm("nativeCompareDataInRange", "(JIJII)Z", &pCompareDataInRange),
        jm("nativeAppendFrom", "(JJII)V", &pAppendFrom),
        jm("nativeHasFileDescriptors", "(J)Z", &pHasFileDescriptors),
        jm("nativeHasFileDescriptorsInRange", "(JII)Z", &pHasFileDescriptorsInRange),
        jm("nativeHasBinders", "(J)Z", &pHasBinders),
        jm("nativeHasBindersInRange", "(JII)Z", &pHasBindersInRange),
        jm("nativeWriteInterfaceToken", "(JLjava/lang/String;)V", &pWriteInterfaceToken),
        jm("nativeEnforceInterface", "(JLjava/lang/String;)V", &pEnforceInterface),
        jm("nativeReplaceCallingWorkSourceUid", "(JI)Z", &pReplaceCallingWorkSourceUid),
        jm("nativeReadCallingWorkSourceUid", "(J)I", &pReadCallingWorkSourceUid),
        jm("nativeGetOpenAshmemSize", "(J)J", &pGetOpenAshmemSize),
        jm("getGlobalAllocSize", "()J", &pGetGlobalAllocSize),
        jm("getGlobalAllocCount", "()J", &pGetGlobalAllocCount),
    };
    if (env.*.*.RegisterNatives.?(env, parcel_cls, &parcel_methods, parcel_methods.len) != 0) return -1;

    const binder_methods = [_]jni.JNINativeMethod{
        jm("getCallingPid", "()I", &bGetCallingPid),
        jm("getCallingUid", "()I", &bGetCallingUid),
        jm("isDirectlyHandlingTransactionNative", "()Z", &bIsDirectlyHandlingTransaction),
        jm("hasExplicitIdentity", "()Z", &bHasExplicitIdentity),
        jm("clearCallingIdentity", "()J", &bClearCallingIdentity),
        jm("restoreCallingIdentity", "(J)V", &bRestoreCallingIdentity),
        jm("setThreadStrictModePolicy", "(I)V", &bSetThreadStrictModePolicy),
        jm("getThreadStrictModePolicy", "()I", &bGetThreadStrictModePolicy),
        jm("setCallingWorkSourceUid", "(I)J", &bSetCallingWorkSourceUid),
        jm("getCallingWorkSourceUid", "()I", &bGetCallingWorkSourceUid),
        jm("clearCallingWorkSource", "()J", &bClearCallingWorkSource),
        jm("restoreCallingWorkSource", "(J)V", &bRestoreCallingWorkSource),
        jm("getNativeFinalizer", "()J", &bGetNativeFinalizer),
        jm("getNativeBBinderHolder", "()J", &bGetNativeBBinderHolder),
        jm("markVintfStability", "()V", &bMarkVintfStability),
        jm("forceDowngradeToSystemStability", "()V", &bForceDowngradeToSystemStability),
        jm("flushPendingCommands", "()V", &bFlushPendingCommands),
        jm("blockUntilThreadAvailable", "()V", &bBlockUntilThreadAvailable),
        jm("setExtensionNative", "(Landroid/os/IBinder;)V", &bSetExtensionNative),
    };
    if (env.*.*.RegisterNatives.?(env, binder_cls, &binder_methods, binder_methods.len) != 0) return -1;

    const proxy_methods = [_]jni.JNINativeMethod{
        jm("pingBinder", "()Z", &bpPingBinder),
        jm("isBinderAlive", "()Z", &bpIsBinderAlive),
        jm("getExtension", "()Landroid/os/IBinder;", &bpGetExtension),
        jm("getNativeFinalizer", "()J", &bpGetNativeFinalizer),
        jm("getInterfaceDescriptor", "()Ljava/lang/String;", &bpGetInterfaceDescriptor),
        jm("transactNative", "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z", &bpTransactNative),
        jm("linkToDeathNative", "(Landroid/os/IBinder$DeathRecipient;I)V", &bpLinkToDeathNative),
        jm("unlinkToDeathNative", "(Landroid/os/IBinder$DeathRecipient;I)Z", &bpUnlinkToDeathNative),
        jm("addFrozenStateChangeCallbackNative", "(Landroid/os/IBinder$FrozenStateChangeCallback;)V", &bpAddFrozenStateChangeCallbackNative),
        jm("removeFrozenStateChangeCallbackNative", "(Landroid/os/IBinder$FrozenStateChangeCallback;)Z", &bpRemoveFrozenStateChangeCallbackNative),
        jm("isFrozenStateChangeCallbackSupportedNative", "()Z", &bpIsFrozenStateChangeCallbackSupportedNative),
    };
    if (env.*.*.RegisterNatives.?(env, proxy_cls, &proxy_methods, proxy_methods.len) != 0) return -1;

    const internal_methods = [_]jni.JNINativeMethod{
        jm("joinThreadPool", "()V", &biJoinThreadPool),
        jm("getContextObject", "()Landroid/os/IBinder;", &biGetContextObject),
        jm("disableBackgroundScheduling", "(Z)V", &biDisableBackgroundScheduling),
        jm("setMaxThreads", "(I)V", &biSetMaxThreads),
        jm("handleGc", "()V", &biHandleGc),
        jm("nSetBinderProxyCountEnabled", "(Z)V", &biSetBinderProxyCountEnabled),
        jm("nGetBinderProxyPerUidCounts", "()Landroid/util/SparseIntArray;", &biGetBinderProxyPerUidCounts),
        jm("nGetBinderProxyCount", "(I)I", &biGetBinderProxyCount),
        jm("nSetBinderProxyCountWatermarks", "(III)V", &biSetBinderProxyCountWatermarks),
    };
    if (env.*.*.RegisterNatives.?(env, internal_cls, &internal_methods, internal_methods.len) != 0) return -1;

    // ServiceManager (static waitForServiceNative) and the package-private
    // ServiceManagerProxy (getNativeServiceManager -> the gid-1 context object).
    const sm_cls = findGlobalClass(env, "android/os/ServiceManager") orelse return -1;
    defer env.*.*.DeleteGlobalRef.?(env, @ptrCast(sm_cls));
    const sm_methods = [_]jni.JNINativeMethod{
        jm("waitForServiceNative", "(Ljava/lang/String;)Landroid/os/IBinder;", &smWaitForServiceNative),
    };
    if (env.*.*.RegisterNatives.?(env, sm_cls, &sm_methods, sm_methods.len) != 0) return -1;

    const smp_cls = findGlobalClass(env, "android/os/ServiceManagerProxy") orelse return -1;
    defer env.*.*.DeleteGlobalRef.?(env, @ptrCast(smp_cls));
    const smp_methods = [_]jni.JNINativeMethod{
        jm("getNativeServiceManager", "()Landroid/os/IBinder;", &smpGetNativeServiceManager),
    };
    if (env.*.*.RegisterNatives.?(env, smp_cls, &smp_methods, smp_methods.len) != 0) return -1;

    // dev.artzig.BinderSession: process identity/lifecycle (replaces
    // HostedBinderRuntime.bootstrap / exitProcessOnDisconnect / assignedUid).
    const bs_cls = findGlobalClass(env, "dev/artzig/BinderSession") orelse return -1;
    defer env.*.*.DeleteGlobalRef.?(env, @ptrCast(bs_cls));
    const bs_methods = [_]jni.JNINativeMethod{
        jm("setIdentity", "(Ljava/lang/String;Z)V", &bsSetIdentity),
        jm("setExitOnDisconnect", "()V", &bsSetExitOnDisconnect),
        jm("assignedUid", "()I", &bsAssignedUid),
    };
    if (env.*.*.RegisterNatives.?(env, bs_cls, &bs_methods, bs_methods.len) != 0) return -1;

    return 0;
}
