//! Host-path checks that need no app state: the Wayland task presenter's own
//! accounting, read by a native inspector through the Zig generated from
//! IWaylandTaskPresenter.aidl. Run by tools/ui-test-runner.zig against a live
//! session, beside the UiAutomator tests that cover app-visible behaviour.
//!
//! Usage: presenter-probe <session directory> <check>
const std = @import("std");
const Io = std.Io;
const aidl = @import("aidl");
const Presenter = @import("task_presenter_aidl").@"dev.artzig.window.IWaylandTaskPresenter";

extern fn AServiceManager_waitForService(instance: [*:0]const u8) callconv(.c) ?*aidl.AIBinder;
extern fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;

const SessionInfo = struct { socket: []const u8, inspector_cookie: ?[]const u8 = null, prop_area: []const u8 };

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len != 3) return error.InvalidArguments;

    // Join as the broker's inspector: libbinder_ndk reads its connection from
    // the environment when the first Binder call is made.
    const path = try std.fs.path.join(arena, &.{ args[1], "session.json" });
    const text = try Io.Dir.cwd().readFileAlloc(io, path, arena, .limited(4096));
    const info = try std.json.parseFromSliceLeaky(SessionInfo, arena, text, .{ .ignore_unknown_fields = true });
    const cookie = info.inspector_cookie orelse return error.SessionDoesNotPermitInspection;
    _ = setenv("AZK_BINDER_SOCKET", try arena.dupeSentinel(u8, info.socket, 0), 1);
    _ = setenv("AZK_BINDER_COOKIE", try arena.dupeSentinel(u8, cookie, 0), 1);
    _ = setenv("AZK_BINDER_PACKAGE", "com.android.shell", 1);
    _ = setenv("AZK_PROP_AREA", try arena.dupeSentinel(u8, info.prop_area, 0), 1);

    const presenter = try Presenter.Client.fromBinder(.adopt(AServiceManager_waitForService("dev.artzig.window.presenter") orelse return error.PresenterUnavailable));
    defer presenter.deinit();

    if (std.mem.eql(u8, args[2], "presentedFramesAdvance")) {
        try presentedFramesAdvance(io, arena, presenter);
    } else if (std.mem.eql(u8, args[2], "resourcesAreAccounted")) {
        try resourcesAreAccounted(io, arena, presenter);
    } else return error.UnknownCheck;
    std.debug.print("presenter-probe: {s} ok\n", .{args[2]});
}

/// A requested frame completes with a real Wayland frame callback:
/// observeTestFrames reports [requested, completed] rounds.
fn presentedFramesAdvance(io: Io, arena: std.mem.Allocator, presenter: Presenter.Client) !void {
    const start = try awaitTask(io, arena, presenter);
    var waited: u32 = 0;
    while (waited < 30_000) : (waited += 50) {
        const rounds = try presenter.observeTestFrames(arena, true);
        if (rounds.len != 2 or rounds[0] < rounds[1]) return error.InvalidFrameRounds;
        if (rounds[1] >= start + 3) return;
        try Io.sleep(io, .fromMilliseconds(50), .awake);
    }
    return error.FramesDidNotAdvance;
}

/// testResources reports [tasks, queues, Wayland imports, graphics buffers, descriptors].
fn resourcesAreAccounted(io: Io, arena: std.mem.Allocator, presenter: Presenter.Client) !void {
    _ = try awaitTask(io, arena, presenter);
    const resources = try presenter.testResources(arena);
    if (resources.len != 5) return error.InvalidResourceReport;
    if (resources[0] != 1 or resources[1] < 1 or resources[1] > 2) return error.UnexpectedTaskAccounting;
    if (resources[3] < 1 or resources[4] < 3 or resources[4] > 4096) return error.UnexpectedBufferAccounting;
}

/// Waits until the app's task presents; returns the completed frame rounds then.
fn awaitTask(io: Io, arena: std.mem.Allocator, presenter: Presenter.Client) !i64 {
    var waited: u32 = 0;
    while (waited < 60_000) : (waited += 100) {
        const resources = try presenter.testResources(arena);
        if (resources.len == 5 and resources[0] >= 1 and resources[3] >= 1) {
            const rounds = try presenter.observeTestFrames(arena, false);
            if (rounds.len == 2) return rounds[1];
        }
        try Io.sleep(io, .fromMilliseconds(100), .awake);
    }
    return error.TaskNotPresented;
}
