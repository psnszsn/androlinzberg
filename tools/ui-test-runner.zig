//! Host orchestrator of the UI tests that run on ART (build/ui_tests.zig).
//!
//! An ART process authenticates to one session's broker when it starts, so each
//! test gets its own pair of processes: a fresh session running the app under
//! test, and a runner that joins it as the broker's inspector role and executes
//! one JUnit method through dev.artzig.testing.HostTestRunner. The compositor
//! comes from the caller (tools/shot.sh --runner).
//!
//! A test named `probe#<check>` runs the native presenter probe
//! (tests/ui/probe) against the session instead of an ART runner.
//!
//! A test is `Class#method[@WxH[@apk]]`: its session's launch size and the
//! name of an APK to run instead of the default one, either built by the
//! repository (`--named-apk`) or supplied by the operator (`--pinned-apk`).
//!
//! Usage: ui-test-runner --launcher <androlinzberg> --jvm-runner <azk-jvm-test> --apk <apk> --dex <ui-tests.jar>
//!            --probe <presenter-probe> --artifacts <dir> [--size WxH]
//!            [--seat <wayland-surface-server>]
//!            [--named-apk <name>=<path>]...
//!            [--pinned-apk <name>=<ENVIRONMENT VARIABLE>=<sha256>]...
//!            [--class <suffix>] [--method <name>] <Class#method>...
const std = @import("std");
const Io = std.Io;

const session_timeout_ms = 120_000;
const test_timeout: Io.Duration = .{ .nanoseconds = 300 * std.time.ns_per_s };
const session_marker = "azk: binder session: ";

const Options = struct {
    launcher: []const u8 = "",
    jvm_runner: []const u8 = "",
    apk: []const u8 = "",
    dex: []const u8 = "",
    probe: []const u8 = "",
    /// Started with `--keyboard-seat` for each session: a desktop compositor
    /// grants keyboard focus only once a real keyboard device exists.
    seat: ?[]const u8 = null,
    artifacts: []const u8 = "",
    size: []const u8 = "1280x720",
    class: ?[]const u8 = null,
    method: ?[]const u8 = null,
};

/// An APK supplied by the operator (tests/apks.json): found through an
/// environment variable and accepted only with the pinned SHA-256.
const PinnedApk = struct { name: []const u8, environment: []const u8, sha256: []const u8 };

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    var options: Options = .{};
    var tests: std.ArrayList([]const u8) = .empty;
    var pinned: std.ArrayList(PinnedApk) = .empty;
    var named: std.ArrayList([2][]const u8) = .empty;
    var index: usize = 1;
    while (index < args.len) : (index += 1) {
        const arg = args[index];
        const value: ?*[]const u8 = if (std.mem.eql(u8, arg, "--launcher")) &options.launcher else if (std.mem.eql(u8, arg, "--jvm-runner")) &options.jvm_runner else if (std.mem.eql(u8, arg, "--apk")) &options.apk else if (std.mem.eql(u8, arg, "--dex")) &options.dex else if (std.mem.eql(u8, arg, "--probe")) &options.probe else if (std.mem.eql(u8, arg, "--artifacts")) &options.artifacts else if (std.mem.eql(u8, arg, "--size")) &options.size else null;
        if (value) |destination| {
            index += 1;
            if (index == args.len) return error.MissingOptionValue;
            destination.* = args[index];
        } else if (std.mem.eql(u8, arg, "--seat")) {
            index += 1;
            if (index == args.len) return error.MissingOptionValue;
            options.seat = args[index];
        } else if (std.mem.eql(u8, arg, "--named-apk")) {
            index += 1;
            if (index == args.len) return error.MissingOptionValue;
            const equals = std.mem.indexOfScalar(u8, args[index], '=') orelse return error.InvalidNamedApk;
            try named.append(arena, .{ args[index][0..equals], args[index][equals + 1 ..] });
        } else if (std.mem.eql(u8, arg, "--pinned-apk")) {
            index += 1;
            if (index == args.len) return error.MissingOptionValue;
            var fields = std.mem.splitScalar(u8, args[index], '=');
            try pinned.append(arena, .{
                .name = fields.next() orelse return error.InvalidPinnedApk,
                .environment = fields.next() orelse return error.InvalidPinnedApk,
                .sha256 = fields.next() orelse return error.InvalidPinnedApk,
            });
        } else if (std.mem.eql(u8, arg, "--class") or std.mem.eql(u8, arg, "--method")) {
            index += 1;
            if (index == args.len) return error.MissingOptionValue;
            if (std.mem.eql(u8, arg, "--class")) options.class = args[index] else options.method = args[index];
        } else try tests.append(arena, arg);
    }
    if (options.launcher.len == 0 or options.jvm_runner.len == 0 or options.apk.len == 0 or options.dex.len == 0 or options.artifacts.len == 0)
        return error.MissingRequiredOption;

    var run: usize = 0;
    var failed: usize = 0;
    for (tests.items) |name| {
        const separator = std.mem.indexOfScalar(u8, name, '#') orelse return error.InvalidTestName;
        const class = name[0..separator];
        const size_at = std.mem.indexOfScalar(u8, name, '@');
        const method = name[separator + 1 .. size_at orelse name.len];
        var test_options = options;
        var apk_name: ?[]const u8 = null;
        if (size_at) |at| {
            var fields = std.mem.splitScalar(u8, name[at + 1 ..], '@');
            test_options.size = fields.next().?;
            apk_name = fields.next();
        }
        if (options.class) |wanted| if (!std.mem.endsWith(u8, class, wanted)) continue;
        if (options.method) |wanted| if (!std.mem.eql(u8, method, wanted)) continue;
        run += 1;
        const passed = passed: {
            const built = for (named.items) |candidate| {
                if (apk_name != null and std.mem.eql(u8, candidate[0], apk_name.?)) break candidate[1];
            } else null;
            if (built) |path| test_options.apk = path else if (apk_name) |wanted| test_options.apk = pinnedApk(init, arena, pinned.items, wanted) catch |failure| {
                std.debug.print("ui-test: {s}: APK {s}: {s}\n", .{ name, wanted, @errorName(failure) });
                break :passed false;
            };
            break :passed runTest(init, test_options, class, method);
        } catch |failure| passed: {
            std.debug.print("ui-test: {s}: {s}\n", .{ name, @errorName(failure) });
            break :passed false;
        };
        std.debug.print("ui-test: {s} {s}\n", .{ if (passed) "PASS" else "FAIL", name });
        if (!passed) failed += 1;
    }
    std.debug.print("ui-test: {d} run, {d} failed\n", .{ run, failed });
    if (run == 0) return error.NoTestsSelected;
    if (failed != 0) return error.UiTestsFailed;
}

fn pinnedApk(init: std.process.Init, arena: std.mem.Allocator, pinned: []const PinnedApk, name: []const u8) ![]const u8 {
    const apk = for (pinned) |candidate| {
        if (std.mem.eql(u8, candidate.name, name)) break candidate;
    } else return error.UnknownPinnedApk;
    const path = init.minimal.environ.getPosix(apk.environment) orelse return error.PinnedApkEnvironmentNotSet;
    if (!std.fs.path.isAbsolute(path)) return error.PinnedApkPathMustBeAbsolute;
    const bytes = try Io.Dir.cwd().readFileAlloc(init.io, path, arena, .limited(1024 * 1024 * 1024));
    var digest: [32]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(bytes, &digest, .{});
    const hex = try std.fmt.allocPrint(arena, "{x}", .{&digest});
    if (!std.mem.eql(u8, hex, apk.sha256)) return error.PinnedApkChecksumMismatch;
    return path;
}

fn runTest(init: std.process.Init, options: Options, class: []const u8, method: []const u8) !bool {
    const io = init.io;
    const gpa = init.gpa;
    var arena_state: std.heap.ArenaAllocator = .init(gpa);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    // Retained per test: the session's log and the runner's log.
    const directory = try std.fmt.allocPrint(arena, "{s}/{s}.{s}", .{ options.artifacts, class, method });
    Io.Dir.cwd().deleteTree(io, directory) catch {};
    try Io.Dir.cwd().createDirPath(io, directory);
    const app_log = try std.fs.path.join(arena, &.{ directory, "app.log" });
    const test_log = try std.fs.path.join(arena, &.{ directory, "test.log" });

    var random: [8]u8 = undefined;
    io.random(&random);
    const work = try std.fmt.allocPrint(arena, "/tmp/azk-ui-test-{x}", .{&random});
    try Io.Dir.createDirAbsolute(io, work, @fromBackingInt(@intCast(0o700)));
    defer Io.Dir.cwd().deleteTree(io, work) catch {};
    const data = try std.fs.path.join(arena, &.{ work, "data" });
    const native_libraries = try std.fs.path.join(arena, &.{ work, "native-libs" });
    try Io.Dir.createDirAbsolute(io, data, @fromBackingInt(@intCast(0o700)));
    try Io.Dir.createDirAbsolute(io, native_libraries, @fromBackingInt(@intCast(0o700)));

    var session_env = try init.minimal.environ.createMap(arena);
    // The session admits an inspector and leaves the one UiAutomation
    // registration to the runner.
    try session_env.put("AZK_TEST_INSPECTION", "1");
    try session_env.put("AZK_TEST_RUNNER_AUTOMATION", "1");
    // app.log is the session's complete log; the launcher's default shows
    // warnings only.
    if (session_env.get("AZK_LOG_LEVEL") == null) try session_env.put("AZK_LOG_LEVEL", "v");
    // Cage's single kiosk window fills the output. Host-path tests address
    // Wayland input by Android coordinates, which the presenter projects with
    // this placement; core Wayland exposes no global toplevel position.
    const desktop = std.mem.eql(u8, init.minimal.environ.getPosix("SHOT_COMPOSITOR") orelse "", "labwc");
    try session_env.put("AZK_TEST_WINDOW_PLACEMENT", try placement(init, arena, options.size, desktop));
    // A kiosk session takes its launch size from the test; a desktop session
    // leaves window geometry to the compositor and the task organizer.
    if (!desktop) try session_env.put("AZK_SIZE", options.size);
    try session_env.put("ARTZIG_DATA_DIR", data);
    try session_env.put("ARTZIG_NATIVE_LIB_DIR", native_libraries);

    // The kiosk compositor's output is the launch size (as the task placement
    // above assumes); AZK_HEADLESS_OUTPUT names it when the harness made one.
    if (desktop) {
        // The desktop configuration owns its outputs and window placement.
    } else if (init.minimal.environ.getPosix("AZK_HEADLESS_OUTPUT")) |output| {
        const mode = try std.process.run(gpa, io, .{ .argv = &.{ "wlr-randr", "--output", output, "--custom-mode", options.size } });
        defer gpa.free(mode.stdout);
        defer gpa.free(mode.stderr);
        if (mode.term != .exited or mode.term.exited != 0) return error.HeadlessOutputModeFailed;
    }

    var seat: ?std.process.Child = null;
    defer if (seat) |*helper| {
        if (helper.stdin) |stdin| stdin.close(io);
        helper.stdin = null;
        helper.kill(io);
    };
    if (options.seat) |helper| {
        seat = try std.process.spawn(io, .{ .argv = &.{ helper, "--keyboard-seat" }, .stdin = .pipe, .stdout = .pipe, .stderr = .inherit });
        var line: [16]u8 = undefined;
        const count = try seat.?.stdout.?.readStreaming(io, &.{&line});
        if (!std.mem.startsWith(u8, line[0..count], "ready")) return error.KeyboardSeatNotReady;
    }

    // The shell only redirects and then becomes the launcher, so the child's
    // pid and stdin are the session's.
    (try Io.Dir.cwd().createFile(io, app_log, .{})).close(io);
    try session_env.put("AZK_UI_TEST_SESSION_LOG", app_log);
    var session = try std.process.spawn(io, .{
        .argv = &.{ "sh", "-c", "exec \"$0\" \"$@\" >\"$AZK_UI_TEST_SESSION_LOG\" 2>&1", options.launcher, options.apk },
        .environ_map = &session_env,
        .stdin = .pipe,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    var session_running = true;
    defer if (session_running) {
        if (session.stdin) |stdin| stdin.close(io);
        session.stdin = null;
        session.kill(io);
    };

    const session_dir = try awaitSession(io, arena, app_log);

    var runner_env = try init.minimal.environ.createMap(arena);
    try runner_env.put("AZK_BINDER_ROLE", "inspector");
    try runner_env.put("AZK_TEST_CLASS", "dev.artzig.testing.HostTestRunner");
    try runner_env.put("AZK_TEST_DEX", options.dex);
    // Host-path tests also assert on what the session's native services logged.
    try runner_env.put("AZK_TEST_SESSION_LOG", try Io.Dir.cwd().realPathFileAlloc(io, app_log, arena));
    // A host-path test may start a further process that joins the session.
    try runner_env.put("AZK_TEST_LAUNCHER", try Io.Dir.cwd().realPathFileAlloc(io, options.launcher, arena));
    try runner_env.put("AZK_TEST_SESSION_DIR", session_dir);
    // The APK the session launched, for a host-path test's joined launcher.
    try runner_env.put("AZK_TEST_SESSION_APK", try Io.Dir.cwd().realPathFileAlloc(io, options.apk, arena));
    if (options.seat) |helper| try runner_env.put("AZK_TEST_SEAT", try Io.Dir.cwd().realPathFileAlloc(io, helper, arena));
    const argv: []const []const u8 = if (std.mem.eql(u8, class, "probe"))
        &.{ options.probe, session_dir, method }
    else
        &.{ options.jvm_runner, "--join", session_dir, "dev.artzig.uitest.UiTest", class, method };
    const result = try std.process.run(gpa, io, .{
        .argv = argv,
        .environ_map = &runner_env,
        .stdout_limit = .limited(64 * 1024 * 1024),
        .stderr_limit = .limited(64 * 1024 * 1024),
        .timeout = .{ .duration = .{ .clock = .awake, .raw = test_timeout } },
    });
    defer gpa.free(result.stdout);
    defer gpa.free(result.stderr);
    const output = try Io.Dir.cwd().createFile(io, test_log, .{});
    defer output.close(io);
    try output.writeStreamingAll(io, result.stdout);
    try output.writeStreamingAll(io, result.stderr);
    const test_passed = switch (result.term) {
        .exited => |code| code == 0,
        else => false,
    };

    // Closing stdin lets Session.stop keep its system -> presenter ->
    // SurfaceFlinger -> broker order; a session that ignores it is killed.
    if (session.stdin) |stdin| stdin.close(io);
    session.stdin = null;
    var waited: u32 = 0;
    while (waited < 10_000 and !exited(io, arena, session.id.?)) : (waited += 50) {
        // A desktop session lives until its last task window closes. The seat
        // sends the compositor's close shortcut to each focused window in turn.
        if (seat) |helper| if (waited % 500 == 0) helper.stdin.?.writeStreamingAll(io, "c") catch {};
        try Io.sleep(io, .fromMilliseconds(50), .awake);
    }
    session.kill(io);
    session_running = false;

    // Every session must end without leaked graphics buffers or fatal output.
    const log = try Io.Dir.cwd().readFileAlloc(io, app_log, arena, .limited(256 * 1024 * 1024));
    // Only a desktop session presents task windows through the presenter.
    var clean = !desktop or std.mem.indexOf(u8, log, "task presenter released all buffers") != null;
    if (!clean) std.debug.print("ui-test: {s}#{s}: the task presenter did not report releasing its buffers\n", .{ class, method });
    for ([_][]const u8{ "Native thread exited without calling DetachCurrentThread", "FATAL EXCEPTION", "Unhandled exception", "panic:" }) |fatal| {
        if (std.mem.indexOf(u8, log, fatal) == null) continue;
        std.debug.print("ui-test: {s}#{s}: session log contains \"{s}\"\n", .{ class, method, fatal });
        clean = false;
    }
    return test_passed and clean;
}

/// A child that has exited is a zombie until it is reaped.
fn exited(io: Io, arena: std.mem.Allocator, pid: std.process.Child.Id) bool {
    const path = std.fmt.allocPrint(arena, "/proc/{d}/stat", .{pid}) catch return false;
    const stat = Io.Dir.cwd().readFileAlloc(io, path, arena, .limited(4096)) catch return true;
    const after_name = std.mem.lastIndexOfScalar(u8, stat, ')') orelse return false;
    return after_name + 2 < stat.len and stat[after_name + 2] == 'Z';
}

/// Where the task's window is on its output, which Android cannot discover.
/// The kiosk compositor fills an output of the launch size; the desktop test
/// configuration places the window at the origin of its own output, whose size
/// the compositor's screenshot reports.
fn placement(init: std.process.Init, arena: std.mem.Allocator, size: []const u8, desktop: bool) ![]const u8 {
    if (!desktop) {
        const separator = std.mem.indexOfScalar(u8, size, 'x') orelse return error.InvalidSize;
        return std.fmt.allocPrint(arena, "0,0,{s},{s}", .{ size[0..separator], size[separator + 1 ..] });
    }
    const capture = try std.process.run(init.gpa, init.io, .{ .argv = &.{ "grim", "-t", "ppm", "-" }, .stdout_limit = .limited(256 * 1024 * 1024) });
    defer init.gpa.free(capture.stdout);
    defer init.gpa.free(capture.stderr);
    var lines = std.mem.splitScalar(u8, capture.stdout, '\n');
    if (!std.mem.eql(u8, lines.next() orelse "", "P6")) return error.InvalidOutputCapture;
    var dimensions = std.mem.splitScalar(u8, lines.next() orelse return error.InvalidOutputCapture, ' ');
    const width = dimensions.next() orelse return error.InvalidOutputCapture;
    const height = dimensions.next() orelse return error.InvalidOutputCapture;
    return std.fmt.allocPrint(arena, "0,0,{s},{s}", .{ width, height });
}

/// Session processes share the log without line locking, so another record
/// can follow the directory on its line. The name ends `binder-<pid>-<16 hex>`.
fn sessionDirectory(line: []const u8) []const u8 {
    const name = std.mem.lastIndexOf(u8, line, "/binder-") orelse return line;
    var end = name + "/binder-".len;
    while (end < line.len and std.ascii.isDigit(line[end])) end += 1;
    if (end >= line.len or line[end] != '-') return line;
    return line[0..@min(line.len, end + 1 + 16)];
}

/// The launcher prints its session directory once the broker is up; the session
/// is joinable when `session.json` exists there.
fn awaitSession(io: Io, arena: std.mem.Allocator, app_log: []const u8) ![]const u8 {
    var waited: u32 = 0;
    while (waited < session_timeout_ms) : (waited += 100) {
        const text = Io.Dir.cwd().readFileAlloc(io, app_log, arena, .limited(64 * 1024 * 1024)) catch "";
        if (std.mem.lastIndexOf(u8, text, session_marker)) |start| {
            const rest = text[start + session_marker.len ..];
            if (std.mem.indexOfScalar(u8, rest, '\n')) |end| {
                const directory = sessionDirectory(rest[0..end]);
                const info = try std.fs.path.join(arena, &.{ directory, "session.json" });
                if (Io.Dir.cwd().access(io, info, .{})) |_| return directory else |_| {}
            }
        }
        try Io.sleep(io, .fromMilliseconds(100), .awake);
    }
    return error.SessionNotReady;
}
