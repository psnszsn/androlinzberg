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
    prop_path: []u8,
    group: Io.Group = .init,

    fn start(gpa: std.mem.Allocator, io: Io) !*Harness {
        return startWithLimits(gpa, io, .{});
    }

    fn startWithLimits(gpa: std.mem.Allocator, io: Io, limits: Broker.DeliveryBudget.Limits) !*Harness {
        var buf: [64]u8 = undefined;
        var nonce_bytes: [8]u8 = undefined;
        io.random(&nonce_bytes);
        const nonce = std.mem.readInt(u64, &nonce_bytes, .little);
        const path = try gpa.dupe(u8, try std.fmt.bufPrint(&buf, "/tmp/azk-binder-test-{x}.sock", .{nonce}));
        var prop_buf: [64]u8 = undefined;
        const prop_path = try gpa.dupe(u8, try std.fmt.bufPrint(&prop_buf, "/tmp/azk-binder-test-{x}.props", .{nonce}));
        const broker = try Broker.init(gpa, io, .{ .socket_path = path, .cookie = cookie, .system_cookie = system_cookie, .prop_area_path = prop_path, .delivery_limits = limits });
        const h = try gpa.create(Harness);
        h.* = .{ .broker = broker, .path = path, .prop_path = prop_path };
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
        Io.Dir.deleteFileAbsolute(io, h.prop_path) catch {};
        gpa.free(h.path);
        gpa.free(h.prop_path);
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

fn expectReleased(peer: *Peer, local_id: u64, uses: u64) !void {
    const msg = try peer.next();
    defer testing.allocator.free(msg.fds);
    try testing.expectEqual(Server.Message.Tag.object_released, msg.header.tag);
    const released = try fixedStruct(Server.Message.ObjectReleased, msg.body);
    try testing.expectEqual(local_id, released.object.value);
    try testing.expectEqual(uses, released.uses);
    try peer.client.serveObjectRefMessage(.release_ack, ObjectRef.local(local_id));
}

fn expectAdmission(peer: *Peer, id: u64, status: Client.Message.Reply.Status) !void {
    const msg = try peer.next();
    defer testing.allocator.free(msg.fds);
    try testing.expectEqual(Server.Message.Tag.reply, msg.header.tag);
    try testing.expectEqual(id, msg.header.request_id);
    const rep, const payload = try replyBody(msg.body);
    try testing.expectEqual(status, rep.status);
    try testing.expectEqual(@as(usize, 0), payload.data.len);
    try testing.expectEqual(@as(usize, 0), payload.entries.len);
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

    try expectReleased(&client, 5, 1);

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
    try client.client.serveDeliveryDone(nested_routing);
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
    try server.client.serveDeliveryDone(routing);
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(@as(u64, 10), msg.header.request_id);
        const r, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, r.status);
        try testing.expectEqual(ObjectEntry.Kind.binder_local, payload.entry(0).kind);
        try testing.expectEqual(@as(u64, 42), payload.entry(0).value);
    }

    // Oneway admission replies precede handler completion; deliveries stay ordered.
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 3, .flags = 1, .request_id = 13, .data = "a" });
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 4, .flags = 1, .request_id = 14, .data = "b" });
    for ([_]u64{ 13, 14 }) |id| try expectAdmission(&client, id, .ok);
    for ([_]u32{ 3, 4 }) |code| {
        const msg = try server.next();
        defer gpa.free(msg.fds);
        try testing.expect(msg.header.routing_id != 0);
        try server.client.serveDeliveryDone(msg.header.routing_id);
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
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 7, .flags = 1, .request_id = 17, .entries = &entries, .data = "\x00\x00\x00\x00\x00\x00\x00\x00" });
    try expectAdmission(&client, 17, .ok);
    try client.client.serveObjectRefMessage(.release_ack, ObjectRef.local(42));
    {
        const msg = try server.next();
        try server.client.serveDeliveryDone(msg.header.routing_id);
        defer gpa.free(msg.fds);
        _, const payload = try transactBody(msg.body);
        try testing.expectEqual(callback_gid, payload.entry(0).value);
    }
    try server.client.serveTransact(.{ .target = ObjectRef.remote(callback_gid), .code = 8, .flags = 1, .request_id = 18 });
    try expectAdmission(&server, 18, .ok);
    {
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
        const t, _ = try transactBody(msg.body);
        try testing.expectEqual(@as(u64, 42), t.target.value);
        try client.client.serveDeliveryDone(msg.header.routing_id);
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
    const abandoned = [_]ObjectEntry{.{ .offset = 0, .kind = .binder_local, .value = 99 }};
    try client.client.serveTransact(.{ .target = ObjectRef.remote(svc_gid), .code = 6, .request_id = 31, .entries = &abandoned, .data = "\x00\x00\x00\x00" });
    try expectReleased(&client, 99, 1);
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
    try b.client.serveTransact(.{ .target = ObjectRef.remote(a_gid), .code = 1, .flags = 1, .request_id = 2, .entries = &fd_entries, .data = "\x00\x00\x00\x00", .fd_count = 1 });
    write_end.close(io);
    try expectAdmission(&b, 2, .ok);
    {
        const msg = try a.next();
        try a.client.serveDeliveryDone(msg.header.routing_id);
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
        try a.client.serveDeliveryDone(msg.header.routing_id);
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
        if (case.status == .denied) try expectReleased(case.peer, @intCast(i + 1), 1);
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
        try a.client.serveDeliveryDone(msg.header.routing_id);
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

test "install reserves the same uid as process hello, with system-only allocation" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);
    var sys: Peer = undefined;
    try sys.connectAs(io, h.path, system_cookie, "");
    defer sys.close();
    try sys.expectWelcome();

    try expectPackageUid(&sys, 1, "com.example.cold", .ok, 10001);
    try expectPackageUid(&sys, 2, "com.example.cold", .ok, 10001);
    var app: Peer = undefined;
    try app.connectAs(io, h.path, cookie, "com.example.cold");
    defer app.close();
    try app.expectWelcome();
    try testing.expectEqual(@as(u32, 10001), app.welcome.uid);
    try expectPackageUid(&app, 3, "com.example.denied", .denied, 0);
    // A denied request consumes no uid and cannot query another package's uid.
    try expectPackageUid(&app, 4, "com.example.cold", .denied, 0);
    try expectPackageUid(&sys, 5, "com.example.next", .ok, 10002);
    app.close();
    try expectPackageUid(&sys, 6, "com.example.cold", .ok, 10001);
    try app.connectAs(io, h.path, cookie, "com.example.cold");
    try app.expectWelcome();
    try testing.expectEqual(@as(u32, 10001), app.welcome.uid);

    h.broker.mutex.lockUncancelable(io);
    h.broker.next_app_uid = 19999;
    h.broker.mutex.unlock(io);
    try expectPackageUid(&sys, 7, "com.example.last", .ok, 19999);
    try expectPackageUid(&sys, 8, "com.example.exhausted", .exhausted, 0);
    try expectPackageUid(&sys, 9, "com.example.cold", .ok, 10001);
    var exhausted: Peer = undefined;
    try exhausted.connectAs(io, h.path, cookie, "com.example.exhausted");
    defer exhausted.close();
    const msg = try exhausted.next();
    defer gpa.free(msg.fds);
    try testing.expectEqual(Server.Message.Tag.protocol_error, msg.header.tag);
}

fn expectPackageUid(peer: *Peer, request: u64, package: []const u8, status: Server.Message.PackageUidResult.Status, uid: u32) !void {
    try peer.client.serveReservePackageUid(request, package);
    const msg = try peer.next();
    defer testing.allocator.free(msg.fds);
    try testing.expectEqual(Server.Message.Tag.package_uid_result, msg.header.tag);
    try testing.expectEqual(request, msg.header.request_id);
    const result = try fixedStruct(Server.Message.PackageUidResult, msg.body);
    try testing.expectEqual(status, result.status);
    try testing.expectEqual(uid, result.uid);
}

test "malformed package uid reservations close the peer" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);
    const oversized: [Client.limits.max_name_len + 1]u8 = @splat('a');
    for ([_][]const u8{ "", "com.example\x00bad", &oversized }) |package| {
        var sys: Peer = undefined;
        try sys.connectAs(io, h.path, system_cookie, "");
        defer sys.close();
        try sys.expectWelcome();
        try sys.client.serveReservePackageUid(1, package);
        const msg = try sys.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.protocol_error, msg.header.tag);
    }
}

test "session-wide properties: policy, area contents, change push" {
    const io = testing.io;
    const gpa = testing.allocator;
    const prop_area = @import("prop_area.zig");
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);

    var app: Peer = undefined;
    try app.connectAs(io, h.path, cookie, "com.example.app");
    defer app.close();
    try app.expectWelcome();
    var sys: Peer = undefined;
    try sys.connectAs(io, h.path, system_cookie, "");
    defer sys.close();
    try sys.expectWelcome();

    var reader = try prop_area.Reader.open(io, h.prop_path);
    defer reader.deinit();
    var buf: [prop_area.long_value_max]u8 = undefined;
    // Broker-seeded defaults are visible before any set.
    try testing.expectEqualStrings("35", reader.get("ro.build.version.sdk", &buf).?);
    try testing.expectEqual(@as(usize, 301), reader.get("ro.build.version.known_codenames", &buf).?.len);

    const long_value: [prop_area.value_max]u8 = @splat('x');

    const cases = [_]struct { peer: *Peer, name: []const u8, value: []const u8, status: Server.Message.ServiceResult.Status }{
        .{ .peer = &sys, .name = "sys.session", .value = "up", .status = .ok },
        .{ .peer = &sys, .name = "ro.boot.once", .value = "1", .status = .ok },
        .{ .peer = &sys, .name = "ro.boot.long", .value = &long_value, .status = .ok },
        // ro.* is write-once even for the system.
        .{ .peer = &sys, .name = "ro.boot.once", .value = "2", .status = .denied },
        .{ .peer = &sys, .name = "ro.build.version.sdk", .value = "36", .status = .denied },
        .{ .peer = &app, .name = "debug.example", .value = "on", .status = .ok },
        .{ .peer = &app, .name = "log.tag.Example", .value = "V", .status = .ok },
        .{ .peer = &app, .name = "debug.too_long", .value = &long_value, .status = .denied },
        .{ .peer = &app, .name = "sys.session", .value = "spoof", .status = .denied },
        .{ .peer = &app, .name = "ro.app", .value = "x", .status = .denied },
    };
    for (cases, 0..) |case, i| {
        try case.peer.client.serveSetProperty(@intCast(i + 1), case.name, case.value);
        // Every successful set is also pushed to every peer; drain messages
        // until this request's service_result arrives.
        while (true) {
            const msg = try case.peer.next();
            defer gpa.free(msg.fds);
            if (msg.header.tag == .property_changed) {
                try case.peer.client.serveDeliveryDone(msg.header.routing_id);
                continue;
            }
            try testing.expectEqual(Server.Message.Tag.service_result, msg.header.tag);
            try testing.expectEqual(@as(u64, @intCast(i + 1)), msg.header.request_id);
            try testing.expectEqual(case.status, (try serviceResult(msg.body)).status);
            break;
        }
        if (case.status == .ok) {
            try testing.expectEqualStrings(case.value, reader.get(case.name, &buf).?);
        }
    }
    try testing.expectEqualStrings("1", reader.get("ro.boot.once", &buf).?);
    try testing.expectEqualStrings(&long_value, reader.get("ro.boot.long", &buf).?);
    try testing.expectEqualStrings("up", reader.get("sys.session", &buf).?);

    // After its own drains, sys has exactly the pushes for app's two
    // successful sets still queued, in order.
    for ([_][]const u8{ "debug.example", "log.tag.Example" }) |expected| {
        const msg = try sys.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.property_changed, msg.header.tag);
        try testing.expectEqualStrings(expected, msg.body);
        try sys.client.serveDeliveryDone(msg.header.routing_id);
    }
}

// --- IServiceManager (gid-1 context object) wire helpers -------------------

const sm_descriptor = "android.os.IServiceManager";
/// libbinder interface header marker B_PACK_CHARS('S','Y','S','T').
const sm_header: i32 = 0x53595354;

/// A Parcel builder mirroring the JVM/ndk writeInt32/writeString16 layout, for
/// constructing IServiceManager request payloads in tests.
const ParcelBuf = struct {
    bytes: std.ArrayList(u8) = .empty,
    gpa: std.mem.Allocator,

    fn i32v(p: *ParcelBuf, v: i32) !void {
        var b: [4]u8 = undefined;
        std.mem.writeInt(i32, &b, v, .little);
        try p.bytes.appendSlice(p.gpa, &b);
    }
    fn string16(p: *ParcelBuf, s: []const u8) !void {
        try p.i32v(@intCast(s.len)); // ASCII: one unit per byte
        for (s) |c| {
            var b: [2]u8 = undefined;
            std.mem.writeInt(u16, &b, c, .little);
            try p.bytes.appendSlice(p.gpa, &b);
        }
        try p.bytes.appendSlice(p.gpa, &[_]u8{ 0, 0 }); // NUL unit
        while (p.bytes.items.len % 4 != 0) try p.bytes.append(p.gpa, 0);
    }
    /// The AOSP interface token: strict-mode policy, work source, header, name.
    fn token(p: *ParcelBuf) !void {
        try p.i32v(0);
        try p.i32v(-1);
        try p.i32v(sm_header);
        try p.string16(sm_descriptor);
    }
    fn deinit(p: *ParcelBuf) void {
        p.bytes.deinit(p.gpa);
    }
};

/// Reads a writeString16 value as ASCII bytes at `pos`, returning the text and
/// the position after it (past the NUL unit and 4-byte padding).
fn readString16Ascii(gpa: std.mem.Allocator, data: []const u8, pos: usize) !struct { []u8, usize } {
    var p = pos;
    const count = std.mem.readInt(i32, data[p..][0..4], .little);
    p += 4;
    const n: usize = @intCast(count);
    const out = try gpa.alloc(u8, n);
    for (0..n) |i| {
        out[i] = @intCast(std.mem.readInt(u16, data[p..][0..2], .little));
        p += 2;
    }
    p += 2; // NUL unit
    p = std.mem.alignForward(usize, p, 4);
    return .{ out, p };
}

test "context object serves the IServiceManager AIDL over the broker" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.start(gpa, io);
    defer h.stop(gpa, io);

    var owner: Peer = undefined;
    try owner.connect(io, h.path, cookie);
    defer owner.close();
    try owner.expectWelcome();
    var client: Peer = undefined;
    try client.connect(io, h.path, cookie);
    defer client.close();
    try client.expectWelcome();

    const name = "dev.artzig.test.smsvc";
    const ctx = ObjectRef.remote(Client.context_object_id);

    // addService(name, IBinder, allowIsolated=false, dumpPriority=0). The owner
    // passes a process-local binder (local id 100) in the side table.
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.string16(name);
        const binder_off: u32 = @intCast(p.bytes.items.len);
        try p.i32v(1); // strong binder placeholder
        try p.i32v(0); // allowIsolated
        try p.i32v(0); // dumpPriority
        const entries = [_]ObjectEntry{.{ .offset = binder_off, .kind = .binder_local, .value = 100 }};
        try owner.client.serveTransact(.{ .target = ctx, .code = 5, .request_id = 1, .entries = &entries, .data = p.bytes.items });
        const msg = try owner.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.reply, msg.header.tag);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        // void reply: writeNoException only.
        try testing.expectEqual(@as(i32, 0), std.mem.readInt(i32, payload.data[0..4], .little));
        try testing.expectEqual(@as(usize, 0), payload.entries.len);
    }

    // getService(name) by the owner: returns the binder as local (owner sees it
    // as its own object 100).
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.string16(name);
        try owner.client.serveTransact(.{ .target = ctx, .code = 1, .request_id = 2, .data = p.bytes.items });
        const msg = try owner.next();
        defer gpa.free(msg.fds);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        try testing.expectEqual(@as(i32, 0), std.mem.readInt(i32, payload.data[0..4], .little)); // no exception
        try testing.expectEqual(@as(i32, 1), std.mem.readInt(i32, payload.data[4..8], .little)); // present
        try testing.expectEqual(@as(usize, 1), payload.entries.len);
        try testing.expectEqual(ObjectEntry.Kind.binder_local, payload.entry(0).kind);
        try testing.expectEqual(@as(u32, 4), payload.entry(0).offset);
        try testing.expectEqual(@as(u64, 100), payload.entry(0).value);
    }

    // getService(name) by a different peer: delivered as a remote proxy (gid).
    var svc_gid: u64 = 0;
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.string16(name);
        try client.client.serveTransact(.{ .target = ctx, .code = 1, .request_id = 3, .data = p.bytes.items });
        const msg = try client.next();
        defer gpa.free(msg.fds);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        try testing.expectEqual(@as(usize, 1), payload.entries.len);
        try testing.expectEqual(ObjectEntry.Kind.binder_remote, payload.entry(0).kind);
        svc_gid = payload.entry(0).value;
        try testing.expect(svc_gid >= Client.context_object_id + 1);
    }

    // checkService of an unknown name: null binder, no entry.
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.string16("dev.artzig.test.absent");
        try client.client.serveTransact(.{ .target = ctx, .code = 3, .request_id = 4, .data = p.bytes.items });
        const msg = try client.next();
        defer gpa.free(msg.fds);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        try testing.expectEqual(@as(i32, 0), std.mem.readInt(i32, payload.data[0..4], .little)); // no exception
        try testing.expectEqual(@as(i32, 0), std.mem.readInt(i32, payload.data[4..8], .little)); // null
        try testing.expectEqual(@as(usize, 0), payload.entries.len);
    }

    // listServices: the registry contains exactly our one name.
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.i32v(0); // dumpPriority
        try client.client.serveTransact(.{ .target = ctx, .code = 6, .request_id = 5, .data = p.bytes.items });
        const msg = try client.next();
        defer gpa.free(msg.fds);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        try testing.expectEqual(@as(i32, 0), std.mem.readInt(i32, payload.data[0..4], .little)); // no exception
        try testing.expectEqual(@as(i32, 1), std.mem.readInt(i32, payload.data[4..8], .little)); // one entry
        const listed, _ = try readString16Ascii(gpa, payload.data, 8);
        defer gpa.free(listed);
        try testing.expectEqualStrings(name, listed);
    }

    // A malformed interface token is rejected with a Parcel exception.
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.i32v(0); // strict mode
        try p.i32v(-1); // work source
        try p.i32v(sm_header);
        try p.string16("android.os.WrongInterface");
        try client.client.serveTransact(.{ .target = ctx, .code = 1, .request_id = 6, .data = p.bytes.items });
        const msg = try client.next();
        defer gpa.free(msg.fds);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        // Nonzero exception code in the reply header.
        try testing.expect(std.mem.readInt(i32, payload.data[0..4], .little) != 0);
    }

    // registerForNotifications(name, callback): the owner adding that name
    // later makes the broker call the callback's onRegistration (oneway, from
    // the service manager) with the service delivered to the callback's owner.
    const name2 = "dev.artzig.test.smsvc2";
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.string16(name2);
        const cb_off: u32 = @intCast(p.bytes.items.len);
        try p.i32v(1);
        const entries = [_]ObjectEntry{.{ .offset = cb_off, .kind = .binder_local, .value = 55 }};
        try client.client.serveTransact(.{ .target = ctx, .code = 7, .request_id = 8, .entries = &entries, .data = p.bytes.items });
        const msg = try client.next();
        defer gpa.free(msg.fds);
        const rep, const payload = try replyBody(msg.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        try testing.expectEqual(@as(i32, 0), std.mem.readInt(i32, payload.data[0..4], .little));
    }
    {
        var p = ParcelBuf{ .gpa = gpa };
        defer p.deinit();
        try p.token();
        try p.string16(name2);
        const binder_off: u32 = @intCast(p.bytes.items.len);
        try p.i32v(1);
        try p.i32v(0);
        try p.i32v(0);
        const entries = [_]ObjectEntry{.{ .offset = binder_off, .kind = .binder_local, .value = 101 }};
        try owner.client.serveTransact(.{ .target = ctx, .code = 5, .request_id = 9, .entries = &entries, .data = p.bytes.items });
        const ack = try owner.next();
        defer gpa.free(ack.fds);
        try testing.expectEqual(Server.Message.Tag.reply, ack.header.tag);
        // The callback owner receives onRegistration on its local object 55.
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.transact, msg.header.tag);
        const t, const payload = try transactBody(msg.body);
        try testing.expectEqual(ObjectRef.Kind.local, t.target.kind);
        try testing.expectEqual(@as(u64, 55), t.target.value);
        try testing.expectEqual(@as(u32, 1), t.code);
        try testing.expectEqual(@as(u32, 1), t.flags & 1);
        try testing.expectEqual(@as(u32, 1000), t.caller_uid);
        try testing.expectEqual(@as(usize, 1), payload.entries.len);
        try testing.expectEqual(ObjectEntry.Kind.binder_remote, payload.entry(0).kind);
        // token (3 ints + descriptor) then the name, then the binder placeholder.
        const desc, var pos = try readString16Ascii(gpa, payload.data, 12);
        defer gpa.free(desc);
        try testing.expectEqualStrings("android.os.IServiceCallback", desc);
        const got, pos = try readString16Ascii(gpa, payload.data, pos);
        defer gpa.free(got);
        try testing.expectEqualStrings(name2, got);
        try testing.expectEqual(pos, payload.entry(0).offset);
        try client.client.serveDeliveryDone(msg.header.routing_id);
    }

    // Cross-path: a native lookup_service now sees the AIDL-registered service.
    {
        try client.client.serveLookupService(7, name, false);
        const msg = try client.next();
        defer gpa.free(msg.fds);
        try testing.expectEqual(Server.Message.Tag.service_result, msg.header.tag);
        try testing.expectEqual(Server.Message.ServiceResult.Status.ok, (try serviceResult(msg.body)).status);
    }
}

test "invalid reply retains pending call until protocol disconnect fails caller" {
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
    try server.client.serveRegisterService(1, ObjectRef.local(1), "dev.artzig.test.invalidreply");
    gpa.free((try server.next()).fds);
    try client.client.serveLookupService(2, "dev.artzig.test.invalidreply", false);
    const msg = try client.next();
    defer gpa.free(msg.fds);
    const target = (try serviceResult(msg.body)).object;
    try client.client.serveTransact(.{ .target = target, .code = 1, .request_id = 3 });
    const request = try server.next();
    defer gpa.free(request.fds);
    // Structurally valid payload, but the callee does not hold this remote ID.
    const invalid = [_]ObjectEntry{.{ .offset = 0, .kind = .binder_remote, .value = 999999 }};
    try server.client.serveReply(.{ .routing_id = request.header.routing_id, .entries = &invalid, .data = "\x01\x00\x00\x00" });
    const rejected = try server.next();
    defer gpa.free(rejected.fds);
    try testing.expectEqual(Server.Message.Tag.protocol_error, rejected.header.tag);
    const reply = try client.next();
    defer gpa.free(reply.fds);
    try testing.expectEqual(Server.Message.Tag.reply, reply.header.tag);
    try testing.expectEqual(@as(u64, 3), reply.header.request_id);
    const result, _ = try replyBody(reply.body);
    try testing.expectEqual(Client.Message.Reply.Status.dead_object, result.status);
}

fn barrier(peer: *Peer) !void {
    try peer.client.serveMessageHeader(.{ .tag = .list_services, .bytes_len = 0, .request_id = 90000 });
    try peer.client.out.flush();
    const msg = try peer.next();
    defer testing.allocator.free(msg.fds);
    try testing.expectEqual(Server.Message.Tag.service_result, msg.header.tag);
    try testing.expectEqual(@as(u64, 90000), msg.header.request_id);
}

fn quotaService(owner: *Peer, caller: *Peer) !ObjectRef {
    try owner.client.serveRegisterService(1, ObjectRef.local(1), "dev.artzig.test.quota");
    testing.allocator.free((try owner.next()).fds);
    try caller.client.serveLookupService(1, "dev.artzig.test.quota", false);
    const msg = try caller.next();
    defer testing.allocator.free(msg.fds);
    return (try serviceResult(msg.body)).object;
}

test "recipient byte, count and descriptor admission preserves sync progress and rejected ownership" {
    const gpa = testing.allocator;
    const io = testing.io;
    const Usage = Broker.DeliveryBudget.Usage;
    for ([_]struct { limit: Usage, accepted: usize, fd_entries: usize }{
        .{ .limit = .{ .bytes = 65536, .fds = 64, .count = 4 }, .accepted = 4, .fd_entries = 0 },
        .{ .limit = .{ .bytes = 512, .fds = 64, .count = 16 }, .accepted = 2, .fd_entries = 0 },
        .{ .limit = .{ .bytes = 65536, .fds = 4, .count = 16 }, .accepted = 2, .fd_entries = 1 },
        // Repeated wire ordinals become separately owned descriptors at inflate.
        .{ .limit = .{ .bytes = 65536, .fds = 6, .count = 16 }, .accepted = 2, .fd_entries = 2 },
    }) |case| {
        const h = try Harness.startWithLimits(gpa, io, .{ .asynchronous = case.limit });
        defer h.stop(gpa, io);
        var owner: Peer = undefined;
        try owner.connect(io, h.path, cookie);
        defer owner.close();
        try owner.expectWelcome();
        var caller: Peer = undefined;
        try caller.connect(io, h.path, cookie);
        defer caller.close();
        try caller.expectWelcome();
        const target = try quotaService(&owner, &caller);
        var pipe_fds: [2]std.posix.fd_t = undefined;
        try testing.expectEqual(@as(usize, 0), linux.pipe2(&pipe_fds, .{ .CLOEXEC = true }));
        const read_end: Io.File = .{ .handle = pipe_fds[0], .flags = .{ .nonblocking = false } };
        const write_end: Io.File = .{ .handle = pipe_fds[1], .flags = .{ .nonblocking = false } };
        defer read_end.close(io);
        var write_closed = false;
        defer if (!write_closed) write_end.close(io);
        var entries: [3]ObjectEntry = undefined;
        for (entries[0..case.fd_entries], 0..) |*entry, i| entry.* = .{ .offset = @intCast(i * 4), .kind = .file_descriptor, .value = 0 };
        const bytes: [128]u8 = @splat(0);
        var routes: [4]u64 = undefined;
        for (0..case.accepted) |i| {
            if (case.fd_entries > 0) try caller.conn.writer.attachFds(&.{write_end});
            try caller.client.serveTransact(.{ .target = target, .code = @intCast(i), .request_id = i + 10, .flags = 1, .data = &bytes, .entries = entries[0..case.fd_entries], .fd_count = if (case.fd_entries > 0) 1 else 0 });
            try expectAdmission(&caller, i + 10, .ok);
            const msg = try owner.next();
            defer gpa.free(msg.fds);
            for (msg.fds) |fd| fd.close(io);
            routes[i] = msg.header.routing_id;
            const tr, _ = try transactBody(msg.body);
            try testing.expectEqual(@as(u32, @intCast(i)), tr.code);
            // Reading the frame is not completion: its admission credit stays held.
        }
        entries[case.fd_entries] = .{ .offset = @intCast(case.fd_entries * 4), .kind = .binder_local, .value = 99 };
        if (case.fd_entries > 0) try caller.conn.writer.attachFds(&.{write_end});
        try caller.client.serveTransact(.{ .target = target, .code = 99, .request_id = 99, .flags = 1, .data = &bytes, .entries = entries[0 .. case.fd_entries + 1], .fd_count = if (case.fd_entries > 0) 1 else 0 });
        try expectReleased(&caller, 99, 1);
        try expectAdmission(&caller, 99, .failed);
        write_end.close(io);
        write_closed = true;
        var eof: [1]u8 = undefined;
        try testing.expectError(error.EndOfStream, read_end.readStreaming(io, &.{&eof}));

        // A synchronous operation still fits while async is full.
        try caller.client.serveTransact(.{ .target = target, .code = 500, .request_id = 500 });
        const sync = try owner.next();
        defer gpa.free(sync.fds);
        const tr, _ = try transactBody(sync.body);
        try testing.expectEqual(@as(u32, 500), tr.code);
        try owner.client.serveReply(.{ .routing_id = sync.header.routing_id, .data = "control" });
        try owner.client.serveDeliveryDone(sync.header.routing_id);
        const reply = try caller.next();
        defer gpa.free(reply.fds);
        const rep, const payload = try replyBody(reply.body);
        try testing.expectEqual(Client.Message.Reply.Status.ok, rep.status);
        try testing.expectEqualStrings("control", payload.data);
        for (routes[0..case.accepted]) |id| try owner.client.serveDeliveryDone(id);
        try barrier(&owner);
        try caller.client.serveTransact(.{ .target = target, .code = 600, .request_id = 600, .flags = 1 });
        try expectAdmission(&caller, 600, .ok);
        const retry = try owner.next();
        defer gpa.free(retry.fds);
        try owner.client.serveDeliveryDone(retry.header.routing_id);
        try barrier(&owner);
        h.broker.mutex.lockUncancelable(io);
        const remaining = h.broker.deliveries;
        h.broker.mutex.unlock(io);
        try testing.expectEqualDeep(Broker.DeliveryBudget{}, remaining);
    }
}

test "forged completion and producer death cannot reset another recipient's quota" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.startWithLimits(gpa, io, .{ .asynchronous = .{ .bytes = 65536, .count = 1, .fds = 64 } });
    defer h.stop(gpa, io);
    var owner: Peer = undefined;
    try owner.connect(io, h.path, cookie);
    defer owner.close();
    try owner.expectWelcome();
    var caller: Peer = undefined;
    try caller.connect(io, h.path, cookie);
    defer caller.close();
    try caller.expectWelcome();
    const target = try quotaService(&owner, &caller);
    try caller.client.serveTransact(.{ .target = target, .code = 1, .request_id = 2, .flags = 1 });
    try expectAdmission(&caller, 2, .ok);
    const held = try owner.next();
    defer gpa.free(held.fds);
    const route = held.header.routing_id;
    try caller.client.serveDeliveryDone(route);
    const bad = try caller.next();
    defer gpa.free(bad.fds);
    try testing.expectEqual(Server.Message.Tag.protocol_error, bad.header.tag);
    try testing.expectError(error.EndOfStream, caller.client.receiveMessage());
    caller.close();
    var newcomer: Peer = undefined;
    try newcomer.connect(io, h.path, cookie);
    defer newcomer.close();
    try newcomer.expectWelcome();
    try newcomer.client.serveLookupService(1, "dev.artzig.test.quota", false);
    gpa.free((try newcomer.next()).fds);
    try newcomer.client.serveTransact(.{ .target = target, .code = 2, .request_id = 2, .flags = 1 });
    try expectAdmission(&newcomer, 2, .failed);
    try owner.client.serveDeliveryDone(route);
    try barrier(&owner);
    try newcomer.client.serveTransact(.{ .target = target, .code = 3, .request_id = 3, .flags = 1 });
    try expectAdmission(&newcomer, 3, .ok);
    const accepted = try owner.next();
    defer gpa.free(accepted.fds);
    // Double completion rejects the owner; the other admitted delivery retires
    // with its dead recipient, and a pending synchronous caller receives death.
    try newcomer.client.serveTransact(.{ .target = target, .code = 4, .request_id = 4 });
    gpa.free((try owner.next()).fds);
    try owner.client.serveDeliveryDone(route);
    const duplicate = try owner.next();
    defer gpa.free(duplicate.fds);
    try testing.expectEqual(Server.Message.Tag.protocol_error, duplicate.header.tag);
    try expectAdmission(&newcomer, 4, .dead_object);
    h.broker.mutex.lockUncancelable(io);
    const remaining = h.broker.deliveries;
    h.broker.mutex.unlock(io);
    try testing.expectEqualDeep(Broker.DeliveryBudget{}, remaining);
}

test "slow property callbacks retire only their recipient without losing healthy notifications" {
    const io = testing.io;
    const gpa = testing.allocator;
    const h = try Harness.startWithLimits(gpa, io, .{ .asynchronous = .{ .bytes = 65536, .count = 2, .fds = 64 } });
    defer h.stop(gpa, io);
    var slow: Peer = undefined;
    try slow.connect(io, h.path, cookie);
    defer slow.close();
    try slow.expectWelcome();
    var healthy: Peer = undefined;
    try healthy.connect(io, h.path, cookie);
    defer healthy.close();
    try healthy.expectWelcome();
    for ([_][]const u8{ "first", "second", "third", "fourth" }, 0..) |value, i| {
        try healthy.client.serveSetProperty(i + 1, "debug.quota", value);
        const ack = try healthy.next();
        defer gpa.free(ack.fds);
        try testing.expectEqual(Server.Message.Tag.service_result, ack.header.tag);
        try testing.expectEqual(Server.Message.ServiceResult.Status.ok, (try serviceResult(ack.body)).status);
        const notification = try healthy.next();
        defer gpa.free(notification.fds);
        try testing.expectEqual(Server.Message.Tag.property_changed, notification.header.tag);
        try testing.expectEqualStrings("debug.quota", notification.body);
        try healthy.client.serveDeliveryDone(notification.header.routing_id);
        try barrier(&healthy);
        if (i < 2) {
            // It reads the socket but never completes the notification callback.
            const held = try slow.next();
            defer gpa.free(held.fds);
            try testing.expectEqual(Server.Message.Tag.property_changed, held.header.tag);
        } else if (i == 2) {
            try testing.expectError(error.EndOfStream, slow.client.receiveMessage());
            slow.close();
        }
    }
}

test "a synchronous reply does not release input ownership before cleanup" {
    const io = testing.io;
    const gpa = testing.allocator;
    const allowance: Broker.DeliveryBudget.Usage = .{ .bytes = 65536, .fds = 64, .count = 1 };
    const h = try Harness.startWithLimits(gpa, io, .{ .total = allowance, .asynchronous = allowance });
    defer h.stop(gpa, io);
    var owner: Peer = undefined;
    try owner.connect(io, h.path, cookie);
    defer owner.close();
    try owner.expectWelcome();
    var caller: Peer = undefined;
    try caller.connect(io, h.path, cookie);
    defer caller.close();
    try caller.expectWelcome();
    const target = try quotaService(&owner, &caller);
    try caller.client.serveTransact(.{ .target = target, .code = 1, .request_id = 2 });
    const held = try owner.next();
    defer gpa.free(held.fds);
    const route = held.header.routing_id;
    try owner.client.serveReply(.{ .routing_id = route });
    try expectAdmission(&caller, 2, .ok);
    try caller.client.serveTransact(.{ .target = target, .code = 2, .request_id = 3, .flags = 1 });
    try expectAdmission(&caller, 3, .failed);
    try owner.client.serveDeliveryDone(route);
    try barrier(&owner);
    try caller.client.serveTransact(.{ .target = target, .code = 3, .request_id = 4, .flags = 1 });
    try expectAdmission(&caller, 4, .ok);
    const accepted = try owner.next();
    defer gpa.free(accepted.fds);
    try owner.client.serveDeliveryDone(accepted.header.routing_id);
    try barrier(&owner);
}
