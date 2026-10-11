//! Cacheable Java, native-header, resource, and fixture generators.
const std = @import("std");
const resource_flags = @import("gen/resource_flags.zig");
const declarations = @import("generator_sources.zig");
const command = @import("command.zig");
const SourceFiles = @import("artzig").SourceSet;

const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    perfetto_headers: LazyPath,
    aosp: *std.Build.Dependency,
    protobuf: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
    sysprop_tool: *std.Build.Step.Compile,
    sysprop_cpp_tool: *std.Build.Step.Compile,
    javastream_tool: *std.Build.Step.Compile,
    cppstream_tool: *std.Build.Step.Compile,
    javanano_tool: *std.Build.Step.Compile,
};

pub const Result = struct {
    /// The pinned AIDL compiler with the Zig backend (build/aidl.zig).
    aidl_tool: *std.Build.Step.Compile,
    jvm: GeneratedJvm,
    native: GeneratedNative,
    resources: FrameworkResources,
    fixtures: FixtureApks,
    aggregate_step: *std.Build.Step,
};

pub const GeneratedJvm = struct {
    sources: []const LazyPath,
    cronet_sources: []const LazyPath,
    aconfig_source_roots: []const LazyPath,
    nano_source_root: LazyPath,
    resources: LazyPath,
    system_features_processor: LazyPath,
    system_features_processor_dependencies: LazyPath,
};

pub const CppXsd = struct {
    headers: LazyPath,
    source_root: LazyPath,
    sources: []const []const u8,
};

pub const GeneratedNative = struct {
    android_account_headers: LazyPath,
    cppstream_headers: LazyPath,
    xsd: []const CppXsd,
    media: @import("graphics.zig").Generated,
    hwui_stats: @import("graphics.zig").Generated,
    media_stats: @import("graphics.zig").Generated,
    codec2_sysprop: struct { headers: LazyPath, source: LazyPath },
    incremental_sysprop_headers: LazyPath,
    incremental_sysprop_source: LazyPath,
    aidl_header_roots: []const LazyPath,
    aidl_source_roots: []const LazyPath,
    hidl_header_roots: []const LazyPath,
    hidl_sources: []const LazyPath,
    aconfig_header_roots: []const LazyPath,
    aconfig_sources: []const LazyPath,
    binder_graphics_headers: LazyPath,
    binder_graphics_sources: LazyPath,
    gui: @import("graphics.zig").Generated,
    renderengine: @import("renderengine.zig").Generated,
    perfetto: @import("perfetto.zig").Generated,
    surfaceflinger: @import("surfaceflinger.zig").Generated,
    input: @import("input.zig").Generated,
    input_dispatcher: @import("input_dispatcher.zig").Generated,
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
    webview: LazyPath,

    pub fn all(self: FixtureApks) [7]LazyPath {
        return .{ self.test_a, self.test_b, self.example, self.permissions, self.identity_a, self.identity_b, self.webview };
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
        "system/connectivity/wificond/aidl/",
        "packages/modules/Virtualization/android/virtualizationservice/aidl/",
        "system/gsid/aidl/",
        "packages/modules/Scheduling/framework/java/",
        "packages/modules/OnDevicePersonalization/framework/java/",
        "packages/modules/DeviceLock/framework/java/",
        "packages/modules/Wifi/framework/aidl-export/",
        "packages/modules/Connectivity/staticlibs/netd/binder/",
        "frameworks/base/wifi/java/src/",
        "frameworks/base/media/java/android/media/quality/aidl/",
        "frameworks/libs/modules-utils/java/",
        "packages/modules/Uwb/framework/java/",
        "packages/modules/Connectivity/thread/framework/java/",
        "packages/modules/Connectivity/nearby/framework/java/",
        "packages/modules/AdServices/adservices/framework/java/",
        "hardware/interfaces/power/aidl/",
        "hardware/interfaces/contexthub/aidl/",
        "frameworks/native/cmds/dumpstate/binder/",
        "frameworks/base/packages/Vcn/framework-b/src/",
        "frameworks/base/packages/NeuralNetworks/framework/module/java/",
        "frameworks/base/apex/blobstore/framework/java/",
        "frameworks/base/omapi/aidl/aidl_api/android.se.omapi/1/",
        "packages/modules/AdServices/sdksandbox/framework/java/",
        "packages/modules/Nfc/framework/java/",
        "packages/modules/Profiling/aidl/",
        "frameworks/av/media/module/libmediatranscoding/aidl/",
        "frameworks/base/packages/services/PacProcessor/src/",
        "frameworks/base/nfc-non-updatable/java/",
        "packages/modules/Media/apex/aidl/stable/",
        "packages/providers/MediaProvider/apex/framework/java/",

        declarations.device_state_aidl.root,
        "aidl-compat/",
        "frameworks/base/core/java/",
        "frameworks/base/graphics/java/",
        "frameworks/base/media/java/android/media/tv/tunerresourcemanager/aidl/",
        "frameworks/base/media/java/",
        "frameworks/base/media/aidl/",
        "frameworks/av/aidl/",
        "frameworks/av/camera/aidl/",
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
        "art/artd/binder/",
        "art/dexopt_chroot_setup/binder/",
        "frameworks/base/cmds/idmap2/idmap2d/aidl/services/",
        "system/security/keystore2/aidl/",
        "system/update_engine/binder_bindings/",
        "packages/modules/CrashRecovery/framework/java/",
        "frameworks/base/location/java/",
        "frameworks/base/telephony/java/",
        "frameworks/base/telecomm/java/",
        "frameworks/base/mms/java/",
        "hardware/interfaces/power/aidl/",
        "hardware/interfaces/gnss/aidl/",
        "hardware/interfaces/biometrics/common/aidl/",
        "hardware/interfaces/biometrics/face/aidl/",
        "hardware/interfaces/biometrics/fingerprint/aidl/",
        "hardware/interfaces/common/aidl/",
        "hardware/interfaces/cas/aidl/",
        "frameworks/av/media/libmediametrics/aidl/",
        "hardware/interfaces/common/fmq/aidl/",
        "hardware/interfaces/keymaster/aidl/",
        "hardware/interfaces/security/keymint/aidl/",
        "system/hardware/interfaces/keystore2/aidl/",
        "system/core/gatekeeperd/binder/",
        "system/vold/binder/",
        "system/core/storaged/binder/",
        "system/memory/mmd/aidl/",
        "hardware/interfaces/graphics/common/aidl/",
        "hardware/interfaces/health/aidl/",
        "frameworks/native/services/surfaceflinger/PowerAdvisor/aidl/",
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
    run.addPrefixedDirectoryArg("-I", aosp.path("system/connectivity/wificond/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Virtualization/android/virtualizationservice/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("system/gsid/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Scheduling/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/OnDevicePersonalization/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/DeviceLock/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Connectivity/staticlibs/netd/binder"));
    run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/base/wifi/java/src"));
    run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/base/media/java/android/media/quality/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Uwb/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Connectivity/thread/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Connectivity/nearby/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/AdServices/adservices/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/power/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/contexthub/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/native/cmds/dumpstate/binder"));
    run.addPrefixedDirectoryArg("-I", aosp.path("system/core/storaged/binder"));
    run.addPrefixedDirectoryArg("-I", aosp.path("system/memory/mmd/aidl"));
    run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/base/packages/Vcn/framework-b/src"));
    run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/base/packages/NeuralNetworks/framework/module/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/base/apex/blobstore/framework/java"));
    run.addPrefixedDirectoryArg("-I", aosp.path("packages/modules/Connectivity/framework/aidl-export"));
    inline for (.{
        declarations.device_state_aidl.root,
        "frameworks/base/core/java",
        "frameworks/base/graphics/java",
    }) |path| run.addPrefixedDirectoryArg("-I", aosp.path(path));
    run.addPrefixedDirectoryArg("-I", compat);
    inline for (.{
        "frameworks/base/omapi/aidl/aidl_api/android.se.omapi/1",
        "packages/modules/AdServices/sdksandbox/framework/java",
        "packages/modules/Nfc/framework/java",
        "packages/modules/Profiling/aidl",
        "frameworks/av/media/module/libmediatranscoding/aidl",
        "frameworks/base/packages/services/PacProcessor/src",
        "frameworks/base/nfc-non-updatable/java",
        "packages/modules/Media/apex/aidl/stable",
        "packages/providers/MediaProvider/apex/framework/java",
        "packages/modules/Connectivity/framework-t/src",
        "frameworks/base/media/java/android/media/tv/tunerresourcemanager/aidl",
        "frameworks/base/media/java",
        "frameworks/base/media/aidl",
        "frameworks/av/aidl",
        "frameworks/av/camera/aidl",
        "frameworks/av/media/libaudioclient/aidl",
        "system/hardware/interfaces/media/aidl",
        "frameworks/base/keystore/java",
        "frameworks/libs/modules-utils/java",
        "frameworks/native/libs/input",
        "frameworks/native/libs/gui",
        "frameworks/native/aidl",
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
        "art/artd/binder",
        "art/dexopt_chroot_setup/binder",
        "frameworks/base/cmds/idmap2/idmap2d/aidl/services",
        "system/security/keystore2/aidl",
        "system/update_engine/binder_bindings",
        "packages/modules/CrashRecovery/framework/java",
        "frameworks/base/location/java",
        "frameworks/base/telephony/java",
        "frameworks/base/telecomm/java",
        "frameworks/base/mms/java",
        "hardware/interfaces/power/aidl",
        "hardware/interfaces/gnss/aidl",
        "hardware/interfaces/biometrics/common/aidl",
        "hardware/interfaces/biometrics/face/aidl",
        "hardware/interfaces/biometrics/fingerprint/aidl",
        "hardware/interfaces/common/aidl",
        "hardware/interfaces/cas/aidl",
        "frameworks/av/media/libmediametrics/aidl",
        "hardware/interfaces/common/fmq/aidl",
        "hardware/interfaces/keymaster/aidl",
        "hardware/interfaces/security/keymint/aidl",
        "system/hardware/interfaces/keystore2/aidl",
        "system/core/gatekeeperd/binder",
        "system/vold/binder",
        "hardware/interfaces/graphics/common/aidl",
        "hardware/interfaces/health/aidl",
        "frameworks/native/services/surfaceflinger/PowerAdvisor/aidl",
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
    aidl_tool: *std.Build.Step.Compile,
) []const LazyPath {
    // The native permission AIDL lib (AttributionSourceState, IAppOpsCallback,
    // IPermissionChecker) is staged into the compat root rather than added as
    // an -I root, so its parcelable declarations resolve after core/java's.
    const permission_aidl = "frameworks/native/libs/permission/aidl/";
    const compat = b.addWriteFiles();
    _ = compat.addCopyDirectory(b.path("aidl-compat"), "", .{});
    _ = compat.addCopyDirectory(aosp.path(permission_aidl), "", .{ .include_extensions = &.{".aidl"} });
    const compat_dir = compat.getDirectory();

    // Mainline Bluetooth's socket AIDL refers to the SDK parcelable by its
    // short name. Supply the same preprocessed SDK declaration Soong sees.
    const sdk_parcelables = b.addRunArtifact(aidl_tool);
    sdk_parcelables.addArg("--preprocess");
    const sdk_preprocessed = sdk_parcelables.addOutputFileArg("framework-parcelables.aidl");
    sdk_parcelables.addFileArg(aosp.path("frameworks/base/core/java/android/content/AttributionSource.aidl"));

    var files: std.ArrayList(LazyPath) = .empty;
    var index: usize = 0;
    for (declarations.aidl) |input| {
        const run = b.addRunArtifact(aidl_tool);
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
        // Thread's framework-t module has a minimum SDK of 33; binder-interface
        // lists require the corresponding AIDL Java backend rather than API 1.
        if (std.mem.startsWith(u8, input.path, "packages/modules/Connectivity/thread/")) {
            run.addArg("--min_sdk_version=33");
        }
        for (declarations.versioned_aidl) |version| {
            if (input.source == .aosp and std.mem.startsWith(u8, input.path, version.prefix)) {
                run.addArgs(&.{ "--structured", "--stability=vintf", "--min_sdk_version=35" });
                run.addArg(b.fmt("--version={s}", .{version.version}));
                run.addArg(b.fmt("--hash={s}", .{version.hash}));
            }
        }
        addAidlIncludes(run, aosp, compat_dir);
        var preprocessed = sdk_preprocessed;
        for (declarations.sdk_aidl.prefixes) |prefix| {
            if (input.source == .aosp and std.mem.startsWith(u8, input.path, prefix)) {
                preprocessed = aosp.path(declarations.sdk_aidl.preprocessed);
                break;
            }
        }
        run.addPrefixedFileArg("-p", preprocessed);
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

const HidlCppResult = struct { headers: []const LazyPath, sources: []const LazyPath };

fn hidlCpp(b: *std.Build, aosp: *std.Build.Dependency, runner: *std.Build.Step.Compile) HidlCppResult {
    var headers: std.ArrayList(LazyPath) = .empty;
    var sources: std.ArrayList(LazyPath) = .empty;
    for (declarations.hidl_cpp, 0..) |entry, index| {
        inline for (.{ "c++-headers", "c++-sources" }) |language| {
            const run = b.addRunArtifact(runner);
            run.addArg("--library-dir");
            run.addDirectoryArg(aosp.path("prebuilts/build-tools/linux-x86/lib64"));
            run.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/hidl-gen"));
            run.addArgs(&.{ "-L" ++ language, "-R" });
            run.addPrefixedDirectoryArg("-randroid.hardware:", aosp.path("hardware/interfaces"));
            run.addPrefixedDirectoryArg("-randroid.hidl:", aosp.path("system/libhidl/transport"));
            run.addArg("-o");
            const output = run.addOutputDirectoryArg(b.fmt("hidl-{s}-{d}", .{ language, index }));
            run.addArg(entry.fqname);
            run.setName(b.fmt("generate HIDL {s} {s}", .{ language, entry.fqname }));
            if (comptime std.mem.eql(u8, language, "c++-headers")) {
                headers.append(b.allocator, output) catch @panic("OOM");
            } else {
                for (entry.sources) |path| sources.append(b.allocator, output.path(b, path)) catch @panic("OOM");
            }
        }
    }
    const result = HidlCppResult{
        .headers = headers.toOwnedSlice(b.allocator) catch @panic("OOM"),
        .sources = sources.toOwnedSlice(b.allocator) catch @panic("OOM"),
    };
    const step = exposePaths(b, "gen-hidl-cpp", "Generate pinned HIDL C++ protocols", result.sources);
    for (result.headers) |path| path.addStepDependencies(step);
    return result;
}

const CppAidlResult = struct { headers: []const LazyPath, sources: []const LazyPath };

fn aidlCpp(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    aidl_tool: *std.Build.Step.Compile,
) CppAidlResult {
    var roots: std.ArrayList(LazyPath) = .empty;
    var files: std.ArrayList(LazyPath) = .empty;
    for (declarations.cpp_aidl, 0..) |entry, index| {
        const root = aosp.path(entry.root);
        const run = b.addRunArtifact(aidl_tool);
        run.addArgs(&.{ b.fmt("--lang={s}", .{@tagName(entry.backend)}), "-I" });
        run.addDirectoryArg(root);
        // Native interfaces import types owned by other selected AIDL packages.
        var include_roots: std.StringHashMapUnmanaged(void) = .empty;
        include_roots.put(b.allocator, entry.root, {}) catch @panic("OOM");
        for (declarations.cpp_aidl) |dependency| {
            if (!entry.include_selected_roots) break;
            // The native privacy declarations are a separate Soong module.
            if (!dependency.include_selected_roots) continue;
            // The frozen observer interface and the live resource-manager tree
            // contain the same observer names. Soong resolves each against its
            // selected version, never both definitions in one compilation.
            const observer_root = "frameworks/av/services/mediaresourcemanager/aidl_api/";
            const frozen_observer = std.mem.startsWith(u8, entry.root, observer_root);
            if (frozen_observer and std.mem.eql(u8, dependency.root, "frameworks/av/services/mediaresourcemanager/aidl")) continue;
            if (!frozen_observer and std.mem.startsWith(u8, dependency.root, observer_root)) continue;
            const found = include_roots.getOrPut(b.allocator, dependency.root) catch @panic("OOM");
            if (!found.found_existing) run.addPrefixedDirectoryArg("-I", aosp.path(dependency.root));
        }
        if (std.mem.startsWith(u8, entry.root, "hardware/interfaces/drm/")) {
            // libmediadrm selects the frozen android.hardware.drm-V1-ndk ABI.
            run.addArgs(&.{ "--version=1", "--hash=3a0197fb44863256da9034c26e721b1eee12d1be" });
            run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/common/aidl"));
            run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/drm/common/aidl"));
        }
        for (entry.include_roots) |include| run.addPrefixedDirectoryArg("-I", aosp.path(include));
        if (entry.backend == .ndk and !entry.vintf_stable) run.addArg("--structured");
        if (entry.min_sdk_version) |version| run.addArg(b.fmt("--min_sdk_version={d}", .{version}));
        if (entry.version) |version| run.addArg(b.fmt("--version={d}", .{version}));
        if (entry.hash) |hash| run.addArg(b.fmt("--hash={s}", .{hash}));
        run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/common/aidl"));
        run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/common/fmq/aidl"));
        run.addPrefixedDirectoryArg("-I", aosp.path("hardware/interfaces/graphics/common/aidl"));
        run.addPrefixedDirectoryArg("-I", aosp.path("frameworks/native/aidl/gui"));
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
) struct { java: []const LazyPath, java_roots: []const LazyPath, cpp_headers: []const LazyPath, cpp_sources: []const LazyPath } {
    var files: std.ArrayList(LazyPath) = .empty;
    var roots: std.ArrayList(LazyPath) = .empty;
    var headers: std.ArrayList(LazyPath) = .empty;
    var cpp_sources: std.ArrayList(LazyPath) = .empty;
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
        if (entry.fixed_disabled_flags.len != 0 or entry.fixed_enabled_flags.len != 0) {
            var values: []const u8 = "";
            for (entry.fixed_disabled_flags) |name| {
                values = b.fmt("{s}flag_value {{ package: \"{s}\" name: \"{s}\" state: DISABLED permission: READ_ONLY }}\n", .{ values, entry.package, name });
            }
            for (entry.fixed_enabled_flags) |name| {
                values = b.fmt("{s}flag_value {{ package: \"{s}\" name: \"{s}\" state: ENABLED permission: READ_ONLY }}\n", .{ values, entry.package, name });
            }
            const inputs = b.addWriteFiles();
            cache.addArg("--values");
            cache.addFileArg(inputs.add("host-fixed-flags.textproto", values));
        }
        cache.addArg("--cache");
        const cache_file = cache.addOutputFileArg(b.fmt("aconfig-{d}.pb", .{index}));
        cache.setName(b.fmt("create aconfig cache {s}", .{entry.package}));

        if (entry.cpp_header or entry.cpp_source) {
            const cpp = b.addRunArtifact(runner);
            cpp.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
            cpp.addArgs(&.{ "create-cpp-lib", "--cache" });
            cpp.addFileArg(cache_file);
            cpp.addArg("--out");
            const output = cpp.addOutputDirectoryArg(b.fmt("aconfig-cpp-{d}", .{index}));
            cpp.addArgs(&.{ "--mode", mode });
            cpp.setName(b.fmt("generate aconfig C++ header {s}", .{entry.package}));
            headers.append(b.allocator, output.path(b, "include")) catch @panic("OOM");
            if (entry.cpp_source) {
                const stem = std.mem.replaceOwned(u8, b.allocator, entry.package, ".", "_") catch @panic("OOM");
                cpp_sources.append(b.allocator, output.path(b, b.fmt("{s}.cc", .{stem}))) catch @panic("OOM");
            }
        }
        const java = b.addRunArtifact(runner);
        java.addFileArg(aosp.path("prebuilts/build-tools/linux-x86/bin/aconfig"));
        java.addArgs(&.{ "create-java-lib", "--cache" });
        java.addFileArg(cache_file);
        java.addArg("--out");
        const output = java.addOutputDirectoryArg(b.fmt("aconfig-java-{d}", .{index}));
        roots.append(b.allocator, output) catch @panic("OOM");
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
    return .{ .java = result, .java_roots = roots.toOwnedSlice(b.allocator) catch @panic("OOM"), .cpp_headers = headers.toOwnedSlice(b.allocator) catch @panic("OOM"), .cpp_sources = cpp_sources.toOwnedSlice(b.allocator) catch @panic("OOM") };
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

fn syspropCpp(b: *std.Build, aosp: *std.Build.Dependency, tool: *std.Build.Step.Compile, declaration: anytype) struct { headers: LazyPath, source: LazyPath } {
    const run = b.addRunArtifact(tool);
    run.addArg("--header-dir");
    const headers = run.addOutputDirectoryArg("sysprop-headers");
    run.addArg("--public-header-dir");
    _ = run.addOutputDirectoryArg("sysprop-public-headers");
    run.addArg("--source-dir");
    const sources = run.addOutputDirectoryArg("sysprop-sources");
    run.addArgs(&.{ "--include-name", declaration.header });
    run.addFileArg(sourcePath(b, aosp, declaration.input));
    run.setName(b.fmt("generate sysprop C++ {s}", .{declaration.header}));
    // sysprop_cpp emits the input basename; Soong exports it under include-name.
    const exported = b.addWriteFiles();
    _ = exported.addCopyFile(headers.path(b, std.fs.path.basename(declaration.header)), declaration.header);
    return .{ .headers = exported.getDirectory(), .source = sources.path(b, declaration.source) };
}

fn javaTemplates(b: *std.Build, aosp: *std.Build.Dependency) []const LazyPath {
    var files: std.ArrayList(LazyPath) = .empty;
    inline for (declarations.java_templates) |entry| {
        const run = command.create(b);
        run.addArgs(&.{ "sed", "-e", entry.substitution });
        run.addFileArg(sourcePath(b, aosp, entry.input));
        run.run.setName(b.fmt("generate Java template {s}", .{entry.output}));
        files.append(b.allocator, run.run.captureStdOut(.{ .basename = std.fs.path.basename(entry.output) })) catch @panic("OOM");
    }
    return files.toOwnedSlice(b.allocator) catch @panic("OOM");
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

const NanoJava = struct { java: []const LazyPath, root: LazyPath };

fn nanoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile, package_rewriter: *std.Build.Step.Compile) NanoJava {
    var files: std.ArrayList(LazyPath) = .empty;
    const tree = b.addWriteFiles();
    const relocation = declarations.nano_runtime_relocation;
    for (relocation.outputs) |file| {
        const rewrite = b.addRunArtifact(package_rewriter);
        rewrite.addFileArg(aosp.path(b.fmt("{s}/{s}", .{ relocation.root, file })));
        const relocated = rewrite.addOutputFileArg(b.fmt("com/android/framework/protobuf/nano/{s}", .{file}));
        rewrite.addArgs(&.{ relocation.from, relocation.to });
        rewrite.addFileInput(aosp.path(relocation.rule));
        files.append(b.allocator, tree.addCopyFile(relocated, b.fmt("com/android/framework/protobuf/nano/{s}", .{file}))) catch @panic("OOM");
    }
    var index: usize = 0;
    for (declarations.nano) |declaration| {
        const input = declaration.input;
        const run = b.addRunArtifact(protoc);
        run.addPrefixedFileArg("--plugin=protoc-gen-javanano=", plugin.getEmittedBin());
        const output = run.addPrefixedOutputDirectoryArg("--javanano_out=", b.fmt("nano-java-{d}", .{index}));
        run.addPrefixedDirectoryArg("-I", sourceDir(b, aosp, input));
        run.addFileArg(sourcePath(b, aosp, input));
        run.setName(b.fmt("generate nano Java {s}", .{std.fs.path.basename(input.path)}));
        for (declaration.outputs) |file| {
            var java = output.path(b, file);
            if (declaration.relocate_runtime) {
                const rewrite = b.addRunArtifact(package_rewriter);
                rewrite.addFileArg(java);
                java = rewrite.addOutputFileArg(file);
                rewrite.addArgs(&.{ relocation.from, relocation.to, "--references-only" });
                rewrite.addFileInput(aosp.path(relocation.rule));
            }
            files.append(b.allocator, tree.addCopyFile(java, file)) catch @panic("OOM");
        }
        index += 1;
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-nano", "Generate javanano message sources", result);
    return .{ .java = result, .root = tree.getDirectory() };
}

fn frameworkProtoCpp(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile) LazyPath {
    const run = command.create(b);
    run.addFileArg(protoc.getEmittedBin());
    run.addPrefixedFileArg("--plugin=protoc-gen-cppstream=", plugin.getEmittedBin());
    const output = run.addPrefixedOutputDirectoryArg("--cppstream_out=", "cppstream-headers");
    run.addPrefixedDirectoryArg("-I", aosp.path(""));
    run.addPrefixedDirectoryArg("-I", aosp.path("external/protobuf/src"));
    for (declarations.cppstream) |entry| run.addFileArg(aosp.path(entry.input));
    var headers: std.ArrayList(LazyPath) = .empty;
    for (declarations.cppstream) |entry| headers.append(b.allocator, output.path(b, entry.output)) catch @panic("OOM");
    run.run.setName("generate original streaming proto C++ constants");
    _ = exposePaths(b, "gen-cppstream", "Generate original C++ streaming proto headers", headers.items);
    return output;
}

fn liteProtoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile) []const LazyPath {
    const declaration = declarations.lite_java_protos;
    const run = b.addRunArtifact(protoc);
    const output = run.addPrefixedOutputDirectoryArg("--java_out=lite:", "lite-proto-java");
    run.addPrefixedDirectoryArg("-I", aosp.path(""));
    run.addPrefixedDirectoryArg("-I", aosp.path("external/protobuf/src"));
    for (declaration.inputs) |input| run.addFileArg(aosp.path(input));
    run.setName("generate lite proto Java");
    const files = b.allocator.alloc(LazyPath, declaration.outputs.len) catch @panic("OOM");
    for (declaration.outputs, 0..) |path, index| files[index] = output.path(b, path);
    return files;
}

fn frameworkProtoJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile, plugin: *std.Build.Step.Compile, package_rewriter: *std.Build.Step.Compile) []const LazyPath {
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
    for (declarations.proto_outputs, 0..) |path, index| {
        files[index] = output.path(b, path);
        inline for (declarations.proto_relocations) |relocation| {
            if (std.mem.eql(u8, path, relocation.output)) {
                const rewrite = b.addRunArtifact(package_rewriter);
                rewrite.addFileArg(files[index]);
                files[index] = rewrite.addOutputFileArg(std.fs.path.basename(path));
                rewrite.addArgs(&.{ relocation.from, relocation.to });
                rewrite.setName(b.fmt("relocate framework proto package in {s}", .{path}));
            }
        }
    }
    var all: std.ArrayList(LazyPath) = .empty;
    all.appendSlice(b.allocator, files) catch @panic("OOM");
    for (declarations.proto_unrelocated_copies) |path| all.append(b.allocator, output.path(b, path)) catch @panic("OOM");
    return all.toOwnedSlice(b.allocator) catch @panic("OOM");
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
    const framework_files = frameworkProtoJava(b, aosp, protoc, plugin, package_rewriter);
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
    const stage = @import("stats_tool.zig").templates(b, aosp.path(""));

    // One run per stats-log module, as Soong's stats-log-api-gen genrules do:
    // the framework's FrameworkStatsLog, StatsD's StatsdStatsLog, telephony's
    // TelephonyStatsLog (AnomalyReporter), and telephony-common's
    // TelephonyCommonStatsLog (TelephonyPermissions).
    const specs = declarations.stats_java;
    const result = b.allocator.alloc(LazyPath, specs.len) catch @panic("OOM");
    inline for (specs, 0..) |spec, index| {
        const run = b.addRunArtifact(stats_tool);
        run.setCwd(stage);
        run.addArg("--java");
        const java = run.addOutputFileArg(spec.class ++ ".java");
        run.addArgs(&.{
            "--module",      spec.module,
            "--javaPackage", spec.package,
            "--javaClass",   spec.class,
        });
        if (@hasField(@TypeOf(spec), "min_api")) run.addArgs(&.{ "--minApiLevel", spec.min_api });
        if (@hasField(@TypeOf(spec), "worksource") and spec.worksource) run.addArg("--worksource");
        run.setName("generate " ++ spec.package ++ "." ++ spec.class ++ ".java");
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

const GeneratedXsd = struct { java: []const LazyPath, cpp: []const CppXsd };

fn xsdSources(b: *std.Build, aosp: *std.Build.Dependency) GeneratedXsd {
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
    compile.run.setName("compile AOSP XSD generator");

    var files: std.ArrayList(LazyPath) = .empty;
    var native: std.ArrayList(CppXsd) = .empty;
    for (declarations.xsd) |entry| {
        const run = command.create(b);
        run.addArgs(&.{ "java", "-cp" });
        run.addJoinedPathInputs(":", &.{ .{ .directory = classes }, .{ .file = commons_cli } });
        const backend = if (entry.backend == .java) "Java" else "C++";
        run.addArgs(&.{ "com.android.xsdc.Main", if (entry.backend == .java) "-j" else "-c", "-p", entry.package, "-o" });
        const output = run.addOutputDirectoryArg(b.fmt("{s}-{s}", .{ entry.name, @tagName(entry.backend) }));
        run.addArgs(entry.arguments);
        run.addFileArg(sourcePath(b, aosp, entry.input));
        run.run.setName(b.fmt("generate {s} {s}", .{ entry.name, backend }));
        const result = b.allocator.alloc(LazyPath, entry.outputs.len) catch @panic("OOM");
        for (entry.outputs, 0..) |path, index| result[index] = output.path(b, path);
        _ = exposePaths(b, b.fmt("gen-{s}", .{entry.name}), b.fmt("Generate {s} {s} sources", .{ entry.name, backend }), result);
        if (entry.backend == .java) {
            files.appendSlice(b.allocator, result) catch @panic("OOM");
        } else {
            var cpp: std.ArrayList([]const u8) = .empty;
            for (entry.outputs) |path| {
                if (std.mem.endsWith(u8, path, ".cpp")) cpp.append(b.allocator, path) catch @panic("OOM");
            }
            native.append(b.allocator, .{ .headers = output.path(b, "include"), .source_root = output, .sources = cpp.toOwnedSlice(b.allocator) catch @panic("OOM") }) catch @panic("OOM");
        }
    }
    return .{ .java = files.toOwnedSlice(b.allocator) catch @panic("OOM"), .cpp = native.toOwnedSlice(b.allocator) catch @panic("OOM") };
}

pub fn signApk(
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
    const desktop_compile = b.addSystemCommand(&.{ "aapt2", "compile", "--dir" });
    desktop_compile.addDirectoryArg(b.path("src/resources/framework"));
    desktop_compile.addArg("-o");
    const desktop_resources = desktop_compile.addOutputFileArg("desktop-framework-res.zip");
    desktop_compile.setName("aapt2 compile desktop framework configuration");

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
    link.addArg("-R");
    link.addFileArg(desktop_resources);
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
        "dev.artzig.webviewtest",
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
        if (comptime std.mem.eql(u8, package, "dev.artzig.webviewtest")) {
            const assets = b.addWriteFiles();
            _ = assets.addCopyFile(b.path("testdata/fixture-apks/dev.artzig.webviewtest/assets/index.html"), "index.html");
            link.addArg("-A");
            link.addDirectoryArg(assets.getDirectory());
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
        .webview = outputs[6],
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

fn cronetJava(b: *std.Build, aosp: *std.Build.Dependency, protoc: *std.Build.Step.Compile) []const LazyPath {
    const inputs = b.addWriteFiles();
    inline for (.{ declarations.cronet.support, declarations.cronet.enum_headers, declarations.cronet.proto_inputs, declarations.cronet.jni_support, declarations.cronet.jni_inputs }) |paths| {
        for (paths) |path| _ = inputs.addCopyFile(aosp.path(b.fmt("{s}/{s}", .{ declarations.cronet.root, path })), path);
    }
    const root = inputs.getDirectory();
    var files: std.ArrayList(LazyPath) = .empty;
    const enums = b.addSystemCommand(&.{ "python3", "-B" });
    enums.addFileArg(b.path("tools/cronet-java-enums.py"));
    enums.addArgs(&.{ declarations.cronet.copyright_year, "--srcjar" });
    const archive = enums.addOutputFileArg("cronet-enums.srcjar");
    enums.addArgs(declarations.cronet.enum_headers);
    enums.setCwd(root);
    enums.setName("generate stock Cronet Java enums");
    const extract = b.addSystemCommand(&.{ "python3", "-B", "-m", "zipfile", "-e" });
    extract.addFileArg(archive);
    const enum_root = extract.addOutputDirectoryArg("cronet-enums");
    extract.setName("extract Cronet enum sources");
    for (declarations.cronet.enum_outputs) |output| files.append(b.allocator, enum_root.path(b, output)) catch @panic("OOM");

    const switches = b.addSystemCommand(&.{ "python3", "-B" });
    switches.addFileArg(root.path(b, "build/android/gyp/java_cpp_strings.py"));
    switches.addArg("--srcjar");
    const switches_archive = switches.addOutputFileArg("cronet-switches.srcjar");
    switches.addArgs(&.{ "--template", "base/android/java/src/org/chromium/base/BaseSwitches.java.tmpl", "base/base_switches.cc" });
    switches.setCwd(root);
    switches.setName("generate stock Cronet switches");
    const extract_switches = b.addSystemCommand(&.{ "python3", "-B", "-m", "zipfile", "-e" });
    extract_switches.addFileArg(switches_archive);
    const switches_root = extract_switches.addOutputDirectoryArg("cronet-switches");
    files.append(b.allocator, switches_root.path(b, declarations.cronet.switches_output)) catch @panic("OOM");

    // Match stable/Android.bp: separate the provisional GEN_JNI source and
    // compile only the final hashed bindings, retaining ForTesting methods.
    const jni = b.addSystemCommand(&.{ "python3", "-B" });
    jni.addFileArg(root.path(b, "third_party/jni_zero/jni_zero.py"));
    jni.addArgs(&.{ "from-source", "--use-proxy-hash", "--extra-include", "third_party/jni_zero/jni_zero_internal.h", "--output-dir" });
    _ = jni.addOutputDirectoryArg("cronet-jni-headers");
    jni.addArg("--srcjar-path");
    const jni_archive = jni.addOutputFileArg("cronet-jni.srcjar");
    jni.addArg("--placeholder-srcjar-path");
    _ = jni.addOutputFileArg("cronet-jni-placeholder.srcjar");
    for (declarations.cronet.jni_inputs) |input| {
        jni.addArgs(&.{ "--input-file", input, "--output-name", b.fmt("{s}_jni.h", .{std.fs.path.stem(input)}) });
    }
    jni.setCwd(root);
    jni.setName("generate stock Cronet JNI interfaces");
    const extract_jni = b.addSystemCommand(&.{ "python3", "-B", "-m", "zipfile", "-e" });
    extract_jni.addFileArg(jni_archive);
    const jni_root = extract_jni.addOutputDirectoryArg("cronet-jni");
    for (declarations.cronet.jni_outputs) |output| files.append(b.allocator, jni_root.path(b, output)) catch @panic("OOM");

    const jni_list = inputs.add("cronet-jni-sources.txt", b.fmt("{s}\n", .{std.mem.join(b.allocator, "\n", declarations.cronet.jni_inputs) catch @panic("OOM")}));
    const final_jni = b.addSystemCommand(&.{ "python3", "-B" });
    final_jni.addFileArg(root.path(b, "third_party/jni_zero/jni_zero.py"));
    final_jni.addArgs(&.{ "generate-final", "--include-test-only", "--use-proxy-hash", "--manual-jni-registration", "--java-sources-file" });
    final_jni.addFileArg(jni_list);
    final_jni.addArg("--srcjar-path");
    const final_archive = final_jni.addOutputFileArg("cronet-final-jni.srcjar");
    final_jni.addArg("--header-path");
    _ = final_jni.addOutputFileArg("cronet-jni-registration.h");
    final_jni.setCwd(root);
    final_jni.setName("generate stock Cronet final JNI bindings");
    const extract_final_jni = b.addSystemCommand(&.{ "python3", "-B", "-m", "zipfile", "-e" });
    extract_final_jni.addFileArg(final_archive);
    const final_jni_root = extract_final_jni.addOutputDirectoryArg("cronet-final-jni");
    for (declarations.cronet.jni_final_outputs) |output| files.append(b.allocator, final_jni_root.path(b, output)) catch @panic("OOM");

    // Chromium build/protoc_java.py uses --java_out=lite with these schemas.
    // The packaged AOSP protoc and Chromium's pinned protoc are both 3.21.12.
    const proto = b.addRunArtifact(protoc);
    const proto_root = proto.addPrefixedOutputDirectoryArg("--java_out=lite:", "cronet-protos");
    proto.addPrefixedDirectoryArg("--proto_path=", root.path(b, "components/cronet/android"));
    for (declarations.cronet.proto_inputs) |input| proto.addFileArg(root.path(b, input));
    proto.setName("generate stock Cronet protobuf messages");
    for (declarations.cronet.proto_outputs) |output| files.append(b.allocator, proto_root.path(b, output)) catch @panic("OOM");

    const version = b.addSystemCommand(&.{ "python3", "-B" });
    version.addFileArg(root.path(b, "build/util/version.py"));
    version.addArgs(&.{ "--official", "-f", "chrome/VERSION", "-f", "build/util/LASTCHANGE", "-e", declarations.cronet.api_level, "-o" });
    const api_version = version.addOutputFileArg(declarations.cronet.api_version_output);
    version.addArg("components/cronet/android/api/src/org/chromium/net/ApiVersion.template");
    version.setCwd(root);
    version.setName("generate stock Cronet API version");
    files.append(b.allocator, api_version) catch @panic("OOM");
    const impl_version = b.addSystemCommand(&.{ "python3", "-B" });
    impl_version.addFileArg(root.path(b, "build/util/version.py"));
    impl_version.addArgs(&.{ "--official", "-f", "chrome/VERSION", "-f", "build/util/LASTCHANGE", "-e", declarations.cronet.api_level, "-o" });
    const impl_version_java = impl_version.addOutputFileArg(declarations.cronet.impl_version_output);
    impl_version.addArg("components/cronet/android/java/src/org/chromium/net/impl/ImplVersion.template");
    impl_version.setCwd(root);
    impl_version.setName("generate stock Cronet implementation version");
    files.append(b.allocator, impl_version_java) catch @panic("OOM");
    inline for (declarations.cronet.templates) |entry| {
        const run = b.addSystemCommand(&.{ "cc", "-E", "-P", "-x", "c++", "-DANDROID", "-I", "." });
        run.addArgs(entry.flags);
        run.addArg(entry.input);
        run.addArg("-o");
        const output = run.addOutputFileArg(entry.output);
        run.setCwd(root);
        run.setName(b.fmt("generate stock {s}", .{entry.output}));
        files.append(b.allocator, output) catch @panic("OOM");
    }
    const result = files.toOwnedSlice(b.allocator) catch @panic("OOM");
    _ = exposePaths(b, "gen-cronet", "Generate pinned Cronet enums and configuration", result);
    return result;
}

pub fn build(b: *std.Build, o: Options) Result {
    const runner = hostTool(b, "aosp-tool-runner", "artzig/tools/aosp-tool-runner.zig");
    const aidl_tool = @import("aidl.zig").build(b, .{ .vendor = o.aosp.path(""), .target = b.graph.host });
    _ = expose(b, "aidl-tool", "Build the pinned AIDL compiler with the Zig backend", &b.addInstallArtifact(aidl_tool, .{}).step);
    var gui = @import("graphics.zig").generate(b, o.aosp.path(""), runner, aidl_tool, o.sysprop_cpp_tool);
    const renderengine = @import("renderengine.zig").generate(b, o.aosp.path(""), runner, aidl_tool, o.sysprop_cpp_tool);
    const perfetto = @import("perfetto.zig").generate(b, o.aosp.path(""), o.protobuf, o.protoc, o.perfetto_headers);
    const filter = hostTool(b, "filter-flagged-api", "tools/filter-flagged-api.zig");
    const package_rewriter = hostTool(b, "rewrite-java-package", "tools/rewrite-java-package.zig");
    const stats_tool = @import("stats_tool.zig").build(b, .{
        .aosp = o.aosp.path(""),
        .target = b.graph.host,
        .protobuf = o.protobuf,
        .protoc = o.protoc,
    });
    _ = expose(b, "stats-tool", "Build stats-log-api-gen", &stats_tool.step);
    const surfaceflinger = @import("surfaceflinger.zig").generate(b, o.aosp.path(""), runner, aidl_tool, o.protoc, perfetto, stats_tool);
    const accounts = b.addSystemCommand(&.{ "python3", "-B" });
    accounts.addFileArg(o.aosp.path(declarations.android_accounts.generator));
    accounts.addArg("aidarray");
    accounts.addFileArg(o.aosp.path(declarations.android_accounts.header));
    const account_headers = b.addWriteFiles();
    _ = account_headers.addCopyFile(accounts.captureStdOut(.{}), declarations.android_accounts.output);
    const hwui_stats_headers = b.addWriteFiles();
    const hwui_stats_sources = b.addWriteFiles();
    const hwui_stats = b.addRunArtifact(stats_tool);
    hwui_stats.setCwd(@import("stats_tool.zig").templates(b, o.aosp.path("")));
    const hwui_stats_spec = declarations.hwui_native_stats;
    hwui_stats.addArgs(&.{ "--module", hwui_stats_spec.module, "--namespace", hwui_stats_spec.namespace, "--bootstrap", "--importHeader", hwui_stats_spec.stem ++ ".h", "--header" });
    _ = hwui_stats_headers.addCopyFile(hwui_stats.addOutputFileArg(hwui_stats_spec.stem ++ ".h"), hwui_stats_spec.stem ++ ".h");
    hwui_stats.addArg("--cpp");
    _ = hwui_stats_sources.addCopyFile(hwui_stats.addOutputFileArg(hwui_stats_spec.stem ++ ".cpp"), hwui_stats_spec.stem ++ ".cpp");
    const media_stats_headers = b.addWriteFiles();
    const media_stats_sources = b.addWriteFiles();
    const media_stats = b.addRunArtifact(stats_tool);
    media_stats.setCwd(@import("stats_tool.zig").templates(b, o.aosp.path("")));
    const media_stats_spec = declarations.media_native_stats;
    media_stats.addArgs(&.{ "--module", media_stats_spec.module, "--namespace", media_stats_spec.namespace, "--importHeader", media_stats_spec.stem ++ ".h", "--header" });
    _ = media_stats_headers.addCopyFile(media_stats.addOutputFileArg(media_stats_spec.stem ++ ".h"), media_stats_spec.stem ++ ".h");
    media_stats.addArg("--cpp");
    _ = media_stats_sources.addCopyFile(media_stats.addOutputFileArg(media_stats_spec.stem ++ ".cpp"), media_stats_spec.stem ++ ".cpp");

    const media_proto = b.addRunArtifact(o.protoc);
    media_proto.addPrefixedDirectoryArg("-I", o.aosp.path("frameworks/av/drm/libmediadrm"));
    const media_proto_output = media_proto.addPrefixedOutputDirectoryArg("--cpp_out=", "media-protos");
    for (declarations.media_native_protos) |file|
        media_proto.addFileArg(o.aosp.path(b.fmt("frameworks/av/drm/libmediadrm/{s}", .{file})));
    const media = @import("graphics.zig").Generated{ .headers = media_proto_output, .sources = media_proto_output };
    const aidl_java = aidlJava(b, o.aosp, aidl_tool);
    const hidl_java = hidlJava(b, o.aosp, runner);
    const hidl_cpp = hidlCpp(b, o.aosp, runner);
    gui.hidl_headers = hidl_cpp.headers;
    const aidl_cpp = aidlCpp(b, o.aosp, aidl_tool);
    const input = @import("input.zig").generate(b, o.aosp.path(""), runner, aidl_tool, gui, aidl_cpp.headers);
    const input_dispatcher = @import("input_dispatcher.zig").generate(b, o.aosp.path(""), runner, aidl_tool, o.protoc, stats_tool);
    const graphics = b.addRunArtifact(aidl_tool);
    graphics.addArgs(&.{ "--lang=cpp", "-I" });
    graphics.addDirectoryArg(o.aosp.path("frameworks/native/libs/gui/aidl"));
    graphics.addArg("-h");
    const graphics_headers = graphics.addOutputDirectoryArg("binder-graphics-headers");
    graphics.addArg("-o");
    const graphics_sources = graphics.addOutputDirectoryArg("binder-graphics-sources");
    graphics.addFileArg(o.aosp.path(declarations.binder_graphics_aidl));
    graphics.setName("generate graphics Binder test interface");
    const cronet_java = cronetJava(b, o.aosp, o.protoc);
    const logtags_java = logtagsJava(b, o.aosp);
    const aconfig_java = aconfigJava(b, o.aosp, runner);
    const sysprop_java = syspropJava(b, o.aosp, o.sysprop_tool);
    const incremental_sysprop = syspropCpp(b, o.aosp, o.sysprop_cpp_tool, declarations.incremental_sysprop);
    const codec2_sysprop = syspropCpp(b, o.aosp, o.sysprop_cpp_tool, declarations.codec2_sysprop);
    const nano_java = nanoJava(b, o.aosp, o.protoc, o.javanano_tool, package_rewriter);
    const cppstream_headers = frameworkProtoCpp(b, o.aosp, o.protoc, o.cppstream_tool);
    const proto_java = protoJava(b, o.aosp, o.protoc, o.javastream_tool, package_rewriter);
    const stats_java = statsJava(b, o.aosp, stats_tool);
    const lite_proto_java = liteProtoJava(b, o.aosp, o.protoc);
    const express_metric_ids_java = expressMetricIdsJava(b, o.aosp);
    const system_features_java = systemFeaturesJava(b, o.aosp);
    const aconfig_device_paths_java = aconfigDevicePathsJava(b, o.aosp);
    const template_java = javaTemplates(b, o.aosp);
    const xsd = xsdSources(b, o.aosp);
    const res = resources(b, o.aosp, filter);
    const fixtures = fixtureApks(b, o.aosp, res.apk);
    const mime_resources = mimeResources(b, o.aosp);
    var java_files: std.ArrayList(LazyPath) = .empty;
    inline for (.{ template_java, cronet_java, aidl_java, hidl_java, logtags_java, aconfig_java.java, sysprop_java, nano_java.java, proto_java, lite_proto_java, stats_java, xsd.java, res.java_sources }) |generated_set| {
        java_files.appendSlice(b.allocator, generated_set) catch @panic("OOM");
    }
    java_files.append(b.allocator, express_metric_ids_java) catch @panic("OOM");
    java_files.append(b.allocator, system_features_java.source) catch @panic("OOM");
    java_files.append(b.allocator, aconfig_device_paths_java) catch @panic("OOM");

    const aggregate = b.step("generators", "Generate all framework sources, resources, and fixture APKs");
    for (java_files.items) |path| path.addStepDependencies(aggregate);
    for (aidl_cpp.headers) |path| path.addStepDependencies(aggregate);
    for (hidl_cpp.headers) |path| path.addStepDependencies(aggregate);
    for (hidl_cpp.sources) |path| path.addStepDependencies(aggregate);
    for (aconfig_java.cpp_headers) |path| path.addStepDependencies(aggregate);
    for (aconfig_java.cpp_sources) |path| path.addStepDependencies(aggregate);
    for (xsd.cpp) |native| native.source_root.addStepDependencies(aggregate);
    account_headers.getDirectory().addStepDependencies(aggregate);
    cppstream_headers.addStepDependencies(aggregate);
    inline for (.{ gui, renderengine, perfetto, surfaceflinger, input, input_dispatcher, media }) |native| {
        native.headers.addStepDependencies(aggregate);
        native.sources.addStepDependencies(aggregate);
    }
    graphics_headers.addStepDependencies(aggregate);
    graphics_sources.addStepDependencies(aggregate);
    res.apk.addStepDependencies(aggregate);
    mime_resources.addStepDependencies(aggregate);
    incremental_sysprop.source.addStepDependencies(aggregate);
    codec2_sysprop.source.addStepDependencies(aggregate);
    for (fixtures.all()) |path| path.addStepDependencies(aggregate);

    return .{
        .aidl_tool = aidl_tool,
        .jvm = .{
            .sources = java_files.toOwnedSlice(b.allocator) catch @panic("OOM"),
            .cronet_sources = cronet_java,
            .aconfig_source_roots = aconfig_java.java_roots,
            .nano_source_root = nano_java.root,
            .resources = mime_resources,
            .system_features_processor = system_features_java.processor,
            .system_features_processor_dependencies = system_features_java.dependencies,
        },
        .native = .{
            .android_account_headers = account_headers.getDirectory(),
            .cppstream_headers = cppstream_headers,
            .xsd = xsd.cpp,
            .media = media,
            .media_stats = .{ .headers = media_stats_headers.getDirectory(), .sources = media_stats_sources.getDirectory() },
            .hwui_stats = .{ .headers = hwui_stats_headers.getDirectory(), .sources = hwui_stats_sources.getDirectory() },
            .aidl_header_roots = aidl_cpp.headers,
            .aidl_source_roots = aidl_cpp.sources,
            .hidl_header_roots = hidl_cpp.headers,
            .hidl_sources = hidl_cpp.sources,
            .aconfig_header_roots = aconfig_java.cpp_headers,
            .aconfig_sources = aconfig_java.cpp_sources,
            .binder_graphics_headers = graphics_headers,
            .binder_graphics_sources = graphics_sources,
            .codec2_sysprop = .{ .headers = codec2_sysprop.headers, .source = codec2_sysprop.source },
            .incremental_sysprop_headers = incremental_sysprop.headers,
            .incremental_sysprop_source = incremental_sysprop.source,
            .gui = gui,
            .renderengine = renderengine,
            .surfaceflinger = surfaceflinger,
            .perfetto = perfetto,
            .input = input,
            .input_dispatcher = input_dispatcher,
        },
        .resources = res,
        .fixtures = fixtures,
        .aggregate_step = aggregate,
    };
}
