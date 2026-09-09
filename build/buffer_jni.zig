//! Test-only observations of stock Java buffers, native windows and EGL textures.
const std = @import("std");
const graphics = @import("graphics.zig");

pub fn testLibrary(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: graphics.Generated, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, island: std.Build.LazyPath) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    graphics.configure(b, mod, aosp, gui);
    for ([_][]const u8{ "frameworks/base/core/jni", "frameworks/base/core/jni/include", "libnativehelper/include_jni", "libnativehelper/include", "libnativehelper/include_platform", "frameworks/av/media/ndk/include" }) |path| mod.addIncludePath(aosp.path(b, path));
    for ([_][]const u8{ "tests/native/BufferJniTest.cpp", "tests/native/NdkImageReaderTest.cpp", "tests/native/NdkSurfaceControlTest.cpp", "tests/native/SurfaceTextureJniTest.cpp" }) |source| mod.addCSourceFile(.{ .file = b.path(source), .flags = &.{ "-std=c++23", "-UNDEBUG", "-fno-exceptions", "-fno-rtti", "-Wno-invalid-specialization", "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}) } });
    mod.linkSystemLibrary("EGL", .{});
    mod.linkSystemLibrary("GLESv2", .{});
    mod.linkLibrary(binder);
    mod.linkLibrary(ndk);
    mod.addObjectFile(island);
    return b.addLibrary(.{ .name = "buffer_jni_test", .linkage = .dynamic, .root_module = mod });
}
