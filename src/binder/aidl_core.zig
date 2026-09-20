//! Transport-independent half of the Zig AIDL runtime.
//!
//! `aidl --lang=zig` emits data types and codec tables; everything here is
//! expressed over a `Transport` that supplies three types:
//!
//!   Parcel  primitive reads and writes, `position`/`setPosition`,
//!           `writeString`/`readString` (UTF-8 here, UTF-16 on the wire),
//!           `writeByteArray`/`readByteArray`, `writeBinder`/`readBinder`,
//!           `writeFile`/`readFile`, `writeStatus`/`readStatus`
//!   Binder  a Binder reference with `deinit`
//!   File    a descriptor with `close(io)`
//!
//! src/binder/aidl.zig instantiates it over libbinder_ndk for ordinary clients
//! and services; src/binder/broker_aidl.zig instantiates it over raw AZKB
//! frames for the broker's IServiceManager. Generated code imports whichever
//! one the build names `aidl`, so one generated interface serves both.
const std = @import("std");
pub const Allocator = std.mem.Allocator;

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

pub fn Runtime(comptime Transport: type) type {
    return struct {
        const Parcel = Transport.Parcel;
        const aidl = Transport;

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
                pub const T = Transport.Binder;
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
                pub const T = Transport.File;
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
                    pub const nullable_on_wire = true;
                    /// `@nullable T[N]`: a count of -1, as for `T[]`.
                    pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
                        return write(parcel, value orelse return parcel.writeInt32(-1));
                    }
                    pub fn readNullable(parcel: Parcel, allocator: Allocator) Error!?T {
                        const start = parcel.position();
                        if (try parcel.readInt32() < 0) return null;
                        try parcel.setPosition(start);
                        return try read(parcel, allocator);
                    }
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
                    pub const nullable_on_wire = true;
                    pub fn writeNullable(parcel: Parcel, value: ?T) Error!void {
                        return if (value) |bytes| write(parcel, bytes) else parcel.writeByteArray(null);
                    }
                    pub fn readNullable(parcel: Parcel, allocator: Allocator) Error!?T {
                        const bytes = try parcel.readByteArray(allocator) orelse return null;
                        defer allocator.free(bytes);
                        if (bytes.len != length) return error.BadParcelable;
                        return bytes[0..length].*;
                    }
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
            if (Method.reply.len == 1 and comptime std.mem.eql(u8, Method.reply[0][0], "_result")) return Method.reply[0][1].T;
            return Method.Reply;
        }

        pub fn unwrapReply(comptime Method: type, reply: Method.Reply) ReplyOf(Method) {
            return switch (ReplyOf(Method)) {
                void => {},
                Method.Reply => reply,
                else => reply._result,
            };
        }

        pub fn wrapReply(comptime Method: type, value: ReplyOf(Method)) Method.Reply {
            return switch (ReplyOf(Method)) {
                void => .{},
                Method.Reply => value,
                else => .{ ._result = value },
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
            try parcel.writeStatus(&.{});
            inline for (Method.reply) |value| try value[1].write(parcel, @field(reply, value[0]));
        }

        fn decodeReplyValues(comptime Method: type, parcel: Parcel, allocator: Allocator) Error!Method.Reply {
            var reply: Method.Reply = undefined;
            inline for (Method.reply) |value| @field(reply, value[0]) = try value[1].read(parcel, allocator);
            return reply;
        }

        /// Read the status header and then the return value and `out`
        /// parameters. A remote exception is left in `status` and returned as
        /// the matching error.
        pub fn decodeReply(comptime Method: type, parcel: Parcel, allocator: Allocator, status: *Status) Error!Method.Reply {
            status.* = try parcel.readStatus();
            return switch (status.exception) {
                .none => decodeReplyValues(Method, parcel, allocator),
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
    };
}

/// The exception AIDL carries for `failure`, if it is one.
pub fn exceptionOf(failure: anyerror) ?Exception {
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
