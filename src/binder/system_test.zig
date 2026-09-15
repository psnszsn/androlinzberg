//! Multi-process hosted system-service test driver.
//!
//! Usage: azk-system-test <androlinzberg> <azk-binder-broker> <test-a.apk> <test-b.apk> <ndk-client> <identity-a.apk> <identity-b.apk> <surfaceflinger> <service-fixture.apk> <media-service> <media-probe>
//!
//! Starts the broker, SurfaceFlinger, system and native media processes, then two ART app peers: package
//! `dev.artzig.test.b` hosting a real `android.app.Service` through the
//! session's activity manager, and package `dev.artzig.test.a` binding it
//! across processes. Every child must exit 0.
const std = @import("std");
const Io = std.Io;

const timeout_seconds = 300;

var peer_pids: [3]std.atomic.Value(i32) = .{ .init(0), .init(0), .init(0) };

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const gpa = init.gpa;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);
    if (args.len >= 3 and std.mem.eql(u8, args[1], "--service-exec"))
        return std.process.replace(io, .{ .argv = args[2..] });
    if ((args.len == 6 or args.len == 7) and std.mem.eql(u8, args[1], "--cold-window"))
        return coldWindow(init, args);
    if (args.len < 12) {
        std.debug.print("usage: azk-system-test <androlinzberg> <azk-binder-broker> <test-a.apk> <test-b.apk> <ndk-system-client> <identity-a.apk> <identity-b.apk> <surfaceflinger> <service-fixture.apk> <media-service> <media-probe>\n", .{});
        return error.InvalidArguments;
    }
    const launcher = args[1];
    const broker_exe = args[2];
    const fixture_a = args[3];
    const fixture_b = args[4];
    const ndk_client = args[5];
    const identity_a = args[6];
    const identity_b = args[7];
    const surfaceflinger_exe = args[8];
    const service_fixture = args[9];

    var env = try init.minimal.environ.createMap(gpa);
    defer env.deinit();
    // The peers install their packages from build-time fixture APKs.
    try env.put("AZK_FIXTURE_APK_A", fixture_a);
    try env.put("AZK_FIXTURE_APK_B", fixture_b);
    try env.put("AZK_FIXTURE_IDENTITY_A", identity_a);
    try env.put("AZK_FIXTURE_IDENTITY_B", identity_b);
    try env.put("AZK_FIXTURE_SERVICE_INSTANCES", service_fixture);
    // Hermetic: a nonexistent display keeps the hosted clipboard monitor off
    // the developer's real Wayland clipboard (an empty value would fall back
    // to wayland-0; parallel test sessions would cross-talk through it).
    try env.put("WAYLAND_DISPLAY", "azk-hermetic-none");

    // The test entry enables the caller API flag in the stock system process.
    // Everything else uses production session metadata, cold starts, output
    // drains, service ownership and bounded shutdown.
    try env.put("AZK_TEST_CLASS", "dev.artzig.systemtest.SystemTestServer");
    try env.put("AZK_BINDER_ROLE", "system");
    const session_env: std.process.Environ = .{ .block = try env.createPosixBlock(gpa, .{}) };
    defer session_env.block.deinit(gpa);
    const session_module = @import("session.zig");
    var session = try session_module.Session.start(gpa, io, .{
        .launcher_exe = launcher,
        .broker_exe = broker_exe,
        .surfaceflinger_exe = surfaceflinger_exe,
        .media_service_exe = args[10],
        .platform_root = std.fs.path.dirname(std.fs.path.dirname(launcher).?).?,
        .system_args = &.{"--javatest"},
        .environ = session_env,
    });
    defer session.stop();
    try session.awaitSystemReady();
    try env.put(session_module.socket_env, session.socket_path);
    try env.put(session_module.cookie_env, session.cookie);
    try env.put(session_module.system_cookie_env, session.system_cookie);
    try env.put(session_module.prop_area_env, session.prop_area_path);

    var watchdog: Io.Group = .init;
    var expired = std.atomic.Value(bool).init(false);
    try watchdog.concurrent(io, watch, .{ io, &expired });
    defer watchdog.cancel(io);

    try signalOutput(io, &env, launcher);

    try env.put("AZK_BINDER_PACKAGE", "android:media-fixture");
    var media_probe = try std.process.spawn(io, .{
        .argv = &.{args[11]},
        .environ_map = &env,
        .stdin = .ignore,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    peer_pids[0].store(media_probe.id.?, .release);
    const media_ok = expectExit(io, &media_probe, "native media resource client");
    peer_pids[0].store(0, .release);
    if (!media_ok) return error.SystemTestFailed;

    var fixture_install = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.ServiceFixtureInstallSmoke", "android");
    if (!expectExit(io, &fixture_install, "service fixture install")) return error.SystemTestFailed;

    var host = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.SystemTestHostB", "dev.artzig.test.b");
    peer_pids[0].store(host.id.?, .release);
    var client = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.SystemTestClientA", "dev.artzig.test.a");
    peer_pids[1].store(client.id.?, .release);

    // Cross-language native peer: a standalone libbinder_ndk process (its own
    // package/uid) that connects to this broker via the inherited env and calls
    // the Java service host B registers. Not a launcher --javatest peer.
    try env.put("AZK_BINDER_PACKAGE", "dev.artzig.ndkclient");
    var ndkc = try std.process.spawn(io, .{
        .argv = &.{ndk_client},
        .environ_map = &env,
        .stdin = .ignore,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    peer_pids[2].store(ndkc.id.?, .release);

    var failed = false;
    failed = !expectExit(io, &client, "client") or failed;
    peer_pids[1].store(0, .release);
    if (failed) {
        // A failed client cannot send the service host's normal completion
        // signals. Retire owned peers now instead of waiting for fixture timeouts.
        ndkc.kill(io);
        host.kill(io);
    } else {
        failed = !expectExit(io, &ndkc, "ndk system client") or failed;
        failed = !expectExit(io, &host, "service host") or failed;
    }
    peer_pids[2].store(0, .release);
    peer_pids[0].store(0, .release);
    if (!failed) {
        var crashing = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.RuntimeCrashSystemSmoke", "dev.artzig.test.a");
        peer_pids[0].store(crashing.id.?, .release);
        const crash_term = try crashing.wait(io);
        peer_pids[0].store(0, .release);
        const killed = switch (crash_term) {
            .signal => |signal| signal == .KILL,
            else => false,
        };
        if (!killed) {
            std.debug.print("system-test: RuntimeInit did not kill the crashing process: {any}\n", .{crash_term});
            failed = true;
        } else {
            try env.put("AZK_VERIFY_RUNTIME_CRASH", "1");
            var verifier = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.RuntimeCrashSystemSmoke", "dev.artzig.test.a");
            peer_pids[0].store(verifier.id.?, .release);
            failed = !expectExit(io, &verifier, "runtime crash verifier") or failed;
            peer_pids[0].store(0, .release);
        }
    }
    if (!failed) {
        var identity_host = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.CallerIdentitySystemSmoke", "dev.artzig.identity.b");
        peer_pids[0].store(identity_host.id.?, .release);
        var identity_client = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.CallerIdentitySystemSmoke", "dev.artzig.identity.a");
        peer_pids[1].store(identity_client.id.?, .release);
        var identity_launcher = try spawnPeer(io, &env, launcher, "dev.artzig.systemtest.IdentityLaunchSystemSmoke", "android");
        peer_pids[2].store(identity_launcher.id.?, .release);
        failed = !expectExit(io, &identity_client, "caller identity client") or failed;
        peer_pids[1].store(0, .release);
        failed = !expectExit(io, &identity_launcher, "caller identity launcher") or failed;
        peer_pids[2].store(0, .release);
        failed = !expectExit(io, &identity_host, "caller identity host") or failed;
        peer_pids[0].store(0, .release);
    }
    if (expired.load(.acquire)) {
        std.debug.print("system-test: timed out after {d}s\n", .{timeout_seconds});
        failed = true;
    }
    if (failed) return error.SystemTestFailed;
    std.debug.print("system-test: all test peers exited cleanly\n", .{});
}

/// Use the production session supervisor, including its stdout drain and app
/// launcher metadata. Only this test's system client receives the system cookie.
fn coldWindow(init: std.process.Init, args: []const []const u8) !void {
    const io = init.io;
    const gpa = init.gpa;
    const session_module = @import("session.zig");
    var session = try session_module.Session.start(gpa, io, .{
        .launcher_exe = args[2],
        .broker_exe = args[3],
        .surfaceflinger_exe = args[4],
        .task_presenter_exe = if (args.len == 7) args[6] else null,
        .environ = init.minimal.environ,
    });
    defer session.stop();
    try session.awaitSystemReady();
    var env = try init.minimal.environ.createMap(gpa);
    defer env.deinit();
    try env.put(session_module.socket_env, session.socket_path);
    try env.put(session_module.cookie_env, session.cookie);
    try env.put(session_module.system_cookie_env, session.system_cookie);
    try env.put(session_module.prop_area_env, session.prop_area_path);
    try env.put("AZK_BINDER_SESSION_DIR", session.dir);
    try env.put("AZK_TEST_CLASS", "dev.artzig.window.StockColdLaunchSmoke");
    try env.put("AZK_BINDER_ROLE", "system");
    var client = try std.process.spawn(io, .{
        .argv = &.{ args[2], "--javatest", args[5] },
        .environ_map = &env,
        .stdin = .inherit,
        .stdout = .inherit,
        .stderr = .inherit,
    });
    defer client.kill(io);
    if (!expectExit(io, &client, "stock cold launch")) return error.ColdLaunchFailed;
}

fn spawnPeer(
    io: Io,
    env: *std.process.Environ.Map,
    launcher: []const u8,
    class_name: []const u8,
    package: []const u8,
) !std.process.Child {
    try env.put("AZK_TEST_CLASS", class_name);
    try env.put("AZK_BINDER_PACKAGE", package);
    try env.put("AZK_BINDER_ROLE", if (std.mem.eql(u8, package, "android")) "system" else "app");
    return std.process.spawn(io, .{
        .argv = &.{ launcher, "--javatest" },
        .environ_map = env,
        .stdin = .ignore,
        .stdout = .inherit,
        .stderr = .inherit,
    });
}

// Exercise the production ART/platform DSO combination. A marker in a named
// Java thread and SignalCatcher's final acknowledgement prove the whole dump
// path, rather than only resolving PaletteWriteCrashThreadStacks.
fn signalOutput(io: Io, env: *std.process.Environ.Map, launcher: []const u8) !void {
    try env.put("AZK_TEST_CLASS", "dev.artzig.systemtest.RuntimeSignalSystemSmoke");
    try env.put("AZK_BINDER_ROLE", "app");
    try env.put("AZK_BINDER_PACKAGE", "dev.artzig.test.a");
    var child = try std.process.spawn(io, .{
        .argv = &.{ launcher, "--javatest" },
        .environ_map = env,
        .stdin = .pipe,
        .stdout = .inherit,
        .stderr = .pipe,
    });
    defer child.kill(io);
    peer_pids[0].store(child.id.?, .release);
    defer peer_pids[0].store(0, .release);
    var buffer: [64 * 1024]u8 = undefined;
    var reader = child.stderr.?.readerStreaming(io, &buffer);
    var threads = false;
    var named = false;
    var completed = false;
    while (true) {
        const line = reader.interface.takeDelimiterInclusive('\n') catch |err| switch (err) {
            error.EndOfStream => break,
            else => return err,
        };
        try Io.File.stderr().writeStreamingAll(io, line);
        threads = threads or std.mem.indexOf(u8, line, "DALVIK THREADS (") != null;
        named = named or std.mem.indexOf(u8, line, "stock-signal-regression") != null;
        if (!completed and std.mem.indexOf(u8, line, "Wrote stack traces to tombstoned") != null) {
            if (!threads or !named) return error.IncompleteSignalStackDump;
            completed = true;
            try child.stdin.?.writeStreamingAll(io, &.{1});
            child.stdin.?.close(io);
            child.stdin = null;
        }
    }
    if (!expectExit(io, &child, "SIGQUIT stack output") or !completed)
        return error.SignalStackOutputFailed;
}

fn expectExit(io: Io, child: *std.process.Child, name: []const u8) bool {
    const term = child.wait(io) catch |err| {
        std.debug.print("system-test: {s}: wait failed: {t}\n", .{ name, err });
        return false;
    };
    switch (term) {
        .exited => |code| {
            if (code == 0) return true;
            std.debug.print("system-test: {s} exited with {d}\n", .{ name, code });
            return false;
        },
        else => {
            std.debug.print("system-test: {s} terminated abnormally: {any}\n", .{ name, term });
            return false;
        },
    }
}

fn watch(io: Io, expired: *std.atomic.Value(bool)) Io.Cancelable!void {
    try Io.sleep(io, .{ .nanoseconds = timeout_seconds * std.time.ns_per_s }, .awake);
    expired.store(true, .release);
    for (&peer_pids) |*pid| {
        const id = pid.load(.acquire);
        if (id != 0) _ = std.os.linux.kill(id, .KILL);
    }
}
