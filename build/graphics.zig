//! Pinned libgui/libui with a GBM gralloc backend and the shared Zig Binder runtime.
const std = @import("std");
const declarations = @import("generator_sources.zig");
const binder = @import("binder_cpp.zig");
pub const Generated = struct { headers: std.Build.LazyPath, sources: std.Build.LazyPath };

pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, runner: *std.Build.Step.Compile, sysprop: *std.Build.Step.Compile) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    inline for (.{ declarations.gui_cpp_aidl, declarations.graphics_ndk_aidl }, .{ "cpp", "ndk" }) |files, language| {
        for (files) |file| {
            const run = b.addRunArtifact(runner);
            run.addFileArg(aosp.path(b, "prebuilts/sdk/tools/linux/bin/aidl"));
            run.addArg(b.fmt("--lang={s}", .{language}));
            if (comptime std.mem.eql(u8, language, "ndk")) run.addArgs(&.{ "--structured", "--stability=vintf" });
            for (aidl_roots) |root| run.addPrefixedDirectoryArg("-I", aosp.path(b, root));
            run.addArg("-h");
            const h = run.addOutputDirectoryArg("headers");
            run.addArg("-o");
            const s = run.addOutputDirectoryArg("sources");
            run.addFileArg(aosp.path(b, file));
            _ = headers.addCopyDirectory(h, "", .{});
            _ = sources.addCopyDirectory(s, "", .{});
        }
    }
    for ([_][]const u8{ "1.0", "1.1", "1.2" }) |version| {
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/hidl-gen"));
        run.addArg("-Lc++-headers");
        run.addPrefixedDirectoryArg("-randroid.hardware:", aosp.path(b, "hardware/interfaces"));
        run.addPrefixedDirectoryArg("-randroid.hidl:", aosp.path(b, "system/libhidl/transport"));
        run.addArg("-o");
        const h = run.addOutputDirectoryArg("headers");
        run.addArg(b.fmt("android.hardware.graphics.common@{s}", .{version}));
        _ = headers.addCopyDirectory(h, "", .{});
    }
    const cache = b.addRunArtifact(runner);
    cache.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    cache.addArgs(&.{ "create-cache", "--package", "com.android.graphics.libgui.flags", "--container", "system", "--declarations" });
    cache.addFileArg(aosp.path(b, declarations.gui_aconfig));
    cache.addArg("--cache");
    const flags_cache = cache.addOutputFileArg("gui-flags.pb");
    const flags = b.addRunArtifact(runner);
    flags.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    flags.addArg("create-cpp-lib");
    flags.addArg("--cache");
    flags.addFileArg(flags_cache);
    flags.addArgs(&.{ "--mode", "force-read-only", "--out" });
    const flags_dir = flags.addOutputDirectoryArg("flags");
    _ = headers.addCopyDirectory(flags_dir.path(b, "include"), "", .{});
    _ = sources.addCopyFile(flags_dir.path(b, "com_android_graphics_libgui_flags.cc"), "com_android_graphics_libgui_flags.cc");
    const props = b.addRunArtifact(sysprop);
    props.addArg("--header-dir");
    const prop_headers = props.addOutputDirectoryArg("headers");
    props.addArg("--public-header-dir");
    _ = props.addOutputDirectoryArg("public");
    props.addArg("--source-dir");
    const prop_sources = props.addOutputDirectoryArg("sources");
    props.addArgs(&.{ "--include-name", "LibGuiProperties.sysprop.h" });
    props.addFileArg(aosp.path(b, declarations.gui_sysprop));
    _ = headers.addCopyDirectory(prop_headers, "", .{});
    _ = sources.addCopyDirectory(prop_sources, "", .{});
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory() };
}

const aidl_roots = [_][]const u8{
    "frameworks/native/aidl",
    "frameworks/native/aidl/gui",
    "frameworks/native/libs/gui/aidl",
    "frameworks/native/libs/gui",
    "frameworks/native/libs/ui",
    "frameworks/native/libs/input",
    "hardware/interfaces/graphics/common/aidl",
    "hardware/interfaces/common/aidl",
};
pub const includes = [_][]const u8{
    "external/fmtlib/include",
    "libnativehelper/include_jni",
    "frameworks/native/libs/ftl/include",
    "frameworks/native/libs/gui/include",
    "frameworks/native/libs/ui/include",
    "frameworks/native/libs/ui/include_private",
    "frameworks/native/libs/ui/include_types",
    "frameworks/native/libs/math/include",
    "frameworks/native/libs/arect/include",
    "frameworks/native/libs/nativebase/include",
    "frameworks/native/libs/nativewindow/include",
    "frameworks/native/include",
    "frameworks/native/libs/nativewindow/include-private",
    "system/core/libsync/include",
    "hardware/libhardware/include_all",
    "system/core/libgrallocusage/include",
    "system/libhidl/base/include",
    "system/libhidl/transport/include",
    "system/libfmq/base",
    "frameworks/native/libs/binder/ndk/include_cpp",
};
pub const gui_sources = [_][]const u8{
    "IGraphicBufferProducer.cpp", "IGraphicBufferProducerFlattenables.cpp", "IProducerListener.cpp",
    "BufferQueue.cpp",            "BufferQueueCore.cpp",                    "BufferQueueProducer.cpp",
    "BufferQueueConsumer.cpp",    "BufferQueueThreadState.cpp",             "BufferSlot.cpp",
    "BufferItem.cpp",             "BatchBufferOps.cpp",                     "FrameRateUtils.cpp",
    "FrameTimestamps.cpp",        "HdrMetadata.cpp",                        "GLConsumerUtils.cpp",
    "OccupancyTracker.cpp",       "Surface.cpp",                            "SurfaceComposerClient.cpp",
    "ISurfaceComposer.cpp",       "WindowInfosListenerReporter.cpp",        "BitTube.cpp",
    "VsyncEventData.cpp",         "LayerMetadata.cpp",                      "TransactionState.cpp",
    "WindowInfosUpdate.cpp",      "WindowInfo.cpp",                         "DisplayInfo.cpp",
    "SurfaceControl.cpp",         "FenceMonitor.cpp",                       "LayerStatePermissions.cpp",
    "LayerState.cpp",             "DisplayLuts.cpp",                        "ITransactionCompletedListener.cpp",
    "BufferReleaseChannel.cpp",   "ScreenCaptureResults.cpp",               "BLASTBufferQueue.cpp",
    "Choreographer.cpp",          "DisplayEventDispatcher.cpp",             "DisplayEventReceiver.cpp",
    "BufferItemConsumer.cpp",     "ConsumerBase.cpp",                       "GLConsumer.cpp",
    "DebugEGLImageTracker.cpp",   "SyncFeatures.cpp",                        "view/Surface.cpp",
};
pub const ui_sources = [_][]const u8{
    "ColorSpace.cpp",    "DebugUtils.cpp",          "DependencyMonitor.cpp",
    "GraphicBuffer.cpp", "GraphicBufferMapper.cpp", "GraphicBufferAllocator.cpp",
    "Gralloc.cpp",       "Fence.cpp",               "FenceTime.cpp",
    "PixelFormat.cpp",   "Rect.cpp",                "Region.cpp",
    "Transform.cpp",     "DynamicDisplayInfo.cpp",  "PictureProfileHandle.cpp",
};
pub fn configure(b: *std.Build, mod: *std.Build.Module, aosp: std.Build.LazyPath, generated: Generated) void {
    mod.addIncludePath(b.path("src/native/binder/include"));
    mod.addIncludePath(b.path("src/native/graphics/include"));
    mod.addIncludePath(generated.headers);
    for (binder.includes ++ includes) |path| mod.addIncludePath(aosp.path(b, path));
}
pub const Libraries = struct { ui: *std.Build.Step.Compile, gui: *std.Build.Step.Compile };
pub fn build(b: *std.Build, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode, aosp: std.Build.LazyPath, generated: Generated) Libraries {
    const mod = b.createModule(.{ .root_source_file = b.path("src/platform/graphics_buffer.zig"), .target = target, .optimize = optimize, .link_libc = true, .link_libcpp = true, .pic = true });
    const ui = b.addLibrary(.{ .name = "ui_host", .root_module = mod });
    configure(b, mod, aosp, generated);
    const flags = &.{ "-std=c++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DARTZIG_HOST_THREAD_PRIORITY", "-DANDROID_UTILS_CALLSTACK_ENABLED=0", "-DDEBUG_ONLY_CODE=0", "-Wno-invalid-specialization", "-Wno-inconsistent-missing-override", "-Wno-deprecated-declarations", "-Wno-deprecated-enum-compare", "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-literal-operator", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}), "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}), "-D__INTRODUCED_IN(x)=", "-D__INTRODUCED_IN_64(x)=", "-D__INTRODUCED_IN_32(x)=" };
    mod.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/libs/ui"), .files = &ui_sources, .flags = flags });
    mod.addCSourceFile(.{ .file = b.path("src/native/graphics/HostGralloc.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, "frameworks/native/libs/nativewindow/AHardwareBuffer.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, "system/core/libgrallocusage/GrallocUsageConversion.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, "system/core/libsync/sync.c"), .flags = &.{ "-std=gnu11", "-D__INTRODUCED_IN(x)=" } });
    mod.linkSystemLibrary("gbm", .{});
    mod.linkSystemLibrary("EGL", .{});
    const gui_mod = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, gui_mod, aosp, generated);
    gui_mod.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/libs/gui"), .files = &gui_sources, .flags = flags });
    gui_mod.addCSourceFile(.{ .file = b.path("src/native/graphics/SurfaceQueue.cpp"), .flags = flags });
    gui_mod.addCSourceFile(.{ .file = generated.sources.path(b, "LibGuiProperties.sysprop.cpp"), .flags = flags });
    gui_mod.addCSourceFile(.{ .file = generated.sources.path(b, "com_android_graphics_libgui_flags.cc"), .flags = flags });
    for (declarations.gui_cpp_aidl ++ declarations.graphics_ndk_aidl) |file| {
        const namespace_start = (std.mem.indexOf(u8, file, "/android/") orelse unreachable) + 1;
        const output = b.fmt("{s}.cpp", .{file[namespace_start .. file.len - ".aidl".len]});
        gui_mod.addCSourceFile(.{ .file = generated.sources.path(b, output), .flags = flags });
    }
    gui_mod.linkLibrary(ui);
    gui_mod.linkSystemLibrary("GLESv2", .{});
    const gui = b.addLibrary(.{ .name = "gui_host", .root_module = gui_mod });
    return .{ .ui = ui, .gui = gui };
}
