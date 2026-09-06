//! Test-only JNI access to the stock Java/native input-window metadata boundary.
const std = @import("std");
const graphics = @import("graphics.zig");

pub fn testLibrary(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: graphics.Generated, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, island: std.Build.LazyPath) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    graphics.configure(b, mod, aosp, gui);
    for ([_][]const u8{ "frameworks/base/core/jni", "libnativehelper/include_jni", "libnativehelper/include", "libnativehelper/include_platform" }) |path| mod.addIncludePath(aosp.path(b, path));
    mod.addCSourceFile(.{ .file = b.path("tests/native/InputHandleJniTest.cpp"), .flags = &.{ "-std=c++23", "-fno-exceptions", "-fno-rtti", "-Wno-invalid-specialization", "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}) } });
    mod.linkLibrary(binder);
    mod.linkLibrary(ndk);
    mod.addObjectFile(island);
    return b.addLibrary(.{ .name = "input_handle_jni_test", .linkage = .dynamic, .root_module = mod });
}
