//! Real-process adversarial peer for supervised launch protocol tests.
const std = @import("std");
const Io = std.Io;
const Client = @import("Client.zig");
const Server = @import("Server.zig");
const Connection = @import("Connection.zig");
const ProcessNamespace = @import("ProcessNamespace.zig");

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len != 5) return error.InvalidArguments;
    const uid = try std.fmt.parseInt(u32, args[4], 10);
    if (!std.mem.eql(u8, args[2], "inherited")) try ProcessNamespace.enter(init.io, uid);
    if (std.mem.eql(u8, args[2], "nested")) try ProcessNamespace.enter(init.io, uid);
    try Io.File.stdout().writeStreamingAll(init.io, "ready\n");
    var input_buffer: [512]u8 = undefined;
    var input = Io.File.stdin().readerStreaming(init.io, &input_buffer);
    const cookie = (try input.interface.takeDelimiter('\n')) orelse return error.MissingCredential;
    if (std.mem.eql(u8, args[2], "descendant")) {
        const exe = try std.process.executablePathAlloc(init.io, init.arena.allocator());
        var child = try std.process.spawn(init.io, .{
            .argv = &.{ exe, args[1], "inherited", args[3], args[4] },
            .stdin = .pipe,
            .stdout = .ignore,
            .stderr = .inherit,
        });
        defer child.kill(init.io);
        try child.stdin.?.writeStreamingAll(init.io, cookie);
        try child.stdin.?.writeStreamingAll(init.io, "\n");
        const term = try child.wait(init.io);
        if (term != .exited or term.exited != 0) return error.DescendantEscapedPolicy;
        return;
    }
    const address = try Io.net.UnixAddress.init(args[1]);
    const stream = try address.connect(init.io);
    defer stream.close(init.io);
    var read_buffer: [4096]u8 = undefined;
    var write_buffer: [4096]u8 = undefined;
    var connection = Connection.init(init.io, stream, &read_buffer, &write_buffer);
    const client: Client = .{ .in = &connection.reader.interface, .out = &connection.writer.interface };
    try client.serveHello(cookie, args[3]);
    const controller = std.mem.eql(u8, args[2], "controller");
    const system = std.mem.eql(u8, args[2], "system-grant");
    const watch = std.mem.eql(u8, args[2], "grant-watch");
    const expect_grant = std.mem.eql(u8, args[2], "grant") or controller or system or watch;
    const header = client.receiveMessage() catch |err| {
        if (expect_grant) return err;
        return; // Broker closed the unauthenticated connection.
    };
    if (!expect_grant and header.tag == .protocol_error) return;
    if (!expect_grant or header.tag != .welcome) return error.UnexpectedWelcome;
    const welcome = try client.in.takeStruct(Server.Message.Welcome, .little);
    if (welcome.uid != uid or welcome.pid != std.os.linux.getpid() or welcome.role != (if (system) Server.Message.Welcome.Role.system else if (controller) Server.Message.Welcome.Role.app else Server.Message.Welcome.Role.isolated) or
        std.os.linux.getuid() != uid or std.os.linux.getgid() != uid) return error.WrongIdentity;
    try Io.File.stdout().writeStreamingAll(init.io, "authenticated\n");
    if (controller) try supervise(init, args[1], client, &input.interface);
    if (watch) {
        try expectClosed(client);
        try Io.File.stdout().writeStreamingAll(init.io, "revoked\n");
    }
    // Stay alive after authentication so the parent can verify UID quarantine.
    _ = input.interface.takeDelimiterInclusive('\n') catch {};
}

fn reply(comptime T: type, client: Client, tag: Server.Message.Tag) !T {
    const header = try client.receiveMessage();
    if (header.tag != tag or header.fd_count != 0) return error.UnexpectedResponse;
    const bytes = try client.in.take(header.bytes_len);
    var reader: Io.Reader = .fixed(bytes);
    return reader.takeStruct(T, .little);
}

fn expectClosed(client: Client) !void {
    const header = client.receiveMessage() catch return;
    if (header.tag != .protocol_error) return error.RevokedPeerStillConnected;
}

fn supervise(init: std.process.Init, socket: []const u8, client: Client, input: *Io.Reader) !void {
    const package = "dev.artzig.renderer";
    // The effective renderer package differs from its provider. A controller
    // may use registered package identities, but cannot invent new ones.
    for ([_]u32{ 0, 2, 3 }) |kind| {
        try client.serveReserveLaunchForUid(10 + kind, kind, package, "renderer", 1, 0, null);
        const result = try reply(Server.Message.LaunchResult, client, .launch_result);
        if (result.status != .denied) return error.ControllerEscalated;
    }
    try client.serveReserveLaunchForUid(14, 1, package, "renderer", 1, 0, 90000);
    if ((try reply(Server.Message.LaunchResult, client, .launch_result)).status != .denied)
        return error.ControllerEscapedUidRange;
    try client.serveReserveLaunchForUid(15, 1, "dev.artzig.unknown", "renderer", 1, 0, 99009);
    if ((try reply(Server.Message.LaunchResult, client, .launch_result)).status == .ok)
        return error.ControllerCreatedPackage;
    try client.serveReserveLaunchForUid(16, 1, package, "renderer", 1, 0, 99009);
    const ticket = try reply(Server.Message.LaunchResult, client, .launch_result);
    if (ticket.status != .ok or ticket.uid != 99009) return error.RendererReservationFailed;
    const exe = try std.process.executablePathAlloc(init.io, init.arena.allocator());
    var child = try std.process.spawn(init.io, .{
        .argv = &.{ exe, socket, "grant-watch", package, "99009" },
        .stdin = .pipe,
        .stdout = .pipe,
        .stderr = .inherit,
    });
    defer child.kill(init.io);
    var buffer: [128]u8 = undefined;
    var output = child.stdout.?.readerStreaming(init.io, &buffer);
    if (!std.mem.eql(u8, "ready\n", try output.interface.takeDelimiterInclusive('\n')))
        return error.RendererNotReady;
    try client.serveBindLaunch(17, ticket.id, child.id.?);
    if ((try reply(Server.Message.ServiceResult, client, .service_result)).status != .ok)
        return error.RendererBindFailed;
    try child.stdin.?.writeStreamingAll(init.io, &ticket.token);
    try child.stdin.?.writeStreamingAll(init.io, "\n");
    if (!std.mem.eql(u8, "authenticated\n", try output.interface.takeDelimiterInclusive('\n')))
        return error.RendererNotAuthenticated;
    var pid_buffer: [32]u8 = undefined;
    try Io.File.stdout().writeStreamingAll(init.io, try std.fmt.bufPrint(&pid_buffer, "delegated {d}\n", .{child.id.?}));
    _ = try input.takeDelimiterInclusive('\n');
    try expectClosed(client);
    if (!std.mem.eql(u8, "revoked\n", try output.interface.takeDelimiterInclusive('\n')))
        return error.RendererNotRevoked;
    // Keep both kernel processes alive while the parent checks quarantine.
    try Io.File.stdout().writeStreamingAll(init.io, "revoked\n");
    _ = try input.takeDelimiterInclusive('\n');
}
