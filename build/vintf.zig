//! Original libvintf conformance tests using the production object files.
const std = @import("std");

pub fn testSuite(b: *std.Build, o: @import("island.zig").Options, island: @import("island.zig").Result, broker: *std.Build.Step.Compile) void {
    const mod = b.createModule(.{ .target = o.target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    for (island.binder_jni_includes) |path| mod.addIncludePath(path);
    for (@import("vintf_sources.zig").includes) |path| mod.addIncludePath(o.aosp.path(b, path));
    for ([_][]const u8{ "external/googletest/googletest", "external/googletest/googletest/include", "external/googletest/googlemock", "external/googletest/googlemock/include" }) |path| mod.addIncludePath(o.aosp.path(b, path));
    const flags = &.{ "-std=gnu++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-UNDEBUG", "-DLIBVINTF_TARGET", "-D__INTRODUCED_IN(x)=", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "system/libvintf/test/LibVintfTest.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "external/googletest/googletest/src/gtest-all.cc"), .flags = flags });
    mod.addCSourceFile(.{ .file = o.aosp.path(b, "external/googletest/googlemock/src/gmock-all.cc"), .flags = flags });
    // Keep stream constructors, moves and destructors in the test ELF's C++
    // runtime. JNI exercises these same objects inside the platform library.
    for (island.vintf_objects) |object| mod.addObjectFile(object);
    mod.addObjectFile(island.file);
    mod.linkLibrary(o.binder_cpp);
    mod.linkLibrary(o.binder_ndk);
    mod.linkSystemLibrary("libselinux", .{ .use_pkg_config = .force });
    mod.linkSystemLibrary("zlib", .{ .use_pkg_config = .force });
    const exe = b.addExecutable(.{ .name = "vintf-test", .root_module = mod });
    _ = @import("surfaceflinger.zig").runBinaryTest(b, exe.getEmittedBin(), o.binder_cpp, o.binder_ndk, broker, "vintf", "Run original VINTF XML, manifest, matrix and compatibility contracts", .standalone, null, &.{island.file}, &.{});
}
