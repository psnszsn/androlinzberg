//! Pinned Codec2 clients, transport and bufferpool2. Decoder integration uses these
//! original work/handle protocols rather than a second hosted codec protocol.
const std = @import("std");
const declarations = @import("generator_sources.zig");
const graphics = @import("graphics.zig");

pub const pool_root = "hardware/interfaces/media/bufferpool/aidl/default";
pub const pool_sources = [_][]const u8{
    "Accessor.cpp",      "BufferPool.cpp", "BufferPoolClient.cpp", "BufferStatus.cpp",
    "ClientManager.cpp", "Connection.cpp", "Observer.cpp",
};

pub fn isProtocol(entry: declarations.CppAidl) bool {
    return std.mem.startsWith(u8, entry.root, "hardware/interfaces/media/c2/aidl/") or
        std.mem.startsWith(u8, entry.root, "hardware/interfaces/media/bufferpool/aidl/");
}

pub fn isClientProtocol(entry: declarations.CppAidl) bool {
    return std.mem.eql(u8, entry.root, "frameworks/av/media/module/aidlpersistentsurface/aidl") or
        std.mem.eql(u8, entry.root, "frameworks/base/media/java/android/media/quality/aidl") or
        (std.mem.eql(u8, entry.root, "frameworks/av/media/libmedia/aidl") and
            (std.mem.eql(u8, entry.input, "android/IGraphicBufferSource.aidl") or
                std.mem.eql(u8, entry.input, "android/IOMXBufferSource.aidl")));
}

pub fn isResourceProtocol(entry: declarations.CppAidl) bool {
    return std.mem.startsWith(u8, entry.root, "frameworks/av/services/mediaresourcemanager/aidl_api/") or
        std.mem.eql(u8, entry.input, "android/app/ProcessStateEnum.aidl");
}

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    aosp: std.Build.LazyPath,
    renderengine_headers: std.Build.LazyPath,
    renderengine_sources: std.Build.LazyPath,
    package_native_headers: std.Build.LazyPath,
    package_native_sources: std.Build.LazyPath,
    host_include_roots: []const std.Build.LazyPath,
    generated: graphics.Generated,
    media_stats: graphics.Generated,
    gui_generated: graphics.Generated,
    headers: []const std.Build.LazyPath,
    sources: []const std.Build.LazyPath,
    hidl_headers: []const std.Build.LazyPath,
    flag_headers: []const std.Build.LazyPath,
    flag_sources: []const std.Build.LazyPath,
    sysprop_headers: std.Build.LazyPath,
    sysprop_source: std.Build.LazyPath,
    binder: *std.Build.Step.Compile,
    ndk: *std.Build.Step.Compile,
    broker: *std.Build.Step.Compile,
    gui: *std.Build.Step.Compile,
};

fn configure(b: *std.Build, mod: *std.Build.Module, o: Options) void {
    graphics.configure(b, mod, o.aosp, o.gui_generated);
    mod.addIncludePath(o.generated.headers);
    for (o.headers) |header| mod.addIncludePath(header);
    for ([_][]const u8{
        pool_root ++ "/include",                              "hardware/interfaces/common/support/include",
        "system/libfmq/include",                              "system/libfmq/base",
        "frameworks/native/libs/binder/ndk/include_platform", "frameworks/native/libs/binder/ndk/include_cpp",
        "frameworks/av/media/codec2/core/include",            "frameworks/av/media/codec2/vndk/include",
    }) |path| mod.addIncludePath(o.aosp.path(b, path));
}

pub const Libraries = struct {
    transport: *std.Build.Step.Compile,
    client: *std.Build.Step.Compile,
    resources: *std.Build.Step.Compile,
    aosp: std.Build.LazyPath,
    flags: []const []const u8,
};

pub fn build(b: *std.Build, o: Options) Libraries {
    const flags = &[_][]const u8{ "-std=gnu++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-fno-strict-aliasing", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DBUFFERPOOL_CLONE_HANDLES", "-D__INTRODUCED_IN(x)=", "-D__ANDROID_API_U__=34", "-D__ANDROID_API_S__=31", "-D__unused=__attribute__((unused))", "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-deprecated-enum-compare", "-fno-char8_t", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, o);
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, pool_root), .files = &pool_sources, .flags = flags });
    for (declarations.cpp_aidl, o.sources) |entry, root| {
        if (isProtocol(entry)) if (entry.output) |source|
            mod.addCSourceFile(.{ .file = root.path(b, source), .flags = flags });
    }
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "system/libfmq"), .files = &.{ "EventFlag.cpp", "FmqInternal.cpp" }, .flags = flags });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "hardware/interfaces/common/support"), .files = &.{"NativeHandle.cpp"}, .flags = flags });
    mod.addCSourceFiles(.{ .root = o.generated.sources, .files = &.{ "android/hardware/common/NativeHandle.cpp", "android/hardware/common/fmq/GrantorDescriptor.cpp" }, .flags = flags });
    // C++ logging remains with the consuming runtime, as in the other native modules.
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "system/libbase"), .files = &.{ "logging.cpp", "threads.cpp", "file.cpp" }, .flags = flags });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "system/core/libutils/SystemClock.cpp"), .flags = flags });
    mod.linkLibrary(o.gui);
    const library = b.addLibrary(.{ .name = "codec2_transport", .root_module = mod });
    b.step("codec2-transport", "Compile pinned Codec2 protocols and bufferpool2").dependOn(&library.step);
    const client = buildClients(b, o, flags, library);

    const pool_test = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, pool_test, o);
    pool_test.addIncludePath(o.aosp.path(b, "external/googletest/googletest"));
    pool_test.addIncludePath(o.aosp.path(b, "external/googletest/googletest/include"));
    pool_test.addCSourceFile(.{ .file = o.aosp.path(b, pool_root ++ "/tests/allocator.cpp"), .flags = flags });
    pool_test.addCSourceFile(.{ .file = o.aosp.path(b, pool_root ++ "/tests/single.cpp"), .flags = flags ++ &[_][]const u8{"-Dmain=codec2BufferpoolTestMain"} });
    pool_test.addCSourceFile(.{ .file = b.path("tests/native/Codec2BufferpoolMain.cpp"), .flags = flags });
    pool_test.addCSourceFile(.{ .file = o.aosp.path(b, "external/googletest/googletest/src/gtest-all.cc"), .flags = flags });
    pool_test.linkLibrary(library);
    pool_test.linkLibrary(o.binder);
    pool_test.linkLibrary(o.ndk);
    @import("surfaceflinger.zig").runTest(b, pool_test, o.binder, o.ndk, o.broker, "codec2-bufferpool", "Run original AIDL bufferpool allocation, recycling and transfer tests", .standalone, null);

    const transport_test = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, transport_test, o);
    transport_test.addIncludePath(o.aosp.path(b, pool_root ++ "/tests"));
    const test_flags = flags ++ &[_][]const u8{"-UNDEBUG"};
    transport_test.addCSourceFile(.{ .file = b.path("tests/native/Codec2TransportTest.cpp"), .flags = test_flags });
    transport_test.addCSourceFile(.{ .file = o.aosp.path(b, pool_root ++ "/tests/allocator.cpp"), .flags = test_flags });
    transport_test.linkLibrary(library);
    transport_test.linkLibrary(o.binder);
    transport_test.linkLibrary(o.ndk);
    @import("surfaceflinger.zig").runTest(b, transport_test, o.binder, o.ndk, o.broker, "codec2-transport", "Verify stock Codec2 work, shared buffers and service death across processes", .application, null);

    const params = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, params, o);
    params.addIncludePath(o.aosp.path(b, "external/googletest/googletest"));
    params.addIncludePath(o.aosp.path(b, "external/googletest/googletest/include"));
    const param_flags = flags ++ &[_][]const u8{"-std=gnu++17"};
    params.addCSourceFiles(.{ .root = o.aosp.path(b, "frameworks/av/media/codec2/tests"), .files = &.{ "C2Param_test.cpp", "C2_test.cpp" }, .flags = param_flags });
    params.addCSourceFiles(.{ .root = o.aosp.path(b, "external/googletest/googletest"), .files = &.{ "src/gtest-all.cc", "src/gtest_main.cc" }, .flags = param_flags });
    const exe = b.addExecutable(.{ .name = "codec2-params-test", .root_module = params });
    const run = b.addRunArtifact(exe);
    b.step("codec2-params-test", "Run original Codec2 parameter and scalar contract tests").dependOn(&run.step);
    const client_flags = clientFlags(b, flags);
    const resources = buildResources(b, o, client, client_flags);
    return .{ .transport = library, .client = client, .resources = resources, .aosp = o.aosp, .flags = client_flags };
}

fn clientFlags(b: *std.Build, flags: []const []const u8) []const []const u8 {
    return std.mem.concat(b.allocator, []const u8, &.{ flags, &.{ "-Wno-invalid-specialization", "-Wno-macro-redefined", "-Wno-deprecated-declarations", "-Wno-deprecated-enum-enum-conversion", "-Wno-reorder-init-list", "-Wno-inconsistent-missing-override", "-Wno-nontrivial-memcall", "-D__ANDROID__", "-D__ANDROID_API__=36", "-D__ANDROID_MIN_SDK_VERSION__=36", "-include", b.pathResolve(&.{"src/native/codec2/Compat.h"}) } }) catch @panic("OOM");
}

fn buildClients(b: *std.Build, o: Options, flags: []const []const u8, transport: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const selected = @import("codec2_sources.zig");
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, o);
    const libc_headers = b.addWriteFiles();
    _ = libc_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/bits/get_device_api_level_inlines.h"), "bits/get_device_api_level_inlines.h");
    for ([_][]const u8{ "api-level.h", "versioning.h", "fdsan.h", "dlext.h" }) |name|
        _ = libc_headers.addCopyFile(o.aosp.path(b, b.fmt("bionic/libc/include/android/{s}", .{name})), b.fmt("android/{s}", .{name}));
    mod.addIncludePath(o.renderengine_headers);
    mod.addIncludePath(o.package_native_headers);
    mod.addIncludePath(libc_headers.getDirectory());
    mod.addIncludePath(o.sysprop_headers);
    for (o.hidl_headers) |header| mod.addIncludePath(header);
    for (o.flag_headers) |header| mod.addIncludePath(header);
    for (selected.includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    for (o.host_include_roots) |path| mod.addSystemIncludePath(path);
    // Pinned C2EasyEnum specializes traits which newer host libc++ diagnoses.
    // Keep AOSP's enum representation. Compat.h also includes Binder logging
    // before each source supplies its own LOG_TAG.
    const client_flags = clientFlags(b, flags);
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "system/memory/libion/ion.c"), .flags = &.{ "-std=gnu11", "-include", b.pathResolve(&.{"src/native/codec2/CompatC.h"}) } });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "bionic/libc/bionic/get_device_api_level.cpp"), .language = .c, .flags = &.{ "-std=c11", "-D_Nonnull=" } });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "system/core/libcutils/properties.cpp"), .flags = std.mem.concat(b.allocator, []const u8, &.{ client_flags, &.{ "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}) } }) catch @panic("OOM") });
    for (selected.groups) |group| {
        const group_flags = std.mem.concat(b.allocator, []const u8, &.{ client_flags, group.flags }) catch @panic("OOM");
        mod.addCSourceFiles(.{ .root = o.aosp.path(b, group.root), .files = group.files, .flags = group_flags });
    }
    mod.addCSourceFile(.{ .file = o.sysprop_source, .flags = client_flags });
    mod.addCSourceFile(.{ .file = o.renderengine_sources.path(b, "SurfaceFlingerProperties.sysprop.cpp"), .flags = client_flags });
    for (declarations.input_dispatcher_pm_aidl) |file| {
        const root = "frameworks/native/libs/binder/aidl/";
        mod.addCSourceFile(.{ .file = o.package_native_sources.path(b, b.fmt("{s}.cpp", .{file[root.len .. file.len - ".aidl".len]})), .flags = client_flags });
    }
    for (o.flag_sources) |source| mod.addCSourceFile(.{ .file = source, .flags = client_flags });
    for (declarations.cpp_aidl, o.sources) |entry, root| {
        if (isClientProtocol(entry)) if (entry.output) |source|
            mod.addCSourceFile(.{ .file = root.path(b, source), .flags = client_flags });
    }
    const library = b.addLibrary(.{ .name = "codec2_client", .root_module = mod });
    b.step("codec2-client", "Compile pinned MediaCodec, CCodec, Codec2 and NDK clients").dependOn(&library.step);
    const allocator_test = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    allocator_test.include_dirs = mod.include_dirs;
    allocator_test.addCSourceFile(.{ .file = b.path("tests/native/Codec2AllocatorTest.cpp"), .flags = std.mem.concat(b.allocator, []const u8, &.{ client_flags, &.{"-UNDEBUG"} }) catch @panic("OOM") });
    allocator_test.linkLibrary(library);
    allocator_test.linkLibrary(transport);
    allocator_test.linkLibrary(o.binder);
    allocator_test.linkLibrary(o.ndk);
    @import("surfaceflinger.zig").runTest(b, allocator_test, o.binder, o.ndk, o.broker, "codec2-allocator", "Verify original Codec2 linear allocation and imported storage", .standalone, null);
    return library;
}

pub fn clientTest(b: *std.Build, libraries: Libraries, platform: std.Build.LazyPath, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile) void {
    const mod = b.createModule(.{ .target = libraries.client.root_module.resolved_target.?, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    mod.include_dirs = libraries.client.root_module.include_dirs;
    mod.addCSourceFile(.{ .file = b.path("tests/native/Codec2ClientTest.cpp"), .flags = &.{ "-std=gnu++17", "-UNDEBUG", "-D__INTRODUCED_IN(x)=", "-D__ANDROID_API__=36" } });
    mod.addObjectFile(platform);
    mod.linkLibrary(binder);
    mod.linkLibrary(ndk);
    const exe = b.addExecutable(.{ .name = "codec2-client-test", .root_module = mod });
    @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), binder, ndk, broker, "codec2-client", "Link the complete stock codec client and verify NDK crypto metadata ownership", .standalone, null, &.{platform});
}

pub fn ffmpegTest(b: *std.Build, libraries: Libraries, platform: std.Build.LazyPath, codec: *std.Build.Module, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile) void {
    const backend = b.addLibrary(.{ .name = "codec2_ffmpeg_backend", .root_module = codec });
    const mod = b.createModule(.{ .target = libraries.client.root_module.resolved_target.?, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    mod.include_dirs = libraries.client.root_module.include_dirs.clone(b.allocator) catch @panic("OOM");
    const flags = std.mem.concat(b.allocator, []const u8, &.{ libraries.flags, &.{"-UNDEBUG"} }) catch @panic("OOM");
    mod.addCSourceFile(.{ .file = b.path("src/native/codec2/FfmpegComponent.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = b.path("tests/native/FfmpegComponentTest.cpp"), .flags = flags });
    mod.addIncludePath(b.path("src/native/codec2"));
    mod.linkLibrary(backend);
    mod.linkSystemLibrary("libavcodec", .{ .needed = true, .use_pkg_config = .force });
    mod.linkSystemLibrary("libavutil", .{ .needed = true, .use_pkg_config = .force });
    mod.addObjectFile(platform);
    mod.linkLibrary(binder);
    mod.linkLibrary(ndk);
    const exe = b.addExecutable(.{ .name = "codec2-ffmpeg-test", .root_module = mod });
    @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), binder, ndk, broker, "codec2-ffmpeg", "Verify FFmpeg components through original Codec2 work and lifecycle", .standalone, null, &.{platform});
}

fn buildResources(b: *std.Build, o: Options, client: *std.Build.Step.Compile, flags: []const []const u8) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    mod.include_dirs = client.root_module.include_dirs.clone(b.allocator) catch @panic("OOM");
    mod.addIncludePath(o.media_stats.headers);
    for (@import("codec2_sources.zig").resource_groups) |group|
        mod.addCSourceFiles(.{ .root = o.aosp.path(b, group.root), .files = group.files, .flags = flags });
    mod.addCSourceFile(.{ .file = o.media_stats.sources.path(b, "stats_media_metrics.cpp"), .flags = flags });
    for (declarations.cpp_aidl, o.sources) |entry, root| {
        if (isResourceProtocol(entry)) if (entry.output) |source|
            mod.addCSourceFile(.{ .file = root.path(b, source), .flags = flags });
    }
    const library = b.addLibrary(.{ .name = "media_resource_service", .root_module = mod });
    b.step("media-resource-service", "Compile original codec resource management and host service clients").dependOn(&library.step);
    return library;
}

pub fn resourceTests(b: *std.Build, libraries: Libraries, platform: std.Build.LazyPath, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile) void {
    const all = b.step("media-resource-test", "Run original resource manager, observer and service log tests");
    // Soong builds these separately: their shared fixture defines non-inline symbols.
    inline for (.{ "ResourceManagerService", "ResourceObserverService", "ServiceLog" }, .{ "media-resource-manager", "media-resource-observer", "media-service-log" }) |source, name| {
        const mod = b.createModule(.{ .target = libraries.client.root_module.resolved_target.?, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
        mod.include_dirs = libraries.resources.root_module.include_dirs.clone(b.allocator) catch @panic("OOM");
        mod.addIncludePath(libraries.aosp.path(b, "external/googletest/googletest"));
        mod.addIncludePath(libraries.aosp.path(b, "external/googletest/googletest/include"));
        mod.addCSourceFile(.{ .file = libraries.aosp.path(b, "frameworks/av/services/mediaresourcemanager/test/" ++ source ++ "_test.cpp"), .flags = libraries.flags });
        mod.addCSourceFiles(.{ .root = libraries.aosp.path(b, "external/googletest/googletest"), .files = &.{ "src/gtest-all.cc", "src/gtest_main.cc" }, .flags = libraries.flags });
        mod.linkLibrary(libraries.resources);
        mod.addObjectFile(platform);
        mod.linkLibrary(binder);
        mod.linkLibrary(ndk);
        const exe = b.addExecutable(.{ .name = name ++ "-test", .root_module = mod });
        @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), binder, ndk, broker, name, "Run original " ++ source ++ " tests", .standalone, null, &.{platform});
        all.dependOn(&b.top_level_steps.get(name ++ "-test").?.step);
    }
}

pub fn service(b: *std.Build, libraries: Libraries, platform: std.Build.LazyPath, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const mod = b.createModule(.{
        .root_source_file = b.path("src/media_service_main.zig"),
        .target = libraries.client.root_module.resolved_target.?,
        .optimize = .ReleaseFast,
        .link_libc = true,
        .link_libcpp = true,
    });
    mod.include_dirs = libraries.resources.root_module.include_dirs.clone(b.allocator) catch @panic("OOM");
    mod.addCSourceFile(.{ .file = b.path("src/native/codec2/MediaServiceHost.cpp"), .flags = libraries.flags });
    mod.addCSourceFiles(.{ .root = libraries.aosp.path(b, "frameworks/av/media/libmedia"), .files = &.{ "MediaResource.cpp", "MediaResourcePolicy.cpp" }, .flags = libraries.flags });
    mod.linkLibrary(libraries.resources);
    // GUI/foundation globals have one owner in the existing runtime DSO.
    mod.addObjectFile(platform);
    mod.addObjectFile(binder.getEmittedBin());
    mod.addObjectFile(ndk.getEmittedBin());
    mod.addRPathSpecial("$ORIGIN/../lib");
    mod.addRPathSpecial("$ORIGIN/../lib/art");
    const exe = b.addExecutable(.{ .name = "azk-media-service", .root_module = mod });
    b.step("media-service", "Build supervised original media resource services").dependOn(&exe.step);
    return exe;
}

pub fn serviceProbe(b: *std.Build, libraries: Libraries, platform: std.Build.LazyPath, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = libraries.client.root_module.resolved_target.?, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    mod.include_dirs = libraries.resources.root_module.include_dirs.clone(b.allocator) catch @panic("OOM");
    const flags = std.mem.concat(b.allocator, []const u8, &.{ libraries.flags, &.{"-UNDEBUG"} }) catch @panic("OOM");
    mod.addCSourceFile(.{ .file = b.path("tests/native/MediaResourceServiceTest.cpp"), .flags = flags });
    mod.addObjectFile(platform);
    mod.linkLibrary(binder);
    mod.linkLibrary(ndk);
    return b.addExecutable(.{ .name = "media-resource-service-test", .root_module = mod });
}
