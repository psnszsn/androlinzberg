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
/// Given to the broker, and published in `session.json`, only for a session
/// started for inspection; a host test tool presents it as `AZK_BINDER_COOKIE`.
pub const inspector_cookie_env = "AZK_BINDER_INSPECTOR_COOKIE";
/// Path of the session's read-only property area file (see
/// `src/binder/prop_area.zig`); exported to every session process.
pub const prop_area_env = "AZK_PROP_AREA";

pub const session_file = "session.json";

/// Storage selected by the session owner, also used by independently joined
/// launchers. These are filesystem locations, never Binder role credentials.
pub const Storage = struct {
    application_data: []const u8,
    /// Backs the emulated volume; processes reach it as
    /// `StorageView.primary` inside the session's mount view.
    external_storage: []const u8,
    download_cache: []const u8,
    runtime_cache: []const u8,

    pub const Entry = struct { name: [:0]const u8, value: []const u8 };
    pub fn environment(self: Storage) [5]Entry {
        const view = @import("StorageView.zig");
        return .{
            .{ .name = "ARTZIG_DATA_DIR", .value = self.application_data },
            .{ .name = "EXTERNAL_STORAGE", .value = view.primary },
            .{ .name = "DOWNLOAD_CACHE", .value = self.download_cache },
            .{ .name = "ARTZIG_RUNTIME_CACHE_DIR", .value = self.runtime_cache },
            // Session children and joiners are inside the owner's view already;
            // app children that re-join by `--join` must not enter it again.
            .{ .name = view.env_marker, .value = "1" },
        };
    }
};

/// Contents of `session.json` (0600 inside the 0700 runtime directory).
pub const SessionInfo = struct {
    socket: []const u8,
    cookie: []const u8,
    /// Joins the session as the `inspector` role; absent outside inspection.
    inspector_cookie: ?[]const u8 = null,
    launcher: []const u8,
    pid: i32,
    prop_area: []const u8,
    storage: ?Storage = null,

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

/// Android's credential daemons, which init starts before the system server
/// (early_hal and core classes) with their state under /data/misc.
pub const CredentialDaemons = struct {
    keymint: []const u8,
    gatekeeper_hal: []const u8,
    gatekeeperd: []const u8,
    keystore2: []const u8,

    /// The daemons as the application tree installs them.
    pub fn installed(arena: std.mem.Allocator, tree: []const u8) !CredentialDaemons {
        const dir = try std.fs.path.join(arena, &.{ tree, "libexec", "androlinzberg" });
        return .{
            .keymint = try std.fs.path.join(arena, &.{ dir, "android.hardware.security.keymint-service.nonsecure" }),
            .gatekeeper_hal = try std.fs.path.join(arena, &.{ dir, "android.hardware.gatekeeper-service.nonsecure" }),
            .gatekeeperd = try std.fs.path.join(arena, &.{ dir, "gatekeeperd" }),
            .keystore2 = try std.fs.path.join(arena, &.{ dir, "keystore2" }),
        };
    }
};

/// The service manager declarations of the credential HALs' stock VINTF
/// fragments and keystore2's own.
pub const credential_declarations = [_][]const u8{
    "android.hardware.security.keymint.IKeyMintDevice/default",
    "android.hardware.security.keymint.IRemotelyProvisionedComponent/default",
    "android.hardware.security.secureclock.ISecureClock/default",
    "android.hardware.security.sharedsecret.ISharedSecret/default",
    "android.hardware.security.sharedsecret.ISharedSecret/gatekeeper",
    "android.hardware.gatekeeper.IGatekeeper/default",
    "android.system.keystore2.IKeystoreService/default",
};

pub const Session = struct {
    io: Io,
    gpa: std.mem.Allocator,
    child: std.process.Child,
    /// keymint, gatekeeper HAL, keystore2, gatekeeperd.
    credential: [4]?std.process.Child = @splat(null),
    surfaceflinger: ?std.process.Child = null,
    presenter: ?std.process.Child = null,
    system: ?std.process.Child = null,
    media: ?std.process.Child = null,
    ready_timeout: Io.Duration = .{ .nanoseconds = 30 * std.time.ns_per_s },
    shutdown_timeout: Io.Duration = .{ .nanoseconds = 5 * std.time.ns_per_s },
    system_ready: bool = false,
    /// Drains the system process's stdout after `ready` (app processes inherit
    /// the pipe as their stdout; an undrained pipe wedges the session).
    /// Stable address: media startup starts draining before start() returns
    /// the Session value to its owner. Active groups cannot move with it.
    drain: *Io.Group,
    dir: []u8,
    socket_path: []u8,
    prop_area_path: []u8,
    cookie: []u8,
    system_cookie: []u8,
    inspector_cookie: ?[]u8 = null,
    /// Exclusive ownership of the application data root's package database.
    data_lock: ?Io.File = null,

    pub const Options = struct {
        broker_exe: []const u8,
        /// Public launcher path recorded for clients and legacy fixtures.
        launcher_exe: []const u8,
        system_exe: ?[]const u8 = null,
        app_exe: ?[]const u8 = null,
        /// Alternative framework entry for system-process fixtures. Session
        /// identity, metadata and supervision remain identical to production.
        system_args: []const []const u8 = &.{"--system-server"},
        /// Omitted by transport-only fixtures; normal application sessions supply SF.
        surfaceflinger_exe: ?[]const u8 = null,
        task_presenter_exe: ?[]const u8 = null,
        media_service_exe: ?[]const u8 = null,
        /// Immutable native configuration used by clients inside service processes.
        platform_root: ?[]const u8 = null,
        ready_timeout: Io.Duration = .{ .nanoseconds = 30 * std.time.ns_per_s },
        shutdown_timeout: Io.Duration = .{ .nanoseconds = 5 * std.time.ns_per_s },
        environ: std.process.Environ,
        storage: ?Storage = null,
        /// `lockDataRoot` result for `storage`; the session owns it from start.
        data_lock: ?Io.File = null,
        /// PackageManagerService assigns installed packages' uids from the
        /// bottom of the application range; peers of packages it never
        /// installed take broker uids from the top, so the two never collide.
        first_app_uid: u32 = 19000,
        declared_services: []const []const u8 = &.{},
        /// Their databases live in the data partition: `storage`, else the
        /// environment's ARTZIG_DATA_DIR.
        credential: ?CredentialDaemons = null,
        /// Admit host test tools as the broker's `inspector` role.
        inspection: bool = false,
    };

    pub fn start(gpa: std.mem.Allocator, io: Io, options: Options) !Session {
        // startBroker transfers allocations and child ownership only on success.
        // Its errdefers have ended before stop takes over startup failure cleanup.
        var session = startBroker(gpa, io, options) catch |err| {
            if (options.data_lock) |file| file.close(io);
            return err;
        };
        session.data_lock = options.data_lock;
        errdefer session.stop();
        try session.writeInfo(options.launcher_exe, options.storage);
        if (options.surfaceflinger_exe) |exe| try session.startSurfaceFlinger(options, exe);
        if (options.task_presenter_exe) |exe| try session.startTaskPresenter(options, exe);
        if (options.credential) |daemons| try session.startCredentialDaemons(options, daemons);
        try session.startSystemProcess(options);
        if (options.media_service_exe) |exe| {
            // init runs mediaserver beside system_server: the system server's
            // StorageManagerService reaches media.player at its systemReady,
            // before the session's ready line. The media service waits for the
            // system services it needs, so neither process gates the other.
            try session.spawnMediaService(options, exe);
            try session.awaitSystemReady();
            try session.awaitMediaReady();
        }
        return session;
    }

    fn startBroker(gpa: std.mem.Allocator, io: Io, options: Options) !Session {
        const drain = try gpa.create(Io.Group);
        errdefer gpa.destroy(drain);
        drain.* = .init;
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
        io.random(&random);
        const inspector_cookie = if (options.inspection) try std.fmt.allocPrint(gpa, "{x}", .{&random}) else null;
        errdefer if (inspector_cookie) |value| gpa.free(value);
        var uid_buf: [16]u8 = undefined;
        const uid_text = try std.fmt.bufPrint(&uid_buf, "{d}", .{options.first_app_uid});

        // Cookies travel in the broker's environment, never on argv.
        var child_env = try options.environ.createMap(gpa);
        defer child_env.deinit();
        try child_env.put(cookie_env, cookie);
        try child_env.put(system_cookie_env, system_cookie);
        if (inspector_cookie) |value| try child_env.put(inspector_cookie_env, value);
        var broker_args: std.ArrayList([]const u8) = .empty;
        defer broker_args.deinit(gpa);
        try broker_args.appendSlice(gpa, &.{ options.broker_exe, "--socket", socket_path, "--prop-area", prop_area_path, "--session-dir", dir, "--first-app-uid", uid_text });
        for (options.declared_services) |name| try broker_args.appendSlice(gpa, &.{ "--declare-service", name });
        // The device manifest names the credential HALs where they are installed.
        if (options.credential != null) for (credential_declarations) |name| try broker_args.appendSlice(gpa, &.{ "--declare-service", name });
        var child = try spawnService(gpa, io, .{
            .argv = broker_args.items,
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
            .drain = drain,
            .dir = dir,
            .socket_path = socket_path,
            .prop_area_path = prop_area_path,
            .cookie = cookie,
            .system_cookie = system_cookie,
            .inspector_cookie = inspector_cookie,
            .ready_timeout = options.ready_timeout,
            .shutdown_timeout = options.shutdown_timeout,
        };
    }

    fn writeInfo(s: *Session, launcher_exe: []const u8, storage: ?Storage) !void {
        const io = s.io;
        const path = try std.fs.path.join(s.gpa, &.{ s.dir, session_file });
        defer s.gpa.free(path);
        const text = try std.json.Stringify.valueAlloc(s.gpa, SessionInfo{
            .socket = s.socket_path,
            .cookie = s.cookie,
            .inspector_cookie = s.inspector_cookie,
            .launcher = launcher_exe,
            .pid = @intCast(std.os.linux.getpid()),
            .prop_area = s.prop_area_path,
            .storage = storage,
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

    /// Starts the HALs, keystore2 and gatekeeperd from their stock mains, as
    /// init's services: they register their interfaces by name and clients
    /// wait for them, so no process gates another.
    fn startCredentialDaemons(s: *Session, options: Options, daemons: CredentialDaemons) !void {
        const data_root = if (options.storage) |storage| storage.application_data else options.environ.getPosix("ARTZIG_DATA_DIR") orelse
            return error.CredentialDaemonsNeedDataPartition;
        var env = try options.environ.createMap(s.gpa);
        defer env.deinit();
        try env.put(socket_env, s.socket_path);
        try env.put(cookie_env, s.cookie);
        try env.put(system_cookie_env, s.system_cookie);
        try env.put(prop_area_env, s.prop_area_path);
        if (options.platform_root) |root| try env.put("ARTZIG_PLATFORM_ROOT", root);
        try env.put("AZK_BINDER_EXIT_ON_DISCONNECT", "1");
        const keystore_dir = try std.fs.path.join(s.gpa, &.{ data_root, "misc", "keystore" });
        defer s.gpa.free(keystore_dir);
        const gatekeeper_dir = try std.fs.path.join(s.gpa, &.{ data_root, "misc", "gatekeeper" });
        defer s.gpa.free(gatekeeper_dir);
        for ([_][]const u8{ keystore_dir, gatekeeper_dir }) |dir| try Io.Dir.cwd().createDirPath(s.io, dir);
        const services = [_]struct { argv: []const []const u8, package: []const u8 }{
            .{ .argv = &.{daemons.keymint}, .package = "android:keymint" },
            .{ .argv = &.{daemons.gatekeeper_hal}, .package = "android:gatekeeper" },
            .{ .argv = &.{ daemons.keystore2, keystore_dir }, .package = "android:keystore2" },
            .{ .argv = &.{ daemons.gatekeeperd, gatekeeper_dir }, .package = "android:gatekeeperd" },
        };
        for (services, &s.credential) |service, *slot| {
            try env.put("AZK_BINDER_PACKAGE", service.package);
            slot.* = try spawnService(s.gpa, s.io, .{
                .argv = service.argv,
                .environ_map = &env,
                .stdin = .ignore,
                .stdout = .inherit,
                .stderr = .inherit,
            });
        }
    }

    /// Starts the native resource services after their system dependencies.
    fn spawnMediaService(s: *Session, options: Options, exe: []const u8) !void {
        var env = try options.environ.createMap(s.gpa);
        defer env.deinit();
        try env.put(socket_env, s.socket_path);
        try env.put(cookie_env, s.cookie);
        try env.put(system_cookie_env, s.system_cookie);
        try env.put(prop_area_env, s.prop_area_path);
        try env.put("AZK_BINDER_PACKAGE", "android:media");
        if (options.platform_root) |root| try env.put("ARTZIG_PLATFORM_ROOT", root);
        s.media = try spawnService(s.gpa, s.io, .{
            .argv = &.{exe},
            .environ_map = &env,
            .stdin = .pipe,
            .stdout = .pipe,
            .stderr = .inherit,
        });
    }

    /// Blocks until the media service printed `ready` (its services are
    /// registered), then keeps its stdout drained.
    fn awaitMediaReady(s: *Session) !void {
        const media = &(s.media orelse return error.NoMediaService);
        awaitReady(s.io, media.stdout.?, s.ready_timeout) catch |err| {
            return if (err == error.NotReady) error.MediaServiceNotReady else err;
        };
        try s.drain.concurrent(s.io, drainPipe, .{ s.io, media.stdout.? });
    }

    /// Spawns the configured system entry point with the trusted service cookie.
    fn startSystemProcess(s: *Session, options: Options) !void {
        const io = s.io;
        var child_env = try options.environ.createMap(s.gpa);
        defer child_env.deinit();
        try child_env.put(socket_env, s.socket_path);
        try child_env.put(cookie_env, s.cookie);
        try child_env.put(system_cookie_env, s.system_cookie);
        try child_env.put(prop_area_env, s.prop_area_path);
        try configureSystemData(s.gpa, io, &child_env, s.dir);
        if (options.storage) |storage| {
            for (storage.environment()) |entry| try child_env.put(entry.name, entry.value);
        }
        try child_env.put("AZK_LAUNCHER_EXE", options.launcher_exe);
        if (options.app_exe) |exe| try child_env.put("AZK_APP_PROCESS_EXE", exe);
        if (options.task_presenter_exe != null) try child_env.put("AZK_TASK_WINDOWS", "1");
        const argv = try s.gpa.alloc([]const u8, options.system_args.len + 1);
        defer s.gpa.free(argv);
        argv[0] = options.system_exe orelse options.launcher_exe;
        @memcpy(argv[1..], options.system_args);
        s.system = try spawnService(s.gpa, io, .{
            .argv = argv,
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
        if (s.media) |*media| stopChild(io, media, s.shutdown_timeout);
        s.media = null;
        if (s.system) |*system| stopChild(io, system, s.shutdown_timeout);
        s.system = null;
        // init stops services with SIGTERM; the stock daemons take its default.
        for (&s.credential) |*slot| if (slot.*) |*daemon| daemon.kill(io);
        s.credential = @splat(null);
        if (s.presenter) |*presenter| stopChild(io, presenter, s.shutdown_timeout);
        s.presenter = null;
        if (s.surfaceflinger) |*surfaceflinger| stopChild(io, surfaceflinger, s.shutdown_timeout);
        s.surfaceflinger = null;
        stopChild(io, &s.child, s.shutdown_timeout);
        Io.Dir.cwd().deleteTree(io, s.dir) catch |err| {
            std.log.err("session directory cleanup failed for {s}: {s}", .{ s.dir, @errorName(err) });
        };
        s.gpa.free(s.dir);
        s.gpa.free(s.socket_path);
        s.gpa.free(s.prop_area_path);
        s.gpa.free(s.cookie);
        s.gpa.free(s.system_cookie);
        if (s.inspector_cookie) |value| s.gpa.free(value);
        s.gpa.destroy(s.drain);
        if (s.data_lock) |file| file.close(io);
        s.data_lock = null;
    }
};

/// Android runs one system server per data partition, and its persistent
/// package database admits one owning session; other launchers join it.
pub fn lockDataRoot(io: Io, root: []const u8) !Io.File {
    var dir = try Io.Dir.cwd().openDir(io, root, .{});
    defer dir.close(io);
    const file = try dir.createFile(io, ".session.lock", .{ .truncate = false });
    errdefer file.close(io);
    if (!try file.tryLock(io, .exclusive)) {
        std.log.err("data root {s} belongs to another running session; join it with --join", .{root});
        return error.DataRootInUse;
    }
    return file;
}

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
    select.concurrent(.exited, waitOwnedChild, .{ child, io }) catch return;
    select.concurrent(.timeout, Io.sleep, .{ io, timeout, .awake }) catch return;
    _ = select.await() catch return;
}

fn waitOwnedChild(child: *std.process.Child, io: Io) std.process.Child.WaitError!std.process.Child.Term {
    const id = child.id;
    return child.wait(io) catch |err| {
        // The pinned std.Io.Threaded clears Child.id even when wait is canceled
        // before reaping. Retain ownership in that case so the timeout path can
        // terminate and reap the child. A successful wait must never restore it.
        if (err == error.Canceled) child.id = id;
        return err;
    };
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

/// Name the session to a system process. Its Android data partition is the
/// persistent application root (artzig data_partition), prepared by
/// Runtime.create before the original Environment class initializes.
pub fn configureSystemData(gpa: std.mem.Allocator, io: Io, child_env: *std.process.Environ.Map, session_dir: []const u8) !void {
    _ = gpa;
    _ = io;
    try child_env.put("AZK_BINDER_SESSION_DIR", session_dir);
}
