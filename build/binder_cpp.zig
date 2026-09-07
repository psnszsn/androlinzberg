//! Source-compatible hosted libbinder. One copy of the AOSP reference/string
//! support is shared by standalone clients and the native island.
const std = @import("std");
const gen = @import("gen/island_sources.zig");
pub fn sharedUtils(file: []const u8) bool {
    return std.mem.startsWith(u8, file, "binder/");
}
pub const includes = [_][]const u8{
    "frameworks/native/libs/binder/include",
    "frameworks/native/libs/binder/ndk/include_ndk",
    "frameworks/native/libs/binder/ndk/include_platform",
    "system/core/libutils/include",
    "system/core/libutils/binder/include",
    "system/core/libcutils/include",
    "system/logging/liblog/include",
    "system/libbase/include",
    "system/core/libsystem/include",
};
pub fn build(b: *std.Build, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode, aosp: std.Build.LazyPath, ndk: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const lib = b.addLibrary(.{ .name = "binder", .linkage = .dynamic, .root_module = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true, .link_libcpp = true }) });
    lib.root_module.addIncludePath(b.path("src/native/binder/include"));
    for (includes) |path| lib.root_module.addIncludePath(aosp.path(b, path));
    lib.root_module.addIncludePath(aosp.path(b, "system/logging/liblog"));
    const flags = &.{ "-std=gnu++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-DNDEBUG", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DLIBLOG_LOG_TAG=1006", "-DANDROID_DEBUGGABLE=0", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    lib.root_module.addCSourceFiles(.{ .files = &.{ "src/native/binder/Binder.cpp", "src/native/binder/Parcel.cpp" }, .flags = flags });
    lib.root_module.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/libs/binder"), .files = &.{ "IInterface.cpp", "Status.cpp", "IResultReceiver.cpp", "IMemory.cpp", "MemoryBase.cpp", "MemoryHeapBase.cpp", "MemoryDealer.cpp" }, .flags = flags });
    for (gen.libutils_srcs) |file| if (sharedUtils(file)) {
        lib.root_module.addCSourceFile(.{ .file = aosp.path(b, b.fmt("system/core/libutils/{s}", .{file})), .flags = flags });
    };
    lib.root_module.addCSourceFiles(.{ .root = aosp.path(b, "system/logging/liblog"), .files = &gen.liblog_srcs, .flags = flags });
    lib.root_module.addCSourceFile(.{ .file = aosp.path(b, "system/core/libcutils/native_handle.cpp"), .flags = flags });
    // Share the original host ashmem backing with the native island.
    lib.root_module.addCSourceFile(.{ .file = aosp.path(b, "system/core/libcutils/ashmem-host.cpp"), .flags = flags });
    // Use the declared shared-object input without Zig adding its cache
    // directory to the installed library's RUNPATH. Runtime lookup is sibling-only.
    lib.root_module.addObjectFile(ndk.getEmittedBin());
    lib.root_module.addRPathSpecial("$ORIGIN");
    return lib;
}
