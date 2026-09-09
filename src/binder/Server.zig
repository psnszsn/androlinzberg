//! Broker side of the hosted Binder socket protocol.
//!
//! Declares the messages `azk-binder-broker` sends to a peer and the helpers
//! for serving them and receiving `Client.Message`s. The wire layout matches
//! `Client`: same 32-byte header, little-endian extern bodies, trailing data
//! described in doc comments.
const Server = @This();

const std = @import("std");
const Io = std.Io;
const assert = std.debug.assert;
const Reader = Io.Reader;
const Writer = Io.Writer;
const Client = @import("Client.zig");
const OutMessage = Server.Message;
const InMessage = Client.Message;
const ObjectRef = Client.ObjectRef;
const ObjectEntry = Client.ObjectEntry;
const limits = Client.limits;

in: *Reader,
out: *Writer,

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
        /// `reply`/`service_result`: the peer's request id being answered.
        /// `transact`: when non-zero, the peer request id whose waiting
        /// thread must execute this nested call.
        request_id: u64 = 0,
        /// `transact`/`property_changed`: id echoed in `reply` (sync only),
        /// then `delivery_done` after the handler and input cleanup finish.
        routing_id: u64 = 0,
    };

    pub const Flags = packed struct(u16) {
        _: u16 = 0,
    };

    pub const Tag = enum(u16) {
        /// Answer to `hello`. Body is a `Welcome`.
        welcome,
        /// Body is a UTF-8 reason. The broker closes the connection after it.
        protocol_error,
        /// Body is a `Transact`.
        transact,
        /// Body is a `Reply`.
        reply,
        /// Body is an `ObjectRef` (`remote`): an object the peer holds died.
        object_dead,
        /// Body is an `ObjectReleased`: no peer holds the object any more.
        /// The owner drops its export and answers `release_ack`, unless it
        /// has sent more references to the object than `uses` (those are in
        /// flight and revive the object).
        object_released,
        /// Body is a `ServiceResult`.
        service_result,
        /// No body. The broker is going away.
        shutdown,
        /// Body is the UTF-8 name of a property that changed in the session's
        /// property area. Not acknowledged; drives
        /// `SystemProperties.addChangeCallback` (the `SYSPROPS_TRANSACTION`
        /// analogue).
        property_changed,
        /// Body is a PackageUidResult; answers `reserve_package_uid`.
        package_uid_result,
        _,
    };

    pub const Welcome = extern struct {
        peer_id: u32,
        pid: i32,
        /// Android uid assigned to this peer for the session.
        uid: u32,
        role: Role,

        pub const Role = enum(u32) {
            app = 0,
            system = 1,
            _,
        };
    };

    pub const PackageUidResult = extern struct {
        status: Status,
        /// Zero unless status is ok.
        uid: u32 = 0,

        pub const Status = enum(u32) { ok, denied, exhausted, _ };
    };

    /// Trailing:
    /// * entries: [object_count]ObjectEntry
    /// * data: [data_len]u8
    pub const Transact = extern struct {
        /// `local` when the peer owns the target, otherwise `remote`.
        target: ObjectRef,
        code: u32,
        flags: u32,
        caller_pid: i32,
        caller_uid: u32,
        object_count: u32,
        data_len: u32,
    };

    pub const Reply = InMessage.Reply;

    pub const ObjectReleased = extern struct {
        object: ObjectRef,
        /// Local references to the object the broker processed from the owner.
        uses: u64,
    };

    /// Trailing:
    /// * names: [names_len]u8, NUL-separated (list results only)
    pub const ServiceResult = extern struct {
        status: Status,
        _pad: u32 = 0,
        object: ObjectRef,
        names_len: u32,
        _pad2: u32 = 0,

        pub const Status = enum(u32) {
            ok = 0,
            not_found = 1,
            /// Another peer already registered that name.
            already_registered = 2,
            /// Broker shutdown interrupted a waiting lookup.
            shutdown = 3,
            /// The peer may not register that name (registry policy).
            denied = 4,
            _,
        };
    };

    comptime {
        assert(@sizeOf(Header) == 32);
        assert(@sizeOf(Transact) == 40);
    }
};

pub const ReceiveError = Client.ReceiveError;

pub fn receiveMessage(s: *const Server) ReceiveError!InMessage.Header {
    const header = try s.in.takeStruct(InMessage.Header, .little);
    if (header.magic != Client.magic) return error.BadMagic;
    if (header.version != Client.version) return error.BadVersion;
    if (header.bytes_len > limits.max_body_len) return error.BodyTooLarge;
    if (header.fd_count > limits.max_fds) return error.TooManyFds;
    return header;
}

/// Don't forget to flush!
pub fn serveMessageHeader(s: *const Server, header: OutMessage.Header) Writer.Error!void {
    try s.out.writeStruct(header, .little);
}

pub fn serveBodylessMessage(s: *const Server, tag: OutMessage.Tag) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = tag, .bytes_len = 0 });
    try s.out.flush();
}

pub fn serveWelcome(s: *const Server, welcome: OutMessage.Welcome) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = .welcome, .bytes_len = @sizeOf(OutMessage.Welcome) });
    try s.out.writeStruct(welcome, .little);
    try s.out.flush();
}

pub fn serveProtocolError(s: *const Server, reason: []const u8) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = .protocol_error, .bytes_len = @intCast(reason.len) });
    try s.out.writeAll(reason);
    try s.out.flush();
}

pub fn servePackageUidResult(s: *const Server, request_id: u64, result: OutMessage.PackageUidResult) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = .package_uid_result, .bytes_len = @sizeOf(OutMessage.PackageUidResult), .request_id = request_id });
    try s.out.writeStruct(result, .little);
    try s.out.flush();
}

pub const TransactOptions = struct {
    target: ObjectRef,
    code: u32,
    flags: u32,
    caller_pid: i32,
    caller_uid: u32,
    routing_id: u64,
    nest_request_id: u64 = 0,
    entries: []const ObjectEntry,
    data: []const u8,
    fd_count: u16,
};

pub fn serveTransact(s: *const Server, options: TransactOptions) Writer.Error!void {
    try s.serveMessageHeader(.{
        .tag = .transact,
        .fd_count = options.fd_count,
        .bytes_len = @intCast(@sizeOf(OutMessage.Transact) +
            options.entries.len * @sizeOf(ObjectEntry) + options.data.len),
        .request_id = options.nest_request_id,
        .routing_id = options.routing_id,
    });
    try s.out.writeStruct(OutMessage.Transact{
        .target = options.target,
        .code = options.code,
        .flags = options.flags,
        .caller_pid = options.caller_pid,
        .caller_uid = options.caller_uid,
        .object_count = @intCast(options.entries.len),
        .data_len = @intCast(options.data.len),
    }, .little);
    try s.out.writeSliceEndian(ObjectEntry, options.entries, .little);
    try s.out.writeAll(options.data);
    try s.out.flush();
}

pub const ReplyOptions = struct {
    request_id: u64,
    status: OutMessage.Reply.Status,
    handled: bool,
    binder_status: i32 = 0,
    entries: []const ObjectEntry = &.{},
    data: []const u8 = &.{},
    fd_count: u16 = 0,
};

pub fn serveReply(s: *const Server, options: ReplyOptions) Writer.Error!void {
    try s.serveMessageHeader(.{
        .tag = .reply,
        .fd_count = options.fd_count,
        .bytes_len = @intCast(@sizeOf(OutMessage.Reply) +
            options.entries.len * @sizeOf(ObjectEntry) + options.data.len),
        .request_id = options.request_id,
    });
    try s.out.writeStruct(OutMessage.Reply{
        .status = options.status,
        .handled = @intFromBool(options.handled),
        .object_count = @intCast(options.entries.len),
        .data_len = @intCast(options.data.len),
        .binder_status = options.binder_status,
    }, .little);
    try s.out.writeSliceEndian(ObjectEntry, options.entries, .little);
    try s.out.writeAll(options.data);
    try s.out.flush();
}

pub fn serveObjectRefMessage(s: *const Server, tag: OutMessage.Tag, object: ObjectRef) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = tag, .bytes_len = @sizeOf(ObjectRef) });
    try s.out.writeStruct(object, .little);
    try s.out.flush();
}

pub fn serveObjectReleased(s: *const Server, object: ObjectRef, uses: u64) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = .object_released, .bytes_len = @sizeOf(OutMessage.ObjectReleased) });
    try s.out.writeStruct(OutMessage.ObjectReleased{ .object = object, .uses = uses }, .little);
    try s.out.flush();
}

pub fn servePropertyChanged(s: *const Server, name: []const u8, routing_id: u64) Writer.Error!void {
    try s.serveMessageHeader(.{ .tag = .property_changed, .bytes_len = @intCast(name.len), .routing_id = routing_id });
    try s.out.writeAll(name);
    try s.out.flush();
}

pub fn serveServiceResult(
    s: *const Server,
    request_id: u64,
    status: OutMessage.ServiceResult.Status,
    object: ObjectRef,
    names: []const u8,
) Writer.Error!void {
    try s.serveMessageHeader(.{
        .tag = .service_result,
        .bytes_len = @intCast(@sizeOf(OutMessage.ServiceResult) + names.len),
        .request_id = request_id,
    });
    try s.out.writeStruct(OutMessage.ServiceResult{
        .status = status,
        .object = object,
        .names_len = @intCast(names.len),
    }, .little);
    try s.out.writeAll(names);
    try s.out.flush();
}
