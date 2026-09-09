const std = @import("std");
const Io = std.Io;
const art = @import("art.zig");
const Library = @import("system_certificates.zig").Library;

fn require(ok: bool) !void {
    if (!ok) return error.CertificateContractFailed;
}

fn write(io: Io, path: []const u8, bytes: []const u8) !void {
    var file = try Io.Dir.cwd().createFile(io, path, .{ .exclusive = true });
    defer file.close(io);
    try file.writeStreamingAll(io, bytes);
}

pub fn main(init: std.process.Init) !void {
    const a = init.arena.allocator();
    const io = init.io;
    const args = try init.minimal.args.toSlice(a);
    if (args.len != 2) return error.ExpectedRuntimeDirectory;
    const runtime = try Io.Dir.cwd().realPathFileAlloc(io, args[1], a);
    const bundle = try art.locateCaBundle(a, io, init.minimal.environ, null);
    const pem = try Io.Dir.cwd().readFileAlloc(io, bundle, a, .limited(64 * 1024 * 1024));
    const begin = std.mem.indexOf(u8, pem, "-----BEGIN CERTIFICATE-----") orelse return error.MissingCertificate;
    const end_marker = "-----END CERTIFICATE-----";
    const end = begin + (std.mem.indexOf(u8, pem[begin..], end_marker) orelse return error.MissingCertificate) + end_marker.len;
    const certificate = pem[begin..end];
    var nonce: [16]u8 = undefined;
    io.random(&nonce);
    const temporary = try std.fmt.allocPrint(a, "/tmp/artzig-certificate-test-{s}", .{std.fmt.bytesToHex(nonce, .lower)});
    try Io.Dir.cwd().createDir(io, temporary, .fromMode(0o700));
    defer Io.Dir.cwd().deleteTree(io, temporary) catch {};
    const crypto = try Library.open(a, runtime);
    defer crypto.close();
    const duplicate_bundle = try std.fs.path.join(a, &.{ temporary, "duplicates.pem" });
    try write(io, duplicate_bundle, try std.fmt.allocPrint(a, "Root label\n{s}\n# another label\n{s}\n", .{ certificate, certificate }));
    const output = try std.fs.path.join(a, &.{ temporary, "certificates" });
    try Io.Dir.cwd().createDir(io, output, .default_dir);
    try require(try crypto.install(a, io, duplicate_bundle, output) == 2);
    var directory = try Io.Dir.cwd().openDir(io, output, .{ .iterate = true });
    defer directory.close(io);
    var files = directory.iterate();
    var names: std.ArrayList([]const u8) = .empty;
    while (try files.next(io)) |entry| try names.append(a, try a.dupe(u8, entry.name));
    try require(names.items.len == 2);
    try require(names.items[0].len == 10 and names.items[1].len == 10);
    try require(std.mem.eql(u8, names.items[0][0..8], names.items[1][0..8]));
    try require(names.items[0][9] != names.items[1][9]);
    const first = try directory.readFileAlloc(io, names.items[0], a, .limited(1024 * 1024));
    const second = try directory.readFileAlloc(io, names.items[1], a, .limited(1024 * 1024));
    try require(std.mem.eql(u8, first, second));
    // Decode the PEM independently to prove that the files contain certificates,
    // rather than a hash, a bundle or a placeholder.
    var base64: std.ArrayList(u8) = .empty;
    var lines = std.mem.splitScalar(u8, certificate, '\n');
    while (lines.next()) |line| {
        if (std.mem.startsWith(u8, line, "-----")) continue;
        try base64.appendSlice(a, std.mem.trimEnd(u8, line, "\r"));
    }
    const decoder = std.base64.standard.Decoder;
    const der = try a.alloc(u8, try decoder.calcSizeForSlice(base64.items));
    try decoder.decode(der, base64.items);
    try require(std.mem.eql(u8, first, der));
    if (crypto.install(a, io, duplicate_bundle, output)) |_| return error.OverwroteCertificateDirectory else |err| try require(err == error.CertificateDirectoryNotEmpty);

    const cases = [_]struct { contents: []const u8, expected: anyerror }{
        .{ .contents = "# no roots\n", .expected = error.EmptyCertificateBundle },
        .{ .contents = "-----BEGIN CERTIFICATE-----\nbroken\n-----END CERTIFICATE-----\n", .expected = error.InvalidCertificate },
        .{ .contents = "-----BEGIN CERTIFICATE-----\nbroken\n", .expected = error.IncompleteCertificate },
        .{ .contents = "-----BEGIN CERTIFICATE-----\n-----BEGIN CERTIFICATE-----\n", .expected = error.NestedCertificate },
        .{ .contents = try std.fmt.allocPrint(a, "{s}\n-----BEGIN CERTIFICATE-----\n", .{certificate}), .expected = error.IncompleteCertificate },
    };
    for (cases, 0..) |case, index| {
        const file = try std.fmt.allocPrint(a, "{s}/invalid-{d}.pem", .{ temporary, index });
        const dir = try std.fmt.allocPrint(a, "{s}/invalid-{d}", .{ temporary, index });
        try write(io, file, case.contents);
        try Io.Dir.cwd().createDir(io, dir, .default_dir);
        if (crypto.install(a, io, file, dir)) |_| return error.AcceptedInvalidBundle else |err| try require(err == case.expected);
    }

    const failed_data = try std.fs.path.join(a, &.{ temporary, "failed-vm-data" });
    if (art.Runtime.create(init.gpa, io, init.minimal.environ, .{
        .root = runtime,
        .ca_bundle = try std.fs.path.join(a, &.{ temporary, "invalid-4.pem" }),
        .data_dir = failed_data,
    })) |created| {
        var unexpected = created;
        unexpected.destroy();
        return error.StartedVmWithIncompleteBundle;
    } else |err| try require(err == error.SystemCertificateInstallationFailed);
    var failed_dir = try Io.Dir.cwd().openDir(io, failed_data, .{ .iterate = true });
    defer failed_dir.close(io);
    var failed_entries = failed_dir.iterate();
    while (try failed_entries.next(io)) |entry|
        try require(!std.mem.startsWith(u8, entry.name, "system-"));

    // Default standalone ART startup must supply the stock trust store too,
    // without any framework bootstrap or repository-owned Java trust adapter.
    var vm = try art.Runtime.create(init.gpa, io, init.minimal.environ, .{
        .root = runtime,
        .ca_bundle = duplicate_bundle,
        .data_dir = try std.fs.path.join(a, &.{ temporary, "android-data" }),
        .check_jni = true,
    });
    const root = try a.dupe(u8, vm.system_root_overlay.?);
    {
        defer vm.destroy();
        const e = art.Env{ .env = vm.env };
        const env = vm.env;
        const cls = try e.findClass("java/security/KeyStore");
        const get = try e.getStaticMethodID(cls, "getInstance", "(Ljava/lang/String;)Ljava/security/KeyStore;");
        const store = env.*.*.CallStaticObjectMethodA.?(env, cls, get, &[_]art.jni.jvalue{.{ .l = try e.newString("AndroidCAStore") }});
        try e.check();
        try require(store != null);
        const load = try e.getMethodID(cls, "load", "(Ljava/io/InputStream;[C)V");
        env.*.*.CallVoidMethodA.?(env, store, load, &[_]art.jni.jvalue{ .{ .l = null }, .{ .l = null } });
        try e.check();
        const size = try e.getMethodID(cls, "size", "()I");
        const actual = env.*.*.CallIntMethodA.?(env, store, size, null);
        try e.check();
        try require(actual == 2);
    }
    if (Io.Dir.cwd().access(io, root, .{})) |_| return error.RetainedSystemRoot else |err| try require(err == error.FileNotFound);
    try Io.File.stdout().writeStreamingAll(io, "system-certificates: PASS DER, collisions, malformed bundles, stock standalone trust and cleanup\n");
}
