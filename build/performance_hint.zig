//! Pinned performance-hint tests linked to the production client object.
const std = @import("std");

pub fn testSuite(b: *std.Build, o: @import("island.zig").Options, island: @import("island.zig").Result, broker: *std.Build.Step.Compile) void {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    for (island.binder_jni_includes) |path| mod.addIncludePath(path);
    for ([_][]const u8{ "frameworks/native/include/private", "system/libfmq/include", "system/libfmq/base", "external/googletest/googletest", "external/googletest/googletest/include", "external/googletest/googlemock", "external/googletest/googlemock/include" }) |path| mod.addIncludePath(o.aosp.path(b, path));
    // Pinned TestSupportObject compares a one-byte bitfield through an int union.
    // Match build/soong/cc/config/global.go CommonGlobalCflags: zero-init locals.
    const flags = &.{ "-std=gnu++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-UNDEBUG", "-ftrivial-auto-var-init=zero", "-D__INTRODUCED_IN(x)=", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    for ([_][]const u8{ "frameworks/base/native/android/tests/performance_hint/PerformanceHintNativeTest.cpp", "external/googletest/googletest/src/gtest-all.cc", "external/googletest/googletest/src/gtest_main.cc", "external/googletest/googlemock/src/gmock-all.cc" }) |path| mod.addCSourceFile(.{ .file = o.aosp.path(b, path), .flags = flags });
    mod.addObjectFile(island.hint_client);
    mod.addObjectFile(island.file);
    mod.linkLibrary(o.binder_cpp);
    mod.linkLibrary(o.binder_ndk);
    const exe = b.addExecutable(.{ .name = "performance-hint-test", .root_module = mod });
    const run = @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), o.binder_cpp, o.binder_ndk, broker, "performance-hint", "Run original performance-hint contracts (known pinned null-layer crash excluded)", .standalone, null, &.{island.file}, &.{});
    // android-16.0.0_r1 layersFromNativeSurfaces dereferences null SurfaceControls.
    // Its own TestSessionCreationWithNullLayers supplies one. Keep the original
    // client and test unchanged; this option reproduces that upstream failure.
    if (!(b.option(bool, "performance-hint-all-tests", "Include the pinned null-SurfaceControl crash regression") orelse false)) {
        run.addArg("--probe-arg=--gtest_filter=-PerformanceHintTest.TestSessionCreationWithNullLayers");
    }
}
