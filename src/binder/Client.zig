//! Peer side of the hosted Binder socket protocol.
//!
//! A peer (an ART process) connects to `azk-binder-broker` over a Unix stream
//! socket. This file declares the messages a peer sends and the helpers for
//! serving them and receiving the broker's messages. It mirrors the shape of
//! `std.zig.Client`: an explicit little-endian wire format with a fixed
//! header, extern body structs, and trailing data described in doc comments.
//!
//! File descriptors travel as SCM_RIGHTS attached to the first byte of a
//! frame; the frame header states how many. See `Connection`.
const Client = @This();

const std = @import("std");
const Io = std.Io;
const assert = std.debug.assert;
const Reader = Io.Reader;
const Writer = Io.Writer;
const OutMessage = Client.Message;
const InMessage = @import("Server.zig").Message;

in: *Reader,
out: *Writer,

/// First 4 bytes of every frame ("AZKB" as little-endian u32).
pub const magic: u32 = 0x424B5A41;
/// Bumped for every incompatible wire change.
pub const version: u16 = 8;

/// Android uid of the trusted system process (AOSP `Process.SYSTEM_UID`).
pub const system_uid: u32 = 1000;
/// First synthetic app uid; the broker hands one uid per distinct package
/// name in a session, starting here (`Process.FIRST_APPLICATION_UID` + 1, the
/// value the JVM falls back to before it has connected).
pub const default_app_uid: u32 = 10001;

/// The well-known global object id of the context object (the service manager),
/// reserved by the broker and never handed to a real object. A peer reaches it
/// by transacting to `ObjectRef.remote(context_object_id)`; the broker answers
/// the stable `IServiceManager` AIDL directly (see `Broker.handleTransact`),
/// so `BinderInternal.getContextObject()` builds a proxy for it without any
/// broker round-trip. AOSP's equivalent is binder handle 0.
pub const context_object_id: u64 = 1;

pub const limits = struct {
    /// Maximum Parcel payload carried by one transact or reply.
    pub const max_data_len: u32 = 1024 * 1024;
    /// Maximum object entries (binders + descriptors) per transaction.
    pub const max_objects: u32 = 1024;
    /// Maximum descriptors per frame.
    pub const max_fds: u16 = 64;
    /// Longest service name or hello cookie.
    pub const max_name_len: u32 = 256;
    /// Largest body accepted on either side. Covers the biggest transact
    /// (fixed part + entries + data) with room for service listings.
    pub const max_body_len: u32 = max_data_len + max_objects * @sizeOf(ObjectEntry) + 4096;
};

/// A reference to a Binder object as seen by the sending side.
pub const ObjectRef = extern struct {
    kind: Kind,
    _pad: u32 = 0,
    /// `local`: the sender's process-local object id. `remote`: a broker
    /// global object id.
    value: u64,

    pub const Kind = enum(u32) {
        null = 0,
        local = 1,
        remote = 2,
        _,
    };

    pub const null_ref: ObjectRef = .{ .kind = .null, .value = 0 };
    pub fn local(id: u64) ObjectRef {
        return .{ .kind = .local, .value = id };
    }
    pub fn remote(id: u64) ObjectRef {
        return .{ .kind = .remote, .value = id };
    }
};

/// One Parcel side-table entry.
pub const ObjectEntry = extern struct {
    /// Byte offset of the placeholder inside the Parcel data.
    offset: u32,
    kind: Kind,
    /// Binder kinds: as in `ObjectRef.value`. `file_descriptor`: ordinal into
    /// the frame's SCM_RIGHTS list.
    value: u64,

    pub const Kind = enum(u32) {
        binder_null = 0,
        binder_local = 1,
        binder_remote = 2,
        file_descriptor = 3,
        _,
    };
};

comptime {
    assert(@sizeOf(ObjectRef) == 16);
    assert(@sizeOf(ObjectEntry) == 16);
}

pub const Message = struct {
    pub const Header = extern struct {
        magic: u32 = Client.magic,
        version: u16 = Client.version,
        tag: Tag,
        flags: Flags = .{},
        /// Number of descriptors attached to the first byte of this frame.
        fd_count: u16 = 0,
        /// Size of the body only; does not include this Header.
        bytes_len: u32,
        /// Peer-chosen correlation id for synchronous requests; 0 when the
        /// message expects no answer.
        request_id: u64 = 0,
        /// `transact`: broker routing id of the call the sending thread is
        /// currently servicing (0 when not nested). `reply`: routing id of
        /// the call being answered.
        routing_id: u64 = 0,
    };

    pub const Flags = packed struct(u16) {
        _: u16 = 0,
    };

    pub const Tag = enum(u16) {
        /// First message on a connection. Body is a `Hello`.
        hello,
        /// Body is a `Transact`.
        transact,
        /// Body is a `Reply`.
        reply,
        /// Body is a `Release`.
        release,
        /// Body is an `ObjectRef` (must be `remote`).
        link_death,
        /// Body is an `ObjectRef` (must be `remote`).
        unlink_death,
        /// Body is a `RegisterService`.
        register_service,
        /// Body is a `LookupService`.
        lookup_service,
        /// No body. Answered with a `service_result` carrying names.
        list_services,
        /// Body is an `ObjectRef` (`local`). Answers `object_released`: the
        /// owner dropped its export. Sent only when the owner's own count of
        /// references sent equals the `uses` in the notification.
        release_ack,
        /// Body is a `SetProperty`. Answered with a `service_result`
        /// (`ok`/`denied`); on `ok` the property area is already updated.
        set_property,
        /// Body is a package name. Trusted system peers reserve its uid before
        /// launching a process; answered with `package_uid_result`.
        reserve_package_uid,
        /// Empty body. Return routing credit after transaction, property,
        /// release or death callbacks and cleanup. Sync calls must reply first.
        delivery_done,
        reserve_launch,
        bind_launch,
        revoke_launch,
        /// Kernel PID; returns PackageUidResult for an authenticated live process.
        process_uid,
        _,
    };

    /// Trailing:
    /// * cookie: [cookie_len]u8
    /// * package: [package_len]u8 — the Android package name the peer runs;
    ///   empty only for the system process (whose package is "android").
    pub const ReserveLaunch = extern struct {
        kind: u32, // LaunchGrants.Kind, selected by an authenticated supervisor
        package_len: u32,
        process_len: u32,
        _pad: u32 = 0,
        sequence: u64,
    };
    pub const BindLaunch = extern struct { id: u64, pid: i32, _pad: u32 = 0 };

    pub const Hello = extern struct {
        cookie_len: u32,
        package_len: u32,
    };

    /// Trailing:
    /// * name: [name_len]u8
    /// * value: [value_len]u8
    pub const SetProperty = extern struct {
        name_len: u32,
        value_len: u32,
    };

    /// Trailing:
    /// * entries: [object_count]ObjectEntry
    /// * data: [data_len]u8
    pub const Transact = extern struct {
        target: ObjectRef,
        code: u32,
        flags: u32,
        object_count: u32,
        data_len: u32,
    };

    /// Trailing:
    /// * entries: [object_count]ObjectEntry
    /// * data: [data_len]u8
    pub const Reply = extern struct {
        status: Status,
        /// Return value of `onTransact`.
        handled: u32,
        object_count: u32,
        data_len: u32,
        /// Exact `binder_status_t` from the callee's onTransact (protocol v4).
        /// The caller returns it verbatim so default-impl fallbacks
        /// (STATUS_UNKNOWN_TRANSACTION) work across the language boundary;
        /// meaningful only when `status` is `ok`.
        binder_status: i32 = 0,

        pub const Status = enum(u32) {
            ok = 0,
            /// The target object no longer exists.
            dead_object = 1,
            /// The callee threw or the reference was invalid.
            failed = 2,
            _,
        };
    };

    pub const Release = extern struct {
        object: ObjectRef,
        /// Number of deliveries of this object that the peer is giving back.
        count: u32,
        _pad: u32 = 0,
    };

    /// Trailing:
    /// * name: [name_len]u8
    pub const RegisterService = extern struct {
        object: ObjectRef,
        name_len: u32,
        allow_isolated: u32 = 0,
        dump_priority: u32 = 8, // IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT
        _pad: u32 = 0,
    };

    /// Trailing:
    /// * name: [name_len]u8
    pub const LookupService = extern struct {
        flags: LookupFlags,
        name_len: u32,

        pub const LookupFlags = packed struct(u32) {
            /// Hold the request until a service with that name is registered.
            wait: bool = false,
            _: u31 = 0,
        };
    };

    comptime {
        assert(@sizeOf(Header) == 32);
    }
};

pub const ReceiveError = Reader.Error || error{ BadMagic, BadVersion, BodyTooLarge, TooManyFds };

pub fn receiveMessage(c: *const Client) ReceiveError!InMessage.Header {
    const header = try c.in.takeStruct(InMessage.Header, .little);
    if (header.magic != magic) return error.BadMagic;
    if (header.version != version) return error.BadVersion;
    if (header.bytes_len > limits.max_body_len) return error.BodyTooLarge;
    if (header.fd_count > limits.max_fds) return error.TooManyFds;
    return header;
}

/// Don't forget to flush!
pub fn serveMessageHeader(c: *const Client, header: OutMessage.Header) Writer.Error!void {
    try c.out.writeStruct(header, .little);
}

pub fn serveBodylessMessage(c: *const Client, tag: OutMessage.Tag, request_id: u64) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = tag, .bytes_len = 0, .request_id = request_id });
    try c.out.flush();
}

pub fn serveHello(c: *const Client, cookie: []const u8, package: []const u8) Writer.Error!void {
    try c.serveMessageHeader(.{
        .tag = .hello,
        .bytes_len = @intCast(@sizeOf(OutMessage.Hello) + cookie.len + package.len),
    });
    try c.out.writeStruct(OutMessage.Hello{
        .cookie_len = @intCast(cookie.len),
        .package_len = @intCast(package.len),
    }, .little);
    try c.out.writeAll(cookie);
    try c.out.writeAll(package);
    try c.out.flush();
}

pub const TransactOptions = struct {
    target: ObjectRef,
    code: u32,
    flags: u32 = 0,
    /// Nonzero for both synchronous replies and oneway admission replies.
    request_id: u64,
    /// Routing id of the call the sending thread is servicing, or 0.
    parent_routing_id: u64 = 0,
    entries: []const ObjectEntry = &.{},
    data: []const u8 = &.{},
    /// Descriptors referenced by `file_descriptor` entries; must already be
    /// attached to the connection's writer.
    fd_count: u16 = 0,
};

pub fn serveTransact(c: *const Client, options: TransactOptions) Writer.Error!void {
    try c.serveMessageHeader(.{
        .tag = .transact,
        .fd_count = options.fd_count,
        .bytes_len = @intCast(@sizeOf(OutMessage.Transact) +
            options.entries.len * @sizeOf(ObjectEntry) + options.data.len),
        .request_id = options.request_id,
        .routing_id = options.parent_routing_id,
    });
    try c.out.writeStruct(OutMessage.Transact{
        .target = options.target,
        .code = options.code,
        .flags = options.flags,
        .object_count = @intCast(options.entries.len),
        .data_len = @intCast(options.data.len),
    }, .little);
    try c.out.writeSliceEndian(ObjectEntry, options.entries, .little);
    try c.out.writeAll(options.data);
    try c.out.flush();
}

/// Complete a delivered call after input cleanup (and reply, for sync calls).
pub fn serveDeliveryDone(c: *const Client, routing_id: u64) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .delivery_done, .bytes_len = 0, .routing_id = routing_id });
    try c.out.flush();
}

pub const ReplyOptions = struct {
    routing_id: u64,
    status: OutMessage.Reply.Status = .ok,
    handled: bool = true,
    binder_status: i32 = 0,
    entries: []const ObjectEntry = &.{},
    data: []const u8 = &.{},
    fd_count: u16 = 0,
};

pub fn serveReply(c: *const Client, options: ReplyOptions) Writer.Error!void {
    try c.serveMessageHeader(.{
        .tag = .reply,
        .fd_count = options.fd_count,
        .bytes_len = @intCast(@sizeOf(OutMessage.Reply) +
            options.entries.len * @sizeOf(ObjectEntry) + options.data.len),
        .routing_id = options.routing_id,
    });
    try c.out.writeStruct(OutMessage.Reply{
        .status = options.status,
        .handled = @intFromBool(options.handled),
        .object_count = @intCast(options.entries.len),
        .data_len = @intCast(options.data.len),
        .binder_status = options.binder_status,
    }, .little);
    try c.out.writeSliceEndian(ObjectEntry, options.entries, .little);
    try c.out.writeAll(options.data);
    try c.out.flush();
}

pub fn serveRelease(c: *const Client, object: ObjectRef, count: u32) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .release, .bytes_len = @sizeOf(OutMessage.Release) });
    try c.out.writeStruct(OutMessage.Release{ .object = object, .count = count }, .little);
    try c.out.flush();
}

pub fn serveObjectRefMessage(c: *const Client, tag: OutMessage.Tag, object: ObjectRef) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = tag, .bytes_len = @sizeOf(ObjectRef) });
    try c.out.writeStruct(object, .little);
    try c.out.flush();
}

pub fn serveRegisterService(c: *const Client, request_id: u64, object: ObjectRef, name: []const u8) Writer.Error!void {
    return c.serveRegisterServiceWithPolicy(request_id, object, name, false, 8);
}

pub fn serveRegisterServiceWithPolicy(c: *const Client, request_id: u64, object: ObjectRef, name: []const u8, allow_isolated: bool, dump_priority: u32) Writer.Error!void {
    try c.serveMessageHeader(.{
        .tag = .register_service,
        .bytes_len = @intCast(@sizeOf(OutMessage.RegisterService) + name.len),
        .request_id = request_id,
    });
    try c.out.writeStruct(OutMessage.RegisterService{ .object = object, .name_len = @intCast(name.len), .allow_isolated = @intFromBool(allow_isolated), .dump_priority = dump_priority }, .little);
    try c.out.writeAll(name);
    try c.out.flush();
}

pub fn serveLookupService(c: *const Client, request_id: u64, name: []const u8, wait: bool) Writer.Error!void {
    try c.serveMessageHeader(.{
        .tag = .lookup_service,
        .bytes_len = @intCast(@sizeOf(OutMessage.LookupService) + name.len),
        .request_id = request_id,
    });
    try c.out.writeStruct(OutMessage.LookupService{ .flags = .{ .wait = wait }, .name_len = @intCast(name.len) }, .little);
    try c.out.writeAll(name);
    try c.out.flush();
}

pub fn serveSetProperty(c: *const Client, request_id: u64, name: []const u8, value: []const u8) Writer.Error!void {
    try c.serveMessageHeader(.{
        .tag = .set_property,
        .bytes_len = @intCast(@sizeOf(OutMessage.SetProperty) + name.len + value.len),
        .request_id = request_id,
    });
    try c.out.writeStruct(OutMessage.SetProperty{
        .name_len = @intCast(name.len),
        .value_len = @intCast(value.len),
    }, .little);
    try c.out.writeAll(name);
    try c.out.writeAll(value);
    try c.out.flush();
}

pub fn serveReserveLaunch(c: *const Client, request_id: u64, kind: u32, package: []const u8, process: []const u8, sequence: u64) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .reserve_launch, .request_id = request_id, .bytes_len = @intCast(@sizeOf(OutMessage.ReserveLaunch) + package.len + process.len) });
    try c.out.writeStruct(OutMessage.ReserveLaunch{ .kind = kind, .package_len = @intCast(package.len), .process_len = @intCast(process.len), .sequence = sequence }, .little);
    try c.out.writeAll(package);
    try c.out.writeAll(process);
    try c.out.flush();
}

pub fn serveBindLaunch(c: *const Client, request_id: u64, id: u64, pid: i32) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .bind_launch, .request_id = request_id, .bytes_len = @sizeOf(OutMessage.BindLaunch) });
    try c.out.writeStruct(OutMessage.BindLaunch{ .id = id, .pid = pid }, .little);
    try c.out.flush();
}

pub fn serveRevokeLaunch(c: *const Client, request_id: u64, id: u64) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .revoke_launch, .request_id = request_id, .bytes_len = @sizeOf(u64) });
    try c.out.writeInt(u64, id, .little);
    try c.out.flush();
}

pub fn serveProcessUid(c: *const Client, request_id: u64, pid: i32) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .process_uid, .request_id = request_id, .bytes_len = @sizeOf(i32) });
    try c.out.writeInt(i32, pid, .little);
    try c.out.flush();
}

pub fn serveReservePackageUid(c: *const Client, request_id: u64, package: []const u8) Writer.Error!void {
    try c.serveMessageHeader(.{ .tag = .reserve_package_uid, .bytes_len = @intCast(package.len), .request_id = request_id });
    try c.out.writeAll(package);
    try c.out.flush();
}

/// Reads the trailing entries and data of a `Server.Message.Transact` or
/// `Server.Message.Reply` body from `body`.
pub const Payload = struct {
    entries: []align(1) const ObjectEntry,
    data: []const u8,

    pub fn parse(body: []const u8, object_count: u32, data_len: u32) error{Malformed}!Payload {
        if (object_count > limits.max_objects or data_len > limits.max_data_len) return error.Malformed;
        const entries_len: usize = @as(usize, object_count) * @sizeOf(ObjectEntry);
        if (body.len != entries_len + data_len) return error.Malformed;
        const raw: []align(1) const ObjectEntry = @ptrCast(body[0..entries_len]);
        return .{ .entries = raw, .data = body[entries_len..] };
    }

    /// `entries` are stored little-endian on the wire; on little-endian hosts
    /// they are used in place. Call this to obtain a host-order copy elsewhere.
    pub fn entry(p: Payload, i: usize) ObjectEntry {
        var e = p.entries[i];
        if (@import("builtin").cpu.arch.endian() == .big) {
            e.offset = @byteSwap(e.offset);
            e.kind = @fromBackingInt(@intCast(@byteSwap(@backingInt(e.kind))));
            e.value = @byteSwap(e.value);
        }
        return e;
    }
};
