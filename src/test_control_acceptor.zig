//! One blocking std.Io accept task. Accepted descriptors move to the UI owner
//! through a one-slot queue and eventfd; this task never enters ART or reads an
//! Android tree. Demand is rearmed only when the previous client is retired.
const std = @import("std");
const Io = std.Io;
const EventFd = @import("event_fd").EventFd;
const Self = @This();

const Accepted = anyerror!Io.File;
gpa: std.mem.Allocator,
io: Io,
listener: Io.net.Server,
wake: EventFd,
demand: Io.Event = .is_set,
group: Io.Group = .init,
storage: [1]Accepted = undefined,
ready: Io.Queue(Accepted),

pub fn init(gpa: std.mem.Allocator, io: Io, path: []const u8) !*Self {
    const self = try gpa.create(Self);
    errdefer gpa.destroy(self);
    // std.Io's Unix listen constructor binds and listens together. Keep the
    // endpoint inaccessible until chmod completes, then atomically publish it
    // without replacing any existing socket. Linux pathname sockets follow the
    // inode across rename; all filesystem and socket ownership stays in std.Io.
    var nonce: [8]u8 = undefined;
    io.random(&nonce);
    var name_buffer: [21]u8 = undefined;
    const name = try std.fmt.bufPrint(&name_buffer, ".azk-{s}", .{std.fmt.bytesToHex(nonce, .lower)});
    const staging = try std.fs.path.join(gpa, &.{ std.fs.path.dirname(path) orelse ".", name });
    defer gpa.free(staging);
    try Io.Dir.cwd().createDir(io, staging, .fromMode(0o700));
    defer Io.Dir.cwd().deleteDir(io, staging) catch {};
    const staged_path = try std.fs.path.join(gpa, &.{ staging, "s" });
    defer gpa.free(staged_path);
    // Binding through the owned directory descriptor keeps the temporary
    // sockaddr short even when the requested endpoint is near sun_path's limit.
    var staging_dir = try Io.Dir.cwd().openDir(io, staging, .{});
    defer staging_dir.close(io);
    var bind_buffer: [64]u8 = undefined;
    const bind_path = try std.fmt.bufPrint(&bind_buffer, "/proc/self/fd/{d}/s", .{staging_dir.handle});
    const address = try Io.net.UnixAddress.init(bind_path);
    var listener = try address.listen(io, .{ .kernel_backlog = 4 });
    errdefer listener.deinit(io);
    defer Io.Dir.cwd().deleteFile(io, staged_path) catch {};
    try Io.Dir.cwd().setFilePermissions(io, staged_path, .fromMode(0o600), .{});
    try Io.Dir.cwd().renamePreserve(staged_path, Io.Dir.cwd(), path, io);
    errdefer Io.Dir.cwd().deleteFile(io, path) catch {};
    const wake = try EventFd.init(io);
    errdefer wake.close();
    self.* = .{ .gpa = gpa, .io = io, .listener = listener, .wake = wake, .ready = undefined };
    self.ready = .init(&self.storage);
    try self.group.concurrent(io, run, .{self});
    return self;
}

pub fn deinit(self: *Self) void {
    self.group.cancel(self.io);
    if (self.take()) |result| {
        if (result) |file| file.close(self.io) else |_| {}
    }
    self.listener.deinit(self.io);
    self.wake.close();
    self.gpa.destroy(self);
}

pub fn take(self: *Self) ?Accepted {
    _ = self.wake.consume();
    var result: [1]Accepted = undefined;
    const count = self.ready.get(self.io, &result, 0) catch return null;
    return if (count == 0) null else result[0];
}
pub fn again(self: *Self) void {
    self.demand.set(self.io);
}

pub fn nonblocking(io: Io, file: Io.File) !Io.File {
    var enabled: i32 = 1;
    const result = try io.operate(.{ .device_io_control = .{
        .file = file,
        .code = std.os.linux.T.FIONBIO,
        .arg = &enabled,
    } });
    if (result.device_io_control != 0) return error.NonblockingControlUnavailable;
    var ready = file;
    ready.flags.nonblocking = true;
    return ready;
}

fn accept(self: *Self) !Io.File {
    const stream = while (true) {
        break self.listener.accept(self.io) catch |err| switch (err) {
            error.ConnectionAborted => continue,
            else => return err,
        };
    };
    const file: Io.File = .{ .handle = stream.socket.handle, .flags = .{ .nonblocking = false } };
    errdefer file.close(self.io);
    return nonblocking(self.io, file);
}
fn run(self: *Self) void {
    while (true) {
        self.demand.wait(self.io) catch return;
        self.demand.reset();
        const result = self.accept();
        self.ready.putOne(self.io, result) catch {
            if (result) |file| file.close(self.io) else |_| {}
            return;
        };
        self.wake.signal();
        // A terminal listener failure is reported once, rather than spinning a
        // failed accept on the UI loop. SocketNotListening also covers shutdown.
        if (result) |_| {} else |_| return;
    }
}
