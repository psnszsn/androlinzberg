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
//! composition rules (arrays, nullability, parcelable framing) live here.
const std = @import("std");
pub const Allocator = std.mem.Allocator;
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
extern fn AIBinder_getUserData(?*AIBinder) callconv(.c) ?*anyopaque;
extern fn AIBinder_incStrong(?*AIBinder) callconv(.c) void;
extern fn AIBinder_decStrong(?*AIBinder) callconv(.c) void;
extern fn AIBinder_prepareTransaction(*AIBinder, *?*AParcel) callconv(.c) i32;
extern fn AIBinder_transact(*AIBinder, u32, *?*AParcel, *?*AParcel, u32) callconv(.c) i32;
extern fn AParcel_delete(?*AParcel) callconv(.c) void;
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

/// android.os.Parcel exception codes carried by the AIDL status header.
pub const Exception = enum(i32) {
    none = 0,
    security = -1,
    bad_parcelable = -2,
    illegal_argument = -3,
    null_pointer = -4,
    illegal_state = -5,
    network_main_thread = -6,
    unsupported_operation = -7,
    service_specific = -8,
    parcelable = -9,
    transaction_failed = -129,
    _,
};

pub const Error = error{
    OutOfMemory,
    /// The transport rejected or lost the transaction.
    TransactionFailed,
    DeadObject,
    UnknownTransaction,
    /// The parcel does not hold what the interface declares.
    BadParcelable,
    UnexpectedNull,
    SecurityException,
    IllegalArgument,
    NullPointer,
    IllegalState,
    UnsupportedOperation,
    /// A `ServiceSpecificException`; its code is in `lastStatus()`.
    ServiceSpecific,
    /// Any other remote exception; its code is in `lastStatus()`.
    RemoteException,
};

/// The AIDL status of a call, as `android.os.Parcel.writeException` carries it.
pub const Status = struct {
    exception: Exception = .none,
    service_specific: i32 = 0,
    message_buffer: [256]u8 = undefined,
    message_len: usize = 0,

    pub fn message(self: *const Status) []const u8 {
        return self.message_buffer[0..self.message_len];
    }

    pub fn setMessage(self: *Status, text: []const u8) void {
        self.message_len = @min(text.len, self.message_buffer.len - 1);
        @memcpy(self.message_buffer[0..self.message_len], text[0..self.message_len]);
    }
};

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

fn exceptionOf(failure: anyerror) ?Exception {
    return switch (failure) {
        error.SecurityException => .security,
        error.IllegalArgument => .illegal_argument,
        error.NullPointer => .null_pointer,
        error.IllegalState => .illegal_state,
        error.UnsupportedOperation => .unsupported_operation,
        error.ServiceSpecific => .service_specific,
        else => null,
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

fn Scalar(comptime Value: type, comptime writer: []const u8, comptime reader: []const u8) type {
    return struct {
        pub const T = Value;
        pub fn write(parcel: Parcel, value: T) Error!void {
            return @field(Parcel, writer)(parcel, value);
        }
        pub fn read(parcel: Parcel, _: Allocator) Error!T {
            return @field(Parcel, reader)(parcel);
        }
        pub fn release(_: std.Io, _: T) void {}
    };
}

/// Codecs the generator names for each AIDL type.
pub const codec = struct {
    pub const Bool = Scalar(bool, "writeBool", "readBool");
    pub const Byte = Scalar(i8, "writeByte", "readByte");
    pub const Char = Scalar(u16, "writeChar", "readChar");
    pub const Int = Scalar(i32, "writeInt32", "readInt32");
    pub const Long = Scalar(i64, "writeInt64", "readInt64");
    pub const Float = Scalar(f32, "writeFloat", "readFloat");
    pub const Double = Scalar(f64, "writeDouble", "readDouble");

    /// AIDL `String`: UTF-8 here, UTF-16 on the wire.
    pub const String = struct {
        pub const T = []const u8;
        pub fn release(_: std.Io, _: T) void {}
        pub const nullable_on_wire = true;
        pub fn write(parcel: Parcel, value: T) Error!void {
            return parcel.writeString(value);
        }
        pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
            return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
        }
        pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
            return parcel.writeString(value);
        }
        pub fn readNullable(parcel: Parcel, allocator: Allocator) Error!?T {
            return parcel.readString(allocator);
        }
    };

    /// AIDL `byte[]`, which is a packed blob rather than an element list.
    pub const ByteArray = struct {
        pub const T = []const u8;
        pub fn release(_: std.Io, _: T) void {}
        pub const nullable_on_wire = true;
        pub fn write(parcel: Parcel, value: T) Error!void {
            return parcel.writeByteArray(value);
        }
        pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
            return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
        }
        pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
            return parcel.writeByteArray(value);
        }
        pub fn readNullable(parcel: Parcel, allocator: Allocator) Error!?T {
            return parcel.readByteArray(allocator);
        }
    };

    /// AIDL `IBinder`. A read reference is owned by the reader.
    pub const Binder = struct {
        pub const T = aidl.Binder;
        pub fn release(_: std.Io, value: T) void {
            value.deinit();
        }
        pub const nullable_on_wire = true;
        pub fn write(parcel: Parcel, value: T) Error!void {
            return parcel.writeBinder(value);
        }
        pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
            return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
        }
        pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
            return parcel.writeBinder(value);
        }
        pub fn readNullable(parcel: Parcel, _: Allocator) Error!?T {
            return parcel.readBinder();
        }
    };

    /// AIDL `ParcelFileDescriptor`. A read descriptor is owned by the reader.
    pub const File = struct {
        pub const T = std.Io.File;
        pub fn release(io: std.Io, value: T) void {
            value.close(io);
        }
        pub const nullable_on_wire = true;
        pub fn write(parcel: Parcel, value: T) Error!void {
            return parcel.writeFile(value);
        }
        pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
            return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
        }
        pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
            return parcel.writeFile(value);
        }
        pub fn readNullable(parcel: Parcel, _: Allocator) Error!?T {
            return parcel.readFile();
        }
    };

    /// A generated interface, carried as its `Client`.
    pub fn Interface(comptime Declaration: type) type {
        return struct {
            pub const T = Declaration.Client;
            pub fn release(_: std.Io, value: T) void {
                value.deinit();
            }
            pub const nullable_on_wire = true;
            pub fn write(parcel: Parcel, value: T) Error!void {
                return parcel.writeBinder(value.remote);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
            }
            pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
                return parcel.writeBinder(if (value) |client| client.remote else null);
            }
            pub fn readNullable(parcel: Parcel, _: Allocator) Error!?T {
                const binder = try parcel.readBinder() orelse return null;
                return try Declaration.Client.fromBinder(binder);
            }
        };
    }

    /// A generated enum, carried as its backing type.
    pub fn Enum(comptime Declaration: type) type {
        const Backing = switch (@typeInfo(Declaration).@"enum".tag_type) {
            i8 => Byte,
            i32 => Int,
            i64 => Long,
            else => @compileError("AIDL enums are backed by byte, int or long"),
        };
        return struct {
            pub const T = Declaration;
            pub fn release(_: std.Io, _: T) void {}
            pub fn write(parcel: Parcel, value: T) Error!void {
                return Backing.write(parcel, @backingInt(value));
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                return @fromBackingInt(@intCast(try Backing.read(parcel, allocator)));
            }
        };
    }

    /// A structured parcelable or union (generated), or an unstructured
    /// parcelable supplied by the `aidl_custom` module. Always preceded by the
    /// typed-object presence flag, as `Parcel.writeTypedObject` writes it.
    pub fn Parcelable(comptime Declaration: type) type {
        return struct {
            pub const T = Declaration;
            pub fn release(io: std.Io, value: T) void {
                releaseBody(Declaration, io, value);
            }
            pub const nullable_on_wire = true;
            pub fn write(parcel: Parcel, value: T) Error!void {
                try parcel.writeInt32(1);
                return writeBody(Declaration, parcel, value);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
            }
            pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
                const present = value orelse return parcel.writeInt32(0);
                return write(parcel, present);
            }
            pub fn readNullable(parcel: Parcel, allocator: Allocator) Error!?T {
                if (try parcel.readInt32() == 0) return null;
                return try readBody(Declaration, parcel, allocator);
            }
        };
    }

    /// `T[]` and `List<T>` other than `byte[]`: a count, then each element.
    pub fn Array(comptime Element: type) type {
        return struct {
            pub const T = []const Element.T;
            pub fn release(io: std.Io, value: T) void {
                for (value) |element| Element.release(io, element);
            }
            pub const nullable_on_wire = true;
            pub fn write(parcel: Parcel, value: T) Error!void {
                return writeNullable(parcel, value);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                return try readNullable(parcel, allocator) orelse error.UnexpectedNull;
            }
            pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
                const elements = value orelse return parcel.writeInt32(-1);
                if (elements.len > std.math.maxInt(i32)) return error.BadParcelable;
                try parcel.writeInt32(@intCast(elements.len));
                for (elements) |element| try Element.write(parcel, element);
            }
            pub fn readNullable(parcel: Parcel, allocator: Allocator) Error!?T {
                const count = try parcel.readInt32();
                if (count < 0) return null;
                // Elements occupy at least four bytes; an arena bounds a hostile count.
                const elements = try allocator.alloc(Element.T, @intCast(count));
                errdefer allocator.free(elements);
                for (elements) |*element| element.* = try Element.read(parcel, allocator);
                return elements;
            }
        };
    }

    /// `T[N]`: the same layout as `T[]` with a checked count.
    pub fn FixedArray(comptime Element: type, comptime length: usize) type {
        return struct {
            pub const T = [length]Element.T;
            pub fn release(io: std.Io, value: T) void {
                for (value) |element| Element.release(io, element);
            }
            pub fn write(parcel: Parcel, value: T) Error!void {
                try parcel.writeInt32(length);
                for (value) |element| try Element.write(parcel, element);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                if (try parcel.readInt32() != length) return error.BadParcelable;
                var value: T = undefined;
                for (&value) |*element| element.* = try Element.read(parcel, allocator);
                return value;
            }
        };
    }

    /// `byte[N]`, packed like `byte[]`.
    pub fn FixedByteArray(comptime length: usize) type {
        return struct {
            pub const T = [length]u8;
            pub fn release(_: std.Io, _: T) void {}
            pub fn write(parcel: Parcel, value: T) Error!void {
                return parcel.writeByteArray(&value);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                const bytes = try parcel.readByteArray(allocator) orelse return error.UnexpectedNull;
                defer allocator.free(bytes);
                if (bytes.len != length) return error.BadParcelable;
                return bytes[0..length].*;
            }
        };
    }

    /// `@nullable(heap=true) T`, which lets a parcelable contain itself.
    pub fn Boxed(comptime Inner: type) type {
        return struct {
            pub const T = ?*const Inner.T;
            pub fn release(io: std.Io, value: T) void {
                if (value) |box| Inner.release(io, box.*);
            }
            pub fn write(parcel: Parcel, value: T) Error!void {
                return Inner.writeNullable(parcel, if (value) |box| box.* else null);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                const value = try Inner.readNullable(parcel, allocator) orelse return null;
                const box = try allocator.create(Inner.T);
                box.* = value;
                return box;
            }
        };
    }

    /// `@nullable T` for the types AIDL allows to be null.
    pub fn Nullable(comptime Inner: type) type {
        if (!@hasDecl(Inner, "nullable_on_wire")) @compileError("AIDL type cannot be @nullable");
        return struct {
            pub const T = ?Inner.T;
            pub fn release(io: std.Io, value: T) void {
                if (value) |present| Inner.release(io, present);
            }
            pub fn write(parcel: Parcel, value: T) Error!void {
                return Inner.writeNullable(parcel, value);
            }
            pub fn read(parcel: Parcel, allocator: Allocator) Error!T {
                return Inner.readNullable(parcel, allocator);
            }
        };
    }
};

const aidl = @This();

fn writeBody(comptime Declaration: type, parcel: Parcel, value: Declaration) Error!void {
    if (@hasDecl(Declaration, "aidl_fields")) {
        // Structured parcelable: a byte size that lets older readers skip
        // fields they do not know, then the fields in declaration order.
        const start = parcel.position();
        try parcel.writeInt32(0);
        inline for (Declaration.aidl_fields) |field| try field[1].write(parcel, @field(value, field[0]));
        const end = parcel.position();
        try parcel.setPosition(start);
        try parcel.writeInt32(end - start);
        return parcel.setPosition(end);
    }
    if (@hasDecl(Declaration, "aidl_alternatives")) {
        inline for (Declaration.aidl_alternatives, 0..) |alternative, tag| {
            if (value == @field(std.meta.Tag(Declaration), alternative[0])) {
                try parcel.writeInt32(tag);
                return alternative[1].write(parcel, @field(value, alternative[0]));
            }
        }
        unreachable;
    }
    return value.writeToParcel(parcel);
}

fn readBody(comptime Declaration: type, parcel: Parcel, allocator: Allocator) Error!Declaration {
    if (@hasDecl(Declaration, "aidl_fields")) {
        const start = parcel.position();
        const size = try parcel.readInt32();
        if (size < 4 or size > std.math.maxInt(i32) - start) return error.BadParcelable;
        var value: Declaration = .{};
        inline for (Declaration.aidl_fields) |field| {
            // A shorter parcelable came from an older writer: keep the defaults.
            if (parcel.position() - start < size)
                @field(value, field[0]) = try field[1].read(parcel, allocator);
        }
        try parcel.setPosition(start + size);
        return value;
    }
    if (@hasDecl(Declaration, "aidl_alternatives")) {
        const tag = try parcel.readInt32();
        inline for (Declaration.aidl_alternatives, 0..) |alternative, index| {
            if (tag == index) return @unionInit(Declaration, alternative[0], try alternative[1].read(parcel, allocator));
        }
        return error.BadParcelable;
    }
    return Declaration.readFromParcel(parcel, allocator);
}

/// Drop the Binder references and descriptors a decoded value owns.
fn releaseBody(comptime Declaration: type, io: std.Io, value: Declaration) void {
    if (@hasDecl(Declaration, "aidl_fields")) {
        inline for (Declaration.aidl_fields) |field| field[1].release(io, @field(value, field[0]));
    } else if (@hasDecl(Declaration, "aidl_alternatives")) {
        inline for (Declaration.aidl_alternatives) |alternative| {
            if (value == @field(std.meta.Tag(Declaration), alternative[0]))
                alternative[1].release(io, @field(value, alternative[0]));
        }
    } else if (@hasDecl(Declaration, "release")) {
        value.release(io);
    }
}

/// Drop what decoded `in` arguments own once a server method has returned. A
/// method that keeps a Binder clones it; one that keeps a descriptor dups it.
pub fn releaseArgs(comptime Method: type, io: std.Io, arguments: Method.Args) void {
    inline for (Method.args) |argument| argument[1].release(io, @field(arguments, argument[0]));
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

/// User data of every Binder object this runtime creates.
const LocalObject = struct {
    context: *anyopaque,
    transact: *const fn (*anyopaque, u32, Parcel, Parcel) i32,

    fn onCreate(arguments: ?*anyopaque) callconv(.c) ?*anyopaque {
        return arguments;
    }
    fn onDestroy(_: ?*anyopaque) callconv(.c) void {}
    fn onTransact(binder: *AIBinder, code: u32, input: *const AParcel, output: *AParcel) callconv(.c) i32 {
        const self: *LocalObject = @ptrCast(@alignCast(AIBinder_getUserData(binder) orelse return status_unknown_error));
        return self.transact(self.context, code, .{ .raw = @constCast(input) }, .{ .raw = output });
    }
};

/// Associate a Binder with `Interface` so transactions carry its token.
pub fn associate(comptime Interface: type, binder: Binder) Error!void {
    if (!AIBinder_associateClass(binder.handle, ClassOf(Interface).get())) return error.BadParcelable;
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

/// The value AIDL gives a parcelable field that declares no default.
pub fn defaultValue(comptime T: type) T {
    return switch (@typeInfo(T)) {
        .bool => false,
        .int, .float => 0,
        .optional => null,
        .@"enum" => @fromBackingInt(@intCast(0)),
        .array => |array| @splat(defaultValue(array.child)),
        .pointer => &.{},
        .@"union" => T.aidl_default,
        .@"struct" => if (@hasDecl(T, "aidl_default")) T.aidl_default else .{},
        else => @compileError("no AIDL default for " ++ @typeName(T)),
    };
}

/// What a proxy method returns: nothing, the return value alone, or the
/// method's `Reply` when it also has `out` parameters.
pub fn ReplyOf(comptime Method: type) type {
    if (Method.reply.len == 0) return void;
    if (Method.reply.len == 1 and comptime std.mem.eql(u8, Method.reply[0][0], "result")) return Method.reply[0][1].T;
    return Method.Reply;
}

fn unwrapReply(comptime Method: type, reply: Method.Reply) ReplyOf(Method) {
    return switch (ReplyOf(Method)) {
        void => {},
        Method.Reply => reply,
        else => reply.result,
    };
}

fn wrapReply(comptime Method: type, value: ReplyOf(Method)) Method.Reply {
    return switch (ReplyOf(Method)) {
        void => .{},
        Method.Reply => value,
        else => .{ .result = value },
    };
}

/// Marshal the `in` side of a method exactly as its proxy does.
pub fn encodeArgs(comptime Method: type, parcel: Parcel, arguments: Method.Args) Error!void {
    inline for (Method.args) |argument| try argument[1].write(parcel, @field(arguments, argument[0]));
}

/// Unmarshal the `in` side of a method; allocations belong to `allocator`.
pub fn decodeArgs(comptime Method: type, parcel: Parcel, allocator: Allocator) Error!Method.Args {
    var arguments: Method.Args = undefined;
    inline for (Method.args) |argument| @field(arguments, argument[0]) = try argument[1].read(parcel, allocator);
    return arguments;
}

/// Write `Status::ok` and then the return value and `out` parameters.
pub fn encodeReply(comptime Method: type, parcel: Parcel, reply: Method.Reply) Error!void {
    const ok = AStatus_newOk();
    defer AStatus_delete(ok);
    try check(AParcel_writeStatusHeader(parcel.raw, ok));
    inline for (Method.reply) |value| try value[1].write(parcel, @field(reply, value[0]));
}

/// Write the status header of `failure` when AIDL carries it as an exception.
/// Returns the transport status the transaction must finish with.
pub fn encodeFailure(parcel: Parcel, failure: anyerror) i32 {
    const exception = exceptionOf(failure) orelse return statusOf(failure);
    var text: [257]u8 = undefined;
    const message = last_status.message();
    @memcpy(text[0..message.len], message);
    text[message.len] = 0;
    const status = if (exception == .service_specific)
        AStatus_fromServiceSpecificErrorWithMessage(last_status.service_specific, @ptrCast(&text))
    else
        AStatus_fromExceptionCodeWithMessage(@backingInt(exception), @ptrCast(&text));
    defer AStatus_delete(status);
    last_status = .{};
    return AParcel_writeStatusHeader(parcel.raw, status);
}

fn decodeReply(comptime Method: type, parcel: Parcel, allocator: Allocator) Error!Method.Reply {
    var status: ?*AStatus = null;
    try check(AParcel_readStatusHeader(parcel.raw, &status));
    defer AStatus_delete(status);
    const exception: Exception = @fromBackingInt(@intCast(AStatus_getExceptionCode(status.?)));
    if (exception != .none) {
        last_status = .{ .exception = exception, .service_specific = AStatus_getServiceSpecificError(status.?) };
        last_status.setMessage(std.mem.span(AStatus_getMessage(status.?)));
        return switch (exception) {
            .security => error.SecurityException,
            .bad_parcelable => error.BadParcelable,
            .illegal_argument => error.IllegalArgument,
            .null_pointer => error.NullPointer,
            .illegal_state => error.IllegalState,
            .unsupported_operation => error.UnsupportedOperation,
            .service_specific => error.ServiceSpecific,
            else => error.RemoteException,
        };
    }
    var reply: Method.Reply = undefined;
    inline for (Method.reply) |value| @field(reply, value[0]) = try value[1].read(parcel, allocator);
    return reply;
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
    if (Method.oneway) return unwrapReply(Method, undefined);
    return unwrapReply(Method, try decodeReply(Method, .{ .raw = output.? }, allocator));
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
            return .{ .handle = AIBinder_new(ClassOf(Interface).get(), &self.object) };
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
            const arguments = try decodeArgs(Method, input, arena);
            defer releaseArgs(Method, io, arguments);
            var context: Call = .{ .arena = arena };
            var parameters: std.meta.ArgsTuple(@TypeOf(@field(Implementation, Method.name))) = undefined;
            parameters[0] = implementation;
            parameters[1] = &context;
            inline for (Method.args, 2..) |argument, index| parameters[index] = @field(arguments, argument[0]);
            const result: ReplyOf(Method) = try @call(.auto, @field(Implementation, Method.name), parameters);
            if (!Method.oneway) try encodeReply(Method, output, wrapReply(Method, result));
        }
    };
}
