//! Pinned Codec2 transport and bufferpool2. Decoder integration uses these
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

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    aosp: std.Build.LazyPath,
    generated: graphics.Generated,
    gui_generated: graphics.Generated,
    headers: []const std.Build.LazyPath,
    sources: []const std.Build.LazyPath,
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

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
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
    return library;
}
