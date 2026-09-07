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
            const r = @import("azk_codec").azk_codec_write_test_m4v("/tmp/azk-test.m4v");
            std.debug.print("wrote /tmp/azk-test.m4v: {d} frames\n", .{r});
            return;
        } else if (std.mem.eql(u8, args[i], "--codec-make-mp3")) {
            const r = @import("azk_codec").azk_codec_write_test_mp3("/tmp/azk-test.mp3");
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
