//! A Unix stream socket carrying protocol frames plus SCM_RIGHTS descriptors.
//!
//! `Reader` and `Writer` implement `std.Io.Reader`/`std.Io.Writer` so the
//! protocol layer (`Client`, `Server`) sees a plain byte stream. Descriptors
//! received alongside bytes queue up on the reader in arrival order; a frame
//! whose header announces `fd_count` descriptors takes that many from the
//! queue. Descriptors attached to the writer ride with the next bytes drained,
//! which the protocol layer guarantees is the start of a frame.
//!
//! std.Io policy note: receiving uses `std.Io.net.Socket.receive`, which
//! carries ancillary data. Sending a frame that carries descriptors cannot use
//! `std.Io.net` in this Zig version: its send path always supplies an IP
//! `msg_name`, which the kernel rejects on a connected AF_UNIX socket. Like
//! `platform/event_fd.zig`, that single operation uses `std.os.linux.sendmsg`;
//! every other read, write, and close goes through std.Io, and every
//! descriptor is owned by a `std.Io.File`.
const Connection = @This();

const std = @import("std");
const Io = std.Io;
const net = Io.net;
const linux = std.os.linux;
const assert = std.debug.assert;

pub const max_fds = @import("Client.zig").limits.max_fds;

io: Io,
stream: net.Stream,
reader: Reader,
writer: Writer,

pub fn init(io: Io, stream: net.Stream, read_buffer: []u8, write_buffer: []u8) Connection {
    return .{
        .io = io,
        .stream = stream,
        .reader = .init(io, stream, read_buffer),
        .writer = .init(io, stream, write_buffer),
    };
}

/// Closes the socket and every descriptor still queued on the reader or
/// attached to the writer.
pub fn deinit(c: *Connection) void {
    c.reader.closeQueued();
    c.writer.closeAttached();
    c.stream.close(c.io);
}

pub const Reader = struct {
    io: Io,
    interface: Io.Reader,
    stream: net.Stream,
    err: ?Error = null,
    /// Descriptors received but not yet taken, in arrival order.
    fds: [max_fds * 2]Io.File = undefined,
    fds_len: usize = 0,
    control: [control_len]u8 align(@alignOf(linux.cmsghdr)) = undefined,

    pub const Error = net.Socket.ReceiveError || error{ ControlTruncated, TooManyDescriptors };
    const control_len = cmsgSpace(@sizeOf(std.posix.fd_t) * max_fds);

    pub fn init(io: Io, stream: net.Stream, buffer: []u8) Reader {
        return .{
            .io = io,
            .stream = stream,
            .interface = .{
                .vtable = &.{ .stream = streamImpl, .readVec = readVec },
                .buffer = buffer,
                .seek = 0,
                .end = 0,
            },
        };
    }

    /// Takes `n` descriptors that arrived with the current frame.
    pub fn takeFds(r: *Reader, dest: []Io.File) error{MissingDescriptors}!void {
        if (r.fds_len < dest.len) return error.MissingDescriptors;
        @memcpy(dest, r.fds[0..dest.len]);
        const remaining = r.fds_len - dest.len;
        std.mem.copyForwards(Io.File, r.fds[0..remaining], r.fds[dest.len..r.fds_len]);
        r.fds_len = remaining;
    }

    pub fn queuedFdCount(r: *const Reader) usize {
        return r.fds_len;
    }

    pub fn closeQueued(r: *Reader) void {
        for (r.fds[0..r.fds_len]) |file| file.close(r.io);
        r.fds_len = 0;
    }

    fn streamImpl(io_r: *Io.Reader, io_w: *Io.Writer, limit: Io.Limit) Io.Reader.StreamError!usize {
        const dest = limit.slice(try io_w.writableSliceGreedy(1));
        var data: [1][]u8 = .{dest};
        const n = try readVec(io_r, &data);
        io_w.advance(n);
        return n;
    }

    fn readVec(io_r: *Io.Reader, data: [][]u8) Io.Reader.Error!usize {
        const r: *Reader = @alignCast(@fieldParentPtr("interface", io_r));
        // Only the first vector is filled: one recvmsg per call keeps the
        // descriptor-to-frame accounting simple.
        var iovecs_buffer: [8][]u8 = undefined;
        const dest_n, const data_size = try io_r.writableVector(&iovecs_buffer, data);
        assert(dest_n >= 1);
        const dest = iovecs_buffer[0];
        assert(dest.len > 0);
        var message: net.IncomingMessage = .init;
        message.control = &r.control;
        const maybe_err, const count = (r.io.operate(.{ .net_receive = .{
            .socket_handle = r.stream.socket.handle,
            .message_buffer = (&message)[0..1],
            .data_buffer = dest,
            .flags = .{},
        } }) catch |err| {
            r.err = err;
            return error.ReadFailed;
        }).net_receive;
        if (maybe_err) |err| {
            r.err = err;
            return error.ReadFailed;
        }
        assert(count == 1);
        if (message.flags.ctrunc) {
            r.err = error.ControlTruncated;
            return error.ReadFailed;
        }
        r.collectFds(message.control) catch |err| {
            r.err = err;
            return error.ReadFailed;
        };
        const n = message.data.len;
        if (n == 0) return error.EndOfStream;
        if (n > data_size) {
            r.interface.end += n - data_size;
            return data_size;
        }
        return n;
    }

    fn collectFds(r: *Reader, control: []const u8) Error!void {
        var offset: usize = 0;
        while (offset + @sizeOf(linux.cmsghdr) <= control.len) {
            const hdr: *align(1) const linux.cmsghdr = @ptrCast(control[offset..].ptr);
            if (hdr.len < @sizeOf(linux.cmsghdr) or offset + hdr.len > control.len) break;
            if (hdr.level == linux.SOL.SOCKET and hdr.type == linux.SCM.RIGHTS) {
                const payload = control[offset + cmsgLen(0) .. offset + hdr.len];
                var i: usize = 0;
                while (i + @sizeOf(std.posix.fd_t) <= payload.len) : (i += @sizeOf(std.posix.fd_t)) {
                    const fd = std.mem.readInt(std.posix.fd_t, payload[i..][0..@sizeOf(std.posix.fd_t)], .little);
                    const file: Io.File = .{ .handle = fd, .flags = .{ .nonblocking = false } };
                    if (r.fds_len == r.fds.len) {
                        file.close(r.io);
                        return error.TooManyDescriptors;
                    }
                    r.fds[r.fds_len] = file;
                    r.fds_len += 1;
                }
            }
            offset += cmsgAlign(hdr.len);
        }
    }
};

pub const Writer = struct {
    io: Io,
    interface: Io.Writer,
    stream: net.Stream,
    err: ?Error = null,
    /// Descriptors to attach to the next drained bytes.
    attached: [max_fds]Io.File = undefined,
    attached_len: usize = 0,

    pub const Error = Io.Operation.NetWrite.Error || Io.Cancelable || error{ BrokenPipe, SendFailed };

    pub fn init(io: Io, stream: net.Stream, buffer: []u8) Writer {
        return .{
            .io = io,
            .stream = stream,
            .interface = .{
                .vtable = &.{ .drain = drain },
                .buffer = buffer,
            },
        };
    }

    /// Attaches descriptors to the next frame. The writer must be flushed
    /// (no buffered bytes) so they land on the frame header. The descriptors
    /// remain owned by the caller; the kernel duplicates them on send.
    pub fn attachFds(w: *Writer, files: []const Io.File) error{ TooManyDescriptors, UnflushedBytes }!void {
        if (w.interface.buffered().len != 0) return error.UnflushedBytes;
        if (w.attached_len + files.len > max_fds) return error.TooManyDescriptors;
        @memcpy(w.attached[w.attached_len..][0..files.len], files);
        w.attached_len += files.len;
    }

    /// Drops attached descriptors without closing them (they belong to the caller).
    pub fn clearAttached(w: *Writer) void {
        w.attached_len = 0;
    }

    fn closeAttached(w: *Writer) void {
        w.attached_len = 0;
    }

    fn drain(io_w: *Io.Writer, data: []const []const u8, splat: usize) Io.Writer.Error!usize {
        const w: *Writer = @alignCast(@fieldParentPtr("interface", io_w));
        const io = w.io;
        const buffered = io_w.buffered();
        const handle = w.stream.socket.handle;
        if (w.attached_len == 0) {
            const result = io.operate(.{ .net_write = .{
                .socket_handle = handle,
                .header = buffered,
                .data = data,
                .splat = splat,
            } }) catch |err| {
                w.err = err;
                return error.WriteFailed;
            };
            const n = result.net_write catch |err| {
                w.err = err;
                return error.WriteFailed;
            };
            return io_w.consume(n);
        }
        // Descriptor-bearing send: one sendmsg with the buffered bytes and as
        // much of `data` as fits in the iovec table. The descriptors attach to
        // the first byte, which is the frame header.
        var iovecs: [8]std.posix.iovec_const = undefined;
        var iov_len: usize = 0;
        if (buffered.len > 0) {
            iovecs[iov_len] = .{ .base = buffered.ptr, .len = buffered.len };
            iov_len += 1;
        }
        for (data, 0..) |chunk, i| {
            if (iov_len == iovecs.len) break;
            if (chunk.len == 0) continue;
            const repeat: usize = if (i == data.len - 1) splat else 1;
            var k: usize = 0;
            while (k < repeat and iov_len < iovecs.len) : (k += 1) {
                iovecs[iov_len] = .{ .base = chunk.ptr, .len = chunk.len };
                iov_len += 1;
            }
        }
        if (iov_len == 0) return 0;
        var control: [cmsgSpace(@sizeOf(std.posix.fd_t) * max_fds)]u8 align(@alignOf(linux.cmsghdr)) = undefined;
        const payload_len = @sizeOf(std.posix.fd_t) * w.attached_len;
        const hdr: *linux.cmsghdr = @ptrCast(&control);
        hdr.* = .{ .len = cmsgLen(payload_len), .level = linux.SOL.SOCKET, .type = linux.SCM.RIGHTS };
        for (w.attached[0..w.attached_len], 0..) |file, i| {
            std.mem.writeInt(std.posix.fd_t, control[cmsgLen(0) + i * @sizeOf(std.posix.fd_t) ..][0..@sizeOf(std.posix.fd_t)], file.handle, .little);
        }
        const msg: linux.msghdr_const = .{
            .name = null,
            .namelen = 0,
            .iov = &iovecs,
            .iovlen = @intCast(iov_len),
            .control = &control,
            .controllen = @intCast(cmsgSpace(payload_len)),
            .flags = 0,
        };
        while (true) {
            const rc = linux.sendmsg(handle, &msg, linux.MSG.NOSIGNAL);
            switch (linux.errno(rc)) {
                .SUCCESS => {
                    w.attached_len = 0;
                    return io_w.consume(rc);
                },
                .INTR => continue,
                .PIPE, .CONNRESET => {
                    w.err = error.BrokenPipe;
                    return error.WriteFailed;
                },
                else => {
                    w.err = error.SendFailed;
                    return error.WriteFailed;
                },
            }
        }
    }
};

fn cmsgAlign(len: usize) usize {
    return (len + @sizeOf(usize) - 1) & ~(@as(usize, @sizeOf(usize)) - 1);
}

fn cmsgLen(payload: usize) usize {
    return cmsgAlign(@sizeOf(linux.cmsghdr)) + payload;
}

fn cmsgSpace(payload: usize) usize {
    return cmsgAlign(@sizeOf(linux.cmsghdr)) + cmsgAlign(payload);
}

/// Peer credentials of a connected AF_UNIX socket (SO_PEERCRED).
///
/// std.Io has no credential query; this is one getsockopt on a descriptor
/// that stays owned by std.Io.
pub const Credentials = struct { pid: i32, uid: u32, gid: u32 };

pub fn peerCredentials(stream: net.Stream) error{Unexpected}!Credentials {
    var cred: extern struct { pid: i32, uid: u32, gid: u32 } = undefined;
    var len: linux.socklen_t = @sizeOf(@TypeOf(cred));
    const rc = linux.getsockopt(stream.socket.handle, linux.SOL.SOCKET, linux.SO.PEERCRED, @ptrCast(&cred), &len);
    if (linux.errno(rc) != .SUCCESS) return error.Unexpected;
    return .{ .pid = cred.pid, .uid = cred.uid, .gid = cred.gid };
}
