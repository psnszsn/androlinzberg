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
/// Zig AIDL runtime over AZKB frames (broker_aidl.zig) and the interfaces
/// `aidl --lang=zig` generates against it.
const aidl = @import("aidl");
const os_aidl = @import("servicemanager_aidl");
const Server = @import("Server.zig");
const Connection = @import("Connection.zig");
const LaunchGrants = @import("LaunchGrants.zig");
const ProcessNamespace = @import("ProcessNamespace.zig");
const prop_area = @import("prop_area.zig");
const ObjectRef = Client.ObjectRef;
const ObjectEntry = Client.ObjectEntry;
const limits = Client.limits;
pub const DeliveryBudget = @import("DeliveryBudget.zig");

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
/// Presented by host test tools; null unless the session permits inspection.
inspector_cookie: ?[]const u8,
/// Package name -> Android uid for this session; uids are never reused.
packages: std.StringHashMapUnmanaged(u32) = .empty,
/// Session-wide system property area; the broker is the only writer
/// (source note: init's property_service).
props: prop_area.Area,
next_app_uid: u32,
launches: LaunchGrants,
user_namespace: Io.File.INode,
shutting_down: bool = false,
peers: std.AutoHashMapUnmanaged(u32, *Peer) = .empty,
next_peer_id: u32 = 1,
objects: std.AutoHashMapUnmanaged(u64, *Object) = .empty,
/// Real objects start at 2; id 1 (`Client.context_object_id`) is reserved for
/// the service manager and is never assigned to an owned object.
next_object_id: u64 = Client.context_object_id + 1,
local_index: std.AutoHashMapUnmanaged(LocalKey, u64) = .empty,
services: std.StringHashMapUnmanaged(Service) = .empty,
/// Session manifest, independent of whether a declared service is running.
declared_services: std.StringHashMapUnmanaged(void) = .empty,
waiters: std.ArrayList(Waiter) = .empty,
/// IServiceManager.registerForNotifications: service name -> the global ids of
/// the IServiceCallback objects to notify (each holds a `service_refs` on its
/// object so the callback stays exported until unregistered or its owner dies).
service_callbacks: std.StringHashMapUnmanaged(std.ArrayListUnmanaged(u64)) = .empty,
calls: std.AutoHashMapUnmanaged(u64, Call) = .empty,
next_routing_id: u64 = 1,
deliveries: DeliveryBudget = .{},
delivery_limits: DeliveryBudget.Limits,
resource_limits: ResourceLimits,
outbox_mutex: Io.Mutex = .init,
outbox_usage: DeliveryBudget = .{},
service_callback_count: usize = 0,

/// Bytes a peer may have queued before it is considered unresponsive.
pub const max_outbox_bytes: usize = 64 * 1024 * 1024;
const read_buffer_len = 64 * 1024;
const write_buffer_len = 64 * 1024;

/// Host session metadata and transport storage, independent of Parcel payloads.
/// Keep the service-name cap within the largest UTF-16 IServiceManager reply.
pub const ResourceLimits = struct {
    peers: usize = 64,
    launches: usize = 1024,
    objects: usize = 65536,
    peer_objects: usize = 16384,
    services: usize = 1024,
    peer_services: usize = 256,
    waiters: usize = 8192,
    peer_waiters: usize = 256,
    callback_names: usize = 1024,
    callbacks: usize = 8192,
    outbox: DeliveryBudget.Usage = .{ .bytes = 256 * 1024 * 1024, .fds = 8192, .count = 65536 },
};

pub const Options = struct {
    socket_path: []const u8,
    cookie: []const u8,
    system_cookie: []const u8,
    /// Present only when the session was started for inspection. A peer that
    /// presents it joins as `inspector` (see `inspector_services`).
    inspector_cookie: ?[]const u8 = null,
    /// File every session process maps read-only for `SystemProperties`.
    prop_area_path: []const u8,
    first_app_uid: u32 = Client.default_app_uid,
    declared_services: []const []const u8 = &.{},
    kernel_backlog: u31 = 16,
    delivery_limits: DeliveryBudget.Limits = .{},
    resource_limits: ResourceLimits = .{},
};

const LocalKey = struct { peer: u32, local_id: u64 };

const Waiter = struct { peer: u32, request_id: u64, name: []u8 };

const Call = struct {
    caller: u32,
    caller_request: u64,
    target: u32,
    parent: u64,
    asynchronous: bool,
    async_budget: bool,
    replied: bool = false,
    cost: DeliveryBudget.Usage,
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
    accounted: bool = false,
    user_work: bool = false,

    fn cost(frame: Frame) DeliveryBudget.Usage {
        return .{ .bytes = frame.bytes.len, .fds = frame.fds.len, .count = 1 };
    }
};

const Peer = struct {
    broker: *Broker,
    id: u32,
    pid: i32,
    kernel_start_time: u64,
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
    outbox_fds: usize = 0,
    deliveries: DeliveryBudget = .{},
    owned_objects: usize = 0,
    registered_services: usize = 0,
    service_waiters: usize = 0,
    closing: bool = false,
    writer_done: bool = false,

    fn enqueue(p: *Peer, incoming: Frame) void {
        var frame = incoming;
        const io = p.broker.io;
        p.out_mutex.lockUncancelable(io);
        defer p.out_mutex.unlock(io);
        if (p.closing or p.outbox_bytes + frame.bytes.len > max_outbox_bytes or
            p.outbox.items.len >= 8192 or p.outbox_fds + frame.fds.len > 2048)
        {
            p.broker.destroyFrame(frame);
            if (!p.closing) {
                log.warn("peer {d} unresponsive; disconnecting", .{p.id});
                p.closing = true;
                p.conn.stream.shutdown(io, .both) catch {};
            }
            p.out_cond.signal(io);
            return;
        }
        if (!p.broker.reserveFrame(frame)) {
            p.broker.destroyFrame(frame);
            log.warn("peer {d} exhausted broker outbox storage; disconnecting", .{p.id});
            p.closing = true;
            p.conn.stream.shutdown(io, .both) catch {};
            p.out_cond.signal(io);
            return;
        }
        frame.accounted = true;
        p.outbox.append(p.broker.gpa, frame) catch {
            p.broker.destroyFrame(frame);
            p.closing = true;
            p.out_cond.signal(io);
            return;
        };
        p.outbox_bytes += frame.bytes.len;
        p.outbox_fds += frame.fds.len;
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
    const inspector_cookie = if (options.inspector_cookie) |value| try gpa.dupe(u8, value) else null;
    errdefer if (inspector_cookie) |value| gpa.free(value);
    var declared_services: std.StringHashMapUnmanaged(void) = .empty;
    errdefer {
        var names = declared_services.keyIterator();
        while (names.next()) |name| gpa.free(name.*);
        declared_services.deinit(gpa);
    }
    for (options.declared_services) |name| {
        // VINTF AIDL names are interface/instance. The session launcher supplies
        // this immutable manifest; addService and peer death cannot change it.
        const slash = std.mem.indexOfScalar(u8, name, '/') orelse return error.InvalidDeclaration;
        if (slash == 0 or slash + 1 == name.len or name.len > limits.max_name_len or
            std.mem.indexOfScalar(u8, name[slash + 1 ..], '/') != null)
            return error.InvalidDeclaration;
        for (name) |c| if (!std.ascii.isAlphanumeric(c) and c != '.' and c != '_' and c != '-' and c != '/')
            return error.InvalidDeclaration;
        if (declared_services.contains(name)) continue;
        const owned = try gpa.dupe(u8, name);
        errdefer gpa.free(owned);
        try declared_services.put(gpa, owned, {});
    }
    var props = try prop_area.Area.create(io, options.prop_area_path);
    errdefer props.deinit();
    try seedProperties(&props);
    const user_namespace = try ProcessNamespace.identity(io, @intCast(std.os.linux.getpid()));
    const address = try net.UnixAddress.init(socket_path);
    const listener = try address.listen(io, .{ .kernel_backlog = options.kernel_backlog });
    b.* = .{
        .gpa = gpa,
        .io = io,
        .listener = listener,
        .socket_path = socket_path,
        .cookie = cookie,
        .system_cookie = system_cookie,
        .inspector_cookie = inspector_cookie,
        .declared_services = declared_services,
        .props = props,
        .delivery_limits = options.delivery_limits,
        .resource_limits = options.resource_limits,
        .user_namespace = user_namespace,
        .next_app_uid = options.first_app_uid,
        .launches = .{ .gpa = gpa, .io = io, .limit = options.resource_limits.launches },
    };
    return b;
}

/// Boot-time defaults: the session's build identity, read by the real
/// `android.os.Build` (values previously hard-coded in the hosted shim).
/// `ro.boot.qemu` stays unset: hosted processes are not emulators.
fn seedProperties(props: *prop_area.Area) prop_area.Error!void {
    const seeds = [_][2][]const u8{
        // Pinned ART gc::Heap::kDefaultMaximumSize; hosted boot does not override
        // -Xmx or set a separate growth limit. ActivityManager reads this value.
        .{ "dalvik.vm.heapsize", "256m" },
        .{ "ro.build.version.sdk", "35" },
        .{ "ro.build.version.sdk_full", "35.0" },
        .{ "ro.build.version.release", "15" },
        .{ "ro.build.version.release_or_codename", "15" },
        .{ "ro.build.version.release_or_preview_display", "15" },
        .{ "ro.build.version.codename", "REL" },
        .{ "ro.build.version.all_codenames", "REL" },
        .{ "ro.build.version.known_codenames", "Base,Base11,Cupcake,Donut,Eclair,Eclair01,EclairMr1,Froyo,Gingerbread,GingerbreadMr1,Honeycomb,HoneycombMr1,HoneycombMr2,IceCreamSandwich,IceCreamSandwichMr1,JellyBean,JellyBeanMr1,JellyBeanMr2,Kitkat,KitkatWatch,Lollipop,LollipopMr1,M,N,NMr1,O,OMr1,P,Q,R,S,Sv2,Tiramisu,UpsideDownCake,VanillaIceCream" },
        .{ "ro.build.version.incremental", "artzig" },
        .{ "ro.build.version.security_patch", "2026-08-01" },
        .{ "ro.build.version.preview_sdk", "0" },
        .{ "ro.build.version.min_supported_target_sdk", "28" },
        .{ "ro.build.id", "artzig" },
        .{ "ro.build.display.id", "artzig" },
        .{ "ro.build.type", "user" },
        .{ "ro.build.tags", "release-keys" },
        .{ "ro.build.user", "artzig" },
        .{ "ro.build.host", "artzig" },
        .{ "ro.build.date.utc", "0" },
        .{ "ro.build.fingerprint", "artzig/artzig:16" },
        // Logical host-composer display; task outputs apply their own scale.
        .{ "ro.sf.lcd_density", "160" },
        .{ "ro.product.brand", "artzig" },
        .{ "ro.product.device", "artzig" },
        .{ "ro.product.model", "artzig" },
        .{ "ro.product.manufacturer", "artzig" },
        .{ "ro.product.name", "artzig" },
        .{ "ro.product.board", "artzig" },
        .{ "ro.product.first_api_level", "24" },
        .{ "ro.product.cpu.abi", "x86_64" },
        .{ "ro.product.cpu.abilist", "x86_64,arm64-v8a" },
        .{ "ro.product.cpu.abilist64", "x86_64,arm64-v8a" },
        .{ "ro.product.cpu.abilist32", "" },
        .{ "ro.hardware", "artzig" },
        // CameraUtils uses the standard no-cameraserver configuration before waiting.
        .{ "config.disable_cameraservice", "true" },
        .{ "ro.debuggable", "0" },
        .{ "ro.hw_timeout_multiplier", "1" },
        .{ "debug.force_rtl", "0" },
    };
    for (seeds) |seed| try props.set(seed[0], seed[1]);
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
    assert(b.calls.count() == 0);
    assert(b.outbox_usage.total.count == 0 and b.outbox_usage.total.bytes == 0 and b.outbox_usage.total.fds == 0);
    assert(b.service_callback_count == 0);
    assert(b.deliveries.total.count == 0 and b.deliveries.total.bytes == 0 and b.deliveries.total.fds == 0);
    const gpa = b.gpa;
    const io = b.io;
    b.listener.deinit(io);
    b.props.deinit();
    b.launches.deinit();
    Io.Dir.cwd().deleteFile(io, b.socket_path) catch {};
    var objects = b.objects.valueIterator();
    while (objects.next()) |o| o.*.deinit(gpa);
    b.objects.deinit(gpa);
    b.local_index.deinit(gpa);
    var names = b.services.keyIterator();
    while (names.next()) |name| gpa.free(name.*);
    b.services.deinit(gpa);
    var declared = b.declared_services.keyIterator();
    while (declared.next()) |name| gpa.free(name.*);
    b.declared_services.deinit(gpa);
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
    if (b.inspector_cookie) |value| gpa.free(value);
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
    b.mutex.lockUncancelable(io);
    const exhausted = b.peers.count() >= b.resource_limits.peers;
    b.mutex.unlock(io);
    if (exhausted) return error.PeerLimit;
    // Only the accept loop calls admit, so another admission cannot race this
    // check while buffers are allocated. Peer removals only free capacity.
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
        .kernel_start_time = b.launches.liveStartTime(creds.pid) orelse return error.PeerProcessExited,
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
        peer.outbox_fds -= frame.fds.len;
        peer.out_mutex.unlock(io);
        const ok = writeFrame(peer, frame);
        b.destroyFrame(frame);
        if (!ok) {
            peer.out_mutex.lockUncancelable(io);
            peer.closing = true;
            for (peer.outbox.items) |f| b.destroyFrame(f);
            peer.outbox.clearRetainingCapacity();
            peer.outbox_bytes = 0;
            peer.outbox_fds = 0;
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

/// Never take the broker mutex from a writer or while holding outbox_mutex.
/// This short independent lock accounts retained frames through the actual
/// write/close, not merely until a writer removes them from its queue.
fn reserveFrame(b: *Broker, frame: Frame) bool {
    b.outbox_mutex.lockUncancelable(b.io);
    defer b.outbox_mutex.unlock(b.io);
    const cost = frame.cost();
    const total = b.resource_limits.outbox;
    const quota: DeliveryBudget.Limits = .{ .total = total, .asynchronous = .{
        .bytes = total.bytes / 2,
        .fds = total.fds / 2,
        .count = total.count / 2,
    } };
    if (!b.outbox_usage.fits(cost, frame.user_work, quota)) return false;
    b.outbox_usage.add(cost, frame.user_work);
    return true;
}

fn destroyFrame(b: *Broker, frame: Frame) void {
    for (frame.fds) |f| f.close(b.io);
    b.gpa.free(frame.fds);
    b.gpa.free(frame.bytes);
    if (frame.accounted) {
        b.outbox_mutex.lockUncancelable(b.io);
        b.outbox_usage.subtract(frame.cost(), frame.user_work);
        b.outbox_mutex.unlock(b.io);
    }
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
            .delivery_done => try handleDeliveryDone(peer, header, body),
            .release => try handleRelease(peer, header, body),
            .release_ack => try handleReleaseAck(peer, body),
            .link_death => try handleLink(peer, header, body, true),
            .unlink_death => try handleLink(peer, header, body, false),
            .register_service => try handleRegister(peer, header, body),
            .lookup_service => try handleLookup(peer, header, body),
            .list_services => try handleList(peer, header),
            .set_property => try handleSetProperty(peer, header, body),
            .reserve_package_uid => try handleReservePackageUid(peer, header, body),
            .process_uid => try handleProcessUid(peer, header, body),
            .system_process => try handleSystemProcess(peer, header, body),
            .reserve_launch => try handleReserveLaunch(peer, header, body),
            .bind_launch => try handleBindLaunch(peer, header, body),
            .revoke_launch => try handleRevokeLaunch(peer, header, body),
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
    if (b.launches.claim(cookie, package, peer.pid, peer.id)) |claim| {
        peer.role = switch (claim.kind) {
            .app, .webview_controller => .app,
            .isolated => .isolated,
            .system => .system,
        };
        peer.android_uid = claim.uid;
        peer.package = try gpa.dupe(u8, claim.package);
    } else |_| {
        // Trusted host launchers share the broker's user namespace. Every APK
        // namespace (including fork/exec descendants and nested namespaces)
        // requires a PID-bound grant; shared cookies cannot restore an app or
        // system role there. A directly bound PID also cannot fall back before
        // entering its namespace or after its grant has been revoked.
        const user_namespace = ProcessNamespace.identity(io, peer.pid) catch
            return protocolError(peer, "cannot authenticate peer namespace");
        if (user_namespace != b.user_namespace or b.launches.isBoundProcess(peer.pid))
            return protocolError(peer, "peer requires a supervised launch grant");
        if (std.mem.eql(u8, cookie, b.system_cookie)) {
            peer.role = .system;
            peer.android_uid = Client.system_uid;
            peer.package = try gpa.dupe(u8, "android");
        } else if (b.inspector_cookie != null and std.mem.eql(u8, cookie, b.inspector_cookie.?)) {
            // `adb shell am instrument`: the identity stock accessibility
            // policy already trusts to register a UiAutomation.
            peer.role = .inspector;
            peer.android_uid = shell_uid;
            peer.package = try gpa.dupe(u8, "com.android.shell");
        } else if (std.mem.eql(u8, cookie, b.cookie)) {
            if (package.len == 0) return protocolError(peer, "app peer without a package name");
            peer.role = .app;
            peer.android_uid = try assignPackageUidLocked(b, package) orelse
                return protocolError(peer, "application uid range exhausted");
            peer.package = try gpa.dupe(u8, package);
        } else {
            return protocolError(peer, "bad launch cookie");
        }
    }
    peer.hello_done = true;
    peer.enqueue(try b.encode(Server.serveWelcome, .{Server.Message.Welcome{
        .peer_id = peer.id,
        .pid = peer.pid,
        .uid = peer.android_uid,
        .role = peer.role,
    }}));
}

/// Shares hello's allocation table with install-time reservation. Uids never
/// change or get reused in a session. Android's regular application range is
/// Process.FIRST_APPLICATION_UID..LAST_APPLICATION_UID (10000..19999).
fn assignPackageUidLocked(b: *Broker, package: []const u8) error{OutOfMemory}!?u32 {
    if (b.packages.get(package)) |uid| return uid;
    if (b.next_app_uid < 10000 or b.next_app_uid > 19999) return null;
    const owned = try b.gpa.dupe(u8, package);
    errdefer b.gpa.free(owned);
    const uid = b.next_app_uid;
    try b.packages.put(b.gpa, owned, uid);
    b.next_app_uid += 1;
    return uid;
}

fn handleProcessUid(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    if (body.len != @sizeOf(i32)) return protocolError(peer, "malformed process_uid");
    const pid = std.mem.readInt(i32, body[0..4], .little);
    const b = peer.broker;
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    // Supervised children retain their kernel UID through revocation. Ordinary
    // framework peers carry the logical Android identity authenticated at hello.
    var uid = b.launches.liveUid(pid);
    if (uid == null) if (b.launches.liveStartTime(pid)) |start| {
        var peers = b.peers.valueIterator();
        while (peers.next()) |candidate| {
            const other = candidate.*;
            if (!other.alive or !other.hello_done or other.pid != pid or other.kernel_start_time != start) continue;
            // Native protocol tests can intentionally host several identities in
            // one Linux process. Such an ambiguous PID must never pick a UID.
            if (uid != null and uid.? != other.android_uid) {
                uid = null;
                break;
            }
            uid = other.android_uid;
        }
    };
    const result: Server.Message.PackageUidResult = if (uid) |value|
        .{ .status = .ok, .uid = value }
    else
        .{ .status = .not_found };
    peer.enqueue(try b.encode(Server.servePackageUidResult, .{ header.request_id, result }));
}

fn handleReservePackageUid(peer: *Peer, header: Client.Message.Header, package: []const u8) ServeError!void {
    if (package.len == 0 or package.len > limits.max_name_len or std.mem.indexOfScalar(u8, package, 0) != null)
        return protocolError(peer, "malformed reserve_package_uid");
    const b = peer.broker;
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    const result: Server.Message.PackageUidResult = if (peer.role != .system)
        .{ .status = .denied }
    else if (try assignPackageUidLocked(b, package)) |uid|
        .{ .status = .ok, .uid = uid }
    else
        .{ .status = .exhausted };
    peer.enqueue(try b.encode(Server.servePackageUidResult, .{ header.request_id, result }));
}

fn launchFailure(err: LaunchGrants.Failure) Server.Message.ServiceResult.Status {
    return switch (err) {
        error.Exhausted, error.OutOfMemory => .exhausted,
        error.Stale => .not_found,
        error.Denied, error.Invalid => .denied,
    };
}

/// Control authorization differs from process_uid metadata: disconnected or
/// revoked-but-living processes cannot retain system authority. The consumer
/// must also bind this query to the actual socket peer's kernel lifetime.
fn handleSystemProcess(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    if (body.len != @sizeOf(i32)) return protocolError(peer, "malformed system_process");
    const pid = std.mem.readInt(i32, body[0..4], .little);
    const b = peer.broker;
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    var authorized = false;
    if (pid > 0) if (b.launches.liveStartTime(pid)) |start| {
        var peers = b.peers.valueIterator();
        while (peers.next()) |candidate| {
            const other = candidate.*;
            if (!other.alive or !other.hello_done or other.pid != pid or other.kernel_start_time != start or
                other.role != .system or other.android_uid != Client.system_uid) continue;
            other.out_mutex.lockUncancelable(b.io);
            const closing = other.closing;
            other.out_mutex.unlock(b.io);
            if (!closing) {
                authorized = true;
                break;
            }
        }
    };
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, @as(Server.Message.ServiceResult.Status, if (authorized) .ok else .denied), ObjectRef.null_ref, "" }));
}

fn handleReserveLaunch(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    const req = takeStruct(Client.Message.ReserveLaunch, body) catch return protocolError(peer, "malformed reserve_launch");
    const rest = body[@sizeOf(Client.Message.ReserveLaunch)..];
    if (@as(usize, req.package_len) + req.process_len != rest.len or req.kind > 3)
        return protocolError(peer, "malformed reserve_launch");
    const package = try readName(peer, rest[0..req.package_len], req.package_len);
    const process = try readName(peer, rest[req.package_len..], req.process_len);
    const b = peer.broker;
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    var result: Server.Message.LaunchResult = .{ .status = .denied };
    const kind: LaunchGrants.Kind = @fromBackingInt(@intCast(req.kind));
    const requested: ?u32 = if (req.requested_uid == std.math.maxInt(u32)) null else req.requested_uid;
    if (peer.role == .system or b.launches.mayDelegate(peer.id, kind, requested)) {
        const app_id = if (kind == .system) @as(?u32, Client.system_uid) else if (peer.role == .system) try assignPackageUidLocked(b, package) else b.packages.get(package); // Delegation cannot create package identities.
        if (app_id) |id| {
            if (req.user_id > @as(u32, std.math.maxInt(i32)) / 100000 or
                (kind == .system and req.user_id != 0))
            {
                result.status = .denied;
            } else {
                const package_uid = req.user_id * 100000 + id;
                if (b.launches.reserveForUid(peer.id, peer.pid, kind, package, process, package_uid, req.sequence, requested)) |ticket| {
                    result = .{ .status = .ok, .id = ticket.id, .uid = ticket.uid, .token = ticket.token };
                } else |err| result.status = launchFailure(err);
            }
        } else result.status = .exhausted;
    }
    peer.enqueue(try b.encode(Server.serveLaunchResult, .{ header.request_id, result }));
}

fn handleBindLaunch(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    if (body.len != @sizeOf(Client.Message.BindLaunch)) return protocolError(peer, "malformed bind_launch");
    const req = takeStruct(Client.Message.BindLaunch, body) catch return protocolError(peer, "malformed bind_launch");
    const b = peer.broker;
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    var status: Server.Message.ServiceResult.Status = .denied;
    if (peer.role == .system or b.launches.isIsolatedSupervisor(peer.id)) {
        status = if (b.launches.bind(peer.id, req.id, req.pid)) |_| .ok else |err| launchFailure(err);
    }
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, status, ObjectRef.null_ref, "" }));
}

fn handleRevokeLaunch(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    if (body.len != @sizeOf(u64)) return protocolError(peer, "malformed revoke_launch");
    const id = std.mem.readInt(u64, body[0..8], .little);
    const b = peer.broker;
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    var status: Server.Message.ServiceResult.Status = .denied;
    if (peer.role == .system or b.launches.isIsolatedSupervisor(peer.id)) {
        if (b.launches.revoke(peer.id, id)) |claimed_peer| {
            status = .ok;
            if (claimed_peer) |peer_id| if (b.peers.get(peer_id)) |child| child.requestClose();
        } else |err| status = launchFailure(err);
    }
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, status, ObjectRef.null_ref, "" }));
}

/// android.os.Process.SHELL_UID.
const shell_uid: u32 = 2000;

/// What an inspector may look up: the services android.app.UiAutomation and
/// UiAutomationConnection obtain, plus the hosted inspection journal and the
/// task presenter's test methods. It registers nothing.
const inspector_services = [_][]const u8{
    "accessibility",               "window", "permissionmgr",
    "activity",                    "user",   "dev.artzig.testing.inspection",
    "dev.artzig.window.presenter",
};

fn inspectorMaySee(name: []const u8) bool {
    for (inspector_services) |allowed| if (std.mem.eql(u8, name, allowed)) return true;
    return false;
}

/// Registry policy: the system process may claim any name; an app may only
/// claim its own package name or names beneath it.
fn mayRegister(peer: *const Peer, name: []const u8) bool {
    if (isIsolated(peer.android_uid)) return false;
    if (peer.role == .inspector) return false;
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
fn resolveRef(peer: *Peer, ref: ObjectRef) error{ Invalid, OutOfMemory, ResourceLimit }!*Object {
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
            if (b.objects.count() >= b.resource_limits.objects or peer.owned_objects >= b.resource_limits.peer_objects)
                return error.ResourceLimit;
            const object = try b.gpa.create(Object);
            errdefer b.gpa.destroy(object);
            object.* = .{ .id = b.next_object_id, .owner = peer.id, .local_id = ref.value, .uses = 1 };
            try b.objects.put(b.gpa, object.id, object);
            errdefer _ = b.objects.remove(object.id);
            try b.local_index.put(b.gpa, key, object.id);
            b.next_object_id += 1;
            peer.owned_objects += 1;
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
) error{ Invalid, OutOfMemory, ResourceLimit }![]ObjectEntry {
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
        if (!call.asynchronous and call.caller == target_peer) return call.caller_request;
        current = call.parent;
    }
    return 0;
}

/// Includes wire copies and conservative decoded-entry metadata. Every FD
/// entry is charged independently: repeated ordinals still duplicate descriptors
/// in AParcel.inflate. All delivery accounting is under the broker mutex.
fn deliveryCost(payload: Client.Payload, fd_count: u16) DeliveryBudget.Usage {
    var descriptors: usize = fd_count;
    for (0..payload.entries.len) |i| {
        if (payload.entry(i).kind == .file_descriptor) descriptors += 1;
    }
    return .{ .bytes = 128 + payload.data.len + payload.entries.len * 128, .fds = descriptors, .count = 1 };
}

fn beginDelivery(b: *Broker, target: *Peer, caller: u32, request: u64, parent: u64, asynchronous: bool, cost: DeliveryBudget.Usage) !?u64 {
    return b.beginDeliveryCharged(target, caller, request, parent, asynchronous, asynchronous, cost);
}

fn beginDeliveryCharged(b: *Broker, target: *Peer, caller: u32, request: u64, parent: u64, asynchronous: bool, async_budget: bool, cost: DeliveryBudget.Usage) Allocator.Error!?u64 {
    if (!target.deliveries.fits(cost, async_budget, b.delivery_limits) or
        !b.deliveries.fits(cost, async_budget, DeliveryBudget.global_limits)) return null;
    const id = b.next_routing_id;
    b.next_routing_id += 1;
    try b.calls.put(b.gpa, id, .{ .caller = caller, .caller_request = request, .target = target.id, .parent = parent, .asynchronous = asynchronous, .async_budget = async_budget, .cost = cost });
    target.deliveries.add(cost, async_budget);
    b.deliveries.add(cost, async_budget);
    return id;
}

/// Reference control may use the space reserved from asynchronous work, but
/// remains charged until its destructor/death callbacks finish on the receiver.
fn beginControlDelivery(b: *Broker, target: *Peer) Allocator.Error!?u64 {
    const id = try b.beginDeliveryCharged(target, 0, 0, 0, true, false, .{ .bytes = 128, .count = 1 });
    if (id == null) {
        log.warn("peer {d} exhausted retained reference callbacks; disconnecting", .{target.id});
        target.requestClose();
    }
    return id;
}

fn notifyObjectDead(b: *Broker, target: *Peer, ref: ObjectRef) Allocator.Error!void {
    const routing_id = (try b.beginControlDelivery(target)) orelse return;
    errdefer b.finishDelivery(routing_id);
    target.enqueue(try b.encode(Server.serveObjectRefMessage, .{ .object_dead, ref, routing_id }));
}

fn finishDelivery(b: *Broker, id: u64) void {
    const call = b.calls.fetchRemove(id).?.value;
    if (b.peers.get(call.target)) |target| target.deliveries.subtract(call.cost, call.async_budget);
    b.deliveries.subtract(call.cost, call.async_budget);
}

fn handleDeliveryDone(peer: *Peer, header: Client.Message.Header, body: []const u8) ServeError!void {
    const b = peer.broker;
    if (body.len != 0 or header.request_id != 0) return protocolError(peer, "malformed delivery completion");
    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);
    const call = b.calls.get(header.routing_id) orelse return protocolError(peer, "completion for unknown delivery");
    if (call.target != peer.id or (!call.asynchronous and !call.replied)) return protocolError(peer, "completion from wrong recipient or before synchronous reply");
    b.finishDelivery(header.routing_id);
}

fn handleTransact(peer: *Peer, header: Client.Message.Header, body: []const u8, fds: []Io.File) ServeError!void {
    const b = peer.broker;
    const gpa = b.gpa;
    const io = b.io;
    const t = takeStruct(Client.Message.Transact, body) catch return protocolError(peer, "malformed transact");
    const payload = Client.Payload.parse(body[@sizeOf(Client.Message.Transact)..], t.object_count, t.data_len) catch
        return protocolError(peer, "malformed transact payload");
    const oneway = (t.flags & 1) != 0;
    if (header.request_id == 0) return protocolError(peer, "transact without request id");

    // The reserved context object is the service manager: the broker answers
    // the stable IServiceManager AIDL directly rather than routing to an owner.
    if (t.target.kind == .remote and t.target.value == Client.context_object_id) {
        try handleServiceManagerTransact(peer, header, t, payload);
        b.closeAll(fds);
        gpa.free(fds);
        return;
    }

    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const object = resolveRef(peer, t.target) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
        error.Invalid => return protocolError(peer, "transact on an object the peer does not hold"),
    };
    if (header.routing_id != 0) {
        const parent = b.calls.get(header.routing_id) orelse return protocolError(peer, "unknown parent routing id");
        if (parent.target != peer.id or parent.asynchronous or parent.replied) return protocolError(peer, "invalid synchronous parent routing id");
    }
    const owner: ?*Peer = if (object.dead) null else b.peers.get(object.owner);
    if (owner == null or !owner.?.alive) {
        try b.discardEntries(peer, payload);
        peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
            .request_id = header.request_id,
            .status = .dead_object,
            .handled = false,
        }}));
        b.closeAll(fds);
        gpa.free(fds);
        return;
    }
    const target = owner.?;
    const routing_id = (try b.beginDelivery(target, peer.id, header.request_id, header.routing_id, oneway, deliveryCost(payload, header.fd_count))) orelse {
        // Pinned IPCThreadState.waitForResponse returns BR_FAILED_REPLY for
        // failed admission, including oneway. A busy target remains usable.
        try b.discardEntries(peer, payload);
        peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
            .request_id = header.request_id,
            .status = .failed,
            .handled = false,
        }}));
        b.closeAll(fds);
        gpa.free(fds);
        return;
    };
    errdefer b.finishDelivery(routing_id);
    var delivered: std.ArrayList(*Object) = .empty;
    defer delivered.deinit(gpa);
    const entries = b.translateEntries(peer, target, payload, header.fd_count, &delivered) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
        error.Invalid => return protocolError(peer, "invalid object entry in transact"),
    };
    defer gpa.free(entries);
    errdefer b.undeliverAll(target, delivered.items);
    const nest_request = if (oneway) 0 else b.findNestRequest(header.routing_id, target.id);
    // Pinned IPCThreadState.waitForResponse accepts BR_TRANSACTION_COMPLETE
    // without waiting for a oneway handler. Reserve first, then acknowledge.
    const admission = if (oneway) try b.encode(Server.serveReply, .{Server.ReplyOptions{
        .request_id = header.request_id,
        .status = .ok,
        .handled = true,
    }}) else null;
    errdefer if (admission) |ack| b.destroyFrame(ack);
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
    frame.user_work = true;
    target.enqueue(frame);
    if (admission) |ack| peer.enqueue(ack);
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
    if (call.target != peer.id or call.asynchronous or call.replied) return protocolError(peer, "reply from wrong peer, for asynchronous delivery or already answered");
    const caller = b.peers.get(call.caller);
    if (caller == null or !caller.?.alive) {
        try b.discardEntries(peer, payload);
        b.calls.getPtr(header.routing_id).?.replied = true;
        b.closeAll(fds);
        gpa.free(fds);
        return;
    }
    var delivered: std.ArrayList(*Object) = .empty;
    defer delivered.deinit(gpa);
    const entries = b.translateEntries(peer, caller.?, payload, header.fd_count, &delivered) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
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
    b.calls.getPtr(header.routing_id).?.replied = true;
    caller.?.enqueue(frame);
}

/// Consume local export announcements even when a valid frame has no recipient.
/// The owner counts each announcement and needs the normal release/ack handshake.
fn discardEntries(b: *Broker, peer: *Peer, payload: Client.Payload) ServeError!void {
    for (0..payload.entries.len) |i| {
        const e = payload.entry(i);
        if (e.kind == .binder_local) {
            const object = resolveRef(peer, ObjectRef.local(e.value)) catch |err| switch (err) {
                error.OutOfMemory => return error.OutOfMemory,
                error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
                error.Invalid => return protocolError(peer, "invalid discarded object"),
            };
            try b.maybeReleaseObject(object);
        }
    }
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
    const routing_id = (try b.beginControlDelivery(owner)) orelse return;
    errdefer b.finishDelivery(routing_id);
    owner.enqueue(try b.encode(Server.serveObjectReleased, .{ ObjectRef.local(object.local_id), object.uses, routing_id }));
}

/// Requires `b.mutex`.
fn destroyObject(b: *Broker, object: *Object) void {
    _ = b.local_index.remove(.{ .peer = object.owner, .local_id = object.local_id });
    _ = b.objects.remove(object.id);
    if (b.peers.get(object.owner)) |owner| owner.owned_objects -= 1;
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
        if (link) try b.notifyObjectDead(peer, ref);
        return;
    };
    if (!peer.holdings.contains(object.id) and object.owner != peer.id) return protocolError(peer, "death link on an object the peer does not hold");
    if (link) {
        if (object.dead) {
            try b.notifyObjectDead(peer, ref);
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
    if (reg.allow_isolated > 1) return protocolError(peer, "invalid allowIsolated");
    b.mutex.lockUncancelable(io);
    defer b.mutex.unlock(io);
    const object = resolveRef(peer, reg.object) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
        error.Invalid => return protocolError(peer, "register_service with an object the peer does not hold"),
    };
    defer b.maybeReleaseObject(object) catch {};
    if (!mayRegister(peer, name)) {
        log.warn("peer {d} ({s}) may not register '{s}'", .{ peer.id, peer.package, name });
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .denied, ObjectRef.null_ref, "" }));
        return;
    }
    if (object.dead) {
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .not_found, ObjectRef.null_ref, "" }));
        return;
    }
    const status: Server.Message.ServiceResult.Status = switch (try registerService(b, peer, object, name, reg.allow_isolated != 0, reg.dump_priority)) {
        .ok => .ok,
        .taken => .already_registered,
        .exhausted => .exhausted,
    };
    peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, status, ObjectRef.null_ref, "" }));
}

// ServiceManager::Service retains policy per registration, including replacements.
const Service = struct {
    object_id: u64,
    allow_isolated: bool,
    dump_priority: u32,

    fn permits(service: Service, peer: *const Peer, name: []const u8) bool {
        if (peer.role == .inspector) return inspectorMaySee(name);
        return service.allow_isolated or !isIsolated(peer.android_uid);
    }
};

// Pinned servicemanager's is_multiuser_uid_isolated (not a child-supplied role).
fn isIsolated(uid: u32) bool {
    const app_id = uid % 100000;
    return app_id >= 90000 and app_id <= 99999;
}

const RegisterOutcome = enum { ok, taken, exhausted };

/// Registers `object` under `name` (replacing the owner's own prior registration)
/// and wakes every waiter blocked on that name. Returns `.taken` without any
/// change when a different peer already owns the name. Shared by the native
/// register_service path and the gid-1 IServiceManager addService path so both
/// feed one registry and wake the same waiters. Requires `b.mutex`; the caller
/// has already checked `mayRegister` and that the object is alive.
fn registerService(b: *Broker, peer: *Peer, object: *Object, name: []const u8, allow_isolated: bool, dump_priority: u32) ServeError!RegisterOutcome {
    const service: Service = .{ .object_id = object.id, .allow_isolated = allow_isolated, .dump_priority = dump_priority };
    const gpa = b.gpa;
    if (b.services.getEntry(name)) |entry| {
        const existing = b.objects.get(entry.value_ptr.object_id).?;
        if (existing.owner != peer.id) return .taken;
        // The owner replaces its own registration.
        existing.service_refs -= 1;
        entry.value_ptr.* = service;
        object.service_refs += 1;
        try b.maybeReleaseObject(existing);
    } else {
        if (b.services.count() >= b.resource_limits.services or peer.registered_services >= b.resource_limits.peer_services) return .exhausted;
        const owned_name = try gpa.dupe(u8, name);
        errdefer gpa.free(owned_name);
        try b.services.put(gpa, owned_name, service);
        object.service_refs += 1;
        peer.registered_services += 1;
    }
    var i: usize = 0;
    while (i < b.waiters.items.len) {
        const w = b.waiters.items[i];
        if (!std.mem.eql(u8, w.name, name)) {
            i += 1;
            continue;
        }
        if (b.peers.get(w.peer)) |waiter| {
            waiter.service_waiters -= 1;
            if (waiter.alive) {
                const allowed = service.permits(waiter, name);
                const ref = if (allowed) try b.deliverRef(waiter, object) else ObjectRef.null_ref;
                waiter.enqueue(try b.encode(Server.serveServiceResult, .{ w.request_id, @as(Server.Message.ServiceResult.Status, if (allowed) .ok else .not_found), ref, "" }));
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
        // Interface token, then onRegistration(String name, IBinder binder). The
        // Binder is filled in once admission has priced the transaction.
        const Method = ServiceCallback.method.onRegistration;
        var request: aidl.Frame = .{ .gpa = gpa };
        defer request.deinit();
        const parcel = request.parcel();
        encodeRegistration(parcel, name) catch return error.OutOfMemory;
        const cost: DeliveryBudget.Usage = .{ .bytes = request.data.items.len - @sizeOf(i32) + 256, .count = 1 };
        const routing_id = (try b.beginDelivery(owner, 0, 0, 0, true, cost)) orelse {
            log.warn("peer {d} exhausted service callback admission; disconnecting", .{owner.id});
            owner.requestClose();
            continue;
        };
        errdefer b.finishDelivery(routing_id);
        const ref = try b.deliverRef(owner, object);
        errdefer if (ref.kind == .remote) b.undeliverAll(owner, &.{object});
        request.entries.items[0].binder = parcelBinder(ref).?;
        var arena_state: std.heap.ArenaAllocator = .init(gpa);
        defer arena_state.deinit();
        var frame = try b.encode(Server.serveTransact, .{Server.TransactOptions{
            .target = ObjectRef.local(cb.local_id),
            .code = Method.code,
            .flags = 1, // FLAG_ONEWAY
            .caller_pid = 0,
            .caller_uid = 1000, // the service manager's identity
            .routing_id = routing_id,
            .entries = try smEntries(arena_state.allocator(), &request),
            .data = request.data.items,
            .fd_count = 0,
        }});
        frame.user_work = true;
        owner.enqueue(frame);
    }
}

fn encodeRegistration(parcel: aidl.Parcel, name: []const u8) aidl.Error!void {
    try parcel.writeInterfaceToken(ServiceCallback.descriptor);
    // The placeholder reference occupies the side-table slot delivery fills.
    try aidl.encodeArgs(ServiceCallback.method.onRegistration, parcel, .{ .name = name, .binder = .{ .kind = .remote, .value = 0 } });
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
                b.service_callback_count -= 1;
                if (cb) |object| {
                    object.service_refs -= 1;
                    b.maybeReleaseObject(object) catch {};
                }
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
    if (b.services.get(name)) |service| {
        if (!service.permits(peer, name)) {
            peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .not_found, ObjectRef.null_ref, "" }));
            return;
        }
        const object = b.objects.get(service.object_id).?;
        const ref = try b.deliverRef(peer, object);
        peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .ok, ref, "" }));
        return;
    }
    if (lookup.flags.wait and !b.shutting_down) {
        if (b.waiters.items.len >= b.resource_limits.waiters or peer.service_waiters >= b.resource_limits.peer_waiters) {
            peer.enqueue(try b.encode(Server.serveServiceResult, .{ header.request_id, .exhausted, ObjectRef.null_ref, "" }));
            return;
        }
        const owned_name = try gpa.dupe(u8, name);
        errdefer gpa.free(owned_name);
        try b.waiters.append(gpa, .{ .peer = peer.id, .request_id = header.request_id, .name = owned_name });
        peer.service_waiters += 1;
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

/// Generated from the pinned frameworks/native/libs/binder/aidl by
/// `aidl --lang=zig`; `aidl` is the frame transport (broker_aidl.zig), so the
/// transaction codes, argument order, union and parcelable framing below are
/// the stable interface's own rather than a transcription of it.
const ServiceManager = os_aidl.@"android.os.IServiceManager";
const ServiceCallback = os_aidl.@"android.os.IServiceCallback";
const SM = ServiceManager.method;

fn binderEntryKind(kind: ObjectRef.Kind) ObjectEntry.Kind {
    return switch (kind) {
        .local => .binder_local,
        .remote => .binder_remote,
        else => .binder_null,
    };
}

fn parcelBinder(ref: ObjectRef) ?aidl.Binder {
    return switch (ref.kind) {
        .local => .{ .kind = .local, .value = ref.value },
        .remote => .{ .kind = .remote, .value = ref.value },
        else => null,
    };
}

fn objectRef(binder: aidl.Binder) ObjectRef {
    return switch (binder.kind) {
        .local => ObjectRef.local(binder.value),
        .remote => ObjectRef.remote(binder.value),
    };
}

/// What one IServiceManager method produced: its reply, an AIDL exception the
/// generated proxy rethrows, or nothing because the peer was already failed.
fn Outcome(comptime Method: type) type {
    return union(enum) {
        reply: Method.Reply,
        exception: struct { code: aidl.Exception, message: []const u8 },

        fn raise(code: aidl.Exception, message: []const u8) @This() {
            return .{ .exception = .{ .code = code, .message = message } };
        }
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
    if (header.request_id == 0) return; // IServiceManager has no oneway methods

    b.mutex.lockUncancelable(b.io);
    defer b.mutex.unlock(b.io);

    var arena_state: std.heap.ArenaAllocator = .init(b.gpa);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    var binders: std.ArrayList(aidl.ObjectEntry) = .empty;
    for (0..payload.entries.len) |index| {
        const entry = payload.entry(index);
        const binder: aidl.Binder = switch (entry.kind) {
            .binder_local => .{ .kind = .local, .value = entry.value },
            .binder_remote => .{ .kind = .remote, .value = entry.value },
            else => continue,
        };
        try binders.append(arena, .{ .offset = entry.offset, .binder = binder });
    }
    var request = aidl.Frame.reading(arena, payload.data, binders.items);
    const input = request.parcel();
    if (!input.enforceInterface(arena, ServiceManager.descriptor))
        return smReplyException(peer, header, .illegal_state, "bad IServiceManager interface token");

    ServiceManagerMethods.held = .empty;
    defer for (ServiceManagerMethods.held.items) |object| b.maybeReleaseObject(object) catch {};
    inline for (ServiceManager.methods) |Method| {
        if (t.code == Method.code) {
            // Pinned servicemanager refuses isolated callers before it parses.
            if (Method == SM.registerForNotifications or Method == SM.unregisterForNotifications) {
                if (isIsolated(peer.android_uid)) return smReplyException(peer, header, .security, "isolated app");
            }
            const args = aidl.decodeArgs(Method, input, arena) catch |failure| switch (failure) {
                error.OutOfMemory => return error.OutOfMemory,
                else => return smReplyException(peer, header, .illegal_argument, "malformed " ++ Method.name ++ " arguments"),
            };
            switch (try @field(ServiceManagerMethods, Method.name)(b, peer, arena, args)) {
                .exception => |exception| return smReplyException(peer, header, exception.code, exception.message),
                .reply => |reply| {
                    var output: aidl.Frame = .{ .gpa = arena };
                    aidl.encodeReply(Method, output.parcel(), reply) catch |failure| switch (failure) {
                        error.OutOfMemory => return error.OutOfMemory,
                        else => return smReplyException(peer, header, .illegal_state, "unencodable " ++ Method.name ++ " reply"),
                    };
                    return smReply(peer, header, arena, &output);
                },
            }
        }
    }
    return smReplyException(peer, header, .unsupported_operation, "unsupported IServiceManager transaction");
}

/// The registry service `name` as a Binder delivered to `peer`, or null when it
/// is absent or not visible to that peer.
fn smServiceBinder(b: *Broker, peer: *Peer, name: []const u8) ServeError!?aidl.Binder {
    const service = b.services.get(name) orelse return null;
    if (!service.permits(peer, name)) return null;
    return parcelBinder(try b.deliverRef(peer, b.objects.get(service.object_id).?));
}

fn validServiceName(name: []const u8) bool {
    return name.len != 0 and name.len <= limits.max_name_len and std.mem.indexOfScalar(u8, name, 0) == null;
}

fn sortedNames(names: [][]const u8) void {
    // Pinned ServiceManager stores registrations in an ordered map.
    std.mem.sort([]const u8, names, {}, struct {
        fn lessThan(_: void, a: []const u8, other: []const u8) bool {
            return std.mem.lessThan(u8, a, other);
        }
    }.lessThan);
}

/// One function per IServiceManager method, named after it. All run under
/// `b.mutex`; `arena` lives until the reply is encoded.
const ServiceManagerMethods = struct {
    /// Objects a method resolved from the caller's references. They are
    /// released only after the reply is queued, so a peer sees the answer
    /// before any release notice for an export the method did not keep.
    threadlocal var held: std.ArrayList(*Object) = .empty;

    fn getService(b: *Broker, peer: *Peer, _: Allocator, args: SM.getService.Args) ServeError!Outcome(SM.getService) {
        return .{ .reply = .{ ._result = try smServiceBinder(b, peer, args.name) } };
    }
    fn checkService(b: *Broker, peer: *Peer, _: Allocator, args: SM.checkService.Args) ServeError!Outcome(SM.checkService) {
        return .{ .reply = .{ ._result = try smServiceBinder(b, peer, args.name) } };
    }
    // The framework's ServiceManagerProxy resolves every service through
    // checkService2, which returns a Service union.
    fn getService2(b: *Broker, peer: *Peer, _: Allocator, args: SM.getService2.Args) ServeError!Outcome(SM.getService2) {
        return .{ .reply = .{ ._result = .{ .serviceWithMetadata = .{ .service = try smServiceBinder(b, peer, args.name), .isLazyService = false } } } };
    }
    fn checkService2(b: *Broker, peer: *Peer, _: Allocator, args: SM.checkService2.Args) ServeError!Outcome(SM.checkService2) {
        return .{ .reply = .{ ._result = .{ .serviceWithMetadata = .{ .service = try smServiceBinder(b, peer, args.name), .isLazyService = false } } } };
    }
    fn addService(b: *Broker, peer: *Peer, arena: Allocator, args: SM.addService.Args) ServeError!Outcome(SM.addService) {
        const Result = Outcome(SM.addService);
        if (!validServiceName(args.name)) return Result.raise(.illegal_argument, "malformed service name");
        if (!mayRegister(peer, args.name)) {
            log.warn("peer {d} ({s}) may not register '{s}' (gid-1)", .{ peer.id, peer.package, args.name });
            return Result.raise(.security, "not allowed to add this service");
        }
        const object = resolveRef(peer, objectRef(args.service)) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
            error.Invalid => return protocolError(peer, "addService with an object the peer does not hold"),
        };
        try held.append(arena, object);
        if (object.dead) return Result.raise(.illegal_state, "service object is dead");
        return switch (try registerService(b, peer, object, args.name, args.allowIsolated, @bitCast(args.dumpPriority))) {
            .ok => .{ .reply = .{} },
            .taken => Result.raise(.illegal_state, "service already registered by another owner"),
            .exhausted => Result.raise(.illegal_state, "service registry capacity exhausted"),
        };
    }
    fn listServices(b: *Broker, _: *Peer, arena: Allocator, args: SM.listServices.Args) ServeError!Outcome(SM.listServices) {
        const priority: u32 = @bitCast(args.dumpPriority);
        var names: std.ArrayList([]const u8) = .empty;
        var it = b.services.iterator();
        while (it.next()) |entry| {
            if (entry.value_ptr.dump_priority & priority != 0) try names.append(arena, entry.key_ptr.*);
        }
        sortedNames(names.items);
        return .{ .reply = .{ ._result = names.items } };
    }
    fn isDeclared(b: *Broker, _: *Peer, _: Allocator, args: SM.isDeclared.Args) ServeError!Outcome(SM.isDeclared) {
        return .{ .reply = .{ ._result = b.declared_services.contains(args.name) } };
    }
    fn getDeclaredInstances(b: *Broker, _: *Peer, arena: Allocator, args: SM.getDeclaredInstances.Args) ServeError!Outcome(SM.getDeclaredInstances) {
        const interface = args.iface;
        var instances: std.ArrayList([]const u8) = .empty;
        var declared = b.declared_services.keyIterator();
        while (declared.next()) |name| {
            if (name.len > interface.len and std.mem.startsWith(u8, name.*, interface) and name.*[interface.len] == '/')
                try instances.append(arena, name.*[interface.len + 1 ..]);
        }
        sortedNames(instances.items);
        return .{ .reply = .{ ._result = instances.items } };
    }
    fn updatableViaApex(_: *Broker, _: *Peer, _: Allocator, _: SM.updatableViaApex.Args) ServeError!Outcome(SM.updatableViaApex) {
        return .{ .reply = .{ ._result = null } };
    }
    fn getUpdatableNames(_: *Broker, _: *Peer, _: Allocator, _: SM.getUpdatableNames.Args) ServeError!Outcome(SM.getUpdatableNames) {
        return .{ .reply = .{ ._result = &.{} } };
    }
    fn getConnectionInfo(_: *Broker, _: *Peer, _: Allocator, _: SM.getConnectionInfo.Args) ServeError!Outcome(SM.getConnectionInfo) {
        return .{ .reply = .{ ._result = null } };
    }
    fn getServiceDebugInfo(_: *Broker, _: *Peer, _: Allocator, _: SM.getServiceDebugInfo.Args) ServeError!Outcome(SM.getServiceDebugInfo) {
        return .{ .reply = .{ ._result = &.{} } };
    }
    // Client-count callbacks (lazy services) are accepted but never delivered
    // on this host; callers do not block on them.
    fn registerClientCallback(_: *Broker, _: *Peer, _: Allocator, _: SM.registerClientCallback.Args) ServeError!Outcome(SM.registerClientCallback) {
        return .{ .reply = .{} };
    }
    fn tryUnregisterService(_: *Broker, _: *Peer, _: Allocator, _: SM.tryUnregisterService.Args) ServeError!Outcome(SM.tryUnregisterService) {
        return .{ .reply = .{} };
    }

    /// The callback object behind a notification registration, or the
    /// exception that refuses it.
    fn notificationCallback(comptime Method: type, peer: *Peer, arena: Allocator, name: []const u8, callback: ServiceCallback.Client) ServeError!union(enum) { object: *Object, refused: Outcome(Method) } {
        const object = resolveRef(peer, objectRef(callback.remote)) catch |err| switch (err) {
            error.OutOfMemory => return error.OutOfMemory,
            error.ResourceLimit => return protocolError(peer, "exported Binder object limit exceeded"),
            error.Invalid => return protocolError(peer, "service callback the peer does not hold"),
        };
        try held.append(arena, object);
        if (!validServiceName(name)) {
            return .{ .refused = Outcome(Method).raise(.illegal_argument, "malformed service callback name") };
        }
        return .{ .object = object };
    }
    fn registerForNotifications(b: *Broker, peer: *Peer, arena: Allocator, args: SM.registerForNotifications.Args) ServeError!Outcome(SM.registerForNotifications) {
        const Result = Outcome(SM.registerForNotifications);
        const gpa = b.gpa;
        const cb = switch (try notificationCallback(SM.registerForNotifications, peer, arena, args.name, args.callback)) {
            .refused => |refusal| return refusal,
            .object => |object| object,
        };
        if (cb.dead) return Result.raise(.illegal_state, "callback object is dead");
        const existing = b.service_callbacks.get(args.name);
        if (existing) |list| {
            for (list.items) |gid| if (gid == cb.id) return .{ .reply = .{} };
        }
        if (b.service_callback_count >= b.resource_limits.callbacks or
            (existing == null and b.service_callbacks.count() >= b.resource_limits.callback_names))
            return Result.raise(.illegal_state, "service callback capacity exhausted");
        const gop = try b.service_callbacks.getOrPut(gpa, args.name);
        if (!gop.found_existing) {
            gop.key_ptr.* = try gpa.dupe(u8, args.name);
            gop.value_ptr.* = .empty;
        }
        try gop.value_ptr.append(gpa, cb.id);
        cb.service_refs += 1; // keep the callback exported while registered
        b.service_callback_count += 1;
        return .{ .reply = .{} };
    }
    fn unregisterForNotifications(b: *Broker, peer: *Peer, arena: Allocator, args: SM.unregisterForNotifications.Args) ServeError!Outcome(SM.unregisterForNotifications) {
        const gpa = b.gpa;
        const cb = switch (try notificationCallback(SM.unregisterForNotifications, peer, arena, args.name, args.callback)) {
            .refused => |refusal| return refusal,
            .object => |object| object,
        };
        if (b.service_callbacks.getPtr(args.name)) |list| {
            var i: usize = 0;
            while (i < list.items.len) : (i += 1) {
                if (list.items[i] != cb.id) continue;
                _ = list.swapRemove(i);
                cb.service_refs -= 1;
                b.service_callback_count -= 1;
                break;
            }
            if (list.items.len == 0) {
                const kv = b.service_callbacks.fetchRemove(args.name).?;
                var l = kv.value;
                l.deinit(gpa);
                gpa.free(kv.key);
            }
        }
        return .{ .reply = .{} };
    }
};

/// The side table of a written frame in the wire form the peer receives.
fn smEntries(arena: Allocator, frame: *const aidl.Frame) ServeError![]const ObjectEntry {
    const entries = try arena.alloc(ObjectEntry, frame.entries.items.len);
    for (entries, frame.entries.items) |*entry, written|
        entry.* = .{ .offset = written.offset, .kind = binderEntryKind(objectRef(written.binder).kind), .value = written.binder.value };
    return entries;
}

/// Sends an IServiceManager reply (status ok, handled) carrying `frame`.
fn smReply(peer: *Peer, header: Client.Message.Header, arena: Allocator, frame: *const aidl.Frame) ServeError!void {
    const b = peer.broker;
    peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
        .request_id = header.request_id,
        .status = .ok,
        .handled = true,
        .entries = try smEntries(arena, frame),
        .data = frame.data.items,
    }}));
}

/// Sends an IServiceManager reply that carries a Parcel exception, which the
/// generated proxy rethrows.
fn smReplyException(peer: *Peer, header: Client.Message.Header, code: aidl.Exception, message: []const u8) ServeError!void {
    const b = peer.broker;
    var frame: aidl.Frame = .{ .gpa = b.gpa };
    defer frame.deinit();
    aidl.encodeException(frame.parcel(), code, message) catch return error.OutOfMemory;
    peer.enqueue(try b.encode(Server.serveReply, .{Server.ReplyOptions{
        .request_id = header.request_id,
        .status = .ok,
        .handled = true,
        .data = frame.data.items,
    }}));
}

/// Property policy (source note: init's property_service + SELinux property
/// contexts, reduced): the system process may set anything, but an `ro.*`
/// only while it is unset; app peers may touch only `debug.*` and `log.*`.
fn maySetProperty(peer: *const Peer, name: []const u8) bool {
    const b = peer.broker;
    if (peer.role == .system) {
        if (!std.mem.startsWith(u8, name, "ro.")) return true;
        var buf: [prop_area.long_value_max]u8 = undefined;
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
        msg.name_len > limits.max_name_len or msg.value_len >= prop_area.long_value_max)
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
    if (!std.mem.startsWith(u8, name, "ro.") and value.len >= prop_area.value_max) {
        log.warn("property '{s}' exceeds PROP_VALUE_MAX", .{name});
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
        const target = other.*;
        const cost: DeliveryBudget.Usage = .{ .bytes = name.len + 128, .count = 1 };
        const routing_id = (try b.beginDelivery(target, 0, 0, 0, true, cost)) orelse {
            // Property notifications are not coalesced or silently lost. Retire
            // an unresponsive subscription; healthy peers continue independently.
            log.warn("peer {d} exhausted property callback admission; disconnecting", .{target.id});
            target.requestClose();
            continue;
        };
        errdefer b.finishDelivery(routing_id);
        var frame = try b.encode(Server.servePropertyChanged, .{ name, routing_id });
        frame.user_work = true;
        target.enqueue(frame);
    }
}

/// Tears a peer down: fails its calls, kills its objects, drops its holdings.
fn removePeer(b: *Broker, peer: *Peer) void {
    const gpa = b.gpa;
    const io = b.io;
    b.mutex.lockUncancelable(io);
    peer.alive = false;
    _ = b.peers.remove(peer.id);

    // Fail synchronous callers. Keep dead-caller deliveries charged until the
    // recipient finishes: disconnecting a producer must not reset its backlog.
    var dead_calls: std.ArrayList(u64) = .empty;
    defer dead_calls.deinit(gpa);
    var calls = b.calls.iterator();
    while (calls.next()) |entry| {
        const call = entry.value_ptr.*;
        if (call.target == peer.id) {
            if (!call.asynchronous and !call.replied) if (b.peers.get(call.caller)) |caller| {
                if (caller.alive) caller.enqueue(b.encode(Server.serveReply, .{Server.ReplyOptions{
                    .request_id = call.caller_request,
                    .status = .dead_object,
                    .handled = false,
                }}) catch continue);
            };
            dead_calls.append(gpa, entry.key_ptr.*) catch {};
        }
    }
    for (dead_calls.items) |id| b.finishDelivery(id);

    for (b.launches.grants.items) |grant| {
        if (grant.supervisor == peer.id) if (grant.claimed_peer) |child_id| {
            if (b.peers.get(child_id)) |child| child.requestClose();
        };
    }
    b.launches.disconnected(peer.id);

    // Registry names, waiters and notification callbacks.
    b.purgeServiceCallbacks(peer.id);
    var removed_names: std.ArrayList([]const u8) = .empty;
    defer removed_names.deinit(gpa);
    var services = b.services.iterator();
    while (services.next()) |entry| {
        const object = b.objects.get(entry.value_ptr.object_id).?;
        if (object.owner == peer.id) removed_names.append(gpa, entry.key_ptr.*) catch {};
    }
    for (removed_names.items) |name| {
        const kv = b.services.fetchRemove(name).?;
        b.objects.get(kv.value.object_id).?.service_refs -= 1;
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
                    if (linked.alive) b.notifyObjectDead(linked, ObjectRef.remote(object.id)) catch continue;
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
