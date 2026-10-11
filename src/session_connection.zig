//! Connect a standalone process to its existing instance, or own a new instance.
const std = @import("std");
const binder_session = @import("binder/session.zig");
const runtime_identity = @import("runtime_identity.zig");
const image = @import("install_tree.zig");
const installTree = image.root;
const sessionStorage = @import("runtime_storage.zig").sessionStorage;
const applySessionStorage = @import("runtime_storage.zig").applySessionStorage;
const StorageView = @import("binder/StorageView.zig");
extern "c" fn azk_art_prepare_signals() c_int;
extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;

// APKs require task presentation. Headless JVM
// probes need no Wayland connection unless they explicitly request a presenter.
pub fn taskPresentation(environ: std.process.Environ, apk_launch: bool) !bool {
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
pub fn ensureSession(init: std.process.Init, binder: *?binder_session.Session, join_dir: ?[]const u8, present_tasks: bool) !void {
    if (azk_art_prepare_signals() != 0) return error.RuntimeSignalMaskFailed;
    const arena = init.arena.allocator();
    if (join_dir) |dir| {
        const info = try joinSession(init, dir);
        if (info.storage) |storage| {
            // The owner's mount view: the broker authenticates peers by user namespace.
            try StorageView.join(init.io, arena, init.minimal.environ, info.pid);
            try applySessionStorage(init, storage);
        }
        _ = setenv(binder_session.socket_env, try arena.dupeSentinel(u8, info.socket, 0), 1);
        if (runtime_identity.isInspector(init.minimal.environ)) {
            // A host test tool: the broker admits it as Android's shell uid and
            // shows it only the services UiAutomation uses.
            const cookie = info.inspector_cookie orelse return error.SessionDoesNotPermitInspection;
            _ = setenv(binder_session.cookie_env, try arena.dupeSentinel(u8, cookie, 0), 1);
            _ = setenv("AZK_BINDER_PACKAGE", "com.android.shell", 1);
        } else if (init.minimal.environ.getPosix("AZK_LAUNCH_CREDENTIAL") == null)
            _ = setenv(binder_session.cookie_env, try arena.dupeSentinel(u8, info.cookie, 0), 1);
        _ = setenv(binder_session.prop_area_env, try arena.dupeSentinel(u8, info.prop_area, 0), 1);
        _ = setenv("AZK_BINDER_SESSION_DIR", try arena.dupeSentinel(u8, dir, 0), 1);
        std.debug.print("azk: joined binder session: {s}\n", .{dir});
        return;
    }
    if (init.minimal.environ.getPosix(binder_session.socket_env) != null) return;
    const tree = try installTree(init);
    const broker_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-binder-broker" });
    const launcher_exe = try std.fs.path.join(arena, &.{ tree, "bin", "androlinzberg" });
    const storage = try sessionStorage(init);
    // Every session process inherits /storage/emulated/0 from here.
    try StorageView.enter(init.io, arena, init.minimal.environ, storage.external_storage);
    // Exports the storage environment, including the view marker.
    try applySessionStorage(init, storage);
    binder.* = try binder_session.Session.start(init.gpa, init.io, .{
        .broker_exe = broker_exe,
        .launcher_exe = launcher_exe,
        .system_exe = try image.executable(init, "azk-system-server"),
        .system_args = &.{},
        .app_exe = try image.executable(init, "azk-app-process"),
        .surfaceflinger_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-surfaceflinger" }),
        .media_service_exe = try std.fs.path.join(arena, &.{ tree, "bin", "azk-media-service" }),
        .platform_root = tree,
        .declared_services = &.{ "android.hardware.media.c2.IComponentStore/software", "android.hardware.media.c2.IComponentStore/default" },
        .task_presenter_exe = if (present_tasks)
            try std.fs.path.join(arena, &.{ tree, "bin", "azk-task-presenter" })
        else
            null,
        .environ = init.minimal.environ,
        .storage = storage,
        .data_lock = try binder_session.lockDataRoot(init.io, storage.application_data),
        .inspection = std.mem.eql(u8, init.minimal.environ.getPosix("AZK_TEST_INSPECTION") orelse "", "1"),
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
