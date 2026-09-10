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
    const expect_grant = std.mem.eql(u8, args[2], "grant");
    const header = client.receiveMessage() catch |err| {
        if (expect_grant) return err;
        return; // Broker closed the unauthenticated connection.
    };
    if (!expect_grant and header.tag == .protocol_error) return;
    if (!expect_grant or header.tag != .welcome) return error.UnexpectedWelcome;
    const welcome = try client.in.takeStruct(Server.Message.Welcome, .little);
    if (welcome.uid != uid or welcome.pid != std.os.linux.getpid() or welcome.role != .isolated or
        std.os.linux.getuid() != uid or std.os.linux.getgid() != uid) return error.WrongIdentity;
    try Io.File.stdout().writeStreamingAll(init.io, "authenticated\n");
    // Stay alive after authentication so the parent can verify UID quarantine.
    _ = input.interface.takeDelimiterInclusive('\n') catch {};
}
