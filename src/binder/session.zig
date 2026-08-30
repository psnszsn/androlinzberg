//! Launcher-side lifecycle of one hosted Binder session: a private runtime
//! directory, one `azk-binder-broker` child, readiness over the child's
//! stdout pipe, shutdown over its stdin pipe, and cleanup.
//!
//! Every process launched within the session inherits `AZK_BINDER_SOCKET`
//! and `AZK_BINDER_COOKIE`, so they all join the same object graph.
const std = @import("std");
const Io = std.Io;
const Client = @import("Client.zig");

pub const socket_env = "AZK_BINDER_SOCKET";
pub const cookie_env = "AZK_BINDER_COOKIE";

pub const Session = struct {
    io: Io,
    gpa: std.mem.Allocator,
    child: std.process.Child,
    dir: []u8,
    socket_path: []u8,
    cookie: []u8,

    pub const Options = struct {
        broker_exe: []const u8,
        environ: std.process.Environ,
        app_uid: u32 = Client.default_app_uid,
    };

    pub fn start(gpa: std.mem.Allocator, io: Io, options: Options) !Session {
        // The final component is random and created with an exclusive mkdir,
        // so another local user cannot pre-create it or plant a symlink.
        var random: [16]u8 = undefined;
        io.random(&random);
        const dir = try runtimeDir(gpa, io, options.environ, random[0..8]);
        errdefer gpa.free(dir);
        try Io.Dir.createDirAbsolute(io, dir, @fromBackingInt(@intCast(0o700)));
        errdefer Io.Dir.cwd().deleteTree(io, dir) catch {};
        // umask may have widened the mode; make the directory private.
        var private_dir = try Io.Dir.cwd().openDir(io, dir, .{ .iterate = true });
        defer private_dir.close(io);
        try private_dir.setPermissions(io, @fromBackingInt(@intCast(0o700)));

        const socket_path = try std.fs.path.join(gpa, &.{ dir, "binder.sock" });
        errdefer gpa.free(socket_path);
        io.random(&random);
        const cookie = try std.fmt.allocPrint(gpa, "{x}", .{&random});
        errdefer gpa.free(cookie);
        var uid_buf: [16]u8 = undefined;
        const uid_text = try std.fmt.bufPrint(&uid_buf, "{d}", .{options.app_uid});

        // The cookie travels in the broker's environment, never on argv.
        var child_env = try options.environ.createMap(gpa);
        defer child_env.deinit();
        try child_env.put(cookie_env, cookie);
        var child = try std.process.spawn(io, .{
            .argv = &.{ options.broker_exe, "--socket", socket_path, "--app-uid", uid_text },
            .environ_map = &child_env,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .inherit,
        });
        errdefer {
            child.kill(io);
            _ = child.wait(io) catch {};
        }
        // Readiness: the broker prints one line once it listens.
        var line: [64]u8 = undefined;
        var got: usize = 0;
        while (got < line.len) {
            const n = try child.stdout.?.readStreaming(io, &.{line[got..]});
            if (n == 0) break;
            got += n;
            if (std.mem.indexOfScalar(u8, line[0..got], '\n') != null) break;
        }
        if (!std.mem.startsWith(u8, line[0..got], "ready")) return error.BrokerNotReady;
        return .{
            .io = io,
            .gpa = gpa,
            .child = child,
            .dir = dir,
            .socket_path = socket_path,
            .cookie = cookie,
        };
    }

    /// Closes the session pipe (the broker exits on EOF), reaps the child, and
    /// removes the runtime directory.
    pub fn stop(s: *Session) void {
        const io = s.io;
        if (s.child.stdin) |stdin| {
            stdin.close(io);
            s.child.stdin = null;
        }
        if (s.child.stdout) |stdout| {
            stdout.close(io);
            s.child.stdout = null;
        }
        _ = s.child.wait(io) catch {};
        Io.Dir.cwd().deleteTree(io, s.dir) catch {};
        s.gpa.free(s.dir);
        s.gpa.free(s.socket_path);
        s.gpa.free(s.cookie);
    }
};

/// `$XDG_RUNTIME_DIR/androlinzberg/binder-<pid>-<random>` when the private
/// per-user runtime directory exists, else `/tmp/androlinzberg-<pid>-<random>`.
/// Only the random-suffixed leaf is created by the caller; no shared parent is
/// created at a predictable name.
fn runtimeDir(gpa: std.mem.Allocator, io: Io, environ: std.process.Environ, nonce: []const u8) ![]u8 {
    const pid = std.os.linux.getpid();
    if (environ.getPosix("XDG_RUNTIME_DIR")) |base| {
        const parent = try std.fs.path.join(gpa, &.{ base, "androlinzberg" });
        defer gpa.free(parent);
        try Io.Dir.createDirPath(.cwd(), io, parent);
        return std.fmt.allocPrint(gpa, "{s}/binder-{d}-{x}", .{ parent, pid, nonce });
    }
    return std.fmt.allocPrint(gpa, "/tmp/androlinzberg-{d}-{x}", .{ pid, nonce });
}
