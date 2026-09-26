//! Resolve an ART process's identity before any framework class can cache it.
//! Package metadata uses the pinned ApkLiteParseUtils in a separate parser VM;
//! application code runs only after the broker authenticates its final identity.
const std = @import("std");
const art = @import("art");
pub const Role = enum { app, system, metadata };
extern "c" fn getenv([*:0]const u8) ?[*:0]const u8;

fn env(name: [*:0]const u8) ?[]const u8 {
    return if (getenv(name)) |value| std.mem.span(value) else null;
}

pub fn testRole(environ: std.process.Environ) !Role {
    const role = environ.getPosix("AZK_BINDER_ROLE") orelse return .app;
    if (std.mem.eql(u8, role, "system")) return .system;
    if (std.mem.eql(u8, role, "app")) return .app;
    // The broker, not this process, grants the inspector role: it follows from
    // the cookie `--join` selected. Locally it is an ordinary unprivileged VM.
    if (std.mem.eql(u8, role, "inspector")) return .app;
    return error.InvalidBinderRole;
}

pub fn isInspector(environ: std.process.Environ) bool {
    return std.mem.eql(u8, environ.getPosix("AZK_BINDER_ROLE") orelse "", "inspector");
}

pub fn prepare(init: std.process.Init, library: *std.DynLib, role: Role, apk: ?[]const u8) !?art.ProcessIdentity {
    if (role == .metadata) {
        if (env("AZK_PROP_AREA") == null) return error.MissingRuntimeProperties;
        return null;
    }
    if (env("AZK_BINDER_SOCKET") == null) return null;
    const a = init.arena.allocator();
    const package = package: {
        if (env("AZK_LAUNCH_CREDENTIAL") != null)
            break :package env("AZK_BINDER_PACKAGE") orelse return error.MissingLaunchPackage;
        if (role == .system) break :package "android";
        if (env("AZK_BINDER_PACKAGE")) |name| break :package name;
        if (apk) |path| break :package try inspectPackage(init, path);
        break :package try std.fmt.allocPrint(a, "anonymous.{d}", .{std.os.linux.getpid()});
    };
    const Bootstrap = *const fn ([*:0]const u8, c_int) callconv(.c) i32;
    const bootstrap = library.lookup(Bootstrap, "azk_binder_bootstrap_identity") orelse return error.SymbolNotFound;
    const uid = bootstrap(try a.dupeSentinel(u8, package, 0), @intFromBool(role == .system));
    if (uid < 0) return error.RuntimeIdentityAuthenticationFailed;
    const mapper = library.lookup(art.ProcessIdentity.Mapper, "azk_binder_map_peer_credentials") orelse return error.SymbolNotFound;
    return .{ .uid = @intCast(uid), .gid = @intCast(uid), .map_credentials = mapper };
}

fn inspectPackage(init: std.process.Init, apk: []const u8) ![]const u8 {
    return metadata(init, "--inspect-apk-package", apk);
}

/// Import a distribution envelope before native-library and process setup.
pub fn importXapk(init: std.process.Init, path: []const u8) ![]const u8 {
    return metadata(init, "--import-xapk", path);
}

fn metadata(init: std.process.Init, operation: []const u8, apk: []const u8) ![]const u8 {
    const a = init.arena.allocator();
    var environment = try init.minimal.environ.createMap(a);
    defer environment.deinit();
    for ([_][]const u8{ "AZK_BINDER_SOCKET", "AZK_BINDER_COOKIE", "AZK_BINDER_SYSTEM_COOKIE", "AZK_BINDER_SESSION_DIR", "AZK_BINDER_PACKAGE", "AZK_BINDER_ROLE", "AZK_LAUNCH_CREDENTIAL", "AZK_LAUNCH_UID" }) |name|
        _ = environment.swapRemove(name);
    // Build/ABI properties are public OS metadata. Preserve their read-only
    // area while withholding every Binder credential and the session socket.
    try environment.put("AZK_PROP_AREA", env("AZK_PROP_AREA") orelse return error.MissingRuntimeProperties);
    for ([_][]const u8{ "ARTZIG_DATA_DIR", "EXTERNAL_STORAGE", "DOWNLOAD_CACHE", "ARTZIG_RUNTIME_CACHE_DIR" }) |name| {
        const key = try a.dupeSentinel(u8, name, 0);
        if (env(key)) |value| try environment.put(name, value);
    }
    const result = try std.process.run(a, init.io, .{
        .argv = &.{ try std.process.executablePathAlloc(init.io, a), operation, apk },
        .environ_map = &environment,
        .stdout_limit = .limited(4096),
        .stderr_limit = .limited(1024 * 1024),
        .timeout = .{ .duration = .{ .clock = .awake, .raw = .fromSeconds(300) } },
    });
    defer a.free(result.stdout);
    defer a.free(result.stderr);
    if (!result.term.success()) try std.Io.File.stderr().writeStreamingAll(init.io, result.stderr);
    switch (result.term) {
        .exited => |code| if (code != 0) return error.ApkPackageInspectionFailed,
        else => return error.ApkPackageInspectionFailed,
    }
    const package = std.mem.trim(u8, result.stdout, "\r\n");
    if (package.len == 0 or std.mem.indexOfAny(u8, package, "\r\n\x00") != null) return error.InvalidApkPackageInspection;
    return a.dupe(u8, package);
}

/// Metadata-only launcher entry: no application classes, Binder session or
/// fabricated package parser. JNI can call ApkHost's existing private helper.
pub fn printPackage(runtime: *art.Runtime, io: std.Io, apk: []const u8) !void {
    return printMetadata(runtime, io, apk, "dev/artzig/ApkHost", "parseApkLitePackageName");
}

pub fn printImportedPackage(runtime: *art.Runtime, io: std.Io, path: []const u8) !void {
    return printMetadata(runtime, io, path, "dev/artzig/HostedAppPackage", "importXapk");
}

fn printMetadata(runtime: *art.Runtime, io: std.Io, apk: []const u8, class_name: [:0]const u8, method_name: [:0]const u8) !void {
    const a = runtime.arena.allocator();
    const e = art.Env{ .env = runtime.env };
    const cls = try e.findClass(class_name);
    defer runtime.env.*.*.DeleteLocalRef.?(runtime.env, cls);
    const method = try e.getStaticMethodID(cls, method_name, "(Ljava/lang/String;)Ljava/lang/String;");
    const path = try e.newString(try a.dupeSentinel(u8, apk, 0));
    defer runtime.env.*.*.DeleteLocalRef.?(runtime.env, path);
    const value: art.jni.jstring = @ptrCast(runtime.env.*.*.CallStaticObjectMethod.?(runtime.env, cls, method, path));
    try e.check();
    if (value == null) return error.ApkPackageInspectionFailed;
    defer runtime.env.*.*.DeleteLocalRef.?(runtime.env, value);
    const chars = runtime.env.*.*.GetStringUTFChars.?(runtime.env, value, null) orelse return error.ApkPackageInspectionFailed;
    defer runtime.env.*.*.ReleaseStringUTFChars.?(runtime.env, value, chars);
    try std.Io.File.stdout().writeStreamingAll(io, std.mem.span(chars));
    try std.Io.File.stdout().writeStreamingAll(io, "\n");
}
