//! Cacheable Java, native-header, resource, and fixture generators.
const std = @import("std");
const resource_flags = @import("gen/resource_flags.zig");
const declarations = @import("generator_sources.zig");
const command = @import("command.zig");

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
    jvm: GeneratedJvm,
    native: GeneratedNative,
    resources: FrameworkResources,
    fixtures: FixtureApks,
    aggregate_step: *std.Build.Step,
};

pub const GeneratedJvm = struct {
    sources: []const LazyPath,
};

pub const GeneratedNative = struct {
    aidl_header_roots: []const LazyPath,
};

pub const FrameworkResources = struct {
    apk: LazyPath,
    java_sources: []const LazyPath,
};

pub const FixtureApks = struct {
    test_a: LazyPath,
    test_b: LazyPath,
    example: LazyPath,
    permissions: LazyPath,

    pub fn all(self: FixtureApks) [4]LazyPath {
        return .{ self.test_a, self.test_b, self.example, self.permissions };
    }
};

const Source = declarations.Source;
const SourcePath = declarations.SourcePath;

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
        "frameworks/base/cmds/idmap2/idmap2d/aidl/core/",
        "frameworks/base/location/java/",
        "frameworks/base/telephony/java/",
        "frameworks/base/telecomm/java/",
        "frameworks/base/mms/java/",
    }) |prefix| {
        if (std.mem.startsWith(u8, input, prefix)) {
            return replaceExtension(b, input[prefix.len..], ".java");
        }
    }
    std.debug.panic("no Java output mapping for AIDL input {s}", .{input});
}

fn expose(b: *std.Build, name: []const u8, description: []const u8, dependency: *std.Build.Step) *std.Build.Step {
    const step = b.step(name, description);
    step.dependOn(dependency);
    return step;
}

fn exposePaths(b: *std.Build, name: []const u8, description: []const u8, paths: []const LazyPath) *std.Build.Step {
    const step = b.step(name, description);
    for (paths) |path| path.addStepDependencies(step);
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
        "frameworks/base/cmds/idmap2/idmap2d/aidl/core",
        "frameworks/base/location/java",
        "frameworks/base/telephony/java",
        "frameworks/base/telecomm/java",
        "frameworks/base/mms/java",
    }) |path| run.addPrefixedDirectoryArg("-I", aosp.path(path));
}

fn aidlJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) []const LazyPath {
    // The native permission AIDL lib (AttributionSourceState, IAppOpsCallback,
    // IPermissionChecker) is staged into the compat root rather than added as
    // an -I root, so its parcelable declarations resolve after core/java's.
    const permission_aidl = "frameworks/native/libs/permission/aidl/";
    const compat = b.addWriteFiles();
    _ = compat.addCopyDirectory(b.path("aidl-compat"), "", .{});
    _ = compat.addCopyDirectory(aosp.path(permission_aidl), "", .{ .include_extensions = &.{".aidl"} });
    const compat_dir = compat.getDirectory();

    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.aidl) |input| {
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
        files.append(b.allocator, output.path(b, aidlOutputPath(b, input.path))) catch @panic("OOM");
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-aidl", "Generate framework Java AIDL sources", result);
    return result;
}

fn aidlCpp(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) []const LazyPath {
    const inputs = [_][]const u8{
        "IInputConstants.aidl",
        "InputConfig.aidl",
        "MotionEventFlag.aidl",
        "PointerIconType.aidl",
        "InputEventInjectionSync.aidl",
        "InputEventInjectionResult.aidl",
    };
    const root = aosp.path("frameworks/native/libs/input");
    var roots: std.ArrayList(LazyPath) = .empty;
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
        roots.append(b.allocator, headers) catch @panic("OOM");
        _ = sources;
    }
    const result = roots.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-aidl-cpp", "Generate native input AIDL headers", result);
    return result;
}

fn logtagsJava(b: *std.Build, aosp: *std.Build.Dependency) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    for (declarations.logtags) |entry| {
        const run = b.addSystemCommand(&.{"python3"});
        run.addFileArg(aosp.path("build/make/tools/java-event-log-tags.py"));
        run.addArg("-o");
        const output = run.addOutputFileArg(entry.output);
        run.addFileArg(sourcePath(b, aosp, entry.input));
        run.setName(b.fmt("generate {s}", .{entry.output}));
        files.append(b.allocator, output) catch @panic("OOM");
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-logtags", "Generate EventLogTags Java sources", result);
    return result;
}

fn aconfigJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.aconfig) |entry| {
        const cache = b.addRunArtifact(runner);
        cache.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        cache.addArgs(&.{ "create-cache", "--package", entry.package, "--container", entry.container });
        for (entry.declarations) |declaration| {
            cache.addArg("--declarations");
            cache.addFileArg(sourcePath(b, aosp, .{ .source = entry.source, .path = declaration }));
        }
        cache.addArg("--cache");
        const cache_file = cache.addOutputFileArg(b.fmt("aconfig-{d}.pb", .{index}));
        cache.setName(b.fmt("create aconfig cache {s}", .{entry.package}));

        const java = b.addRunArtifact(runner);
        java.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        java.addArgs(&.{ "create-java-lib", "--cache" });
        java.addFileArg(cache_file);
        java.addArg("--out");
        const output = java.addOutputDirectoryArg(b.fmt("aconfig-java-{d}", .{index}));
        java.addArgs(&.{ "--mode", "production" });
        java.setName(b.fmt("generate aconfig Java {s}", .{entry.package}));
        const package_path = std.mem.replaceOwned(u8, b.allocator, entry.package, ".", "/") catch @panic("OOM");
        inline for (.{ "CustomFeatureFlags", "FakeFeatureFlagsImpl", "FeatureFlags", "FeatureFlagsImpl", "Flags" }) |class| {
            files.append(b.allocator, output.path(b, b.fmt("{s}/{s}.java", .{ package_path, class }))) catch @panic("OOM");
        }
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-aconfig", "Generate aconfig Java sources", result);
    return result;
}

fn syspropJava(b: *std.Build, aosp: *std.Build.Dependency, tool: *std.Build.Step.Compile) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.sysprop) |entry| {
        const run = b.addRunArtifact(tool);
        run.addArgs(&.{ "--scope", entry.scope, "--java-output-dir" });
        const output = run.addOutputDirectoryArg(b.fmt("sysprop-java-{d}", .{index}));
        run.addFileArg(sourcePath(b, aosp, entry.input));
        run.setName(b.fmt("generate sysprop Java {s}", .{std.fs.path.basename(entry.input.path)}));
        files.append(b.allocator, output.path(b, b.fmt("android/sysprop/{s}", .{replaceExtension(b, std.fs.path.basename(entry.input.path), ".java")}))) catch @panic("OOM");
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-sysprop", "Generate sysprop Java sources", result);
    return result;
}

fn nanoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.nano) |input| {
        const run = b.addRunArtifact(protoc);
        run.addPrefixedFileArg("--plugin=protoc-gen-javanano=", plugin.getEmittedBin());
        const output = run.addPrefixedOutputDirectoryArg("--javanano_out=", b.fmt("nano-java-{d}", .{index}));
        run.addPrefixedDirectoryArg("-I", sourceDir(b, aosp, input));
        run.addFileArg(sourcePath(b, aosp, input));
        run.setName(b.fmt("generate nano Java {s}", .{std.fs.path.basename(input.path)}));
        files.append(b.allocator, output.path(b, "com/android/internal/logging/nano/MetricsProto.java")) catch @panic("OOM");
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-nano", "Generate javanano message sources", result);
    return result;
}

fn protoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) []const LazyPath {
    const run = b.addRunArtifact(protoc);
    run.addPrefixedFileArg("--plugin=protoc-gen-javastream=", plugin.getEmittedBin());
    const output = run.addPrefixedOutputDirectoryArg("--javastream_out=", "javastream-java");
    run.addPrefixedDirectoryArg("-I", aosp.path(""));
    run.addPrefixedDirectoryArg("-I", aosp.path("external/protobuf/src"));
    for (declarations.proto_inputs) |input| run.addFileArg(sourcePath(b, aosp, input));
    run.setName("generate javastream proto Java");
    const files = b.allocator.alloc(LazyPath, declarations.proto_outputs.len) catch @panic("OOM");
    for (declarations.proto_outputs, 0..) |path, index| files[index] = output.path(b, path);
    _ = exposePaths(b, "gen-proto", "Generate framework javastream proto sources", files);
    return files;
}

fn statsJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    stats_tool: *std.Build.Step.Compile,
) []const LazyPath {
    const stage = b.addWriteFiles();
    const base = "tools/out/etc/stats-log-api-gen";
    _ = stage.addCopyDirectory(aosp.path("frameworks/proto_logging/stats/stats_log_api_gen/include_java"), base ++ "/java", .{});
    _ = stage.addCopyDirectory(aosp.path("frameworks/proto_logging/stats/stats_log_api_gen/include_cc_hdrs"), base ++ "/cc_hdrs", .{});
    _ = stage.addCopyDirectory(aosp.path("frameworks/proto_logging/stats/stats_log_api_gen/include_cc_srcs"), base ++ "/cc_srcs", .{});

    // One run per stats-log module, as Soong's stats-log-api-gen genrules do:
    // the framework's FrameworkStatsLog, telephony's TelephonyStatsLog (AnomalyReporter)
    // and telephony-common's TelephonyCommonStatsLog (TelephonyPermissions).
    const specs = .{
        .{ .module = "framework", .package = "com.android.internal.util", .class = "FrameworkStatsLog" },
        .{ .module = "telephony", .package = "com.android.internal.telephony", .class = "TelephonyStatsLog" },
        .{ .module = "telephony_common", .package = "com.android.internal.telephony", .class = "TelephonyCommonStatsLog" },
    };
    const result = b.allocator.alloc(LazyPath, specs.len) catch @panic("OOM");
    inline for (specs, 0..) |spec, index| {
        const run = b.addRunArtifact(stats_tool);
        run.setCwd(stage.getDirectory());
        run.addArg("--java");
        const java = run.addOutputFileArg(spec.class ++ ".java");
        run.addArgs(&.{
            "--module",      spec.module,
            "--javaPackage", spec.package,
            "--javaClass",   spec.class,
        });
        run.setName("generate " ++ spec.class ++ ".java");
        result[index] = java;
    }
    _ = exposePaths(b, "gen-stats", "Generate stats-log Java sources", result);
    return result;
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

fn resources(b: *std.Build, aosp: *std.Build.Dependency, filter: *std.Build.Step.Compile) FrameworkResources {
    const res = aosp.path("frameworks/base/core/res");
    const compile = b.addSystemCommand(&.{ "aapt2", "compile", "--dir" });
    compile.addDirectoryArg(res.path(b, "res"));
    compile.addArgs(&.{ "--feature-flags", resource_flags.csv, "-o" });
    const compiled = compile.addOutputFileArg("framework-res-compiled.zip");
    compile.setName("aapt2 compile framework resources");
    const remote_colors = remoteColorResources(b, res);

    const link = command.create(b);
    link.addArgs(&.{ "aapt2", "link", "-o" });
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
    link.addFileArg(remote_colors);
    inline for (.{
        "warn: removing resource android:string/user_switched without required default value.",
        "warn: duplicate definitions of 'SET_DEFAULT_ACCOUNT_FOR_CONTACTS', overriding previous.",
        "warn: duplicate definitions of 'SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE', overriding previous.",
        "warn: duplicate definitions of 'READ_BLOCKED_NUMBERS', overriding previous.",
        "warn: duplicate definitions of 'ACCESS_SHARED_LIBRARIES', overriding previous.",
    }) |pattern| link.filterStderr(pattern);
    link.run.setName("aapt2 link framework resources");

    var java_sources: std.ArrayList(LazyPath) = .empty;
    inline for (.{
        "android/Manifest.java",
        "android/R.java",
        "com/android/internal/R.java",
    }) |path| {
        const run = b.addRunArtifact(filter);
        run.addFileArg(generated_java.path(b, path));
        const filtered = run.addOutputFileArg(std.fs.path.basename(path));
        run.setName(b.fmt("filter generated {s}", .{path}));
        java_sources.append(b.allocator, filtered) catch @panic("OOM");
    }
    const signed = signApk(b, aosp, unsigned, "framework-res.apk");
    const result = java_sources.toOwnedSlice(b.allocator) catch @panic("OOM");
    var outputs: std.ArrayList(LazyPath) = .empty;
    outputs.appendSlice(b.allocator, result) catch @panic("OOM");
    outputs.append(b.allocator, signed) catch @panic("OOM");
    _ = exposePaths(b, "gen-res", "Compile, link, and sign framework resources", outputs.items);
    return .{ .java_sources = result, .apk = signed };
}

/// The Soong genrules remote-color-resources-{compile-public,compile-colors,apk,arsc}
/// of frameworks/base/core/res/Android.bp: the color resources RemoteViews may re-theme
/// are linked into their own package and its resources.arsc ships as the raw framework
/// resource R.raw.remote_views_color_resources. Returns the compiled overlay for link.
fn remoteColorResources(b: *std.Build, res: LazyPath) LazyPath {
    const compile = b.addSystemCommand(&.{ "aapt2", "compile", "-o" });
    const flats = compile.addOutputDirectoryArg("remote-color-resources-flat");
    compile.addFileArg(res.path(b, "remote_color_resources_res/values/public.xml"));
    compile.addFileArg(res.path(b, "remote_color_resources_res/values/colors.xml"));
    compile.setName("aapt2 compile remote color resources");

    const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
    const apk = link.addOutputFileArg("remote-color-resources.apk");
    link.addArg("--manifest");
    link.addFileArg(res.path(b, "RemoteThemeColorsAndroidManifest.xml"));
    link.addFileArg(flats.path(b, "values_public.arsc.flat"));
    link.addFileArg(flats.path(b, "values_colors.arsc.flat"));
    link.setName("aapt2 link remote color resources");

    const extract = b.addSystemCommand(&.{ "bash", "-c", "unzip -p \"$1\" resources.arsc > \"$2\"", "remote-color-resources-arsc" });
    extract.addFileArg(apk);
    const arsc = extract.addOutputFileArg("remote_views_color_resources.arsc");
    extract.setName("extract remote color resources.arsc");

    // The raw resource is private: its R.raw symbol exists only through the
    // <java-symbol> declaration the genrule zips next to it.
    const overlay = b.addWriteFiles();
    _ = overlay.addCopyFile(arsc, "res/raw/remote_views_color_resources.arsc");
    _ = overlay.addCopyFile(res.path(b, "remote_color_resources_res/symbols.xml"), "res/values/remote_views_symbols.xml");
    const compile_overlay = b.addSystemCommand(&.{ "aapt2", "compile", "--dir" });
    compile_overlay.addDirectoryArg(overlay.getDirectory().path(b, "res"));
    compile_overlay.addArg("-o");
    const compiled = compile_overlay.addOutputFileArg("remote-color-resources-raw.zip");
    compile_overlay.setName("aapt2 compile remote color raw resource");
    return compiled;
}

fn fixtureApks(b: *std.Build, aosp: *std.Build.Dependency, framework_res: LazyPath) FixtureApks {
    const packages = [_][]const u8{
        "dev.artzig.test.a",
        "dev.artzig.test.b",
        "org.example",
        "org.example.permissions",
    };
    var outputs: [packages.len]LazyPath = undefined;
    inline for (packages, 0..) |package, index| {
        const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
        const unsigned = link.addOutputFileArg(package ++ "-unsigned.apk");
        link.addArg("--manifest");
        link.addFileArg(b.path("testdata/fixture-apks/" ++ package ++ "/AndroidManifest.xml"));
        link.addArg("-I");
        link.addFileArg(framework_res);
        link.addArgs(&.{ "--min-sdk-version", "21", "--target-sdk-version", "35" });
        link.setName(b.fmt("aapt2 link fixture {s}", .{package}));
        const signed = signApk(b, aosp, unsigned, package ++ ".apk");
        outputs[index] = signed;
    }
    _ = exposePaths(b, "gen-fixture-apks", "Build and sign fixture APKs", &outputs);
    return .{
        .test_a = outputs[0],
        .test_b = outputs[1],
        .example = outputs[2],
        .permissions = outputs[3],
    };
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
    const fixtures = fixtureApks(b, o.aosp, res.apk);
    var java_files: std.ArrayList(LazyPath) = .empty;
    inline for (.{ aidl_java, logtags_java, aconfig_java, sysprop_java, nano_java, proto_java, stats_java, res.java_sources }) |generated_set| {
        java_files.appendSlice(b.allocator, generated_set) catch @panic("OOM");
    }

    const aggregate = b.step("generators", "Generate all framework sources, resources, and fixture APKs");
    for (java_files.items) |path| path.addStepDependencies(aggregate);
    for (aidl_cpp) |path| path.addStepDependencies(aggregate);
    res.apk.addStepDependencies(aggregate);
    for (fixtures.all()) |path| path.addStepDependencies(aggregate);

    return .{
        .jvm = .{ .sources = java_files.toOwnedSlice(b.allocator) catch @panic("OOM") },
        .native = .{ .aidl_header_roots = aidl_cpp },
        .resources = res,
        .fixtures = fixtures,
        .aggregate_step = aggregate,
    };
}
