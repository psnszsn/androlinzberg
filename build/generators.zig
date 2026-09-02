//! Cacheable Java, native-header, resource, and fixture generators.
const std = @import("std");
const resource_flags = @import("gen/resource_flags.zig");
const generated_sources = @import("generated_java_sources.zig");

const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    aosp: *std.Build.Dependency,
    protobuf: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
    sysprop_tool: *std.Build.Step.Compile,
    javastream_tool: *std.Build.Step.Compile,
    javanano_tool: *std.Build.Step.Compile,
};

pub const Result = struct {
    aidl_java: LazyPath,
    aidl_cpp: LazyPath,
    logtags_java: LazyPath,
    aconfig_java: LazyPath,
    sysprop_java: LazyPath,
    nano_java: LazyPath,
    proto_java: LazyPath,
    stats_java: LazyPath,
    res_dir: LazyPath,
    res_apk: LazyPath,
    fixture_apks_dir: LazyPath,
    java_files: []const LazyPath,
    aggregate_step: *std.Build.Step,
};

const Source = enum { aosp, repo };

const SourcePath = struct {
    source: Source,
    path: []const u8,
};

fn parseSourcePath(line: []const u8) SourcePath {
    const split = std.mem.indexOfScalar(u8, line, '|') orelse @panic("generator input needs source|path");
    const source = std.meta.stringToEnum(Source, line[0..split]) orelse @panic("unknown generator input source");
    return .{ .source = source, .path = line[split + 1 ..] };
}

fn sourcePath(b: *std.Build, aosp: *std.Build.Dependency, input: SourcePath) LazyPath {
    return switch (input.source) {
        .aosp => aosp.path(input.path),
        .repo => b.path(input.path),
    };
}

fn sourceDir(b: *std.Build, aosp: *std.Build.Dependency, input: SourcePath) LazyPath {
    const dirname = std.fs.path.dirname(input.path) orelse ".";
    return switch (input.source) {
        .aosp => aosp.path(dirname),
        .repo => b.path(dirname),
    };
}

fn lines(comptime text: []const u8) std.mem.TokenIterator(u8, .scalar) {
    return std.mem.tokenizeScalar(u8, text, '\n');
}

fn replaceExtension(b: *std.Build, path: []const u8, extension: []const u8) []const u8 {
    const stem = path[0 .. path.len - std.fs.path.extension(path).len];
    return b.fmt("{s}{s}", .{ stem, extension });
}

fn aidlOutputPath(b: *std.Build, input: []const u8) []const u8 {
    inline for (.{
        "aidl-compat/",
        "frameworks/base/core/java/",
        "frameworks/base/graphics/java/",
        "frameworks/base/media/java/",
        "frameworks/base/apex/jobscheduler/framework/java/",
        "frameworks/base/native/android/aidl/",
        "frameworks/native/libs/input/",
        "frameworks/native/libs/binder/aidl/",
        "frameworks/native/libs/permission/aidl/",
    }) |prefix| {
        if (std.mem.startsWith(u8, input, prefix)) {
            return replaceExtension(b, input[prefix.len..], ".java");
        }
    }
    std.debug.panic("no Java output mapping for AIDL input {s}", .{input});
}

fn generatedJavaFiles(
    b: *std.Build,
    aidl: LazyPath,
    logtags: LazyPath,
    aconfig: LazyPath,
    sysprop: LazyPath,
    nano: LazyPath,
    proto: LazyPath,
    stats: LazyPath,
    resources_dir: LazyPath,
) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;

    var aidl_lines = lines(@embedFile("../tools/aidl-list.txt"));
    while (aidl_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        files.append(b.allocator, aidl.path(b, aidlOutputPath(b, parseSourcePath(line).path))) catch @panic("OOM");
    }

    var logtag_lines = lines(@embedFile("../tools/logtags-list.txt"));
    while (logtag_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        var fields = std.mem.splitScalar(u8, line, '|');
        _ = fields.next();
        _ = fields.next();
        const output_path = fields.next() orelse @panic("missing logtags output");
        files.append(b.allocator, logtags.path(b, output_path)) catch @panic("OOM");
    }

    var aconfig_lines = lines(@embedFile("../tools/aconfig-inputs.txt"));
    while (aconfig_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        const package = std.mem.sliceTo(line, '|');
        const package_path = std.mem.replaceOwned(u8, b.allocator, package, ".", "/") catch @panic("OOM");
        inline for (.{ "CustomFeatureFlags", "FakeFeatureFlagsImpl", "FeatureFlags", "FeatureFlagsImpl", "Flags" }) |class| {
            files.append(b.allocator, aconfig.path(b, b.fmt("{s}/{s}.java", .{ package_path, class }))) catch @panic("OOM");
        }
    }

    var sysprop_lines = lines(@embedFile("../tools/sysprop-list.txt"));
    while (sysprop_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        var fields = std.mem.splitScalar(u8, line, '|');
        _ = fields.next();
        _ = fields.next();
        const input_path = fields.next() orelse @panic("missing sysprop input");
        files.append(b.allocator, sysprop.path(b, b.fmt("android/sysprop/{s}", .{replaceExtension(b, std.fs.path.basename(input_path), ".java")}))) catch @panic("OOM");
    }

    files.append(b.allocator, nano.path(b, "com/android/internal/logging/nano/MetricsProto.java")) catch @panic("OOM");
    for (generated_sources.proto) |path| files.append(b.allocator, proto.path(b, path)) catch @panic("OOM");
    files.append(b.allocator, stats.path(b, "com/android/internal/util/FrameworkStatsLog.java")) catch @panic("OOM");
    inline for (.{ "android/Manifest.java", "android/R.java", "com/android/internal/R.java" }) |path| {
        files.append(b.allocator, resources_dir.path(b, "java/" ++ path)) catch @panic("OOM");
    }
    return files.toOwnedSlice(b.allocator) catch @panic("OOM");
}

fn expose(b: *std.Build, name: []const u8, description: []const u8, dependency: *std.Build.Step) *std.Build.Step {
    const step = b.step(name, description);
    step.dependOn(dependency);
    return step;
}

fn hostTool(b: *std.Build, name: []const u8, source: []const u8) *std.Build.Step.Compile {
    return b.addExecutable(.{
        .name = name,
        .root_module = b.createModule(.{
            .root_source_file = b.path(source),
            .target = b.graph.host,
            .optimize = .ReleaseSafe,
        }),
    });
}

fn addAidlIncludes(run: *std.Build.Step.Run, aosp: *std.Build.Dependency, compat: LazyPath) void {
    inline for (.{
        "frameworks/base/core/java",
        "frameworks/base/graphics/java",
    }) |path| run.addPrefixedDirectoryArg("-I", aosp.path(path));
    run.addPrefixedDirectoryArg("-I", compat);
    inline for (.{
        "frameworks/base/media/java",
        "frameworks/native/libs/input",
        "frameworks/native/libs/binder/aidl",
        "frameworks/base/apex/jobscheduler/framework/java",
        "frameworks/base/native/android/aidl",
    }) |path| run.addPrefixedDirectoryArg("-I", aosp.path(path));
}

fn aidlJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) LazyPath {
    // The native permission AIDL lib (AttributionSourceState, IAppOpsCallback,
    // IPermissionChecker) is staged into the compat root rather than added as
    // an -I root, so its parcelable declarations resolve after core/java's.
    const permission_aidl = "frameworks/native/libs/permission/aidl/";
    const compat = b.addWriteFiles();
    _ = compat.addCopyDirectory(b.path("aidl-compat"), "", .{});
    _ = compat.addCopyDirectory(aosp.path(permission_aidl), "", .{ .include_extensions = &.{".aidl"} });
    const compat_dir = compat.getDirectory();

    const merged = b.addWriteFiles();
    var input_lines = lines(@embedFile("../tools/aidl-list.txt"));
    var index: usize = 0;
    while (input_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        const input = parseSourcePath(line);
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path("prebuilts/sdk/tools/linux/bin/aidl"));
        addAidlIncludes(run, aosp, compat_dir);
        run.addArg("-o");
        const output = run.addOutputDirectoryArg(b.fmt("aidl-java-{d}", .{index}));
        if (input.source == .repo) {
            const prefix = "aidl-compat/";
            if (!std.mem.startsWith(u8, input.path, prefix)) @panic("repository AIDL must be under aidl-compat");
            run.addFileArg(compat_dir.path(b, input.path[prefix.len..]));
        } else if (std.mem.startsWith(u8, input.path, permission_aidl)) {
            run.addFileArg(compat_dir.path(b, input.path[permission_aidl.len..]));
        } else {
            run.addFileArg(sourcePath(b, aosp, input));
        }
        run.setName(b.fmt("generate Java AIDL {s}", .{std.fs.path.basename(input.path)}));
        _ = merged.addCopyDirectory(output, "", .{});
        index += 1;
    }
    _ = expose(b, "gen-aidl", "Generate framework Java AIDL sources", &merged.step);
    return merged.getDirectory();
}

fn aidlCpp(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) LazyPath {
    const inputs = [_][]const u8{
        "IInputConstants.aidl",
        "InputConfig.aidl",
        "MotionEventFlag.aidl",
        "PointerIconType.aidl",
        "InputEventInjectionSync.aidl",
        "InputEventInjectionResult.aidl",
    };
    const root = aosp.path("frameworks/native/libs/input");
    const merged = b.addWriteFiles();
    inline for (inputs, 0..) |name, index| {
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path("prebuilts/sdk/tools/linux/bin/aidl"));
        run.addArgs(&.{ "--lang=cpp", "-I" });
        run.addDirectoryArg(root);
        run.addArg("-h");
        const headers = run.addOutputDirectoryArg(b.fmt("aidl-cpp-headers-{d}", .{index}));
        run.addArg("-o");
        const sources = run.addOutputDirectoryArg(b.fmt("aidl-cpp-sources-{d}", .{index}));
        run.addFileArg(root.path(b, "android/os/" ++ name));
        run.setName(b.fmt("generate C++ AIDL {s}", .{name}));
        _ = merged.addCopyDirectory(headers, "gen", .{});
        _ = merged.addCopyDirectory(sources, "src", .{});
    }
    _ = expose(b, "gen-aidl-cpp", "Generate native input AIDL headers", &merged.step);
    return merged.getDirectory();
}

fn logtagsJava(b: *std.Build, aosp: *std.Build.Dependency) LazyPath {
    const merged = b.addWriteFiles();
    var input_lines = lines(@embedFile("../tools/logtags-list.txt"));
    while (input_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        var fields = std.mem.splitScalar(u8, line, '|');
        const source = std.meta.stringToEnum(Source, fields.next() orelse @panic("missing logtags source")) orelse @panic("bad logtags source");
        const input_path = fields.next() orelse @panic("missing logtags input");
        const output_path = fields.next() orelse @panic("missing logtags output");
        if (fields.next() != null) @panic("too many logtags fields");
        const run = b.addSystemCommand(&.{"python3"});
        run.addFileArg(aosp.path("build/make/tools/java-event-log-tags.py"));
        run.addArg("-o");
        const output = run.addOutputFileArg(std.fs.path.basename(output_path));
        run.addFileArg(sourcePath(b, aosp, .{ .source = source, .path = input_path }));
        run.setName(b.fmt("generate {s}", .{output_path}));
        _ = merged.addCopyFile(output, output_path);
    }
    _ = expose(b, "gen-logtags", "Generate EventLogTags Java sources", &merged.step);
    return merged.getDirectory();
}

fn aconfigJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) LazyPath {
    const merged = b.addWriteFiles();
    var input_lines = lines(@embedFile("../tools/aconfig-inputs.txt"));
    var index: usize = 0;
    while (input_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        var fields = std.mem.splitScalar(u8, line, '|');
        const package = fields.next() orelse @panic("missing aconfig package");
        const container = fields.next() orelse @panic("missing aconfig container");
        const source = std.meta.stringToEnum(Source, fields.next() orelse @panic("missing aconfig source")) orelse @panic("bad aconfig source");
        const cache = b.addRunArtifact(runner);
        cache.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        cache.addArgs(&.{ "create-cache", "--package", package, "--container", container });
        var declaration_count: usize = 0;
        while (fields.next()) |declaration| {
            cache.addArg("--declarations");
            cache.addFileArg(sourcePath(b, aosp, .{ .source = source, .path = declaration }));
            declaration_count += 1;
        }
        if (declaration_count == 0) @panic("aconfig package has no declarations");
        cache.addArg("--cache");
        const cache_file = cache.addOutputFileArg(b.fmt("aconfig-{d}.pb", .{index}));
        cache.setName(b.fmt("create aconfig cache {s}", .{package}));

        const java = b.addRunArtifact(runner);
        java.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        java.addArgs(&.{ "create-java-lib", "--cache" });
        java.addFileArg(cache_file);
        java.addArg("--out");
        const output = java.addOutputDirectoryArg(b.fmt("aconfig-java-{d}", .{index}));
        java.addArgs(&.{ "--mode", "production" });
        java.setName(b.fmt("generate aconfig Java {s}", .{package}));
        _ = merged.addCopyDirectory(output, "", .{});
        index += 1;
    }
    _ = expose(b, "gen-aconfig", "Generate aconfig Java sources", &merged.step);
    return merged.getDirectory();
}

fn syspropJava(b: *std.Build, aosp: *std.Build.Dependency, tool: *std.Build.Step.Compile) LazyPath {
    const merged = b.addWriteFiles();
    var input_lines = lines(@embedFile("../tools/sysprop-list.txt"));
    var index: usize = 0;
    while (input_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        var fields = std.mem.splitScalar(u8, line, '|');
        const scope = fields.next() orelse @panic("missing sysprop scope");
        const source = std.meta.stringToEnum(Source, fields.next() orelse @panic("missing sysprop source")) orelse @panic("bad sysprop source");
        const path = fields.next() orelse @panic("missing sysprop path");
        if (fields.next() != null) @panic("too many sysprop fields");
        const run = b.addRunArtifact(tool);
        run.addArgs(&.{ "--scope", scope, "--java-output-dir" });
        const output = run.addOutputDirectoryArg(b.fmt("sysprop-java-{d}", .{index}));
        run.addFileArg(sourcePath(b, aosp, .{ .source = source, .path = path }));
        run.setName(b.fmt("generate sysprop Java {s}", .{std.fs.path.basename(path)}));
        _ = merged.addCopyDirectory(output, "", .{});
        index += 1;
    }
    _ = expose(b, "gen-sysprop", "Generate sysprop Java sources", &merged.step);
    return merged.getDirectory();
}

fn nanoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) LazyPath {
    const merged = b.addWriteFiles();
    var input_lines = lines(@embedFile("../tools/nano-list.txt"));
    var index: usize = 0;
    while (input_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        const input = parseSourcePath(line);
        const run = b.addRunArtifact(protoc);
        run.addPrefixedFileArg("--plugin=protoc-gen-javanano=", plugin.getEmittedBin());
        const output = run.addPrefixedOutputDirectoryArg("--javanano_out=", b.fmt("nano-java-{d}", .{index}));
        run.addPrefixedDirectoryArg("-I", sourceDir(b, aosp, input));
        run.addFileArg(sourcePath(b, aosp, input));
        run.setName(b.fmt("generate nano Java {s}", .{std.fs.path.basename(input.path)}));
        _ = merged.addCopyDirectory(output, "", .{});
        index += 1;
    }
    _ = expose(b, "gen-nano", "Generate javanano message sources", &merged.step);
    return merged.getDirectory();
}

fn protoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) LazyPath {
    const run = b.addRunArtifact(protoc);
    run.addPrefixedFileArg("--plugin=protoc-gen-javastream=", plugin.getEmittedBin());
    const output = run.addPrefixedOutputDirectoryArg("--javastream_out=", "javastream-java");
    run.addPrefixedDirectoryArg("-I", aosp.path(""));
    run.addPrefixedDirectoryArg("-I", aosp.path("external/protobuf/src"));
    var input_lines = lines(@embedFile("../tools/proto-list.txt"));
    while (input_lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (line.len == 0 or line[0] == '#') continue;
        run.addFileArg(sourcePath(b, aosp, parseSourcePath(line)));
    }
    run.setName("generate javastream proto Java");
    _ = expose(b, "gen-proto", "Generate framework javastream proto sources", &run.step);
    return output;
}

fn statsJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    stats_tool: *std.Build.Step.Compile,
) LazyPath {
    const stage = b.addWriteFiles();
    const base = "tools/out/etc/stats-log-api-gen";
    _ = stage.addCopyDirectory(aosp.path("frameworks/proto_logging/stats/stats_log_api_gen/include_java"), base ++ "/java", .{});
    _ = stage.addCopyDirectory(aosp.path("frameworks/proto_logging/stats/stats_log_api_gen/include_cc_hdrs"), base ++ "/cc_hdrs", .{});
    _ = stage.addCopyDirectory(aosp.path("frameworks/proto_logging/stats/stats_log_api_gen/include_cc_srcs"), base ++ "/cc_srcs", .{});

    const run = b.addRunArtifact(stats_tool);
    run.setCwd(stage.getDirectory());
    run.addArg("--java");
    const java = run.addOutputFileArg("FrameworkStatsLog.java");
    run.addArgs(&.{
        "--module",      "framework",
        "--javaPackage", "com.android.internal.util",
        "--javaClass",   "FrameworkStatsLog",
    });
    run.setName("generate FrameworkStatsLog.java");
    const output = b.addWriteFiles();
    _ = output.addCopyFile(java, "com/android/internal/util/FrameworkStatsLog.java");
    _ = expose(b, "gen-stats", "Generate FrameworkStatsLog Java source", &output.step);
    return output.getDirectory();
}

fn signApk(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    unsigned: LazyPath,
    output_name: []const u8,
) LazyPath {
    const sign = b.addSystemCommand(&.{ "java", "-jar" });
    sign.addFileArg(aosp.path("prebuilts/sdk/tools/linux/lib/apksigner.jar"));
    sign.addArgs(&.{ "sign", "--key" });
    sign.addFileArg(b.path("tools/signing/azk-platform.pk8"));
    sign.addArg("--cert");
    sign.addFileArg(b.path("tools/signing/azk-platform.x509.pem"));
    sign.addArgs(&.{
        "--v1-signing-enabled", "false",
        "--v2-signing-enabled", "true",
        "--v4-signing-enabled", "false",
        "--in",
    });
    sign.addFileArg(unsigned);
    sign.addArg("--out");
    return sign.addOutputFileArg(output_name);
}

const ResourceResult = struct { directory: LazyPath, apk: LazyPath };

fn resources(b: *std.Build, aosp: *std.Build.Dependency, filter: *std.Build.Step.Compile) ResourceResult {
    const res = aosp.path("frameworks/base/core/res");
    const compile = b.addSystemCommand(&.{ "aapt2", "compile", "--dir" });
    compile.addDirectoryArg(res.path(b, "res"));
    compile.addArgs(&.{ "--feature-flags", resource_flags.csv, "-o" });
    const compiled = compile.addOutputFileArg("framework-res-compiled.zip");
    compile.setName("aapt2 compile framework resources");

    const link = b.addSystemCommand(&.{
        "bash",                 "-c",
        \\exec "$@" 2> >(grep -F -v -e 'warn: removing resource android:string/user_switched without required default value.' -e "warn: duplicate definitions of 'SET_DEFAULT_ACCOUNT_FOR_CONTACTS', overriding previous." -e "warn: duplicate definitions of 'SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE', overriding previous." -e "warn: duplicate definitions of 'READ_BLOCKED_NUMBERS', overriding previous." -e "warn: duplicate definitions of 'ACCESS_SHARED_LIBRARIES', overriding previous." >&2)
        ,
        "framework-aapt2-link", "aapt2",
        "link",                 "-o",
    });
    const unsigned = link.addOutputFileArg("framework-res-unsigned.apk");
    link.addArgs(&.{ "--feature-flags", resource_flags.csv, "--manifest" });
    link.addFileArg(res.path(b, "AndroidManifest.xml"));
    link.addArg("-A");
    link.addDirectoryArg(res.path(b, "assets"));
    link.addArgs(&.{ "--private-symbols", "com.android.internal", "--java" });
    const generated_java = link.addOutputDirectoryArg("framework-res-java");
    link.addArgs(&.{
        "--min-sdk-version", "35",                 "--target-sdk-version", "35",
        "--no-auto-version", "--auto-add-overlay", "--no-compress",        "--enable-sparse-encoding",
        "-x",
    });
    link.addFileArg(compiled);
    link.setName("aapt2 link framework resources");

    const output = b.addWriteFiles();
    inline for (.{
        "android/Manifest.java",
        "android/R.java",
        "com/android/internal/R.java",
    }) |path| {
        const run = b.addRunArtifact(filter);
        run.addFileArg(generated_java.path(b, path));
        const filtered = run.addOutputFileArg(std.fs.path.basename(path));
        run.setName(b.fmt("filter generated {s}", .{path}));
        _ = output.addCopyFile(filtered, "java/" ++ path);
    }
    const signed = signApk(b, aosp, unsigned, "framework-res.apk");
    _ = output.addCopyFile(signed, "framework-res.apk");
    _ = expose(b, "gen-res", "Compile, link, and sign framework resources", &output.step);
    return .{ .directory = output.getDirectory(), .apk = signed };
}

fn fixtureApks(b: *std.Build, aosp: *std.Build.Dependency, framework_res: LazyPath) LazyPath {
    const packages = [_][]const u8{
        "dev.artzig.test.a",
        "dev.artzig.test.b",
        "org.example",
        "org.example.permissions",
    };
    const output = b.addWriteFiles();
    inline for (packages) |package| {
        const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
        const unsigned = link.addOutputFileArg(package ++ "-unsigned.apk");
        link.addArg("--manifest");
        link.addFileArg(b.path("testdata/fixture-apks/" ++ package ++ "/AndroidManifest.xml"));
        link.addArg("-I");
        link.addFileArg(framework_res);
        link.addArgs(&.{ "--min-sdk-version", "21", "--target-sdk-version", "35" });
        link.setName(b.fmt("aapt2 link fixture {s}", .{package}));
        const signed = signApk(b, aosp, unsigned, package ++ ".apk");
        _ = output.addCopyFile(signed, package ++ ".apk");
    }
    _ = expose(b, "gen-fixture-apks", "Build and sign fixture APKs", &output.step);
    return output.getDirectory();
}

pub fn build(b: *std.Build, o: Options) Result {
    const runner = hostTool(b, "aosp-tool-runner", "tools/aosp-tool-runner.zig");
    const filter = hostTool(b, "filter-flagged-api", "tools/filter-flagged-api.zig");
    const stats_tool = @import("stats_tool.zig").build(b, .{
        .aosp = o.aosp.path(""),
        .target = b.graph.host,
        .protobuf = o.protobuf,
        .protoc = o.protoc,
    });
    _ = expose(b, "stats-tool", "Build stats-log-api-gen", &stats_tool.step);

    const aidl_java = aidlJava(b, o.aosp, runner);
    const aidl_cpp = aidlCpp(b, o.aosp, runner);
    const logtags_java = logtagsJava(b, o.aosp);
    const aconfig_java = aconfigJava(b, o.aosp, runner);
    const sysprop_java = syspropJava(b, o.aosp, o.sysprop_tool);
    const nano_java = nanoJava(b, o.aosp, o.protoc, o.javanano_tool);
    const proto_java = protoJava(b, o.aosp, o.protoc, o.javastream_tool);
    const stats_java = statsJava(b, o.aosp, stats_tool);
    const res = resources(b, o.aosp, filter);
    const fixture_apks_dir = fixtureApks(b, o.aosp, res.apk);
    const java_files = generatedJavaFiles(
        b,
        aidl_java,
        logtags_java,
        aconfig_java,
        sysprop_java,
        nano_java,
        proto_java,
        stats_java,
        res.directory,
    );

    const aggregate = b.step("generators", "Generate all framework sources, resources, and fixture APKs");
    inline for (.{
        aidl_java, aidl_cpp,   logtags_java, aconfig_java,  sysprop_java,
        nano_java, proto_java, stats_java,   res.directory, fixture_apks_dir,
    }) |path| path.addStepDependencies(aggregate);

    return .{
        .aidl_java = aidl_java,
        .aidl_cpp = aidl_cpp,
        .logtags_java = logtags_java,
        .aconfig_java = aconfig_java,
        .sysprop_java = sysprop_java,
        .nano_java = nano_java,
        .proto_java = proto_java,
        .stats_java = stats_java,
        .res_dir = res.directory,
        .res_apk = res.apk,
        .fixture_apks_dir = fixture_apks_dir,
        .java_files = java_files,
        .aggregate_step = aggregate,
    };
}
