//! Build-graph audits for source provenance and native ABI boundaries.
const std = @import("std");
const Io = std.Io;

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2) return usage(init.io);
    if (std.mem.eql(u8, args[1], "jvm-source")) {
        if (args.len < 4) return usage(init.io);
        try jvmSource(init, args[2], args[3..]);
    } else if (std.mem.eql(u8, args[1], "cpp-boundary")) {
        try cppBoundary(init.io, args[2..]);
    } else if (std.mem.eql(u8, args[1], "ndk-abi")) {
        if (args.len != 4) return usage(init.io);
        try ndkAbi(init, args[2], args[3]);
    } else if (std.mem.eql(u8, args[1], "graph")) {
        if (args.len < 3) return usage(init.io);
        try graph(init, args[2..]);
    } else return usage(init.io);
}

fn usage(io: Io) error{InvalidArguments}!void {
    Io.File.stderr().writeStreamingAll(io, "usage: build-audit jvm-source <repo> <files...> | cpp-boundary <files...> | ndk-abi <library> <reference> | graph <build-files...>\n") catch {};
    return error.InvalidArguments;
}

fn relativeTo(root: []const u8, path: []const u8) ?[]const u8 {
    // Step.Run may render a project LazyPath as `./foo` even when the directory
    // argument used for the project root is absolute. These are still the same
    // graph-owned input; keep path spelling out of the provenance identity.
    if (!std.fs.path.isAbsolute(path)) {
        var relative = path;
        while (std.mem.startsWith(u8, relative, "./")) relative = relative[2..];
        return relative;
    }
    if (!std.mem.startsWith(u8, path, root)) return null;
    var relative = path[root.len..];
    while (relative.len > 0 and std.fs.path.isSep(relative[0])) relative = relative[1..];
    return relative;
}

fn allowedJvmPath(path: []const u8) bool {
    inline for (.{
        "artzig/src/gen/",
        "artzig/examples/",
        "src/java/",
        "src/annotation-runtime/",
        "tests/",
        "testdata/kotlin-shadow/",
    }) |prefix| if (std.mem.startsWith(u8, path, prefix)) return true;
    return false;
}

fn jvmPackage(contents: []const u8) ?[]const u8 {
    var in_comment = false;
    var lines = std.mem.splitScalar(u8, contents, '\n');
    while (lines.next()) |raw| {
        var line = std.mem.trim(u8, raw, " \t\r");
        while (line.len != 0) {
            if (in_comment) {
                const end = std.mem.indexOf(u8, line, "*/") orelse break;
                line = std.mem.trimStart(u8, line[end + 2 ..], " \t");
                in_comment = false;
            } else if (std.mem.startsWith(u8, line, "/*")) {
                in_comment = true;
                line = line[2..];
            } else break;
        }
        if (in_comment or !std.mem.startsWith(u8, line, "package")) continue;
        if (line.len == 7 or (line[7] != ' ' and line[7] != '\t')) continue;
        const name = std.mem.trimStart(u8, line[7..], " \t");
        var end: usize = 0;
        while (end < name.len and (std.ascii.isAlphanumeric(name[end]) or
            name[end] == '_' or name[end] == '.' or name[end] == '$')) : (end += 1)
        {}
        return name[0..end];
    }
    return null;
}

fn isClientPackage(package: []const u8) bool {
    return std.mem.eql(u8, package, "android") or std.mem.startsWith(u8, package, "android.") or
        std.mem.eql(u8, package, "com.android.internal") or
        std.mem.startsWith(u8, package, "com.android.internal.");
}

fn jvmSource(init: std.process.Init, repo_root: []const u8, files: []const []const u8) !void {
    var failed = false;
    for (files) |file| {
        const relative = relativeTo(repo_root, file) orelse {
            std.debug.print("JVM source is outside repository root: {s}\n", .{file});
            failed = true;
            continue;
        };
        if (!allowedJvmPath(relative)) {
            std.debug.print("repository-owned JVM source is not allowed here: {s}\n", .{relative});
            failed = true;
        }
        // Use the declared package, not a directory convention: moving a shim
        // into src/java/dev/artzig must not evade the stock-client boundary.
        if (jvmPackage(try readFile(init, file))) |package| {
            // These generated SDK headers are compile-only inputs owned by
            // Artzig's boot API generator, not runtime framework replacements.
            const boot_headers = std.mem.startsWith(u8, relative, "artzig/src/gen/java/core-all/");
            if (isClientPackage(package) and !boot_headers) {
                std.debug.print("repository-owned Android client class: {s} (package {s}); reuse pinned AOSP or move host behavior to dev.artzig\n", .{ relative, package });
                failed = true;
            }
        }
    }
    if (failed) return error.AuditFailed;
}

fn cppBoundary(io: Io, files: []const []const u8) !void {
    const expected = [_][]const u8{
        "src/native/AospJniBridge.cpp",
        "src/native/ArrayUtilsJniBridge.cpp",
        "src/native/BinderJniBridge.cpp",
        "src/native/EglWindow.cpp",
        "src/native/GraphicsEnvironmentBackend.cpp",
        "src/native/HostInputDispatcher.cpp",
        "src/native/HostLooper.cpp",
        // Genuine AndroidRuntime subclass binds the launcher's existing ART VM.
        "src/native/HostedAndroidRuntime.cpp",
        "src/native/MediaPlayerJniBridge.cpp",
        "src/native/PlatformFiles.cpp",
        "src/native/RuntimeDiagnostics.cpp",
        // Original sensor service construction and public NDK contract checks.
        "src/native/SensorClientTest.cpp",
        "src/native/SensorServiceBootstrap.cpp",
        // Host audio services behind the pinned libaudioclient AIDL (src/native/audio).
        "src/native/audio/AAudioSmoke.cpp",
        "src/native/audio/AudioServiceJni.cpp",
        "src/native/audio/HostAudioFlinger.cpp",
        "src/native/audio/HostAudioPolicyService.cpp",
        "src/native/audio/HostAudioTrack.cpp",
        "src/native/binder/Binder.cpp",
        "src/native/binder/Parcel.cpp",
        "src/native/binder/ServiceNotifications.cpp",
        "src/native/binder/ThreadScheduling.cpp",
        // Session bootstrap for the original native media services.
        "src/native/codec2/FfmpegComponent.cpp",
        "src/native/codec2/FfmpegStore.cpp",
        "src/native/codec2/MediaServiceHost.cpp",
        "src/native/graphics/HostComposer.cpp",
        "src/native/graphics/HostGralloc.cpp",
        "src/native/graphics/HostRenderEngineCapabilities.cpp",
        "src/native/graphics/HostTrace.cpp",
        "src/native/graphics/SurfaceFlingerHost.cpp",
        "src/native/graphics/SurfaceQueue.cpp",
        // Stock InputReader's device source and factory, and the IMS JNI's
        // user-activity entry point while PowerManagerService is hosted.
        "src/native/input/HostEventHub.cpp",
        "src/native/input/HostInputDispatcherFactory.cpp",
        "src/native/input/HostInputReaderFactory.cpp",
        "src/native/input/HostUserActivity.cpp",
        // dnsproxyd's stock socket protocol backed by the Linux resolver.
        "src/native/network/DnsProxy.cpp",
        "src/native/network/NetworkBackend.cpp",
    };
    if (files.len != expected.len) return error.AuditFailed;
    for (files, expected) |actual, suffix| {
        if (!std.mem.endsWith(u8, actual, suffix)) {
            try Io.File.stderr().writeStreamingAll(io, "unexpected application C++ source: ");
            try Io.File.stderr().writeStreamingAll(io, actual);
            try Io.File.stderr().writeStreamingAll(io, "\n");
            return error.AuditFailed;
        }
    }
}

fn readFile(init: std.process.Init, path: []const u8) ![]const u8 {
    return Io.Dir.cwd().readFileAlloc(init.io, path, init.arena.allocator(), .limited(std.math.maxInt(u32)));
}

fn dynamicSymbols(init: std.process.Init, path: []const u8) !std.StringHashMapUnmanaged(void) {
    const bytes = try readFile(init, path);
    var reader = Io.Reader.fixed(bytes);
    const header = try std.elf.Header.read(&reader);
    var sections = try init.arena.allocator().alloc(std.elf.Elf64_Shdr, header.shnum);
    var iterator = header.iterateSectionHeadersBuffer(bytes);
    var index: usize = 0;
    while (try iterator.next()) |section| : (index += 1) sections[index] = section;

    var result: std.StringHashMapUnmanaged(void) = .empty;
    for (sections) |section| {
        if (section.sh_type != std.elf.SHT_DYNSYM or section.sh_entsize == 0) continue;
        const strings = sections[section.sh_link];
        const string_table = bytes[@intCast(strings.sh_offset)..@intCast(strings.sh_offset + strings.sh_size)];
        var symbols = Io.Reader.fixed(bytes[@intCast(section.sh_offset)..@intCast(section.sh_offset + section.sh_size)]);
        const count = section.sh_size / section.sh_entsize;
        for (0..@intCast(count)) |_| {
            const symbol = if (header.is_64)
                try symbols.takeStruct(std.elf.Elf64_Sym, header.endian)
            else blk: {
                const value = try symbols.takeStruct(std.elf.Elf32_Sym, header.endian);
                break :blk std.elf.Elf64_Sym{
                    .st_name = value.st_name,
                    .st_info = value.st_info,
                    .st_other = value.st_other,
                    .st_shndx = value.st_shndx,
                    .st_value = value.st_value,
                    .st_size = value.st_size,
                };
            };
            if (symbol.st_shndx == std.elf.SHN_UNDEF or symbol.st_name >= string_table.len) continue;
            const tail = string_table[symbol.st_name..];
            const end = std.mem.indexOfScalar(u8, tail, 0) orelse continue;
            const versioned = tail[0..end];
            if (versioned.len == 0 or versioned[0] != 'A') continue;
            const version = std.mem.indexOf(u8, versioned, "@@") orelse versioned.len;
            try result.put(init.arena.allocator(), versioned[0..version], {});
        }
    }
    return result;
}

fn ndkAbi(init: std.process.Init, library: []const u8, reference: []const u8) !void {
    var actual = try dynamicSymbols(init, library);
    var expected = try dynamicSymbols(init, reference);
    var missing: usize = 0;
    var iterator = expected.keyIterator();
    while (iterator.next()) |symbol| {
        if (actual.contains(symbol.*)) continue;
        std.debug.print("missing NDK ABI symbol: {s}\n", .{symbol.*});
        missing += 1;
    }
    if (missing != 0) return error.AuditFailed;
    std.debug.print("NDK ABI: all {d} reference A* symbols exported ({d} total)\n", .{ expected.count(), actual.count() });
}

const obsolete_scripts = [_][]const u8{
    "artzig/tools/d8.sh",
    "artzig/tools/extract-apex-etc.sh",
    "artzig/tools/make-system-modules.sh",
    "artzig/tools/mkbootjar.sh",
    "tools/make-core-plus.sh",
    "tools/dexopt-framework.sh",
    "tools/check-jvm-source-policy.sh",
    "tools/check-native-boundary.sh",
    "tools/check-ndk-abi.sh",
};

fn graphViolation(contents: []const u8) ?[]const u8 {
    for (obsolete_scripts) |script| if (std.mem.indexOf(u8, contents, script) != null)
        return "obsolete build script is passed or referenced";
    inline for (.{
        "b.graph.path(.install_prefix",
        ".path(.install_prefix",
        "vendor_dir:",
        "root_dir:",
        "aosp_dir:",
    }) |pattern| if (std.mem.indexOf(u8, contents, pattern) != null)
        return "absolute/install-prefix path escaped into a build interface";
    var lines = std.mem.splitScalar(u8, contents, '\n');
    while (lines.next()) |line| {
        if (std.mem.indexOf(u8, line, ".cwd_relative =") != null)
            return "project or generated input uses .cwd_relative";
    }
    return null;
}

fn graph(init: std.process.Init, files: []const []const u8) !void {
    var failed = false;
    for (files) |file| {
        const contents = try readFile(init, file);
        if (graphViolation(contents)) |reason| {
            std.debug.print("build graph audit: {s}: {s}\n", .{ file, reason });
            failed = true;
        }
    }
    if (failed) return error.AuditFailed;
}

test "JVM allowlist rejects arbitrary locations" {
    try std.testing.expect(allowedJvmPath("src/java/dev/artzig/Host.kt"));
    try std.testing.expect(!allowedJvmPath("tools/Accidental.java"));
}

test "client namespace audit follows Java and Kotlin packages instead of file locations" {
    try std.testing.expectEqualStrings("android.view", jvmPackage("/* license */\npackage android.view;\nclass Host {}\n").?);
    try std.testing.expectEqualStrings("com.android.internal.os", jvmPackage("package\tcom.android.internal.os\nobject Host\n").?);
    try std.testing.expectEqualStrings("dev.artzig", jvmPackage("/*\npackage android.fake;\n*/\npackage dev.artzig\n").?);
    try std.testing.expect(isClientPackage("android.view"));
    try std.testing.expect(isClientPackage("com.android.internal.os"));
    try std.testing.expect(!isClientPackage("androidx.annotation"));
    try std.testing.expect(!isClientPackage("com.android.server.wm"));
    try std.testing.expect(!isClientPackage("dev.artzig"));
}

test "graph audit accepts LazyPath interfaces and rejects regressions" {
    try std.testing.expectEqual(@as(?[]const u8, null), graphViolation(
        \\const source = dependency.path("src/Foo.java");
        \\run.addFileArg(source);
    ));
    try std.testing.expect(graphViolation(
        \\run.addArg("tools/dexopt-framework.sh");
    ) != null);
    try std.testing.expect(graphViolation(
        \\const input = b.graph.path(.install_prefix, "lib/runtime.so");
    ) != null);
    try std.testing.expect(graphViolation(
        \\const source: LazyPath = .{ .cwd_relative = "generated/Foo.java" };
    ) != null);
}
