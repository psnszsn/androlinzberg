//! Private process roles; no public CLI dispatch or executable discovery fallback.
const std = @import("std");
const art = @import("art");
extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
const binder_session = @import("binder/session.zig");
const runtime_identity = @import("runtime_identity.zig");
const bootstrap = @import("runtime_bootstrap.zig");
const bootHeadless = bootstrap.bootHeadless;
const bootHeadlessApk = bootstrap.bootHeadlessApk;
const runFrameworkMain = bootstrap.runFrameworkMain;
const installTree = @import("install_tree.zig").root;
const configureDataRoot = @import("runtime_storage.zig").configureDataRoot;
const ensureSession = @import("session_connection.zig").ensureSession;
const taskPresentation = @import("session_connection.zig").taskPresentation;
extern "c" fn azk_process_isolate_app_data(root: [*:0]const u8) c_int;

pub const Role = enum { standalone, desktop, system, app, metadata, jvm_test };

pub fn main(comptime role: Role, init: std.process.Init) !void {
    const Runner = struct {
        fn run(context: std.process.Init) !void {
            return runRole(role, context);
        }
    };
    return bootstrap.runWithExit(init, Runner.run);
}

fn runRole(comptime role: Role, init: std.process.Init) !void {
    if (setenv("ARTZIG_PLATFORM_ROOT", try init.arena.allocator().dupeSentinel(u8, try installTree(init), 0), 1) != 0)
        return error.PlatformConfigurationEnvironmentFailed;
    if (init.minimal.environ.getPosix("AZK_LAUNCH_UID")) |raw_uid| {
        if (init.minimal.environ.getPosix("AZK_LAUNCH_CREDENTIAL") == null) return error.MissingLaunchCredential;
        try @import("binder/ProcessNamespace.zig").enter(init.io, try std.fmt.parseInt(u32, raw_uid, 10));
        if (init.minimal.environ.getPosix("AZK_RUNTIME_MASK_APP_DATA")) |mask| {
            if (!std.mem.eql(u8, mask, "1") or !std.mem.eql(u8, raw_uid, "1053")) return error.InvalidChildZygoteIdentity;
            const root = init.minimal.environ.getPosix("ARTZIG_DATA_DIR") orelse return error.MissingApplicationDataRoot;
            const status = azk_process_isolate_app_data(root.ptr);
            if (status != 0) {
                std.debug.print("child zygote app-data isolation failed: errno {d}\n", .{status});
                return error.ChildZygoteAppDataIsolationFailed;
            }
        }
        // The trusted supervisor binds its real child handle before releasing
        // this gate. No ART or application native constructor runs beforehand.
        var released: [1]u8 = undefined;
        const count = try std.Io.File.stdin().readStreaming(init.io, &.{&released});
        if (count != 1 or released[0] != 1) return error.LaunchAuthorizationLost;
    }

    // AndroidRuntime owns the writable exec argument block for setArgv0.
    // Retain independent strings for launcher dispatch and Java arguments.
    const raw_args = try init.minimal.args.toSlice(init.arena.allocator());
    const args = try init.arena.allocator().alloc([:0]const u8, raw_args.len);
    for (raw_args, args) |argument, *copy| copy.* = try init.arena.allocator().dupeSentinel(u8, argument, 0);
    // Session supervisor trampoline: establish child ownership before executing
    // a service. exec failure becomes EOF on the child's readiness pipe.
    if (args.len >= 3 and std.mem.eql(u8, args[1], "--service-exec"))
        return std.process.replace(init.io, .{ .argv = args[2..] });
    // Retain the explicit stock selection for child processes and reject obsolete
    // backends before any ART or system-service initialization.
    if (init.minimal.environ.getPosix("AZK_WINDOW_BACKEND") == null) {
        if (setenv("AZK_WINDOW_BACKEND", "stock", 1) != 0)
            return error.WindowBackendEnvironmentFailed;
    }
    if (init.minimal.environ.getPosix("AZK_WINDOW_BACKEND")) |mode| {
        if (!std.mem.eql(u8, mode, "stock")) return error.UnsupportedWindowBackend;
    }
    try configureDataRoot(init);

    var join_dir: ?[]const u8 = null;
    var i: usize = 1;
    while (i < args.len and std.mem.eql(u8, args[i], "--join")) {
        if (i + 1 >= args.len) return error.MissingSessionDirectory;
        join_dir = args[i + 1];
        i += 2;
    }
    var binder: ?binder_session.Session = null;
    defer if (binder) |*session| session.stop();
    switch (role) {
        .system => {
            if (i < args.len and std.mem.eql(u8, args[i], "--system-server")) i += 1;
            if (i != args.len) return error.InvalidSystemProcessArguments;

            // Spawned by session.zig inside a session; never starts one itself.
            var headless = try bootHeadless(init, .system);
            defer headless.rt.releaseProcessState();
            try runFrameworkMain(&headless.rt, "dev.artzig.system.SystemServer", &.{});
            return;
        },
        .metadata => {
            if (i < args.len and std.mem.eql(u8, args[i], "--inspect-apk-package")) i += 1;
            if (i + 1 != args.len) return error.InvalidApkPackageInspectionArguments;
            var headless = try bootHeadless(init, .metadata);
            defer headless.rt.releaseProcessState();
            try runtime_identity.printPackage(&headless.rt, init.io, args[i]);
        },
        .app => {
            if (i + 2 != args.len) return error.InvalidAppProcessArguments;
            if (std.mem.eql(u8, args[i], "--runtime-process")) {

                // A stock Process.start request, executed under a one-use broker
                // grant. RuntimeProcess calls original RuntimeInit.applicationInit.
                if (getenv("AZK_LAUNCH_CREDENTIAL") == null) return error.MissingRuntimeLaunchGrant;
                i += 1;
                const app_apk = args[i];
                if (!std.fs.path.isAbsolute(app_apk)) return error.RuntimeApkPathMustBeAbsolute;
                // Consume bounded setup before ART startup or any service calls. The
                // parent may hold the AMS lock while writing this pipe and awaiting PID.
                var setup_buffer: [4096]u8 = undefined;
                var setup_reader = std.Io.File.stdin().readerStreaming(init.io, &setup_buffer);
                const setup_size = try setup_reader.interface.takeInt(u32, .big);
                if (setup_size == 0 or setup_size > 524288) return error.InvalidRuntimeSetupSize;
                const setup = try init.arena.allocator().allocSentinel(u8, setup_size, 0);
                try setup_reader.interface.readSliceAll(setup);
                _ = setenv("ARTZIG_APK", try init.arena.allocator().dupeSentinel(u8, app_apk, 0), 1);
                try ensureSession(init, &binder, join_dir, try taskPresentation(init.minimal.environ, false));
                const process_role = try runtime_identity.testRole(init.minimal.environ);
                var headless = try bootHeadlessApk(init, app_apk, process_role);
                defer headless.rt.releaseProcessState();
                try runFrameworkMain(&headless.rt, "dev.artzig.RuntimeProcess", &.{setup});
                return;
            } else if (std.mem.eql(u8, args[i], "--app-process")) {

                // Headless app process hosting services for one APK; spawned by the
                // activity manager (or a test driver) inside an existing session.
                i += 1;
                const app_apk = args[i];
                const apk_abs = if (std.fs.path.isAbsolute(app_apk))
                    app_apk
                else
                    try std.fs.path.join(init.arena.allocator(), &.{ init.minimal.environ.getPosix("PWD") orelse ".", app_apk });
                _ = setenv("ARTZIG_APK", try init.arena.allocator().dupeSentinel(u8, apk_abs, 0), 1);
                try ensureSession(init, &binder, join_dir, try taskPresentation(init.minimal.environ, false));
                // Windowless, but with the bionic loader and the APK's native
                // libraries configured, so an auto-spawned service that loads an
                // NDK library works exactly as it does in the UI process.
                var headless = try bootHeadlessApk(init, apk_abs, .app);
                defer headless.rt.releaseProcessState();
                if (binder) |*session| try session.awaitSystemReady();
                try runFrameworkMain(&headless.rt, "dev.artzig.AppProcess", &.{});
                return;
            } else return error.InvalidAppProcessArguments;
        },
        .jvm_test => {
            // The legacy marker remains accepted by existing test drivers.
            if (i < args.len and std.mem.eql(u8, args[i], "--javatest")) i += 1;

            try ensureSession(init, &binder, join_dir, try taskPresentation(init.minimal.environ, false));
            const test_apk = init.minimal.environ.getPosix("AZK_TEST_APK");
            if (test_apk) |fixture_apk| {
                if (!std.fs.path.isAbsolute(fixture_apk)) return error.TestApkPathMustBeAbsolute;
                _ = setenv("ARTZIG_APK", try init.arena.allocator().dupeSentinel(u8, fixture_apk, 0), 1);
            }
            const process_role = try runtime_identity.testRole(init.minimal.environ);
            var headless = if (test_apk) |fixture_apk| try bootHeadlessApk(init, fixture_apk, process_role) else try bootHeadless(init, process_role);
            defer headless.rt.releaseProcessState();
            if (binder) |*session| try session.awaitSystemReady();
            const rt = &headless.rt;
            const cls_name = init.minimal.environ.getPosix("AZK_TEST_CLASS") orelse "dev.artzig.TestMain";
            try runFrameworkMain(rt, cls_name, args[i..]);
            const e2 = art.Env{ .env = rt.env };
            const cls2 = try e2.findClass("dev/artzig/GraphicsSmoke");
            const draw2 = try e2.getStaticMethodID(cls2, "draw", "(II)Landroid/graphics/Bitmap;");
            const jb = rt.env.*.*.CallStaticObjectMethod.?(rt.env, cls2, draw2, @as(art.jni.jint, 64), @as(art.jni.jint, 64));
            try e2.check();
            std.debug.print("draw() returned bitmap: {}\n", .{jb != null});
            return;
        },
        .desktop => return runDesktop(init, args[i..]),
        .standalone => return runStandalone(init, args, &binder, join_dir, i),
    }
}

fn runStandalone(init: std.process.Init, args: []const [:0]const u8, binder: *?binder_session.Session, joined: ?[]const u8, start: usize) !void {
    var join_dir = joined;
    var apk: ?[]const u8 = null;
    var i = start;
    while (i < args.len) : (i += 1) {
        if (std.mem.eql(u8, args[i], "--join")) {
            if (i + 1 >= args.len) return error.MissingSessionDirectory;
            i += 1;
            join_dir = args[i];
        } else if (std.mem.eql(u8, args[i], "--java") or std.mem.eql(u8, args[i], "--standalone")) {
            continue;
        } else if (std.mem.eql(u8, args[i], "--fullscreen") or std.mem.eql(u8, args[i], "--frames")) {
            return error.TaskWindowPresentationOptionsUnavailable;
        } else if (std.mem.startsWith(u8, args[i], "-")) {
            return error.UnknownOption;
        } else {
            if (apk != null) return error.MultipleApks;
            apk = args[i];
        }
    }
    const path_arg = apk orelse return error.MissingApk;
    const present_tasks = try taskPresentation(init.minimal.environ, true);
    try ensureSession(init, binder, join_dir, present_tasks);
    const arena = init.arena.allocator();
    const path = if (std.fs.path.isAbsolute(path_arg)) path_arg else try std.fs.path.join(arena, &.{ init.minimal.environ.getPosix("PWD") orelse ".", path_arg });
    _ = setenv("ARTZIG_APK", try arena.dupeSentinel(u8, path, 0), 1);
    if (binder.*) |*session| try session.awaitSystemReady();
    // The foreground owner contains no VM. A controller uses Android APIs to
    // launch the task and observe its lifetime, then reports completion here.
    var env = try init.minimal.environ.createMap(init.gpa);
    defer env.deinit();
    for ([_][]const u8{
        "ARTZIG_PLATFORM_ROOT", "ARTZIG_DATA_DIR",  "ARTZIG_RUNTIME_CACHE_DIR",
        "DOWNLOAD_CACHE",       "EXTERNAL_STORAGE", "AZK_BINDER_SOCKET",
        "AZK_BINDER_COOKIE",    "AZK_PROP_AREA",    "AZK_BINDER_SESSION_DIR",
        "AZK_WINDOW_BACKEND",
    }) |name| {
        const key = try arena.dupeSentinel(u8, name, 0);
        if (getenv(key)) |value| try env.put(name, std.mem.span(value));
    }
    if (binder.*) |session| try env.put(binder_session.system_cookie_env, session.system_cookie);
    const helper = try @import("install_tree.zig").executable(init, "azk-desktop-controller");
    const self = try std.process.executablePathAlloc(init.io, arena);
    // Preserve the session spawn workaround: own the child before helper exec.
    var child = try std.process.spawn(init.io, .{
        .argv = &.{ self, "--service-exec", helper, if (binder.* != null) "owner" else "joined", path },
        .environ_map = &env,
        .stdin = .inherit,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    defer child.kill(init.io);
    const result = try child.wait(init.io);
    switch (result) {
        .exited => |code| if (code != 0) return error.DesktopControllerFailed,
        else => return error.DesktopControllerFailed,
    }
}

fn runDesktop(init: std.process.Init, args: []const [:0]const u8) !void {
    if (args.len != 2) return error.InvalidDesktopControllerArguments;
    const owner = std.mem.eql(u8, args[0], "owner");
    if (!owner and !std.mem.eql(u8, args[0], "joined")) return error.InvalidDesktopControllerArguments;
    if (getenv("AZK_BINDER_SOCKET") == null) return error.MissingDesktopSession;
    const path = args[1];
    if (!std.fs.path.isAbsolute(path)) return error.DesktopApkPathMustBeAbsolute;
    _ = setenv("ARTZIG_APK", path, 1);
    var headless = try bootHeadlessApk(init, path, if (owner) .system else .app);
    defer headless.rt.releaseProcessState();
    try runFrameworkMain(&headless.rt, "dev.artzig.window.DesktopLauncher", &.{args[0]});
    const e = art.Env{ .env = headless.rt.env };
    const cls = try e.findClass("dev/artzig/window/DesktopLauncher");
    defer headless.rt.env.*.*.DeleteLocalRef.?(headless.rt.env, cls);
    const failed = try e.getStaticMethodID(cls, "sessionFailed", "()Z");
    const lost = headless.rt.env.*.*.CallStaticBooleanMethod.?(headless.rt.env, cls, failed) != 0;
    try e.check();
    if (lost) return error.DesktopSessionLost;
}
