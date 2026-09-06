//! Build-graph audits for source provenance and native ABI boundaries.
const std = @import("std");
const Io = std.Io;
const generator_sources = @import("generator_sources");

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    if (args.len < 2) return usage(init.io);
    if (std.mem.eql(u8, args[1], "jvm-source")) {
        if (args.len < 6) return usage(init.io);
        try jvmSource(init, args[2], args[3], args[4], args[5..]);
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
    Io.File.stderr().writeStreamingAll(io, "usage: build-audit jvm-source <repo> <aosp> <stub-audit> <files...> | cpp-boundary <files...> | ndk-abi <library> <reference> | graph <build-files...>\n") catch {};
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
        "src/framework/",
        "src/framework-stubs/",
        "src/java/",
        "src/annotation-runtime/",
        "src/testkit/",
        "tests/",
        "testdata/kotlin-shadow/",
    }) |prefix| if (std.mem.startsWith(u8, path, prefix)) return true;
    return false;
}

fn putForbidden(set: *std.StringHashMapUnmanaged(void), allocator: std.mem.Allocator, path: []const u8) !void {
    try set.put(allocator, try allocator.dupe(u8, path), {});
    if (std.mem.endsWith(u8, path, ".java")) {
        const kotlin = try std.mem.concat(allocator, u8, &.{ path[0 .. path.len - ".java".len], ".kt" });
        try set.put(allocator, kotlin, {});
    }
}

fn generatedClasses(io: Io, allocator: std.mem.Allocator, aosp_root: []const u8) !std.StringHashMapUnmanaged(void) {
    var result: std.StringHashMapUnmanaged(void) = .empty;
    for (generator_sources.aconfig) |entry| {
        const package_path = try std.mem.replaceOwned(u8, allocator, entry.package, ".", "/");
        inline for (.{ "Flags", "FeatureFlags", "FeatureFlagsImpl", "CustomFeatureFlags", "FakeFeatureFlagsImpl" }) |class| {
            try putForbidden(&result, allocator, try std.fmt.allocPrint(allocator, "{s}/{s}.java", .{ package_path, class }));
        }
    }
    for (generator_sources.hidl) |entry| {
        for (entry.outputs) |output| try putForbidden(&result, allocator, output);
    }
    for (generator_sources.xsd) |entry| {
        for (entry.outputs) |output| try putForbidden(&result, allocator, output);
    }
    for (generator_sources.cronet.enum_outputs) |output| try putForbidden(&result, allocator, output);
    for (generator_sources.cronet.proto_outputs) |output| try putForbidden(&result, allocator, output);
    for (generator_sources.cronet.jni_outputs) |output| try putForbidden(&result, allocator, output);
    for (generator_sources.cronet.jni_final_outputs) |output| try putForbidden(&result, allocator, output);
    try putForbidden(&result, allocator, generator_sources.cronet.api_version_output);
    try putForbidden(&result, allocator, generator_sources.cronet.impl_version_output);
    try putForbidden(&result, allocator, generator_sources.cronet.switches_output);
    try putForbidden(&result, allocator, generator_sources.cronet.preload.output);
    inline for (generator_sources.cronet.templates) |entry| try putForbidden(&result, allocator, entry.output);
    for (generator_sources.logtags) |entry| try putForbidden(&result, allocator, entry.output);
    for (generator_sources.proto_outputs) |output| try putForbidden(&result, allocator, output);
    for (generator_sources.nano) |entry| {
        for (entry.outputs) |output| try putForbidden(&result, allocator, output);
    }
    try putForbidden(&result, allocator, generator_sources.aconfig_device_paths.output);
    try putForbidden(&result, allocator, generator_sources.system_features.output);
    try putForbidden(&result, allocator, generator_sources.system_features.metadata_output);
    inline for (generator_sources.stats_java) |entry| {
        const package_path = try std.mem.replaceOwned(u8, allocator, entry.package, ".", "/");
        defer allocator.free(package_path);
        const output = try std.fmt.allocPrint(allocator, "{s}/{s}.java", .{ package_path, entry.class });
        defer allocator.free(output);
        try putForbidden(&result, allocator, output);
    }
    try putForbidden(&result, allocator, "android/os/UserManagerCache.java");
    try putForbidden(&result, allocator, "com/android/modules/expresslog/MetricIds.java");
    for (generator_sources.sysprop) |entry| {
        if (entry.input.source != .aosp) continue;
        const path = try std.fs.path.join(allocator, &.{ aosp_root, entry.input.path });
        const contents = try Io.Dir.cwd().readFileAlloc(io, path, allocator, .limited(1024 * 1024));
        if (moduleName(contents)) |name| {
            const class_path = try std.mem.replaceOwned(u8, allocator, name, ".", "/");
            try putForbidden(&result, allocator, try std.mem.concat(allocator, u8, &.{ class_path, ".java" }));
        }
    }
    return result;
}

fn moduleName(contents: []const u8) ?[]const u8 {
    var lines = std.mem.splitScalar(u8, contents, '\n');
    while (lines.next()) |line| {
        const marker = "module:";
        const start = std.mem.indexOf(u8, line, marker) orelse continue;
        const quote = std.mem.indexOfScalarPos(u8, line, start + marker.len, '"') orelse continue;
        const end = std.mem.indexOfScalarPos(u8, line, quote + 1, '"') orelse continue;
        return line[quote + 1 .. end];
    }
    return null;
}

fn jvmSource(init: std.process.Init, repo_root: []const u8, aosp_root: []const u8, stub_audit: []const u8, files: []const []const u8) !void {
    var forbidden = try generatedClasses(init.io, init.arena.allocator(), aosp_root);
    var failed = !(try stubAudit(init, repo_root, stub_audit, files));
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
        const framework_relative = if (std.mem.startsWith(u8, relative, "src/framework/"))
            relative["src/framework/".len..]
        else if (std.mem.startsWith(u8, relative, "src/framework-stubs/"))
            relative["src/framework-stubs/".len..]
        else
            continue;
        if (forbidden.contains(framework_relative)) {
            std.debug.print("hand-written class shadows a generated one: {s}\n", .{relative});
            failed = true;
        }
        if (!std.mem.startsWith(u8, framework_relative, "android/") and
            !std.mem.startsWith(u8, framework_relative, "com/android/")) continue;
        if (try aospDuplicate(init, aosp_root, framework_relative)) {
            const contents = try Io.Dir.cwd().readFileAlloc(init.io, file, init.arena.allocator(), .limited(2 * 1024 * 1024));
            const first_lines = firstLines(contents, 20);
            if (std.mem.indexOf(u8, first_lines, "artzig") == null and
                std.mem.indexOf(u8, first_lines, "Hosted adaptation of AOSP") == null)
            {
                std.debug.print("unmarked duplicate of a vendored AOSP class: {s}\n", .{relative});
                failed = true;
            }
        }
    }
    if (failed) return error.AuditFailed;
}

/// The stub ratchet: every stub under src/framework-stubs is listed in
/// tools/stub-audit.txt with a family it is pending replacement in or a
/// `survivor:` reason, every listed path still exists, and the number of
/// pending (non-survivor) entries stays within the file's `pending-budget`.
/// The budget only goes down as stubs are replaced by the real AOSP classes.
fn stubAudit(init: std.process.Init, repo_root: []const u8, audit_path: []const u8, files: []const []const u8) !bool {
    const allocator = init.arena.allocator();
    const contents = try Io.Dir.cwd().readFileAlloc(init.io, audit_path, allocator, .limited(4 * 1024 * 1024));
    var listed: std.StringHashMapUnmanaged(void) = .empty;
    var budget: ?usize = null;
    var pending: usize = 0;
    var ok = true;
    var lines = std.mem.splitScalar(u8, contents, '\n');
    while (lines.next()) |raw| {
        const line = std.mem.trimEnd(u8, raw, "\r");
        if (line.len == 0) continue;
        if (line[0] == '#') {
            const marker = "# pending-budget:";
            if (std.mem.startsWith(u8, line, marker))
                budget = std.fmt.parseInt(usize, std.mem.trim(u8, line[marker.len..], " \t"), 10) catch null;
            continue;
        }
        var fields = std.mem.splitScalar(u8, line, '|');
        const path = fields.next() orelse continue;
        _ = fields.next();
        const family = fields.next() orelse "";
        try listed.put(allocator, path, {});
        const full = try std.fs.path.join(allocator, &.{ repo_root, path });
        const exists = if (std.fs.path.isAbsolute(full)) blk: {
            Io.Dir.accessAbsolute(init.io, full, .{}) catch break :blk false;
            break :blk true;
        } else blk: {
            Io.Dir.cwd().access(init.io, full, .{}) catch break :blk false;
            break :blk true;
        };
        if (!exists) {
            std.debug.print("stub audit names a file that no longer exists (remove the line): {s}\n", .{path});
            ok = false;
        }
        if (family.len == 0) {
            std.debug.print("stub audit entry has no family or survivor reason: {s}\n", .{path});
            ok = false;
        }
        if (!std.mem.startsWith(u8, family, "survivor:")) pending += 1;
    }
    if (budget == null) {
        std.debug.print("{s}: missing \"# pending-budget:\" line\n", .{audit_path});
        ok = false;
    }
    for (files) |file| {
        const relative = relativeTo(repo_root, file) orelse continue;
        if (!std.mem.startsWith(u8, relative, "src/framework-stubs/")) continue;
        if (!std.mem.endsWith(u8, relative, ".java") and !std.mem.endsWith(u8, relative, ".kt")) continue;
        if (listed.contains(relative)) continue;
        std.debug.print("stub is not in {s} (run tools/aosp-closure.py audit --update {s} and assign a family or survivor reason): {s}\n", .{ audit_path, audit_path, relative });
        ok = false;
    }
    if (budget) |limit| if (pending > limit) {
        std.debug.print("{s}: {d} pending stubs exceed the budget of {d} (replace stubs; never raise the budget)\n", .{ audit_path, pending, limit });
        ok = false;
    };
    return ok;
}

fn firstLines(contents: []const u8, count: usize) []const u8 {
    var end: usize = 0;
    var lines: usize = 0;
    while (end < contents.len and lines < count) : (end += 1) {
        if (contents[end] == '\n') lines += 1;
    }
    return contents[0..end];
}

fn aospDuplicate(init: std.process.Init, root: []const u8, relative: []const u8) !bool {
    for ([_][]const u8{
        "frameworks/base",
        "frameworks/libs/modules-utils",
        "packages/modules/StatsD",
    }) |prefix| {
        const path = try std.fs.path.join(init.arena.allocator(), &.{ root, prefix, relative });
        if (std.fs.path.isAbsolute(path))
            Io.Dir.accessAbsolute(init.io, path, .{}) catch continue
        else
            Io.Dir.cwd().access(init.io, path, .{}) catch continue;
        return true;
    }
    return false;
}

fn cppBoundary(io: Io, files: []const []const u8) !void {
    const expected = [_][]const u8{
        "src/native/AospJniBridge.cpp",
        "src/native/AospParcelAbi.cpp",
        "src/native/ArrayUtilsJniBridge.cpp",
        "src/native/ArtzigGpuPipeline.cpp",
        "src/native/GraphicsEnvironmentBackend.cpp",
        "src/native/HardwareBufferRender.cpp",
        "src/native/HostInputDispatcher.cpp",
        "src/native/HostLooper.cpp",
        "src/native/HwuiRenderProxyBridge.cpp",
        "src/native/WebViewGraphicBuffer.cpp",
        "src/native/binder/Binder.cpp",
        "src/native/binder/Parcel.cpp",
        "src/native/graphics/HostComposer.cpp",
        "src/native/graphics/HostGralloc.cpp",
        "src/native/graphics/HostRenderEngineCapabilities.cpp",
        "src/native/graphics/HostTrace.cpp",
        "src/native/graphics/SurfaceFlingerHost.cpp",
        "src/native/graphics/SurfaceQueue.cpp",
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
    try std.testing.expect(allowedJvmPath("src/framework/android/View.java"));
    try std.testing.expect(!allowedJvmPath("tools/Accidental.java"));
}

test "firstLines is bounded" {
    try std.testing.expectEqualStrings("a\nb\n", firstLines("a\nb\nc\n", 2));
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
