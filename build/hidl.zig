//! Shared source selection for the pinned host HIDL runtime and protocols.
//! libgui's original hybrid interfaces and native MediaCodec use this runtime.
const std = @import("std");
const binder = @import("binder_cpp.zig");

pub const includes = [_][]const u8{
    "system/libhidl/base/include",                      "system/libhidl/transport/include",
    "system/libhwbinder/include",                       "system/libfmq/base",
    "system/libhidl/transport/token/1.0/utils/include", "system/libhidl/transport/allocator/1.0/utils/include",
    "system/libhidl/libhidlmemory/include",             "system/libhidl/libhidlmemory",
};

pub fn configure(b: *std.Build, mod: *std.Build.Module, aosp: std.Build.LazyPath, headers: []const std.Build.LazyPath) void {
    for (headers) |root| mod.addIncludePath(root);
    for (includes) |path| mod.addIncludePath(aosp.path(b, path));
}

pub fn build(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, headers: []const std.Build.LazyPath, sources: []const std.Build.LazyPath, binder_cpp: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    mod.addIncludePath(b.path("src/native/binder/include"));
    for (binder.includes) |path| mod.addIncludePath(aosp.path(b, path));
    configure(b, mod, aosp, headers);
    const uapi = b.addWriteFiles();
    _ = uapi.addCopyFile(aosp.path(b, "bionic/libc/kernel/uapi/linux/android/binder.h"), "linux/android/binder.h");
    mod.addIncludePath(uapi.getDirectory());
    // ENFORCE_VINTF_MANIFEST: libhidl's enforce_vintf_manifest product
    // variable, set by every full-Treble product.
    const flags = &.{ "-std=gnu++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DENFORCE_VINTF_MANIFEST", "-DARTZIG_HOST_THREAD_PRIORITY", "-DANDROID_UTILS_CALLSTACK_ENABLED=0", "-D__INTRODUCED_IN(x)=", "-Wno-deprecated-declarations", "-Wno-deprecated-enum-enum-conversion", "-Wno-deprecated-anon-enum-enum-conversion", "-Wno-nontrivial-memcall", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libhidl/base"), .files = &.{ "HidlSupport.cpp", "HidlInternal.cpp", "Status.cpp", "TaskRunner.cpp" }, .flags = flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libhidl/transport"), .files = &.{ "HidlBinderSupport.cpp", "HidlLazyUtils.cpp", "HidlPassthroughSupport.cpp", "HidlTransportSupport.cpp", "HidlTransportUtils.cpp", "LegacySupport.cpp", "ServiceManagement.cpp", "Static.cpp", "token/1.0/utils/HybridInterface.cpp", "allocator/1.0/utils/FrameworkUtils.cpp" }, .flags = flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libhwbinder"), .files = &.{ "Binder.cpp", "BpHwBinder.cpp", "BufferedTextOutput.cpp", "Debug.cpp", "IInterface.cpp", "IPCThreadState.cpp", "Parcel.cpp", "ProcessState.cpp", "Static.cpp", "TextOutput.cpp", "Utils.cpp" }, .flags = flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libhidl/libhidlmemory"), .files = &.{ "mapping.cpp", "HidlMemoryToken.cpp" }, .flags = flags });
    for (sources) |source| mod.addCSourceFile(.{ .file = source, .flags = flags });
    // Standalone libgui consumers also need the original C++ logging helpers.
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libbase"), .files = &.{ "logging.cpp", "threads.cpp", "file.cpp" }, .flags = flags });
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/core/libutils"), .files = &.{ "SystemClock.cpp", "misc.cpp" }, .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, "system/core/libcutils/multiuser.cpp"), .flags = flags });
    mod.linkLibrary(binder_cpp);
    const library = b.addLibrary(.{ .name = "hidl_host", .root_module = mod });
    b.step("hidl", "Build pinned HIDL runtime shared by libgui and media clients").dependOn(&library.step);
    return library;
}
