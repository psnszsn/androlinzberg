//! Runtime for the Zig AIDL backend (`aidl --lang=zig`, tools/aidl-zig).
//!
//! Generated interfaces, parcelables, unions and enums import this module as
//! `aidl`. It marshals through the stable `libbinder_ndk` C ABI, which this
//! repository implements over AZKB in src/binder/ndk.zig, so generated Zig is
//! wire-compatible with the Java, cpp, ndk and rust backends by construction:
//! interface tokens, status headers, object tables and descriptor passing are
//! the NDK's, not a second implementation.
//!
//! A codec is a type with `T`, `write(parcel, value)` and
//! `read(parcel, allocator)`. The generator names one codec per AIDL type; the
//! composition rules (arrays, nullability, parcelable framing) live in
//! aidl_core.zig, which this file instantiates over libbinder_ndk.
const std = @import("std");
const core = @import("aidl_core.zig");
const runtime = core.Runtime(@This());
pub const Allocator = std.mem.Allocator;
pub const Exception = core.Exception;
pub const Error = core.Error;
pub const Status = core.Status;
pub const codec = runtime.codec;
pub const defaultValue = runtime.defaultValue;
pub const ReplyOf = runtime.ReplyOf;
pub const encodeArgs = runtime.encodeArgs;
pub const decodeArgs = runtime.decodeArgs;
pub const encodeReply = runtime.encodeReply;
pub const releaseArgs = runtime.releaseArgs;
/// AIDL `ParcelFileDescriptor`.
pub const File = std.Io.File;

pub const AIBinder = opaque {};
pub const AIBinderClass = opaque {};
pub const AParcel = opaque {};
pub const AStatus = opaque {};

const OnTransact = *const fn (*AIBinder, u32, *const AParcel, *AParcel) callconv(.c) i32;
const StringAllocator = *const fn (?*anyopaque, i32, ?*?[*]u8) callconv(.c) bool;
const ByteArrayAllocator = *const fn (?*anyopaque, i32, ?*?[*]i8) callconv(.c) bool;

extern fn AIBinder_Class_define([*:0]const u8, ?*const fn (?*anyopaque) callconv(.c) ?*anyopaque, ?*const fn (?*anyopaque) callconv(.c) void, ?OnTransact) callconv(.c) *AIBinderClass;
extern fn AIBinder_new(*const AIBinderClass, ?*anyopaque) callconv(.c) *AIBinder;
extern fn AIBinder_associateClass(?*AIBinder, ?*const AIBinderClass) callconv(.c) bool;
extern fn AIBinder_getClass(?*AIBinder) callconv(.c) ?*const AIBinderClass;
extern fn AIBinder_Class_getDescriptor(*const AIBinderClass) callconv(.c) [*:0]const u8;
extern fn AIBinder_getUserData(?*AIBinder) callconv(.c) ?*anyopaque;
extern fn AIBinder_incStrong(?*AIBinder) callconv(.c) void;
extern fn AIBinder_decStrong(?*AIBinder) callconv(.c) void;
extern fn AIBinder_prepareTransaction(*AIBinder, *?*AParcel) callconv(.c) i32;
extern fn AIBinder_transact(*AIBinder, u32, *?*AParcel, *?*AParcel, u32) callconv(.c) i32;
extern fn AIBinder_getCallingUid() callconv(.c) u32;
extern fn AIBinder_getCallingPid() callconv(.c) i32;
extern fn AServiceManager_addService(*AIBinder, [*:0]const u8) callconv(.c) i32;
extern fn AParcel_delete(?*AParcel) callconv(.c) void;
extern fn AParcel_getDataSize(*const AParcel) callconv(.c) i32;
extern fn AParcel_getDataPosition(*const AParcel) callconv(.c) i32;
extern fn AParcel_setDataPosition(*const AParcel, i32) callconv(.c) i32;
extern fn AParcel_writeBool(*AParcel, bool) callconv(.c) i32;
extern fn AParcel_readBool(*const AParcel, *bool) callconv(.c) i32;
extern fn AParcel_writeByte(*AParcel, i8) callconv(.c) i32;
extern fn AParcel_readByte(*const AParcel, *i8) callconv(.c) i32;
extern fn AParcel_writeChar(*AParcel, u16) callconv(.c) i32;
extern fn AParcel_readChar(*const AParcel, *u16) callconv(.c) i32;
extern fn AParcel_writeInt32(*AParcel, i32) callconv(.c) i32;
extern fn AParcel_readInt32(*const AParcel, *i32) callconv(.c) i32;
extern fn AParcel_writeInt64(*AParcel, i64) callconv(.c) i32;
extern fn AParcel_readInt64(*const AParcel, *i64) callconv(.c) i32;
extern fn AParcel_writeFloat(*AParcel, f32) callconv(.c) i32;
extern fn AParcel_readFloat(*const AParcel, *f32) callconv(.c) i32;
extern fn AParcel_writeDouble(*AParcel, f64) callconv(.c) i32;
extern fn AParcel_readDouble(*const AParcel, *f64) callconv(.c) i32;
extern fn AParcel_writeString(*AParcel, ?[*]const u8, i32) callconv(.c) i32;
extern fn AParcel_readString(*const AParcel, ?*anyopaque, StringAllocator) callconv(.c) i32;
extern fn AParcel_writeByteArray(*AParcel, ?[*]const i8, i32) callconv(.c) i32;
extern fn AParcel_readByteArray(*const AParcel, ?*anyopaque, ByteArrayAllocator) callconv(.c) i32;
extern fn AParcel_writeStrongBinder(*AParcel, ?*AIBinder) callconv(.c) i32;
extern fn AParcel_readStrongBinder(*const AParcel, *?*AIBinder) callconv(.c) i32;
extern fn AParcel_writeParcelFileDescriptor(*AParcel, i32) callconv(.c) i32;
extern fn AParcel_readParcelFileDescriptor(*const AParcel, *i32) callconv(.c) i32;
extern fn AParcel_writeStatusHeader(*AParcel, *const AStatus) callconv(.c) i32;
extern fn AParcel_readStatusHeader(*const AParcel, *?*AStatus) callconv(.c) i32;
extern fn AStatus_newOk() callconv(.c) *AStatus;
extern fn AStatus_fromExceptionCodeWithMessage(i32, ?[*:0]const u8) callconv(.c) *AStatus;
extern fn AStatus_fromServiceSpecificErrorWithMessage(i32, ?[*:0]const u8) callconv(.c) *AStatus;
extern fn AStatus_getExceptionCode(*const AStatus) callconv(.c) i32;
extern fn AStatus_getServiceSpecificError(*const AStatus) callconv(.c) i32;
extern fn AStatus_getMessage(*const AStatus) callconv(.c) [*:0]const u8;
extern fn AStatus_delete(?*AStatus) callconv(.c) void;

/// android/binder_status.h transport statuses used by generated code.
pub const status_ok: i32 = 0;
pub const status_unknown_error: i32 = std.math.minInt(i32);
pub const status_no_memory: i32 = -12;
pub const status_bad_value: i32 = -22;
pub const status_dead_object: i32 = -32;
pub const status_failed_transaction: i32 = status_unknown_error + 2;
pub const status_unknown_transaction: i32 = -74;
pub const status_unexpected_null: i32 = status_unknown_error + 8;

/// IBinder.FLAG_ONEWAY.
pub const flag_oneway: u32 = 1;

threadlocal var last_status: Status = .{};

/// Status of this thread's most recent client call that returned a remote
/// exception, or the status a server method set before returning an error.
pub fn lastStatus() *const Status {
    return &last_status;
}

/// A server method that returns `error.ServiceSpecific` calls this first.
pub fn setServiceSpecific(code: i32, text: []const u8) void {
    last_status = .{ .exception = .service_specific, .service_specific = code };
    last_status.setMessage(text);
}

/// Message attached to the exception a server method is about to return.
pub fn setExceptionMessage(text: []const u8) void {
    last_status = .{};
    last_status.setMessage(text);
}

fn check(status: i32) Error!void {
    return switch (status) {
        status_ok => {},
        status_no_memory => error.OutOfMemory,
        status_dead_object => error.DeadObject,
        status_unknown_transaction => error.UnknownTransaction,
        status_unexpected_null => error.UnexpectedNull,
        status_failed_transaction => error.TransactionFailed,
        else => error.BadParcelable,
    };
}

fn statusOf(failure: anyerror) i32 {
    return switch (failure) {
        error.OutOfMemory => status_no_memory,
        error.DeadObject => status_dead_object,
        error.UnknownTransaction => status_unknown_transaction,
        error.UnexpectedNull => status_unexpected_null,
        error.BadParcelable => status_bad_value,
        else => status_failed_transaction,
    };
}

/// A strong reference to a local or remote Binder object.
pub const Binder = struct {
    handle: *AIBinder,

    /// Adopt a reference the caller already owns (for example the result of
    /// `AServiceManager_waitForService`).
    pub fn adopt(handle: *AIBinder) Binder {
        return .{ .handle = handle };
    }

    pub fn clone(self: Binder) Binder {
        AIBinder_incStrong(self.handle);
        return self;
    }

    pub fn deinit(self: Binder) void {
        AIBinder_decStrong(self.handle);
    }
};

/// A parcel borrowed from a transaction.
pub const Parcel = struct {
    raw: *AParcel,

    pub fn position(self: Parcel) i32 {
        return AParcel_getDataPosition(self.raw);
    }
    /// A server that owns its whole contract rejects arguments it did not declare.
    pub fn expectEnd(self: Parcel) Error!void {
        if (AParcel_getDataPosition(self.raw) != AParcel_getDataSize(self.raw)) return error.BadParcelable;
    }
    pub fn setPosition(self: Parcel, value: i32) Error!void {
        return check(AParcel_setDataPosition(self.raw, value));
    }
    pub fn writeBool(self: Parcel, value: bool) Error!void {
        return check(AParcel_writeBool(self.raw, value));
    }
    pub fn readBool(self: Parcel) Error!bool {
        var value: bool = undefined;
        try check(AParcel_readBool(self.raw, &value));
        return value;
    }
    pub fn writeByte(self: Parcel, value: i8) Error!void {
        return check(AParcel_writeByte(self.raw, value));
    }
    pub fn readByte(self: Parcel) Error!i8 {
        var value: i8 = undefined;
        try check(AParcel_readByte(self.raw, &value));
        return value;
    }
    pub fn writeChar(self: Parcel, value: u16) Error!void {
        return check(AParcel_writeChar(self.raw, value));
    }
    pub fn readChar(self: Parcel) Error!u16 {
        var value: u16 = undefined;
        try check(AParcel_readChar(self.raw, &value));
        return value;
    }
    pub fn writeInt32(self: Parcel, value: i32) Error!void {
        return check(AParcel_writeInt32(self.raw, value));
    }
    pub fn readInt32(self: Parcel) Error!i32 {
        var value: i32 = undefined;
        try check(AParcel_readInt32(self.raw, &value));
        return value;
    }
    pub fn writeInt64(self: Parcel, value: i64) Error!void {
        return check(AParcel_writeInt64(self.raw, value));
    }
    pub fn readInt64(self: Parcel) Error!i64 {
        var value: i64 = undefined;
        try check(AParcel_readInt64(self.raw, &value));
        return value;
    }
    pub fn writeFloat(self: Parcel, value: f32) Error!void {
        return check(AParcel_writeFloat(self.raw, value));
    }
    pub fn readFloat(self: Parcel) Error!f32 {
        var value: f32 = undefined;
        try check(AParcel_readFloat(self.raw, &value));
        return value;
    }
    pub fn writeDouble(self: Parcel, value: f64) Error!void {
        return check(AParcel_writeDouble(self.raw, value));
    }
    pub fn readDouble(self: Parcel) Error!f64 {
        var value: f64 = undefined;
        try check(AParcel_readDouble(self.raw, &value));
        return value;
    }

    /// UTF-8 in Zig, UTF-16 on the wire; null is the AIDL null string.
    pub fn writeString(self: Parcel, value: ?[]const u8) Error!void {
        const text = value orelse return check(AParcel_writeString(self.raw, null, -1));
        if (text.len > std.math.maxInt(i32)) return error.BadParcelable;
        return check(AParcel_writeString(self.raw, text.ptr, @intCast(text.len)));
    }
    pub fn readString(self: Parcel, allocator: Allocator) Error!?[]const u8 {
        var sink: BufferSink(u8) = .{ .allocator = allocator };
        errdefer sink.deinit();
        try check(AParcel_readString(self.raw, &sink, BufferSink(u8).allocate));
        if (sink.failed) return error.OutOfMemory;
        // The NDK contract counts and writes a NUL terminator; drop it.
        const storage = sink.storage orelse return null;
        if (storage.len == 0) return storage;
        if (allocator.resize(storage, storage.len - 1)) return storage[0 .. storage.len - 1];
        const text = try allocator.dupe(u8, storage[0 .. storage.len - 1]);
        allocator.free(storage);
        sink.storage = null;
        return text;
    }

    pub fn writeByteArray(self: Parcel, value: ?[]const u8) Error!void {
        const bytes = value orelse return check(AParcel_writeByteArray(self.raw, null, -1));
        if (bytes.len > std.math.maxInt(i32)) return error.BadParcelable;
        return check(AParcel_writeByteArray(self.raw, @ptrCast(bytes.ptr), @intCast(bytes.len)));
    }
    pub fn readByteArray(self: Parcel, allocator: Allocator) Error!?[]const u8 {
        var sink: BufferSink(i8) = .{ .allocator = allocator };
        errdefer sink.deinit();
        try check(AParcel_readByteArray(self.raw, &sink, BufferSink(i8).allocate));
        if (sink.failed) return error.OutOfMemory;
        return sink.storage;
    }

    pub fn writeBinder(self: Parcel, value: ?Binder) Error!void {
        return check(AParcel_writeStrongBinder(self.raw, if (value) |binder| binder.handle else null));
    }
    /// The returned reference is owned by the caller.
    pub fn readBinder(self: Parcel) Error!?Binder {
        var handle: ?*AIBinder = null;
        try check(AParcel_readStrongBinder(self.raw, &handle));
        return if (handle) |present| .{ .handle = present } else null;
    }

    pub fn writeStatus(self: Parcel, status: *const Status) Error!void {
        var text: [257]u8 = undefined;
        const message = status.message();
        @memcpy(text[0..message.len], message);
        text[message.len] = 0;
        const header = switch (status.exception) {
            .none => AStatus_newOk(),
            .service_specific => AStatus_fromServiceSpecificErrorWithMessage(status.service_specific, @ptrCast(&text)),
            else => |exception| AStatus_fromExceptionCodeWithMessage(@backingInt(exception), @ptrCast(&text)),
        };
        defer AStatus_delete(header);
        return check(AParcel_writeStatusHeader(self.raw, header));
    }
    pub fn readStatus(self: Parcel) Error!Status {
        var header: ?*AStatus = null;
        try check(AParcel_readStatusHeader(self.raw, &header));
        defer AStatus_delete(header);
        var status: Status = .{
            .exception = @fromBackingInt(@intCast(AStatus_getExceptionCode(header.?))),
            .service_specific = AStatus_getServiceSpecificError(header.?),
        };
        status.setMessage(std.mem.span(AStatus_getMessage(header.?)));
        return status;
    }

    /// The parcel duplicates the descriptor; the caller keeps its own.
    pub fn writeFile(self: Parcel, value: ?std.Io.File) Error!void {
        return check(AParcel_writeParcelFileDescriptor(self.raw, if (value) |file| file.handle else -1));
    }
    /// The returned descriptor is owned by the caller.
    pub fn readFile(self: Parcel) Error!?std.Io.File {
        var descriptor: i32 = -1;
        try check(AParcel_readParcelFileDescriptor(self.raw, &descriptor));
        return if (descriptor < 0) null else .{ .handle = descriptor, .flags = .{ .nonblocking = false } };
    }
};

/// Receives the buffer an NDK string or byte-array reader asks for.
fn BufferSink(comptime Element: type) type {
    return struct {
        allocator: Allocator,
        storage: ?[]u8 = null,
        failed: bool = false,

        fn deinit(self: *@This()) void {
            if (self.storage) |bytes| self.allocator.free(bytes);
        }

        /// A negative length is the AIDL null value and allocates nothing.
        fn allocate(context: ?*anyopaque, length: i32, buffer: ?*?[*]Element) callconv(.c) bool {
            const self: *@This() = @ptrCast(@alignCast(context.?));
            if (length < 0) return true;
            const storage = self.allocator.alloc(u8, @intCast(length)) catch {
                self.failed = true;
                return false;
            };
            self.storage = storage;
            if (buffer) |out| out.* = @ptrCast(storage.ptr);
            return true;
        }
    };
}

fn ClassOf(comptime Interface: type) type {
    return struct {
        var cached: std.atomic.Value(?*AIBinderClass) = .init(null);

        /// One AIBinder class per descriptor, shared by proxies and servers.
        fn get() *AIBinderClass {
            if (cached.load(.acquire)) |class| return class;
            const class = AIBinder_Class_define(Interface.descriptor, LocalObject.onCreate, LocalObject.onDestroy, LocalObject.onTransact);
            cached.store(class, .release);
            return class;
        }
    };
}

/// User data of every Binder object this runtime creates. `Server` fills one
/// in; a service that must leave the Binder thread before it can answer (the
/// Wayland task presenter) supplies its own `transact`, returns a transport
/// status from it, and uses `decodeArgs`/`encodeReply` for the marshalling.
pub const LocalObject = struct {
    context: *anyopaque,
    transact: *const fn (*anyopaque, u32, Parcel, Parcel) i32,

    /// A new strong reference to a new Binder object of `Interface`. `self`
    /// must outlive it.
    pub fn binder(self: *LocalObject, comptime Interface: type) Binder {
        return .{ .handle = AIBinder_new(ClassOf(Interface).get(), self) };
    }

    fn onCreate(arguments: ?*anyopaque) callconv(.c) ?*anyopaque {
        return arguments;
    }
    fn onDestroy(_: ?*anyopaque) callconv(.c) void {}
    fn onTransact(target: *AIBinder, code: u32, input: *const AParcel, output: *AParcel) callconv(.c) i32 {
        const self: *LocalObject = @ptrCast(@alignCast(AIBinder_getUserData(target) orelse return status_unknown_error));
        return self.transact(self.context, code, .{ .raw = @constCast(input) }, .{ .raw = output });
    }
};

/// Identity of the caller of the transaction this thread is serving.
pub fn callingUid() u32 {
    return AIBinder_getCallingUid();
}
pub fn callingPid() i32 {
    return AIBinder_getCallingPid();
}

pub fn addService(binder: Binder, name: [*:0]const u8) Error!void {
    return check(AServiceManager_addService(binder.handle, name));
}

/// Associate a Binder with `Interface` so transactions carry its token.
pub fn associate(comptime Interface: type, binder: Binder) Error!void {
    if (AIBinder_associateClass(binder.handle, ClassOf(Interface).get())) return;
    // As NDK fromBinder: an in-process object of another definition of this
    // interface keeps its class and is called through the proxy.
    const original = AIBinder_getClass(binder.handle) orelse return error.BadParcelable;
    if (!std.mem.eql(u8, std.mem.span(AIBinder_Class_getDescriptor(original)), Interface.descriptor)) return error.BadParcelable;
}

/// The allocator of a proxy call whose reply cannot allocate.
pub const no_allocator: Allocator = .{ .ptr = undefined, .vtable = &.{
    .alloc = noAlloc,
    .resize = Allocator.noResize,
    .remap = Allocator.noRemap,
    .free = Allocator.noFree,
} };
fn noAlloc(_: *anyopaque, _: usize, _: std.mem.Alignment, _: usize) ?[*]u8 {
    return null;
}

/// Write the status header of `failure` when AIDL carries it as an exception.
/// Returns the transport status the transaction must finish with.
pub fn encodeFailure(parcel: Parcel, failure: anyerror) i32 {
    var status = last_status;
    last_status = .{};
    status.exception = core.exceptionOf(failure) orelse return statusOf(failure);
    parcel.writeStatus(&status) catch |transport| return statusOf(transport);
    return status_ok;
}

/// One proxy call. `allocator` owns whatever the reply allocates.
pub fn call(comptime Method: type, remote: Binder, allocator: Allocator, arguments: Method.Args) Error!ReplyOf(Method) {
    var input: ?*AParcel = null;
    try check(AIBinder_prepareTransaction(remote.handle, &input));
    // AIBinder_transact consumes the input parcel, including on failure.
    errdefer AParcel_delete(input);
    try encodeArgs(Method, .{ .raw = input.? }, arguments);
    var output: ?*AParcel = null;
    const status = AIBinder_transact(remote.handle, Method.code, &input, &output, if (Method.oneway) flag_oneway else 0);
    input = null;
    defer AParcel_delete(output);
    try check(status);
    if (Method.oneway) return runtime.unwrapReply(Method, undefined);
    return runtime.unwrapReply(Method, try runtime.decodeReply(Method, .{ .raw = output.? }, allocator, &last_status));
}

/// What a server method learns about the transaction it is serving.
pub const Call = struct {
    /// Freed when the transaction returns; use it for the reply.
    arena: Allocator,
};

/// The Binder object of an `Interface` implemented by `Implementation`.
///
/// Each AIDL method `m` is served by
/// `fn m(self: *Implementation, call: *aidl.Call, <in arguments>...) !Result`
/// where `Result` is the return value, or the method's `Reply` struct when it
/// also has `out` parameters. `error.SecurityException`, `IllegalArgument`,
/// `NullPointer`, `IllegalState`, `UnsupportedOperation` and `ServiceSpecific`
/// reach the caller as the matching AIDL exception.
pub fn Server(comptime Interface: type, comptime Implementation: type) type {
    return struct {
        object: LocalObject,
        gpa: Allocator,
        io: std.Io,
        implementation: *Implementation,

        /// `gpa` backs the per-transaction arena and must be thread-safe.
        pub fn init(self: *@This(), gpa: Allocator, io: std.Io, implementation: *Implementation) void {
            self.* = .{ .gpa = gpa, .io = io, .implementation = implementation, .object = .{ .context = self, .transact = transact } };
        }

        /// A new strong reference to a new Binder object. `self` must outlive it.
        pub fn binder(self: *@This()) Binder {
            return self.object.binder(Interface);
        }

        fn transact(context: *anyopaque, code: u32, input: Parcel, output: Parcel) i32 {
            const self: *@This() = @ptrCast(@alignCast(context));
            const implementation = self.implementation;
            var arena: std.heap.ArenaAllocator = .init(self.gpa);
            defer arena.deinit();
            inline for (Interface.methods) |Method| {
                if (code == Method.code) {
                    serve(Method, self.io, implementation, arena.allocator(), input, output) catch |failure|
                        return encodeFailure(output, failure);
                    return status_ok;
                }
            }
            return status_unknown_transaction;
        }

        fn serve(comptime Method: type, io: std.Io, implementation: *Implementation, arena: Allocator, input: Parcel, output: Parcel) !void {
            // A versioned interface answers its own identity unless the
            // implementation chooses to.
            if (comptime !@hasDecl(Implementation, Method.name)) {
                if (comptime std.mem.eql(u8, Method.name, "getInterfaceVersion"))
                    return encodeReply(Method, output, .{ ._result = Interface.version });
                if (comptime std.mem.eql(u8, Method.name, "getInterfaceHash"))
                    return encodeReply(Method, output, .{ ._result = Interface.hash });
            }
            const arguments = try decodeArgs(Method, input, arena);
            defer releaseArgs(Method, io, arguments);
            var context: Call = .{ .arena = arena };
            var parameters: std.meta.ArgsTuple(@TypeOf(@field(Implementation, Method.name))) = undefined;
            parameters[0] = implementation;
            parameters[1] = &context;
            inline for (Method.args, 2..) |argument, index| parameters[index] = @field(arguments, argument[0]);
            const result: ReplyOf(Method) = try @call(.auto, @field(Implementation, Method.name), parameters);
            if (!Method.oneway) try encodeReply(Method, output, runtime.wrapReply(Method, result));
        }
    };
}
