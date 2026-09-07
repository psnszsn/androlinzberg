//! libandroid_platform.so — the native island: AOSP hwui (host variant) +
//! graphics/core JNI + libbase/liblog/libcutils/libutils + androidfw + minikin
//! + our shims, built with zig c++ and linked against our static Skia
//! (build/skia.zig). Replaces tools/build-native.sh; the source lists live in
//! build/gen/island_sources.zig.
const std = @import("std");
const gen = @import("gen/island_sources.zig");

pub const Options = struct {
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    skia: *std.Build.Step.Compile,
    /// libbinder_ndk.so — the hwui parcel JNI (android 16) calls its AParcel ABI.
    binder_ndk: *std.Build.Step.Compile,
    binder_cpp: *std.Build.Step.Compile,
    perfetto: @import("perfetto.zig").Result,
    art: *std.Build.Module,
    aidl_header_roots: []const std.Build.LazyPath,
    aidl_source_roots: []const std.Build.LazyPath,
    aconfig_header_roots: []const std.Build.LazyPath,
    incremental_sysprop_headers: std.Build.LazyPath,
    incremental_sysprop_source: std.Build.LazyPath,
    host_include_roots: []const std.Build.LazyPath,
    host_library_roots: []const std.Build.LazyPath,
    host_libraries: []const []const u8,
    host_link_flags: []const []const u8,
    aosp: std.Build.LazyPath,
};

pub const Result = struct {
    file: std.Build.LazyPath,
    step: *std.Build.Step,
};

const Roots = struct {
    app: std.Build.LazyPath,
    aosp: std.Build.LazyPath,

    fn resolve(self: Roots, b: *std.Build, value: []const u8) std.Build.LazyPath {
        const mappings = .{
            .{ "$MINIKIN", self.aosp.path(b, "frameworks/minikin") },
            .{ "$APP", self.app },
            .{ "$AZ", self.aosp },
            .{ "$FB", self.aosp.path(b, "frameworks/base") },
            .{ "$HW", self.aosp.path(b, "frameworks/base/libs/hwui") },
            .{ "$V", self.aosp },
            .{ "$SKS", self.aosp.path(b, "external/skia") },
        };
        inline for (mappings) |mapping| {
            if (std.mem.startsWith(u8, value, mapping[0])) {
                var relative = value[mapping[0].len..];
                while (std.mem.startsWith(u8, relative, "/")) relative = relative[1..];
                return if (relative.len == 0) mapping[1] else mapping[1].path(b, relative);
            }
        }
        std.debug.panic("unknown island path root: {s}", .{value});
    }
};

const minikin_srcs = [_][]const u8{
    "libs/minikin/BidiUtils.cpp",          "libs/minikin/CmapCoverage.cpp",    "libs/minikin/Debug.cpp",
    "libs/minikin/Emoji.cpp",              "libs/minikin/FontCollection.cpp",  "libs/minikin/Font.cpp",
    "libs/minikin/FontFakery.cpp",         "libs/minikin/FontFamily.cpp",      "libs/minikin/FontFeatureUtils.cpp",
    "libs/minikin/FontFileParser.cpp",     "libs/minikin/FontUtils.cpp",       "libs/minikin/GraphemeBreak.cpp",
    "libs/minikin/GreedyLineBreaker.cpp",  "libs/minikin/Hyphenator.cpp",      "libs/minikin/HyphenatorMap.cpp",
    "libs/minikin/LayoutCore.cpp",         "libs/minikin/Layout.cpp",          "libs/minikin/LayoutUtils.cpp",
    "libs/minikin/LineBreaker.cpp",        "libs/minikin/LineBreakerUtil.cpp", "libs/minikin/Locale.cpp",
    "libs/minikin/LocaleListCache.cpp",    "libs/minikin/MeasuredText.cpp",    "libs/minikin/Measurement.cpp",
    "libs/minikin/MinikinFontFactory.cpp", "libs/minikin/MinikinInternal.cpp", "libs/minikin/OptimalLineBreaker.cpp",
    "libs/minikin/ScriptUtils.cpp",        "libs/minikin/SparseBitSet.cpp",    "libs/minikin/SystemFonts.cpp",
    "libs/minikin/U16StringPiece.cpp",     "libs/minikin/WordBreaker.cpp",
};

// skia TUs the framework needs that are not part of //:skia (tools/)
const skia_extra_srcs = [_][]const u8{"tools/SkSharingProc.cpp"};

const our_srcs = [_][]const u8{
    "src/native/ArtzigGpuPipeline.cpp",     "src/native/HardwareBufferRender.cpp",
    "src/native/HwuiRenderProxyBridge.cpp", "src/native/AospJniBridge.cpp",
    "src/native/AospParcelAbi.cpp",
};

pub fn build(b: *std.Build, o: Options) Result {
    const r = Roots{ .app = b.path(""), .aosp = o.aosp };

    const abi_object = b.addObject(.{
        .name = "android-platform-abi",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/native/platform_abi.zig"),
            .target = o.target,
            .optimize = o.optimize,
            .link_libc = true,
            .pic = true,
            .imports = &.{.{ .name = "art", .module = o.art }},
        }),
    });

    // common flags
    var cxx = std.ArrayList([]const u8).empty;
    cxx.appendSlice(b.allocator, &.{
        "-std=gnu++23",            "-fno-exceptions",             "-fno-rtti", "-w",                                          "-D_GNU_SOURCE", "-DNDEBUG",
        // zig enables UBSan for C/C++ in Debug; AOSP code trips it (null memcpy of 0 bytes)
        "-fno-sanitize=undefined",
        // AOSP's half type intentionally specializes this standard trait.
        "-Wno-invalid-specialization",
        // Keep the LazyPath include root below as the dependency identity. Zig's
        // C cache currently requires the force-included header itself to be an
        // absolute compiler argument when many source roots share one module.
        "-include",  b.pathResolve(&.{"src/native/azk_prelude.h"}),
    }) catch @panic("oom");
    var cc = std.ArrayList([]const u8).empty;
    cc.appendSlice(b.allocator, &.{ "-std=gnu17", "-w", "-D_GNU_SOURCE", "-DNDEBUG", "-fno-sanitize=undefined" }) catch @panic("oom");
    for (gen.defines) |d| {
        cxx.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");
        cc.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");
    }

    const binder_inc = o.aosp.path(b, "frameworks/native/libs/binder/include");
    const binder_ndk_includes = [_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/native/libs/binder/ndk/include_ndk"),
        o.aosp.path(b, "frameworks/native/libs/binder/ndk/include_platform"),
        o.aosp.path(b, "frameworks/native/libs/binder/ndk/include_cpp"),
        binder_inc,
    };
    // Isolate the pinned ashmem UAPI header from the rest of bionic's libc headers.
    const ashmem_headers = b.addWriteFiles();
    _ = ashmem_headers.addCopyFile(o.aosp.path(b, "bionic/libc/kernel/uapi/linux/ashmem.h"), "linux/ashmem.h");
    // Every HWUI translation unit must see the real Fence layout. Preserve the
    // other hostgraphics headers while overriding only ui/Fence.h.
    _ = ashmem_headers.addCopyFile(o.aosp.path(b, "frameworks/native/libs/ui/include/ui/Fence.h"), "ui/Fence.h");
    var common_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    common_includes.append(b.allocator, ashmem_headers.getDirectory()) catch @panic("oom");
    common_includes.append(b.allocator, b.path("src/native/binder/include")) catch @panic("oom");
    for (gen.include_dirs) |d| common_includes.append(b.allocator, r.resolve(b, d)) catch @panic("oom");
    common_includes.appendSlice(b.allocator, &binder_ndk_includes) catch @panic("oom");
    common_includes.append(b.allocator, b.path("src/native")) catch @panic("oom");
    common_includes.append(b.allocator, b.path("src/native/input-compat")) catch @panic("oom");
    common_includes.appendSlice(b.allocator, o.aidl_header_roots) catch @panic("oom");
    common_includes.appendSlice(b.allocator, o.aconfig_header_roots) catch @panic("oom");
    common_includes.appendSlice(b.allocator, &.{
        o.aosp.path(b, "frameworks/av/camera/include"),
        o.aosp.path(b, "system/media/camera/include"),
        o.aosp.path(b, "system/media/private/camera/include"),
        o.incremental_sysprop_headers,
        o.aosp.path(b, "system/incremental_delivery/incfs/include"),
        o.aosp.path(b, "system/incremental_delivery/incfs/kernel-headers"),
        o.aosp.path(b, "external/selinux/libselinux/include"),
        o.aosp.path(b, "system/sepolicy/compat/libgenfslabelsversion/include"),
    }) catch @panic("oom");

    var objects: std.ArrayList(std.Build.LazyPath) = .empty;

    const G = struct {
        b: *std.Build,
        objects: *std.ArrayList(std.Build.LazyPath),
        target: std.Build.ResolvedTarget,
        optimize: std.builtin.OptimizeMode,
        common_includes: []const std.Build.LazyPath,
        host_includes: []const std.Build.LazyPath,
        cxx: []const []const u8,
        cc: []const []const u8,
        next_index: usize = 0,

        fn addObject(
            self: *@This(),
            root: std.Build.LazyPath,
            files: []const []const u8,
            includes: []const std.Build.LazyPath,
            flags: []const []const u8,
            language: std.Build.Module.CSourceLanguage,
        ) void {
            for (files) |file| {
                const name = self.b.fmt("android-platform-tu-{d}", .{self.next_index});
                self.next_index += 1;
                const compiler = if (language == .cpp) "c++" else "cc";
                const compile = self.b.addSystemCommand(&.{ self.b.graph.zig_exe, compiler, "-c", "-fPIC" });
                compile.step.name = self.b.fmt("compile {s}", .{file});
                compile.addArgs(&.{
                    "-target",
                    self.target.query.zigTriple(self.b.allocator) catch @panic("oom"),
                    switch (self.optimize) {
                        .Debug => "-O0",
                        .ReleaseSafe => "-O2",
                        .ReleaseFast => "-O3",
                        .ReleaseSmall => "-Os",
                    },
                });
                compile.addArgs(flags);
                for (self.common_includes) |include| compile.addPrefixedDirectoryArg("-I", include);
                for (self.host_includes) |include| compile.addPrefixedDirectoryArg("-isystem", include);
                for (includes) |include| compile.addPrefixedDirectoryArg("-I", include);
                compile.addFileArg(root.path(self.b, file));
                compile.addArg("-o");
                const object = compile.addOutputFileArg(self.b.fmt("{s}.o", .{name}));
                self.objects.append(self.b.allocator, object) catch @panic("oom");
            }
        }

        fn add(
            self: *@This(),
            root: std.Build.LazyPath,
            files: []const []const u8,
            includes: []const std.Build.LazyPath,
            extra: []const []const u8,
        ) void {
            var cpp: std.ArrayList([]const u8) = .empty;
            var c: std.ArrayList([]const u8) = .empty;
            for (files) |f| {
                if (std.mem.endsWith(u8, f, ".c")) c.append(self.b.allocator, f) catch @panic("oom") else cpp.append(self.b.allocator, f) catch @panic("oom");
            }
            if (cpp.items.len > 0) {
                var flags = std.ArrayList([]const u8).empty;
                flags.appendSlice(self.b.allocator, self.cxx) catch @panic("oom");
                flags.appendSlice(self.b.allocator, extra) catch @panic("oom");
                self.addObject(root, cpp.items, includes, flags.items, .cpp);
            }
            if (c.items.len > 0) {
                var flags = std.ArrayList([]const u8).empty;
                flags.appendSlice(self.b.allocator, self.cc) catch @panic("oom");
                flags.appendSlice(self.b.allocator, extra) catch @panic("oom");
                self.addObject(root, c.items, includes, flags.items, .c);
            }
        }
    };
    var g = G{
        .b = b,
        .objects = &objects,
        .target = o.target,
        .optimize = o.optimize,
        .common_includes = common_includes.items,
        .host_includes = o.host_include_roots,
        .cxx = cxx.items,
        .cc = cc.items,
    };
    // NDK binder C headers (AParcel): the ABI itself is our zig libbinder_ndk,
    // linked below; installed at lib/art, reached from bin via the rpath.
    const zip_inc = o.aosp.path(b, "system/libziparchive/include");
    const zip_incfs_inc = o.aosp.path(b, "system/libziparchive/incfs_support/include");
    // Generated AIDL C++ headers are exact LazyPath roots shared by each group.
    const aidl_inc = "-DARTZIG_AIDL_CPP_INCLUDED";
    const input_compat_inc = b.path("src/native/input-compat");
    const apex_inc = o.aosp.path(b, "frameworks/base/libs/hwui/apex/include");
    const hw_jni_inc = o.aosp.path(b, "frameworks/base/libs/hwui/jni");
    const fn_inc = o.aosp.path(b, "frameworks/native/include");

    g.add(o.aosp.path(b, "packages/modules/Connectivity/staticlibs/native/bpfmapjni"), &.{"com_android_net_module_util_BpfMap.cpp"}, &.{
        o.aosp.path(b, "packages/modules/Connectivity/bpf/syscall_wrappers/include"),
        o.aosp.path(b, "packages/modules/Connectivity/bpf/headers/include"),
        // KernelUtils uses bionic's unused annotation; the host libc has no spelling for it.
    }, &.{"-D__unused=__attribute__((__unused__))"});
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{"android_media_MediaHTTPConnection.cpp"}, &.{ o.aosp.path(b, "frameworks/av/media/module/foundation/include"), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{});
    // Android's host-capable camera metadata library and C++ value containers.
    g.add(o.aosp.path(b, "frameworks/av/camera"), &.{ "CameraMetadata.cpp", "VendorTagDescriptor.cpp" }, &.{}, &.{});
    g.add(o.aosp.path(b, "system/media/camera/src"), &.{"camera_metadata.c"}, &.{}, &.{});
    g.add(o.aosp.path(b, "system/core/libusbhost"), &.{ "usbhost.c", "usbhost_jni.cpp" }, &.{}, &.{});
    g.add(o.aosp.path(b, "system/libbase"), &gen.libbase_srcs, &.{o.aosp.path(b, "system/libbase")}, &.{});
    for (gen.libcutils_srcs) |file| {
        if (!std.mem.eql(u8, file, "native_handle.cpp") and !std.mem.eql(u8, file, "ashmem-host.cpp")) g.add(o.aosp.path(b, "system/core/libcutils"), &.{file}, &.{o.aosp.path(b, "system/core/libcutils")}, &.{});
    }
    for (gen.libutils_srcs) |file| {
        if (!@import("binder_cpp.zig").sharedUtils(file)) g.add(o.aosp.path(b, "system/core/libutils"), &.{file}, &.{o.aosp.path(b, "system/core/libutils")}, &.{"-DANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION"});
    }
    g.add(o.aosp.path(b, "external/fmtlib"), &gen.fmtlib_srcs, &.{}, &.{});
    g.add(o.aosp.path(b, "frameworks/minikin"), &minikin_srcs, &.{ o.aosp.path(b, "frameworks/minikin/libs"), o.aosp.path(b, "frameworks/minikin/libs/minikin") }, &.{});
    g.add(o.aosp.path(b, "external/skia"), &skia_extra_srcs, &.{}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/hostgraphics"), &gen.hostgraphics_srcs, &.{}, &.{});
    g.add(o.aosp.path(b, "frameworks/native/libs/ui"), &.{"Fence.cpp"}, &.{o.aosp.path(b, "system/core/libsync/include")}, &.{});
    g.add(o.aosp.path(b, "system/core/libsync"), &.{"sync.c"}, &.{o.aosp.path(b, "system/core/libsync/include")}, &.{});
    g.add(b.path("tests/native"), &.{"FenceTest.cpp"}, &.{}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_hardware_SyncFence.cpp", "android_opengl_EGL15.cpp" }, &.{o.aosp.path(b, "frameworks/base/core/jni/include")}, &.{});
    const tracing_includes = &.{
        o.aosp.path(b, "frameworks/native/libs/tracing_perfetto/include"),
        o.aosp.path(b, "external/perfetto/include"),
    };
    g.add(o.aosp.path(b, "frameworks/native/libs/tracing_perfetto"), &.{ "tracing_perfetto.cpp", "tracing_perfetto_internal.cpp", "tracing_sdk.cpp" }, tracing_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_os_PerfettoTrace.cpp", "android_os_PerfettoTrackEventExtra.cpp" }, tracing_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/native/libs/ui"), &gen.fn_ui_srcs, &.{o.aosp.path(b, "frameworks/native/libs/ui/include_private")}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/androidfw"), &gen.androidfw_srcs, &.{ o.aosp.path(b, "frameworks/base/libs/androidfw/include_pathutils"), zip_inc, zip_incfs_inc, binder_inc }, &.{});
    g.add(o.aosp.path(b, "system/libziparchive"), &gen.ziparchive_srcs, &.{ zip_inc, zip_incfs_inc, o.aosp.path(b, "system/libziparchive") }, &.{ "-DINCFS_SUPPORT_DISABLED=1", "-DZIPARCHIVE_DISABLE_CALLBACK_API=1", "-DZLIB_CONST", "-D_FILE_OFFSET_BITS=64" });
    g.add(o.aosp.path(b, "system/incremental_delivery/incfs/util"), &.{"map_ptr.cpp"}, &.{}, &.{});
    g.add(o.aosp.path(b, "system/incremental_delivery/incfs"), &.{ "incfs_ndk.c", "incfs.cpp", "MountRegistry.cpp", "path.cpp" }, &.{}, &.{"-D_FILE_OFFSET_BITS=64"});
    g.add(o.incremental_sysprop_source.dirname(), &.{"IncrementalProperties.sysprop.cpp"}, &.{}, &.{});
    for (o.aidl_source_roots, @import("generator_sources.zig").cpp_aidl) |root, entry| {
        if (entry.output) |output| g.add(root, &.{output}, &.{}, &.{});
    }
    g.add(o.aosp.path(b, "frameworks/native/libs/input"), &gen.input_srcs, &.{ fn_inc, o.aosp.path(b, "frameworks/native/libs/input"), input_compat_inc, binder_inc }, &.{aidl_inc});
    g.add(o.aosp.path(b, "frameworks/native/libs/attestation"), &gen.attestation_srcs, &.{fn_inc}, &.{});
    // Isolate the pinned Android loader declarations from Bionic's libc headers.
    const graphics_headers = b.addWriteFiles();
    _ = graphics_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/android/dlext.h"), "android/dlext.h");
    const graphics_includes = &.{ o.aosp.path(b, "frameworks/native/libs/graphicsenv/include"), o.aosp.path(b, "art/libnativeloader/include"), graphics_headers.getDirectory(), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") };
    g.add(o.aosp.path(b, "frameworks/native/libs/graphicsenv"), &.{ "FeatureOverrides.cpp", "GpuStatsInfo.cpp", "GraphicsEnv.cpp", "IGpuService.cpp" }, graphics_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_GraphicsEnvironment.cpp"}, graphics_includes, &.{ "-include", b.pathResolve(&.{"src/native/GraphicsEnvironmentBackend.h"}) });
    g.add(b.path("src/native"), &.{"GraphicsEnvironmentBackend.cpp"}, graphics_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &gen.core_jni_srcs, &.{ b.path("src/native/sqlite-compat"), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), zip_inc, apex_inc, input_compat_inc, binder_inc }, &.{aidl_inc});
    // These pinned files rely on Bionic/transitive includes for all_of, ioctl
    // and EXT4_SUPER_MAGIC. Declare those headers explicitly for host libc++.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "com_android_internal_content_FileSystemUtils.cpp", "com_android_internal_content_NativeLibraryHelper.cpp" }, &.{ zip_inc, o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{ "-include", "algorithm", "-include", "sys/ioctl.h", "-include", "linux/magic.h" });
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &gen.hwui_srcs, &.{ apex_inc, b.path("src/native") }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{ "apex/android_matrix.cpp", "apex/android_canvas.cpp" }, &.{ apex_inc, hw_jni_inc }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &gen.jni_srcs, &.{hw_jni_inc}, &.{});
    g.add(o.aosp.path(b, "libnativehelper"), &gen.nativehelper_srcs, &.{}, &.{ "-U_GNU_SOURCE", "-D_POSIX_C_SOURCE=200809L", "-D_DEFAULT_SOURCE" });
    g.add(b.path(""), &our_srcs, &.{ hw_jni_inc, o.aosp.path(b, "frameworks/base/core/jni/include"), binder_inc, b.path("src/platform"), o.aosp.path(b, "system/media/audio/include") }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"com_google_android_gles_jni_GLImpl.cpp"}, &.{o.aosp.path(b, "frameworks/base/core/jni/include")}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_media_PublicFormatUtils.cpp"}, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{});

    // Zig's bundled LLD rejects valid weak-hidden libc++ COMDAT relocations in
    // Skia before resolving them locally. Keep Zig's compiler and libc++, but
    // delegate this one final ELF link to GNU bfd.
    const link = b.addSystemCommand(&.{
        b.graph.zig_exe,                      "c++",                           "-fuse-ld=bfd", "-shared", "-Wl,-z,defs",
        "-Wl,-soname,libandroid_platform.so", "-Wl,-rpath,$ORIGIN/../lib/art",
    });
    link.step.name = "link libandroid_platform.so";
    link.step.max_rss = 16 * 1024 * 1024 * 1024;
    // The Nix development shell injects every build input, plus outputs/out,
    // through NIX_LDFLAGS. Link only the typed pkg-config roots above so this
    // product cannot acquire an undeclared build-tree RUNPATH.
    link.removeEnvironmentVariable("NIX_LDFLAGS");
    link.addArtifactArg(abi_object);
    for (objects.items) |object| link.addFileArg(object);
    link.addFileArg(o.skia.getEmittedBin());
    link.addFileArg(o.perfetto.sdk.getEmittedBin());
    link.addFileArg(o.perfetto.base.getEmittedBin());
    link.addFileArg(o.binder_ndk.getEmittedBin());
    link.addFileArg(o.binder_cpp.getEmittedBin());
    for (o.host_library_roots) |root| {
        link.addPrefixedDirectoryArg("-L", root);
        link.addPrefixedDirectoryArg("-Wl,-rpath,", root);
    }
    for (o.host_libraries) |library| link.addArg(b.fmt("-l{s}", .{library}));
    link.addArgs(o.host_link_flags);
    link.addArgs(&.{ "-ldl", "-lpthread", "-lm", "-o" });
    const output = link.addOutputFileArg("libandroid_platform.so");
    return .{ .file = output, .step = &link.step };
}
