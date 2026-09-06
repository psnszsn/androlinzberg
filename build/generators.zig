//! Cacheable Java, native-header, resource, and fixture generators.
const std = @import("std");
const resource_flags = @import("gen/resource_flags.zig");
const declarations = @import("generator_sources.zig");
const command = @import("command.zig");
const SourceFiles = @import("artzig").SourceSet;

const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    aosp: *std.Build.Dependency,
    protobuf: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
    sysprop_tool: *std.Build.Step.Compile,
    sysprop_cpp_tool: *std.Build.Step.Compile,
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
    resources: LazyPath,
    system_features_processor: LazyPath,
    system_features_processor_dependencies: LazyPath,
};

pub const GeneratedNative = struct {
    incremental_sysprop_headers: LazyPath,
    incremental_sysprop_source: LazyPath,
    aidl_header_roots: []const LazyPath,
    aidl_source_roots: []const LazyPath,
    aconfig_header_roots: []const LazyPath,
    binder_graphics_headers: LazyPath,
    binder_graphics_sources: LazyPath,
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
    identity_a: LazyPath,
    identity_b: LazyPath,

    pub fn all(self: FixtureApks) [6]LazyPath {
        return .{ self.test_a, self.test_b, self.example, self.permissions, self.identity_a, self.identity_b };
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
        "frameworks/av/aidl/",
        "frameworks/av/media/libaudioclient/aidl/",
        "system/hardware/interfaces/media/aidl/",
        "frameworks/base/keystore/java/",
        "frameworks/base/apex/jobscheduler/framework/java/",
        "frameworks/base/native/android/aidl/",
        "frameworks/native/libs/input/",
        "frameworks/native/libs/gui/",
        "frameworks/native/aidl/",
        "frameworks/native/libs/binder/aidl/",
        "frameworks/native/libs/incidentcompanion/binder/",
        "frameworks/native/libs/permission/aidl/",
        "packages/modules/StatsD/aidl/",
        "frameworks/native/cmds/installd/binder/",
        "system/apex/apexd/aidl/",
        "frameworks/base/cmds/idmap2/idmap2d/aidl/core/",
        "frameworks/base/location/java/",
        "frameworks/base/telephony/java/",
        "frameworks/base/telecomm/java/",
        "frameworks/base/mms/java/",
        "hardware/interfaces/gnss/aidl/",
        "hardware/interfaces/biometrics/common/aidl/",
        "hardware/interfaces/biometrics/face/aidl/",
        "hardware/interfaces/biometrics/fingerprint/aidl/",
        "hardware/interfaces/common/aidl/",
        "hardware/interfaces/keymaster/aidl/",
        "hardware/interfaces/security/keymint/aidl/",
        "system/hardware/interfaces/keystore2/aidl/",
        "system/core/gatekeeperd/binder/",
        "system/vold/binder/",
        "hardware/interfaces/graphics/common/aidl/",
        "hardware/interfaces/health/aidl/",
        "hardware/interfaces/radio/aidl/",
        "hardware/interfaces/thermal/aidl/",
        "hardware/interfaces/usb/aidl/",
        "hardware/interfaces/usb/gadget/aidl/",
        "hardware/interfaces/vibrator/aidl/",
        "packages/modules/AppSearch/framework/java/",
        "packages/modules/Bluetooth/android/app/aidl/",
        "packages/modules/Bluetooth/common/",
        "packages/modules/Bluetooth/framework/aidl-export/",
        "packages/modules/Bluetooth/service/aidl/",
        "packages/modules/ConfigInfrastructure/framework/java/",
        "packages/modules/HealthFitness/framework/java/",
        "packages/modules/Connectivity/Tethering/common/TetheringLib/src/",
        "packages/modules/Connectivity/framework-t/src/",
        "packages/modules/Connectivity/framework/src/",
        "packages/modules/Media/apex/aidl/private/",
        "packages/modules/Permission/framework-s/java/",
        "packages/modules/Wifi/framework/java/",
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
        "packages/modules/Connectivity/framework-t/src",
        "frameworks/base/media/java",
        "frameworks/av/aidl",
        "frameworks/av/media/libaudioclient/aidl",
        "system/hardware/interfaces/media/aidl",
        "frameworks/base/keystore/java",
        "frameworks/libs/modules-utils/java",
        "frameworks/native/libs/input",
        "frameworks/native/libs/gui",
        "frameworks/native/aidl/binder",
        "frameworks/native/aidl",
        "frameworks/native/libs/binder/aidl",
        "frameworks/native/libs/incidentcompanion/binder",
        "packages/modules/StatsD/aidl",
        "frameworks/native/cmds/installd/binder",
        "system/apex/apexd/aidl",
        "frameworks/base/apex/jobscheduler/framework/java",
        "frameworks/base/native/android/aidl",
        "frameworks/base/cmds/idmap2/idmap2d/aidl/core",
        "frameworks/base/location/java",
        "frameworks/base/telephony/java",
        "frameworks/base/telecomm/java",
        "frameworks/base/mms/java",
        "hardware/interfaces/gnss/aidl",
        "hardware/interfaces/biometrics/common/aidl",
        "hardware/interfaces/biometrics/face/aidl",
        "hardware/interfaces/biometrics/fingerprint/aidl",
        "hardware/interfaces/common/aidl",
        "hardware/interfaces/keymaster/aidl",
        "hardware/interfaces/security/keymint/aidl",
        "system/hardware/interfaces/keystore2/aidl",
        "system/core/gatekeeperd/binder",
        "system/vold/binder",
        "hardware/interfaces/graphics/common/aidl",
        "hardware/interfaces/health/aidl",
        "hardware/interfaces/radio/aidl",
        "hardware/interfaces/thermal/aidl",
        "hardware/interfaces/usb/aidl",
        "hardware/interfaces/usb/gadget/aidl",
        "hardware/interfaces/vibrator/aidl",
        "packages/modules/AppSearch/framework/java",
        "packages/modules/Bluetooth/android/app/aidl",
        "packages/modules/Bluetooth/common",
        "packages/modules/Bluetooth/framework/aidl-export",
        "packages/modules/Bluetooth/service/aidl",
        "packages/modules/ConfigInfrastructure/framework/java",
        "packages/modules/HealthFitness/framework/java",
        "packages/modules/Connectivity/Tethering/common/TetheringLib/src",
        "packages/modules/Connectivity/framework-t/src",
        "packages/modules/Connectivity/framework/src",
        "packages/modules/Media/apex/aidl/private",
        "packages/modules/Permission/framework-s/java",
        "packages/modules/Wifi/framework/java",
        "packages/modules/Wifi/framework/aidl-export",
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
    for (declarations.aidl_exports) |entry| {
        _ = compat.addCopyFile(sourcePath(b, aosp, entry.input), entry.output);
    }
    const compat_dir = compat.getDirectory();

    // Mainline Bluetooth's socket AIDL refers to the SDK parcelable by its
    // short name. Supply the same preprocessed SDK declaration Soong sees.
    const sdk_parcelables = b.addRunArtifact(runner);
    sdk_parcelables.addFileArg(aosp.path("prebuilts/sdk/tools/linux/bin/aidl"));
    sdk_parcelables.addArg("--preprocess");
    const sdk_preprocessed = sdk_parcelables.addOutputFileArg("framework-parcelables.aidl");
    sdk_parcelables.addFileArg(aosp.path("frameworks/base/core/java/android/content/AttributionSource.aidl"));

    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.aidl) |input| {
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path("prebuilts/sdk/tools/linux/bin/aidl"));
        if (input.source == .aosp and
            (std.mem.startsWith(u8, input.path, "hardware/interfaces/") or
                std.mem.startsWith(u8, input.path, "system/hardware/interfaces/")))
        {
            run.addArgs(&.{ "--structured", "--min_sdk_version=35" });
            // Virtual HAL control APIs are explicitly unstable; the device
            // HAL interfaces alongside them are VINTF-stable.
            if (std.mem.indexOf(u8, input.path, "/virtualhal/") == null) {
                run.addArgs(&.{ "--stability", "vintf" });
            }
        }
        addAidlIncludes(run, aosp, compat_dir);
        run.addPrefixedFileArg("-p", sdk_preprocessed);
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

fn hidlJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    for (declarations.hidl, 0..) |entry, index| {
        const run = b.addRunArtifact(runner);
        run.addArg("--library-dir");
        run.addDirectoryArg(aosp.path("prebuilts/build-tools/linux-x86/lib64"));
        run.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/hidl-gen"));
        run.addArg(switch (entry.language) {
            .java => "-Ljava",
            .java_constants => "-Ljava-constants",
        });
        run.addArg("-R");
        run.addPrefixedDirectoryArg("-randroid.hardware:", aosp.path("hardware/interfaces"));
        run.addPrefixedDirectoryArg("-randroid.hidl:", aosp.path("system/libhidl/transport"));
        run.addArg("-o");
        const output = run.addOutputDirectoryArg(b.fmt("hidl-java-{d}", .{index}));
        run.addArg(entry.fqname);
        run.setName(b.fmt("generate HIDL Java {s}", .{entry.fqname}));
        for (entry.outputs) |path| {
            files.append(b.allocator, output.path(b, path)) catch @panic("OOM");
        }
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-hidl", "Generate framework HIDL Java sources", result);
    return result;
}

const CppAidlResult = struct { headers: []const LazyPath, sources: []const LazyPath };

fn aidlCpp(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    runner: *std.Build.Step.Compile,
) CppAidlResult {
    var roots: std.ArrayList(LazyPath) = .empty;
    var files: std.ArrayList(LazyPath) = .empty;
    for (declarations.cpp_aidl, 0..) |entry, index| {
        const root = aosp.path(entry.root);
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path("prebuilts/sdk/tools/linux/bin/aidl"));
        run.addArgs(&.{ b.fmt("--lang={s}", .{@tagName(entry.backend)}), "-I" });
        run.addDirectoryArg(root);
        if (entry.vintf_stable) run.addArgs(&.{ "--structured", "--stability=vintf" });
        run.addArg("-h");
        const headers = run.addOutputDirectoryArg(b.fmt("aidl-cpp-headers-{d}", .{index}));
        run.addArg("-o");
        const sources = run.addOutputDirectoryArg(b.fmt("aidl-cpp-sources-{d}", .{index}));
        run.addFileArg(root.path(b, entry.input));
        run.setName(b.fmt("generate {s} AIDL {s}", .{ @tagName(entry.backend), entry.input }));
        roots.append(b.allocator, headers) catch @panic("OOM");
        files.append(b.allocator, sources) catch @panic("OOM");
    }
    const headers = roots.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-aidl-cpp", "Generate native AIDL headers and parcelables", headers);
    return .{ .headers = headers, .sources = files.toOwnedSlice(b.allocator) catch @panic("OOM") };
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
) struct { java: []const LazyPath, cpp_headers: []const LazyPath } {
    var files: std.ArrayList(LazyPath) = .empty;
    var headers: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.aconfig) |entry| {
        const mode = switch (entry.mode) {
            .production => "production",
            .force_read_only => "force-read-only",
        };
        const cache = b.addRunArtifact(runner);
        cache.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        cache.addArgs(&.{ "create-cache", "--package", entry.package, "--container", entry.container });
        if (entry.default_permission == .read_only) cache.addArgs(&.{ "--default-permission", "READ_ONLY" });
        for (entry.declarations) |declaration| {
            cache.addArg("--declarations");
            cache.addFileArg(sourcePath(b, aosp, .{ .source = entry.source, .path = declaration }));
        }
        for (entry.values) |value| {
            cache.addArg("--values");
            cache.addFileArg(sourcePath(b, aosp, .{ .source = entry.source, .path = value }));
        }
        cache.addArg("--cache");
        const cache_file = cache.addOutputFileArg(b.fmt("aconfig-{d}.pb", .{index}));
        cache.setName(b.fmt("create aconfig cache {s}", .{entry.package}));

        if (entry.cpp_header) {
            const cpp = b.addRunArtifact(runner);
            cpp.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
            cpp.addArgs(&.{ "create-cpp-lib", "--cache" });
            cpp.addFileArg(cache_file);
            cpp.addArg("--out");
            const output = cpp.addOutputDirectoryArg(b.fmt("aconfig-cpp-{d}", .{index}));
            cpp.addArgs(&.{ "--mode", mode });
            cpp.setName(b.fmt("generate aconfig C++ header {s}", .{entry.package}));
            headers.append(b.allocator, output.path(b, "include")) catch @panic("OOM");
        }
        const java = b.addRunArtifact(runner);
        java.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        java.addArgs(&.{ "create-java-lib", "--cache" });
        java.addFileArg(cache_file);
        java.addArg("--out");
        const output = java.addOutputDirectoryArg(b.fmt("aconfig-java-{d}", .{index}));
        java.addArgs(&.{ "--mode", mode });
        java.setName(b.fmt("generate aconfig Java {s}", .{entry.package}));
        const package_path = std.mem.replaceOwned(u8, b.allocator, entry.package, ".", "/") catch @panic("OOM");
        inline for (.{ "CustomFeatureFlags", "FakeFeatureFlagsImpl", "FeatureFlags", "FeatureFlagsImpl", "Flags" }) |class| {
            files.append(b.allocator, output.path(b, b.fmt("{s}/{s}.java", .{ package_path, class }))) catch @panic("OOM");
        }
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-aconfig", "Generate aconfig Java sources", result);
    _ = exposePaths(b, "gen-aconfig-cpp", "Generate fixed read-only aconfig C++ headers", headers.items);
    return .{ .java = result, .cpp_headers = headers.toOwnedSlice(b.allocator) catch @panic("OOM") };
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

fn incrementalSyspropCpp(b: *std.Build, aosp: *std.Build.Dependency, tool: *std.Build.Step.Compile) struct { headers: LazyPath, source: LazyPath } {
    const declaration = declarations.incremental_sysprop;
    const run = b.addRunArtifact(tool);
    run.addArg("--header-dir");
    const headers = run.addOutputDirectoryArg("incremental-sysprop-headers");
    run.addArg("--public-header-dir");
    _ = run.addOutputDirectoryArg("incremental-sysprop-public-headers");
    run.addArg("--source-dir");
    const sources = run.addOutputDirectoryArg("incremental-sysprop-sources");
    run.addArgs(&.{ "--include-name", declaration.header });
    run.addFileArg(sourcePath(b, aosp, declaration.input));
    run.setName("generate IncFs sysprop C++");
    return .{ .headers = headers, .source = sources.path(b, declaration.source) };
}

fn aconfigDevicePathsJava(b: *std.Build, aosp: *std.Build.Dependency) LazyPath {
    const declaration = declarations.aconfig_device_paths;
    // The exact substitution used by libaconfig_java_device_paths_src in AOSP's Android.bp.
    const run = command.create(b);
    run.addArgs(&.{ "sed", "-e" });
    run.addPrefixedFileArg("/TEMPLATE/{r ", sourcePath(b, aosp, declaration.paths));
    run.addArgs(&.{ "-e", "d}" });
    run.addFileArg(sourcePath(b, aosp, declaration.template));
    run.run.setName("generate aconfig device proto paths");
    return run.run.captureStdOut(.{ .basename = std.fs.path.basename(declaration.output) });
}

fn nanoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.nano) |declaration| {
        const input = declaration.input;
        const run = b.addRunArtifact(protoc);
        run.addPrefixedFileArg("--plugin=protoc-gen-javanano=", plugin.getEmittedBin());
        const output = run.addPrefixedOutputDirectoryArg("--javanano_out=", b.fmt("nano-java-{d}", .{index}));
        run.addPrefixedDirectoryArg("-I", sourceDir(b, aosp, input));
        run.addFileArg(sourcePath(b, aosp, input));
        run.setName(b.fmt("generate nano Java {s}", .{std.fs.path.basename(input.path)}));
        files.append(b.allocator, output.path(b, declaration.output)) catch @panic("OOM");
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-nano", "Generate javanano message sources", result);
    return result;
}

fn frameworkProtoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) []const LazyPath {
    const run = command.create(b);
    run.addFileArg(protoc.getEmittedBin());
    run.addPrefixedFileArg("--plugin=protoc-gen-javastream=", plugin.getEmittedBin());
    const output = run.addPrefixedOutputDirectoryArg("--javastream_out=", "javastream-java");
    run.addPrefixedDirectoryArg("-I", aosp.path(""));
    run.addPrefixedDirectoryArg("-I", aosp.path("external/protobuf/src"));
    for (declarations.proto_inputs) |input| run.addFileArg(sourcePath(b, aosp, input));
    // The pinned battery usage schema retains a stats enum import that protoc
    // diagnoses as unused. Soong permits that warning; keep it visible only as
    // a filtered, known diagnostic while preserving strict stderr handling for
    // every other generator message.
    run.filterStderr("frameworks/base/core/proto/android/os/batteryusagestats.proto:22:1: warning: Import frameworks/proto_logging/stats/enums/os/enums.proto is unused.");
    run.run.setName("generate javastream proto Java");
    const files = b.allocator.alloc(LazyPath, declarations.proto_outputs.len) catch @panic("OOM");
    for (declarations.proto_outputs, 0..) |path, index| files[index] = output.path(b, path);
    return files;
}

fn perfettoProtoJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    protoc: *std.Build.Step.Compile,
    plugin: *std.Build.Step.Compile,
    package_rewriter: *std.Build.Step.Compile,
) []const LazyPath {
    const run = b.addRunArtifact(protoc);
    run.addPrefixedFileArg("--plugin=protoc-gen-javastream=", plugin.getEmittedBin());
    run.addArg(b.fmt("--javastream_opt={s}", .{declarations.perfetto_proto_include_filter}));
    const output = run.addPrefixedOutputDirectoryArg("--javastream_out=", "perfetto-javastream-java");
    run.addPrefixedDirectoryArg("-I", aosp.path("external/perfetto"));
    run.addPrefixedDirectoryArg("-I", aosp.path("external/protobuf/src"));
    for (declarations.perfetto_proto_inputs) |input| run.addFileArg(sourcePath(b, aosp, input));
    run.setName("generate filtered Perfetto javastream proto Java");

    const files = b.allocator.alloc(LazyPath, declarations.perfetto_proto_outputs.len) catch @panic("OOM");
    for (declarations.perfetto_proto_outputs, 0..) |path, index| {
        const basename = std.fs.path.basename(path);
        const rewrite = b.addRunArtifact(package_rewriter);
        rewrite.addFileArg(output.path(b, b.fmt("perfetto/protos/{s}", .{basename})));
        files[index] = rewrite.addOutputFileArg(basename);
        rewrite.addArgs(&.{ "perfetto.protos", "android.internal.perfetto.protos" });
        rewrite.setName(b.fmt("relocate Perfetto proto package in {s}", .{basename}));
    }
    return files;
}

fn protoJava(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    protoc: *std.Build.Step.Compile,
    plugin: *std.Build.Step.Compile,
    package_rewriter: *std.Build.Step.Compile,
) []const LazyPath {
    const framework_files = frameworkProtoJava(b, aosp, protoc, plugin);
    const perfetto_files = perfettoProtoJava(b, aosp, protoc, plugin, package_rewriter);
    var files: std.ArrayList(LazyPath) = .empty;
    files.appendSlice(b.allocator, framework_files) catch @panic("OOM");
    files.appendSlice(b.allocator, perfetto_files) catch @panic("OOM");
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-proto", "Generate framework javastream proto sources", result);
    return result;
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
    // the framework's FrameworkStatsLog, StatsD's StatsdStatsLog, telephony's
    // TelephonyStatsLog (AnomalyReporter), and telephony-common's
    // TelephonyCommonStatsLog (TelephonyPermissions).
    const specs = .{
        .{ .module = "framework", .package = "com.android.internal.util", .class = "FrameworkStatsLog" },
        .{ .module = "statsd", .package = "com.android.internal.statsd", .class = "StatsdStatsLog" },
        .{ .module = "telephony", .package = "com.android.internal.telephony", .class = "TelephonyStatsLog" },
        .{ .module = "telephony_common", .package = "com.android.internal.telephony", .class = "TelephonyCommonStatsLog" },
        .{ .module = "expresslog", .package = "com.android.modules.expresslog", .class = "StatsExpressLog" },
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

fn systemFeaturesJava(b: *std.Build, aosp: *std.Build.Dependency) struct {
    source: LazyPath,
    processor: LazyPath,
    dependencies: LazyPath,
} {
    const declaration = declarations.system_features;
    var sources = SourceFiles.Collector.init(b);
    sources.addTree(SourceFiles.Root.dependency(b, aosp, "external/javapoet/src/main/java"), .{});
    const java = sources.select(".java");
    const javapoet = command.create(b);
    javapoet.addArgs(&.{ "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-d" });
    const javapoet_classes = javapoet.addOutputDirectoryArg("systemfeatures-javapoet-classes");
    javapoet.addPrefixedFileArg("@", java.responseFile(b, "systemfeatures-javapoet.rsp"));
    javapoet.addFileArg(aosp.path("frameworks/libs/modules-utils/java/android/annotation/SdkConstant.java"));
    const guava = aosp.path("prebuilts/tools/common/m2/repository/com/google/guava/guava/32.1.1-jre/guava-32.1.1-jre.jar");
    const stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");
    const compile = command.create(b);
    compile.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    compile.addArgs(&.{ "-no-reflect", "-jvm-target", "21", "-classpath" });
    compile.addJoinedPathInputs(":", &.{ .{ .file = guava }, .{ .file = stdlib }, .{ .directory = javapoet_classes } });
    compile.addArg("-d");
    const generator = compile.addOutputFileArg("systemfeatures-generator.jar");
    compile.addFileArg(aosp.path(declaration.generator));
    compile.addFileArg(aosp.path(declaration.processor));
    compile.run.setName("compile stock system feature generator");
    const run = command.create(b);
    run.addArgs(&.{ "java", "-cp" });
    run.addJoinedPathInputs(":", &.{ .{ .file = generator }, .{ .file = guava }, .{ .file = stdlib }, .{ .directory = javapoet_classes } });
    run.addArgs(&.{ "com.android.systemfeatures.SystemFeaturesGenerator", declaration.class });
    run.addArgs(declaration.arguments);
    run.run.setName("generate RoSystemFeatures.java");
    return .{
        .source = run.run.captureStdOut(.{ .basename = std.fs.path.basename(declaration.output) }),
        .processor = generator,
        .dependencies = javapoet_classes,
    };
}

fn expressMetricIdsJava(b: *std.Build, aosp: *std.Build.Dependency) LazyPath {
    const guava = aosp.path("prebuilts/tools/common/m2/repository/com/google/guava/guava/32.1.1-jre/guava-32.1.1-jre.jar");
    const kotlin_stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");

    const compile = command.create(b);
    compile.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    compile.addArgs(&.{ "-no-reflect", "-jvm-target", "21", "-classpath" });
    compile.addJoinedPathInputs(":", &.{ .{ .file = guava }, .{ .file = kotlin_stdlib } });
    compile.addArg("-d");
    const generator_jar = compile.addOutputFileArg("express-metric-ids-generator.jar");
    compile.addFileArg(b.path("src/java/dev/artzig/tools/GenerateExpressMetricIds.kt"));
    compile.run.setName("compile express metric ID generator");

    const run = command.create(b);
    run.addArgs(&.{ "java", "-cp" });
    run.addJoinedPathInputs(":", &.{
        .{ .file = generator_jar },
        .{ .file = guava },
        .{ .file = kotlin_stdlib },
    });
    run.addArg("dev.artzig.tools.GenerateExpressMetricIdsKt");
    run.addDirectoryArg(aosp.path("frameworks/proto_logging/stats/express/catalog"));
    const output = run.addOutputFileArg("MetricIds.java");
    run.run.setName("generate MetricIds.java");
    return output;
}

fn xsdJava(b: *std.Build, aosp: *std.Build.Dependency) []const LazyPath {
    var sources = SourceFiles.Collector.init(b);
    const root = SourceFiles.Root.dependency(b, aosp, "system/tools/xsdc/src/main/java");
    sources.addTree(root, .{});
    const java_sources = sources.select(".java");

    const compile = command.create(b);
    compile.addArgs(&.{ "javac", "-source", "21", "-target", "21", "-encoding", "UTF-8", "-nowarn", "-classpath" });
    const commons_cli = aosp.path("prebuilts/misc/common/commons-cli/commons-cli-1.2.jar");
    compile.addFileArg(commons_cli);
    compile.addArg("-d");
    const classes = compile.addOutputDirectoryArg("xsdc-classes");
    const rsp = java_sources.responseFile(b, "xsdc-sources.rsp");
    compile.addPrefixedFileArg("@", rsp);
    java_sources.addInputs(compile.run);
    compile.run.setName("compile AOSP XSD Java generator");

    var files: std.ArrayList(LazyPath) = .empty;
    for (declarations.xsd) |entry| {
        const run = command.create(b);
        run.addArgs(&.{ "java", "-cp" });
        run.addJoinedPathInputs(":", &.{ .{ .directory = classes }, .{ .file = commons_cli } });
        run.addArgs(&.{ "com.android.xsdc.Main", "-j", "-p", entry.package, "-o" });
        const output = run.addOutputDirectoryArg(b.fmt("{s}-java", .{entry.name}));
        run.addArgs(entry.arguments);
        run.addFileArg(sourcePath(b, aosp, entry.input));
        run.run.setName(b.fmt("generate {s} Java", .{entry.name}));
        const result = b.allocator.alloc(LazyPath, entry.outputs.len) catch @panic("OOM");
        for (entry.outputs, 0..) |path, index| result[index] = output.path(b, path);
        _ = exposePaths(b, b.fmt("gen-{s}", .{entry.name}), b.fmt("Generate {s} Java sources", .{entry.name}), result);
        files.appendSlice(b.allocator, result) catch @panic("OOM");
    }
    return files.toOwnedSlice(b.allocator) catch @panic("OOM");
}

fn signApk(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    unsigned: LazyPath,
    output_name: []const u8,
    v1_signing: bool,
    key_name: []const u8,
) LazyPath {
    const sign = b.addSystemCommand(&.{ "java", "-jar" });
    sign.addFileArg(aosp.path("prebuilts/sdk/tools/linux/lib/apksigner.jar"));
    sign.addArgs(&.{ "sign", "--key" });
    sign.addFileArg(b.path(b.fmt("tools/signing/{s}.pk8", .{key_name})));
    sign.addArg("--cert");
    sign.addFileArg(b.path(b.fmt("tools/signing/{s}.x509.pem", .{key_name})));
    sign.addArgs(&.{
        "--v1-signing-enabled", if (v1_signing) "true" else "false",
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
    const signed = signApk(b, aosp, unsigned, "framework-res.apk", false, "azk-platform");
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
        "dev.artzig.identity.a",
        "dev.artzig.identity.b",
    };
    var outputs: [packages.len]LazyPath = undefined;
    inline for (packages, 0..) |package, index| {
        const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
        const unsigned = link.addOutputFileArg(package ++ "-unsigned.apk");
        link.addArg("--manifest");
        link.addFileArg(b.path("testdata/fixture-apks/" ++ package ++ "/AndroidManifest.xml"));
        link.addArg("-I");
        link.addFileArg(framework_res);
        if (comptime std.mem.eql(u8, package, "org.example.permissions")) {
            const fixture_resources = b.addWriteFiles();
            _ = fixture_resources.addCopyDirectory(b.path("testdata/fixture-apks/org.example.permissions/res"), "", .{});
            _ = fixture_resources.addCopyFile(aosp.path("frameworks/base/core/tests/coretests/res/raw/fsverity_sig"), "raw/fsverity_sig");
            _ = fixture_resources.addCopyFile(aosp.path("frameworks/base/core/tests/coretests/certs/unit_test.der"), "raw/fsverity_certificate.der");
            const compile = b.addSystemCommand(&.{ "aapt2", "compile", "--dir" });
            compile.addDirectoryArg(fixture_resources.getDirectory());
            compile.addArg("-o");
            link.addFileArg(compile.addOutputFileArg("fixture-resources.zip"));
            compile.setName("aapt2 compile framework fixture resources");
        }
        link.addArgs(&.{ "--min-sdk-version", "21", "--target-sdk-version", "35" });
        link.setName(b.fmt("aapt2 link fixture {s}", .{package}));
        // Keep a real v1 signature alongside v2 for strict JAR verification
        // and signature-stripping regression tests.
        const signed = signApk(b, aosp, unsigned, package ++ ".apk", comptime std.mem.eql(u8, package, "org.example.permissions"), if (index >= 4) "azk-untrusted" else "azk-platform");
        outputs[index] = signed;
    }
    _ = exposePaths(b, "gen-fixture-apks", "Build and sign fixture APKs", &outputs);
    return .{
        .test_a = outputs[0],
        .test_b = outputs[1],
        .example = outputs[2],
        .permissions = outputs[3],
        .identity_a = outputs[4],
        .identity_b = outputs[5],
    };
}

fn mimeResources(b: *std.Build, aosp: *std.Build.Dependency) LazyPath {
    const minifier = hostTool(b, "minimize-mime-types", "tools/minimize-mime-types.zig");
    const resources_dir = b.addWriteFiles();
    for (declarations.mime_types) |input| {
        const run = b.addRunArtifact(minifier);
        run.addFileArg(sourcePath(b, aosp, input.input));
        const output = run.addOutputFileArg(input.output);
        run.addArg(if (input.vendor) "vendor" else "platform");
        run.setName(b.fmt("minimize {s}", .{input.output}));
        _ = resources_dir.addCopyFile(output, b.fmt("res/{s}", .{input.output}));
    }
    const result = resources_dir.getDirectory();
    _ = exposePaths(b, "gen-mime", "Generate stock MIME-map resources", &.{result});
    return result;
}

pub fn build(b: *std.Build, o: Options) Result {
    const runner = hostTool(b, "aosp-tool-runner", "tools/aosp-tool-runner.zig");
    const filter = hostTool(b, "filter-flagged-api", "tools/filter-flagged-api.zig");
    const package_rewriter = hostTool(b, "rewrite-java-package", "tools/rewrite-java-package.zig");
    const stats_tool = @import("stats_tool.zig").build(b, .{
        .aosp = o.aosp.path(""),
        .target = b.graph.host,
        .protobuf = o.protobuf,
        .protoc = o.protoc,
    });
    _ = expose(b, "stats-tool", "Build stats-log-api-gen", &stats_tool.step);

    const aidl_java = aidlJava(b, o.aosp, runner);
    const hidl_java = hidlJava(b, o.aosp, runner);
    const aidl_cpp = aidlCpp(b, o.aosp, runner);
    const graphics = b.addRunArtifact(runner);
    graphics.addFileArg(o.aosp.path("prebuilts/sdk/tools/linux/bin/aidl"));
    graphics.addArgs(&.{ "--lang=cpp", "-I" });
    graphics.addDirectoryArg(o.aosp.path("frameworks/native/libs/gui/aidl"));
    graphics.addArg("-h");
    const graphics_headers = graphics.addOutputDirectoryArg("binder-graphics-headers");
    graphics.addArg("-o");
    const graphics_sources = graphics.addOutputDirectoryArg("binder-graphics-sources");
    graphics.addFileArg(o.aosp.path(declarations.binder_graphics_aidl));
    graphics.setName("generate graphics Binder test interface");
    const logtags_java = logtagsJava(b, o.aosp);
    const aconfig_java = aconfigJava(b, o.aosp, runner);
    const sysprop_java = syspropJava(b, o.aosp, o.sysprop_tool);
    const incremental_sysprop = incrementalSyspropCpp(b, o.aosp, o.sysprop_cpp_tool);
    const nano_java = nanoJava(b, o.aosp, o.protoc, o.javanano_tool);
    const proto_java = protoJava(b, o.aosp, o.protoc, o.javastream_tool, package_rewriter);
    const stats_java = statsJava(b, o.aosp, stats_tool);
    const express_metric_ids_java = expressMetricIdsJava(b, o.aosp);
    const system_features_java = systemFeaturesJava(b, o.aosp);
    const aconfig_device_paths_java = aconfigDevicePathsJava(b, o.aosp);
    const xsd_java = xsdJava(b, o.aosp);
    const res = resources(b, o.aosp, filter);
    const fixtures = fixtureApks(b, o.aosp, res.apk);
    const mime_resources = mimeResources(b, o.aosp);
    var java_files: std.ArrayList(LazyPath) = .empty;
    inline for (.{ aidl_java, hidl_java, logtags_java, aconfig_java.java, sysprop_java, nano_java, proto_java, stats_java, xsd_java, res.java_sources }) |generated_set| {
        java_files.appendSlice(b.allocator, generated_set) catch @panic("OOM");
    }
    java_files.append(b.allocator, express_metric_ids_java) catch @panic("OOM");
    java_files.append(b.allocator, system_features_java.source) catch @panic("OOM");
    java_files.append(b.allocator, aconfig_device_paths_java) catch @panic("OOM");

    const aggregate = b.step("generators", "Generate all framework sources, resources, and fixture APKs");
    for (java_files.items) |path| path.addStepDependencies(aggregate);
    for (aidl_cpp.headers) |path| path.addStepDependencies(aggregate);
    for (aconfig_java.cpp_headers) |path| path.addStepDependencies(aggregate);
    res.apk.addStepDependencies(aggregate);
    mime_resources.addStepDependencies(aggregate);
    incremental_sysprop.source.addStepDependencies(aggregate);
    for (fixtures.all()) |path| path.addStepDependencies(aggregate);

    return .{
        .jvm = .{
            .sources = java_files.toOwnedSlice(b.allocator) catch @panic("OOM"),
            .resources = mime_resources,
            .system_features_processor = system_features_java.processor,
            .system_features_processor_dependencies = system_features_java.dependencies,
        },
        .native = .{
            .aidl_header_roots = aidl_cpp.headers,
            .aidl_source_roots = aidl_cpp.sources,
            .aconfig_header_roots = aconfig_java.cpp_headers,
            .binder_graphics_headers = graphics_headers,
            .binder_graphics_sources = graphics_sources,
            .incremental_sysprop_headers = incremental_sysprop.headers,
            .incremental_sysprop_source = incremental_sysprop.source,
        },
        .resources = res,
        .fixtures = fixtures,
        .aggregate_step = aggregate,
    };
}
