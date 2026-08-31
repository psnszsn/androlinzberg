//! The hosted Binder transaction router.
//!
//! Every ART process in a hosted session connects to one broker. The broker
//! owns the global object graph: it assigns non-reused 64-bit object ids,
//! translates object references between peers, tracks remote reference
//! counts and death links, forwards descriptors, and routes synchronous calls
//! (including nested callbacks to the waiting thread) and ordered oneway
//! calls. It never interprets Parcel payloads; only the object side table.
//!
//! Object possession is a capability: a peer may transact on an object it
//! owns, looked up in the registry, or received in a valid transaction.
//!
//! Concurrency: one reader task and one writer task per peer plus the accept
//! loop, all in `group`. State changes happen under `mutex`; socket writes
//! never do. Outgoing frames are queued per peer so a slow peer cannot block
//! the reader of another peer.
const Broker = @This();

const std = @import("std");
const Io = std.Io;
const net = Io.net;
const Allocator = std.mem.Allocator;
const assert = std.debug.assert;
const Client = @import("Client.zig");
const Server = @import("Server.zig");
const Connection = @import("Connection.zig");
const prop_area = @import("prop_area.zig");
const ObjectRef = Client.ObjectRef;
const ObjectEntry = Client.ObjectEntry;
const limits = Client.limits;

pub const log = std.log.scoped(.binder_broker);

gpa: Allocator,
io: Io,
mutex: Io.Mutex = .init,
group: Io.Group = .init,
listener: net.Server,
socket_path: []const u8,
cookie: []const u8,
/// Presented only by the session's trusted system process.
system_cookie: []const u8,
/// Package name -> Android uid for this session; uids are never reused.
packages: std.StringHashMapUnmanaged(u32) = .empty,
/// Session-wide system property area; the broker is the only writer
/// (source note: init's property_service).
props: prop_area.Area,
next_app_uid: u32,
shutting_down: bool = false,
peers: std.AutoHashMapUnmanaged(u32, *Peer) = .empty,
next_peer_id: u32 = 1,
objects: std.AutoHashMapUnmanaged(u64, *Object) = .empty,
next_object_id: u64 = 1,
local_index: std.AutoHashMapUnmanaged(LocalKey, u64) = .empty,
services: std.StringHashMapUnmanaged(u64) = .empty,
waiters: std.ArrayList(Waiter) = .empty,
calls: std.AutoHashMapUnmanaged(u64, Call) = .empty,
next_routing_id: u64 = 1,

/// Bytes a peer may have queued before it is considered unresponsive.
pub const max_outbox_bytes: usize = 64 * 1024 * 1024;
const read_buffer_len = 64 * 1024;
const write_buffer_len = 64 * 1024;

pub const Options = struct {
    socket_path: []const u8,
    cookie: []const u8,
    system_cookie: []const u8,
    /// File every session process maps read-only for `SystemProperties`.
    prop_area_path: []const u8,
    first_app_uid: u32 = Client.default_app_uid,
    kernel_backlog: u31 = 16,
};

const LocalKey = struct { peer: u32, local_id: u64 };

const Waiter = struct { peer: u32, request_id: u64, name: []u8 };

const Call = struct {
    caller: u32,
    caller_request: u64,
    target: u32,
    parent: u64,
};

const Object = struct {
    id: u64,
    owner: u32,
    local_id: u64,
    dead: bool = false,
    /// Peer id -> outstanding deliveries.
    holders: std.AutoHashMapUnmanaged(u32, u32) = .empty,
    /// Peers with a death link.
    links: std.AutoHashMapUnmanaged(u32, void) = .empty,
    /// Registry names keeping the object alive.
    service_refs: u32 = 0,
    /// `object_released` was sent; waiting for the owner's `release_ack` or
    /// for the owner to reference the object again (which revives it).
    releasing: bool = false,
    /// Local references to this object the broker has processed from the
    /// owner. Echoed in `object_released` so the owner can tell whether it has
    /// re-exports in flight that the broker has not seen yet.
    uses: u64 = 0,

    fn deinit(o: *Object, gpa: Allocator) void {
        o.holders.deinit(gpa);
        o.links.deinit(gpa);
        gpa.destroy(o);
    }
};

const Frame = struct {
    bytes: []u8,
    fds: []Io.File,
};

const Peer = struct {
    broker: *Broker,
    id: u32,
    pid: i32,
    /// Linux uid from SO_PEERCRED (always the session user).
    uid: u32,
    /// Android identity assigned at hello.
    android_uid: u32 = 0,
    role: Server.Message.Welcome.Role = .app,
    package: []u8 = &.{},
    conn: Connection,
    read_buffer: []u8,
    write_buffer: []u8,
    alive: bool = true,
    hello_done: bool = false,
    /// Global ids -> outstanding deliveries to this peer.
    holdings: std.AutoHashMapUnmanaged(u64, u32) = .empty,
    out_mutex: Io.Mutex = .init,
    out_cond: Io.Condition = .init,
    outbox: std.ArrayList(Frame) = .empty,
    outbox_bytes: usize = 0,
    closing: bool = false,
    writer_done: bool = false,

    fn enqueue(p: *Peer, frame: Frame) void {
        const io = p.broker.io;
        p.out_mutex.lockUncancelable(io);
        defer p.out_mutex.unlock(io);
        if (p.closing or p.outbox_bytes + frame.bytes.len > max_outbox_bytes) {
            p.broker.destroyFrame(frame);
            if (!p.closing) {
                log.warn("peer {d} unresponsive; disconnecting", .{p.id});
                p.closing = true;
            }
            p.out_cond.signal(io);
            return;
        }
        p.outbox.append(p.broker.gpa, frame) catch {
            p.broker.destroyFrame(frame);
            p.closing = true;
            p.out_cond.signal(io);
            return;
        };
        p.outbox_bytes += frame.bytes.len;
        p.out_cond.signal(io);
    }

    fn requestClose(p: *Peer) void {
        const io = p.broker.io;
        p.out_mutex.lockUncancelable(io);
        p.closing = true;
        p.out_cond.signal(io);
        p.out_mutex.unlock(io);
    }
};

pub fn init(gpa: Allocator, io: Io, options: Options) !*Broker {
    const b = try gpa.create(Broker);
    errdefer gpa.destroy(b);
    const socket_path = try gpa.dupe(u8, options.socket_path);
    errdefer gpa.free(socket_path);
    const cookie = try gpa.dupe(u8, options.cookie);
    errdefer gpa.free(cookie);
    const system_cookie = try gpa.dupe(u8, options.system_cookie);
    errdefer gpa.free(system_cookie);
    var props = try prop_area.Area.create(io, options.prop_area_path);
    errdefer props.deinit();
    seedProperties(&props);
    const address = try net.UnixAddress.init(socket_path);
    const listener = try address.listen(io, .{ .kernel_backlog = options.kernel_backlog });
    b.* = .{
        .gpa = gpa,
        .io = io,
        .listener = listener,
        .socket_path = socket_path,
        .cookie = cookie,
        .system_cookie = system_cookie,
        .props = props,
        .next_app_uid = options.first_app_uid,
    };
    return b;
}

/// Boot-time defaults, previously the static block of the per-process
/// `SystemProperties` shim.
fn seedProperties(props: *prop_area.Area) void {
    const seeds = [_][2][]const u8{
        .{ "ro.build.version.sdk", "35" },
        .{ "ro.build.version.release", "15" },
        .{ "ro.build.version.codename", "REL" },
        .{ "ro.hardware", "artzig" },
        .{ "ro.product.cpu.abi", "x86_64" },
        .{ "debug.force_rtl", "0" },
    };
    for (seeds) |seed| props.set(seed[0], seed[1]) catch unreachable;
}

/// Accepts peers until `shutdown` is called, then tears every peer down.
pub fn run(b: *Broker) !void {
    const io = b.io;
    while (true) {
        const stream = b.listener.accept(io) catch |err| {
            if (b.isShuttingDown()) break;
            log.warn("accept failed: {t}", .{err});
            continue;
        };
        if (b.isShuttingDown()) {
            stream.close(io);
            break;
        }
        b.admit(stream) catch |err| {
            log.warn("could not admit peer: {t}", .{err});
            stream.close(io);
        };
    }
    b.mutex.lockUncancelable(io);
    var it = b.peers.valueIterator();
    while (it.next()) |peer| {
        peer.*.enqueue(b.encode(Server.serveBodylessMessage, .{.shutdown}) catch continue);
        peer.*.requestClose();
        // Wake the reader: the writer closes both directions when it drains.
    }
    for (b.waiters.items) |w| {
        if (b.peers.get(w.peer)) |peer| {
            peer.enqueue(b.encode(Server.serveServiceResult, .{ w.request_id, .shutdown, ObjectRef.null_ref, "" }) catch continue);
        }
        b.gpa.free(w.name);
    }
    b.waiters.clearRetainingCapacity();
    b.mutex.unlock(io);
    b.group.await(io) catch {};
}

fn isShuttingDown(b: *Broker) bool {
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    return b.shutting_down;
}

/// Stops `run`. Safe to call from another task.
pub fn shutdown(b: *Broker) void {
    const io = b.io;
    b.mutex.lockUncancelable(io);
    if (b.shutting_down) {
        b.mutex.unlock(io);
        return;
    }
    b.shutting_down = true;
    b.mutex.unlock(io);
    // Wake the accept loop deterministically: shutting the listener's receive
    // side makes the blocked accept return SocketNotListening.
    const listener_stream: net.Stream = .{ .socket = b.listener.socket };
    listener_stream.shutdown(io, .recv) catch {};
}

pub fn deinit(b: *Broker) void {
    const gpa = b.gpa;
    const io = b.io;
    b.listener.deinit(io);
    b.props.deinit();
    Io.Dir.cwd().deleteFile(io, b.socket_path) catch {};
    var objects = b.objects.valueIterator();
    while (objects.next()) |o| o.*.deinit(gpa);
    b.objects.deinit(gpa);
    b.local_index.deinit(gpa);
    var names = b.services.keyIterator();
    while (names.next()) |name| gpa.free(name.*);
    b.services.deinit(gpa);
    for (b.waiters.items) |w| gpa.free(w.name);
    b.waiters.deinit(gpa);
    b.calls.deinit(gpa);
    b.peers.deinit(gpa);
    var packages = b.packages.keyIterator();
    while (packages.next()) |name| gpa.free(name.*);
    b.packages.deinit(gpa);
    gpa.free(b.socket_path);
    gpa.free(b.cookie);
    gpa.free(b.system_cookie);
    gpa.destroy(b);
}

fn admit(b: *Broker, stream: net.Stream) !void {
    const gpa = b.gpa;
    const io = b.io;
    const creds = try Connection.peerCredentials(stream);
    // The socket lives in a private directory, but only processes of the
    // session's Linux user may join regardless of who learned the cookie.
    if (creds.uid != std.os.linux.getuid()) {
        log.warn("rejecting peer pid {d}: uid {d} is not the session user", .{ creds.pid, creds.uid });
        return error.PeerNotSessionUser;
    }
    const peer = try gpa.create(Peer);
    errdefer gpa.destroy(peer);
    const read_buffer = try gpa.alloc(u8, read_buffer_len);
    errdefer gpa.free(read_buffer);
    const write_buffer = try gpa.alloc(u8, write_buffer_len);
    errdefer gpa.free(write_buffer);
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    peer.* = .{
        .broker = b,
        .id = b.next_peer_id,
        .pid = creds.pid,
        .uid = creds.uid,
        .conn = .init(io, stream, read_buffer, write_buffer),
        .read_buffer = read_buffer,
        .write_buffer = write_buffer,
    };
    b.next_peer_id += 1;
    try b.peers.put(gpa, peer.id, peer);
    errdefer _ = b.peers.remove(peer.id);
    try b.group.concurrent(io, writerTask, .{peer});
    // From here the writer owns the connection until it reports completion;
    // nothing above may run its errdefers.
    b.group.concurrent(io, readerTask, .{peer}) catch |err| {
        _ = b.peers.remove(peer.id);
        b.mutex.unlock(io);
        joinWriterAndFree(peer);
        b.mutex.lockUncancelable(io);
        return err;
    };
}

/// Asks the writer to finish, waits for it (it shuts the socket down), then
/// frees the peer. Must not hold `b.mutex`.
fn joinWriterAndFree(peer: *Peer) void {
    const b = peer.broker;
    const io = b.io;
    const gpa = b.gpa;
    peer.requestClose();
    peer.out_mutex.lockUncancelable(io);
    while (!peer.writer_done) peer.out_cond.waitUncancelable(io, &peer.out_mutex);
    peer.out_mutex.unlock(io);
    peer.conn.deinit();
    peer.outbox.deinit(gpa);
    gpa.free(peer.read_buffer);
    gpa.free(peer.write_buffer);
    gpa.destroy(peer);
}

fn writerTask(peer: *Peer) Io.Cancelable!void {
    const b = peer.broker;
    const io = b.io;
    while (true) {
        peer.out_mutex.lockUncancelable(io);
        while (peer.outbox.items.len == 0 and !peer.closing) {
            peer.out_cond.waitUncancelable(io, &peer.out_mutex);
        }
        if (peer.outbox.items.len == 0) {
            // Closing with nothing left to send. Shut the socket down first:
            // once writer_done is published, removePeer may free the peer.
            peer.conn.stream.shutdown(io, .both) catch {};
            peer.writer_done = true;
            peer.out_cond.broadcast(io);
            peer.out_mutex.unlock(io);
            return;
        }
        const frame = peer.outbox.orderedRemove(0);
        peer.outbox_bytes -= frame.bytes.len;
        peer.out_mutex.unlock(io);
        const ok = writeFrame(peer, frame);
        b.destroyFrame(frame);
        if (!ok) {
            peer.out_mutex.lockUncancelable(io);
            peer.closing = true;
            for (peer.outbox.items) |f| b.destroyFrame(f);
            peer.outbox.clearRetainingCapacity();
            peer.outbox_bytes = 0;
            // Unblock the reader (it removes the peer) before publishing completion.
            peer.conn.stream.shutdown(io, .both) catch {};
            peer.writer_done = true;
            peer.out_cond.broadcast(io);
            peer.out_mutex.unlock(io);
            return;
        }
    }
}

fn writeFrame(peer: *Peer, frame: Frame) bool {
    const w = &peer.conn.writer;
    w.attachFds(frame.fds) catch return false;
    w.interface.writeAll(frame.bytes) catch return false;
    w.interface.flush() catch return false;
    return true;
}

fn destroyFrame(b: *Broker, frame: Frame) void {
    for (frame.fds) |f| f.close(b.io);
    b.gpa.free(frame.fds);
    b.gpa.free(frame.bytes);
}

/// Encodes one broker message with a `Server` helper into an owned frame.
fn encode(b: *Broker, comptime serve_fn: anytype, args: anytype) Allocator.Error!Frame {
    var allocating: Io.Writer.Allocating = .init(b.gpa);
    errdefer allocating.deinit();
    const server: Server = .{ .in = undefined, .out = &allocating.writer };
    @call(.auto, serve_fn, .{&server} ++ args) catch return error.OutOfMemory;
    return .{ .bytes = try allocating.toOwnedSlice(), .fds = &.{} };
}

fn readerTask(peer: *Peer) Io.Cancelable!void {
    const b = peer.broker;
    defer b.removePeer(peer);
    serveLoop(peer) catch |err| switch (err) {
        error.ProtocolError => {},
        else => log.debug("peer {d} left: {t}", .{ peer.id, err }),
    };
}

const ServeError = error{ ProtocolError, Disconnected, OutOfMemory, Canceled };

fn protocolError(peer: *Peer, reason: []const u8) ServeError {
    log.warn("peer {d} (pid {d}): {s}", .{ peer.id, peer.pid, reason });
    peer.enqueue(peer.broker.encode(Server.serveProtocolError, .{reason}) catch return error.ProtocolError);
    peer.requestClose();
    return error.ProtocolError;
}

fn serveLoop(peer: *Peer) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const server: Server = .{ .in = &peer.conn.reader.interface, .out = undefined };
    while (true) {
        const header = server.receiveMessage() catch |err| switch (err) {
            error.EndOfStream => return error.Disconnected,
            error.ReadFailed => return error.Disconnected,
            error.BadMagic, error.BadVersion => return protocolError(peer, "malformed header"),
            error.BodyTooLarge => return protocolError(peer, "oversized message"),
            error.TooManyFds => return protocolError(peer, "too many descriptors"),
        };
        const body = server.in.readAlloc(gpa, header.bytes_len) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            error.EndOfStream, error.ReadFailed => return error.Disconnected,
        };
        defer gpa.free(body);
        // One recvmsg may return the tail of this frame together with the
        // start of the next, descriptor-bearing frame, so more descriptors than
        // announced can be queued; they belong to the frame that announces them.
        if (peer.conn.reader.queuedFdCount() < header.fd_count) {
            peer.conn.reader.closeQueued();
            return protocolError(peer, "frame announces more descriptors than were received");
        }
        const fds = try gpa.alloc(Io.File, header.fd_count);
        peer.conn.reader.takeFds(fds) catch unreachable;
        var fds_owned = true;
        defer if (fds_owned) {
            for (fds) |f| f.close(b.io);
            gpa.free(fds);
        };
        if (!peer.hello_done and header.tag != .hello) return protocolError(peer, "expected hello");
        if (header.fd_count != 0 and header.tag != .transact and header.tag != .reply) {
            return protocolError(peer, "unexpected descriptors");
        }
        switch (header.tag) {
            .hello => try handleHello(peer, header, body),
            .transact => {
                try handleTransact(peer, header, body, fds);
                fds_owned = false;
            },
            .reply => {
                try handleReply(peer, header, body, fds);
                fds_owned = false;
            },
            .release => try handleRelease(peer, header, body),
            .release_ack => try handleReleaseAck(peer, body),
            .link_death => try handleLink(peer, header, body, true),
            .unlink_death => try handleLink(peer, header, body, false),
            .register_service => try handleRegister(peer, header, body),
            .lookup_service => try handleLookup(peer, header, body),
            .list_services => try handleList(peer, header),
            .set_property => try handleSetProperty(peer, header, body),
            _ => return protocolError(peer, "unknown message tag"),
        }
    }
}

fn takeStruct(comptime T: type, body: []const u8) error{Malformed}!T {
    if (body.len < @sizeOf(T)) return error.Malformed;
    var r: Io.Reader = .fixed(body);
    return r.takeStruct(T, .little) catch error.Malformed;
}

fn handleHello(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    _ = header;
    if (peer.hello_done) return protocolError(peer, "duplicate hello");
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    const hello = takeStruct(Client.Message.Hello, body) catch return protocolError(peer, "malformed hello");
    const rest = body[@sizeOf(Client.Message.Hello)..];
    if (@as(usize, hello.cookie_len) + hello.package_len != rest.len or
        hello.cookie_len > limits.max_name_len or hello.package_len > limits.max_name_len)
        return protocolError(peer, "malformed hello");
    const cookie = rest[0..hello.cookie_len];
    const package = rest[hello.cookie_len..];
    if (std.mem.indexOfScalar(u8, package, 0) != null) return protocolError(peer, "malformed hello");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    if (std.mem.eql(u8, cookie, b.system_cookie)) {
        peer.role = .system;
        peer.android_uid = Client.system_uid;
        peer.package = try gpa.dupe(u8, "android");
    } else if (std.mem.eql(u8, cookie, b.cookie)) {
        if (package.len == 0) return protocolError(peer, "app peer without a package name");
        peer.role = .app;
        if (b.packages.get(package)) |uid| {
            peer.android_uid = uid;
        } else {
            const owned = try gpa.dupe(u8, package);
            errdefer gpa.free(owned);
            try b.packages.put(gpa, owned, b.next_app_uid);
            peer.android_uid = b.next_app_uid;
            b.next_app_uid += 1;
        }
        peer.package = try gpa.dupe(u8, package);
    } else {
        return protocolError(peer, "bad launch cookie");
    }
    peer.hello_done = true;
    peer.enqueue(try b.encode(Server.serveWelcome, .{Server.Message.Welcome{
        .peer_id = peer.id,
        .pid = peer.pid,
        .uid = peer.android_uid,
        .role = peer.role,
    }}));
}

/// Registry policy: the system process may claim any name; an app may only
/// claim its own package name or names beneath it.
fn mayRegister(peer: *const Peer, name: []const u8) bool {
    if (peer.role == .system) return true;
    if (!namespacePrefix(peer.package, name)) return false;
    // A dotted-prefix package must not squat inside a longer known package's
    // namespace: `com.example` may not claim `com.example.app.svc` once
    // `com.example.app` exists. Requires `b.mutex` (held by handleRegister).
    var it = peer.broker.packages.iterator();
    while (it.next()) |entry| {
        const other = entry.key_ptr.*;
        if (other.len > peer.package.len and namespacePrefix(other, name)) return false;
    }
    return true;
}

fn namespacePrefix(package: []const u8, name: []const u8) bool {
    if (!std.mem.startsWith(u8, name, package)) return false;
    return name.len == package.len or name[package.len] == '.';
}

/// Resolves a reference the peer sent. Local references intern a new object
/// on first use. Requires `b.mutex`.
fn resolveRef(peer: *Peer, ref: ObjectRef) error{ Invalid, OutOfMemory }!*Object {
    const b = peer.broker;
    switch (ref.kind) {
        .local => {
            const key: LocalKey = .{ .peer = peer.id, .local_id = ref.value };
            if (b.local_index.get(key)) |gid| {
                const existing = b.objects.get(gid).?;
                existing.uses += 1;
                // The owner referenced it again before acknowledging the
                // release: the object stays, with its global id.
                existing.releasing = false;
                return existing;
            }
            const object = try b.gpa.create(Object);
            errdefer b.gpa.destroy(object);
            object.* = .{ .id = b.next_object_id, .owner = peer.id, .local_id = ref.value, .uses = 1 };
            try b.objects.put(b.gpa, object.id, object);
            errdefer _ = b.objects.remove(object.id);
            try b.local_index.put(b.gpa, key, object.id);
            b.next_object_id += 1;
            return object;
        },
        .remote => {
            const object = b.objects.get(ref.value) orelse return error.Invalid;
            if (object.owner == peer.id) return object;
            if (!peer.holdings.contains(object.id)) return error.Invalid;
            return object;
        },
        else => return error.Invalid,
    }
}

/// Produces the reference `target` will see for `object`, recording a
/// delivery when it is remote for that peer. Requires `b.mutex`.
fn deliverRef(b: *Broker, target: *Peer, object: *Object) Allocator.Error!ObjectRef {
    if (object.owner == target.id) return ObjectRef.local(object.local_id);
    // Both maps change together or not at all.
    const holder = try object.holders.getOrPut(b.gpa, target.id);
    if (!holder.found_existing) holder.value_ptr.* = 0;
    errdefer if (holder.value_ptr.* == 0) {
        _ = object.holders.remove(target.id);
    };
    const holding = try target.holdings.getOrPut(b.gpa, object.id);
    if (!holding.found_existing) holding.value_ptr.* = 0;
    holding.value_ptr.* += 1;
    holder.value_ptr.* += 1;
    return ObjectRef.remote(object.id);
}

/// Reverses one `deliverRef` whose transaction never reached `target`.
fn undeliverRef(target: *Peer, object: *Object) void {
    if (object.owner == target.id) return;
    if (target.holdings.getPtr(object.id)) |holding| {
        holding.* -= 1;
        if (holding.* == 0) _ = target.holdings.remove(object.id);
    }
    if (object.holders.getPtr(target.id)) |holder| {
        holder.* -= 1;
        if (holder.* == 0) _ = object.holders.remove(target.id);
    }
}

/// Undoes every delivery recorded in `delivered` (a failed translation or an
/// undeliverable frame) so reference counts do not leak.
fn undeliverAll(b: *Broker, target: *Peer, delivered: []const *Object) void {
    for (delivered) |object| {
        undeliverRef(target, object);
        b.maybeReleaseObject(object) catch {};
    }
}

/// Translates a payload's entries from `from`'s view to `to`'s view.
/// `delivered` records every object delivered to `to`; on error the deliveries
/// are undone before returning, and the caller must undo them itself if the
/// translated frame is not sent.
fn translateEntries(
    b: *Broker,
    from: *Peer,
    to: *Peer,
    payload: Client.Payload,
    fd_count: u16,
    delivered_out: *std.ArrayList(*Object),
) error{ Invalid, OutOfMemory }![]ObjectEntry {
    const out = try b.gpa.alloc(ObjectEntry, payload.entries.len);
    errdefer b.gpa.free(out);
    errdefer {
        b.undeliverAll(to, delivered_out.items);
        delivered_out.clearRetainingCapacity();
    }
    for (out, 0..) |*dst, i| {
        const src = payload.entry(i);
        if (src.offset > limits.max_data_len) return error.Invalid;
        switch (src.kind) {
            .binder_null => dst.* = src,
            .binder_local, .binder_remote => {
                const ref: ObjectRef = if (src.kind == .binder_local) .local(src.value) else .remote(src.value);
                const object = try resolveRef(from, ref);
                try delivered_out.ensureUnusedCapacity(b.gpa, 1);
                const delivered = try b.deliverRef(to, object);
                if (delivered.kind == .remote) delivered_out.appendAssumeCapacity(object);
                dst.* = .{
                    .offset = src.offset,
                    .kind = if (delivered.kind == .local) .binder_local else .binder_remote,
                    .value = delivered.value,
                };
            },
            .file_descriptor => {
                if (src.value >= fd_count) return error.Invalid;
                dst.* = src;
            },
            _ => return error.Invalid,
        }
    }
    return out;
}

fn findNestRequest(b: *Broker, parent: u64, target_peer: u32) u64 {
    var current = parent;
    var guard: usize = 0;
    while (current != 0 and guard < 4096) : (guard += 1) {
        const call = b.calls.get(current) orelse return 0;
        if (call.caller == target_peer) return call.caller_request;
        current = call.parent;
    }
    return 0;
}

fn handleTransact(peer: *Peer, header: Client.Message.Header, body: []const u8, fds: []Io.File) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    const t = takeStruct(Client.Message.Transact, body) catch return protocolError(peer, "malformed transact");
    const payload = Client.Payload.parse(body[@sizeOf(Client.Message.Transact)..], t.object_count, t.data_len) catch
        return protocolError(peer, "malformed transact payload");
    const oneway = (t.flags & 1) != 0;
    if (!oneway and header.request_id == 0) return protocolError(peer, "synchronous transact without request id");

    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const object = resolveRef(peer, t.target) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.Invalid => return protocolError(peer, "transact on an object the peer does not hold"),
    };
    if (header.routing_id != 0) {
        const parent = b.calls.get(header.routing_id) orelse return protocolError(peer, "unknown parent routing id");
        if (parent.target != peer.id) return protocolError(peer, "parent routing id belongs to another peer");
    }
    const owner: ?*Peer = if (object.dead) null else b.peers.get(object.owner);
    if (owner == null or !owner.?.alive) {
        b.closeAll(fds);
        gpa.free(fds);
        if (!oneway) {
            peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
                .request_id = header.request_id,
                .status = .dead_object,
                .handled = false,
            }}));
        }
        return;
    }
    const target = owner.?;
    var delivered: std.ArrayList(*Object) = .empty;
    defer delivered.deinit(gpa);
    const entries = b.translateEntries(peer, target, payload, header.fd_count, &delivered) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.Invalid => return protocolError(peer, "invalid object entry in transact"),
    };
    defer gpa.free(entries);
    errdefer b.undeliverAll(target, delivered.items);
    var routing_id: u64 = 0;
    var nest_request: u64 = 0;
    if (!oneway) {
        routing_id = b.next_routing_id;
        b.next_routing_id += 1;
        try b.calls.put(gpa, routing_id, .{
            .caller = peer.id,
            .caller_request = header.request_id,
            .target = target.id,
            .parent = header.routing_id,
        });
        nest_request = b.findNestRequest(header.routing_id, target.id);
    }
    var frame = try b.encode(Server.serveTransact, .{Server.TransactOptions{
        .target = if (object.owner == target.id) ObjectRef.local(object.local_id) else ObjectRef.remote(object.id),
        .code = t.code,
        .flags = t.flags,
        .caller_pid = peer.pid,
        .caller_uid = peer.android_uid,
        .routing_id = routing_id,
        .nest_request_id = nest_request,
        .entries = entries,
        .data = payload.data,
        .fd_count = header.fd_count,
    }});
    frame.fds = fds;
    target.enqueue(frame);
}

fn handleReply(peer: *Peer, header: Client.Message.Header, body: []const u8, fds: []Io.File) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    const r = takeStruct(Client.Message.Reply, body) catch return protocolError(peer, "malformed reply");
    const payload = Client.Payload.parse(body[@sizeOf(Client.Message.Reply)..], r.object_count, r.data_len) catch
        return protocolError(peer, "malformed reply payload");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const call = b.calls.get(header.routing_id) orelse return protocolError(peer, "reply for unknown call");
    if (call.target != peer.id) return protocolError(peer, "reply from the wrong peer");
    _ = b.calls.remove(header.routing_id);
    const caller = b.peers.get(call.caller);
    if (caller == null or !caller.?.alive) {
        b.closeAll(fds);
        gpa.free(fds);
        return;
    }
    var delivered: std.ArrayList(*Object) = .empty;
    defer delivered.deinit(gpa);
    const entries = b.translateEntries(peer, caller.?, payload, header.fd_count, &delivered) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.Invalid => return protocolError(peer, "invalid object entry in reply"),
    };
    defer gpa.free(entries);
    errdefer b.undeliverAll(caller.?, delivered.items);
    var frame = try b.encode(Server.serveReply, .{Server.ReplyOptions{
        .request_id = call.caller_request,
        .status = r.status,
        .handled = r.handled != 0,
        .binder_status = r.binder_status,
        .entries = entries,
        .data = payload.data,
        .fd_count = header.fd_count,
    }});
    frame.fds = fds;
    caller.?.enqueue(frame);
}

fn closeAll(b: *Broker, fds: []Io.File) void {
    for (fds) |f| f.close(b.io);
}

fn handleRelease(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    _ = header;
    const b = peer.broker;
    const io = b.io;
    const rel = takeStruct(Client.Message.Release, body) catch return protocolError(peer, "malformed release");
    if (rel.object.kind != .remote) return protocolError(peer, "release of a non-remote reference");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const object = b.objects.get(rel.object.value) orelse return; // already gone
    const holding = peer.holdings.getPtr(object.id) orelse return protocolError(peer, "release of an object the peer does not hold");
    if (rel.count > holding.*) return protocolError(peer, "release count exceeds deliveries");
    holding.* -= rel.count;
    const holder = object.holders.getPtr(peer.id) orelse return protocolError(peer, "release bookkeeping mismatch");
    holder.* -= rel.count;
    if (holding.* == 0) {
        _ = peer.holdings.remove(object.id);
        _ = object.holders.remove(peer.id);
    }
    try b.maybeReleaseObject(object);
}

/// Drops an object nobody references. Requires `b.mutex`.
fn maybeReleaseObject(b: *Broker, object: *Object) Allocator.Error!void {
    if (object.holders.count() != 0 or object.service_refs != 0) return;
    if (object.dead) {
        b.destroyObject(object);
        return;
    }
    if (object.releasing) return;
    const owner = b.peers.get(object.owner) orelse {
        b.destroyObject(object);
        return;
    };
    // Keep the object and its local index until the owner acknowledges, so a
    // re-export racing with this notification revives the same global id.
    object.releasing = true;
    owner.enqueue(try b.encode(Server.serveObjectReleased, .{ ObjectRef.local(object.local_id), object.uses }));
}

/// Requires `b.mutex`.
fn destroyObject(b: *Broker, object: *Object) void {
    _ = b.local_index.remove(.{ .peer = object.owner, .local_id = object.local_id });
    _ = b.objects.remove(object.id);
    object.deinit(b.gpa);
}

fn handleReleaseAck(peer: *Peer, body: []const u8) ServeError!void {
    const b = peer.broker;
    const io = b.io;
    const ref = takeStruct(ObjectRef, body) catch return protocolError(peer, "malformed release_ack");
    if (ref.kind != .local) return protocolError(peer, "release_ack must name a local object");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const gid = b.local_index.get(.{ .peer = peer.id, .local_id = ref.value }) orelse return;
    const object = b.objects.get(gid).?;
    // Ignore a stale ack for an object that was revived or re-referenced.
    if (!object.releasing or object.holders.count() != 0 or object.service_refs != 0) return;
    b.destroyObject(object);
}

fn handleLink(peer: *Peer, header: Client.Message.Header, body: []const u8, link: bool) ServeError!void {
    _ = header;
    const b = peer.broker;
    const io = b.io;
    const ref = takeStruct(ObjectRef, body) catch return protocolError(peer, "malformed death link");
    if (ref.kind != .remote) return protocolError(peer, "death link on a non-remote reference");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const object = b.objects.get(ref.value) orelse {
        // Unknown or already reclaimed: treat as dead.
        if (link) peer.enqueue(try b.encode(Server.serveObjectRefMessage, .{ .object_dead, ref }));
        return;
    };
    if (!peer.holdings.contains(object.id) and object.owner != peer.id) return protocolError(peer, "death link on an object the peer does not hold");
    if (link) {
        if (object.dead) {
            peer.enqueue(try b.encode(Server.serveObjectRefMessage, .{ .object_dead, ref }));
            return;
        }
        try object.links.put(b.gpa, peer.id, {});
    } else {
        _ = object.links.remove(peer.id);
    }
}

fn readName(peer: *Peer, name: []const u8, declared: u32) ServeError![]const u8 {
    if (name.len != declared or name.len == 0 or name.len > limits.max_name_len) return protocolError(peer, "malformed service name");
    if (std.mem.indexOfScalar(u8, name, 0) != null) return protocolError(peer, "malformed service name");
    return name;
}

fn handleRegister(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    const reg = takeStruct(Client.Message.RegisterService, body) catch return protocolError(peer, "malformed register_service");
    const name = try readName(peer, body[@sizeOf(Client.Message.RegisterService)..], reg.name_len);
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    if (!mayRegister(peer, name)) {
        log.warn("peer {d} ({s}) may not register '{s}'", .{ peer.id, peer.package, name });
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .denied, ObjectRef.null_ref, "" }));
        return;
    }
    const object = resolveRef(peer, reg.object) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.Invalid => return protocolError(peer, "register_service with an object the peer does not hold"),
    };
    if (object.dead) {
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .not_found, ObjectRef.null_ref, "" }));
        return;
    }
    if (b.services.getEntry(name)) |entry| {
        const existing = b.objects.get(entry.value_ptr.*).?;
        if (existing.owner != peer.id) {
            peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .already_registered, ObjectRef.null_ref, "" }));
            return;
        }
        // The owner replaces its own registration.
        existing.service_refs -= 1;
        entry.value_ptr.* = object.id;
        object.service_refs += 1;
        try b.maybeReleaseObject(existing);
    } else {
        const owned_name = try gpa.dupe(u8, name);
        errdefer gpa.free(owned_name);
        try b.services.put(gpa, owned_name, object.id);
        object.service_refs += 1;
    }
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .ok, ObjectRef.null_ref, "" }));
    // Wake waiters.
    var i: usize = 0;
    while (i < b.waiters.items.len) {
        const w = b.waiters.items[i];
        if (!std.mem.eql(u8, w.name, name)) {
            i += 1;
            continue;
        }
        if (b.peers.get(w.peer)) |waiter| {
            if (waiter.alive) {
                const ref = try b.deliverRef(waiter, object);
                waiter.enqueue(try b.encode(Server.serveServiceResult, .{ w.request_id, .ok, ref, "" }));
            }
        }
        gpa.free(w.name);
        _ = b.waiters.swapRemove(i);
    }
}

fn handleLookup(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    const lookup = takeStruct(Client.Message.LookupService, body) catch return protocolError(peer, "malformed lookup_service");
    const name = try readName(peer, body[@sizeOf(Client.Message.LookupService)..], lookup.name_len);
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    if (b.services.get(name)) |gid| {
        const object = b.objects.get(gid).?;
        const ref = try b.deliverRef(peer, object);
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .ok, ref, "" }));
        return;
    }
    if (lookup.flags.wait and !b.shutting_down) {
        try b.waiters.append(gpa, .{ .peer = peer.id, .request_id = header.request_id, .name = try gpa.dupe(u8, name) });
        return;
    }
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .not_found, ObjectRef.null_ref, "" }));
}

fn handleList(peer: *Peer, header: Client.Message.Header) ServeError!void {
    const b = peer.broker;
    const io = b.io;
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    var names: std.ArrayList(u8) = .empty;
    defer names.deinit(b.gpa);
    var it = b.services.keyIterator();
    while (it.next()) |name| {
        try names.appendSlice(b.gpa, name.*);
        try names.append(b.gpa, 0);
    }
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .ok, ObjectRef.null_ref, names.items }));
}

/// Property policy (source note: init's property_service + SELinux property
/// contexts, reduced): the system process may set anything, but an `ro.*`
/// only while it is unset; app peers may touch only `debug.*` and `log.*`.
fn maySetProperty(peer: *const Peer, name: []const u8) bool {
    const b = peer.broker;
    if (peer.role == .system) {
        if (!std.mem.startsWith(u8, name, "ro.")) return true;
        var buf: [prop_area.value_max]u8 = undefined;
        return b.props.get(name, &buf) == null;
    }
    return std.mem.startsWith(u8, name, "debug.") or std.mem.startsWith(u8, name, "log.");
}

fn handleSetProperty(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    const b = peer.broker;
    const io = b.io;
    const msg = takeStruct(Client.Message.SetProperty, body) catch return protocolError(peer, "malformed set_property");
    const rest = body[@sizeOf(Client.Message.SetProperty)..];
    if (@as(usize, msg.name_len) + msg.value_len != rest.len or msg.name_len == 0 or
        msg.name_len > limits.max_name_len or msg.value_len > limits.max_name_len)
        return protocolError(peer, "malformed set_property");
    const name = rest[0..msg.name_len];
    const value = rest[msg.name_len..];
    if (std.mem.indexOfScalar(u8, name, 0) != null or std.mem.indexOfScalar(u8, value, 0) != null)
        return protocolError(peer, "malformed set_property");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    if (!maySetProperty(peer, name)) {
        log.warn("peer {d} ({s}) may not set property '{s}'", .{ peer.id, peer.package, name });
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .denied, ObjectRef.null_ref, "" }));
        return;
    }
    b.props.set(name, value) catch |err| {
        log.warn("cannot set property '{s}': {t}", .{ name, err });
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .denied, ObjectRef.null_ref, "" }));
        return;
    };
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .ok, ObjectRef.null_ref, "" }));
    // Everyone (the setter included) learns of the change; the area itself is
    // already current, this only drives change callbacks.
    var it = b.peers.valueIterator();
    while (it.next()) |other| {
        if (!other.*.alive or !other.*.hello_done) continue;
        other.*.enqueue(try b.encode(Server.servePropertyChanged, .{name}));
    }
}

/// Tears a peer down: fails its calls, kills its objects, drops its holdings.
fn removePeer(b: *Broker, peer: *Peer) void {
    const gpa = b.gpa;
    const io = b.io;
    b.mutex.lockUncancelable(io);
    peer.alive = false;
    _ = b.peers.remove(peer.id);

    // Calls targeting the peer fail; calls it made are forgotten.
    var dead_calls: std.ArrayList(u64) = .empty;
    defer dead_calls.deinit(gpa);
    var calls = b.calls.iterator();
    while (calls.next()) |entry| {
        const call = entry.value_ptr.*;
        if (call.target == peer.id) {
            if (b.peers.get(call.caller)) |caller| {
                if (caller.alive) caller.enqueue(b.encode(Server.serveReply, .{Server.ReplyOptions{
                    .request_id = call.caller_request,
                    .status = .dead_object,
                    .handled = false,
                }}) catch continue);
            }
            dead_calls.append(gpa, entry.key_ptr.*) catch {};
        } else if (call.caller == peer.id) {
            dead_calls.append(gpa, entry.key_ptr.*) catch {};
        }
    }
    for (dead_calls.items) |id| _ = b.calls.remove(id);

    // Registry names and waiters.
    var removed_names: std.ArrayList([]const u8) = .empty;
    defer removed_names.deinit(gpa);
    var services = b.services.iterator();
    while (services.next()) |entry| {
        const object = b.objects.get(entry.value_ptr.*).?;
        if (object.owner == peer.id) removed_names.append(gpa, entry.key_ptr.*) catch {};
    }
    for (removed_names.items) |name| {
        const kv = b.services.fetchRemove(name).?;
        b.objects.get(kv.value).?.service_refs -= 1;
        gpa.free(kv.key);
    }
    var i: usize = 0;
    while (i < b.waiters.items.len) {
        if (b.waiters.items[i].peer == peer.id) {
            gpa.free(b.waiters.items[i].name);
            _ = b.waiters.swapRemove(i);
        } else i += 1;
    }

    // Objects the peer owns die; objects it holds lose a holder.
    var affected: std.ArrayList(*Object) = .empty;
    defer affected.deinit(gpa);
    var objects = b.objects.valueIterator();
    while (objects.next()) |entry| {
        const object = entry.*;
        if (object.owner == peer.id and !object.dead) {
            object.dead = true;
            _ = b.local_index.remove(.{ .peer = object.owner, .local_id = object.local_id });
            var links = object.links.keyIterator();
            while (links.next()) |linked_id| {
                if (b.peers.get(linked_id.*)) |linked| {
                    if (linked.alive) linked.enqueue(b.encode(Server.serveObjectRefMessage, .{ .object_dead, ObjectRef.remote(object.id) }) catch continue);
                }
            }
            object.links.clearRetainingCapacity();
        }
        if (object.holders.remove(peer.id) or object.owner == peer.id) affected.append(gpa, object) catch {};
        _ = object.links.remove(peer.id);
    }
    for (affected.items) |object| b.maybeReleaseObject(object) catch {};
    peer.holdings.deinit(gpa);
    gpa.free(peer.package);
    b.mutex.unlock(io);

    // Let the writer drain (it closes the socket), then free.
    peer.requestClose();
    peer.out_mutex.lockUncancelable(io);
    while (!peer.writer_done) peer.out_cond.waitUncancelable(io, &peer.out_mutex);
    peer.out_mutex.unlock(io);
    peer.conn.deinit();
    peer.outbox.deinit(gpa);
    gpa.free(peer.read_buffer);
    gpa.free(peer.write_buffer);
    gpa.destroy(peer);
}
