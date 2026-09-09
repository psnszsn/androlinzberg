//! Materialize the host PEM trust bundle in Android's system CA directory.
//! Follows Conscrypt TrustedCertificateStore's X509_NAME_hash_old + collision
//! suffix layout. The pinned BoringSSL parses/encodes certificates; std.Io owns
//! every file. Stock Java trust, app anchors, user removal and pinning stay intact.
const std = @import("std");
const Io = std.Io;
const art = @import("art.zig");
const Opaque = opaque {};

pub const Library = struct {
    handle: *anyopaque,
    bio_new: *const fn (*const anyopaque, c_int) callconv(.c) ?*Opaque,
    bio_free: *const fn (?*Opaque) callconv(.c) c_int,
    read_certificate: *const fn (*Opaque, ?*?*Opaque, ?*anyopaque, ?*anyopaque) callconv(.c) ?*Opaque,
    free_certificate: *const fn (?*Opaque) callconv(.c) void,
    subject: *const fn (*const Opaque) callconv(.c) ?*Opaque,
    subject_hash: *const fn (*Opaque) callconv(.c) u32,
    encode: *const fn (*const Opaque, ?*[*]u8) callconv(.c) c_int,

    pub fn open(a: std.mem.Allocator, runtime: []const u8) !Library {
        const path = try std.fs.path.joinZ(a, &.{ runtime, "lib64", "libjavacrypto.so" });
        defer a.free(path);
        // Reuse the runtime's deep-bound loader: a desktop process may already
        // have ABI-incompatible host OpenSSL symbols in its global namespace.
        const loader = try art.HostLoader.init(runtime);
        const handle = loader.dlopen(path, 2, null) orelse return error.CertificateLibraryLoadFailed;
        errdefer _ = dlclose(handle);
        var result: Library = undefined;
        result.handle = handle;
        inline for (.{
            .{ "bio_new", "BIO_new_mem_buf" },            .{ "bio_free", "BIO_free" },
            .{ "read_certificate", "PEM_read_bio_X509" }, .{ "free_certificate", "X509_free" },
            .{ "subject", "X509_get_subject_name" },      .{ "subject_hash", "X509_NAME_hash_old" },
            .{ "encode", "i2d_X509" },
        }) |entry| {
            @field(result, entry[0]) = @ptrCast(loader.dlsym(handle, entry[1]) orelse return error.CertificateSymbolNotFound);
        }
        return result;
    }

    pub fn close(self: Library) void {
        _ = dlclose(self.handle);
    }

    /// The caller owns an empty private directory and removes it if installation
    /// fails. Nothing is exposed to Java until the complete bundle is installed.
    pub fn install(self: Library, a: std.mem.Allocator, io: Io, bundle_path: []const u8, directory: []const u8) !usize {
        const bundle = try Io.Dir.cwd().readFileAlloc(io, bundle_path, a, .limited(64 * 1024 * 1024));
        defer a.free(bundle);
        var dir = try Io.Dir.cwd().openDir(io, directory, .{ .iterate = true });
        defer dir.close(io);
        var entries = dir.iterate();
        if (try entries.next(io) != null) return error.CertificateDirectoryNotEmpty;
        var collisions: std.AutoHashMapUnmanaged(u32, usize) = .empty;
        defer collisions.deinit(a);
        var block: std.ArrayList(u8) = .empty;
        defer block.deinit(a);
        var active = false;
        var count: usize = 0;
        var lines = std.mem.splitScalar(u8, bundle, '\n');
        while (lines.next()) |raw| {
            const line = std.mem.trimEnd(u8, raw, "\r");
            if (std.mem.eql(u8, line, "-----BEGIN CERTIFICATE-----")) {
                if (active) return error.NestedCertificate;
                active = true;
                block.clearRetainingCapacity();
            }
            if (!active) continue; // Linux bundles can contain labels/comments.
            try block.appendSlice(a, line);
            try block.append(a, '\n');
            if (!std.mem.eql(u8, line, "-----END CERTIFICATE-----")) continue;
            const bio = self.bio_new(block.items.ptr, @intCast(block.items.len)) orelse return error.CertificateAllocationFailed;
            defer _ = self.bio_free(bio);
            const certificate = self.read_certificate(bio, null, null, null) orelse return error.InvalidCertificate;
            defer self.free_certificate(certificate);
            const name = self.subject(certificate) orelse return error.InvalidCertificateSubject;
            const hash = self.subject_hash(name);
            const size = self.encode(certificate, null);
            if (size <= 0) return error.CertificateEncodingFailed;
            const encoded = try a.alloc(u8, @intCast(size));
            defer a.free(encoded);
            var output = encoded.ptr;
            if (self.encode(certificate, &output) != size) return error.CertificateEncodingFailed;
            const entry = try collisions.getOrPut(a, hash);
            if (!entry.found_existing) entry.value_ptr.* = 0;
            const filename = try std.fmt.allocPrint(a, "{x:0>8}.{d}", .{ hash, entry.value_ptr.* });
            defer a.free(filename);
            var file = try dir.createFile(io, filename, .{ .exclusive = true, .permissions = .fromMode(0o644) });
            defer file.close(io);
            try file.writeStreamingAll(io, encoded);
            entry.value_ptr.* += 1;
            count += 1;
            active = false;
        }
        if (active) return error.IncompleteCertificate;
        if (count == 0) return error.EmptyCertificateBundle;
        return count;
    }

    extern "c" fn dlclose(handle: *anyopaque) c_int;
};
