//! Pinned SurfaceFlinger layer state, hierarchy and composition preparation.
const std = @import("std");
const re = @import("renderengine.zig");
const declarations = @import("generator_sources.zig");
pub const Generated = re.Generated;

pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, runner: *std.Build.Step.Compile, aidl_tool: *std.Build.Step.Compile, protoc: *std.Build.Step.Compile, perfetto: Generated, stats_tool: *std.Build.Step.Compile) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    const stats = b.addRunArtifact(stats_tool);
    stats.setCwd(@import("stats_tool.zig").templates(b, aosp));
    stats.addArgs(&.{ "--module", "surfaceflinger", "--namespace", "android,surfaceflinger,stats", "--bootstrap", "--importHeader", "statslog_surfaceflinger.h", "--header" });
    _ = headers.addCopyFile(stats.addOutputFileArg("statslog_surfaceflinger.h"), "statslog_surfaceflinger.h");
    stats.addArg("--cpp");
    _ = sources.addCopyFile(stats.addOutputFileArg("statslog_surfaceflinger.cpp"), "statslog_surfaceflinger.cpp");
    // ComposerHal's C++ types reference HIDL declarations, even for a host
    // backend which does not connect to a HIDL service or transport.
    for ([_][]const u8{
        "android.hidl.base@1.0",                  "android.hidl.manager@1.0",               "android.hidl.manager@1.1",               "android.hidl.manager@1.2",
        "android.hardware.graphics.composer@2.1", "android.hardware.graphics.composer@2.2", "android.hardware.graphics.composer@2.3", "android.hardware.graphics.composer@2.4",
        "android.hardware.power@1.0",             "android.hardware.power@1.1",             "android.hardware.power@1.2",             "android.hardware.power@1.3",
        "android.hardware.configstore@1.0",       "android.hardware.configstore@1.1",
    }) |package| {
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/hidl-gen"));
        run.addArg("-Lc++-headers");
        run.addPrefixedDirectoryArg("-randroid.hardware:", aosp.path(b, "hardware/interfaces"));
        run.addPrefixedDirectoryArg("-randroid.hidl:", aosp.path(b, "system/libhidl/transport"));
        run.addArg("-o");
        const output = run.addOutputDirectoryArg("headers");
        run.addArg(package);
        _ = headers.addCopyDirectory(output, "", .{});
    }
    for (declarations.surfaceflinger_ndk_aidl) |file| {
        const run = b.addRunArtifact(aidl_tool);
        run.addArgs(&.{ "--lang=ndk", "--structured" });
        if (std.mem.startsWith(u8, file, "hardware/")) run.addArg("--stability=vintf");
        // Pinned power/aidl/Android.bp selects android.hardware.power-V6. Its
        // frozen hash is in aidl_api/android.hardware.power/6/.hash.
        if (std.mem.startsWith(u8, file, "hardware/interfaces/power/aidl/"))
            run.addArgs(&.{ "--version=6", "--hash=13171cf98a48de298baf85167633376ea3db4ea0" });
        for ([_][]const u8{ "graphics/composer/aidl", "graphics/common/aidl", "common/aidl", "drm/common/aidl", "power/aidl", "common/fmq/aidl" }) |root|
            run.addPrefixedDirectoryArg("-I", aosp.path(b, b.fmt("hardware/interfaces/{s}", .{root})));
        run.addPrefixedDirectoryArg("-I", aosp.path(b, "frameworks/native/services/surfaceflinger/PowerAdvisor/aidl"));
        run.addArg("-h");
        const h = run.addOutputDirectoryArg("headers");
        run.addArg("-o");
        const s = run.addOutputDirectoryArg("sources");
        run.addFileArg(aosp.path(b, file));
        _ = headers.addCopyDirectory(h, "", .{});
        _ = sources.addCopyDirectory(s, "", .{});
    }
    _ = headers.addCopyDirectory(perfetto.headers, "", .{});
    for (declarations.surfaceflinger_service_protos) |file| {
        const output = b.fmt("{s}.pb.cc", .{file[0 .. file.len - ".proto".len]});
        _ = sources.addCopyFile(perfetto.headers.path(b, output), output);
    }
    for (declarations.surfaceflinger_stats_aidl) |file| {
        const run = b.addRunArtifact(aidl_tool);
        run.addArgs(&.{ "--lang=cpp", "--structured" });
        run.addPrefixedDirectoryArg("-I", aosp.path(b, "frameworks/base/core/java"));
        run.addArg("-h");
        const h = run.addOutputDirectoryArg("headers");
        run.addArg("-o");
        const c = run.addOutputDirectoryArg("sources");
        run.addFileArg(aosp.path(b, file));
        _ = headers.addCopyDirectory(h, "", .{});
        _ = sources.addCopyDirectory(c, "", .{});
    }
    for ([_][]const u8{ "timestatsproto", "timestatsatomsproto" }) |directory| {
        const run = b.addRunArtifact(protoc);
        const root = aosp.path(b, b.fmt("frameworks/native/services/surfaceflinger/TimeStats/{s}", .{directory}));
        run.addPrefixedDirectoryArg("-I", root);
        const output = run.addPrefixedOutputDirectoryArg("--cpp_out=", "protos");
        run.addFileArg(root.path(b, if (std.mem.eql(u8, directory, "timestatsproto")) "timestats.proto" else "timestats_atoms.proto"));
        _ = headers.addCopyDirectory(output, "", .{});
        _ = sources.addCopyDirectory(output, "", .{});
    }
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory() };
}

const includes = [_][]const u8{
    "frameworks/native/services/surfaceflinger",                                       "frameworks/native/services/surfaceflinger/CompositionEngine/include",
    "frameworks/native/services/surfaceflinger/FrontEnd",                              "frameworks/native/services/surfaceflinger/Scheduler/include",
    "frameworks/native/services/surfaceflinger/TimeStats/timestatsatomsproto/include", "frameworks/native/services/surfaceflinger/TimeStats/timestatsproto/include",
    "frameworks/native/services/utils/include",                                        "frameworks/native/libs/input/include",
    "external/protobuf/src",                                                           "external/perfetto/include",
    "system/libfmq/include",                                                           "hardware/interfaces/graphics/composer/2.1/utils/command-buffer/include",
    "hardware/interfaces/graphics/composer/2.2/utils/command-buffer/include",          "hardware/interfaces/graphics/composer/2.3/utils/command-buffer/include",
    "hardware/interfaces/graphics/composer/2.4/utils/command-buffer/include",          "hardware/interfaces/configstore/utils/include",
    "system/core/libprocessgroup/include",
};
pub fn configure(b: *std.Build, mod: *std.Build.Module, aosp: std.Build.LazyPath, gui: Generated, renderengine: Generated, generated: Generated) void {
    re.configure(b, mod, aosp, gui, renderengine);
    mod.addIncludePath(generated.headers);
    mod.addIncludePath(generated.headers.path(b, "protos"));
    for (includes) |path| mod.addIncludePath(aosp.path(b, path));
}
pub fn cppFlags(b: *std.Build) []const []const u8 {
    var flags: std.ArrayList([]const u8) = .empty;
    flags.appendSlice(b.allocator, re.cppFlags(b)) catch @panic("oom");
    flags.appendSlice(b.allocator, &.{ "-ffunction-sections", "-fdata-sections", "-Wno-character-conversion", "-Wno-deprecated-anon-enum-enum-conversion" }) catch @panic("oom");
    return flags.toOwnedSlice(b.allocator) catch @panic("oom");
}
pub const frontend_sources = [_][]const u8{
    "FrontEnd/LayerCreationArgs.cpp", "FrontEnd/LayerHierarchy.cpp",                       "FrontEnd/LayerLifecycleManager.cpp",
    "FrontEnd/LayerSnapshot.cpp",     "FrontEnd/LayerSnapshotBuilder.cpp",                 "FrontEnd/RequestedLayerState.cpp",
    "Scheduler/LayerInfo.cpp",        "Layer.cpp",                                         "LayerFE.cpp",
    "Tracing/TransactionTracing.cpp", "CompositionEngine/src/LayerFECompositionState.cpp", "CompositionEngine/src/DumpHelpers.cpp",
};
pub fn build(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: Generated, renderengine: Generated, generated: Generated, renderengine_lib: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, aosp, gui, renderengine, generated);
    mod.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/services/surfaceflinger"), .files = &frontend_sources, .flags = cppFlags(b) });
    mod.linkLibrary(renderengine_lib);
    return b.addLibrary(.{ .name = "surfaceflinger_frontend", .root_module = mod });
}

pub fn testTarget(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: Generated, renderengine: Generated, generated: Generated, frontend: *std.Build.Step.Compile, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile, gpu: bool) void {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, mod, aosp, gui, renderengine, generated);
    for ([_][]const u8{ "external/googletest/googletest", "external/googletest/googletest/include", "external/googletest/googlemock", "external/googletest/googlemock/include", "frameworks/native/services/surfaceflinger/tests/common" }) |path| mod.addIncludePath(aosp.path(b, path));
    var flags: std.ArrayList([]const u8) = .empty;
    flags.appendSlice(b.allocator, cppFlags(b)) catch @panic("oom");
    flags.append(b.allocator, "-UNDEBUG") catch @panic("oom");
    if (gpu) {
        mod.linkSystemLibrary("vulkan", .{});
        mod.addCSourceFile(.{ .file = b.path("tests/native/SurfaceFlingerCompositionTest.cpp"), .flags = flags.items });
    } else {
        mod.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/services/surfaceflinger/tests/unittests"), .files = &.{ "LayerHierarchyTest.cpp", "LayerLifecycleManagerTest.cpp" }, .flags = flags.items });
        mod.addCSourceFiles(.{ .root = aosp.path(b, "external/googletest"), .files = &.{ "googletest/src/gtest-all.cc", "googletest/src/gtest_main.cc", "googlemock/src/gmock-all.cc" }, .flags = flags.items });
    }
    mod.linkLibrary(frontend);
    mod.linkLibrary(binder);
    mod.linkLibrary(ndk);
    const name = if (gpu) "surfaceflinger-composition" else "surfaceflinger-frontend";
    runTest(b, mod, binder, ndk, broker, name, if (gpu) "Verify pinned SurfaceFlinger subtree GPU composition" else "Verify pinned SurfaceFlinger layer hierarchy and lifecycle contracts", .standalone, null);
}

pub const ClientMode = enum { standalone, application, system };

pub fn runTest(b: *std.Build, mod: *std.Build.Module, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile, name: []const u8, description: []const u8, mode: ClientMode, service: ?*std.Build.Step.Compile) void {
    const exe = b.addExecutable(.{ .name = b.fmt("{s}-test", .{name}), .root_module = mod });
    _ = runBinaryTest(b, exe.getEmittedBin(), binder, ndk, broker, name, description, mode, service, &.{}, &.{});
}

pub fn runBinaryTest(b: *std.Build, exe: std.Build.LazyPath, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile, name: []const u8, description: []const u8, mode: ClientMode, service: ?*std.Build.Step.Compile, extra_libraries: []const std.Build.LazyPath, service_declarations: []const []const u8) @import("command.zig").Command {
    const run = @import("command.zig").create(b);
    run.addArg("python3");
    run.addFileArg(b.path("tools/binder-facade-test.py"));
    for (service_declarations) |declaration| {
        run.addArg("--declare-service");
        run.addArg(declaration);
    }
    switch (mode) {
        .standalone => run.addArg("--standalone"),
        .system => run.addArg("--system-client"),
        .application => {},
    }
    if (service) |server| {
        run.addArg("--server");
        run.addFileArg(server.getEmittedBin());
    }
    run.addArg("--probe");
    run.addFileArg(exe);
    run.addArg("--broker");
    run.addFileArg(broker.getEmittedBin());
    run.addArg("--library");
    run.addFileArg(binder.getEmittedBin());
    run.addArg("--library");
    run.addFileArg(ndk.getEmittedBin());
    for (extra_libraries) |library| {
        run.addArg("--library");
        run.addFileArg(library);
    }
    run.addArg("--output");
    const output = run.addOutputDirectoryArg(name);
    run.run.has_side_effects = true;
    // Preserve this invocation's Mesa paths across Zig configuration-cache hits.
    run.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    const install = b.addInstallDirectory(.{ .source_dir = output, .install_dir = .prefix, .install_subdir = b.fmt("test-results/{s}", .{name}) });
    const step = b.step(b.fmt("{s}-test", .{name}), description);
    step.dependOn(&install.step);
    return run;
}

// Pinned libsurfaceflinger, libcompositionengine and libscheduler sources.
const service_sources = [_][]const u8{
    "ActivePictureTracker.cpp",
    "BackgroundExecutor.cpp",
    "Client.cpp",
    "ClientCache.cpp",
    "CompositionEngine/src/ClientCompositionRequestCache.cpp",
    "CompositionEngine/src/CompositionEngine.cpp",
    "CompositionEngine/src/Display.cpp",
    "CompositionEngine/src/DisplayColorProfile.cpp",
    "CompositionEngine/src/DisplaySurface.cpp",
    "CompositionEngine/src/DumpHelpers.cpp",
    "CompositionEngine/src/HwcAsyncWorker.cpp",
    "CompositionEngine/src/HwcBufferCache.cpp",
    "CompositionEngine/src/LayerFECompositionState.cpp",
    "CompositionEngine/src/Output.cpp",
    "CompositionEngine/src/OutputCompositionState.cpp",
    "CompositionEngine/src/OutputLayer.cpp",
    "CompositionEngine/src/OutputLayerCompositionState.cpp",
    "CompositionEngine/src/RenderSurface.cpp",
    "CompositionEngine/src/planner/CachedSet.cpp",
    "CompositionEngine/src/planner/Flattener.cpp",
    "CompositionEngine/src/planner/LayerState.cpp",
    "CompositionEngine/src/planner/Planner.cpp",
    "CompositionEngine/src/planner/Predictor.cpp",
    "CompositionEngine/src/planner/TexturePool.cpp",
    "Display/DisplayModeController.cpp",
    "Display/DisplaySnapshot.cpp",
    "DisplayDevice.cpp",
    "DisplayHardware/FramebufferSurface.cpp",
    "DisplayHardware/HWC2.cpp",
    "DisplayHardware/HWComposer.cpp",
    "DisplayHardware/VirtualDisplaySurface.cpp",
    "Effects/Daltonizer.cpp",
    "FpsReporter.cpp",
    "FrameTimeline/FrameTimeline.cpp",
    "FrameTracer/FrameTracer.cpp",
    "FrameTracker.cpp",
    "FrontEnd/LayerCreationArgs.cpp",
    "FrontEnd/LayerHandle.cpp",
    "FrontEnd/LayerHierarchy.cpp",
    "FrontEnd/LayerLifecycleManager.cpp",
    "FrontEnd/LayerSnapshot.cpp",
    "FrontEnd/LayerSnapshotBuilder.cpp",
    "FrontEnd/RequestedLayerState.cpp",
    "FrontEnd/TransactionHandler.cpp",
    "HdrLayerInfoReporter.cpp",
    "HdrSdrRatioOverlay.cpp",
    "Jank/JankTracker.cpp",
    "Layer.cpp",
    "LayerFE.cpp",
    "LayerProtoHelper.cpp",
    "LayerVector.cpp",
    "NativeWindowSurface.cpp",
    "PowerAdvisor/PowerAdvisor.cpp",
    "PowerAdvisor/SessionLayerMap.cpp",
    "PowerAdvisor/SessionManager.cpp",
    "RefreshRateOverlay.cpp",
    "RegionSamplingThread.cpp",
    "Scheduler/EventThread.cpp",
    "Scheduler/FrameRateOverrideMappings.cpp",
    "Scheduler/LayerHistory.cpp",
    "Scheduler/LayerInfo.cpp",
    "Scheduler/MessageQueue.cpp",
    "Scheduler/OneShotTimer.cpp",
    "Scheduler/RefreshRateSelector.cpp",
    "Scheduler/Scheduler.cpp",
    "Scheduler/SmallAreaDetectionAllowMappings.cpp",
    "Scheduler/VSyncDispatchTimerQueue.cpp",
    "Scheduler/VSyncPredictor.cpp",
    "Scheduler/VSyncReactor.cpp",
    "Scheduler/VsyncConfiguration.cpp",
    "Scheduler/VsyncModulator.cpp",
    "Scheduler/VsyncSchedule.cpp",
    "Scheduler/src/FrameTargeter.cpp",
    "Scheduler/src/PresentLatencyTracker.cpp",
    "Scheduler/src/Timer.cpp",
    "ScreenCaptureOutput.cpp",
    "SurfaceFlinger.cpp",
    "SurfaceFlingerDefaultFactory.cpp",
    "SurfaceFlingerProperties.cpp",
    "TimeStats/TimeStats.cpp",
    "TimeStats/timestatsproto/TimeStatsHelper.cpp",
    "Tracing/LayerDataSource.cpp",
    "Tracing/LayerTracing.cpp",
    "Tracing/TransactionDataSource.cpp",
    "Tracing/TransactionProtoParser.cpp",
    "Tracing/TransactionTracing.cpp",
    "Tracing/tools/LayerTraceGenerator.cpp",
    "TransactionCallbackInvoker.cpp",
    "TunnelModeEnabledReporter.cpp",
    "WindowInfosListenerInvoker.cpp",
};

// This archive intentionally exposes compilation separately from the service
// executable: boot and Binder publication are validated by the service harness.
pub fn buildServiceCore(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: Generated, renderengine: Generated, generated: Generated, frontend: *std.Build.Step.Compile, perfetto: *std.Build.Step.Compile, protobuf: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, aosp, gui, renderengine, generated);
    for (service_sources) |file| {
        for (frontend_sources) |included| {
            if (std.mem.eql(u8, file, included)) break;
        } else mod.addCSourceFile(.{ .file = aosp.path(b, b.fmt("frameworks/native/services/surfaceflinger/{s}", .{file})), .flags = cppFlags(b) });
    }
    const flags = cppFlags(b);
    mod.addIncludePath(aosp.path(b, "system/core/libstats/bootstrap/include"));
    for ([_][]const u8{
        "system/core/libutils/SystemClock.cpp",
        "system/core/libcutils/properties.cpp",
        "system/libbase/file.cpp",
        "system/libfmq/EventFlag.cpp",
        "system/libfmq/FmqInternal.cpp",
        "system/core/libstats/bootstrap/StatsBootstrapAtomClient.cpp",
        "system/core/libstats/bootstrap/BootstrapClientInternal.cpp",
        "frameworks/native/services/utils/PriorityDumper.cpp",
        "frameworks/native/services/powermanager/PowerHalController.cpp",
        "frameworks/native/services/powermanager/PowerHalWrapper.cpp",
        "frameworks/native/services/powermanager/PowerHintSessionWrapper.cpp",
        "frameworks/native/libs/gui/CompositorTiming.cpp",
        "frameworks/native/libs/ui/DeviceProductInfo.cpp",
        "frameworks/native/libs/ui/DisplayIdentification.cpp",
    }) |file| mod.addCSourceFile(.{ .file = aosp.path(b, file), .flags = flags });
    for (declarations.surfaceflinger_service_protos) |file|
        mod.addCSourceFile(.{ .file = generated.sources.path(b, b.fmt("{s}.pb.cc", .{file[0 .. file.len - ".proto".len]})), .flags = flags });
    for ([_][]const u8{ "timestats.pb.cc", "timestats_atoms.pb.cc", "statslog_surfaceflinger.cpp" }) |file|
        mod.addCSourceFile(.{ .file = generated.sources.path(b, file), .flags = flags });
    for (declarations.surfaceflinger_stats_aidl) |file| {
        const start = (std.mem.indexOf(u8, file, "/android/") orelse unreachable) + 1;
        mod.addCSourceFile(.{ .file = generated.sources.path(b, b.fmt("{s}.cpp", .{file[start .. file.len - 5]})), .flags = flags });
    }
    for (declarations.surfaceflinger_ndk_aidl) |file| {
        if (!std.mem.startsWith(u8, file, "hardware/interfaces/power/aidl/") and !std.mem.startsWith(u8, file, "frameworks/native/services/surfaceflinger/PowerAdvisor/aidl/")) continue;
        const start = (std.mem.indexOf(u8, file, "/android/") orelse unreachable) + 1;
        mod.addCSourceFile(.{ .file = generated.sources.path(b, b.fmt("{s}.cpp", .{file[start .. file.len - 5]})), .flags = flags });
    }
    mod.addCSourceFile(.{ .file = b.path("src/native/graphics/SurfaceFlingerHost.cpp"), .flags = flags });
    mod.linkLibrary(protobuf);
    mod.linkLibrary(frontend);
    mod.linkLibrary(perfetto);
    return b.addLibrary(.{ .name = "surfaceflinger_core", .root_module = mod });
}

pub fn buildHostComposer(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: Generated, renderengine: Generated, generated: Generated) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .root_source_file = b.path("src/platform/host_composer.zig"), .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, aosp, gui, renderengine, generated);
    mod.linkSystemLibrary("EGL", .{});
    mod.addCSourceFile(.{ .file = b.path("src/native/graphics/HostComposer.cpp"), .flags = cppFlags(b) });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libhidl/base"), .files = &.{ "HidlSupport.cpp", "Status.cpp" }, .flags = cppFlags(b) });
    return b.addLibrary(.{ .name = "host_composer", .root_module = mod });
}
