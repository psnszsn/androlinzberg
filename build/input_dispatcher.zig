//! Stock InputDispatcher and its tracing, statistics and key-map closure.
const std = @import("std");
const input = @import("input.zig");
const graphics = @import("graphics.zig");
const declarations = @import("generator_sources.zig");
pub const Generated = graphics.Generated;

pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, runner: *std.Build.Step.Compile, aidl_tool: *std.Build.Step.Compile, protoc: *std.Build.Step.Compile, stats: *std.Build.Step.Compile) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    inline for (.{ &declarations.input_dispatcher_pm_aidl, &declarations.input_dispatcher_stats_aidl }, .{ "cpp", "ndk" }) |files, language| {
        for (files) |file| {
            const run = b.addRunArtifact(aidl_tool);
            run.addArg(b.fmt("--lang={s}", .{language}));
            for ([_][]const u8{ "frameworks/native/libs/binder/aidl", "frameworks/native/libs/binder/ndk/aidl", "packages/modules/StatsD/aidl" }) |root| run.addPrefixedDirectoryArg("-I", aosp.path(b, root));
            run.addArg("-h");
            const h = run.addOutputDirectoryArg("headers");
            run.addArg("-o");
            const s = run.addOutputDirectoryArg("sources");
            run.addFileArg(aosp.path(b, file));
            _ = headers.addCopyDirectory(h, "", .{});
            _ = sources.addCopyDirectory(s, "", .{});
        }
    }
    for ([_][]const u8{ "aggregator.proto", "kll-quantiles.proto" }) |name| {
        const run = b.addRunArtifact(protoc);
        run.addPrefixedDirectoryArg("-I", aosp.path(b, "packages/modules/StatsD/lib/libkll/proto"));
        const output = run.addPrefixedOutputDirectoryArg("--cpp_out=", "protos");
        run.addFileArg(aosp.path(b, b.fmt("packages/modules/StatsD/lib/libkll/proto/{s}", .{name})));
        _ = headers.addCopyDirectory(output, "", .{});
        _ = sources.addCopyDirectory(output, "", .{});
    }
    const stage = @import("stats_tool.zig").templates(b, aosp);
    for ([_]bool{ false, true }) |socket| {
        const run = b.addRunArtifact(stats);
        run.setCwd(stage);
        const name: []const u8 = if (socket) "stats_statsdsocketlog" else "statslog";
        if (socket) run.addArgs(&.{ "--module", "statsdsocket", "--namespace", "android,os,statsdsocket", "--importHeader", "stats_statsdsocketlog.h" });
        run.addArg("--header");
        _ = headers.addCopyFile(run.addOutputFileArg(b.fmt("{s}.h", .{name})), b.fmt("{s}.h", .{name}));
        run.addArg("--cpp");
        _ = sources.addCopyFile(run.addOutputFileArg(b.fmt("{s}.cpp", .{name})), b.fmt("{s}.cpp", .{name}));
    }
    const window_flags = for (declarations.aconfig) |entry| {
        if (std.mem.eql(u8, entry.package, "com.android.window.flags")) break entry.declarations;
    } else unreachable;
    inline for (.{ "com.android.window.flags", "com.android.os.statsd.flags" }, .{ window_flags, &declarations.input_dispatcher_stats_flags }, .{ "system", "com.android.os.statsd" }, .{ "com_android_window_flags.cc", "com_android_os_statsd_flags.cc" }) |package, files, container, filename| {
        const cache = b.addRunArtifact(runner);
        cache.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
        cache.addArgs(&.{ "create-cache", "--package", package, "--container", container });
        for (files) |file| {
            cache.addArg("--declarations");
            cache.addFileArg(aosp.path(b, file));
        }
        cache.addArg("--cache");
        const flags_cache = cache.addOutputFileArg("flags.pb");
        const run = b.addRunArtifact(runner);
        run.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
        run.addArgs(&.{ "create-cpp-lib", "--cache" });
        run.addFileArg(flags_cache);
        run.addArgs(&.{ "--mode", "force-read-only", "--out" });
        const output = run.addOutputDirectoryArg("flags");
        _ = headers.addCopyDirectory(output.path(b, "include"), "", .{});
        _ = sources.addCopyFile(output.path(b, filename), filename);
    }
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory() };
}

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    aosp: std.Build.LazyPath,
    gui: graphics.Generated,
    input_generated: input.Generated,
    aidl: []const std.Build.LazyPath,
    generated: Generated,
    perfetto_generated: @import("perfetto.zig").Generated,
    transport: input.Library,
    perfetto: *std.Build.Step.Compile,
    protobuf: *std.Build.Step.Compile,
    binder: *std.Build.Step.Compile,
    ndk: *std.Build.Step.Compile,
    broker: *std.Build.Step.Compile,
};

pub fn configure(b: *std.Build, mod: *std.Build.Module, o: Options) void {
    input.configure(b, mod, o.aosp, o.gui, o.input_generated, o.aidl);
    @import("perfetto.zig").configure(b, mod, o.aosp, o.perfetto_generated);
    mod.addIncludePath(o.generated.headers);
    mod.addIncludePath(o.perfetto_generated.headers.path(b, "protos"));
    for ([_][]const u8{
        "frameworks/native/services/inputflinger",                "frameworks/native/services/inputflinger/include",
        "frameworks/native/services/inputflinger/dispatcher",     "frameworks/native/services/inputflinger/dispatcher/include",
        "frameworks/native/services/inputflinger/reader/include", "frameworks/native/services/inputflinger/reporter/include",
        "frameworks/native/services/inputflinger/reporter",       "frameworks/native/libs/attestation/include",
        "packages/modules/StatsD/lib/libkll",                     "packages/modules/StatsD/lib/libkll/include",
        "packages/modules/StatsD/lib/libstatspull",               "packages/modules/StatsD/lib/libstatspull/include",
        "packages/modules/StatsD/lib/libstatssocket",             "packages/modules/StatsD/lib/libstatssocket/include",
        "external/protobuf/src",                                  "system/core/libprocessgroup/include",
        "system/server_configurable_flags/libflags/include",
    }) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.linkSystemLibrary("openssl", .{});
}

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, o);
    var cpp_flags: std.ArrayList([]const u8) = .empty;
    cpp_flags.appendSlice(b.allocator, input.cppFlags(b)) catch @panic("oom");
    cpp_flags.appendSlice(b.allocator, &.{ "-DARTZIG_HOST_GRAPHICS", "-Wno-inconsistent-missing-override" }) catch @panic("oom");
    const flags = cpp_flags.items;
    mod.addCSourceFiles(.{ .root = o.aosp, .files = &declarations.input_dispatcher_sources, .flags = flags });
    mod.addCSourceFiles(.{ .root = o.aosp, .files = &declarations.input_dispatcher_c_sources, .flags = &.{ "-std=gnu11", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-ffunction-sections", "-fdata-sections" } });
    mod.addCSourceFiles(.{ .root = o.generated.sources, .files = &.{ "statslog.cpp", "stats_statsdsocketlog.cpp", "aggregator.pb.cc", "kll-quantiles.pb.cc", "com_android_window_flags.cc", "com_android_os_statsd_flags.cc" }, .flags = flags });
    inline for (.{ &declarations.input_dispatcher_pm_aidl, &declarations.input_dispatcher_stats_aidl }, .{ "frameworks/native/libs/binder/aidl/", "packages/modules/StatsD/aidl/" }) |files, root| {
        for (files) |file| mod.addCSourceFile(.{ .file = o.generated.sources.path(b, b.fmt("{s}.cpp", .{file[root.len .. file.len - ".aidl".len]})), .flags = flags });
    }
    o.transport.link(mod);
    mod.linkLibrary(o.perfetto);
    mod.linkLibrary(o.protobuf);
    mod.linkLibrary(o.binder);
    mod.linkLibrary(o.ndk);
    const library = b.addLibrary(.{ .name = "input_dispatcher", .root_module = mod });
    b.step("input-dispatcher", "Build stock input dispatch, tracing and latency reporting").dependOn(&library.step);

    const test_mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, test_mod, o);
    for ([_][]const u8{ "frameworks/native/services/inputflinger/tests", "external/googletest/googletest", "external/googletest/googletest/include", "external/googletest/googlemock/include" }) |path| test_mod.addIncludePath(o.aosp.path(b, path));
    var test_flags: std.ArrayList([]const u8) = .empty;
    test_flags.appendSlice(b.allocator, flags) catch @panic("oom");
    test_flags.append(b.allocator, "-UNDEBUG") catch @panic("oom");
    test_mod.addCSourceFile(.{ .file = b.path("tests/native/InputDispatcherTest.cpp"), .flags = test_flags.items });
    test_mod.addCSourceFiles(.{ .root = o.aosp.path(b, "frameworks/native/services/inputflinger/tests"), .files = &.{ "FakeInputDispatcherPolicy.cpp", "FakeWindows.cpp" }, .flags = test_flags.items });
    test_mod.addCSourceFiles(.{ .root = o.aosp.path(b, "external/googletest/googletest"), .files = &.{ "src/gtest-all.cc", "src/gtest_main.cc" }, .flags = test_flags.items });
    test_mod.linkLibrary(library);
    o.transport.link(test_mod);
    @import("surfaceflinger.zig").runTest(b, test_mod, o.binder, o.ndk, o.broker, "input-dispatcher", "Verify stock routing, overlap, transforms, focus, cancellation and channel death", .standalone, null);
    return library;
}

pub fn testSurfaceFlinger(b: *std.Build, o: Options, library: *std.Build.Step.Compile, service: *std.Build.Step.Compile) void {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, mod, o);
    for ([_][]const u8{ "frameworks/native/services/inputflinger/tests", "external/googletest/googletest", "external/googletest/googletest/include", "external/googletest/googlemock/include" }) |path| mod.addIncludePath(o.aosp.path(b, path));
    var flags: std.ArrayList([]const u8) = .empty;
    flags.appendSlice(b.allocator, input.cppFlags(b)) catch @panic("oom");
    flags.appendSlice(b.allocator, &.{ "-UNDEBUG", "-Wno-inconsistent-missing-override" }) catch @panic("oom");
    mod.addCSourceFile(.{ .file = b.path("tests/native/InputDispatcherSurfaceFlingerTest.cpp"), .flags = flags.items });
    mod.addCSourceFiles(.{ .root = o.aosp.path(b, "frameworks/native/services/inputflinger/tests"), .files = &.{ "FakeInputDispatcherPolicy.cpp", "FakeWindows.cpp" }, .flags = flags.items });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "external/googletest/googletest/src/gtest-all.cc"), .flags = flags.items });
    mod.linkSystemLibrary("GLESv2", .{});
    mod.linkLibrary(library);
    o.transport.link(mod);
    @import("surfaceflinger.zig").runTest(b, mod, o.binder, o.ndk, o.broker, "input-surfaceflinger", "Verify SF-published window transforms, overlap, cancellation and stock dispatch over AZKB", .system, service);
}

/// JNI object kept separate from the reusable native dispatcher archive.
pub fn buildJni(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, o);
    for ([_][]const u8{
        "frameworks/base/core/jni",         "frameworks/base/core/jni/include",
        "frameworks/base/native/include",   "libnativehelper/include",
        "libnativehelper/include_jni",      "libnativehelper/header_only_include",
        "libnativehelper/include_platform",
    }) |path| mod.addIncludePath(o.aosp.path(b, path));
    var flags: std.ArrayList([]const u8) = .empty;
    flags.appendSlice(b.allocator, input.cppFlags(b)) catch @panic("oom");
    flags.append(b.allocator, "-Wno-inconsistent-missing-override") catch @panic("oom");
    mod.addCSourceFile(.{ .file = b.path("src/native/HostInputDispatcher.cpp"), .flags = flags.items });
    const object = b.addObject(.{ .name = "host-input-dispatcher-jni", .root_module = mod });
    b.step("host-input-dispatcher-jni", "Compile the hosted stock dispatcher policy and JNI adapter").dependOn(&object.step);
    return object;
}
