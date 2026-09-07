//! Launcher-side lifecycle of one hosted Binder session: a private runtime
//! directory, one `azk-binder-broker` child, a SurfaceFlinger process and a system-service process
//! (`androlinzberg --system-server`), readiness over each child's stdout pipe,
//! shutdown over its stdin pipe, and cleanup.
//!
//! Every process launched within the session inherits `AZK_BINDER_SOCKET`
//! and `AZK_BINDER_COOKIE`, so they all join the same object graph. Only the
//! compositor and system process additionally receive `AZK_BINDER_SYSTEM_COOKIE`. The
//! runtime directory holds `session.json` so `androlinzberg --join <dir>` can
//! add another app process to the session.
const std = @import("std");
const Io = std.Io;
const Client = @import("Client.zig");

pub const socket_env = "AZK_BINDER_SOCKET";
pub const cookie_env = "AZK_BINDER_COOKIE";
/// Given only to the broker, compositor and session's system process.
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

    /// Returned strings belong to the caller's arena, never a temporary JSON
    /// input buffer. Joined processes retain these paths through native startup.
    pub fn read(arena: std.mem.Allocator, io: Io, dir: []const u8) !SessionInfo {
        const path = try std.fs.path.join(arena, &.{ dir, session_file });
        const contents = try Io.Dir.cwd().readFileAlloc(io, path, arena, .limited(4096));
        return std.json.parseFromSliceLeaky(SessionInfo, arena, contents, .{
            .ignore_unknown_fields = true,
            .allocate = .alloc_always,
        });
    }
};

pub const Session = struct {
    io: Io,
    gpa: std.mem.Allocator,
    child: std.process.Child,
    surfaceflinger: ?std.process.Child = null,
    presenter: ?std.process.Child = null,
    system: ?std.process.Child = null,
    ready_timeout: Io.Duration = .{ .nanoseconds = 30 * std.time.ns_per_s },
    shutdown_timeout: Io.Duration = .{ .nanoseconds = 5 * std.time.ns_per_s },
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
        /// Omitted by transport-only fixtures; normal application sessions supply SF.
        surfaceflinger_exe: ?[]const u8 = null,
        task_presenter_exe: ?[]const u8 = null,
        ready_timeout: Io.Duration = .{ .nanoseconds = 30 * std.time.ns_per_s },
        shutdown_timeout: Io.Duration = .{ .nanoseconds = 5 * std.time.ns_per_s },
        environ: std.process.Environ,
        first_app_uid: u32 = Client.default_app_uid,
    };

    pub fn start(gpa: std.mem.Allocator, io: Io, options: Options) !Session {
        // startBroker transfers allocations and child ownership only on success.
        // Its errdefers have ended before stop takes over startup failure cleanup.
        var session = try startBroker(gpa, io, options);
        errdefer session.stop();
        try session.writeInfo(options.launcher_exe);
        if (options.surfaceflinger_exe) |exe| try session.startSurfaceFlinger(options, exe);
        if (options.task_presenter_exe) |exe| try session.startTaskPresenter(options, exe);
        try session.startSystemProcess(options);
        return session;
    }

    fn startBroker(gpa: std.mem.Allocator, io: Io, options: Options) !Session {
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
        var child = try spawnService(gpa, io, .{
            .argv = &.{ options.broker_exe, "--socket", socket_path, "--prop-area", prop_area_path, "--session-dir", dir, "--first-app-uid", uid_text },
            .environ_map = &child_env,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .inherit,
        });
        errdefer {
            // In this std.process API kill is idempotent and reaps the child.
            child.kill(io);
        }
        awaitReady(io, child.stdout.?, options.ready_timeout) catch |err| {
            return if (err == error.NotReady) error.BrokerNotReady else err;
        };
        return .{
            .io = io,
            .gpa = gpa,
            .child = child,
            .dir = dir,
            .socket_path = socket_path,
            .prop_area_path = prop_area_path,
            .cookie = cookie,
            .system_cookie = system_cookie,
            .ready_timeout = options.ready_timeout,
            .shutdown_timeout = options.shutdown_timeout,
        };
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

    fn startSurfaceFlinger(s: *Session, options: Options, exe: []const u8) !void {
        var env = try options.environ.createMap(s.gpa);
        defer env.deinit();
        try env.put(socket_env, s.socket_path);
        try env.put(cookie_env, s.cookie);
        try env.put(system_cookie_env, s.system_cookie);
        try env.put(prop_area_env, s.prop_area_path);
        s.surfaceflinger = try spawnService(s.gpa, s.io, .{
            .argv = &.{exe},
            .environ_map = &env,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .inherit,
        });
        const stdout = s.surfaceflinger.?.stdout.?;
        awaitReady(s.io, stdout, s.ready_timeout) catch |err| {
            return if (err == error.NotReady) error.SurfaceFlingerNotReady else err;
        };
    }

    fn startTaskPresenter(s: *Session, options: Options, exe: []const u8) !void {
        var env = try options.environ.createMap(s.gpa);
        defer env.deinit();
        try env.put(socket_env, s.socket_path);
        try env.put(cookie_env, s.cookie);
        try env.put(system_cookie_env, s.system_cookie);
        try env.put(prop_area_env, s.prop_area_path);
        try env.put("AZK_BINDER_PACKAGE", "android:wayland");
        s.presenter = try spawnService(s.gpa, s.io, .{
            .argv = &.{exe},
            .environ_map = &env,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .inherit,
        });
        awaitReady(s.io, s.presenter.?.stdout.?, s.ready_timeout) catch |err| {
            return if (err == error.NotReady) error.TaskPresenterNotReady else err;
        };
    }

    /// Spawns `androlinzberg --system-server` with the system cookie; only
    /// the compositor and system process receive that cookie.
    fn startSystemProcess(s: *Session, options: Options) !void {
        const io = s.io;
        var child_env = try options.environ.createMap(s.gpa);
        defer child_env.deinit();
        try child_env.put(socket_env, s.socket_path);
        try child_env.put(cookie_env, s.cookie);
        try child_env.put(system_cookie_env, s.system_cookie);
        try child_env.put(prop_area_env, s.prop_area_path);
        try configureSystemData(s.gpa, io, &child_env, s.dir);
        try child_env.put("AZK_LAUNCHER_EXE", options.launcher_exe);
        if (options.task_presenter_exe != null) try child_env.put("AZK_TASK_WINDOWS", "1");
        s.system = try spawnService(s.gpa, io, .{
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
        awaitReady(s.io, system.stdout.?, s.ready_timeout) catch |err| {
            return if (err == error.NotReady) error.SystemProcessNotReady else err;
        };
        // Keep draining: the system process and every app process it spawns
        // write logs to this pipe; ~64KB of backlog would block them all.
        if (system.stdout) |stdout| {
            try s.drain.concurrent(s.io, drainPipe, .{ s.io, stdout });
        }
        if (s.surfaceflinger) |sf| {
            if (sf.stdout) |stdout| try s.drain.concurrent(s.io, drainPipe, .{ s.io, stdout });
        }
        if (s.presenter) |presenter| {
            if (presenter.stdout) |stdout| try s.drain.concurrent(s.io, drainPipe, .{ s.io, stdout });
        }
        s.system_ready = true;
    }

    /// Stops system-server, SurfaceFlinger, then the broker. Services receive
    /// stdin EOF and a bounded grace period before forced cleanup.
    pub fn stop(s: *Session) void {
        const io = s.io;
        s.drain.cancel(io);
        if (s.system) |*system| stopChild(io, system, s.shutdown_timeout);
        s.system = null;
        if (s.presenter) |*presenter| stopChild(io, presenter, s.shutdown_timeout);
        s.presenter = null;
        if (s.surfaceflinger) |*surfaceflinger| stopChild(io, surfaceflinger, s.shutdown_timeout);
        s.surfaceflinger = null;
        stopChild(io, &s.child, s.shutdown_timeout);
        Io.Dir.cwd().deleteTree(io, s.dir) catch {};
        s.gpa.free(s.dir);
        s.gpa.free(s.socket_path);
        s.gpa.free(s.prop_area_path);
        s.gpa.free(s.cookie);
        s.gpa.free(s.system_cookie);
    }
};

// The pinned std.Io processSpawnPosix loses its pipe handles and child PID
// when the child reports an exec error. Spawn the running executable first,
// then replace it with the service. The parent has a Child before service exec
// can fail, so EOF/timeout cleanup owns every pipe and reaps the failed process.
// The launcher handles --service-exec before initializing Android or a session.
fn spawnService(gpa: std.mem.Allocator, io: Io, options: std.process.SpawnOptions) !std.process.Child {
    const argv = try gpa.alloc([]const u8, options.argv.len + 2);
    defer gpa.free(argv);
    argv[0] = "/proc/self/exe";
    argv[1] = "--service-exec";
    @memcpy(argv[2..], options.argv);
    var supervised = options;
    supervised.argv = argv;
    return std.process.spawn(io, supervised);
}

// Read exactly the protocol line; do not discard logs after the newline.
fn readReady(io: Io, stdout: Io.File) !void {
    for ("ready\n") |expected| {
        var byte: [1]u8 = undefined;
        const count = stdout.readStreaming(io, &.{&byte}) catch |err| switch (err) {
            error.EndOfStream => return error.NotReady,
            else => return err,
        };
        if (count != 1 or byte[0] != expected) return error.NotReady;
    }
}

fn awaitReady(io: Io, stdout: Io.File, timeout: Io.Duration) !void {
    const Result = union(enum) { ready: anyerror!void, timeout: Io.Cancelable!void };
    var results: [2]Result = undefined;
    var select = Io.Select(Result).init(io, &results);
    defer select.cancelDiscard();
    try select.concurrent(.ready, readReady, .{ io, stdout });
    try select.concurrent(.timeout, Io.sleep, .{ io, timeout, .awake });
    switch (try select.await()) {
        .ready => |result| try result,
        .timeout => |result| {
            try result;
            return error.ServiceReadyTimeout;
        },
    }
}

fn stopChild(io: Io, child: *std.process.Child, timeout: Io.Duration) void {
    if (child.stdin) |stdin| stdin.close(io);
    child.stdin = null;
    if (child.stdout) |stdout| stdout.close(io);
    child.stdout = null;
    const Result = union(enum) { exited: std.process.Child.WaitError!std.process.Child.Term, timeout: Io.Cancelable!void };
    var results: [2]Result = undefined;
    var select = Io.Select(Result).init(io, &results);
    // Cancel wait before kill touches the child. kill also reaps and closes pipes.
    defer child.kill(io);
    defer select.cancelDiscard();
    select.concurrent(.exited, std.process.Child.wait, .{ child, io }) catch return;
    select.concurrent(.timeout, Io.sleep, .{ io, timeout, .awake }) catch return;
    _ = select.await() catch return;
}

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

/// Give a system process session-scoped Android policy storage while preserving
/// application storage. Shared by the production launcher and multi-process harness.
pub fn configureSystemData(gpa: std.mem.Allocator, io: Io, child_env: *std.process.Environ.Map, session_dir: []const u8) !void {
    try child_env.put("AZK_BINDER_SESSION_DIR", session_dir);
    // Environment redirects only system/system_ce/system_de here. Application
    // paths and ART's separate ANDROID_DATA cache root retain their normal values.
    const data_dir = try std.fs.path.join(gpa, &.{ session_dir, "android-data" });
    defer gpa.free(data_dir);
    try Io.Dir.createDirAbsolute(io, data_dir, @fromBackingInt(@intCast(0o700)));
    try child_env.put("ARTZIG_SYSTEM_DATA_DIR", data_dir);
}
