//! androlinzberg launcher: boot ART, register the Android platform natives,
//! and present Android framework rendering on Wayland.
const std = @import("std");
const binder_session = @import("binder/session.zig");
const wayland = @import("platform/wayland.zig");
const video_layers = @import("platform/video_layers.zig");
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
    \\       androlinzberg --java            (AZK_DRAW_CLASS entry, default ViewHost)
    \\       androlinzberg --codec-selftest | --codec-make-mp3 | --codec-make-m4v | --javatest
    \\
    \\Paths are resolved relative to the executable's install tree (zig-out/):
    \\  lib/libandroid_platform.so, framework/framework.jar, res/framework-res.apk.
    \\The ART runtime is installed beside the executable at bin/art and is
    \\rebuilt by Zig when its inputs change. ARTZIG_ROOT overrides it.
    \\
    \\environment: AZK_DRAW_CLASS (entry class), ARTZIG_ROOT, ARTZIG_CA_BUNDLE,
    \\  ARTZIG_FRAMEWORK_RES,
    \\  ARTZIG_FONT_FALLBACK_XML (skip fontconfig font discovery),
    \\  ARTZIG_VAAPI_DEVICE=/dev/dri/renderDNNN,
    \\  ARTZIG_VIDEO_YUV_DMABUF=1 (opt in to direct compositor YUV import),
    \\  ARTZIG_FRAME_PACING_TRACE=1, ARTZIG_EGL_SWAP_INTERVAL=0,
    \\  AZK_TEST_SOCKET=/path/to/control.sock (private Varlink test service),
    \\  AZK_SIZE=WxH, AZK_TAP/AZK_SWIPE/AZK_WHEEL/AZK_TYPE/... (test hooks)
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

/// The session's Android data root (android.os.Environment.getDataDirectory):
/// `$ARTZIG_DATA_DIR`, else `$XDG_DATA_HOME/artzig`, else
/// `$HOME/.local/share/artzig`. Exported for this process and everything it
/// spawns (system server, app processes, tests), with the download-cache and
/// shared-storage roots AOSP reads from DOWNLOAD_CACHE and EXTERNAL_STORAGE
/// placed underneath. ANDROID_DATA stays ART's dalvik-cache root.
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

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    // Session supervisor trampoline: establish child ownership before executing
    // a service. exec failure becomes EOF on the child's readiness pipe.
    if (args.len >= 3 and std.mem.eql(u8, args[1], "--service-exec"))
        return std.process.replace(init.io, .{ .argv = args[2..] });
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
            try ensureSession(init, &binder, join_dir);
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
            try ensureSession(init, &binder, join_dir);
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
    try ensureSession(init, &binder, join_dir);
    if (apk != null and std.mem.eql(u8, init.minimal.environ.getPosix("AZK_TASK_WINDOWS") orelse "", "1")) {
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
    return javaMain(init, frames_limit, apk, fullscreen, if (binder) |*session| session else null);
}

/// Starts this launch's session (broker, compositor, system process) unless one is
/// inherited (`AZK_BINDER_SOCKET` set) or joined with `--join`, and exports
/// the socket and app cookie so every child process joins the same session.
fn ensureSession(init: std.process.Init, binder: *?binder_session.Session, join_dir: ?[]const u8) !void {
    const arena = init.arena.allocator();
    if (join_dir) |dir| {
        const info = try joinSession(init, dir);
        _ = setenv(binder_session.socket_env, try arena.dupeSentinel(u8, info.socket, 0), 1);
        _ = setenv(binder_session.cookie_env, try arena.dupeSentinel(u8, info.cookie, 0), 1);
        _ = setenv(binder_session.prop_area_env, try arena.dupeSentinel(u8, info.prop_area, 0), 1);
        std.debug.print("azk: joined binder session: {s}\n", .{dir});
        return;
    }
    if (init.minimal.environ.getPosix(binder_session.socket_env) != null) return;
    const tree = try installTree(init);
    const broker_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-binder-broker" });
    const launcher_exe = try std.process.executablePathAlloc(init.io, arena);
    binder.* = try binder_session.Session.start(init.gpa, init.io, .{
        .broker_exe = broker_exe,
        .launcher_exe = launcher_exe,
        .surfaceflinger_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-surfaceflinger" }),
        .task_presenter_exe = if (std.mem.eql(u8, init.minimal.environ.getPosix("AZK_TASK_WINDOWS") orelse "", "1"))
            try std.fs.path.join(arena, &.{ tree, "bin", "azk-task-presenter" })
        else
            null,
        .environ = init.minimal.environ,
    });
    _ = setenv(binder_session.socket_env, try arena.dupeSentinel(u8, binder.*.?.socket_path, 0), 1);
    _ = setenv(binder_session.cookie_env, try arena.dupeSentinel(u8, binder.*.?.cookie, 0), 1);
    _ = setenv(binder_session.prop_area_env, try arena.dupeSentinel(u8, binder.*.?.prop_area_path, 0), 1);
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
        .system_root_overlay = true,
        .library_path = &.{runtime_overlay_dir},
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

    configureFonts(init);
    var rt = try art.Runtime.create(init.gpa, init.io, init.minimal.environ, .{
        .classpath = &.{framework_jar},
        .system_root_overlay = true,
        .library_path = &.{runtime_overlay_dir},
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
// Java drives the real android.graphics stack. HWUI presents directly through
// EGL; there is deliberately no CPU/Bitmap window-present path.

const RegisterFn = *const fn (env: *anyopaque) callconv(.c) c_int;
const BindPlatformIoFn = *const fn (
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void;
const ConfigureHostFn = *const fn (proxy: *anyopaque, display: ?*anyopaque, surface: ?*anyopaque, width: c_int, height: c_int) callconv(.c) void;
const DestroyHostFn = *const fn (proxy: *anyopaque) callconv(.c) void;
const RetainRenderNodeFn = *const fn (node: *anyopaque) callconv(.c) void;

fn lookupHwuiBridges(lib: *std.DynLib) !host_natives.HwuiBridges {
    return .{
        .bind_egl_display = lib.lookup(RetainRenderNodeFn, "azk_hwui_bind_egl_display") orelse return error.SymbolNotFound,
        .configure = lib.lookup(ConfigureHostFn, "azk_hwui_configure_host_surface") orelse return error.SymbolNotFound,
        .destroy = lib.lookup(DestroyHostFn, "azk_hwui_destroy_host_surface") orelse return error.SymbolNotFound,
        .retain_render_node = lib.lookup(RetainRenderNodeFn, "azk_hwui_retain_render_node") orelse return error.SymbolNotFound,
        .release_render_node = lib.lookup(RetainRenderNodeFn, "azk_hwui_release_render_node") orelse return error.SymbolNotFound,
    };
}

fn bindPlatformIo(lib: *std.DynLib, io: std.Io) !void {
    const bind = lib.lookup(BindPlatformIoFn, "azk_platform_bind_io") orelse
        return error.SymbolNotFound;
    bind(io.userdata, io.vtable);
}
const HardwareDrawAbi = enum { physical, scaled, legacy };

fn javaMain(init: std.process.Init, frames_limit: ?u32, apk: ?[]const u8, fullscreen: bool, session: ?*binder_session.Session) !void {
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const tree = try installTree(init);

    // Running an APK: entry class ApkHost, paths from the install tree.
    const apk_abs: ?[]const u8 = if (apk) |path|
        (if (std.fs.path.isAbsolute(path)) path else try std.fs.path.join(arena, &.{ init.minimal.environ.getPosix("PWD") orelse ".", path }))
    else
        null;
    if (apk_abs) |path| _ = setenv("ARTZIG_APK", try arena.dupeSentinel(u8, path, 0), 1);
    const framework_jar = try std.fs.path.join(arena, &.{ tree, "framework", "framework.jar" });
    const framework_compile_jar = try std.fs.path.join(arena, &.{ tree, "framework", "framework-app-context.jar" });
    _ = setenv("ARTZIG_FRAMEWORK_JAR", try arena.dupeSentinel(u8, framework_jar, 0), 1);
    if (init.minimal.environ.getPosix("ARTZIG_FRAMEWORK_RES") == null) {
        _ = setenv("ARTZIG_FRAMEWORK_RES", try std.fs.path.joinZ(arena, &.{ tree, "res", "framework-res.apk" }), 1);
    }
    // (art.Runtime reads the startup environment snapshot, so pass the root
    // explicitly rather than via setenv)
    const runtime_root = try locateRuntimeRoot(init, tree);
    if (runtime_root) |root| std.debug.print("azk: ART runtime: {s}\n", .{root});
    const libart_overlay = try std.fs.path.join(arena, &.{ tree, "lib", "art", "libart.so" });
    const runtime_overlay_dir = try std.fs.path.join(arena, &.{ tree, "lib", "art" });
    if (apk_abs) |path| {
        const root = runtime_root orelse init.minimal.environ.getPosix("ARTZIG_ROOT") orelse
            return error.RuntimeTreeNotFound;
        try configureNativeLibraries(init, tree, root, path);
    }

    // The island: real hwui + graphics JNI + static Skia.
    const island_path = try std.fs.path.join(arena, &.{ tree, "lib", "libandroid_platform.so" });
    // Make Android platform symbols (notably liblog) visible to the bionic
    // compatibility linker while it relocates APK libraries.
    const island_path_z = try arena.dupeSentinel(u8, island_path, 0);
    if (dlopen(island_path_z, 2 | 0x100) == null) return error.PlatformLoadFailed; // RTLD_NOW|RTLD_GLOBAL
    var lib = std.DynLib.open(island_path) catch |e| {
        std.debug.print("failed to load {s}: {}\n", .{ island_path, e });
        return e;
    };
    try bindPlatformIo(&lib, init.io);
    const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
    const hwui = try lookupHwuiBridges(&lib);
    const looper_poll = lib.lookup(host_natives.LooperPollStateFn, "azk_looper_poll_state") orelse return error.SymbolNotFound;

    configureFonts(init);

    // Boot ART with the multidex framework jar.
    const art_extra_options: []const []const u8 =
        if (init.minimal.environ.getPosix("ARTZIG_VERBOSE_OAT") != null)
            &.{"-verbose:oat"}
        else
            &.{};
    const rt_v = try art.Runtime.create(gpa, init.io, init.minimal.environ, .{
        .classpath = &.{framework_jar},
        .system_root_overlay = true,
        .library_path = &.{runtime_overlay_dir},
        .root = runtime_root,
        .libart_path = libart_overlay,
        .bionic_lib_dir = runtime_overlay_dir,
        .use_bionic_loader = apk != null,
        .extra_options = art_extra_options,
        .exit_hook = try lookupProcessExit(&lib),
    });
    var rt = rt_v;
    _ = &rt;
    defer rt.releaseProcessState();
    const env = rt.env;
    // ART boot overlapped with the system process boot; services must be
    // published before any Java runs.
    if (session) |s| try s.awaitSystemReady();
    try registerBinderJni(arena, tree, @ptrCast(env), &lib);
    if (register(@ptrCast(env)) != 0) return error.JniRegistrationFailed;
    try host_natives.register(init.io, @ptrCast(env), hwui, looper_poll);
    defer host_natives.deinitWakeups();

    const e = art.Env{ .env = env };
    if (apk != null) {
        const host = try e.findClass("dev/artzig/ApkHost");
        const identity = try e.getStaticMethodID(host, "prepareProcessIdentity", "()Ljava/lang/String;");
        const package = env.*.*.CallStaticObjectMethod.?(env, host, identity);
        try e.check();
        if (package) |value| env.*.*.DeleteLocalRef.?(env, value);
        env.*.*.DeleteLocalRef.?(env, host);
    }
    // entry class: AZK_DRAW_CLASS, else ApkHost when an APK was given
    const cls_name = init.minimal.environ.getPosix("AZK_DRAW_CLASS") orelse
        (if (apk != null) "dev/artzig/ApkHost" else "dev/artzig/ViewHost");
    const cls_z = try gpa.dupeSentinel(u8, cls_name, 0);
    defer gpa.free(cls_z);
    const cls = try e.findClass(cls_z);
    const draw_hardware_physical: ?art.jni.jmethodID = e.getStaticMethodID(cls, "drawHardware", "(IIIII)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const draw_hardware_scaled: ?art.jni.jmethodID = if (draw_hardware_physical == null)
        e.getStaticMethodID(cls, "drawHardware", "(IIII)V") catch blk: {
            env.*.*.ExceptionClear.?(env);
            break :blk null;
        }
    else
        null;
    // Keep custom/older draw classes working while the in-tree hosts use
    // physical Android pixels plus the exact Wayland scale numerator.
    const draw_hardware_legacy: ?art.jni.jmethodID = if (draw_hardware_physical == null and draw_hardware_scaled == null)
        e.getStaticMethodID(cls, "drawHardware", "(II)V") catch blk: {
            env.*.*.ExceptionClear.?(env);
            break :blk null;
        }
    else
        null;
    const draw_hardware = draw_hardware_physical orelse draw_hardware_scaled orelse
        draw_hardware_legacy orelse return error.HardwareDrawRequired;
    const draw_hardware_abi: HardwareDrawAbi = if (draw_hardware_physical != null)
        .physical
    else if (draw_hardware_scaled != null)
        .scaled
    else
        .legacy;
    const motion: ?art.jni.jmethodID = e.getStaticMethodID(cls, "motion", "(IFF)V") catch null;
    const mouse: ?art.jni.jmethodID = e.getStaticMethodID(cls, "mouse", "(IIIFF)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const scroll: ?art.jni.jmethodID = e.getStaticMethodID(cls, "scroll", "(FFFF)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const back: ?art.jni.jmethodID = e.getStaticMethodID(cls, "back", "()V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const key: ?art.jni.jmethodID = e.getStaticMethodID(cls, "key", "(III)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const physical_key: ?art.jni.jmethodID = e.getStaticMethodID(cls, "physicalKey", "(IIII)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const touch_contact: ?art.jni.jmethodID = e.getStaticMethodID(cls, "touchContact", "(IIFFFFF)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const input_reset: ?art.jni.jmethodID = e.getStaticMethodID(cls, "inputReset", "(I)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const captured_pointer: ?art.jni.jmethodID = e.getStaticMethodID(cls, "capturedPointer", "(FFI)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const captured_mouse: ?art.jni.jmethodID = e.getStaticMethodID(cls, "capturedMouse", "(III)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const wants_pointer_capture: ?art.jni.jmethodID = e.getStaticMethodID(cls, "wantsPointerCapture", "()Z") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const host_pointer_capture_changed: ?art.jni.jmethodID = e.getStaticMethodID(cls, "onHostPointerCaptureChanged", "(Z)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const hover: ?art.jni.jmethodID = e.getStaticMethodID(cls, "hover", "(FF)I") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const hover_action: ?art.jni.jmethodID = e.getStaticMethodID(cls, "hoverAction", "(IFF)I") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const shutdown: ?art.jni.jmethodID = e.getStaticMethodID(cls, "shutdown", "()V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const wants_fullscreen: ?art.jni.jmethodID = e.getStaticMethodID(cls, "wantsFullscreen", "()Z") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const host_fullscreen_changed: ?art.jni.jmethodID = e.getStaticMethodID(cls, "onHostFullscreenChanged", "(Z)V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const pump_main_looper: ?art.jni.jmethodID = e.getStaticMethodID(cls, "pumpMainLooper", "()J") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const needs_frame: ?art.jni.jmethodID = e.getStaticMethodID(cls, "needsFrame", "()Z") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const apk_dexopt_needed: ?art.jni.jmethodID = e.getStaticMethodID(cls, "apkDexoptNeeded", "()Z") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    const report_dexopt_failure: ?art.jni.jmethodID = e.getStaticMethodID(cls, "reportDexoptFailure", "()V") catch blk: {
        env.*.*.ExceptionClear.?(env);
        break :blk null;
    };
    var control_android: ?test_control.Android = null;
    var control_server: ?test_control.Server = null;
    if (init.minimal.environ.getPosix("AZK_TEST_SOCKET")) |address| {
        control_android = try test_control.Android.init(env, cls);
        control_server = try test_control.Server.init(gpa, init.io, address);
        std.debug.print("azk test control: {s}\n", .{address});
    }
    defer if (control_server) |*server| server.deinit();
    var mouse_button_state: i32 = 0;
    // synthetic tap injection for headless testing: AZK_TAP="x,y@frame"
    // AZK_TAP="x,y@frame[;x,y@frame...]": synthetic taps
    const Tap = struct { x: f32, y: f32, frame: u32 };
    var taps: std.ArrayList(Tap) = .empty;
    defer taps.deinit(gpa);
    if (init.minimal.environ.getPosix("AZK_TAP")) |spec| {
        var groups = std.mem.tokenizeScalar(u8, spec, ';');
        while (groups.next()) |g| {
            var it = std.mem.tokenizeAny(u8, g, ",@");
            try taps.append(gpa, .{
                .x = try std.fmt.parseFloat(f32, it.next() orelse "0"),
                .y = try std.fmt.parseFloat(f32, it.next() orelse "0"),
                .frame = try std.fmt.parseInt(u32, it.next() orelse "3", 10),
            });
        }
    }
    // AZK_WHEEL="dy@frame[;dy@frame...]": synthetic wheel scroll at screen center
    const Wheel = struct { dy: f32, frame: u32 };
    var wheels: std.ArrayList(Wheel) = .empty;
    defer wheels.deinit(gpa);
    if (init.minimal.environ.getPosix("AZK_WHEEL")) |spec| {
        var groups = std.mem.tokenizeScalar(u8, spec, ';');
        while (groups.next()) |g| {
            var it = std.mem.tokenizeScalar(u8, g, '@');
            try wheels.append(gpa, .{
                .dy = try std.fmt.parseFloat(f32, it.next() orelse "0"),
                .frame = try std.fmt.parseInt(u32, it.next() orelse "3", 10),
            });
        }
    }
    // AZK_PRESS="x,y@frame+hold[;...]": touch down at frame, up hold frames later
    const Press = struct { x: f32, y: f32, down: u32, up: u32 };
    var presses: std.ArrayList(Press) = .empty;
    defer presses.deinit(gpa);
    if (init.minimal.environ.getPosix("AZK_PRESS")) |spec| {
        var groups = std.mem.tokenizeScalar(u8, spec, ';');
        while (groups.next()) |group| {
            var it = std.mem.tokenizeAny(u8, group, ",@+");
            const x = try std.fmt.parseFloat(f32, it.next() orelse "0");
            const y = try std.fmt.parseFloat(f32, it.next() orelse "0");
            const down = try std.fmt.parseInt(u32, it.next() orelse "3", 10);
            try presses.append(gpa, .{
                .x = x,
                .y = y,
                .down = down,
                .up = down + (std.fmt.parseInt(u32, it.next() orelse "60", 10) catch 60),
            });
        }
    }
    // AZK_HOVER="x,y@frame": resolve the pointer icon at (x,y), print it (testing)
    var hov: [2]f32 = .{ 0, 0 };
    var hover_frame: u32 = 0;
    if (init.minimal.environ.getPosix("AZK_HOVER")) |spec| {
        var it = std.mem.tokenizeAny(u8, spec, ",@");
        hov[0] = try std.fmt.parseFloat(f32, it.next() orelse "0");
        hov[1] = try std.fmt.parseFloat(f32, it.next() orelse "0");
        hover_frame = try std.fmt.parseInt(u32, it.next() orelse "3", 10);
    }
    // AZK_TYPE="text@frame[;...]": type text (unicode) at frames; "\n" = enter (testing)
    const TypeEvent = struct { text: []const u8, frame: u32 };
    var type_events: std.ArrayList(TypeEvent) = .empty;
    defer type_events.deinit(gpa);
    if (init.minimal.environ.getPosix("AZK_TYPE")) |spec| {
        var groups = std.mem.tokenizeScalar(u8, spec, ';');
        while (groups.next()) |group| {
            if (std.mem.lastIndexOfScalar(u8, group, '@')) |at| {
                try type_events.append(gpa, .{
                    .text = group[0..at],
                    .frame = try std.fmt.parseInt(u32, group[at + 1 ..], 10),
                });
            }
        }
    }
    // AZK_BACK="frame[;frame...]": simulate mouse back button presses (testing)
    var backs: std.ArrayList(u32) = .empty;
    defer backs.deinit(gpa);
    if (init.minimal.environ.getPosix("AZK_BACK")) |spec| {
        var it = std.mem.tokenizeScalar(u8, spec, ';');
        while (it.next()) |g| try backs.append(gpa, try std.fmt.parseInt(u32, g, 10));
    }
    // AZK_RESIZE="WxH@frame": simulate a compositor resize (testing)
    var resize_w: i32 = 0;
    var resize_h: i32 = 0;
    var resize_frame: u32 = 0;
    if (init.minimal.environ.getPosix("AZK_RESIZE")) |spec| {
        var it = std.mem.tokenizeAny(u8, spec, "x@");
        resize_w = try std.fmt.parseInt(i32, it.next() orelse "0", 10);
        resize_h = try std.fmt.parseInt(i32, it.next() orelse "0", 10);
        resize_frame = try std.fmt.parseInt(u32, it.next() orelse "3", 10);
    }
    // AZK_SWIPE="x1,y1,x2,y2@frame": down at (x1,y1), interpolated moves, up at (x2,y2)
    var sw: [4]f32 = .{ 0, 0, 0, 0 };
    var swipe_frame: u32 = 0;
    if (init.minimal.environ.getPosix("AZK_SWIPE")) |spec| {
        var it = std.mem.tokenizeAny(u8, spec, ",@");
        for (&sw) |*v| v.* = try std.fmt.parseFloat(f32, it.next() orelse "0");
        swipe_frame = try std.fmt.parseInt(u32, it.next() orelse "3", 10);
    }
    // AZK_MOUSE_SWIPE="x1,y1,x2,y2@frame": primary-button mouse drag across frames.
    var mouse_sw: [4]f32 = .{ 0, 0, 0, 0 };
    var mouse_swipe_frame: u32 = 0;
    var mouse_swipe_step: u32 = 0;
    if (init.minimal.environ.getPosix("AZK_MOUSE_SWIPE")) |spec| {
        var it = std.mem.tokenizeAny(u8, spec, ",@");
        for (&mouse_sw) |*v| v.* = try std.fmt.parseFloat(f32, it.next() orelse "0");
        mouse_swipe_frame = try std.fmt.parseInt(u32, it.next() orelse "3", 10);
    }

    var init_w: i32 = 800;
    var init_h: i32 = 600;
    if (init.minimal.environ.getPosix("AZK_SIZE")) |spec| {
        var it = std.mem.tokenizeScalar(u8, spec, 'x');
        init_w = try std.fmt.parseInt(i32, it.next() orelse "800", 10);
        init_h = try std.fmt.parseInt(i32, it.next() orelse "600", 10);
    }
    const win = try wayland.Window.initOpts(gpa, init.io, init_w, init_h, "androlinzberg", fullscreen);
    defer win.deinit();
    if (control_server) |*server| {
        if (win.testInputAvailable()) {
            if (win.prepareTestInput()) {
                server.setInput(.{
                    .context = win,
                    .tap_fn = controlTap,
                    .drag_fn = controlDrag,
                    .type_fn = controlType,
                    .key_fn = controlKey,
                    .fullscreen_fn = controlFullscreen,
                });
            } else |err| {
                std.debug.print("azk test input unavailable: {}\n", .{err});
            }
        }
    }
    host_natives.bindWayland(@ptrCast(win.session.display), @ptrCast(win.renderSurface()));
    video_layers.register(gpa, init.io, win, @ptrCast(env), draw_hardware_abi == .physical) catch |err| {
        std.debug.print("video layers unavailable: {}\n", .{err});
    };
    defer video_layers.deinit();
    defer if (shutdown) |mid| jenv_call_void(env, cls, mid);
    var frame_count: u32 = 0;
    var presented_frame_count: u64 = 0;
    const trace_frame_pacing = init.minimal.environ.getPosix("ARTZIG_FRAME_PACING_TRACE") != null;
    const trace_slow_frames = init.minimal.environ.getPosix("ARTZIG_SLOW_FRAME_TRACE") != null;
    var last_frame_done_ms: i64 = 0;
    var callback_samples: u32 = 0;
    var callback_last_ns: i128 = 0;
    var callback_total_ns: i128 = 0;
    var callback_max_ns: i128 = 0;
    var app_fullscreen = false;
    var app_pointer_capture = false;
    var first_presented_ms: ?i64 = null;
    var last_input_ms = monotonicMs();
    var dexopt_attempted = false;
    var dexopt_running = false;
    try javaFrame(win, env, cls, draw_hardware, draw_hardware_abi);
    var render_in_flight = true;
    var vsync_pending = false;
    // A Wayland frame callback can either request the next Android traversal or merely observe
    // that the first committed buffer reached the compositor. Keep those meanings separate so
    // the first-presentation dexopt clock does not manufacture an otherwise-unneeded frame.
    var vsync_requires_frame = false;
    var deferred_frame = false;
    var looper_timeout_ms = callMainLooperPump(env, cls, pump_main_looper);
    try syncAppFullscreen(win, env, cls, wants_fullscreen, &app_fullscreen);
    try syncAppPointerCapture(win, env, cls, wants_pointer_capture, &app_pointer_capture);
    try win.present();
    frame_count += 1;
    if (control_server) |*server| if (control_android) |*android| {
        server.tick(android, frame_count, presented_frame_count);
    };

    outer: while (true) {
        var wait_timeout_ms = dexoptWaitTimeout(
            looper_timeout_ms,
            apk_abs != null and !dexopt_attempted,
            first_presented_ms,
            last_input_ms,
        );
        if (control_server) |*server| {
            wait_timeout_ms = minPollTimeout(wait_timeout_ms, server.waitTimeoutMs());
        }
        const native_looper = host_natives.looperPollState();
        wait_timeout_ms = minPollTimeout(wait_timeout_ms, native_looper.timeout_ms);
        const waited = try win.waitEvents(.{
            .video = video_layers.wakeupFd(),
            .looper = native_looper.fd,
            .renderer = host_natives.rendererWakeupFd(),
            .optimizer = apk_optimizer.wakeupFd(),
            .control_listener = if (control_server) |*server| server.listenerFd() else null,
            .control_client = if (control_server) |*server| server.clientFd() else null,
            .control_client_write = if (control_server) |*server| server.clientWantsWrite() else false,
            .timeout_ms = wait_timeout_ms,
        });
        const events = waited.events;
        if (trace_frame_pacing and (waited.looper_ready or waited.renderer_ready or
            waited.optimizer_ready or events.len != 0))
        {
            std.debug.print(
                "azk pacing: wake t={d} looper={} renderer={} optimizer={} events={d}\n",
                .{ monotonicMs(), waited.looper_ready, waited.renderer_ready, waited.optimizer_ready, events.len },
            );
        }
        if (waited.renderer_ready and host_natives.consumeRendererWakeup() != 0) {
            if (trace_frame_pacing) std.debug.print("azk pacing: renderer ready t={d}, committing parent\n", .{monotonicMs()});
            render_in_flight = false;
            const frame_work_pending = deferred_frame or frameLimitPending(frames_limit, frame_count) or
                callStaticBoolean(env, cls, needs_frame) or
                (if (control_server) |*server| server.needsFrame() else false);
            if (first_presented_ms == null or frame_work_pending) {
                try win.requestFrame();
                vsync_pending = true;
                vsync_requires_frame = vsync_requires_frame or frame_work_pending;
                if (frame_work_pending) deferred_frame = false;
            }
            win.uiFrameReady();
            // A synchronized child commit remains cached until the parent commits. Publish it
            // before submitting another Java frame or the EGL swapchain eventually fills and
            // blocks syncAndDraw despite setWaitForPresent(false).
            video_layers.process();
            try win.present();
            if (trace_frame_pacing) std.debug.print("azk pacing: parent committed t={d}\n", .{monotonicMs()});
            if (frameLimitReached(frames_limit, frame_count)) break :outer;
        }
        if (waited.optimizer_ready) {
            switch (apk_optimizer.consume()) {
                .success => {
                    dexopt_running = false;
                    std.debug.print("azk: background dexopt completed successfully; it will be used next launch\n", .{});
                },
                .failure => {
                    dexopt_running = false;
                    std.debug.print("azk: background dexopt failed; a later launch will retry\n", .{});
                    if (report_dexopt_failure) |mid| jenv_call_void(env, cls, mid);
                },
                .none => {},
            }
        }
        var need_frame = false;
        for (events) |ev| switch (ev) {
            .close => break :outer,
            .configure, .fullscreen_changed => need_frame = true,
            .frame_done => {
                vsync_pending = false;
                const frame_work_pending = vsync_requires_frame;
                vsync_requires_frame = false;
                presented_frame_count += 1;
                if (first_presented_ms == null) first_presented_ms = monotonicMs();
                need_frame = need_frame or frame_work_pending;
                if (trace_slow_frames) {
                    const frame_done_ms = monotonicMs();
                    if (last_frame_done_ms != 0 and frame_done_ms - last_frame_done_ms > 25) {
                        std.debug.print("azk slow frame: compositor interval={d}ms t={d}\n", .{ frame_done_ms - last_frame_done_ms, frame_done_ms });
                    }
                    last_frame_done_ms = frame_done_ms;
                }
                if (trace_frame_pacing) {
                    std.debug.print("azk pacing: frame done t={d}\n", .{monotonicMs()});
                    const now_ns: i128 = std.Io.Clock.awake.now(init.io).nanoseconds;
                    if (callback_last_ns != 0) {
                        const elapsed_ns = now_ns - callback_last_ns;
                        callback_samples += 1;
                        callback_total_ns += elapsed_ns;
                        callback_max_ns = @max(callback_max_ns, elapsed_ns);
                        if (callback_samples == 120) {
                            std.debug.print(
                                "azk pacing: wl_surface.frame avg={d:.3}ms max={d:.3}ms n={d}\n",
                                .{
                                    @as(f64, @floatFromInt(callback_total_ns)) / 120_000_000.0,
                                    @as(f64, @floatFromInt(callback_max_ns)) / 1_000_000.0,
                                    callback_samples,
                                },
                            );
                            callback_samples = 0;
                            callback_total_ns = 0;
                            callback_max_ns = 0;
                        }
                    }
                    callback_last_ns = now_ns;
                }
            },
            else => {},
        };
        for (events) |ev| if (isInputEvent(ev)) {
            last_input_ms = monotonicMs();
        };
        // Input first, then the looper: Looper.pollOnce services the input channel before it
        // returns the next due message, so a release that already arrived reaches the view
        // before a long-press or tap-timeout message becomes due behind a heavy frame.
        for (events) |ev| switch (ev) {
            .fullscreen_changed => |actual_fullscreen| if (host_fullscreen_changed) |mid| {
                env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jboolean, @intFromBool(actual_fullscreen)));
                try e.check();
            },
            .pointer_motion => |m| if (mouse_button_state != 0) {
                if (control_server) |*server| server.noteInput();
                callMouseEvent(env, cls, mouse, motion, 2, mouse_button_state, 0, inputX(win, draw_hardware_abi, m.x), inputY(win, draw_hardware_abi, m.y));
            } else if (hover_action != null or hover != null) {
                if (control_server) |*server| server.noteInput();
                const icon = callHoverEvent(env, cls, hover_action, hover, 7, inputX(win, draw_hardware_abi, m.x), inputY(win, draw_hardware_abi, m.y));
                win.setCursorShape(cursorShapeFromPointerIcon(icon));
            },
            .pointer_enter => |p| if (hover_action != null or hover != null) {
                if (control_server) |*server| server.noteInput();
                const icon = callHoverEvent(env, cls, hover_action, hover, 9, inputX(win, draw_hardware_abi, p.x), inputY(win, draw_hardware_abi, p.y));
                win.setCursorShape(cursorShapeFromPointerIcon(icon));
            },
            .pointer_leave => |p| if (hover_action != null or hover != null) {
                if (control_server) |*server| server.noteInput();
                _ = callHoverEvent(env, cls, hover_action, hover, 10, inputX(win, draw_hardware_abi, p.x), inputY(win, draw_hardware_abi, p.y));
            },
            .pointer_cancel => {
                mouse_button_state = 0;
                if (input_reset) |mid| jenv_call_int(env, cls, mid, 0);
            },
            .pointer_relative => |motion_event| if (captured_pointer) |mid| {
                jenv_call_captured_pointer(
                    env,
                    cls,
                    mid,
                    inputDeltaX(win, draw_hardware_abi, motion_event.dx),
                    inputDeltaY(win, draw_hardware_abi, motion_event.dy),
                    mouse_button_state,
                );
            },
            .pointer_capture_changed => |has_capture| if (host_pointer_capture_changed) |mid| {
                env.*.*.CallStaticVoidMethod.?(
                    env,
                    cls,
                    mid,
                    @as(art.jni.jboolean, @intFromBool(has_capture.enabled)),
                );
                try e.check();
            },
            .pointer_button => |b| if (win.hasPointerCapture()) {
                if (androidMouseButton(b.button)) |button| if (captured_mouse) |mid| {
                    if (control_server) |*server| server.noteInput();
                    if (b.pressed) {
                        const was = mouse_button_state;
                        mouse_button_state |= button;
                        if (was == 0) jenv_call_captured_mouse(env, cls, mid, 0, mouse_button_state, 0);
                        jenv_call_captured_mouse(env, cls, mid, 11, mouse_button_state, button);
                    } else {
                        mouse_button_state &= ~button;
                        jenv_call_captured_mouse(env, cls, mid, 12, mouse_button_state, button);
                        if (mouse_button_state == 0) jenv_call_captured_mouse(env, cls, mid, 1, 0, 0);
                    }
                };
            } else if (b.button == 0x113 or b.button == 0x116) {
                // Android's default View handling does not turn BUTTON_BACK generic motion into
                // navigation. Preserve the host's physical mouse back-button policy.
                if (control_server) |*server| server.noteInput();
                if (b.pressed) {
                    if (back) |mid| jenv_call_void(env, cls, mid);
                }
            } else if (androidMouseButton(b.button)) |button| {
                if (mouse != null or motion != null) {
                    if (control_server) |*server| server.noteInput();
                    const x = inputX(win, draw_hardware_abi, b.x);
                    const y = inputY(win, draw_hardware_abi, b.y);
                    if (b.pressed) {
                        const was = mouse_button_state;
                        mouse_button_state |= button;
                        if (was == 0) callMouseEvent(env, cls, mouse, motion, 0, mouse_button_state, 0, x, y);
                        callMouseEvent(env, cls, mouse, motion, 11, mouse_button_state, button, x, y);
                    } else {
                        mouse_button_state &= ~button;
                        callMouseEvent(env, cls, mouse, motion, 12, mouse_button_state, button, x, y);
                        if (mouse_button_state == 0) callMouseEvent(env, cls, mouse, motion, 1, mouse_button_state, 0, x, y);
                    }
                }
            },
            .key => |k| {
                if (control_server) |*server| server.noteInput();
                if (k.pressed and k.keysym == 0xffc8) {
                    win.toggleFullscreen(); // XKB_KEY_F11
                } else if (physical_key) |mid| {
                    jenv_call_physical_key(
                        env,
                        cls,
                        mid,
                        if (k.pressed) 0 else 1,
                        @intCast(k.keycode),
                        @intCast(k.unicode),
                        @intCast(k.keysym),
                    );
                } else if (key) |mid| {
                    const android_code = androidKeyFromKeysym(k.keysym);
                    if (android_code != 0 or k.unicode >= 32) jenv_call_key(
                        env,
                        cls,
                        mid,
                        if (k.pressed) 0 else 1,
                        android_code,
                        @intCast(k.unicode),
                    );
                }
            },
            .keyboard_reset => if (input_reset) |mid| jenv_call_int(env, cls, mid, 1),
            .touch => |touch_event| if (touch_contact) |mid| {
                jenv_call_touch_contact(
                    env,
                    cls,
                    mid,
                    @backingInt(touch_event.action),
                    touch_event.id,
                    inputX(win, draw_hardware_abi, touch_event.x),
                    inputY(win, draw_hardware_abi, touch_event.y),
                    inputDeltaX(win, draw_hardware_abi, touch_event.major),
                    inputDeltaY(win, draw_hardware_abi, touch_event.minor),
                    @floatCast(touch_event.orientation),
                );
            },
            .touch_cancel => if (input_reset) |mid| jenv_call_int(env, cls, mid, 2),
            .pointer_axis => |a| if (scroll) |mid| {
                if (control_server) |*server| server.noteInput();
                jenv_call_scroll(env, cls, mid, inputX(win, draw_hardware_abi, win.pointer_x), inputY(win, draw_hardware_abi, win.pointer_y), @floatCast(a.horizontal), @floatCast(a.vertical));
            },
            else => {},
        };
        // Android's MessageQueue wakes independently from vsync. Dispatch ordinary Handler work
        // immediately; only Choreographer traversal callbacks remain frame-paced.
        looper_timeout_ms = callMainLooperPump(env, cls, pump_main_looper);
        if (hover_frame != 0 and frame_count == hover_frame) {
            if (hover) |mid| {
                const icon = jenv_call_hover(env, cls, mid, inputX(win, draw_hardware_abi, hov[0]), inputY(win, draw_hardware_abi, hov[1]));
                std.debug.print("azk: hover at {d},{d}: pointer icon {d} -> cursor shape {d}\n", .{ hov[0], hov[1], icon, cursorShapeFromPointerIcon(icon) });
            }
            hover_frame = 0;
        }
        for (type_events.items) |*type_event| {
            if (type_event.frame != 0 and frame_count == type_event.frame) {
                if (key) |mid| {
                    var it = (std.unicode.Utf8View.init(type_event.text) catch std.unicode.Utf8View.initUnchecked("")).iterator();
                    var pending_escape = false;
                    while (it.nextCodepoint()) |cp| {
                        if (!pending_escape and cp == '\\') {
                            pending_escape = true;
                            continue;
                        }
                        if (pending_escape and cp == 'n') {
                            jenv_call_key(env, cls, mid, 0, 66, 0); // ENTER
                            jenv_call_key(env, cls, mid, 1, 66, 0);
                        } else {
                            jenv_call_key(env, cls, mid, 0, 0, @intCast(cp));
                            jenv_call_key(env, cls, mid, 1, 0, 0);
                        }
                        pending_escape = false;
                    }
                }
                type_event.frame = 0;
            }
        }
        for (presses.items) |*press| {
            if (press.down != 0 and frame_count == press.down) {
                if (motion) |mid| jenv_call_motion(env, cls, mid, 0, inputX(win, draw_hardware_abi, press.x), inputY(win, draw_hardware_abi, press.y));
            }
            if (press.up != 0 and frame_count == press.up) {
                if (motion) |mid| jenv_call_motion(env, cls, mid, 1, inputX(win, draw_hardware_abi, press.x), inputY(win, draw_hardware_abi, press.y));
                press.down = 0;
                press.up = 0;
            }
        }
        for (backs.items) |bf| {
            if (bf == frame_count) {
                if (back) |mid| jenv_call_void(env, cls, mid);
            }
        }
        if (resize_frame != 0 and frame_count == resize_frame) {
            win.simulateResize(resize_w, resize_h);
            resize_frame = 0;
            need_frame = true;
        }
        for (wheels.items) |wh| {
            if (wh.frame == frame_count) {
                if (scroll) |mid| {
                    const cx = inputX(win, draw_hardware_abi, @as(f64, @floatFromInt(@divTrunc(win.width, 2))));
                    const cy = inputY(win, draw_hardware_abi, @as(f64, @floatFromInt(@divTrunc(win.height, 2))));
                    jenv_call_scroll(env, cls, mid, cx, cy, 0, wh.dy);
                }
            }
        }
        for (taps.items) |t| {
            if (t.frame == frame_count) {
                if (motion) |mid| {
                    jenv_call_motion(env, cls, mid, 0, inputX(win, draw_hardware_abi, t.x), inputY(win, draw_hardware_abi, t.y));
                    jenv_call_motion(env, cls, mid, 1, inputX(win, draw_hardware_abi, t.x), inputY(win, draw_hardware_abi, t.y));
                }
            }
        }
        if (swipe_frame != 0 and frame_count == swipe_frame) {
            if (motion) |mid| {
                jenv_call_motion(env, cls, mid, 0, inputX(win, draw_hardware_abi, sw[0]), inputY(win, draw_hardware_abi, sw[1]));
                var step: f32 = 1;
                while (step <= 10) : (step += 1) {
                    const t = step / 10.0;
                    jenv_call_motion(env, cls, mid, 2, inputX(win, draw_hardware_abi, sw[0] + (sw[2] - sw[0]) * t), inputY(win, draw_hardware_abi, sw[1] + (sw[3] - sw[1]) * t));
                }
                jenv_call_motion(env, cls, mid, 1, inputX(win, draw_hardware_abi, sw[2]), inputY(win, draw_hardware_abi, sw[3]));
            }
            swipe_frame = 0;
        }
        if (mouse_swipe_frame != 0 and frame_count >= mouse_swipe_frame) {
            last_input_ms = monotonicMs();
            const start_x = inputX(win, draw_hardware_abi, mouse_sw[0]);
            const start_y = inputY(win, draw_hardware_abi, mouse_sw[1]);
            if (mouse_swipe_step == 0) {
                callMouseEvent(env, cls, mouse, motion, 0, 1, 0, start_x, start_y);
                callMouseEvent(env, cls, mouse, motion, 11, 1, 1, start_x, start_y);
            } else if (mouse_swipe_step <= 10) {
                const t = @as(f32, @floatFromInt(mouse_swipe_step)) / 10.0;
                callMouseEvent(
                    env,
                    cls,
                    mouse,
                    motion,
                    2,
                    1,
                    0,
                    inputX(win, draw_hardware_abi, mouse_sw[0] + (mouse_sw[2] - mouse_sw[0]) * t),
                    inputY(win, draw_hardware_abi, mouse_sw[1] + (mouse_sw[3] - mouse_sw[1]) * t),
                );
            } else {
                const end_x = inputX(win, draw_hardware_abi, mouse_sw[2]);
                const end_y = inputY(win, draw_hardware_abi, mouse_sw[3]);
                callMouseEvent(env, cls, mouse, motion, 12, 0, 1, end_x, end_y);
                callMouseEvent(env, cls, mouse, motion, 1, 0, 0, end_x, end_y);
                mouse_swipe_frame = 0;
            }
            mouse_swipe_step += 1;
            need_frame = true;
        }
        // Input dispatch can synchronously enqueue Handler/Rx work. Android's
        // native looper runs that work before the following traversal instead
        // of deferring it until another Wayland event wakes the outer loop.
        looper_timeout_ms = callMainLooperPump(env, cls, pump_main_looper);
        if (need_frame and render_in_flight) {
            deferred_frame = true;
            need_frame = false;
        }
        if (!need_frame and !render_in_flight and !vsync_pending and
            callStaticBoolean(env, cls, needs_frame))
        {
            if (trace_frame_pacing) std.debug.print("azk pacing: request vsync t={d}\n", .{monotonicMs()});
            try win.requestVsync();
            vsync_pending = true;
            vsync_requires_frame = true;
        }
        if (need_frame) {
            const java_frame_start_ms = if (trace_slow_frames) monotonicMs() else 0;
            if (trace_frame_pacing) std.debug.print("azk pacing: submit frame {d} t={d}\n", .{ frame_count + 1, monotonicMs() });
            const submitted = blk: {
                javaFrame(win, env, cls, draw_hardware, draw_hardware_abi) catch |err| {
                    // A broken frame must not leave the asynchronous renderer permanently busy.
                    std.debug.print("azk: frame error: {} (retrying on the next vsync)\n", .{err});
                    break :blk false;
                };
                break :blk true;
            };
            if (trace_slow_frames) {
                const java_frame_elapsed_ms = monotonicMs() - java_frame_start_ms;
                if (java_frame_elapsed_ms > 16) {
                    std.debug.print("azk slow frame: java frame={d} duration={d}ms t={d}\n", .{ frame_count + 1, java_frame_elapsed_ms, monotonicMs() });
                }
            }
            if (submitted) {
                render_in_flight = true;
                frame_count += 1;
                if (trace_frame_pacing) std.debug.print("azk pacing: submitted frame {d} t={d}\n", .{ frame_count, monotonicMs() });
            } else if (!vsync_pending) {
                // Pace repeated Java failures and guarantee another traversal opportunity without
                // waiting for a RenderThread notification that this failed submission cannot send.
                try win.requestVsync();
                vsync_pending = true;
                vsync_requires_frame = true;
            }
        }
        // Handler/input callbacks can change window presentation without invalidating pixels.
        // Observe their state even when there is no Java frame to submit.
        syncAppFullscreen(win, env, cls, wants_fullscreen, &app_fullscreen) catch |err| {
            std.debug.print("azk: fullscreen state error: {} (continuing)\n", .{err});
        };
        syncAppPointerCapture(win, env, cls, wants_pointer_capture, &app_pointer_capture) catch |err| {
            std.debug.print("azk: pointer capture state error: {} (continuing)\n", .{err});
        };
        // Codec threads and SurfaceView traversal only update stable mailbox
        // slots. Drain them after the Java frame so its latest geometry and
        // the UI buffer are published by the same parent transaction.
        video_layers.process();
        try win.present();
        if (control_server) |*server| if (control_android) |*android| {
            server.tick(android, frame_count, presented_frame_count);
        };
        if (!render_in_flight and !vsync_pending and
            (if (control_server) |*server| server.needsFrame() else false))
        {
            try win.requestVsync();
            vsync_pending = true;
            vsync_requires_frame = true;
        }
        if (apk_abs != null and !dexopt_attempted and !dexopt_running and first_presented_ms != null and
            monotonicMs() >= @max(first_presented_ms.?, last_input_ms) + 2000)
        {
            dexopt_attempted = true;
            if (callStaticBoolean(env, cls, apk_dexopt_needed)) {
                const runtime = runtime_root orelse init.minimal.environ.getPosix("ARTZIG_ROOT") orelse {
                    std.debug.print("azk: cannot start background dexopt without ART runtime root\n", .{});
                    continue;
                };
                const android_data_ptr = getenv("ANDROID_DATA") orelse {
                    std.debug.print("azk: cannot start background dexopt without ANDROID_DATA\n", .{});
                    continue;
                };
                const script = try std.fs.path.join(arena, &.{ tree, "share", "androlinzberg", "dexopt-apk.sh" });
                apk_optimizer.launch(
                    init.io,
                    gpa,
                    script,
                    runtime,
                    apk_abs.?,
                    framework_jar,
                    std.mem.span(android_data_ptr),
                    framework_compile_jar,
                ) catch |err| {
                    std.debug.print("azk: failed to launch background dexopt: {}\n", .{err});
                    if (report_dexopt_failure) |mid| jenv_call_void(env, cls, mid);
                    continue;
                };
                dexopt_running = true;
            } else {
                std.debug.print("azk: APK speed artifacts are current\n", .{});
            }
        }
    }
    std.debug.print("androlinzberg: exiting after {d} java frames\n", .{frame_count});
}

fn dexoptWaitTimeout(looper_timeout_ms: i32, eligible: bool, first_presented_ms: ?i64, last_input_ms: i64) i32 {
    if (!eligible or first_presented_ms == null) return looper_timeout_ms;
    const remaining = @max(@max(first_presented_ms.?, last_input_ms) + 2000 - monotonicMs(), 0);
    const dexopt_timeout: i32 = @intCast(@min(remaining, std.math.maxInt(i32)));
    if (looper_timeout_ms < 0) return dexopt_timeout;
    return @min(looper_timeout_ms, dexopt_timeout);
}

fn minPollTimeout(first: i32, second: i32) i32 {
    if (first < 0) return second;
    if (second < 0) return first;
    return @min(first, second);
}

fn frameLimitPending(limit: ?u32, frame_count: u32) bool {
    return if (limit) |value| frame_count < value else false;
}

fn frameLimitReached(limit: ?u32, frame_count: u32) bool {
    return if (limit) |value| frame_count >= value else false;
}

test "frame limit remains pending until the requested submission count" {
    try std.testing.expect(!frameLimitPending(null, 1));
    try std.testing.expect(frameLimitPending(5, 4));
    try std.testing.expect(!frameLimitReached(5, 4));
    try std.testing.expect(!frameLimitPending(5, 5));
    try std.testing.expect(frameLimitReached(5, 5));
}

fn callStaticBoolean(env: anytype, cls: art.jni.jclass, method: ?art.jni.jmethodID) bool {
    const mid = method orelse return false;
    const result = env.*.*.CallStaticBooleanMethod.?(env, cls, mid) != 0;
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
        return true;
    }
    return result;
}

fn isInputEvent(event: wayland.Event) bool {
    return switch (event) {
        .pointer_motion,
        .pointer_enter,
        .pointer_leave,
        .pointer_cancel,
        .pointer_relative,
        .pointer_button,
        .key,
        .keyboard_reset,
        .touch,
        .touch_cancel,
        .pointer_axis,
        => true,
        else => false,
    };
}

fn javaFrame(win: *wayland.Window, env: anytype, cls: art.jni.jclass, draw_hardware: art.jni.jmethodID, draw_hardware_abi: HardwareDrawAbi) !void {
    const e = art.Env{ .env = env };
    const jenv = env.*.*;
    if (draw_hardware_abi == .physical) {
        jenv.CallStaticVoidMethod.?(
            env,
            cls,
            draw_hardware,
            @as(art.jni.jint, @intCast(win.width)),
            @as(art.jni.jint, @intCast(win.height)),
            @as(art.jni.jint, @intCast(win.bufferWidth())),
            @as(art.jni.jint, @intCast(win.bufferHeight())),
            @as(art.jni.jint, @intCast(win.scale_120)),
        );
    } else if (draw_hardware_abi == .scaled) {
        jenv.CallStaticVoidMethod.?(
            env,
            cls,
            draw_hardware,
            @as(art.jni.jint, @intCast(win.width)),
            @as(art.jni.jint, @intCast(win.height)),
            @as(art.jni.jint, @intCast(win.bufferWidth())),
            @as(art.jni.jint, @intCast(win.bufferHeight())),
        );
    } else {
        jenv.CallStaticVoidMethod.?(env, cls, draw_hardware, @as(art.jni.jint, @intCast(win.width)), @as(art.jni.jint, @intCast(win.height)));
    }
    try e.check();
}

fn callMainLooperPump(env: anytype, cls: art.jni.jclass, method: ?art.jni.jmethodID) i32 {
    const mid = method orelse return -1;
    const delay = env.*.*.CallStaticLongMethod.?(env, cls, mid);
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
        return -1;
    }
    if (delay < 0) return -1;
    return @intCast(@min(delay, std.math.maxInt(i32)));
}

fn syncAppFullscreen(win: *wayland.Window, env: anytype, cls: art.jni.jclass, method: ?art.jni.jmethodID, previous: *bool) !void {
    const mid = method orelse return;
    const fullscreen = env.*.*.CallStaticBooleanMethod.?(env, cls, mid) != 0;
    try (art.Env{ .env = env }).check();
    if (fullscreen == previous.*) return;
    previous.* = fullscreen;
    win.setFullscreen(fullscreen);
}

fn syncAppPointerCapture(win: *wayland.Window, env: anytype, cls: art.jni.jclass, method: ?art.jni.jmethodID, previous: *bool) !void {
    const mid = method orelse return;
    const capture = env.*.*.CallStaticBooleanMethod.?(env, cls, mid) != 0;
    try (art.Env{ .env = env }).check();
    if (capture == previous.*) return;
    previous.* = capture;
    try win.setPointerCapture(capture);
}

fn inputX(win: *const wayland.Window, abi: HardwareDrawAbi, x: f64) f32 {
    return @floatCast(if (abi == .physical) win.surfaceToBufferX(x) else x);
}

fn inputY(win: *const wayland.Window, abi: HardwareDrawAbi, y: f64) f32 {
    return @floatCast(if (abi == .physical) win.surfaceToBufferY(y) else y);
}

fn inputDeltaX(win: *const wayland.Window, abi: HardwareDrawAbi, dx: f64) f32 {
    return @floatCast(if (abi == .physical) win.surfaceToBufferX(dx) else dx);
}

fn inputDeltaY(win: *const wayland.Window, abi: HardwareDrawAbi, dy: f64) f32 {
    return @floatCast(if (abi == .physical) win.surfaceToBufferY(dy) else dy);
}

fn controlTap(context: *anyopaque, x: i32, y: i32) !void {
    try @as(*wayland.Window, @ptrCast(@alignCast(context))).testTap(x, y);
}

fn controlDrag(context: *anyopaque, from_x: i32, from_y: i32, to_x: i32, to_y: i32) !void {
    try @as(*wayland.Window, @ptrCast(@alignCast(context))).testDrag(from_x, from_y, to_x, to_y);
}

fn controlType(context: *anyopaque, text: []const u8) !void {
    try @as(*wayland.Window, @ptrCast(@alignCast(context))).testType(text);
}

fn controlKey(context: *anyopaque, key: []const u8) !void {
    try @as(*wayland.Window, @ptrCast(@alignCast(context))).testKey(key);
}

fn controlFullscreen(context: *anyopaque) bool {
    return @as(*wayland.Window, @ptrCast(@alignCast(context))).isFullscreen();
}

fn cursorShapeFromPointerIcon(icon: i32) u32 {
    return @import("platform/cursor_shape.zig").fromAndroid(icon) orelse 1;
}

fn jenv_call_hover(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, x: f32, y: f32) i32 {
    const r = env.*.*.CallStaticIntMethod.?(env, cls, mid, @as(art.jni.jfloat, x), @as(art.jni.jfloat, y));
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
        return 0;
    }
    return r;
}

fn androidKeyFromKeysym(keysym: u32) i32 {
    return switch (keysym) {
        0xff0d, 0xff8d => 66, // Return, KP_Enter -> ENTER
        0xff08 => 67, // BackSpace -> DEL
        0xffff => 112, // Delete -> FORWARD_DEL
        0xff09 => 61, // Tab -> TAB
        0xff1b => 111, // Escape -> ESCAPE
        0xff51 => 21, // Left -> DPAD_LEFT
        0xff52 => 19, // Up -> DPAD_UP
        0xff53 => 22, // Right -> DPAD_RIGHT
        0xff54 => 20, // Down -> DPAD_DOWN
        0xff50 => 122, // Home -> MOVE_HOME
        0xff57 => 123, // End -> MOVE_END
        0xff55 => 92, // Page_Up -> PAGE_UP
        0xff56 => 93, // Page_Down -> PAGE_DOWN
        else => 0,
    };
}

fn jenv_call_key(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, action: i32, code: i32, unicode: i32) void {
    env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jint, action), @as(art.jni.jint, code), @as(art.jni.jint, unicode));
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_physical_key(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, action: i32, scan_code: i32, unicode: i32, keysym: i32) void {
    env.*.*.CallStaticVoidMethod.?(
        env,
        cls,
        mid,
        @as(art.jni.jint, action),
        @as(art.jni.jint, scan_code),
        @as(art.jni.jint, unicode),
        @as(art.jni.jint, keysym),
    );
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_touch_contact(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, action: i32, id: i32, x: f32, y: f32, major: f32, minor: f32, orientation: f32) void {
    env.*.*.CallStaticVoidMethod.?(
        env,
        cls,
        mid,
        @as(art.jni.jint, action),
        @as(art.jni.jint, id),
        @as(art.jni.jfloat, x),
        @as(art.jni.jfloat, y),
        @as(art.jni.jfloat, major),
        @as(art.jni.jfloat, minor),
        @as(art.jni.jfloat, orientation),
    );
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_captured_pointer(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, dx: f32, dy: f32, button_state: i32) void {
    env.*.*.CallStaticVoidMethod.?(
        env,
        cls,
        mid,
        @as(art.jni.jfloat, dx),
        @as(art.jni.jfloat, dy),
        @as(art.jni.jint, button_state),
    );
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_captured_mouse(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, action: i32, button_state: i32, action_button: i32) void {
    env.*.*.CallStaticVoidMethod.?(
        env,
        cls,
        mid,
        @as(art.jni.jint, action),
        @as(art.jni.jint, button_state),
        @as(art.jni.jint, action_button),
    );
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_int(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, value: i32) void {
    env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jint, value));
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_void(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID) void {
    env.*.*.CallStaticVoidMethod.?(env, cls, mid);
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_scroll(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, x: f32, y: f32, hs: f32, vs: f32) void {
    env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jfloat, x), @as(art.jni.jfloat, y), @as(art.jni.jfloat, hs), @as(art.jni.jfloat, vs));
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_motion(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, action: i32, x: f32, y: f32) void {
    env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jint, action), @as(art.jni.jfloat, x), @as(art.jni.jfloat, y));
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn jenv_call_mouse(env: anytype, cls: art.jni.jclass, mid: art.jni.jmethodID, action: i32, button_state: i32, action_button: i32, x: f32, y: f32) void {
    env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jint, action), @as(art.jni.jint, button_state), @as(art.jni.jint, action_button), @as(art.jni.jfloat, x), @as(art.jni.jfloat, y));
    if (env.*.*.ExceptionCheck.?(env) != 0) {
        env.*.*.ExceptionDescribe.?(env);
        env.*.*.ExceptionClear.?(env);
    }
}

fn callMouseEvent(env: anytype, cls: art.jni.jclass, mouse: ?art.jni.jmethodID, touch_fallback: ?art.jni.jmethodID, action: i32, button_state: i32, action_button: i32, x: f32, y: f32) void {
    if (mouse) |mid| {
        jenv_call_mouse(env, cls, mid, action, button_state, action_button, x, y);
    } else if (touch_fallback) |mid| {
        if (action == 0 or action == 1 or action == 2) jenv_call_motion(env, cls, mid, action, x, y);
    }
}

fn callHoverEvent(env: anytype, cls: art.jni.jclass, hover_action: ?art.jni.jmethodID, hover_fallback: ?art.jni.jmethodID, action: i32, x: f32, y: f32) i32 {
    if (hover_action) |mid| {
        const result = env.*.*.CallStaticIntMethod.?(env, cls, mid, @as(art.jni.jint, action), @as(art.jni.jfloat, x), @as(art.jni.jfloat, y));
        if (env.*.*.ExceptionCheck.?(env) != 0) {
            env.*.*.ExceptionDescribe.?(env);
            env.*.*.ExceptionClear.?(env);
            return 0;
        }
        return result;
    }
    return if (hover_fallback) |mid| jenv_call_hover(env, cls, mid, x, y) else 0;
}

fn androidMouseButton(linux_button: u32) ?i32 {
    return switch (linux_button) {
        0x110 => 1, // BTN_LEFT -> MotionEvent.BUTTON_PRIMARY
        0x111 => 2, // BTN_RIGHT -> BUTTON_SECONDARY
        0x112 => 4, // BTN_MIDDLE -> BUTTON_TERTIARY
        0x113, 0x116 => 8, // BTN_SIDE / BTN_BACK -> BUTTON_BACK
        0x114, 0x115 => 16, // BTN_EXTRA / BTN_FORWARD -> BUTTON_FORWARD
        else => null,
    };
}
