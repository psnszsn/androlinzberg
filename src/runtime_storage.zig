//! Android storage and native-library environment for one runtime instance.
const std = @import("std");
const art = @import("art");
extern "c" fn setenv(name: [*:0]const u8, value: [*:0]const u8, overwrite: c_int) c_int;
extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
const binder_session = @import("binder/session.zig");

/// Prepare a real filesystem directory for an APK's bionic libraries.  The
/// Java host extracts lib/x86_64 into it before constructing PathClassLoader;
/// bionic_translation must see both that directory and its own shim libraries
/// when its constructor runs during Runtime.create.
pub fn configureNativeLibraries(init: std.process.Init, tree: []const u8, runtime_root: []const u8, apk: []const u8) !void {
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
    // <native>/apk/<archive>/<version> holds libraries materialized from APK
    // entries ("base.apk!/lib/<abi>/...") that Android's linker would map in
    // place; only an archive's current version remains. bionic_translation
    // matches this pattern to admit an absolute path as a bionic library; its
    // bare-name search does not expand it.
    _ = setenv("BIONIC_LD_LIBRARY_PATH", try std.fmt.allocPrintSentinel(a, "{s}:{s}/apk/*/*:{s}/apps/lib/*", .{ native_dir, native_dir, tree }, 0), 1);

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
pub fn configureDataRoot(init: std.process.Init) !void {
    const a = init.arena.allocator();
    const env = init.minimal.environ;
    const configured_root = if (env.getPosix("ARTZIG_DATA_DIR")) |configured| configured else blk: {
        if (env.getPosix("XDG_DATA_HOME")) |xdg| break :blk try std.fs.path.join(a, &.{ xdg, "artzig" });
        const home = env.getPosix("HOME") orelse "/tmp";
        break :blk try std.fs.path.join(a, &.{ home, ".local", "share", "artzig" });
    };
    try std.Io.Dir.cwd().createDirPath(init.io, configured_root);
    // Runtime processes work from "/" (see runtime_bootstrap), so every
    // exported storage root is absolute.
    const root = try std.Io.Dir.cwd().realPathFileAlloc(init.io, configured_root, a);
    _ = setenv("ARTZIG_DATA_DIR", try a.dupeSentinel(u8, root, 0), 1);
    if (env.getPosix("DOWNLOAD_CACHE") == null) {
        _ = setenv("DOWNLOAD_CACHE", try std.fs.path.joinZ(a, &.{ root, "cache" }), 1);
    }
    if (env.getPosix("EXTERNAL_STORAGE") == null) {
        _ = setenv("EXTERNAL_STORAGE", try std.fs.path.joinZ(a, &.{ root, "external" }), 1);
    }
}

pub fn dataPartitionOptions(init: std.process.Init) !art.DataPartition.Options {
    const persistent = getenv("ARTZIG_DATA_DIR") orelse return error.MissingApplicationDataRoot;
    return .{
        .persistent_root = std.mem.span(persistent),
        .directory = if (getenv("AZK_BINDER_SESSION_DIR")) |session|
            try std.fs.path.join(init.arena.allocator(), &.{ std.mem.span(session), "android-data" })
        else
            null,
    };
}

pub fn sessionStorage(init: std.process.Init) !binder_session.Storage {
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

pub fn applySessionStorage(init: std.process.Init, storage: binder_session.Storage) !void {
    for (storage.environment()) |entry| {
        if (setenv(entry.name.ptr, try init.arena.allocator().dupeSentinel(u8, entry.value, 0), 1) != 0)
            return error.StorageEnvironmentFailed;
    }
}
