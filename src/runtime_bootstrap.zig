//! Shared ART/JNI bootstrap. Process entry points own lifecycle and supervision.
const std = @import("std");
const art = @import("art");
extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
const fonts = @import("platform/fonts.zig");
const host_natives = @import("host_natives");
const runtime_identity = @import("runtime_identity.zig");
const installTree = @import("install_tree.zig").root;
const locateRuntimeRoot = @import("install_tree.zig").runtimeRoot;
const configureNativeLibraries = @import("runtime_storage.zig").configureNativeLibraries;
const dataPartitionOptions = @import("runtime_storage.zig").dataPartitionOptions;
extern "c" fn azk_art_prepare_signals() c_int;
extern "c" fn dlopen(path: [*:0]const u8, flags: c_int) ?*anyopaque;

fn monotonicMs(io: std.Io) i64 {
    return @intCast(std.Io.Clock.awake.now(io).toMilliseconds());
}

// Let the launch/session scopes release their owned resources first. ART and
// original bufferpool workers have process lifetime, so normal libc destruction
// must not run after a runtime has been booted (the same rule as Java exit).
var runtime_process_exit: ?ProcessExitFn = null;
pub fn runWithExit(init: std.process.Init, comptime run: fn (std.process.Init) anyerror!void) !void {
    run(init) catch |err| {
        if (runtime_process_exit) |terminate| {
            std.debug.print("error: {s}\n", .{@errorName(err)});
            terminate(1);
        }
        return err;
    };
    if (runtime_process_exit) |terminate| terminate(0);
}

pub const Headless = struct {
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
    const t0 = monotonicMs(init.io);
    defer std.debug.print("azk: fonts: fontconfig query took {d} ms\n", .{monotonicMs(init.io) - t0});
    if (fonts.generate(init.gpa, init.io, init.minimal.environ)) |config| {
        defer config.deinit(init.gpa);
        _ = setenv("ARTZIG_FONT_FALLBACK_XML", config.xml, 1);
        _ = setenv("ARTZIG_FONTS_XML", config.xml, 1);
        _ = setenv("ARTZIG_FONT_DIR", config.directory, 1);
    } else {
        std.debug.print("azk: fonts: fontconfig query failed; set ARTZIG_FONT_FALLBACK_XML\n", .{});
    }
}

const ProcessExitFn = *const fn (c_int) callconv(.c) noreturn;

// std.Io has no immediate self-termination operation. Use AndroidRuntime's
// native boundary: std.process.exit runs libc destructors in a libc-linked Zig
// executable, which is incompatible with ART's live worker threads.
fn lookupProcessExit(lib: *std.DynLib) !ProcessExitFn {
    const terminate = lib.lookup(ProcessExitFn, "azk_android_runtime_exit") orelse return error.SymbolNotFound;
    runtime_process_exit = terminate;
    return terminate;
}

pub fn runFrameworkMain(runtime: *art.Runtime, class_name: []const u8, arguments: []const [:0]const u8) !void {
    const allocator = runtime.arena.allocator();
    const bootstrap_args = try allocator.alloc([:0]const u8, arguments.len + 1);
    bootstrap_args[0] = try allocator.dupeSentinel(u8, class_name, 0);
    @memcpy(bootstrap_args[1..], arguments);
    try runtime.runMain("dev.artzig.RuntimeBootstrap", bootstrap_args);
}

pub fn bootHeadless(init: std.process.Init, role: runtime_identity.Role) !Headless {
    if (azk_art_prepare_signals() != 0) return error.RuntimeSignalMaskFailed;
    const arena = init.arena.allocator();
    const tree = try installTree(init);
    const runtime_overlay_dir = try std.fs.path.join(arena, &.{ tree, "lib", "art" });
    const island_path = try std.fs.path.join(arena, &.{ tree, "lib", "libandroid_platform.so" });
    // Libcore's native dispatch must see the same platform policy callbacks
    // in system/test VMs as it does in APK VMs.
    const island_path_z = try arena.dupeSentinel(u8, island_path, 0);
    if (dlopen(island_path_z, 2 | 0x100) == null) return error.PlatformLoadFailed; // RTLD_NOW|RTLD_GLOBAL
    var lib = try std.DynLib.open(island_path);
    try bindPlatformIo(&lib, init.io);
    try initializeAndroidRuntime(&lib, init);
    const identity = try runtime_identity.prepare(init, &lib, role, null);
    const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
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
        .process_identity = identity,
        .platform_class_loader = "dev.artzig.HostedPlatformClassLoader",
        .data_partition = try dataPartitionOptions(init),
        .data_dir = if (getenv("ARTZIG_RUNTIME_CACHE_DIR")) |cache| std.mem.span(cache) else null,
        .system_root_overlay = true,
        .system_libraries = try @import("platform/system_libraries.zig").resolve(arena, init.io, tree),
        .library_path = &.{ runtime_overlay_dir, "/system/lib64" },
        .root = try locateRuntimeRoot(init, tree),
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
        host_natives.bindLooper(looper_poll);
    }
    return .{ .rt = rt, .tree = tree };
}

/// Like `bootHeadless`, but with the bionic loader and the APK's native
/// libraries configured (the `--app-process` service host): identical runtime
/// setup to the UI path minus the window, so APK NDK libraries load the same
/// way. The island is dlopened RTLD_GLOBAL so the bionic linker sees platform
/// symbols while relocating APK libraries.
pub fn bootHeadlessApk(init: std.process.Init, apk_abs: []const u8, role: runtime_identity.Role) !Headless {
    if (azk_art_prepare_signals() != 0) return error.RuntimeSignalMaskFailed;
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
    try initializeAndroidRuntime(&lib, init);
    const register = lib.lookup(RegisterFn, "azk_register_graphics_jni") orelse return error.SymbolNotFound;
    const looper_poll = lib.lookup(host_natives.LooperPollStateFn, "azk_looper_poll_state") orelse return error.SymbolNotFound;

    const identity = try runtime_identity.prepare(init, &lib, role, apk_abs);
    configureFonts(init);
    var rt = try art.Runtime.create(init.gpa, init.io, init.minimal.environ, .{
        .classpath = &.{framework_jar},
        .process_identity = identity,
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
        host_natives.bindLooper(looper_poll);
    }
    return .{ .rt = rt, .tree = tree };
}

// ---------------------------------------------------------------------------
// Native platform registration. Stock HWUI
// queues Android surfaces; only the session presenter communicates with Wayland.

const RegisterFn = *const fn (env: *anyopaque) callconv(.c) c_int;
const BindPlatformIoFn = *const fn (
    userdata: ?*anyopaque,
    vtable: *const std.Io.VTable,
) callconv(.c) void;
fn initializeAndroidRuntime(lib: *std.DynLib, init: std.process.Init) !void {
    const arguments = init.minimal.args.vector;
    if (arguments.len == 0) return error.MissingProcessArguments;
    const start = arguments[0];
    const last = arguments[arguments.len - 1];
    const length = @intFromPtr(last) + std.mem.len(last) + 1 - @intFromPtr(start);
    const initialize = lib.lookup(*const fn ([*]u8, usize) callconv(.c) c_int, "azk_android_runtime_initialize") orelse return error.SymbolNotFound;
    if (initialize(@constCast(start), length) != 0) return error.RuntimeInitializationFailed;
}

fn bindPlatformIo(lib: *std.DynLib, io: std.Io) !void {
    const bind = lib.lookup(BindPlatformIoFn, "azk_platform_bind_io") orelse
        return error.SymbolNotFound;
    bind(io.userdata, io.vtable);
}
