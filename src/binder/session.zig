//! Launcher-side lifecycle of one hosted Binder session: a private runtime
//! directory, one `azk-binder-broker` child, one system-service process
//! (`androlinzberg --system-server`), readiness over each child's stdout pipe,
//! shutdown over its stdin pipe, and cleanup.
//!
//! Every process launched within the session inherits `AZK_BINDER_SOCKET`
//! and `AZK_BINDER_COOKIE`, so they all join the same object graph. Only the
//! system process additionally receives `AZK_BINDER_SYSTEM_COOKIE`. The
//! runtime directory holds `session.json` so `androlinzberg --join <dir>` can
//! add another app process to the session.
const std = @import("std");
const Io = std.Io;
const Client = @import("Client.zig");

pub const socket_env = "AZK_BINDER_SOCKET";
pub const cookie_env = "AZK_BINDER_COOKIE";
/// Given only to the broker and the session's system process.
pub const system_cookie_env = "AZK_BINDER_SYSTEM_COOKIE";
/// Path of the session's read-only property area file (see
/// `src/binder/prop_area.zig`); exported to every session process.
pub const prop_area_env = "AZK_PROP_AREA";

pub const session_file = "session.json";

/// Contents of `session.json` (0600 inside the 0700 runtime directory).
pub const SessionInfo = struct {
    socket: []const u8,
    cookie: []const u8,
    launcher: []const u8,
    pid: i32,
    prop_area: []const u8,
};

pub const Session = struct {
    io: Io,
    gpa: std.mem.Allocator,
    child: std.process.Child,
    system: ?std.process.Child = null,
    system_ready: bool = false,
    /// Drains the system process's stdout after `ready` (app processes inherit
    /// the pipe as their stdout; an undrained pipe wedges the session).
    drain: Io.Group = .init,
    dir: []u8,
    socket_path: []u8,
    prop_area_path: []u8,
    cookie: []u8,
    system_cookie: []u8,

    pub const Options = struct {
        broker_exe: []const u8,
        /// The launcher itself, re-executed as `--system-server`.
        launcher_exe: []const u8,
        environ: std.process.Environ,
        first_app_uid: u32 = Client.default_app_uid,
    };

    pub fn start(gpa: std.mem.Allocator, io: Io, options: Options) !Session {
        // The final component is random and created with an exclusive mkdir,
        // so another local user cannot pre-create it or plant a symlink.
        var random: [16]u8 = undefined;
        io.random(&random);
        const dir = try runtimeDir(gpa, io, options.environ, random[0..8]);
        errdefer gpa.free(dir);
        sweepDeadSessions(gpa, io, dir);
        try Io.Dir.createDirAbsolute(io, dir, @fromBackingInt(@intCast(0o700)));
        errdefer Io.Dir.cwd().deleteTree(io, dir) catch {};
        // umask may have widened the mode; make the directory private.
        var private_dir = try Io.Dir.cwd().openDir(io, dir, .{ .iterate = true });
        defer private_dir.close(io);
        try private_dir.setPermissions(io, @fromBackingInt(@intCast(0o700)));

        const socket_path = try std.fs.path.join(gpa, &.{ dir, "binder.sock" });
        errdefer gpa.free(socket_path);
        const prop_area_path = try std.fs.path.join(gpa, &.{ dir, "props" });
        errdefer gpa.free(prop_area_path);
        io.random(&random);
        const cookie = try std.fmt.allocPrint(gpa, "{x}", .{&random});
        errdefer gpa.free(cookie);
        io.random(&random);
        const system_cookie = try std.fmt.allocPrint(gpa, "{x}", .{&random});
        errdefer gpa.free(system_cookie);
        var uid_buf: [16]u8 = undefined;
        const uid_text = try std.fmt.bufPrint(&uid_buf, "{d}", .{options.first_app_uid});

        // Cookies travel in the broker's environment, never on argv.
        var child_env = try options.environ.createMap(gpa);
        defer child_env.deinit();
        try child_env.put(cookie_env, cookie);
        try child_env.put(system_cookie_env, system_cookie);
        var child = try std.process.spawn(io, .{
            .argv = &.{ options.broker_exe, "--socket", socket_path, "--prop-area", prop_area_path, "--session-dir", dir, "--first-app-uid", uid_text },
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
        var session: Session = .{
            .io = io,
            .gpa = gpa,
            .child = child,
            .dir = dir,
            .socket_path = socket_path,
            .prop_area_path = prop_area_path,
            .cookie = cookie,
            .system_cookie = system_cookie,
        };
        try session.writeInfo(options.launcher_exe);
        session.startSystemProcess(options) catch |err| {
            session.stop();
            return err;
        };
        return session;
    }

    fn writeInfo(s: *Session, launcher_exe: []const u8) !void {
        const io = s.io;
        const path = try std.fs.path.join(s.gpa, &.{ s.dir, session_file });
        defer s.gpa.free(path);
        const text = try std.json.Stringify.valueAlloc(s.gpa, SessionInfo{
            .socket = s.socket_path,
            .cookie = s.cookie,
            .launcher = launcher_exe,
            .pid = @intCast(std.os.linux.getpid()),
            .prop_area = s.prop_area_path,
        }, .{});
        defer s.gpa.free(text);
        const file = try Io.Dir.createFileAbsolute(io, path, .{ .permissions = @fromBackingInt(@intCast(0o600)) });
        defer file.close(io);
        try file.writeStreamingAll(io, text);
    }

    /// Spawns `androlinzberg --system-server` with the system cookie; only
    /// this child ever sees that cookie.
    fn startSystemProcess(s: *Session, options: Options) !void {
        const io = s.io;
        var child_env = try options.environ.createMap(s.gpa);
        defer child_env.deinit();
        try child_env.put(socket_env, s.socket_path);
        try child_env.put(cookie_env, s.cookie);
        try child_env.put(system_cookie_env, s.system_cookie);
        try child_env.put(prop_area_env, s.prop_area_path);
        // The system process's activity manager spawns app processes with these.
        try child_env.put("AZK_BINDER_SESSION_DIR", s.dir);
        try child_env.put("AZK_LAUNCHER_EXE", options.launcher_exe);
        s.system = try std.process.spawn(io, .{
            .argv = &.{ options.launcher_exe, "--system-server" },
            .environ_map = &child_env,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .inherit,
        });
    }

    /// Blocks until the system process printed `ready` (its services are
    /// registered). Idempotent.
    pub fn awaitSystemReady(s: *Session) !void {
        if (s.system_ready) return;
        const system = &(s.system orelse return error.NoSystemProcess);
        var line: [64]u8 = undefined;
        var got: usize = 0;
        while (got < line.len) {
            const n = try system.stdout.?.readStreaming(s.io, &.{line[got..]});
            if (n == 0) break;
            got += n;
            if (std.mem.indexOfScalar(u8, line[0..got], '\n') != null) break;
        }
        if (!std.mem.startsWith(u8, line[0..got], "ready")) return error.SystemProcessNotReady;
        s.system_ready = true;
        // Keep draining: the system process and every app process it spawns
        // write logs to this pipe; ~64KB of backlog would block them all.
        if (system.stdout) |stdout| {
            s.drain.concurrent(s.io, drainPipe, .{ s.io, stdout }) catch {};
        }
    }

    /// Closes the session pipes (system process first, then the broker; both
    /// exit on EOF), reaps the children, and removes the runtime directory.
    pub fn stop(s: *Session) void {
        const io = s.io;
        s.drain.cancel(io);
        if (s.system) |*system| {
            if (system.stdin) |stdin| stdin.close(io);
            system.stdin = null;
            if (system.stdout) |stdout| stdout.close(io);
            system.stdout = null;
            _ = system.wait(io) catch {};
            s.system = null;
        }
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
        s.gpa.free(s.prop_area_path);
        s.gpa.free(s.cookie);
        s.gpa.free(s.system_cookie);
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

/// Removes sibling session directories whose owning launcher is gone. A
/// launcher killed with SIGKILL (the integration harness does this) cannot
/// run its own cleanup, and the broker dies with the process group.
fn sweepDeadSessions(gpa: std.mem.Allocator, io: Io, new_dir: []const u8) void {
    const parent_path = std.fs.path.dirname(new_dir) orelse return;
    const new_name = std.fs.path.basename(new_dir);
    var parent = Io.Dir.cwd().openDir(io, parent_path, .{ .iterate = true }) catch return;
    defer parent.close(io);
    var it = parent.iterate();
    while (it.next(io) catch null) |entry| {
        if (entry.kind != .directory) continue;
        // Both naming schemes: `binder-<pid>-<nonce>` under XDG_RUNTIME_DIR
        // and `androlinzberg-<pid>-<nonce>` for the /tmp fallback.
        const rest = for ([_][]const u8{ "binder-", "androlinzberg-" }) |prefix| {
            if (std.mem.startsWith(u8, entry.name, prefix)) break entry.name[prefix.len..];
        } else continue;
        if (std.mem.eql(u8, entry.name, new_name)) continue;
        const dash = std.mem.indexOfScalar(u8, rest, '-') orelse continue;
        const pid = std.fmt.parseInt(i32, rest[0..dash], 10) catch continue;
        // Signal 0 probes existence; ESRCH means the launcher is gone.
        if (std.os.linux.kill(pid, @fromBackingInt(@intCast(0))) == 0) continue;
        const name = gpa.dupe(u8, entry.name) catch continue;
        defer gpa.free(name);
        parent.deleteTree(io, name) catch {};
    }
}

/// Forwards a child's stdout to the launcher's stderr until EOF (or cancel):
/// the session's system and app processes share this pipe for their logs.
fn drainPipe(io: Io, pipe: Io.File) void {
    var buf: [4096]u8 = undefined;
    while (true) {
        const n = pipe.readStreaming(io, &.{buf[0..]}) catch return;
        if (n == 0) return;
        std.debug.print("{s}", .{buf[0..n]});
    }
}
