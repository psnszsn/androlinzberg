//! androlinzberg launcher: boot ART, register the Android platform natives,
//! and present Android framework rendering on Wayland.
const std = @import("std");
const binder_session = @import("binder/session.zig");
const fonts = @import("platform/fonts.zig");
const test_control = @import("test_control.zig");
const host_natives = @import("host_natives");
const apk_optimizer = @import("apk_optimizer");
extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
extern "c" fn dlopen(path: [*:0]const u8, flags: c_int) ?*anyopaque;
const Timespec = extern struct { sec: i64, nsec: i64 };
extern "c" fn clock_gettime(clk: c_int, ts: *Timespec) c_int;
fn monotonicMs() i64 {
    var ts: Timespec = undefined;
    _ = clock_gettime(1, &ts); // CLOCK_MONOTONIC
    return ts.sec * 1000 + @divTrunc(ts.nsec, 1_000_000);
}
const art = @import("art");
// force the azk_codec C-ABI exports (ffmpeg MediaCodec backend) into the binary
comptime {
    _ = @import("azk_codec");
}

const usage =
    \\androlinzberg — run an Android APK on artzig (ART + Skia + Wayland)
    \\
    \\usage: androlinzberg [options] <app.apk>
    \\  --join <session-dir>   add this app to a running session (see session.json)
    \\Every launch runs inside a hosted Binder session (broker + system process);
    \\`--system-server` is the launcher's own headless system-process entry.
    \\       androlinzberg --codec-selftest | --codec-make-mp3 | --codec-make-m4v | --javatest
    \\
    \\Paths are resolved relative to the executable's install tree (zig-out/):
    \\  lib/libandroid_platform.so, framework/framework.jar, res/framework-res.apk.
    \\The ART runtime is installed beside the executable at bin/art and is
    \\rebuilt by Zig when its inputs change. ARTZIG_ROOT overrides it.
    \\
    \\environment: ARTZIG_ROOT, ARTZIG_CA_BUNDLE,
    \\  ARTZIG_FRAMEWORK_RES,
    \\  ARTZIG_FONT_FALLBACK_XML (skip fontconfig font discovery),
    \\  ARTZIG_VAAPI_DEVICE=/dev/dri/renderDNNN,
    \\  AZK_TEST_SOCKET=/path/to/control.sock (private Varlink test service),
    \\  F11 toggles xdg-shell fullscreen mode.
    \\
;

/// The install tree the executable lives in (zig-out): <tree>/bin/androlinzberg.
fn installTree(init: std.process.Init) ![]const u8 {
    const a = init.arena.allocator();
    if (init.minimal.environ.getPosix("ARTZIG_INSTALL_ROOT")) |configured| {
        if (!std.fs.path.isAbsolute(configured)) return error.NoInstallTree;
        return configured;
    }
    const exe = try std.process.executablePathAlloc(init.io, a);
    const bin = std.fs.path.dirname(exe) orelse return error.NoInstallTree;
    const discovered_tree = std.fs.path.dirname(bin) orelse return error.NoInstallTree;
    // ART persists class-loader-context locations verbatim in OAT headers.
    // Normalize the install tree so launching as ./zig-out/bin/... and by an
    // absolute path validate against the same framework location.
    const tree = if (std.fs.path.isAbsolute(discovered_tree))
        discovered_tree
    else
        try std.fs.path.join(a, &.{ init.minimal.environ.getPosix("PWD") orelse ".", discovered_tree });
    const probe = try std.fs.path.join(a, &.{ tree, "lib", "libandroid_platform.so" });
    if (std.Io.Dir.accessAbsolute(init.io, probe, .{})) |_| return tree else |_| {}
    // not an install tree (e.g. run from the zig cache): fall back to ./zig-out
    const cwd = init.minimal.environ.getPosix("PWD") orelse ".";
    return std.fs.path.join(a, &.{ cwd, "zig-out" });
}

/// ART runtime root discovery: ARTZIG_ROOT, otherwise the runtime installed
/// beside the executable by the app build.
fn locateRuntimeRoot(init: std.process.Init, tree: []const u8) !?[]const u8 {
    if (init.minimal.environ.getPosix("ARTZIG_ROOT") != null) return null;
    const a = init.arena.allocator();
    const runtime = try std.fs.path.join(a, &.{ tree, "bin", "art" });
    for ([_][]const u8{
        "runtime.env",
        "lib64/libart.so",
        "apex/com.android.art/javalib/x86_64/boot.art",
    }) |probe| {
        try std.Io.Dir.accessAbsolute(init.io, try std.fs.path.join(a, &.{ runtime, probe }), .{});
    }
    return runtime;
}

/// Prepare a real filesystem directory for an APK's bionic libraries.  The
/// Java host extracts lib/x86_64 into it before constructing PathClassLoader;
/// bionic_translation must see both that directory and its own shim libraries
/// when its constructor runs during Runtime.create.
fn configureNativeLibraries(init: std.process.Init, tree: []const u8, runtime_root: []const u8, apk: []const u8) !void {
    const a = init.arena.allocator();
    const native_dir = if (init.minimal.environ.getPosix("ARTZIG_NATIVE_LIB_DIR")) |configured|
        configured
    else blk: {
        const cache_root = if (init.minimal.environ.getPosix("XDG_CACHE_HOME")) |p|
            p
        else if (init.minimal.environ.getPosix("HOME")) |p|
            try std.fs.path.join(a, &.{ p, ".cache" })
        else
            "/tmp";
        const apk_key = std.hash.Wyhash.hash(0, apk);
        break :blk try std.fmt.allocPrint(a, "{s}/artzig/apk-libs/{x}", .{ cache_root, apk_key });
    };
    try std.Io.Dir.cwd().createDirPath(init.io, native_dir);

    _ = setenv("ARTZIG_NATIVE_LIB_DIR", try a.dupeSentinel(u8, native_dir, 0), 1);
    _ = setenv("ARTZIG_IMAGE_NATIVE_LIB_DIR", try std.fs.path.joinZ(a, &.{ tree, "apps", "lib" }), 1);
    _ = runtime_root;
    _ = setenv("BIONIC_LD_LIBRARY_PATH", try std.fmt.allocPrintSentinel(a, "{s}:{s}/apps/lib/*", .{ native_dir, tree }, 0), 1);

    // libdl_bio reads its soname override files from XDG_DATA_DIRS in its ELF
    // constructor, so prepend the mappings installed alongside the launcher.
    const share = try std.fs.path.join(a, &.{ tree, "share" });
    const data_dirs = if (init.minimal.environ.getPosix("XDG_DATA_DIRS")) |old|
        try std.fmt.allocPrintSentinel(a, "{s}:{s}", .{ share, old }, 0)
    else
        try a.dupeSentinel(u8, share, 0);
    _ = setenv("XDG_DATA_DIRS", data_dirs, 1);
}

/// Persistent Android application storage:
/// `$ARTZIG_DATA_DIR`, else `$XDG_DATA_HOME/artzig`, else
/// `$HOME/.local/share/artzig`. Exported for this process and everything it
/// spawns (system server, app processes, tests), with the download-cache and
/// shared-storage roots AOSP reads from DOWNLOAD_CACHE and EXTERNAL_STORAGE
/// placed underneath. Runtime.create supplies ANDROID_DATA's session layout.
fn configureDataRoot(init: std.process.Init) !void {
    const a = init.arena.allocator();
    const env = init.minimal.environ;
    const root = if (env.getPosix("ARTZIG_DATA_DIR")) |configured| configured else blk: {
        if (env.getPosix("XDG_DATA_HOME")) |xdg| break :blk try std.fs.path.join(a, &.{ xdg, "artzig" });
        const home = env.getPosix("HOME") orelse "/tmp";
        break :blk try std.fs.path.join(a, &.{ home, ".local", "share", "artzig" });
    };
    try std.Io.Dir.cwd().createDirPath(init.io, root);
    _ = setenv("ARTZIG_DATA_DIR", try a.dupeSentinel(u8, root, 0), 1);
    if (env.getPosix("DOWNLOAD_CACHE") == null) {
        _ = setenv("DOWNLOAD_CACHE", try std.fs.path.joinZ(a, &.{ root, "cache" }), 1);
    }
    if (env.getPosix("EXTERNAL_STORAGE") == null) {
        _ = setenv("EXTERNAL_STORAGE", try std.fs.path.joinZ(a, &.{ root, "external" }), 1);
    }
}

fn dataPartitionOptions(init: std.process.Init) !art.DataPartition.Options {
    const persistent = getenv("ARTZIG_DATA_DIR") orelse return error.MissingApplicationDataRoot;
    return .{
        .persistent_root = std.mem.span(persistent),
        .directory = if (getenv("AZK_BINDER_SESSION_DIR")) |session|
            try std.fs.path.join(init.arena.allocator(), &.{ std.mem.span(session), "android-data" })
        else
            null,
    };
}

fn sessionStorage(init: std.process.Init) !binder_session.Storage {
    const a = init.arena.allocator();
    const cache = try art.defaultDataDir(a, init.minimal.environ);
    const external = std.mem.span(getenv("EXTERNAL_STORAGE") orelse return error.MissingExternalStorageRoot);
    const download = std.mem.span(getenv("DOWNLOAD_CACHE") orelse return error.MissingDownloadCache);
    for ([_][]const u8{ cache, external, download }) |path|
        try std.Io.Dir.cwd().createDirPath(init.io, path);
    return .{
        .application_data = try std.Io.Dir.cwd().realPathFileAlloc(init.io, std.mem.span(getenv("ARTZIG_DATA_DIR") orelse return error.MissingApplicationDataRoot), a),
        .external_storage = try std.Io.Dir.cwd().realPathFileAlloc(init.io, external, a),
        .download_cache = try std.Io.Dir.cwd().realPathFileAlloc(init.io, download, a),
        .runtime_cache = try std.Io.Dir.cwd().realPathFileAlloc(init.io, cache, a),
    };
}

fn applySessionStorage(init: std.process.Init, storage: binder_session.Storage) !void {
    for (storage.environment()) |entry| {
        if (setenv(entry.name.ptr, try init.arena.allocator().dupeSentinel(u8, entry.value, 0), 1) != 0)
            return error.StorageEnvironmentFailed;
    }
}

pub fn main(init: std.process.Init) !void {
    if (init.minimal.environ.getPosix("AZK_LAUNCH_UID")) |raw_uid| {
        if (init.minimal.environ.getPosix("AZK_LAUNCH_CREDENTIAL") == null) return error.MissingLaunchCredential;
        try @import("binder/ProcessNamespace.zig").enter(init.io, try std.fmt.parseInt(u32, raw_uid, 10));
        // The trusted supervisor binds its real child handle before releasing
        // this gate. No ART or application native constructor runs beforehand.
        var released: [1]u8 = undefined;
        const count = try std.Io.File.stdin().readStreaming(init.io, &.{&released});
        if (count != 1 or released[0] != 1) return error.LaunchAuthorizationLost;
    }

    const args = try init.minimal.args.toSlice(init.arena.allocator());
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

    var frames_limit: ?u32 = null;
    var use_java = false;
    var fullscreen = false;
    var join_dir: ?[]const u8 = null;
    var apk: ?[]const u8 = null;
    // Hosted Binder session: broker, SurfaceFlinger and system-server per launch;
    // every process started from this one inherits the socket and cookie and
    // joins the same object graph. Joined launches reuse an existing session.
    var binder: ?binder_session.Session = null;
    defer if (binder) |*session| session.stop();
    // `--join` must take effect regardless of argument order: `--app-process`
    // and `--javatest` act (and return) from inside the main loop, so a later
    // `--join` would otherwise be dropped and a second session started.
    {
        var j: usize = 1;
        while (j < args.len) : (j += 1) {
            if (std.mem.eql(u8, args[j], "--join") and j + 1 < args.len) {
                join_dir = args[j + 1];
                j += 1;
            }
        }
    }
    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        if (std.mem.eql(u8, args[i], "--help") or std.mem.eql(u8, args[i], "-h")) {
            std.debug.print(usage, .{});
            return;
        } else if (args[i].len > 0 and args[i][0] != '-') {
            apk = args[i]; // positional: the APK to run
            use_java = true;
        } else if (std.mem.eql(u8, args[i], "--frames") and i + 1 < args.len) {
            i += 1;
            frames_limit = try std.fmt.parseInt(u32, args[i], 10);
        } else if (std.mem.eql(u8, args[i], "--java")) {
            use_java = true;
        } else if (std.mem.eql(u8, args[i], "--fullscreen")) {
            fullscreen = true;
        } else if (std.mem.eql(u8, args[i], "--join") and i + 1 < args.len) {
            i += 1;
            join_dir = args[i];
        } else if (std.mem.eql(u8, args[i], "--app-process") and i + 1 < args.len) {
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
            var headless = try bootHeadlessApk(init, apk_abs);
            defer headless.rt.releaseProcessState();
            if (binder) |*session| try session.awaitSystemReady();
            try runFrameworkMain(&headless.rt, "dev.artzig.AppProcess", &.{});
            return;
        } else if (std.mem.eql(u8, args[i], "--system-server")) {
            // Spawned by session.zig inside a session; never starts one itself.
            var headless = try bootHeadless(init);
            defer headless.rt.releaseProcessState();
            try runFrameworkMain(&headless.rt, "dev.artzig.system.SystemServer", &.{});
            return;
        } else if (std.mem.eql(u8, args[i], "--codec-make-m4v")) {
            const r = @import("azk_codec").writeTestM4V(init.io, "/tmp/azk-test.m4v");
            std.debug.print("wrote /tmp/azk-test.m4v: {d} frames\n", .{r});
            return;
        } else if (std.mem.eql(u8, args[i], "--codec-make-mp3")) {
            const r = @import("azk_codec").writeTestMP3(init.io, "/tmp/azk-test.mp3");
            std.debug.print("wrote /tmp/azk-test.mp3: {d} bytes\n", .{r});
            return;
        } else if (std.mem.eql(u8, args[i], "--codec-selftest")) {
            const n = @import("azk_codec").azk_codec_selftest();
            std.debug.print("codec selftest: {d} samples decoded ({s})\n", .{ n, if (n > 0) "PASS" else "FAIL" });
            return;
        } else if (std.mem.eql(u8, args[i], "--javatest")) {
            try ensureSession(init, &binder, join_dir, try taskPresentation(init.minimal.environ, false));
            const test_apk = init.minimal.environ.getPosix("AZK_TEST_APK");
            if (test_apk) |fixture_apk| {
                if (!std.fs.path.isAbsolute(fixture_apk)) return error.TestApkPathMustBeAbsolute;
                _ = setenv("ARTZIG_APK", try init.arena.allocator().dupeSentinel(u8, fixture_apk, 0), 1);
            }
            var headless = if (test_apk) |fixture_apk| try bootHeadlessApk(init, fixture_apk) else try bootHeadless(init);
            defer headless.rt.releaseProcessState();
            if (binder) |*session| try session.awaitSystemReady();
            const rt = &headless.rt;
            const cls_name = init.minimal.environ.getPosix("AZK_TEST_CLASS") orelse "dev.artzig.TestMain";
            try runFrameworkMain(rt, cls_name, args[i + 1 ..]);
            const e2 = art.Env{ .env = rt.env };
            const cls2 = try e2.findClass("dev/artzig/GraphicsSmoke");
            const draw2 = try e2.getStaticMethodID(cls2, "draw", "(II)Landroid/graphics/Bitmap;");
            const jb = rt.env.*.*.CallStaticObjectMethod.?(rt.env, cls2, draw2, @as(art.jni.jint, 64), @as(art.jni.jint, 64));
            try e2.check();
            std.debug.print("draw() returned bitmap: {}\n", .{jb != null});
            return;
        }
    }

    if (!use_java) {
        std.debug.print(usage, .{});
        return;
    }
    const present_tasks = try taskPresentation(init.minimal.environ, apk != null);
    if (apk == null) return error.TaskPresentationRequiresApk;
    try ensureSession(init, &binder, join_dir, present_tasks);
    if (apk != null and present_tasks) {
        if (frames_limit != null or fullscreen) return error.TaskWindowPresentationOptionsUnavailable;
        const test_address = init.minimal.environ.getPosix("AZK_TEST_SOCKET");
        if (test_address != null and binder == null) return error.TaskWindowTestControllerRequiresSessionOwner;
        const arena = init.arena.allocator();
        const path = if (std.fs.path.isAbsolute(apk.?)) apk.? else try std.fs.path.join(arena, &.{ init.minimal.environ.getPosix("PWD") orelse ".", apk.? });
        _ = setenv("ARTZIG_APK", try arena.dupeSentinel(u8, path, 0), 1);
        // Only the session owner holds the privileged cookie. APK processes are
        // spawned by the system service with app credentials, as on cold launch.
        if (binder) |*session| {
            _ = setenv(binder_session.system_cookie_env, try arena.dupeSentinel(u8, session.system_cookie, 0), 1);
        }
        var headless = try bootHeadlessApk(init, path);
        defer headless.rt.releaseProcessState();
        if (binder) |*session| try session.awaitSystemReady();
        var desktop_control: ?test_control.Server = null;
        defer {
            @import("desktop_test_control.zig").deinit();
            if (desktop_control) |*server| server.deinit();
        }
        if (test_address) |address| {
            desktop_control = try test_control.Server.init(init.gpa, init.io, address);
            try @import("desktop_test_control.zig").register(@ptrCast(headless.rt.env), &desktop_control.?);
        }
        try runFrameworkMain(&headless.rt, "dev.artzig.window.DesktopLauncher", &.{if (binder != null) "owner" else "joined"});
        const e = art.Env{ .env = headless.rt.env };
        const cls = try e.findClass("dev/artzig/window/DesktopLauncher");
        defer headless.rt.env.*.*.DeleteLocalRef.?(headless.rt.env, cls);
        const failed = try e.getStaticMethodID(cls, "sessionFailed", "()Z");
        const lost = headless.rt.env.*.*.CallStaticBooleanMethod.?(headless.rt.env, cls, failed) != 0;
        try e.check();
        // Unwind through the session owner's bounded cleanup before reporting
        // service loss. AndroidRuntime's uncaught-exception exit cannot do that.
        if (lost) return error.DesktopSessionLost;
        return;
    }
    return error.TaskPresentationRequiresApk;
}

// APKs require task presentation. Headless JVM
// probes need no Wayland connection unless they explicitly request a presenter.
fn taskPresentation(environ: std.process.Environ, apk_launch: bool) !bool {
    if (environ.getPosix("AZK_WINDOW_BACKEND")) |mode| {
        if (!std.mem.eql(u8, mode, "stock")) return error.UnsupportedWindowBackend;
    }
    const present = if (environ.getPosix("AZK_TASK_WINDOWS")) |mode| blk: {
        if (std.mem.eql(u8, mode, "1")) break :blk true;
        if (std.mem.eql(u8, mode, "0")) break :blk false;
        return error.InvalidTaskWindowSelection;
    } else apk_launch;
    if (apk_launch and !present) return error.IncompatibleWindowBackendSelection;
    return present;
}

/// Starts this launch's session (broker, compositor, system process) unless one is
/// inherited (`AZK_BINDER_SOCKET` set) or joined with `--join`, and exports
/// the socket and app cookie so every child process joins the same session.
fn ensureSession(init: std.process.Init, binder: *?binder_session.Session, join_dir: ?[]const u8, present_tasks: bool) !void {
    const arena = init.arena.allocator();
    if (join_dir) |dir| {
        const info = try joinSession(init, dir);
        if (info.storage) |storage| try applySessionStorage(init, storage);
        _ = setenv(binder_session.socket_env, try arena.dupeSentinel(u8, info.socket, 0), 1);
        if (init.minimal.environ.getPosix("AZK_LAUNCH_CREDENTIAL") == null)
            _ = setenv(binder_session.cookie_env, try arena.dupeSentinel(u8, info.cookie, 0), 1);
        _ = setenv(binder_session.prop_area_env, try arena.dupeSentinel(u8, info.prop_area, 0), 1);
        _ = setenv("AZK_BINDER_SESSION_DIR", try arena.dupeSentinel(u8, dir, 0), 1);
        std.debug.print("azk: joined binder session: {s}\n", .{dir});
        return;
    }
    if (init.minimal.environ.getPosix(binder_session.socket_env) != null) return;
    const tree = try installTree(init);
    const broker_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-binder-broker" });
    const launcher_exe = try std.process.executablePathAlloc(init.io, arena);
    const storage = try sessionStorage(init);
    try applySessionStorage(init, storage);
    binder.* = try binder_session.Session.start(init.gpa, init.io, .{
        .broker_exe = broker_exe,
        .launcher_exe = launcher_exe,
        .surfaceflinger_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-surfaceflinger" }),
        .task_presenter_exe = if (present_tasks)
            try std.fs.path.join(arena, &.{ tree, "bin", "azk-task-presenter" })
        else
            null,
        .environ = init.minimal.environ,
        .storage = storage,
    });
    _ = setenv(binder_session.socket_env, try arena.dupeSentinel(u8, binder.*.?.socket_path, 0), 1);
    _ = setenv(binder_session.cookie_env, try arena.dupeSentinel(u8, binder.*.?.cookie, 0), 1);
    _ = setenv(binder_session.prop_area_env, try arena.dupeSentinel(u8, binder.*.?.prop_area_path, 0), 1);
    _ = setenv("AZK_BINDER_SESSION_DIR", try arena.dupeSentinel(u8, binder.*.?.dir, 0), 1);
    std.debug.print("azk: binder session: {s}\n", .{binder.*.?.dir});
}

/// Reads `<dir>/session.json` written by the session owner.
fn joinSession(init: std.process.Init, dir: []const u8) !binder_session.SessionInfo {
    return binder_session.SessionInfo.read(init.arena.allocator(), init.io, dir);
}

const Headless = struct {
    rt: art.Runtime,
    tree: []const u8,
};

/// Register the framework natives served by libbinder_ndk.so (SystemProperties
/// for now; the android.os migration grows this). The library also backs the
/// NDK binder ABI, so it is opened RTLD-global from the install tree — the
/// same copy the island links and bionic maps by soname.
fn registerBinderJni(arena: std.mem.Allocator, tree: []const u8, env: *anyopaque, platform: *std.DynLib) !void {
    const bootstrap = platform.lookup(RegisterFn, "azk_register_runtime_jni") orelse return error.SymbolNotFound;
    if (bootstrap(env) != 0) return error.JniRegistrationFailed;
    const path = try std.fs.path.join(arena, &.{ tree, "lib", "art", "libbinder_ndk.so" });
    var lib = try std.DynLib.open(path);
    const RegisterBinderFn = *const fn (*anyopaque) callconv(.c) c_int;
    const register = lib.lookup(RegisterBinderFn, "azk_register_binder_jni") orelse return error.SymbolNotFound;
    const SetProcessExitFn = *const fn (ProcessExitFn) callconv(.c) void;
    const set_process_exit = lib.lookup(SetProcessExitFn, "azk_binder_set_process_exit") orelse return error.SymbolNotFound;
    set_process_exit(try lookupProcessExit(platform));
    if (register(env) != 0) return error.JniRegistrationFailed;
}

/// Boots ART with the framework jar and the host natives but no window, no
/// fonts, and no bionic loader: enough for framework Java (Binder, Looper,
/// AssetManager, PackageParser) in the system process and test peers.
/// System fonts: unless the user points ARTZIG_FONT_FALLBACK_XML at a
/// hand-made config, generate one from fontconfig (must precede ART boot:
/// SystemFonts reads the env at class init). Every ART process needs it, the
/// headless ones included: the real Context's theme resolves a Typeface as
/// soon as a TextView is inflated.
fn configureFonts(init: std.process.Init) void {
    if (init.minimal.environ.getPosix("ARTZIG_FONT_FALLBACK_XML") != null) return;
    const t0 = monotonicMs();
    defer std.debug.print("azk: fonts: fontconfig query took {d} ms\n", .{monotonicMs() - t0});
    if (fonts.generate(init.gpa, init.minimal.environ)) |xml| {
        defer init.gpa.free(xml);
        _ = setenv("ARTZIG_FONT_FALLBACK_XML", xml, 1);
        _ = setenv("ARTZIG_FONTS_XML", xml, 1);
        _ = setenv("ARTZIG_FONT_DIR", "/", 1);
    } else {
        std.debug.print("azk: fonts: fontconfig query failed; set ARTZIG_FONT_FALLBACK_XML\n", .{});
    }
}

const ProcessExitFn = *const fn (c_int) callconv(.c) noreturn;

// std.Io has no immediate self-termination operation. Use AndroidRuntime's
// native boundary: std.process.exit runs libc destructors in a libc-linked Zig
// executable, which is incompatible with ART's live worker threads.
fn lookupProcessExit(lib: *std.DynLib) !ProcessExitFn {
    return lib.lookup(ProcessExitFn, "azk_android_runtime_exit") orelse error.SymbolNotFound;
}

fn runFrameworkMain(runtime: *art.Runtime, class_name: []const u8, arguments: []const [:0]const u8) !void {
    const allocator = runtime.arena.allocator();
    const bootstrap_args = try allocator.alloc([:0]const u8, arguments.len + 1);
    bootstrap_args[0] = try allocator.dupeSentinel(u8, class_name, 0);
    @memcpy(bootstrap_args[1..], arguments);
    try runtime.runMain("dev.artzig.RuntimeBootstrap", bootstrap_args);
}

fn bootHeadless(init: std.process.Init) !Headless {
    const arena = init.arena.allocator();
    const tree = try installTree(init);
    const runtime_overlay_dir = try std.fs.path.join(arena, &.{ tree, "lib", "art" });
    const island_path = try std.fs.path.join(arena, &.{ tree, "lib", "libandroid_platform.so" });
    var lib = try std.DynLib.open(island_path);
    try bindPlatformIo(&lib, init.io);
    const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
    const hwui = try lookupHwuiBridges(&lib);
    const looper_poll = lib.lookup(host_natives.LooperPollStateFn, "azk_looper_poll_state") orelse return error.SymbolNotFound;
    const framework_jar = try std.fs.path.join(arena, &.{ tree, "framework", "framework.jar" });
    _ = setenv("ARTZIG_FRAMEWORK_JAR", try arena.dupeSentinel(u8, framework_jar, 0), 1);
    if (init.minimal.environ.getPosix("ARTZIG_FRAMEWORK_RES") == null) {
        _ = setenv("ARTZIG_FRAMEWORK_RES", try std.fs.path.joinZ(arena, &.{ tree, "res", "framework-res.apk" }), 1);
    }
    configureFonts(init);
    // Stock runtime libart and default library search path: the overlay
    // libart in lib/art has no sibling JNI libraries (libicu_jni.so, ...), and
    // without the bionic loader there is no <root>/lib64 fallback.
    var rt = try art.Runtime.create(init.gpa, init.io, init.minimal.environ, .{
        .classpath = &.{framework_jar},
        .platform_class_loader = "dev.artzig.HostedPlatformClassLoader",
        .data_partition = try dataPartitionOptions(init),
        .data_dir = if (getenv("ARTZIG_RUNTIME_CACHE_DIR")) |cache| std.mem.span(cache) else null,
        .system_root_overlay = true,
        .system_libraries = try @import("platform/system_libraries.zig").resolve(arena, init.io, tree),
        .library_path = &.{ runtime_overlay_dir, "/system/lib64" },
        .use_bionic_loader = false,
        .check_jni = init.minimal.environ.getPosix("AZK_CHECK_JNI") != null,
        .exit_hook = try lookupProcessExit(&lib),
    });
    errdefer rt.releaseProcessState();
    if (init.minimal.environ.getPosix("AZK_NO_REG") == null) {
        // AndroidRuntime registers properties/Binder before display JNI: native
        // Choreographer lookup initializes DisplayManagerGlobal's sysprop fields.
        try registerBinderJni(arena, tree, @ptrCast(rt.env), &lib);
        if (register(@ptrCast(rt.env)) != 0) return error.JniRegistrationFailed;
        try host_natives.register(init.io, @ptrCast(rt.env), hwui, looper_poll);
    }
    return .{ .rt = rt, .tree = tree };
}

/// Like `bootHeadless`, but with the bionic loader and the APK's native
/// libraries configured (the `--app-process` service host): identical runtime
/// setup to the UI path minus the window, so APK NDK libraries load the same
/// way. The island is dlopened RTLD_GLOBAL so the bionic linker sees platform
/// symbols while relocating APK libraries.
fn bootHeadlessApk(init: std.process.Init, apk_abs: []const u8) !Headless {
    const arena = init.arena.allocator();
    const tree = try installTree(init);
    const runtime_root = (try locateRuntimeRoot(init, tree)) orelse
        init.minimal.environ.getPosix("ARTZIG_ROOT") orelse return error.RuntimeTreeNotFound;
    try configureNativeLibraries(init, tree, runtime_root, apk_abs);

    const framework_jar = try std.fs.path.join(arena, &.{ tree, "framework", "framework.jar" });
    _ = setenv("ARTZIG_FRAMEWORK_JAR", try arena.dupeSentinel(u8, framework_jar, 0), 1);
    if (init.minimal.environ.getPosix("ARTZIG_FRAMEWORK_RES") == null) {
        _ = setenv("ARTZIG_FRAMEWORK_RES", try std.fs.path.joinZ(arena, &.{ tree, "res", "framework-res.apk" }), 1);
    }
    const libart_overlay = try std.fs.path.join(arena, &.{ tree, "lib", "art", "libart.so" });
    const runtime_overlay_dir = try std.fs.path.join(arena, &.{ tree, "lib", "art" });

    const island_path = try std.fs.path.join(arena, &.{ tree, "lib", "libandroid_platform.so" });
    const island_path_z = try arena.dupeSentinel(u8, island_path, 0);
    if (dlopen(island_path_z, 2 | 0x100) == null) return error.PlatformLoadFailed; // RTLD_NOW|RTLD_GLOBAL
    var lib = try std.DynLib.open(island_path);
    try bindPlatformIo(&lib, init.io);
    const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
    const hwui = try lookupHwuiBridges(&lib);
    const looper_poll = lib.lookup(host_natives.LooperPollStateFn, "azk_looper_poll_state") orelse return error.SymbolNotFound;

    // libbinder_ndk is the same DSO the platform/ART bridge will use. Complete
    // grant authentication before ART can initialize client identity caches.
    if (init.minimal.environ.getPosix("AZK_LAUNCH_CREDENTIAL") != null) {
        const Authenticate = *const fn () callconv(.c) i32;
        const authenticate = lib.lookup(Authenticate, "azk_binder_authenticate_launch") orelse return error.SymbolNotFound;
        if (authenticate() < 0) return error.LaunchAuthenticationFailed;
    }
    configureFonts(init);
    var rt = try art.Runtime.create(init.gpa, init.io, init.minimal.environ, .{
        .classpath = &.{framework_jar},
        .platform_class_loader = "dev.artzig.HostedPlatformClassLoader",
        .data_partition = try dataPartitionOptions(init),
        .data_dir = if (getenv("ARTZIG_RUNTIME_CACHE_DIR")) |cache| std.mem.span(cache) else null,
        .system_root_overlay = true,
        .system_libraries = try @import("platform/system_libraries.zig").resolve(arena, init.io, tree),
        .library_path = &.{ runtime_overlay_dir, "/system/lib64" },
        .root = runtime_root,
        .libart_path = libart_overlay,
        .bionic_lib_dir = runtime_overlay_dir,
        .use_bionic_loader = true,
        .check_jni = init.minimal.environ.getPosix("AZK_CHECK_JNI") != null,
        .exit_hook = try lookupProcessExit(&lib),
    });
    errdefer rt.releaseProcessState();
    if (init.minimal.environ.getPosix("AZK_NO_REG") == null) {
        // AndroidRuntime registers properties/Binder before display JNI: native
        // Choreographer lookup initializes DisplayManagerGlobal's sysprop fields.
        try registerBinderJni(arena, tree, @ptrCast(rt.env), &lib);
        if (register(@ptrCast(rt.env)) != 0) return error.JniRegistrationFailed;
        try host_natives.register(init.io, @ptrCast(rt.env), hwui, looper_poll);
    }
    return .{ .rt = rt, .tree = tree };
}

// ---------------------------------------------------------------------------
// Native platform registration and RenderNode snapshot ownership. Stock HWUI
// queues Android surfaces; only the session presenter communicates with Wayland.

const RegisterFn = *const fn (env: *anyopaque) callconv(.c) c_int;
const BindPlatformIoFn = *const fn (
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void;
const RetainRenderNodeFn = *const fn (node: *anyopaque) callconv(.c) void;

fn lookupHwuiBridges(lib: *std.DynLib) !host_natives.HwuiBridges {
    return .{
        .retain_render_node = lib.lookup(RetainRenderNodeFn, "azk_hwui_retain_render_node") orelse return error.SymbolNotFound,
        .release_render_node = lib.lookup(RetainRenderNodeFn, "azk_hwui_release_render_node") orelse return error.SymbolNotFound,
    };
}

fn bindPlatformIo(lib: *std.DynLib, io: std.Io) !void {
    const bind = lib.lookup(BindPlatformIoFn, "azk_platform_bind_io") orelse
        return error.SymbolNotFound;
    bind(io.userdata, io.vtable);
}
