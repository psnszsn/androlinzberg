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
/// Real objects start at 2; id 1 (`Client.context_object_id`) is reserved for
/// the service manager and is never assigned to an owned object.
next_object_id: u64 = Client.context_object_id + 1,
local_index: std.AutoHashMapUnmanaged(LocalKey, u64) = .empty,
services: std.StringHashMapUnmanaged(u64) = .empty,
waiters: std.ArrayList(Waiter) = .empty,
/// IServiceManager.registerForNotifications: service name -> the global ids of
/// the IServiceCallback objects to notify (each holds a `service_refs` on its
/// object so the callback stays exported until unregistered or its owner dies).
service_callbacks: std.StringHashMapUnmanaged(std.ArrayListUnmanaged(u64)) = .empty,
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
    var cbs = b.service_callbacks.iterator();
    while (cbs.next()) |entry| {
        gpa.free(entry.key_ptr.*);
        entry.value_ptr.deinit(gpa);
    }
    b.service_callbacks.deinit(gpa);
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

    // The reserved context object is the service manager: the broker answers
    // the stable IServiceManager AIDL directly rather than routing to an owner.
    if (t.target.kind == .remote and t.target.value == Client.context_object_id) {
        b.closeAll(fds);
        gpa.free(fds);
        return handleServiceManagerTransact(peer, header, t, payload);
    }

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
    const status: Server.Message.ServiceResult.Status = switch (try registerService(b, peer, object, name)) {
        .ok => .ok,
        .taken => .already_registered,
    };
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, status, ObjectRef.null_ref, "" }));
}

const RegisterOutcome = enum { ok, taken };

/// Registers `object` under `name` (replacing the owner's own prior registration)
/// and wakes every waiter blocked on that name. Returns `.taken` without any
/// change when a different peer already owns the name. Shared by the native
/// register_service path and the gid-1 IServiceManager addService path so both
/// feed one registry and wake the same waiters. Requires `b.mutex`; the caller
/// has already checked `mayRegister` and that the object is alive.
fn registerService(b: *Broker, peer: *Peer, object: *Object, name: []const u8) ServeError!RegisterOutcome {
    const gpa = b.gpa;
    if (b.services.getEntry(name)) |entry| {
        const existing = b.objects.get(entry.value_ptr.*).?;
        if (existing.owner != peer.id) return .taken;
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
    try b.notifyServiceCallbacks(name, object);
    return .ok;
}

/// IServiceCallback.onRegistration(name, binder) to every callback registered
/// for `name`: a oneway transaction from the service manager (uid 1000) to the
/// callback's owner, carrying the service delivered to that peer. Requires `b.mutex`.
fn notifyServiceCallbacks(b: *Broker, name: []const u8, object: *Object) ServeError!void {
    const gpa = b.gpa;
    const list = b.service_callbacks.getPtr(name) orelse return;
    for (list.items) |cb_gid| {
        const cb = b.objects.get(cb_gid) orelse continue;
        const owner = b.peers.get(cb.owner) orelse continue;
        if (!owner.alive or cb.dead) continue;
        var data: std.ArrayList(u8) = .empty;
        defer data.deinit(gpa);
        var entries: std.ArrayList(ObjectEntry) = .empty;
        defer entries.deinit(gpa);
        // Interface token, then onRegistration(String name, IBinder binder).
        try smWriteI32(&data, gpa, 0); // strict-mode policy
        try smWriteI32(&data, gpa, -1); // work source
        try smWriteI32(&data, gpa, 0x53595354); // 'SYST'
        try smWriteString16(&data, gpa, "android.os.IServiceCallback");
        try smWriteString16(&data, gpa, name);
        const ref = try b.deliverRef(owner, object);
        const off: u32 = @intCast(data.items.len);
        try smWriteI32(&data, gpa, 1);
        try entries.append(gpa, .{ .offset = off, .kind = binderEntryKind(ref.kind), .value = ref.value });
        const frame = try b.encode(Server.serveTransact, .{Server.TransactOptions{
            .target = ObjectRef.local(cb.local_id),
            .code = 1, // IServiceCallback.TRANSACTION_onRegistration
            .flags = 1, // FLAG_ONEWAY
            .caller_pid = 0,
            .caller_uid = 1000, // the service manager's identity
            .routing_id = 0,
            .entries = entries.items,
            .data = data.items,
            .fd_count = 0,
        }});
        owner.enqueue(frame);
    }
}

/// Drops the callback registrations owned by a departing peer. Requires `b.mutex`.
fn purgeServiceCallbacks(b: *Broker, peer_id: u32) void {
    const gpa = b.gpa;
    var emptied: std.ArrayList([]const u8) = .empty;
    defer emptied.deinit(gpa);
    var it = b.service_callbacks.iterator();
    while (it.next()) |entry| {
        var i: usize = 0;
        while (i < entry.value_ptr.items.len) {
            const cb = b.objects.get(entry.value_ptr.items[i]);
            if (cb == null or cb.?.owner == peer_id) {
                _ = entry.value_ptr.swapRemove(i);
            } else i += 1;
        }
        if (entry.value_ptr.items.len == 0) emptied.append(gpa, entry.key_ptr.*) catch {};
    }
    for (emptied.items) |name| {
        const kv = b.service_callbacks.fetchRemove(name).?;
        var list = kv.value;
        list.deinit(gpa);
        gpa.free(kv.key);
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

// ---------------------------------------------------------------------------
// Reserved context object: the IServiceManager AIDL served by the broker.
//
// This is the one place the broker interprets Parcel payloads. Post-flip, the
// framework's verbatim android.os.ServiceManager talks to
// BinderInternal.getContextObject() (a proxy for `context_object_id`) using the
// generated IServiceManager proxy, whose wire format is the stable libbinder
// AIDL. We decode it directly onto the same registry the native
// register/lookup/list ops use, so a service added through either path is
// visible through both and wakes the same waiters.
// ---------------------------------------------------------------------------

/// The interface descriptor the IServiceManager token names.
const iservicemanager_descriptor = "android.os.IServiceManager";

/// Parcel exception codes (android.os.Parcel). writeNoException == writeInt(0);
/// a nonzero code followed by a message string is an exception the proxy throws.
const EX_SECURITY: i32 = -1;
const EX_ILLEGAL_ARGUMENT: i32 = -3;
const EX_ILLEGAL_STATE: i32 = -5;
const EX_UNSUPPORTED_OPERATION: i32 = -7;

/// Stable IServiceManager transaction codes (declaration order from
/// IBinder.FIRST_CALL_TRANSACTION == 1).
const SM = struct {
    const getService = 1;
    const getService2 = 2;
    const checkService = 3;
    const checkService2 = 4;
    const addService = 5;
    const listServices = 6;
    const isDeclared = 9;
    const getDeclaredInstances = 10;
    const updatableViaApex = 11;
    const getUpdatableNames = 12;
    const getConnectionInfo = 13;
    const registerForNotifications = 7;
    const unregisterForNotifications = 8;
    const registerClientCallback = 14;
    const tryUnregisterService = 15;
    const getServiceDebugInfo = 16;
};

fn binderEntryKind(kind: ObjectRef.Kind) ObjectEntry.Kind {
    return switch (kind) {
        .local => .binder_local,
        .remote => .binder_remote,
        else => .binder_null,
    };
}

/// Handles a transaction addressed to the reserved context object by decoding
/// the stable IServiceManager AIDL and answering directly. Requires no held
/// lock on entry (takes `b.mutex`).
fn handleServiceManagerTransact(
    peer: *Peer,
    header: Client.Message.Header,
    t: Client.Message.Transact,
    payload: Client.Payload,
) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    if (header.request_id == 0) return; // IServiceManager has no oneway methods

    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);

    var r = ParcelReader{ .data = payload.data };
    if (!r.enforceToken(gpa, iservicemanager_descriptor))
        return smReplyException(peer, header, EX_ILLEGAL_STATE, "bad IServiceManager interface token");

    var data: std.ArrayList(u8) = .empty;
    defer data.deinit(gpa);
    var entries: std.ArrayList(ObjectEntry) = .empty;
    defer entries.deinit(gpa);

    switch (t.code) {
        SM.getService, SM.checkService => {
            const name = r.readString16(gpa) catch return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "bad service name");
            defer gpa.free(name);
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteServiceBinder(b, peer, &data, &entries, name);
            try smReply(peer, header, data.items, entries.items);
        },
        SM.getService2, SM.checkService2 => {
            // The framework's ServiceManagerProxy resolves every service through
            // checkService2, which returns a Service union. Reply shape:
            //   no-exception, Service(present), tag=serviceWithMetadata,
            //   ServiceWithMetadata(present, length-prefixed){ IBinder, boolean }.
            const name = r.readString16(gpa) catch return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "bad service name");
            defer gpa.free(name);
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, 1); // Service typedObject present
            try smWriteI32(&data, gpa, 0); // union tag: serviceWithMetadata
            try smWriteI32(&data, gpa, 1); // ServiceWithMetadata typedObject present
            const swm_start = data.items.len;
            try smWriteI32(&data, gpa, 0); // parcelable size placeholder
            try smWriteServiceBinder(b, peer, &data, &entries, name); // @nullable IBinder service
            try smWriteI32(&data, gpa, 0); // isLazyService = false
            const size: i32 = @intCast(data.items.len - swm_start);
            std.mem.writeInt(i32, data.items[swm_start..][0..4], size, .little);
            try smReply(peer, header, data.items, entries.items);
        },
        SM.addService => {
            const name = r.readString16(gpa) catch return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "bad service name");
            defer gpa.free(name);
            if (name.len == 0 or name.len > limits.max_name_len or std.mem.indexOfScalar(u8, name, 0) != null)
                return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "malformed service name");
            const binder_off: u32 = @intCast(r.pos);
            _ = r.readI32(); // strong binder placeholder
            const svc_ref = smFindBinder(payload, binder_off) orelse
                return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "addService without a binder");
            if (!mayRegister(peer, name)) {
                log.warn("peer {d} ({s}) may not register '{s}' (gid-1)", .{ peer.id, peer.package, name });
                return smReplyException(peer, header, EX_SECURITY, "not allowed to add this service");
            }
            const object = resolveRef(peer, svc_ref) catch |err| switch (err) {
                error.OutOfMemory => return error.OutOfMemory,
                error.Invalid => return protocolError(peer, "addService with an object the peer does not hold"),
            };
            if (object.dead) return smReplyException(peer, header, EX_ILLEGAL_STATE, "service object is dead");
            switch (try registerService(b, peer, object, name)) {
                .ok => {},
                .taken => return smReplyException(peer, header, EX_ILLEGAL_STATE, "service already registered by another owner"),
            }
            try smWriteI32(&data, gpa, 0); // void -> no exception only
            try smReply(peer, header, data.items, entries.items);
        },
        SM.listServices => {
            _ = r.readI32(); // dumpPriority (ignored: every service is listed)
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, @intCast(b.services.count())); // array length
            var it = b.services.keyIterator();
            while (it.next()) |k| try smWriteString16(&data, gpa, k.*);
            try smReply(peer, header, data.items, entries.items);
        },
        SM.isDeclared => {
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, 0); // false: no VINTF-declared instances hosted
            try smReply(peer, header, data.items, entries.items);
        },
        SM.getDeclaredInstances, SM.getUpdatableNames => {
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, 0); // empty String[]
            try smReply(peer, header, data.items, entries.items);
        },
        SM.updatableViaApex => {
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, -1); // null String
            try smReply(peer, header, data.items, entries.items);
        },
        SM.getConnectionInfo => {
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, 0); // null @nullable ConnectionInfo
            try smReply(peer, header, data.items, entries.items);
        },
        SM.getServiceDebugInfo => {
            try smWriteI32(&data, gpa, 0); // no exception
            try smWriteI32(&data, gpa, 0); // empty ServiceDebugInfo[]
            try smReply(peer, header, data.items, entries.items);
        },
        SM.registerForNotifications, SM.unregisterForNotifications => {
            // registerForNotifications(name, IServiceCallback) /
            // unregisterForNotifications(name, IServiceCallback): void.
            const name = r.readString16(gpa) catch return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "bad service name");
            defer gpa.free(name);
            const binder_off: u32 = @intCast(r.pos);
            _ = r.readI32();
            const cb_ref = smFindBinder(payload, binder_off) orelse
                return smReplyException(peer, header, EX_ILLEGAL_ARGUMENT, "notification callback missing");
            const cb = resolveRef(peer, cb_ref) catch |err| switch (err) {
                error.OutOfMemory => return error.OutOfMemory,
                error.Invalid => return protocolError(peer, "service callback the peer does not hold"),
            };
            if (t.code == SM.registerForNotifications) {
                if (cb.dead) return smReplyException(peer, header, EX_ILLEGAL_STATE, "callback object is dead");
                const gop = try b.service_callbacks.getOrPut(gpa, name);
                if (!gop.found_existing) {
                    gop.key_ptr.* = try gpa.dupe(u8, name);
                    gop.value_ptr.* = .empty;
                }
                var known = false;
                for (gop.value_ptr.items) |gid| known = known or gid == cb.id;
                if (!known) {
                    try gop.value_ptr.append(gpa, cb.id);
                    cb.service_refs += 1; // keep the callback exported while registered
                }
            } else if (b.service_callbacks.getPtr(name)) |list| {
                var i: usize = 0;
                while (i < list.items.len) : (i += 1) {
                    if (list.items[i] != cb.id) continue;
                    _ = list.swapRemove(i);
                    cb.service_refs -= 1;
                    try b.maybeReleaseObject(cb);
                    break;
                }
                if (list.items.len == 0) {
                    const kv = b.service_callbacks.fetchRemove(name).?;
                    var l = kv.value;
                    l.deinit(gpa);
                    gpa.free(kv.key);
                }
            }
            try smWriteI32(&data, gpa, 0); // no exception
            try smReply(peer, header, data.items, entries.items);
        },
        SM.registerClientCallback, SM.tryUnregisterService => {
            // Void methods: client-count callbacks (lazy services) are accepted
            // but never delivered on this host; callers do not block on them.
            try smWriteI32(&data, gpa, 0); // no exception
            try smReply(peer, header, data.items, entries.items);
        },
        else => return smReplyException(peer, header, EX_UNSUPPORTED_OPERATION, "unsupported IServiceManager transaction"),
    }
}

/// Writes a strong-binder value (writeStrongBinder layout: an i32 placeholder +
/// a side-table entry) for the registry service `name`, delivering it to `peer`;
/// writes a null binder (i32 0, no entry) when the service is absent.
fn smWriteServiceBinder(
    b: *Broker,
    peer: *Peer,
    data: *std.ArrayList(u8),
    entries: *std.ArrayList(ObjectEntry),
    name: []const u8,
) ServeError!void {
    if (b.services.get(name)) |gid| {
        const object = b.objects.get(gid).?;
        const ref = try b.deliverRef(peer, object);
        const off: u32 = @intCast(data.items.len);
        try smWriteI32(data, b.gpa, 1); // strong binder present
        try entries.append(b.gpa, .{ .offset = off, .kind = binderEntryKind(ref.kind), .value = ref.value });
    } else {
        try smWriteI32(data, b.gpa, 0); // null binder
    }
}

/// Sends an IServiceManager reply (status ok, handled) carrying `data`/`entries`.
fn smReply(peer: *Peer, header: Client.Message.Header, data: []const u8, entries: []const ObjectEntry) ServeError!void {
    const b = peer.broker;
    peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
        .request_id = header.request_id,
        .status = .ok,
        .handled = true,
        .entries = entries,
        .data = data,
    }}));
}

/// Sends an IServiceManager reply that carries a Parcel exception (nonzero code
/// + message), which the generated proxy rethrows.
fn smReplyException(peer: *Peer, header: Client.Message.Header, code: i32, msg: []const u8) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    var data: std.ArrayList(u8) = .empty;
    defer data.deinit(gpa);
    try smWriteI32(&data, gpa, code);
    try smWriteString16(&data, gpa, msg);
    peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
        .request_id = header.request_id,
        .status = .ok,
        .handled = true,
        .data = data.items,
    }}));
}

/// Finds the strong-binder side-table entry at `offset` in a request payload and
/// returns the reference the sending peer expressed.
fn smFindBinder(payload: Client.Payload, offset: u32) ?ObjectRef {
    for (0..payload.entries.len) |i| {
        const e = payload.entry(i);
        if (e.offset != offset) continue;
        return switch (e.kind) {
            .binder_local => ObjectRef.local(e.value),
            .binder_remote => ObjectRef.remote(e.value),
            else => null,
        };
    }
    return null;
}

fn smWriteI32(list: *std.ArrayList(u8), gpa: Allocator, v: i32) ServeError!void {
    var buf: [4]u8 = undefined;
    std.mem.writeInt(i32, &buf, v, .little);
    try list.appendSlice(gpa, &buf);
}

/// Appends `utf8` in the AOSP writeString16 layout: i32 code-unit count, the
/// UTF-16LE units, a NUL unit, padded to a 4-byte boundary.
fn smWriteString16(list: *std.ArrayList(u8), gpa: Allocator, utf8: []const u8) ServeError!void {
    var units: std.ArrayList(u16) = .empty;
    defer units.deinit(gpa);
    var i: usize = 0;
    while (i < utf8.len) {
        const seq = std.unicode.utf8ByteSequenceLength(utf8[i]) catch 1;
        const cp: u21 = if (i + seq <= utf8.len)
            (std.unicode.utf8Decode(utf8[i .. i + seq]) catch utf8[i])
        else
            utf8[i];
        i += seq;
        if (cp <= 0xFFFF) {
            try units.append(gpa, @intCast(cp));
        } else {
            const c = cp - 0x10000;
            try units.append(gpa, @intCast(0xD800 + (c >> 10)));
            try units.append(gpa, @intCast(0xDC00 + (c & 0x3FF)));
        }
    }
    try smWriteI32(list, gpa, @intCast(units.items.len));
    for (units.items) |u| {
        var buf: [2]u8 = undefined;
        std.mem.writeInt(u16, &buf, u, .little);
        try list.appendSlice(gpa, &buf);
    }
    try list.appendSlice(gpa, &[_]u8{ 0, 0 }); // NUL terminator unit
    while (list.items.len % 4 != 0) try list.append(gpa, 0); // pad to 4
}

/// A cursor over a request Parcel's data for IServiceManager decoding.
const ParcelReader = struct {
    data: []const u8,
    pos: usize = 0,

    fn readI32(r: *ParcelReader) ?i32 {
        if (r.pos + 4 > r.data.len) return null;
        const v = std.mem.readInt(i32, r.data[r.pos..][0..4], .little);
        r.pos += 4;
        return v;
    }

    /// Reads a writeString16 value into freshly allocated UTF-8. `error.Null`
    /// for the -1 marker; the caller frees a returned slice.
    fn readString16(r: *ParcelReader, gpa: Allocator) error{ OutOfMemory, Malformed, Null }![]u8 {
        const count = r.readI32() orelse return error.Malformed;
        if (count < 0) return error.Null;
        const n: usize = @intCast(count);
        var out: std.ArrayList(u8) = .empty;
        errdefer out.deinit(gpa);
        var i: usize = 0;
        while (i < n) : (i += 1) {
            if (r.pos + 2 > r.data.len) return error.Malformed;
            const unit = std.mem.readInt(u16, r.data[r.pos..][0..2], .little);
            r.pos += 2;
            var cp: u21 = unit;
            if (unit >= 0xD800 and unit <= 0xDBFF and i + 1 < n and r.pos + 2 <= r.data.len) {
                const low = std.mem.readInt(u16, r.data[r.pos..][0..2], .little);
                if (low >= 0xDC00 and low <= 0xDFFF) {
                    cp = 0x10000 + ((@as(u21, unit) - 0xD800) << 10) + (@as(u21, low) - 0xDC00);
                    r.pos += 2;
                    i += 1;
                } else cp = 0xFFFD;
            } else if (unit >= 0xD800 and unit <= 0xDFFF) cp = 0xFFFD; // lone surrogate
            var buf: [4]u8 = undefined;
            const len = std.unicode.utf8Encode(cp, &buf) catch (std.unicode.utf8Encode(0xFFFD, &buf) catch unreachable);
            try out.appendSlice(gpa, buf[0..len]);
        }
        r.pos += 2; // NUL unit
        r.pos = std.mem.alignForward(usize, r.pos, 4);
        if (r.pos > r.data.len) r.pos = r.data.len;
        return out.toOwnedSlice(gpa);
    }

    /// Consumes the AOSP interface token (strict-mode policy, work source,
    /// interface header marker, descriptor string) and reports whether it names
    /// `descriptor`.
    fn enforceToken(r: *ParcelReader, gpa: Allocator, descriptor: []const u8) bool {
        _ = r.readI32() orelse return false; // strict-mode policy
        _ = r.readI32() orelse return false; // work source
        _ = r.readI32() orelse return false; // interface header marker
        const s = r.readString16(gpa) catch return false;
        defer gpa.free(s);
        return std.mem.eql(u8, s, descriptor);
    }
};

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

    // Registry names, waiters and notification callbacks.
    b.purgeServiceCallbacks(peer.id);
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
