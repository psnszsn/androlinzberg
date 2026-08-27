//! androlinzberg launcher: boot ART, register the Android platform natives,
//! and present Android framework rendering on Wayland.
const std = @import("std");
const wayland = @import("platform/wayland.zig");
const video_layers = @import("platform/video_layers.zig");
const fonts = @import("platform/fonts.zig");
const test_control = @import("test_control.zig");
const host_natives = @import("host_natives");
extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
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
    const exe = try std.process.executablePathAlloc(init.io, a);
    const bin = std.fs.path.dirname(exe) orelse return error.NoInstallTree;
    const tree = std.fs.path.dirname(bin) orelse return error.NoInstallTree;
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
    _ = runtime_root;
    _ = setenv("BIONIC_LD_LIBRARY_PATH", try a.dupeSentinel(u8, native_dir, 0), 1);

    // libdl_bio reads its soname override files from XDG_DATA_DIRS in its ELF
    // constructor, so prepend the mappings installed alongside the launcher.
    const share = try std.fs.path.join(a, &.{ tree, "share" });
    const data_dirs = if (init.minimal.environ.getPosix("XDG_DATA_DIRS")) |old|
        try std.fmt.allocPrintSentinel(a, "{s}:{s}", .{ share, old }, 0)
    else
        try a.dupeSentinel(u8, share, 0);
    _ = setenv("XDG_DATA_DIRS", data_dirs, 1);
}

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());

    var frames_limit: ?u32 = null;
    var use_java = false;
    var fullscreen = false;
    var apk: ?[]const u8 = null;
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
            var lib = try std.DynLib.open("zig-out/lib/libandroid_platform.so");
            const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
            const hwui = try lookupHwuiBridges(&lib);
            var rt = try art.Runtime.create(init.gpa, init.io, init.minimal.environ, .{
                .classpath = &.{"zig-out/framework/framework.jar"},
                .check_jni = init.minimal.environ.getPosix("AZK_CHECK_JNI") != null,
            });
            defer rt.releaseProcessState();
            if (init.minimal.environ.getPosix("AZK_NO_REG") == null) {
                if (register(@ptrCast(rt.env)) != 0) return error.JniRegistrationFailed;
                try host_natives.register(@ptrCast(rt.env), hwui);
            }
            const cls_name = init.minimal.environ.getPosix("AZK_TEST_CLASS") orelse "dev.artzig.TestMain";
            try rt.runMain(cls_name, args[i + 1 ..]);
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
    return javaMain(init, frames_limit, apk, fullscreen);
}

// ---------------------------------------------------------------------------
// Java drives the real android.graphics stack. HWUI presents directly through
// EGL; there is deliberately no CPU/Bitmap window-present path.

const RegisterFn = *const fn (env: *anyopaque) callconv(.c) c_int;
const ConfigureHostFn = *const fn (proxy: *anyopaque, display: ?*anyopaque, surface: ?*anyopaque, width: c_int, height: c_int) callconv(.c) void;
const DestroyHostFn = *const fn (proxy: *anyopaque) callconv(.c) void;

fn lookupHwuiBridges(lib: *std.DynLib) !host_natives.HwuiBridges {
    return .{
        .configure = lib.lookup(ConfigureHostFn, "azk_hwui_configure_host_surface") orelse return error.SymbolNotFound,
        .destroy = lib.lookup(DestroyHostFn, "azk_hwui_destroy_host_surface") orelse return error.SymbolNotFound,
    };
}
const HardwareDrawAbi = enum { physical, scaled, legacy };

fn javaMain(init: std.process.Init, frames_limit: ?u32, apk: ?[]const u8, fullscreen: bool) !void {
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const tree = try installTree(init);

    // Running an APK: entry class ApkHost, paths from the install tree.
    if (apk) |path| {
        const abs = if (std.fs.path.isAbsolute(path)) path else try std.fs.path.join(arena, &.{ init.minimal.environ.getPosix("PWD") orelse ".", path });
        _ = setenv("ARTZIG_APK", try arena.dupeSentinel(u8, abs, 0), 1);
    }
    if (init.minimal.environ.getPosix("ARTZIG_FRAMEWORK_RES") == null) {
        _ = setenv("ARTZIG_FRAMEWORK_RES", try std.fs.path.joinZ(arena, &.{ tree, "res", "framework-res.apk" }), 1);
    }
    // (art.Runtime reads the startup environment snapshot, so pass the root
    // explicitly rather than via setenv)
    const runtime_root = try locateRuntimeRoot(init, tree);
    if (runtime_root) |root| std.debug.print("azk: ART runtime: {s}\n", .{root});
    const libart_overlay = try std.fs.path.join(arena, &.{ tree, "lib", "art", "libart.so" });
    const runtime_overlay_dir = try std.fs.path.join(arena, &.{ tree, "lib", "art" });
    if (apk) |path| {
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
    const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
    const hwui = try lookupHwuiBridges(&lib);

    // System fonts: unless the user points ARTZIG_FONT_FALLBACK_XML at a
    // hand-made config, generate one from fontconfig (must precede ART boot:
    // SystemFonts reads the env at class init).
    if (init.minimal.environ.getPosix("ARTZIG_FONT_FALLBACK_XML") == null) {
        const t0 = monotonicMs();
        defer std.debug.print("azk: fonts: fontconfig query took {d} ms\n", .{monotonicMs() - t0});
        if (fonts.generate(gpa, init.minimal.environ)) |xml| {
            defer gpa.free(xml);
            _ = setenv("ARTZIG_FONT_FALLBACK_XML", xml, 1);
            _ = setenv("ARTZIG_FONTS_XML", xml, 1);
            _ = setenv("ARTZIG_FONT_DIR", "/", 1);
        } else {
            std.debug.print("azk: fonts: fontconfig query failed; set ARTZIG_FONT_FALLBACK_XML\n", .{});
        }
    }

    // Boot ART with the multidex framework jar.
    const rt_v = try art.Runtime.create(gpa, init.io, init.minimal.environ, .{
        .classpath = &.{try std.fs.path.join(arena, &.{ tree, "framework", "framework.jar" })},
        .library_path = &.{runtime_overlay_dir},
        .root = runtime_root,
        .libart_path = libart_overlay,
        .bionic_lib_dir = runtime_overlay_dir,
        .use_bionic_loader = apk != null,
    });
    var rt = rt_v;
    _ = &rt;
    defer rt.releaseProcessState();
    const env = rt.env;
    if (register(@ptrCast(env)) != 0) return error.JniRegistrationFailed;
    try host_natives.register(@ptrCast(env), hwui);

    const e = art.Env{ .env = env };
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
    const hover: ?art.jni.jmethodID = e.getStaticMethodID(cls, "hover", "(FF)I") catch blk: {
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
    var control_android: ?test_control.Android = null;
    var control_server: ?test_control.Server = null;
    if (init.minimal.environ.getPosix("AZK_TEST_SOCKET")) |address| {
        control_android = try test_control.Android.init(env, cls);
        control_server = try test_control.Server.init(gpa, address);
        std.debug.print("azk test control: {s}\n", .{address});
    }
    defer if (control_server) |*server| server.deinit();
    var button_held = false;
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

    var init_w: i32 = 800;
    var init_h: i32 = 600;
    if (init.minimal.environ.getPosix("AZK_SIZE")) |spec| {
        var it = std.mem.tokenizeScalar(u8, spec, 'x');
        init_w = try std.fmt.parseInt(i32, it.next() orelse "800", 10);
        init_h = try std.fmt.parseInt(i32, it.next() orelse "600", 10);
    }
    const win = try wayland.Window.initOpts(gpa, init_w, init_h, "androlinzberg", fullscreen);
    defer win.deinit();
    if (control_server) |*server| {
        if (win.testInputAvailable()) {
            if (win.prepareTestInput()) {
                server.setInput(.{
                    .context = win,
                    .tap_fn = controlTap,
                    .type_fn = controlType,
                    .key_fn = controlKey,
                    .fullscreen_fn = controlFullscreen,
                });
            } else |err| {
                std.debug.print("azk test input unavailable: {}\n", .{err});
            }
        }
    }
    host_natives.bindWayland(@ptrCast(win.display), @ptrCast(win.surface));
    video_layers.register(gpa, init.io, win, @ptrCast(env), draw_hardware_abi == .physical) catch |err| {
        std.debug.print("video layers unavailable: {}\n", .{err});
    };
    defer video_layers.deinit();
    defer if (shutdown) |mid| jenv_call_void(env, cls, mid);
    var frame_count: u32 = 0;
    var presented_frame_count: u64 = 0;
    const trace_frame_pacing = init.minimal.environ.getPosix("ARTZIG_FRAME_PACING_TRACE") != null;
    var callback_samples: u32 = 0;
    var callback_last_ns: i128 = 0;
    var callback_total_ns: i128 = 0;
    var callback_max_ns: i128 = 0;
    var app_fullscreen = false;
    try javaFrame(win, env, cls, draw_hardware, draw_hardware_abi);
    try syncAppFullscreen(win, env, cls, wants_fullscreen, &app_fullscreen);
    frame_count += 1;
    if (control_server) |*server| if (control_android) |*android| {
        server.tick(android, frame_count, presented_frame_count);
    };

    outer: while (true) {
        const events = try win.waitEvents();
        var need_frame = false;
        for (events) |ev| switch (ev) {
            .close => break :outer,
            .configure, .fullscreen_changed => need_frame = true,
            .frame_done => {
                presented_frame_count += 1;
                need_frame = true;
                if (trace_frame_pacing) {
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
        for (events) |ev| switch (ev) {
            .fullscreen_changed => |actual_fullscreen| if (host_fullscreen_changed) |mid| {
                env.*.*.CallStaticVoidMethod.?(env, cls, mid, @as(art.jni.jboolean, @intFromBool(actual_fullscreen)));
                try e.check();
            },
            .pointer_motion => |m| if (button_held) {
                if (control_server) |*server| server.noteInput();
                if (motion) |mid| jenv_call_motion(env, cls, mid, 2, inputX(win, draw_hardware_abi, m.x), inputY(win, draw_hardware_abi, m.y));
            } else if (hover) |mid| {
                if (control_server) |*server| server.noteInput();
                const icon = jenv_call_hover(env, cls, mid, inputX(win, draw_hardware_abi, m.x), inputY(win, draw_hardware_abi, m.y));
                win.setCursorShape(cursorShapeFromPointerIcon(icon));
            },
            .pointer_button => |b| switch (b.button) {
                0x110 => if (motion) |mid| { // BTN_LEFT
                    if (control_server) |*server| server.noteInput();
                    button_held = b.pressed;
                    jenv_call_motion(env, cls, mid, if (b.pressed) 0 else 1, inputX(win, draw_hardware_abi, b.x), inputY(win, draw_hardware_abi, b.y));
                    // A click can change Android window flags without producing a Wayland event.
                    need_frame = true;
                },
                0x113, 0x116 => if (b.pressed) { // BTN_SIDE / BTN_BACK
                    if (back) |mid| jenv_call_void(env, cls, mid);
                },
                else => {},
            },
            .key => |k| {
                if (control_server) |*server| server.noteInput();
                if (k.pressed and k.keysym == 0xffc8) {
                    win.toggleFullscreen(); // XKB_KEY_F11
                } else if (key) |mid| {
                    const android_code = androidKeyFromKeysym(k.keysym);
                    if (android_code != 0 or k.unicode >= 32) {
                        jenv_call_key(env, cls, mid, if (k.pressed) 0 else 1, android_code, @intCast(k.unicode));
                    }
                }
            },
            .pointer_axis => |a| if (scroll) |mid| {
                if (control_server) |*server| server.noteInput();
                // wayland: ~15 units per wheel detent, positive = content down.
                // android: AXIS_VSCROLL positive = scroll up, 1.0 per detent.
                jenv_call_scroll(env, cls, mid, inputX(win, draw_hardware_abi, win.pointer_x), inputY(win, draw_hardware_abi, win.pointer_y), @floatCast(-a.horizontal / 15.0), @floatCast(-a.vertical / 15.0));
            },
            else => {},
        };
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
        if (need_frame) {
            javaFrame(win, env, cls, draw_hardware, draw_hardware_abi) catch |err| {
                // a broken frame (pending Java exception, etc.) must not kill the app
                std.debug.print("azk: frame error: {} (continuing)\n", .{err});
            };
            syncAppFullscreen(win, env, cls, wants_fullscreen, &app_fullscreen) catch |err| {
                std.debug.print("azk: fullscreen state error: {} (continuing)\n", .{err});
            };
            frame_count += 1;
            if (frames_limit) |lim| {
                if (frame_count >= lim) break;
            }
        }
        if (control_server) |*server| if (control_android) |*android| {
            server.tick(android, frame_count, presented_frame_count);
        };
        video_layers.process();
    }
    std.debug.print("androlinzberg: exiting after {d} java frames\n", .{frame_count});
}

fn javaFrame(win: *wayland.Window, env: anytype, cls: art.jni.jclass, draw_hardware: art.jni.jmethodID, draw_hardware_abi: HardwareDrawAbi) !void {
    const e = art.Env{ .env = env };
    const jenv = env.*.*;
    try win.requestFrame();
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

fn syncAppFullscreen(win: *wayland.Window, env: anytype, cls: art.jni.jclass, method: ?art.jni.jmethodID, previous: *bool) !void {
    const mid = method orelse return;
    const fullscreen = env.*.*.CallStaticBooleanMethod.?(env, cls, mid) != 0;
    try (art.Env{ .env = env }).check();
    if (fullscreen == previous.*) return;
    previous.* = fullscreen;
    win.setFullscreen(fullscreen);
}

fn inputX(win: *const wayland.Window, abi: HardwareDrawAbi, x: f64) f32 {
    return @floatCast(if (abi == .physical) win.surfaceToBufferX(x) else x);
}

fn inputY(win: *const wayland.Window, abi: HardwareDrawAbi, y: f64) f32 {
    return @floatCast(if (abi == .physical) win.surfaceToBufferY(y) else y);
}

fn controlTap(context: *anyopaque, x: i32, y: i32) !void {
    try @as(*wayland.Window, @ptrCast(@alignCast(context))).testTap(x, y);
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

/// android.view.PointerIcon.TYPE_* -> wp_cursor_shape_device_v1.shape
fn cursorShapeFromPointerIcon(icon: i32) u32 {
    return switch (icon) {
        1001 => 2, // CONTEXT_MENU
        1002 => 4, // HAND -> pointer
        1003 => 3, // HELP
        1004 => 6, // WAIT
        1006 => 7, // CELL
        1007 => 8, // CROSSHAIR
        1008 => 9, // TEXT
        1009 => 10, // VERTICAL_TEXT
        1010 => 11, // ALIAS
        1011 => 12, // COPY
        1012 => 14, // NO_DROP
        1013 => 32, // ALL_SCROLL
        1014 => 26, // HORIZONTAL_DOUBLE_ARROW -> ew_resize
        1015 => 27, // VERTICAL_DOUBLE_ARROW -> ns_resize
        1016 => 28, // TOP_RIGHT_DIAGONAL_DOUBLE_ARROW -> nesw_resize
        1017 => 29, // TOP_LEFT_DIAGONAL_DOUBLE_ARROW -> nwse_resize
        1018 => 33, // ZOOM_IN
        1019 => 34, // ZOOM_OUT
        1020 => 16, // GRAB
        1021 => 17, // GRABBING
        else => 1, // ARROW / NULL / CUSTOM -> default
    };
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
