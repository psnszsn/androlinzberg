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
const builtin = @import("builtin");
const linux = std.os.linux;
const E = linux.E;
const fd_t = linux.fd_t;
const Client = @import("Client.zig");
const Server = @import("Server.zig");
const ObjectRef = Client.ObjectRef;
const ObjectEntry = Client.ObjectEntry;
const InHeader = Server.Message.Header;
const OutHeader = Client.Message.Header;

const alloc = std.heap.c_allocator;

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
    message: ?[]u8 = null,

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
            for (p.fds.items) |fd| if (fd >= 0) sysClose(fd);
        }
        for (p.entries.items) |e| if (e.fd >= 0) sysClose(e.fd);
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
            var buf: [4]u8 = undefined;
            const len = std.unicode.utf8Encode(unit, &buf) catch blk: {
                // A lone surrogate: emit the replacement character.
                break :blk std.unicode.utf8Encode(0xFFFD, &buf) catch unreachable;
            };
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

    fn createLocal(class: *AIBinder_Class, user_data: ?*anyopaque) *AIBinder {
        const b = alloc.create(AIBinder) catch @panic("oom");
        const rt = Runtime.get();
        rt.mu.lock();
        const id = rt.next_local_id;
        rt.next_local_id += 1;
        b.* = .{ .strong = .init(1), .kind = .local, .class = class, .user_data = user_data, .id = id, .dead = .init(false) };
        rt.locals.put(alloc, id, b) catch @panic("oom");
        rt.mu.unlock();
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
    mu: Mutex = .{},
    cv: Condition = .{},
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
    fd: fd_t,
    peer_id: u32 = 0,
    pid: i32 = 0,
    uid: u32 = 0,
    write_mu: Mutex = .{},
    mu: Mutex = .{},
    next_request_id: std.atomic.Value(u64) = .init(1),
    next_local_id: u64 = 1,
    pending: std.AutoHashMapUnmanaged(u64, *PendingCall) = .empty,
    locals: std.AutoHashMapUnmanaged(u64, *AIBinder) = .empty,
    export_uses: std.AutoHashMapUnmanaged(u64, u64) = .empty,
    proxies: std.AutoHashMapUnmanaged(u64, *AIBinder) = .empty,
    death_links: std.ArrayListUnmanaged(DeathLink) = .empty,
    /// The routing id of the call each thread is currently servicing, for
    /// nested-transaction parent tracking.
    servicing: Mutex = .{},

    const DeathLink = struct { global_id: u64, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque };

    var instance: ?*Runtime = null;
    var connect_mu: Mutex = .{};
    threadlocal var current_routing: u64 = 0;

    fn get() *Runtime {
        if (instance) |r| return r;
        connect_mu.lock();
        defer connect_mu.unlock();
        if (instance) |r| return r;
        const r = connect() catch |e| std.debug.panic("libbinder_ndk: cannot connect to broker: {s}", .{@errorName(e)});
        instance = r;
        return r;
    }

    fn connect() !*Runtime {
        const path = getenvSlice("AZK_BINDER_SOCKET") orelse return error.NoSocket;
        const cookie = getenvSlice("AZK_BINDER_COOKIE") orelse "";
        const pkg = getenvSlice("AZK_BINDER_PACKAGE") orelse "";
        const fd = try sysSocket(linux.AF.UNIX, linux.SOCK.STREAM | linux.SOCK.CLOEXEC, 0);
        errdefer sysClose(fd);
        var addr = SunAddr{};
        if (path.len >= addr.path.len) return error.NameTooLong;
        @memcpy(addr.path[0..path.len], path);
        addr.path[path.len] = 0;
        try sysConnect(fd, @ptrCast(&addr), @sizeOf(SunAddr));

        const r = try alloc.create(Runtime);
        r.* = .{ .fd = fd };

        // hello
        var hb = std.ArrayListUnmanaged(u8).empty;
        defer hb.deinit(alloc);
        const hello = Client.Message.Hello{ .cookie_len = @intCast(cookie.len), .package_len = @intCast(pkg.len) };
        hb.appendSlice(alloc, std.mem.asBytes(&hello)) catch @panic("oom");
        hb.appendSlice(alloc, cookie) catch @panic("oom");
        hb.appendSlice(alloc, pkg) catch @panic("oom");
        try r.sendFrame(.{ .tag = Client.Message.Tag.hello, .bytes_len = @intCast(hb.items.len) }, hb.items, &.{});

        // welcome (synchronous, before the reader thread starts)
        var wh: InHeader = undefined;
        var wfds: [0]fd_t = undefined;
        _ = try r.recvHeader(&wh, &wfds);
        if (wh.tag != .welcome) return error.BadHello;
        const wbody = try alloc.alloc(u8, wh.bytes_len);
        defer alloc.free(wbody);
        try r.recvExact(wbody);
        const welcome = std.mem.bytesToValue(Server.Message.Welcome, wbody[0..@sizeOf(Server.Message.Welcome)]);
        r.peer_id = welcome.peer_id;
        r.pid = welcome.pid;
        r.uid = welcome.uid;

        const thread = try std.Thread.spawn(.{}, readLoop, .{r});
        thread.detach();
        return r;
    }

    // ----- framing (blocking sockets; fds via SCM_RIGHTS on the first byte) ---

    fn sendFrame(r: *Runtime, header_partial: OutHeader, body: []const u8, fds: []const fd_t) !void {
        var header = header_partial;
        header.fd_count = @intCast(fds.len);
        var frame = std.ArrayListUnmanaged(u8).empty;
        defer frame.deinit(alloc);
        frame.appendSlice(alloc, std.mem.asBytes(&header)) catch @panic("oom");
        frame.appendSlice(alloc, body) catch @panic("oom");

        r.write_mu.lock();
        defer r.write_mu.unlock();

        var iov = [_]std.posix.iovec_const{.{ .base = frame.items.ptr, .len = frame.items.len }};
        var cmsg_buf: [256]u8 align(@alignOf(usize)) = undefined;
        var msg = std.mem.zeroes(linux.msghdr_const);
        msg.iov = &iov;
        msg.iovlen = 1;
        if (fds.len > 0) {
            const space = cmsgSpace(fds.len * @sizeOf(fd_t));
            @memset(cmsg_buf[0..space], 0);
            const cmsg: *cmsghdr = @ptrCast(@alignCast(&cmsg_buf));
            cmsg.len = cmsgLen(fds.len * @sizeOf(fd_t));
            cmsg.level = linux.SOL.SOCKET;
            cmsg.type = 0x01; // SCM_RIGHTS
            const data: [*]fd_t = @ptrCast(@alignCast(cmsgData(cmsg)));
            for (fds, 0..) |fd, i| data[i] = fd;
            msg.control = &cmsg_buf;
            msg.controllen = @intCast(space);
        }
        var sent = try sysSendmsg(r.fd, &msg);
        // Remaining bytes (fds already attached to the first byte) go plain.
        while (sent < frame.items.len) {
            sent += try sysWrite(r.fd, frame.items[sent..]);
        }
    }

    fn recvExact(r: *Runtime, buf: []u8) !void {
        var got: usize = 0;
        while (got < buf.len) {
            const n = try sysRead(r.fd, buf[got..]);
            if (n == 0) return error.Closed;
            got += n;
        }
    }

    /// Reads a 32-byte header, capturing any SCM_RIGHTS fds into `out_fds`
    /// (up to its length). Returns the number of fds received.
    fn recvHeader(r: *Runtime, header: *InHeader, out_fds: []fd_t) !usize {
        var iov = [_]std.posix.iovec{.{ .base = @ptrCast(header), .len = @sizeOf(InHeader) }};
        var cmsg_buf: [256]u8 align(@alignOf(usize)) = undefined;
        var msg = std.mem.zeroes(linux.msghdr);
        msg.iov = &iov;
        msg.iovlen = 1;
        msg.control = &cmsg_buf;
        msg.controllen = cmsg_buf.len;
        var got: usize = 0;
        var fd_count: usize = 0;
        while (got < @sizeOf(InHeader)) {
            iov[0] = .{ .base = @ptrCast(@as([*]u8, @ptrCast(header)) + got), .len = @sizeOf(InHeader) - got };
            const n = try recvmsg(r.fd, &msg, 0);
            if (n == 0) return error.Closed;
            // Harvest fds from the control buffer on the recv that carried them.
            var cmsg: ?*cmsghdr = firstCmsg(&msg);
            while (cmsg) |c| : (cmsg = nextCmsg(&msg, c)) {
                if (c.level == linux.SOL.SOCKET and c.type == 0x01) {
                    const bytes = c.len - cmsgLen(0);
                    const count = bytes / @sizeOf(fd_t);
                    const data: [*]fd_t = @ptrCast(@alignCast(cmsgData(c)));
                    var i: usize = 0;
                    while (i < count) : (i += 1) {
                        if (fd_count < out_fds.len) {
                            out_fds[fd_count] = data[i];
                            fd_count += 1;
                        } else {
                            sysClose(data[i]);
                        }
                    }
                }
            }
            msg.controllen = 0; // fds only arrive with the first byte
            got += n;
        }
        return fd_count;
    }

    // -------------------------------------------------------------- reader

    fn readLoop(r: *Runtime) void {
        while (true) {
            var header: InHeader = undefined;
            var fdbuf: [Client.limits.max_fds]fd_t = undefined;
            const fd_count = r.recvHeader(&header, &fdbuf) catch break;
            if (header.magic != Client.magic or header.version != Client.version) break;
            const body = alloc.alloc(u8, header.bytes_len) catch @panic("oom");
            r.recvExact(body) catch {
                alloc.free(body);
                break;
            };
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
                    r.mu.lock();
                    const call = r.pending.get(header.request_id);
                    r.mu.unlock();
                    if (call) |c| {
                        c.mu.lock();
                        c.nested.append(alloc, in) catch @panic("oom");
                        c.cv.signal();
                        c.mu.unlock();
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
                r.mu.lock();
                const call = r.pending.fetchRemove(header.request_id);
                r.mu.unlock();
                if (call) |kv| {
                    const c = kv.value;
                    const parcel = r.inflate(rest, rep.object_count, rep.data_len, fds) catch AParcel.create();
                    c.mu.lock();
                    c.reply = parcel;
                    c.reply_status = rep.status;
                    c.done = true;
                    c.cv.signal();
                    c.mu.unlock();
                } else {
                    for (fds) |fd| if (fd >= 0) sysClose(fd);
                }
            },
            .service_result => {
                const sr = std.mem.bytesToValue(Server.Message.ServiceResult, body[0..@sizeOf(Server.Message.ServiceResult)]);
                const names = body[@sizeOf(Server.Message.ServiceResult)..];
                r.mu.lock();
                const call = r.pending.fetchRemove(header.request_id);
                r.mu.unlock();
                if (call) |kv| {
                    const c = kv.value;
                    c.mu.lock();
                    c.svc_status = sr.status;
                    c.svc_ref = sr.object;
                    c.svc_names = alloc.dupe(u8, names) catch @panic("oom");
                    c.done = true;
                    c.cv.signal();
                    c.mu.unlock();
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
                for (fds) |fd| if (fd >= 0) sysClose(fd);
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
        r.mu.lock();
        const target = if (in.target.kind == .local) r.locals.get(in.target.value) else null;
        r.mu.unlock();
        var out = AParcel.create();
        defer out.destroy();
        var status = Client.Message.Reply.Status.ok;
        if (target == null or target.?.class == null or target.?.class.?.on_transact == null) {
            status = .failed;
        } else {
            const prev = current_routing;
            current_routing = in.routing_id;
            const rc = target.?.class.?.on_transact.?(target.?, in.code, in.parcel, out);
            current_routing = prev;
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
        r.mu.lock();
        defer r.mu.unlock();
        const uses = (r.export_uses.get(b.id) orelse 0) + 1;
        r.export_uses.put(alloc, b.id, uses) catch @panic("oom");
        return b.id;
    }

    fn proxyFor(r: *Runtime, global_id: u64) *AIBinder {
        r.mu.lock();
        defer r.mu.unlock();
        if (r.proxies.get(global_id)) |b| {
            _ = b.strong.fetchAdd(1, .monotonic);
            return b;
        }
        const b = alloc.create(AIBinder) catch @panic("oom");
        b.* = .{ .strong = .init(1), .kind = .remote, .id = global_id, .dead = .init(false) };
        r.proxies.put(alloc, global_id, b) catch @panic("oom");
        return b;
    }

    fn releaseProxy(r: *Runtime, b: *AIBinder) void {
        r.mu.lock();
        if (r.proxies.get(b.id) == b) _ = r.proxies.remove(b.id);
        r.mu.unlock();
        var body: [@sizeOf(Client.Message.Release)]u8 = undefined;
        const rel = Client.Message.Release{ .object = ObjectRef.remote(b.id), .count = 1 };
        @memcpy(&body, std.mem.asBytes(&rel));
        r.sendFrame(.{ .tag = Client.Message.Tag.release, .bytes_len = body.len }, &body, &.{}) catch {};
        alloc.destroy(b);
    }

    fn maybeRelease(r: *Runtime, id: u64, uses: u64) void {
        r.mu.lock();
        const drop = (r.export_uses.get(id) orelse 0) == uses;
        if (drop) {
            _ = r.export_uses.remove(id);
            _ = r.locals.remove(id);
        }
        r.mu.unlock();
        if (drop) {
            var body: [@sizeOf(ObjectRef)]u8 = undefined;
            @memcpy(&body, std.mem.asBytes(&ObjectRef.local(id)));
            r.sendFrame(.{ .tag = Client.Message.Tag.release_ack, .bytes_len = body.len }, &body, &.{}) catch {};
        }
    }

    fn notifyDeath(r: *Runtime, global_id: u64) void {
        r.mu.lock();
        if (r.proxies.get(global_id)) |b| b.dead.store(true, .monotonic);
        var links = std.ArrayListUnmanaged(DeathLink).empty;
        defer links.deinit(alloc);
        for (r.death_links.items) |l| {
            if (l.global_id == global_id) links.append(alloc, l) catch @panic("oom");
        }
        r.mu.unlock();
        for (links.items) |l| if (l.recipient.on_died) |cb| cb(l.cookie);
    }

    fn onDisconnected(r: *Runtime) void {
        r.mu.lock();
        var it = r.pending.valueIterator();
        while (it.next()) |cp| {
            const c = cp.*;
            c.mu.lock();
            c.failed = true;
            c.done = true;
            c.cv.signal();
            c.mu.unlock();
        }
        r.mu.unlock();
    }

    // ---------------------------------------------------------- transact

    fn transact(r: *Runtime, target: *AIBinder, code: u32, in: *AParcel, out: ?*AParcel, flags: u32) i32 {
        if (target.kind != .remote) return STATUS_BAD_VALUE;
        if (target.dead.load(.monotonic)) return STATUS_DEAD_OBJECT;
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
            r.mu.lock();
            r.pending.put(alloc, request_id, call.?) catch @panic("oom");
            r.mu.unlock();
        }
        r.sendFrame(.{
            .tag = Client.Message.Tag.transact,
            .bytes_len = @intCast(body.items.len),
            .request_id = request_id,
            .routing_id = current_routing,
        }, body.items, fds.items) catch {
            if (call) |c| {
                r.mu.lock();
                _ = r.pending.remove(request_id);
                r.mu.unlock();
                alloc.destroy(c);
            }
            return STATUS_DEAD_OBJECT;
        };
        if (oneway) return STATUS_OK;
        return r.awaitReply(call.?, out);
    }

    fn awaitReply(r: *Runtime, call: *PendingCall, out: ?*AParcel) i32 {
        defer alloc.destroy(call);
        while (true) {
            call.mu.lock();
            while (!call.done and call.nested.items.len == 0) call.cv.wait(&call.mu);
            const nested: ?*Incoming = if (call.nested.items.len > 0) call.nested.orderedRemove(0) else null;
            const done = call.done and nested == null;
            call.mu.unlock();
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
        r.mu.lock();
        r.pending.put(alloc, request_id, call) catch @panic("oom");
        r.mu.unlock();
        r.sendFrame(.{ .tag = tag, .bytes_len = @intCast(body_extra.len), .request_id = request_id }, body_extra, &.{}) catch {
            r.mu.lock();
            _ = r.pending.remove(request_id);
            r.mu.unlock();
            call.failed = true;
            call.done = true;
            return call;
        };
        // Wait, servicing nested transactions like awaitReply.
        while (true) {
            call.mu.lock();
            while (!call.done and call.nested.items.len == 0) call.cv.wait(&call.mu);
            const nested: ?*Incoming = if (call.nested.items.len > 0) call.nested.orderedRemove(0) else null;
            const done = call.done and nested == null;
            call.mu.unlock();
            if (nested) |n| {
                r.dispatch(n);
                continue;
            }
            if (done) return call;
        }
    }
};

// -------------------------------- cmsg helpers (portable across libc layout)

const cmsghdr = extern struct { len: usize, level: i32, type: i32 };
fn cmsgAlign(n: usize) usize {
    return (n + @sizeOf(usize) - 1) & ~@as(usize, @sizeOf(usize) - 1);
}
fn cmsgLen(n: usize) usize {
    return cmsgAlign(@sizeOf(cmsghdr)) + n;
}
fn cmsgSpace(n: usize) usize {
    return cmsgAlign(@sizeOf(cmsghdr)) + cmsgAlign(n);
}
fn cmsgData(c: *cmsghdr) [*]u8 {
    return @as([*]u8, @ptrCast(c)) + cmsgAlign(@sizeOf(cmsghdr));
}
fn firstCmsg(msg: *linux.msghdr) ?*cmsghdr {
    if (msg.controllen < @sizeOf(cmsghdr)) return null;
    return @ptrCast(@alignCast(msg.control));
}
fn nextCmsg(msg: *linux.msghdr, c: *cmsghdr) ?*cmsghdr {
    const next = @as([*]u8, @ptrCast(c)) + cmsgAlign(c.len);
    const base = @as([*]u8, @ptrCast(msg.control));
    const used = @intFromPtr(next) - @intFromPtr(base);
    if (used + @sizeOf(cmsghdr) > msg.controllen) return null;
    return @ptrCast(@alignCast(next));
}

// ----- raw syscall + pthread wrappers (a dlopen'd library has no std.Io loop)

const SunAddr = extern struct { family: u16 = linux.AF.UNIX, path: [108]u8 = undefined };

fn getenvSlice(name: [*:0]const u8) ?[]const u8 {
    const v = std.c.getenv(name) orelse return null;
    return std.mem.span(v);
}
fn sysClose(fd: fd_t) void {
    _ = linux.close(fd);
}
fn sysDup(fd: fd_t) !fd_t {
    const rc = linux.dup(fd);
    if (linux.errno(rc) != .SUCCESS) return error.Dup;
    return @intCast(rc);
}
fn sysSocket(domain: u32, ty: u32, proto: u32) !fd_t {
    const rc = linux.socket(domain, ty, proto);
    if (linux.errno(rc) != .SUCCESS) return error.Socket;
    return @intCast(rc);
}
fn sysConnect(fd: fd_t, addr: *const anyopaque, len: u32) !void {
    const rc = linux.connect(fd, addr, len);
    if (linux.errno(rc) != .SUCCESS) return error.Connect;
}
fn sysRead(fd: fd_t, buf: []u8) !usize {
    while (true) {
        const rc = linux.read(fd, buf.ptr, buf.len);
        switch (linux.errno(rc)) {
            .SUCCESS => return @intCast(rc),
            .INTR => continue,
            else => return error.Read,
        }
    }
}
fn sysWrite(fd: fd_t, buf: []const u8) !usize {
    while (true) {
        const rc = linux.write(fd, buf.ptr, buf.len);
        switch (linux.errno(rc)) {
            .SUCCESS => return @intCast(rc),
            .INTR => continue,
            else => return error.Write,
        }
    }
}
fn sysSendmsg(fd: fd_t, msg: *const linux.msghdr_const) !usize {
    while (true) {
        const rc = linux.sendmsg(fd, msg, 0);
        switch (linux.errno(rc)) {
            .SUCCESS => return @intCast(rc),
            .INTR => continue,
            else => return error.Send,
        }
    }
}
fn recvmsg(fd: fd_t, msg: *linux.msghdr, flags: u32) !usize {
    while (true) {
        const rc = linux.recvmsg(fd, msg, flags);
        switch (linux.errno(rc)) {
            .SUCCESS => return @intCast(rc),
            .INTR => continue,
            else => return error.Recv,
        }
    }
}

/// pthread mutex/condition (libc is linked; all-zero is the static initializer).
const Mutex = struct {
    inner: std.c.pthread_mutex_t = std.mem.zeroes(std.c.pthread_mutex_t),
    fn lock(m: *Mutex) void {
        _ = std.c.pthread_mutex_lock(&m.inner);
    }
    fn unlock(m: *Mutex) void {
        _ = std.c.pthread_mutex_unlock(&m.inner);
    }
};
const Condition = struct {
    inner: std.c.pthread_cond_t = std.mem.zeroes(std.c.pthread_cond_t),
    fn wait(c: *Condition, m: *Mutex) void {
        _ = std.c.pthread_cond_wait(&c.inner, &m.inner);
    }
    fn signal(c: *Condition) void {
        _ = std.c.pthread_cond_signal(&c.inner);
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
    if (message) |m| s.message = alloc.dupe(u8, std.mem.span(m)) catch null;
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
    if (message) |m| s.message = alloc.dupe(u8, std.mem.span(m)) catch null;
    return s;
}
export fn AStatus_fromStatus(status: i32) callconv(.c) *AStatus {
    const s = AStatus.create(if (status == STATUS_OK) EX_NONE else EX_TRANSACTION_FAILED);
    s.status = status;
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
    if (!allocator(string_data, @intCast(utf8.len), &buffer)) return STATUS_NO_MEMORY;
    if (buffer) |b| @memcpy(b[0..utf8.len], utf8);
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
                Runtime.get().mu.lock();
                const local = Runtime.get().locals.get(e.value);
                Runtime.get().mu.unlock();
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
    const dup = sysDup(fd) catch return STATUS_BAD_VALUE;
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
        if (p.readString()) |m| alloc.free(m) else |_| {}
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
    return @intCast(Runtime.get().uid);
}
export fn AIBinder_getCallingPid() callconv(.c) i32 {
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
    alloc.destroy(recipient);
}
export fn AIBinder_linkToDeath(binder: *AIBinder, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque) callconv(.c) i32 {
    if (binder.kind != .remote) return STATUS_INVALID_OPERATION;
    const r = Runtime.get();
    r.mu.lock();
    r.death_links.append(alloc, .{ .global_id = binder.id, .recipient = recipient, .cookie = cookie }) catch @panic("oom");
    r.mu.unlock();
    var body: [@sizeOf(ObjectRef)]u8 = undefined;
    @memcpy(&body, std.mem.asBytes(&ObjectRef.remote(binder.id)));
    r.sendFrame(.{ .tag = Client.Message.Tag.link_death, .bytes_len = body.len }, &body, &.{}) catch {};
    return STATUS_OK;
}
const STATUS_INVALID_OPERATION: i32 = -@as(i32, @intCast(@intFromEnum(E.NOSYS)));
export fn AIBinder_unlinkToDeath(binder: *AIBinder, recipient: *AIBinder_DeathRecipient, cookie: ?*anyopaque) callconv(.c) i32 {
    const r = Runtime.get();
    r.mu.lock();
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
    r.mu.unlock();
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
        alloc.destroy(call);
    }
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
        alloc.destroy(call);
    }
    if (call.svc_status != .ok or call.svc_ref.kind == .null) return null;
    return r.proxyFor(call.svc_ref.value);
}
export fn AServiceManager_checkService(instance: [*:0]const u8) callconv(.c) ?*AIBinder {
    return lookup(instance, false);
}
export fn AServiceManager_getService(instance: [*:0]const u8) callconv(.c) ?*AIBinder {
    return lookup(instance, true);
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
var park_mu: Mutex = .{};
var park_cv: Condition = .{};
export fn ABinderProcess_joinThreadPool() callconv(.c) void {
    // The reader thread services incoming calls; a joiner parks forever.
    _ = Runtime.get();
    park_mu.lock();
    while (true) park_cv.wait(&park_mu);
}
