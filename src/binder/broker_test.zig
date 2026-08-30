//! In-process broker tests with Zig peers standing in for ART processes.
const std = @import("std");
const Io = std.Io;
const net = Io.net;
const testing = std.testing;
const Broker = @import("Broker.zig");
const Client = @import("Client.zig");
const Server = @import("Server.zig");
const Connection = @import("Connection.zig");
const ObjectRef = Client.ObjectRef;
const ObjectEntry = Client.ObjectEntry;
const linux = std.os.linux;

const cookie = "test-cookie";
const system_cookie = "test-system-cookie";

/// Heap-allocated: tasks keep pointers into `group`.
const Harness = struct {
    broker: *Broker,
    path: []u8,
    group: Io.Group = .init,

    fn start(gpa: std.mem.Allocator, io: Io) !*Harness {
        var buf: [64]u8 = undefined;
        var nonce_bytes: [8]u8 = undefined;
        io.random(&nonce_bytes);
        const nonce = std.mem.readInt(u64, &nonce_bytes, .little);
        const path = try gpa.dupe(u8, try std.fmt.bufPrint(&buf, "/tmp/azk-binder-test-{x}.sock", .{nonce}));
        const broker = try Broker.init(gpa, io, .{ .socket_path = path, .cookie = cookie, .system_cookie = system_cookie });
        const h = try gpa.create(Harness);
        h.* = .{ .broker = broker, .path = path };
        try h.group.concurrent(io, runBroker, .{broker});
        return h;
    }

    fn runBroker(broker: *Broker) Io.Cancelable!void {
        broker.run() catch |err| std.debug.print("broker run failed: {t}\n", .{err});
    }

    fn stop(h: *Harness, gpa: std.mem.Allocator, io: Io) void {
        h.broker.shutdown();
        h.group.await(io) catch {};
        h.broker.deinit();
        gpa.free(h.path);
        gpa.destroy(h);
    }
};

const Peer = struct {
    io: Io,
    conn: Connection,
    read_buffer: [64 * 1024]u8 = undefined,
    write_buffer: [64 * 1024]u8 = undefined,
    client: Client = undefined,
    welcome: Server.Message.Welcome = undefined,
    closed: bool = false,

    fn connect(peer: *Peer, io: Io, path: []const u8, hello_cookie: []const u8) !void {
        return peer.connectAs(io, path, hello_cookie, "dev.artzig.test");
    }

    fn connectAs(peer: *Peer, io: Io, path: []const u8, hello_cookie: []const u8, package: []const u8) !void {
        const address = try net.UnixAddress.init(path);
        const stream = try address.connect(io);
        peer.io = io;
        peer.closed = false;
        peer.conn = .init(io, stream, &peer.read_buffer, &peer.write_buffer);
        peer.client = .{ .in = &peer.conn.reader.interface, .out = &peer.conn.writer.interface };
        try peer.client.serveHello(hello_cookie, package);
    }

    fn expectWelcome(peer: *Peer) !void {
        const msg = try peer.next();
        try testing.expectEqual(Server.Message.Tag.welcome, msg.header.tag);
        peer.welcome = try fixedStruct(Server.Message.Welcome, msg.body);
    }

    const Message = struct {
        header: Server.Message.Header,
        body: []const u8,
        fds: []Io.File,
    };

    /// Reads the next broker message into the peer's scratch storage.
    fn next(peer: *Peer) !Message {
        const header = try peer.client.receiveMessage();
        const body = try peer.client.in.take(header.bytes_len);
        try testing.expectEqual(@as(usize, header.fd_count), peer.conn.reader.queuedFdCount());
        const fds = try testing.allocator.alloc(Io.File, header.fd_count);
        try peer.conn.reader.takeFds(fds);
        return .{ .header = header, .body = body, .fds = fds };
    }

    fn close(peer: *Peer) void {
        if (peer.closed) return;
        peer.closed = true;
        peer.conn.deinit();
    }
};

fn fixedStruct(comptime T: type, body: []const u8) !T {
    var r: Io.Reader = .fixed(body);
    return r.takeStruct(T, .little);
}

fn transactBody(body: []const u8) !struct { Server.Message.Transact, Client.Payload } {
    const t = try fixedStruct(Server.Message.Transact, body);
    const payload = try Client.Payload.parse(body[@sizeOf(Server.Message.Transact)..], t.object_count, t.data_len);
    return .{ t, payload };
}

fn replyBody(body: []const u8) !struct { Server.Message.Reply, Client.Payload } {
    const r = try fixedStruct(Server.Message.Reply, body);
    const payload = try Client.Payload.parse(body[@sizeOf(Server.Message.Reply)..], r.object_count, r.data_len);
    return .{ r, payload };
}

fn serviceResult(body: []const u8) !Server.Message.ServiceResult {
    return fixedStruct(Server.Message.ServiceResult, body);
}

test "hello with a bad cookie is rejected" {
    const io = testing.io;
    const h = try Harness.start(testing.allocator, io);
    defer h.stop(testing.allocator, io);
    var peer: Peer = undefined;
    try peer.connectAs(io, h.path, "wrong", "dev.artzig.test");
    defer peer.close();
    const msg = try peer.next();
    try testing.expectEqual(Server.Message.Tag.protocol_error, msg.header.tag);
    testing.allocator.free(msg.fds);
    try testing.expectError(error.EndOfStream, peer.client.receiveMessage());
}

test "registry, transaction routing, nesting, oneway, release and death" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);

    var server: Peer = undefined;
    try server.connect(io, h.path, cookie);
    defer server.close();
    try server.expectWelcome();
    var client: Peer = undefined;
    try client.connect(io, h.path, cookie);
    defer client.close();
    try client.expectWelcome();
    try testing.expect(server.welcome.peer_id != client.welcome.peer_id);
    // Same package -> same uid.
    try testing.expectEqual(@as(u32, 10001), server.welcome.uid);
    try testing.expectEqual(@as(u32, 10001), client.welcome.uid);
    try testing.expectEqual(Server.Message.Welcome.Role.app, client.welcome.role);

    // Lookup with wait blocks until the service is registered.
    try client.client.serveLookupService(1, "dev.artzig.test.svc", true);
    try server.client.serveRegisterService(7, ObjectRef.local(100), "dev.artzig.test.svc");
    {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.service_result, msg.header.tag);
        try testing.expectEqual(@as(u64, 7), msg.header.request_id);
        try testing.expectEqual(Server.Message.ServiceResult.Status.ok, (try serviceResult(msg.body)).status);
    }
    var svc_gid: u64 = 0;
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.service_result, msg.header.tag);
        try testing.expectEqual(@as(u64, 1), msg.header.request_id);
        const result = try serviceResult(msg.body);
        try testing.expectEqual(Server.Message.ServiceResult.Status.ok, result.status);
        try testing.expectEqual(ObjectRef.Kind.remote, result.object.kind);
        svc_gid = result.object.value;
    }

    // Another peer may not take the name; the owner may re-register.
    try client.client.serveRegisterService(2, ObjectRef.local(5), "dev.artzig.test.svc");
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.ServiceResult.Status.already_registered, (try serviceResult(msg.body)).status);
    }

    // Sync call client -> server carrying a client-local callback object.
    const entries = [_]ObjectEntry{.{ .offset = 4, .kind = .binder_local, .value = 42 }};
    try client.client.serveTransact(.{
        .target = ObjectRef.remote(svc_gid),
        .code = 1,
        .request_id = 10,
        .entries = &entries,
        .data = "\x01\x00\x00\x00\x00\x00\x00\x00",
    });
    var routing: u64 = 0;
    var callback_gid: u64 = 0;
    {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
        try testing.expectEqual(@as(u64, 0), msg.header.request_id);
        routing = msg.header.routing_id;
        try testing.expect(routing != 0);
        const t, const payload = try transactBody(msg.body);
        try testing.expectEqual(ObjectRef.Kind.local, t.target.kind);
        try testing.expectEqual(@as(u64, 100), t.target.value);
        try testing.expectEqual(client.welcome.pid, t.caller_pid);
        try testing.expectEqual(@as(usize, 1), payload.entries.len);
        try testing.expectEqual(ObjectEntry.Kind.binder_remote, payload.entry(0).kind);
        callback_gid = payload.entry(0).value;
        try testing.expectEqualSlices(u8, "\x01\x00\x00\x00\x00\x00\x00\x00", payload.data);
    }

    // Nested callback while the client waits: delivered against request 10.
    try server.client.serveTransact(.{
        .target = ObjectRef.remote(callback_gid),
        .code = 2,
        .request_id = 20,
        .parent_routing_id = routing,
    });
    var nested_routing: u64 = 0;
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
        try testing.expectEqual(@as(u64, 10), msg.header.request_id);
        nested_routing = msg.header.routing_id;
        const t, _ = try transactBody(msg.body);
        try testing.expectEqual(ObjectRef.Kind.local, t.target.kind);
        try testing.expectEqual(@as(u64, 42), t.target.value);
    }
    try client.client.serveReply(.{ .routing_id = nested_routing, .data = "ok" });
    {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.reply, msg.header.tag);
        try testing.expectEqual(@as(u64, 20), msg.header.request_id);
        const r, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, r.status);
        try testing.expectEqualSlices(u8, "ok", payload.data);
    }
    // Reply to the outer call returns the callback to its owner as local.
    const reply_entries = [_]ObjectEntry{.{ .offset = 0, .kind = .binder_remote, .value = callback_gid }};
    try server.client.serveReply(.{ .routing_id = routing, .entries = &reply_entries, .data = "done" });
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(@as(u64, 10), msg.header.request_id);
        const r, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, r.status);
        try testing.expectEqual(ObjectEntry.Kind.binder_local, payload.entry(0).kind);
        try testing.expectEqual(@as(u64, 42), payload.entry(0).value);
    }

    // Oneway calls carry no routing id and stay ordered.
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 3, .flags = 1, .data = "a" });
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 4, .flags = 1, .data = "b" });
    for ([_]u32{ 3, 4 }) |code| {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(@as(u64, 0), msg.header.routing_id);
        const t, _ = try transactBody(msg.body);
        try testing.expectEqual(code, t.code);
    }

    // Releasing the server's only hold on the callback tells the client, with
    // the number of local references the broker has processed (one: the
    // transact entry above).
    try server.client.serveRelease(ObjectRef.remote(callback_gid), 1);
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.object_released, msg.header.tag);
        const released = try fixedStruct(Server.Message.ObjectReleased, msg.body);
        try testing.expectEqual(@as(u64, 42), released.object.value);
        try testing.expectEqual(@as(u64, 1), released.uses);
    }
    // Race: the client re-exports 42 before acknowledging. The broker revives
    // the same global object, the stale ack is ignored, and the object stays
    // callable.
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 7, .flags = 1, .entries = &entries, .data = "\x00\x00\x00\x00\x00\x00\x00\x00" });
    try client.client.serveObjectRefMessage(.release_ack, ObjectRef.local(42));
    {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        _, const payload = try transactBody(msg.body);
        try testing.expectEqual(callback_gid, payload.entry(0).value);
    }
    try server.client.serveTransact(.{ .target = ObjectRef.remote(callback_gid), .code = 8, .flags = 1 });
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
        const t, _ = try transactBody(msg.body);
        try testing.expectEqual(@as(u64, 42), t.target.value);
    }
    // Clean release: the hold is dropped and the owner acknowledges (the
    // broker reports both local references it processed).
    try server.client.serveRelease(ObjectRef.remote(callback_gid), 1);
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.object_released, msg.header.tag);
        try testing.expectEqual(@as(u64, 2), (try fixedStruct(Server.Message.ObjectReleased, msg.body)).uses);
    }
    try client.client.serveObjectRefMessage(.release_ack, ObjectRef.local(42));

    // Death: link, then the server disappears mid-call.
    try client.client.serveObjectRefMessage(.link_death, ObjectRef.remote(svc_gid));
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 5, .request_id = 30 });
    {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
    }
    server.close();
    var saw_dead = false;
    var saw_reply = false;
    for (0..2) |_| {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        switch (msg.header.tag) {
            .object_dead => {
                saw_dead = true;
                try testing.expectEqual(svc_gid, (try fixedStruct(ObjectRef, msg.body)).value);
            },
            .reply => {
                saw_reply = true;
                try testing.expectEqual(@as(u64, 30), msg.header.request_id);
                const r, _ = try replyBody(msg.body);
                try testing.expectEqual(Client.Message.Reply.Status.dead_object, r.status);
            },
            else => return error.UnexpectedMessage,
        }
    }
    try testing.expect(saw_dead and saw_reply);
    // Stale id: the broker answers dead_object instead of routing.
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 6, .request_id = 31 });
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        const r, _ = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.dead_object, r.status);
    }
    // The name is gone.
    try client.client.serveLookupService(32, "dev.artzig.test.svc", false);
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.ServiceResult.Status.not_found, (try serviceResult(msg.body)).status);
    }
}

test "descriptors cross the broker and unauthorized use closes only the offender" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);

    var a: Peer = undefined;
    try a.connect(io, h.path, cookie);
    defer a.close();
    try a.expectWelcome();
    var b: Peer = undefined;
    try b.connect(io, h.path, cookie);
    defer b.close();
    try b.expectWelcome();

    try a.client.serveRegisterService(1, ObjectRef.local(1), "dev.artzig.test.a");
    gpa.free((try a.next()).fds);
    try b.client.serveLookupService(1, "dev.artzig.test.a", false);
    const a_gid = blk: {
        const msg = try b.next();
        defer gpa.free(msg.fds);
        break :blk (try serviceResult(msg.body)).object.value;
    };

    // A pipe: b sends the write end to a, a writes, b reads.
    var pipe_fds: [2]std.posix.fd_t = undefined;
    try testing.expectEqual(@as(usize, 0), linux.pipe2(&pipe_fds, .{ .CLOEXEC = true }));
    const read_end: Io.File = .{ .handle = pipe_fds[0], .flags = .{ .nonblocking = false } };
    const write_end: Io.File = .{ .handle = pipe_fds[1], .flags = .{ .nonblocking = false } };
    defer read_end.close(io);
    try b.conn.writer.attachFds(&.{write_end});
    const fd_entries = [_]ObjectEntry{.{ .offset = 0, .kind = .file_descriptor, .value = 0 }};
    try b.client.serveTransact(.{ .target = ObjectRef.remote(a_gid), .code = 1, .flags = 1, .entries = &fd_entries, .data = "\x00\x00\x00\x00", .fd_count = 1 });
    write_end.close(io);
    {
        const msg = try a.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(@as(u16, 1), msg.header.fd_count);
        try testing.expectEqual(@as(usize, 1), msg.fds.len);
        try msg.fds[0].writeStreamingAll(io, "hello");
        msg.fds[0].close(io);
    }
    var buf: [8]u8 = undefined;
    const n = try read_end.readStreaming(io, &.{&buf});
    try testing.expectEqualSlices(u8, "hello", buf[0..n]);

    // b guesses an id it never received: b is disconnected, a is unaffected.
    var c: Peer = undefined;
    try c.connect(io, h.path, cookie);
    defer c.close();
    try c.expectWelcome();
    try c.client.serveTransact(.{ .target = ObjectRef.remote(a_gid + 1000), .code = 1, .request_id = 1 });
    {
        const msg = try c.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.protocol_error, msg.header.tag);
    }
    try testing.expectError(error.EndOfStream, c.client.receiveMessage());
    // Garbage header from a fresh peer closes that peer only.
    var d: Peer = undefined;
    try d.connect(io, h.path, cookie);
    defer d.close();
    try d.expectWelcome();
    try d.client.out.writeAll("this is not a frame header at all!!!");
    try d.client.out.flush();
    {
        const msg = try d.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.protocol_error, msg.header.tag);
    }
    // Unread garbage makes the kernel reset instead of closing cleanly.
    try testing.expect(if (d.client.receiveMessage()) |_| false else |err| err == error.EndOfStream or err == error.ReadFailed);
    // a still works.
    try b.client.serveTransact(.{ .target = ObjectRef.remote(a_gid), .code = 9, .request_id = 5 });
    {
        const msg = try a.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
        try a.client.serveReply(.{ .routing_id = msg.header.routing_id });
    }
    {
        const msg = try b.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.reply, msg.header.tag);
        try testing.expectEqual(@as(u64, 5), msg.header.request_id);
    }
}

test "per-package uids, system role, and registry policy" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);

    var a: Peer = undefined;
    try a.connectAs(io, h.path, cookie, "com.example.a");
    defer a.close();
    try a.expectWelcome();
    var a2: Peer = undefined;
    try a2.connectAs(io, h.path, cookie, "com.example.a");
    defer a2.close();
    try a2.expectWelcome();
    var b: Peer = undefined;
    try b.connectAs(io, h.path, cookie, "com.example.b");
    defer b.close();
    try b.expectWelcome();
    var sys: Peer = undefined;
    try sys.connectAs(io, h.path, system_cookie, "");
    defer sys.close();
    try sys.expectWelcome();

    try testing.expectEqual(@as(u32, 10001), a.welcome.uid);
    try testing.expectEqual(@as(u32, 10001), a2.welcome.uid);
    try testing.expectEqual(@as(u32, 10002), b.welcome.uid);
    try testing.expectEqual(@as(u32, 1000), sys.welcome.uid);
    try testing.expectEqual(Server.Message.Welcome.Role.system, sys.welcome.role);

    // Registry policy.
    const cases = [_]struct { peer: *Peer, name: []const u8, status: Server.Message.ServiceResult.Status }{
        .{ .peer = &a, .name = "activity", .status = .denied },
        .{ .peer = &a, .name = "com.example.b.svc", .status = .denied },
        .{ .peer = &a, .name = "com.example.abc", .status = .denied },
        .{ .peer = &a, .name = "com.example.a", .status = .ok },
        .{ .peer = &a, .name = "com.example.a.svc", .status = .ok },
        .{ .peer = &sys, .name = "activity", .status = .ok },
    };
    for (cases, 0..) |case, i| {
        try case.peer.client.serveRegisterService(@intCast(i + 1), ObjectRef.local(@intCast(i + 1)), case.name);
        const msg = try case.peer.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.service_result, msg.header.tag);
        try testing.expectEqual(case.status, (try serviceResult(msg.body)).status);
    }

    // The calling identity seen by a callee is the caller's own uid.
    try b.client.serveLookupService(50, "com.example.a.svc", false);
    const svc = blk: {
        const msg = try b.next();
        defer gpa.free(msg.fds);
        break :blk (try serviceResult(msg.body)).object.value;
    };
    try b.client.serveTransact(.{ .target = ObjectRef.remote(svc), .code = 1, .request_id = 51 });
    {
        const msg = try a.next();
        defer gpa.free(msg.fds);
        const t, _ = try transactBody(msg.body);
        try testing.expectEqual(@as(u32, 10002), t.caller_uid);
        try a.client.serveReply(.{ .routing_id = msg.header.routing_id });
    }
    gpa.free((try b.next()).fds);
    // An app peer must present a package name.
    var anon: Peer = undefined;
    try anon.connectAs(io, h.path, cookie, "");
    defer anon.close();
    {
        const msg = try anon.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.protocol_error, msg.header.tag);
    }
}
