//! libandroid_platform.so — the native island: original AOSP HWUI +
//! graphics/core JNI + libbase/liblog/libcutils/libutils + androidfw + minikin
//! + our shims, built with zig c++ and linked against our static Skia
//! (build/skia.zig). Replaces tools/build-native.sh; the source lists live in
//! build/gen/island_sources.zig.
const std = @import("std");
const gen = @import("gen/island_sources.zig");

pub const Options = struct {
    resolver: std.Build.LazyPath,
    android_account_headers: std.Build.LazyPath,
    cppstream_headers: std.Build.LazyPath,
    input_dispatcher_headers: std.Build.LazyPath,
    xsd: []const @import("generators.zig").CppXsd,
    media_generated: @import("graphics.zig").Generated,
    hwui_stats: @import("graphics.zig").Generated,
    surfaceflinger_generated: @import("graphics.zig").Generated,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    skia: *std.Build.Step.Compile,
    /// libbinder_ndk.so — the hwui parcel JNI (android 16) calls its AParcel ABI.
    binder_ndk: *std.Build.Step.Compile,
    binder_cpp: *std.Build.Step.Compile,
    hidl: *std.Build.Step.Compile,
    ui: *std.Build.Step.Compile,
    gui: *std.Build.Step.Compile,
    input: @import("input.zig").Library,
    input_dispatcher: *std.Build.Step.Compile,
    input_dispatcher_jni: *std.Build.Step.Compile,
    /// Stock inputflinger (InputManager and its stages), its external closure,
    /// its generated headers and the Rust staticlib that also carries the
    /// input crate's verifier exports.
    inputflinger: *std.Build.Step.Compile,
    inputflinger_external: *std.Build.Step.Compile,
    inputflinger_headers: std.Build.LazyPath,
    inputflinger_rust: std.Build.LazyPath,
    perfetto: @import("perfetto.zig").Result,
    protobuf: *std.Build.Step.Compile,
    input_headers: std.Build.LazyPath,
    graphics_headers: std.Build.LazyPath,
    art: *std.Build.Module,
    host_paths_library: std.Build.LazyPath,
    host_paths_headers: std.Build.LazyPath,
    aidl_header_roots: []const std.Build.LazyPath,
    aidl_source_roots: []const std.Build.LazyPath,
    hidl_header_roots: []const std.Build.LazyPath,
    aconfig_header_roots: []const std.Build.LazyPath,
    incremental_sysprop_headers: std.Build.LazyPath,
    incremental_sysprop_source: std.Build.LazyPath,
    host_include_roots: []const std.Build.LazyPath,
    host_library_roots: []const std.Build.LazyPath,
    host_libraries: []const []const u8,
    host_link_flags: []const []const u8,
    aosp: std.Build.LazyPath,
    /// Optional stock codec closure; enabled by its native acceptance executable
    /// until the component and resource-manager services are ready for apps.
    codec2: @import("codec2.zig").Libraries,
};

pub const Result = struct {
    native_compiles: []const *std.Build.Step.Run,
    pdfclient: std.Build.LazyPath,
    vintf_objects: []const std.Build.LazyPath,
    hint_client: std.Build.LazyPath,
    binder_jni_sources: []const std.Build.LazyPath,
    binder_jni_includes: []const std.Build.LazyPath,
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

// Host media.audio_flinger / media.audio_policy services behind stock libaudioclient.
const audio_host_interfaces = [_][]const u8{
    "android/media/IAudioFlingerService.aidl",
    "android/media/IAudioPolicyService.aidl",
    "android/media/IAudioTrack.aidl",
};
const audio_host_srcs = [_][]const u8{
    "AAudioSmoke.cpp", "AudioServiceJni.cpp", "HostAudioFlinger.cpp", "HostAudioPolicyService.cpp", "HostAudioTrack.cpp", "azk_pipewire.c",
};
const our_srcs = [_][]const u8{
    "src/native/AospJniBridge.cpp",               "src/native/EglWindow.cpp",
    "src/native/BinderJniBridge.cpp",             "src/native/HostLooper.cpp",
    "src/native/binder/ServiceNotifications.cpp",
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
            .imports = &.{ .{ .name = "art", .module = o.art }, .{ .name = "graphics_types", .module = b.createModule(.{ .root_source_file = b.path("src/platform/graphics_types.zig") }) } },
        }),
    });

    // common flags
    var cxx = std.ArrayList([]const u8).empty;
    cxx.appendSlice(b.allocator, &.{
        "-std=gnu++23",            "-fno-exceptions",             "-fno-rtti", "-w",                                          "-D_GNU_SOURCE", "-DNDEBUG",
        // zig enables UBSan for C/C++ in Debug; AOSP code trips it (null memcpy of 0 bytes)
        "-fno-sanitize=undefined",
        // Soong's device default since Android 13: platform code relies on
        // zero-filled locals (e.g. libaudioclient's AidlConversion ORs flags
        // into an uninitialized audio_port_config, so AudioManager.getDevices
        // read a stale gain index on some hosts).
        "-ftrivial-auto-var-init=zero",
        // AOSP's half type intentionally specializes this standard trait.
        "-Wno-invalid-specialization",
        // Keep the LazyPath include root below as the dependency identity. Zig's
        // C cache currently requires the force-included header itself to be an
        // absolute compiler argument when many source roots share one module.
        "-include",  b.pathResolve(&.{"src/native/azk_prelude.h"}), "-include",      b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}),
    }) catch @panic("oom");
    var cc = std.ArrayList([]const u8).empty;
    cc.appendSlice(b.allocator, &.{ "-std=gnu17", "-w", "-D_GNU_SOURCE", "-DNDEBUG", "-fno-sanitize=undefined", "-ftrivial-auto-var-init=zero" }) catch @panic("oom");
    for (gen.defines) |d| {
        cxx.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");
        cc.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");
    }
    // HWUI and its JNI must see the same Skia feature-dependent declarations
    // and layouts as the library, including the Vulkan backend closure.
    for (@import("gen/skia_sources.zig").defines) |define|
        cxx.append(b.allocator, b.fmt("-D{s}", .{define})) catch @panic("oom");

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
    _ = ashmem_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/bits/get_device_api_level_inlines.h"), "bits/get_device_api_level_inlines.h");
    // Reuse the original out-of-line C API with the existing property export.
    // Its C-compatible source avoids glibc's different C++ atoi exception spec;
    // isolate this header instead of replacing the host's libc include tree.
    abi_object.root_module.addIncludePath(ashmem_headers.getDirectory());
    abi_object.root_module.addCSourceFile(.{
        .file = o.aosp.path(b, "bionic/libc/bionic/get_device_api_level.cpp"),
        .language = .c,
        .flags = &.{ "-std=c11", "-D_Nonnull=", "-fvisibility=default" },
    });
    // libhwbinder needs Android's scheduling/security extensions to the Binder UAPI.
    _ = ashmem_headers.addCopyFile(o.aosp.path(b, "bionic/libc/kernel/uapi/linux/android/binder.h"), "linux/android/binder.h");
    // Every HWUI translation unit must see the real Fence layout. Preserve the
    // other hostgraphics headers while overriding only ui/Fence.h.
    _ = ashmem_headers.addCopyFile(o.aosp.path(b, "frameworks/native/libs/ui/include/ui/Fence.h"), "ui/Fence.h");
    var common_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    common_includes.append(b.allocator, @import("skia.zig").config(b, o.aosp.path(b, "external/skia"))) catch @panic("oom");
    common_includes.appendSlice(b.allocator, &.{
        o.hwui_stats.headers,                                     o.surfaceflinger_generated.headers,
        o.aosp.path(b, "frameworks/native/libs/shaders/include"), o.aosp.path(b, "frameworks/native/libs/tonemap/include"),
        o.aosp.path(b, "system/core/libstats/bootstrap/include"),
    }) catch @panic("oom");
    common_includes.append(b.allocator, o.perfetto.headers) catch @panic("oom");
    common_includes.append(b.allocator, ashmem_headers.getDirectory()) catch @panic("oom");
    // The native Choreographer FD needs Android's Looper-driven ThreadBase.
    // Select this stock header alone; the remaining host HWUI platform headers
    // still provide the GBM context boundary instead of Android EGL/Vulkan.
    const thread_headers = b.addWriteFiles();
    _ = thread_headers.addCopyFile(o.aosp.path(b, "frameworks/base/libs/hwui/platform/android/thread/ThreadBase.h"), "thread/ThreadBase.h");
    common_includes.append(b.allocator, thread_headers.getDirectory()) catch @panic("oom");
    common_includes.append(b.allocator, b.path("src/native/binder/include")) catch @panic("oom");
    // Use real libui/libgui types throughout the island. Hostgraphics' abbreviated
    // Surface and producer classes must not shadow the stock JNI object layout.
    common_includes.append(b.allocator, o.aosp.path(b, "frameworks/native/libs/ui/include")) catch @panic("oom");
    common_includes.append(b.allocator, o.aosp.path(b, "frameworks/native/libs/gui/include")) catch @panic("oom");
    common_includes.append(b.allocator, o.aosp.path(b, "frameworks/native/libs/nativedisplay/include-private")) catch @panic("oom");
    common_includes.append(b.allocator, o.aosp.path(b, "frameworks/native/libs/nativedisplay/include")) catch @panic("oom");
    common_includes.append(b.allocator, o.graphics_headers) catch @panic("oom");
    for (gen.include_dirs) |d| common_includes.append(b.allocator, r.resolve(b, d)) catch @panic("oom");
    for (@import("graphics.zig").includes) |path| common_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("oom");
    common_includes.appendSlice(b.allocator, &binder_ndk_includes) catch @panic("oom");
    common_includes.append(b.allocator, b.path("src/native")) catch @panic("oom");
    common_includes.append(b.allocator, o.host_paths_headers) catch @panic("oom");
    common_includes.append(b.allocator, b.path("src/native/input-compat")) catch @panic("oom");
    common_includes.append(b.allocator, o.input_headers) catch @panic("oom");
    common_includes.appendSlice(b.allocator, o.aidl_header_roots) catch @panic("oom");
    common_includes.appendSlice(b.allocator, o.aconfig_header_roots) catch @panic("oom");
    common_includes.appendSlice(b.allocator, o.hidl_header_roots) catch @panic("oom");
    for (@import("hidl.zig").includes) |path|
        common_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("oom");
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
    var native_compiles: std.ArrayList(*std.Build.Step.Run) = .empty;

    const G = struct {
        b: *std.Build,
        objects: *std.ArrayList(std.Build.LazyPath),
        binder_jni_sources: *std.ArrayList(std.Build.LazyPath),
        native_compiles: *std.ArrayList(*std.Build.Step.Run),
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
                // Track consumed headers, including forced ABI headers. Directory
                // arguments alone only record the search path in the Run cache.
                compile.addArgs(&.{ "-MD", "-MF" });
                _ = compile.addDepFileOutputArg2(self.b.fmt("{s}.d", .{name}), .{});
                const source = root.path(self.b, file);
                compile.addFileArg(source);
                for (gen.binder_jni_srcs) |jni_source| {
                    if (std.mem.eql(u8, file, jni_source))
                        self.binder_jni_sources.append(self.b.allocator, source) catch @panic("oom");
                }
                compile.addArg("-o");
                const object = compile.addOutputFileArg(self.b.fmt("{s}.o", .{name}));
                self.native_compiles.append(self.b.allocator, compile) catch @panic("oom");
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
    var binder_jni_sources: std.ArrayList(std.Build.LazyPath) = .empty;
    var g = G{
        .b = b,
        .objects = &objects,
        .binder_jni_sources = &binder_jni_sources,
        .native_compiles = &native_compiles,
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

    // Original startup/heap clients call the host allocator and loader at their
    // Bionic ABI boundary. Keep the Android allocator declarations isolated from
    // the host libc headers.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{
        "android_app_Activity.cpp", "android_app_ActivityThread.cpp", "android_ddm_DdmHandleNativeHeap.cpp",
    }, &.{
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "bionic/libc/platform"),
    }, &.{ "-DM_PURGE=-101", "-Dmallopt=azk_android_mallopt" });

    g.add(o.aosp.path(b, "packages/modules/Connectivity/staticlibs/native/bpfmapjni"), &.{"com_android_net_module_util_BpfMap.cpp"}, &.{
        o.aosp.path(b, "packages/modules/Connectivity/bpf/syscall_wrappers/include"),
        o.aosp.path(b, "packages/modules/Connectivity/bpf/headers/include"),
        // KernelUtils uses bionic's unused annotation; the host libc has no spelling for it.
    }, &.{"-D__unused=__attribute__((__unused__))"});
    const zygote_headers = b.addWriteFiles();
    _ = zygote_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/sys/system_properties.h"), "sys/system_properties.h");
    _ = zygote_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/android/set_abort_message.h"), "android/set_abort_message.h");
    // Keep the stock parser and discard its unselected Android fork loop. Isolate
    // the Bionic declarations and translate their annotations for host libc.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"com_android_internal_os_ZygoteCommandBuffer.cpp"}, &.{
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "bionic/libc/async_safe/include"),
        zygote_headers.getDirectory(),
    }, &.{
        "-D__printflike(a,b)=__attribute__((format(printf,a,b)))",
        "-D__BIONIC_AVAILABILITY_GUARD(x)=1",
        "-D__INTRODUCED_IN(x)=",
        "-D__unused=__attribute__((__unused__))",
        "-fvisibility=hidden",
        "-ffunction-sections",
        "-fdata-sections",
    });
    // HIDL runtime and generated protocols are shared with original libgui.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{
        "android_os_HidlSupport.cpp", "android_os_HwBinder.cpp", "android_os_HwRemoteBinder.cpp",
        "android_os_HwBlob.cpp",      "android_os_HwParcel.cpp",
    }, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{"android_media_MediaHTTPConnection.cpp"}, &.{ o.aosp.path(b, "frameworks/av/media/module/foundation/include"), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{});
    // Original media metrics serialization, Binder client, and JNI. Isolate the
    // Android endian header so it does not replace the host libc header tree.
    const metrics_headers = b.addWriteFiles();
    _ = metrics_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/sys/endian.h"), "sys/endian.h");
    const metrics_includes = &.{
        o.aosp.path(b, "frameworks/av/media/libmediametrics/include"),
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        metrics_headers.getDirectory(),
        o.aosp.path(b, "frameworks/base/media/jni"),
    };
    g.add(o.aosp.path(b, "frameworks/av/media/libmediametrics"), &.{
        "ItemSerialize-ByteString.cpp", "ItemSerialize-Parcel.cpp", "MediaMetrics.cpp", "MediaMetricsItem.cpp",
    }, metrics_includes, &.{"-D__BIONIC_CAST(kind,type,value)=kind<type>(value)"});
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{"android_media_MediaMetricsJNI.cpp"}, metrics_includes, &.{});
    // Stock media extractor client/service, descrambler and their native data types.
    // Keep Android's loader declarations separate from the host libc headers.
    const extractor_headers = b.addWriteFiles();
    _ = extractor_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/android/api-level.h"), "android/api-level.h");
    _ = extractor_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/android/dlext.h"), "android/dlext.h");
    const extractor_includes = &[_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/av/media/module/extractors/wav/include"),
        o.aosp.path(b, "frameworks/av/media/module/extractors/mp3/include"),
        o.aosp.path(b, "frameworks/av/media/module/extractors/aac/include"),
        o.aosp.path(b, "frameworks/av/media/module/id3/include"),

        extractor_headers.getDirectory(),
        o.aosp.path(b, "frameworks/base/media/jni"),
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/av/include"),
        o.aosp.path(b, "frameworks/av/media/libmedia/include"),
        o.aosp.path(b, "frameworks/av/media/libstagefright/include"),
        o.aosp.path(b, "frameworks/av/media/libstagefright"),
        o.aosp.path(b, "frameworks/av/media/libdatasource/include"),
        o.aosp.path(b, "frameworks/av/media/module/foundation/include"),
        o.aosp.path(b, "frameworks/av/media/module/foundation/include/media/stagefright/foundation"),
        o.aosp.path(b, "frameworks/av/media/module/esds/include"),
        o.aosp.path(b, "frameworks/av/media/module/metadatautils/include"),
        o.aosp.path(b, "frameworks/av/media/ndk/include"),
        o.aosp.path(b, "frameworks/av/media/libmediametrics/include"),
        o.aosp.path(b, "frameworks/av/media/libaudioclient/include"),
        o.aosp.path(b, "frameworks/av/media/libaudiofoundation/include"),
        o.aosp.path(b, "frameworks/av/media/audioaidlconversion/include"),
        o.aosp.path(b, "frameworks/av/media/libmediaplayerservice/include"),
        o.aosp.path(b, "frameworks/native/headers/media_plugin"),
        o.aosp.path(b, "system/media/audio/include"),
        o.aosp.path(b, "system/media/audio_utils/include"),
        o.aosp.path(b, "system/libhidl/transport/token/1.0/utils/include"),
        o.aosp.path(b, "system/libhidl/transport/allocator/1.0/utils/include"),
        o.aosp.path(b, "system/libhwbinder/include"),
        o.aosp.path(b, "system/libhidl/transport/include"),
        o.aosp.path(b, "system/libfmq/base"),
        o.aosp.path(b, "art/libnativeloader/include"),
        o.aosp.path(b, "frameworks/av/services/mediaextractor"),
        o.aosp.path(b, "frameworks/native/headers/media_plugin/media/openmax"),
        o.aosp.path(b, "frameworks/av/media/libmediahelper/include"),
        o.aosp.path(b, "frameworks/av/media/liberror/include"),
        o.aosp.path(b, "external/piex"),
    };
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{ "android_media_MediaExtractor.cpp", "android_media_MediaDescrambler.cpp", "android_media_MediaDataSource.cpp" }, extractor_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{"android_media_Streams.cpp"}, extractor_includes, &.{ "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections" });
    g.add(o.aosp.path(b, "frameworks/av/media/libdatasource"), &.{"DataURISource.cpp"}, extractor_includes, &.{ "-include", "log/log.h" });
    g.add(o.aosp.path(b, "frameworks/av/media/libdatasource"), &.{ "DataSourceFactory.cpp", "FileSource.cpp", "HTTPBase.cpp", "MediaHTTP.cpp", "NuCachedSource2.cpp" }, extractor_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/av/media/libmedia"), &.{ "IMediaExtractor.cpp", "IMediaSource.cpp", "IDataSource.cpp", "IMediaHTTPService.cpp", "IMediaHTTPConnection.cpp", "NdkMediaErrorPriv.cpp" }, extractor_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/av/media/libstagefright"), &.{ "NuMediaExtractor.cpp", "InterfaceUtils.cpp", "CallbackDataSource.cpp", "CallbackMediaSource.cpp", "RemoteMediaExtractor.cpp", "RemoteMediaSource.cpp", "MediaSource.cpp", "MediaTrack.cpp", "MediaExtractor.cpp", "MediaExtractorFactory.cpp", "HevcUtils.cpp" }, extractor_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/av/media/libstagefright"), &.{"Utils.cpp"}, extractor_includes, &.{ "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections" });
    g.add(o.aosp.path(b, "frameworks/av/media/module/foundation"), &.{ "MetaData.cpp", "MetaDataBase.cpp" }, extractor_includes, &.{"-D__ANDROID__"});
    g.add(o.aosp.path(b, "frameworks/av/media/module/foundation"), &.{ "MediaBuffer.cpp", "MediaBufferBase.cpp", "MediaBufferGroup.cpp", "MediaDefs.cpp", "AudioPresentationInfo.cpp", "FoundationUtils.cpp", "avc_utils.cpp", "OpusHeader.cpp", "base64.cpp", "ByteUtils.cpp", "ABitReader.cpp", "ColorUtils.cpp", "ColorUtils_fill.cpp", "ColorUtils_ndk.cpp" }, extractor_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/av/media/module/metadatautils"), &.{"MetaDataUtils.cpp"}, extractor_includes, &.{});
    const drm_includes = extractor_includes ++ &[_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/av/drm/libmediadrm/include"),
        o.aosp.path(b, "frameworks/av/drm/libmediadrm/interface"),
        o.aosp.path(b, "hardware/interfaces/common/support/include"),
        o.aosp.path(b, "external/jsoncpp/include"),
        o.media_generated.headers,
        o.aosp.path(b, "external/protobuf/src"),
    };
    var codec_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    codec_includes.appendSlice(b.allocator, drm_includes) catch @panic("OOM");
    for (@import("codec2_sources.zig").includes) |path| codec_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("OOM");
    codec_includes.append(b.allocator, o.aosp.path(b, "libnativehelper/header_only_include")) catch @panic("OOM");
    for ([_][]const u8{ "frameworks/av/media/codec2/core/include", "frameworks/av/media/codec2/vndk/include", "frameworks/native/libs/nativewindow/include-private", "frameworks/av/media/ndk/include_platform", "frameworks/av/media/ndk/include/private", "system/libhidl/transport/memory/1.0/default" }) |path| codec_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("OOM");
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{ "android_media_MediaCodec.cpp", "android_media_MediaCodecList.cpp" }, codec_includes.items, &.{ "-D__ANDROID__", "-D__ANDROID_API__=36", "-Dbetoh64=be64toh", "-D__unused=__attribute__((unused))", "-Wno-invalid-specialization" });
    codec_includes.append(b.allocator, o.aosp.path(b, "frameworks/base/libs/hwui/apex/include")) catch @panic("OOM");
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{ "android_media_MediaMetadataRetriever.cpp", "android_media_SyncParams.cpp" }, codec_includes.items, &.{ "-D__ANDROID__", "-D__ANDROID_API__=36", "-D__unused=__attribute__((unused))", "-D__packed=__attribute__((packed))" });
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_hardware_Camera.cpp"}, codec_includes.items, &.{ "-D__ANDROID__", "-D__ANDROID_API__=36", "-D__unused=__attribute__((unused))", "-D__packed=__attribute__((packed))", "-D__BIONIC_AVAILABILITY_GUARD(x)=1", "-include", "android/api-level.h" });
    g.add(b.path("src/native"), &.{"MediaPlayerJniBridge.cpp"}, codec_includes.items, &.{ "-D__ANDROID__", "-D__ANDROID_API__=36", "-D__unused=__attribute__((unused))", "-D__packed=__attribute__((packed))", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections" });
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{ "android_media_MediaCrypto.cpp", "android_media_MediaDrm.cpp" }, drm_includes, &.{"-Dbetoh64=be64toh"});
    g.add(o.aosp.path(b, "frameworks/av/drm/libmediadrm"), &.{
        "CryptoHal.cpp",              "CryptoHalAidl.cpp",    "CryptoHalHidl.cpp", "DrmStatus.cpp",
        "DrmHal.cpp",                 "DrmHalAidl.cpp",       "DrmHalHidl.cpp",    "DrmHalListener.cpp",
        "DrmSessionManager.cpp",      "DrmMetricsLogger.cpp", "DrmMetrics.cpp",    "DrmMetricsConsumer.cpp",
        "PluginMetricsReporting.cpp",
    }, drm_includes, &.{ "-Dbetoh64=be64toh", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections" });
    // NuPlayer's original DRM factory clients share the platform's HAL objects.
    g.add(o.aosp.path(b, "frameworks/av/drm/libmediadrm"), &.{"DrmUtils.cpp"}, drm_includes, &.{"-Dbetoh64=be64toh"});
    g.add(o.aosp.path(b, "frameworks/av/media/libmedia"), &.{ "MediaProfiles.cpp", "Metadata.cpp" }, codec_includes.items, &.{"-D__unused=__attribute__((unused))"});
    for (@import("generator_sources.zig").media_native_protos) |file|
        g.add(o.media_generated.sources, &.{b.fmt("{s}.pb.cc", .{file[0 .. file.len - 6]})}, drm_includes, &.{});
    g.add(o.surfaceflinger_generated.sources, &.{"android/hardware/drm/HdcpLevels.cpp"}, drm_includes, &.{});
    g.add(o.aosp.path(b, "hardware/interfaces/common/support"), &.{"NativeHandle.cpp"}, drm_includes, &.{});
    g.add(o.aosp.path(b, "external/jsoncpp/src/lib_json"), &.{ "json_reader.cpp", "json_value.cpp", "json_writer.cpp" }, drm_includes, &.{ "-DJSON_USE_EXCEPTION=0", "-DJSONCPP_NO_LOCALE_SUPPORT" });
    g.add(o.aosp.path(b, "frameworks/av/services/mediaextractor"), &.{"MediaExtractorService.cpp"}, extractor_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/av/media/module/esds"), &.{"ESDS.cpp"}, extractor_includes, &.{});
    const extractors = @import("media_extractor_sources.zig");
    var plugin_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    plugin_includes.appendSlice(b.allocator, codec_includes.items) catch @panic("OOM");
    for (extractors.includes) |path| plugin_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("OOM");
    for (extractors.plugins) |plugin| plugin_includes.append(b.allocator, o.aosp.path(b, b.fmt("{s}/{s}/include", .{ extractors.root, plugin.name }))) catch @panic("OOM");
    for (extractors.plugins) |plugin| g.add(o.aosp.path(b, b.fmt("{s}/{s}", .{ extractors.root, plugin.name })), plugin.files, plugin_includes.items, &.{ b.fmt("-DGETEXTRACTORDEF=azk_{s}_extractor_def", .{plugin.name}), "-fvisibility=hidden", "-Dbetoh64=be64toh", "-include", "algorithm" });
    for (extractors.support) |group| g.add(o.aosp.path(b, group.root), group.files, plugin_includes.items, std.mem.concat(b.allocator, []const u8, &.{ &.{"-fvisibility=hidden"}, group.flags }) catch @panic("OOM"));
    g.add(o.aosp.path(b, "frameworks/av/media/module/mpeg2ts"), &extractors.mpeg2, plugin_includes.items, &.{ "-D__ANDROID__", "-D__ANDROID_API__=36", "-DENABLE_CRYPTO" });
    g.add(o.aosp.path(b, "frameworks/av/media/module/foundation"), &.{"MediaKeys.cpp"}, plugin_includes.items, &.{});
    g.add(o.aosp.path(b, "frameworks/av/media/module/id3"), &.{"ID3.cpp"}, extractor_includes, &.{});
    g.add(o.aosp.path(b, "system/media/audio_utils"), &.{"primitives.c"}, extractor_includes, &.{});
    // Android's host-capable camera metadata library and C++ value containers.
    g.add(o.aosp.path(b, "frameworks/av/camera"), &.{ "CameraMetadata.cpp", "VendorTagDescriptor.cpp" }, &.{}, &.{});
    g.add(o.aosp.path(b, "system/media/camera/src"), &.{"camera_metadata.c"}, &.{}, &.{});
    g.add(o.aosp.path(b, "system/core/libusbhost"), &.{ "usbhost.c", "usbhost_jni.cpp" }, &.{}, &.{});
    for (gen.libbase_srcs) |file| {
        if (!@import("binder_cpp.zig").sharedBase(file)) g.add(o.aosp.path(b, "system/libbase"), &.{file}, &.{o.aosp.path(b, "system/libbase")}, &.{});
    }
    for (gen.libcutils_srcs) |file| {
        if (!std.mem.eql(u8, file, "native_handle.cpp") and !std.mem.eql(u8, file, "ashmem-host.cpp") and !std.mem.eql(u8, file, "trace-host.cpp")) g.add(o.aosp.path(b, "system/core/libcutils"), &.{file}, &.{o.aosp.path(b, "system/core/libcutils")}, &.{});
    }
    for (gen.libutils_srcs) |file| {
        if (!@import("binder_cpp.zig").sharedUtils(file)) g.add(o.aosp.path(b, "system/core/libutils"), &.{file}, &.{o.aosp.path(b, "system/core/libutils")}, &.{"-DANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION"});
    }
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_SystemProperties.cpp"}, &.{
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
    }, &.{ "-include", "azk/native_properties.h" });
    // Original Android SQL functions and ICU collators share SQLite/ICU with JNI.
    g.add(o.aosp.path(b, "external/sqlite/android"), &.{ "sqlite3_android.cpp", "PhoneNumberUtils.cpp", "OldPhoneNumberUtils.cpp" }, &.{}, &.{"-DSQLITE_ENABLE_ICU"});
    const utility_includes = &.{
        o.aosp.path(b, "art/libnativeloader/include"),
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/native/libs/tracing_perfetto/include"),
        o.aosp.path(b, "external/perfetto/include"),
        o.perfetto.headers,
    };
    for (gen.utility_jni_srcs) |file| {
        const flags: []const []const u8 = if (std.mem.eql(u8, file, "android_util_Log.cpp"))
            &.{"-D__android_log_buf_write=azk_diagnostic_log_buf_write"}
        else
            &.{};
        g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{file}, utility_includes, flags);
    }
    g.add(o.aosp.path(b, "external/fmtlib"), &gen.fmtlib_srcs, &.{}, &.{});
    g.add(o.aosp.path(b, "frameworks/minikin"), &minikin_srcs, &.{ o.aosp.path(b, "frameworks/minikin/libs"), o.aosp.path(b, "frameworks/minikin/libs/minikin") }, &.{});
    g.add(o.aosp.path(b, "external/skia"), &skia_extra_srcs, &.{}, &.{});
    // Query the real compositor instead of hostgraphics' fixed display.
    g.add(o.aosp.path(b, "frameworks/native/libs/nativedisplay"), &.{"ADisplay.cpp"}, &.{}, &.{});
    for (gen.fn_ui_srcs) |file| {
        const shared = for (@import("graphics.zig").ui_sources) |source| {
            if (std.mem.eql(u8, file, source)) break true;
        } else false;
        if (!shared) g.add(o.aosp.path(b, "frameworks/native/libs/ui"), &.{file}, &.{o.aosp.path(b, "frameworks/native/libs/ui/include_private")}, &.{});
    }
    g.add(b.path("tests/native"), &.{"ReadbackTest.cpp"}, &.{}, &.{"-DARTZIG_HOST_GRAPHICS"});
    g.add(o.hwui_stats.sources, &.{"statslog_hwui.cpp"}, &.{}, &.{});
    g.add(o.aosp.path(b, "system/core/libstats/bootstrap"), &.{ "StatsBootstrapAtomClient.cpp", "BootstrapClientInternal.cpp" }, &.{}, &.{});
    for (@import("generator_sources.zig").surfaceflinger_stats_aidl) |file| {
        const start = (std.mem.indexOf(u8, file, "/android/") orelse unreachable) + 1;
        g.add(o.surfaceflinger_generated.sources, &.{b.fmt("{s}.cpp", .{file[start .. file.len - 5]})}, &.{}, &.{});
    }
    g.add(o.aosp.path(b, "frameworks/native/libs/shaders"), &.{"shaders.cpp"}, &.{}, &.{});
    g.add(o.aosp.path(b, "external/skia"), &.{
        "src/gpu/android/AHardwareBufferUtils.cpp",
        "src/gpu/ganesh/GrAHardwareBufferUtils.cpp",
        "src/gpu/ganesh/gl/AHardwareBufferGL.cpp",
        "src/gpu/ganesh/vk/AHardwareBufferVk.cpp",
        "src/gpu/ganesh/GrAHardwareBufferImageGenerator.cpp",
        "src/image/SkImage_AndroidFactories.cpp",
        "src/gpu/ganesh/surface/SkSurface_AndroidFactories.cpp",
    }, &.{b.path("src/native/graphics/include")}, &@import("graphics.zig").egl_image_flags);

    g.add(o.aosp.path(b, "frameworks/native/libs/tonemap"), &.{"tonemap.cpp"}, &.{}, &.{});

    const hint_includes = &.{
        o.aosp.path(b, "frameworks/native/include/private"),
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/native/services/powermanager/include"),
        o.aosp.path(b, "system/libfmq/include"),
        o.aosp.path(b, "system/libfmq/base"),
    };
    const hint_start = objects.items.len;
    // Soong CommonGlobalCflags zero-initializes the session out-pointer on failure.
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{"performance_hint.cpp"}, hint_includes, &.{"-ftrivial-auto-var-init=zero"});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_PerformanceHintManager.cpp"}, hint_includes, &.{"-Ddlopen=azk_android_dlopen"});
    g.add(o.aosp.path(b, "frameworks/base/services/core/jni"), &.{"com_android_server_hint_HintManagerService.cpp"}, hint_includes, &.{});
    // Original EmptyHalWrapper reports unavailable host power hints below IHintManager.
    g.add(o.aosp.path(b, "frameworks/native/services/powermanager"), &.{ "PowerHalController.cpp", "PowerHalWrapper.cpp", "PowerHintSessionWrapper.cpp" }, hint_includes, &.{"-DARTZIG_HOST_GRAPHICS"});
    for (@import("generator_sources.zig").surfaceflinger_ndk_aidl) |file| {
        const prefix = "hardware/interfaces/power/aidl/";
        if (std.mem.startsWith(u8, file, prefix)) g.add(o.surfaceflinger_generated.sources, &.{b.fmt("{s}.cpp", .{file[prefix.len .. file.len - 5]})}, hint_includes, &.{});
    }

    const sensors = @import("sensor_sources.zig");
    const sensor_headers = b.addWriteFiles();
    // BoringSSL exposes EVP digest declarations through digest.h; host OpenSSL uses evp.h.
    _ = sensor_headers.add("openssl/digest.h", "#include <openssl/evp.h>\n");
    var sensor_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    sensor_includes.appendSlice(b.allocator, &.{ sensor_headers.getDirectory(), o.cppstream_headers, o.input_dispatcher_headers, extractor_headers.getDirectory() }) catch @panic("OOM");
    for (sensors.includes) |path| sensor_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("OOM");
    const android_abi_flags = [_][]const u8{ "-D__unused=__attribute__((unused))", "-D__ANDROID__", "-D__ANDROID_API__=36", "-D__BIONIC_AVAILABILITY_GUARD(x)=1", "-include", "android/api-level.h", "-ftrivial-auto-var-init=zero" };
    const runtime_includes = &[_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/base/core/jni"),                          o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "system/server_configurable_flags/libflags/include"), apex_inc,
        hw_jni_inc,                                                          o.aosp.path(b, "frameworks/base/libs/hwui"),
        o.aosp.path(b, "frameworks/native/libs/nativewindow/include"),       o.aosp.path(b, "art/libnativebridge/include"),
        o.aosp.path(b, "system/memory/libmemunreachable/include"),           o.aosp.path(b, "frameworks/native/libs/binder/include_activity_manager"),
        o.aosp.path(b, "bionic/libc/platform"),                              o.aosp.path(b, "system/core/libprocessgroup/include"),
        extractor_headers.getDirectory(),                                    zygote_headers.getDirectory(),
    };
    // Keep the original embedded lifecycle; unused VM creation/zygote registration
    // sections are discarded because the launcher creates the existing ART VM.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"AndroidRuntime.cpp"}, runtime_includes, &(android_abi_flags ++ .{ "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-include", "sys/system_properties.h", "-include", b.pathResolve(&.{"src/native/RuntimeEmbedding.h"}) }));
    g.add(b.path("src/native"), &.{"HostedAndroidRuntime.cpp"}, runtime_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_util_Process.cpp"}, runtime_includes ++ &[_]std.Build.LazyPath{
        o.aosp.path(b, "system/memory/libmeminfo/include"),
        o.aosp.path(b, "system/memory/libmeminfo/libdmabufinfo/include"),
    }, &(android_abi_flags ++ .{ "-D__CPU_BITTYPE=unsigned long int", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-Dgetpwnam=azk_android_getpwnam", "-Dgetgrnam=azk_android_getgrnam", "-Dopen=azk_process_open", "-Dfopen=azk_process_fopen", "-include", b.pathResolve(&.{"src/native/ProcessAbi.h"}) }));
    g.add(o.aosp.path(b, "bionic/libc/upstream-openbsd/lib/libc/gen"), &.{"setprogname.c"}, &.{}, &.{ "-D__progname=program_invocation_short_name", "-include", "errno.h" });
    const network_headers = b.addWriteFiles();
    _ = network_headers.add("sys/limits.h", "#include <limits.h>\n");
    _ = network_headers.addCopyFile(o.aosp.path(b, "bionic/libc/dns/include/resolv_netid.h"), "resolv_netid.h");
    _ = network_headers.addCopyFile(o.aosp.path(b, "bionic/libc/dns/include/resolv_params.h"), "resolv_params.h");
    const network_includes = runtime_includes ++ &[_]std.Build.LazyPath{
        network_headers.getDirectory(),
        o.aosp.path(b, "packages/modules/DnsResolver/include/dnsproxyd_protocol"),
        o.aosp.path(b, "packages/modules/Connectivity/bpf/headers/include"),
        o.aosp.path(b, "packages/modules/Connectivity/bpf/syscall_wrappers/include"),
        o.aosp.path(b, "system/netd/include"),
        o.aosp.path(b, "packages/modules/Connectivity/staticlibs/netd/libnetdutils/include"),
        o.aosp.path(b, "system/netd/client"),
        o.aosp.path(b, "frameworks/base/native/include"),
    };
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"com_android_internal_net_NetworkUtilsInternal.cpp"}, network_includes, &android_abi_flags);
    g.add(o.aosp.path(b, "packages/modules/Connectivity/framework/jni"), &.{"android_net_NetworkUtils.cpp"}, network_includes, &android_abi_flags);
    g.add(o.aosp.path(b, "packages/modules/Connectivity/framework-t/jni"), &.{"android_net_TrafficStats.cpp"}, network_includes, &android_abi_flags);
    g.add(o.aosp.path(b, "system/netd/client"), &.{ "NetdClient.cpp", "FwmarkClient.cpp" }, network_includes, &android_abi_flags);
    g.add(b.path("src/native/network"), &.{"NetworkBackend.cpp"}, network_includes, &android_abi_flags);
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{"net.c"}, network_includes, &.{ "-D__LIBC_HIDDEN__=__attribute__((visibility(\"hidden\")))", "-D__INTRODUCED_IN(x)=", "-D__BIONIC_AVAILABILITY_GUARD(x)=1" });
    const process_includes = runtime_includes ++ &[_]std.Build.LazyPath{
        o.aosp.path(b, "system/core/libprocessgroup"),
        o.aosp.path(b, "system/core/libprocessgroup/util/include"),
        o.aosp.path(b, "external/jsoncpp/include"),
    };
    g.add(o.aosp.path(b, "system/core/libprocessgroup"), &.{ "cgroup_map.cpp", "processgroup.cpp", "sched_policy.cpp", "task_profiles.cpp", "util/cgroup_controller.cpp", "util/cgroup_descriptor.cpp", "util/util.cpp" }, process_includes, &android_abi_flags);
    const account_includes = process_includes ++ &[_]std.Build.LazyPath{
        b.path("src/native/accounts/include"),            o.android_account_headers,
        o.aosp.path(b, "bionic/libc"),                    o.aosp.path(b, "bionic/libc/bionic"),
        o.aosp.path(b, "bionic/libc/async_safe/include"),
    };
    var account_flags: std.ArrayList([]const u8) = .empty;
    account_flags.appendSlice(b.allocator, &android_abi_flags) catch @panic("OOM");
    account_flags.appendSlice(b.allocator, &.{ "-D__BIONIC_ALIGN(v,a)=(((v)+(a)-1)&~((a)-1))", "-D__LIBC_HIDDEN__=__attribute__((visibility(\"hidden\")))", "-D__predict_true(x)=__builtin_expect(!!(x),1)", "-D__predict_false(x)=__builtin_expect(!!(x),0)", "-D__printflike(x,y)=__attribute__((__format__(printf,x,y)))" }) catch @panic("OOM");
    for ([_][]const u8{ "getpwnam", "getpwnam_r", "getpwuid", "getpwuid_r", "getgrnam", "getgrnam_r", "getgrgid", "getgrgid_r", "getpwent", "getgrent", "setpwent", "setgrent", "endpwent", "endgrent", "getgrouplist", "initgroups" }) |name|
        account_flags.append(b.allocator, b.fmt("-D{s}=azk_android_{s}", .{ name, name })) catch @panic("OOM");
    g.add(o.aosp.path(b, "bionic/libc/bionic"), &.{"grp_pwd.cpp"}, account_includes, account_flags.items);
    account_flags.append(b.allocator, "-Dopen=azk_android_account_open") catch @panic("OOM");
    g.add(o.aosp.path(b, "bionic/libc/bionic"), &.{"grp_pwd_file.cpp"}, account_includes, account_flags.items);
    g.add(o.aosp.path(b, "bionic/libc/async_safe"), &.{"async_safe_log.cpp"}, account_includes, &(android_abi_flags ++ .{ "-D__printflike(x,y)=__attribute__((__format__(printf,x,y)))", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections" }));
    const debug_flags = android_abi_flags ++ .{ "-D__printflike(x,y)=__attribute__((__format__(printf,x,y)))", "-Dsigset64_t=sigset_t", "-Dsigaddset64=sigaddset", "-Dsigdelset64=sigdelset" };
    const debug_includes = runtime_includes ++ &[_]std.Build.LazyPath{
        o.aosp.path(b, "packages/modules/Connectivity/bpf/headers/include"),
        o.aosp.path(b, "packages/modules/Connectivity/bpf/syscall_wrappers/include"),
        o.aosp.path(b, "system/memory/libmemtrack/include"),
        o.aosp.path(b, "frameworks/native/libs/binderdebug/include"),
        o.aosp.path(b, "system/core/debuggerd/include"),
        o.aosp.path(b, "system/core/debuggerd/common/include"),
        o.aosp.path(b, "system/libprocinfo/include"),
        o.aosp.path(b, "system/memory/libmeminfo/include"),
        o.aosp.path(b, "system/memory/libmeminfo/libdmabufinfo/include"),
        o.aosp.path(b, "system/libvintf/include"),
        o.aosp.path(b, "system/memory/libmemunreachable/include"),
        o.aosp.path(b, "system/core/debuggerd"),
        o.aosp.path(b, "bionic/libc/system_properties/include"),
    };
    g.add(o.aosp.path(b, "system/memory/libmeminfo"), &.{ "androidprocheaps.cpp", "pageacct.cpp", "procmeminfo.cpp", "sysmeminfo.cpp" }, debug_includes, &debug_flags);
    g.add(o.aosp.path(b, "system/memory/libmeminfo/libdmabufinfo"), &.{ "dmabufinfo.cpp", "dmabuf_sysfs_stats.cpp" }, debug_includes, &debug_flags);
    g.add(o.aosp.path(b, "system/memory/libmemtrack"), &.{"memtrack.cpp"}, debug_includes, &debug_flags);
    g.add(o.aosp.path(b, "frameworks/native/libs/binderdebug"), &.{"BinderDebug.cpp"}, debug_includes, &debug_flags);
    g.add(o.aosp.path(b, "system/core/debuggerd"), &.{ "client/debuggerd_client.cpp", "util.cpp" }, debug_includes, &debug_flags);
    g.add(o.aosp.path(b, "system/libprocinfo"), &.{"process.cpp"}, debug_includes, &debug_flags);
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_Debug.cpp"}, debug_includes, &(android_abi_flags ++ .{ "-DAZK_STOCK_DEBUG_ABI", "-include", b.pathResolve(&.{"src/native/RuntimeDiagnostics.h"}) }));
    g.add(b.path("src/native"), &.{"RuntimeDiagnostics.cpp"}, debug_includes, &.{});
    for (sensors.groups) |group| g.add(o.aosp.path(b, group.root), group.files, sensor_includes.items, &android_abi_flags);
    g.add(b.path("src/native"), &.{ "SensorServiceBootstrap.cpp", "SensorClientTest.cpp" }, sensor_includes.items, &android_abi_flags);
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{"sensor.cpp"}, sensor_includes.items, &android_abi_flags);
    var hidl_sensor_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    // Both the HIDL and AIDL implementations call their private header convert.h.
    hidl_sensor_includes.append(b.allocator, o.aosp.path(b, "hardware/interfaces/sensors/1.0/default/include/sensors")) catch @panic("OOM");
    hidl_sensor_includes.appendSlice(b.allocator, sensor_includes.items) catch @panic("OOM");
    g.add(o.aosp.path(b, "hardware/interfaces/sensors/1.0/default"), &.{"convert.cpp"}, hidl_sensor_includes.items, &android_abi_flags);
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_hardware_SensorManager.cpp"}, sensor_includes.items, &(android_abi_flags ++ .{ "-include", "arpa/inet.h" }));

    // Original VINTF XML/runtime clients. Keep their C++ runtime in this ELF;
    // a separately linked runtime would duplicate libc++ locale state.
    const vintf = @import("vintf_sources.zig");
    var vintf_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    for (vintf.includes) |path| vintf_includes.append(b.allocator, o.aosp.path(b, path)) catch @panic("OOM");
    vintf_includes.appendSlice(b.allocator, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") }) catch @panic("OOM");
    for (o.xsd) |native| vintf_includes.append(b.allocator, native.headers) catch @panic("OOM");
    const vintf_start = objects.items.len;
    // The host libc++ no longer includes algorithm transitively for libvintf.
    for (vintf.groups) |group| g.add(o.aosp.path(b, group.root), group.files, vintf_includes.items, &.{ "-DLIBVINTF_TARGET", "-include", "algorithm" });
    for (o.xsd) |native| g.add(native.source_root, native.sources, vintf_includes.items, &.{});
    const vintf_end = objects.items.len;
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_VintfObject.cpp"}, vintf_includes.items, &.{ "-DLIBVINTF_TARGET", "-DJNI_OnLoad=azk_vintf_JNI_OnLoad" });
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_VintfRuntimeInfo.cpp"}, vintf_includes.items, &.{"-DLIBVINTF_TARGET"});

    const egl_client_flags = @import("graphics.zig").egl_image_flags ++ [_][]const u8{
        "-DEGL_NO_PLATFORM_SPECIFIC_TYPES",
        "-DeglGetDisplay=bionic_eglGetDisplay",
        "-DeglQueryString=azk_egl_queryString",
        "-DeglChooseConfig=azk_egl_chooseConfig",
        "-DeglGetConfigAttrib=azk_egl_getConfigAttrib",
        "-DeglCreateContext=azk_egl_createContext",
        "-DeglCreatePbufferSurface=azk_egl_createPbuffer",
        "-DeglCreateWindowSurface=azk_egl_createWindow",
        "-DeglQuerySurface=azk_egl_querySurface",
        "-DeglSurfaceAttrib=azk_egl_surfaceAttrib",
        "-DeglSwapBuffers=azk_egl_swapBuffers",
        "-DeglDestroySurface=azk_egl_destroySurface",
        "-DeglMakeCurrent=azk_egl_makeCurrent",
        "-DeglReleaseThread=azk_egl_releaseThread",
        "-DeglInitialize=azk_egl_initialize",
        "-DeglTerminate=azk_egl_terminate",
        "-DeglGetCurrentSurface=azk_egl_currentSurface",
        "-DeglBindTexImage=azk_egl_bindTexImage",
        "-DeglReleaseTexImage=azk_egl_releaseTexImage",
        "-DeglGetProcAddress=azk_egl_getProcAddress",
        "-DeglBeginFrame=azk_egl_beginFrame",
        "-DeglSwapBuffersWithDamageKHR=azk_egl_swapBuffersWithDamage",
        "-DeglSetDamageRegionKHR=azk_egl_setDamageRegion",
        "-DeglPresentationTimeANDROID=azk_egl_presentationTime",
        "-DeglDupNativeFenceFDANDROID=azk_egl_dupNativeFenceFD",
    };
    // Original JNI over the native Android-window / Mesa platform ABI.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_opengl_EGL14.cpp", "com_google_android_gles_jni_EGLImpl.cpp" }, &.{
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/native/opengl/libs/EGL/include"),
    }, &egl_client_flags);
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_opengl_EGLExt.cpp"}, &.{o.aosp.path(b, "frameworks/base/core/jni/include")}, &.{
        "-DeglPresentationTimeANDROID=azk_egl_presentationTime",
        "-DeglDupNativeFenceFDANDROID=azk_egl_dupNativeFenceFD",
    });
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_opengl_EGL15.cpp"}, &.{o.aosp.path(b, "frameworks/base/core/jni/include")}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni/android/opengl"), &.{ "util.cpp", "poly_clip.cpp" }, &.{ apex_inc, b.path("src/native"), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), o.aosp.path(b, "frameworks/native/opengl/include") }, &.{"-DARTZIG_HOST_GRAPHICS"});
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{"apex/android_bitmap.cpp"}, &.{ apex_inc, hw_jni_inc, extractor_headers.getDirectory() }, &(android_abi_flags ++ .{"-D__RENAME_IF_FILE_OFFSET64(x)="}));
    g.add(o.aosp.path(b, "frameworks/native/opengl/libs/ETC1"), &.{"etc1.cpp"}, &.{o.aosp.path(b, "frameworks/native/opengl/include")}, &.{});
    const tracing_includes = &.{
        o.aosp.path(b, "frameworks/native/libs/tracing_perfetto/include"),
        o.aosp.path(b, "external/perfetto/include"),
    };
    g.add(o.aosp.path(b, "frameworks/native/libs/tracing_perfetto"), &.{ "tracing_perfetto.cpp", "tracing_perfetto_internal.cpp", "tracing_sdk.cpp" }, tracing_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_os_PerfettoTrace.cpp", "android_os_PerfettoTrackEventExtra.cpp" }, tracing_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/androidfw"), &gen.androidfw_srcs, &.{ o.aosp.path(b, "frameworks/base/libs/androidfw/include_pathutils"), zip_inc, zip_incfs_inc, binder_inc }, &.{});
    g.add(o.aosp.path(b, "system/libziparchive"), &gen.ziparchive_srcs, &.{ zip_inc, zip_incfs_inc, o.aosp.path(b, "system/libziparchive") }, &.{ "-DINCFS_SUPPORT_DISABLED=1", "-DZIPARCHIVE_DISABLE_CALLBACK_API=1", "-DZLIB_CONST", "-D_FILE_OFFSET_BITS=64" });
    g.add(o.aosp.path(b, "system/incremental_delivery/incfs/util"), &.{"map_ptr.cpp"}, &.{}, &.{});
    g.add(o.aosp.path(b, "system/incremental_delivery/incfs"), &.{ "incfs_ndk.c", "incfs.cpp", "MountRegistry.cpp", "path.cpp" }, &.{}, &.{"-D_FILE_OFFSET_BITS=64"});
    g.add(o.incremental_sysprop_source.dirname(), &.{"IncrementalProperties.sysprop.cpp"}, &.{}, &.{});
    for (o.aidl_source_roots, @import("generator_sources.zig").cpp_aidl) |root, entry| {
        // SensorService also uses this callback from the media server protocol closure.
        if (std.mem.eql(u8, entry.input, "com/android/internal/app/IAppOpsCallback.aidl")) {
            g.add(root, &.{entry.output.?}, extractor_includes, &.{});
            continue;
        }
        // Codec2's native library owns its work and bufferpool protocols.
        if (@import("codec2.zig").isProtocol(entry) or @import("codec2.zig").isClientProtocol(entry) or @import("codec2.zig").isResourceProtocol(entry) or @import("codec2.zig").isPlayerProtocol(entry) or @import("codec2.zig").isCameraProtocol(entry)) continue;
        if (entry.output) |output| g.add(root, &.{output}, extractor_includes, &.{});
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
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &gen.binder_jni_srcs, &.{
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/native/libs/binderthreadstate/include"),
    }, &.{});
    for (gen.core_jni_srcs) |file| {
        if (std.mem.eql(u8, file, "android_hardware_camera2_CameraMetadata.cpp")) {
            g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{file}, &.{ o.aosp.path(b, "frameworks/av/camera/include/camera"), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{});
            continue;
        }
        // InputEventReceiver relies on an explicit trace include with host libc++.
        const extra: []const []const u8 = if (std.mem.eql(u8, file, "android_view_InputEventReceiver.cpp"))
            &.{ aidl_inc, "-include", "utils/Trace.h" }
        else
            &.{aidl_inc};
        g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{file}, &.{ o.aosp.path(b, "external/sqlite/android"), o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), zip_inc, apex_inc, input_compat_inc, binder_inc }, extra);
    }
    // These pinned files rely on Bionic/transitive includes for all_of, ioctl
    // and EXT4_SUPER_MAGIC. Declare those headers explicitly for host libc++.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "com_android_internal_content_FileSystemUtils.cpp", "com_android_internal_content_NativeLibraryHelper.cpp" }, &.{ zip_inc, o.aosp.path(b, "frameworks/base/core/jni/include") }, &.{ "-include", "algorithm", "-include", "sys/ioctl.h", "-include", "linux/magic.h" });
    const hwui_includes = &[_]std.Build.LazyPath{
        apex_inc,                                                     hw_jni_inc,                                                  extractor_headers.getDirectory(),
        o.aosp.path(b, "frameworks/base/core/jni"),                   o.aosp.path(b, "frameworks/base/core/jni/include"),          o.aosp.path(b, "frameworks/native/opengl/libs/EGL"),
        o.aosp.path(b, "frameworks/native/opengl/libs/EGL/include"),  o.aosp.path(b, "frameworks/native/opengl/include"),          o.aosp.path(b, "frameworks/native/vulkan/include"),
        o.aosp.path(b, "frameworks/native/libs/graphicsenv/include"), o.aosp.path(b, "frameworks/native/libs/properties/include"), o.aosp.path(b, "frameworks/native/include/private"),
        o.aosp.path(b, "system/libfmq/include"),                      o.aosp.path(b, "system/core/libstats/socket_lazy/include"),  o.aosp.path(b, "frameworks/av/media/ndk/include"),
    };
    const hwui_flags = android_abi_flags ++ egl_client_flags ++ [_][]const u8{
        "-UEGL_NO_PLATFORM_SPECIFIC_TYPES",
        "-DVK_USE_PLATFORM_ANDROID_KHR",
        "-D__RENAME_IF_FILE_OFFSET64(x)=",
        "-DM_PURGE=-101",
        "-include",
        "malloc.h",
        "-Dmallopt=azk_android_mallopt",
        "-Ddlopen=azk_android_dlopen",
        "-Dashmem_create_region=azk_ndk_ashmem_create_region",
        "-Dashmem_get_size_region=azk_ndk_ashmem_get_size_region",
        "-Dashmem_set_prot_region=azk_ndk_ashmem_set_prot_region",
    };
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &gen.hwui_srcs, hwui_includes, &hwui_flags);
    g.add(b.path("tests/native"), &.{"FenceTest.cpp"}, hwui_includes, &hwui_flags);
    g.add(o.aosp.path(b, "frameworks/native/opengl/libs/EGL"), &.{"FileBlobCache.cpp"}, hwui_includes, &hwui_flags);
    g.add(o.aosp.path(b, "frameworks/native/opengl/libs/EGL"), &.{"BlobCache.cpp"}, hwui_includes, &hwui_flags);
    // InputDispatcher already links the original full stats socket implementation.
    // Isolate the lazy library's forwarding exports in this combined ELF; HWUI
    // keeps its original availability probe and resolves the full implementation.
    var lazy_stats_flags: std.ArrayList([]const u8) = .empty;
    lazy_stats_flags.append(b.allocator, "-Ddlopen=azk_android_dlopen") catch @panic("OOM");
    for ([_][]const u8{ "AStatsEvent_obtain", "AStatsEvent_build", "AStatsEvent_write", "AStatsEvent_release", "AStatsEvent_setAtomId", "AStatsEvent_writeInt32", "AStatsEvent_writeInt64", "AStatsEvent_writeFloat", "AStatsEvent_writeBool", "AStatsEvent_writeByteArray", "AStatsEvent_writeString", "AStatsEvent_writeStringArray", "AStatsEvent_writeAttributionChain", "AStatsEvent_addBoolAnnotation", "AStatsEvent_addInt32Annotation", "AStatsSocket_close" }) |symbol| {
        lazy_stats_flags.append(b.allocator, b.fmt("-D{s}=azk_lazy_{s}", .{ symbol, symbol })) catch @panic("OOM");
    }
    g.add(o.aosp.path(b, "system/core/libstats/socket_lazy"), &.{"libstatssocket_lazy.cpp"}, &.{ o.aosp.path(b, "system/core/libstats/socket_lazy/include"), o.aosp.path(b, "packages/modules/StatsD/lib/libstatssocket/include") }, lazy_stats_flags.items);
    g.add(o.aosp.path(b, "bionic/libc"), &.{"bionic/fortify.cpp"}, &.{ o.aosp.path(b, "bionic/libc"), o.aosp.path(b, "bionic/libc/async_safe/include") }, &.{"-DARTZIG_HOST_FORTIFY"});
    const input_transport_headers = b.addWriteFiles();
    _ = input_transport_headers.addCopyFile(o.aosp.path(b, "external/rust/cxx/include/cxx.h"), "rust/cxx.h");
    _ = input_transport_headers.addCopyFile(o.aosp.path(b, "bionic/libc/kernel/uapi/linux/ashmem.h"), "linux/ashmem.h");
    const webview_includes = &.{
        input_transport_headers.getDirectory(),
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/base/libs/hwui/jni"),
        o.aosp.path(b, "frameworks/base/native/webview/plat_support"),
        apex_inc,
    };
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{ "looper.cpp", "input.cpp", "trace.cpp", "native_window_jni.cpp" }, webview_includes, &.{ "-include", "utils/Timers.h" });
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{ "surface_control.cpp", "display_luts.cpp" }, &.{
        o.aosp.path(b, "frameworks/base/core/jni/include"),
        o.aosp.path(b, "frameworks/native/include/private"),
    }, &.{});
    // NDK and framework JNI share original sealed-memfd operations; Binder retains its backing.
    const ndk_ashmem_flags = &.{
        "-DARTZIG_HOST_MEMFD",
        "-include",
        "linux/ashmem.h",
        "-Dashmem_create_region=azk_ndk_ashmem_create_region",
        "-Dashmem_valid=azk_ndk_ashmem_valid",
        "-Dashmem_get_size_region=azk_ndk_ashmem_get_size_region",
        "-Dashmem_set_prot_region=azk_ndk_ashmem_set_prot_region",
        "-Dashmem_pin_region=azk_ndk_ashmem_pin_region",
        "-Dashmem_unpin_region=azk_ndk_ashmem_unpin_region",
        "-Dhas_memfd_support=azk_ndk_has_memfd_support",
    };
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{"sharedmem.cpp"}, webview_includes, ndk_ashmem_flags);
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_os_SharedMemory.cpp", "android_os_MemoryFile.cpp", "android_util_MemoryIntArray.cpp", "com_android_internal_os_ApplicationSharedMemory.cpp" }, webview_includes, ndk_ashmem_flags);
    g.add(o.aosp.path(b, "system/core/libcutils"), &.{"ashmem-dev.cpp"}, webview_includes, ndk_ashmem_flags);
    // Original OBB parser, quota ioctl and AppFuse client; no hosted mount daemon.
    g.add(o.aosp.path(b, "system/core/libappfuse"), &.{ "FuseAppLoop.cc", "FuseBuffer.cc", "FuseBridgeLoop.cc", "EpollController.cc" }, &.{o.aosp.path(b, "system/core/libappfuse/include")}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_content_res_ObbScanner.cpp", "android_os_storage_StorageManager.cpp", "com_android_internal_os_FuseAppLoop.cpp" }, &.{ o.aosp.path(b, "frameworks/base/core/jni/include"), o.aosp.path(b, "system/core/libappfuse/include") }, &.{ "-include", "sys/ioctl.h" });
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_view_InputQueue.cpp"}, webview_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/native/graphics/jni"), &.{"bitmap.cpp"}, webview_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/native/webview/plat_support"), &.{ "draw_functor.cpp", "draw_gl_functor.cpp", "functor_utils.cpp", "graphics_utils.cpp", "graphic_buffer_impl.cpp" }, webview_includes, &.{"-D__unused=__attribute__((unused))"});
    g.add(o.aosp.path(b, "frameworks/base/native/webview/plat_support"), &.{"jni_entry_point.cpp"}, webview_includes, &.{"-DJNI_OnLoad=azk_webview_JNI_OnLoad"});
    g.add(b.path("src/native"), &.{"ArrayUtilsJniBridge.cpp"}, webview_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_hardware_SerialPort.cpp"}, webview_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"com_android_internal_content_om_OverlayManagerImpl.cpp"}, webview_includes, &.{});

    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{"apex/android_matrix.cpp"}, hwui_includes, &hwui_flags);
    // Stock InputManagerService JNI over inputflinger's InputManager, with
    // libinputservice's sprite PointerController and the viewport and topology
    // JNI it converts display state with.
    const inputflinger_includes = hwui_includes ++ &[_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/base/libs"),                                 o.aosp.path(b, "frameworks/base/libs/input"),
        o.aosp.path(b, "frameworks/native/services"),                           o.aosp.path(b, "frameworks/native/services/inputflinger"),
        o.aosp.path(b, "frameworks/native/services/inputflinger/include"),      o.aosp.path(b, "frameworks/native/services/inputflinger/dispatcher/include"),
        o.aosp.path(b, "frameworks/native/services/inputflinger/reader/include"), o.aosp.path(b, "frameworks/native/services/inputflinger/rust/ffi"),
        o.aosp.path(b, "frameworks/native/services/batteryservice/include"),    o.aosp.path(b, "external/libchrome-gestures"),
        o.aosp.path(b, "external/libchrome-gestures/include"),                  o.aosp.path(b, "frameworks/native/libs/attestation/include"),
        o.aosp.path(b, "frameworks/base/services/core/jni"),                     o.aosp.path(b, "system/server_configurable_flags/libflags/include"),
        o.input_dispatcher_headers,                                              o.inputflinger_headers,
        // The dispatcher's StatsD and processgroup closure (input_dispatcher.zig).
        o.aosp.path(b, "packages/modules/StatsD/lib/libkll/include"),           o.aosp.path(b, "packages/modules/StatsD/lib/libstatspull/include"),
        o.aosp.path(b, "packages/modules/StatsD/lib/libstatssocket/include"),   o.aosp.path(b, "system/core/libprocessgroup/include"),
        o.aosp.path(b, "hardware/libhardware_legacy/include"),
    };
    g.add(o.aosp.path(b, "frameworks/base/libs/input"), &.{ "PointerController.cpp", "PointerControllerContext.cpp", "MouseCursorController.cpp", "TouchSpotController.cpp", "SpriteController.cpp", "SpriteIcon.cpp" }, inputflinger_includes, &hwui_flags);
    g.add(o.aosp.path(b, "frameworks/base/services/core/jni"), &.{"com_android_server_input_InputManagerService.cpp"}, inputflinger_includes, &hwui_flags);
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{ "android_hardware_display_DisplayViewport.cpp", "android_hardware_display_DisplayTopology.cpp", "android_view_PointerIcon.cpp", "android_view_VerifiedKeyEvent.cpp", "android_view_VerifiedMotionEvent.cpp" }, inputflinger_includes, &hwui_flags);
    // Bionic's libc++ reaches <algorithm> transitively; the host's does not.
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"android_os_UEventObserver.cpp"}, inputflinger_includes, &(hwui_flags ++ [_][]const u8{ "-include", "algorithm" }));
    g.add(b.path("src/native/input"), &.{"HostUserActivity.cpp"}, inputflinger_includes, &hwui_flags);
    // UEventObserver's netlink listener, as libhardware_legacy provides it.
    g.add(o.aosp.path(b, "hardware/libhardware_legacy"), &.{"uevent.c"}, &.{o.aosp.path(b, "hardware/libhardware_legacy/include")}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/services/core/jni"), &.{ "com_android_server_display_DisplayControl.cpp", "com_android_server_wm_TaskFpsCallbackController.cpp" }, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), binder_inc }, &.{});
    // Stock AppFuseBridge JNI over libappfuse: the bridge loop itself needs no
    // FUSE device; vold's mountAppFuse supplies one, which the host declines.
    g.add(o.aosp.path(b, "frameworks/base/services/core/jni"), &.{"com_android_server_storage_AppFuseBridge.cpp"}, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), o.aosp.path(b, "system/core/libappfuse/include"), binder_inc }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &gen.jni_srcs, hwui_includes, &hwui_flags);
    // Pinned HWUI codecs and PDF writer, with their original Soong dependency closure.
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{ "jni/Movie.cpp", "jni/MovieImpl.cpp", "jni/GIFMovie.cpp", "jni/YuvToJpegEncoder.cpp", "jni/pdf/PdfDocument.cpp" }, hwui_includes ++ &[_]std.Build.LazyPath{ o.aosp.path(b, "external/giflib"), o.aosp.path(b, "external/libultrahdr/lib/include"), o.aosp.path(b, "external/libultrahdr"), o.aosp.path(b, "external/skia/include/docs") }, &hwui_flags);
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{ "jni/pdf/PdfEditor.cpp", "jni/pdf/PdfUtils.cpp", "apex/jni_runtime.cpp" }, hwui_includes ++ &[_]std.Build.LazyPath{o.aosp.path(b, "external/pdfium/public")}, &hwui_flags);
    const pdfium_includes = &.{ o.aosp.path(b, "external/pdfium"), o.aosp.path(b, "external/pdfium/third_party"), extractor_headers.getDirectory() };
    const pdfium_flags = &(android_abi_flags ++ [_][]const u8{ "-O3", "-fexceptions", "-DANDROID", "-DCOMPONENT_BUILD", "-DFPDF_IMPLEMENTATION", "-DOPJ_STATIC", "-DPNG_PREFIX", "-DPNG_USE_READ_MACROS" });
    const pdfium_start = objects.items.len;
    for (@import("gen/pdfium_sources.zig").sources) |source| {
        // Current libc++ instantiates unique_ptr destruction earlier; provide the
        // original complete image type without changing the pinned sources.
        const image_type = std.mem.eql(u8, source, "core/fxcodec/jbig2/JBig2_SddProc.cpp") or std.mem.eql(u8, source, "core/fxcodec/jbig2/JBig2_SymbolDict.cpp");
        // Host FreeType hides its internal Adobe glyph table. Instantiate the
        // exact table supplied by PDFium, alongside its original lookup code.
        const glyph_table = std.mem.eql(u8, source, "core/fxge/freetype/fx_freetype.cpp");
        g.add(o.aosp.path(b, "external/pdfium"), &.{source}, pdfium_includes, if (image_type) pdfium_flags ++ &[_][]const u8{ "-include", "core/fxcodec/jbig2/JBig2_Image.h" } else if (glyph_table) pdfium_flags ++ &[_][]const u8{"-DDEFINE_PS_TABLES_DATA"} else pdfium_flags);
    }
    g.add(o.aosp.path(b, "external/giflib"), &.{ "dgif_lib.c", "egif_lib.c", "gifalloc.c", "gif_err.c", "gif_hash.c", "quantize.c" }, &.{}, &.{"-DHAVE_REALLOCARRAY"});
    g.add(o.aosp.path(b, "external/libultrahdr"), &@import("gen/island_sources.zig").ultrahdr_srcs, &.{ o.aosp.path(b, "external/libultrahdr"), o.aosp.path(b, "external/libultrahdr/lib/include"), o.aosp.path(b, "external/image_io/includes") }, &.{ "-DUHDR_ENABLE_INTRINSICS", "-DUHDR_WRITE_XMP", "-frtti" });
    g.add(o.aosp.path(b, "external/image_io"), &@import("gen/island_sources.zig").image_io_srcs, &.{ o.aosp.path(b, "external/image_io/includes"), o.aosp.path(b, "external/modp_b64") }, &.{ "-DUNIX_ENV=1", "-frtti" });
    g.add(o.aosp.path(b, "external/modp_b64"), &.{"modp_b64.cc"}, &.{o.aosp.path(b, "external/modp_b64/modp_b64")}, &.{});
    g.add(o.aosp.path(b, "libnativehelper"), &gen.nativehelper_srcs, &.{}, &.{ "-U_GNU_SOURCE", "-D_POSIX_C_SOURCE=200809L", "-D_DEFAULT_SOURCE" });
    var bridge_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    bridge_includes.appendSlice(b.allocator, extractor_includes) catch @panic("OOM");
    bridge_includes.append(b.allocator, apex_inc) catch @panic("OOM");
    bridge_includes.appendSlice(b.allocator, &.{ o.aosp.path(b, "frameworks/av/include"), o.aosp.path(b, "frameworks/av/media/libstagefright/include"), o.aosp.path(b, "frameworks/av/media/module/foundation/include"), o.aosp.path(b, "frameworks/av/services/mediaextractor"), o.aosp.path(b, "frameworks/av/media/libmedia/include"), o.aosp.path(b, "frameworks/av/media/libmediametrics/include"), o.aosp.path(b, "frameworks/base/media/jni"), hw_jni_inc, o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), binder_inc, b.path("src/native/graphics/include"), b.path("src/platform"), o.aosp.path(b, "system/media/audio/include") }) catch @panic("OOM");
    bridge_includes.appendSlice(b.allocator, &.{
        o.aosp.path(b, "frameworks/av/media/libaaudio/include"),
        o.aosp.path(b, "frameworks/av/media/libaaudio/src"),
    }) catch @panic("OOM");
    g.add(o.aosp.path(b, "frameworks/av/media/libaudiofoundation"), &.{"AudioContainers.cpp"}, bridge_includes.items, &.{});
    // Stock libaudioclient, its AIDL conversions and the original audio JNI over
    // hosted media.audio_flinger / media.audio_policy services (src/native/audio).
    const audio_headers = b.addWriteFiles();
    _ = audio_headers.addCopyFile(o.aosp.path(b, "bionic/libc/include/sys/system_properties.h"), "sys/system_properties.h");
    var audio_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    audio_includes.appendSlice(b.allocator, bridge_includes.items) catch @panic("OOM");
    audio_includes.appendSlice(b.allocator, &.{
        audio_headers.getDirectory(),
        o.aosp.path(b, "frameworks/av/media/libaudioclient/include/media"),
        o.aosp.path(b, "frameworks/av/media/utils/include"),
        o.aosp.path(b, "frameworks/av/media/libshmem/include"),
        o.aosp.path(b, "frameworks/av/media/libnbaio/include_mono"),
        fn_inc,
        b.path("src/native/audio"),
    }) catch @panic("OOM");
    const audio_flags = [_][]const u8{ "-D__unused=__attribute__((__unused__))", "-DARTZIG_HOST_THREAD_PRIORITY", "-D__BIONIC_AVAILABILITY_GUARD(x)=1", "-D__INTRODUCED_IN(x)=", "-DARTZIG_SHARED_PROPERTIES" };
    g.add(o.aosp.path(b, "frameworks/av/media/libaudioclient"), &.{
        "ToneGenerator.cpp",            "AudioTrack.cpp",           "AudioSystem.cpp",           "AudioTrackShared.cpp", "IAudioFlinger.cpp",
        "AidlConversion.cpp",           "PlayerBase.cpp",           "TrackPlayerBase.cpp",       "AudioPolicy.cpp",      "AudioProductStrategy.cpp",
        "AudioVolumeGroup.cpp",         "PolicyAidlConversion.cpp", "VolumeGroupAttributes.cpp", "AudioRecord.cpp",      "AudioEffect.cpp",
        "RecordingActivityTracker.cpp",
    }, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/av/media/audioaidlconversion"), &.{"AidlConversionCppNdk.cpp"}, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/av/media/libaudiofoundation"), &.{ "AudioDeviceTypeAddr.cpp", "AudioGain.cpp", "AudioPort.cpp", "AudioProfile.cpp", "DeviceDescriptorBase.cpp" }, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/av/media/libmediahelper"), &.{ "TypeConverter.cpp", "AudioParameter.cpp", "AudioValidator.cpp" }, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/av/media/libshmem"), &.{ "ShmemCompat.cpp", "ShmemUtil.cpp" }, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/av/media/utils"), &.{"ServiceSingleton.cpp"}, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/native/services/audiomanager"), &.{"IAudioManager.cpp"}, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "system/media/audio_utils"), &.{ "mutex.cpp", "threads.cpp", "format.c", "StringUtils.cpp", "roundup.c", "minifloat.c", "Balance.cpp" }, audio_includes.items, &audio_flags);
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{
        "android_media_AudioSystem.cpp",            "android_media_AudioTrack.cpp",            "android_media_AudioRecord.cpp",
        "android_media_AudioAttributes.cpp",        "android_media_AudioDeviceAttributes.cpp", "android_media_AudioEffectDescriptor.cpp",
        "android_media_AudioProductStrategies.cpp", "android_media_AudioVolumeGroups.cpp",     "android_media_MicrophoneInfo.cpp",
        "android_media_AudioTrackCallback.cpp",     "android_media_DeviceCallback.cpp",        "android_media_audio_common_AidlConversion.cpp",
        "android_media_ToneGenerator.cpp",
    }, audio_includes.items, &audio_flags);
    var recorder_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    recorder_includes.appendSlice(b.allocator, audio_includes.items) catch @panic("OOM");
    recorder_includes.appendSlice(b.allocator, codec_includes.items) catch @panic("OOM");
    recorder_includes.appendSlice(b.allocator, &.{ extractor_headers.getDirectory(), o.aosp.path(b, "frameworks/base/media/jni/audioeffect") }) catch @panic("OOM");
    g.add(o.aosp.path(b, "frameworks/base/media/jni"), &.{ "android_media_MediaRecorder.cpp", "android_media_MediaProfiles.cpp" }, recorder_includes.items, &android_abi_flags);
    g.add(o.aosp.path(b, "frameworks/av/media/libmedia"), &.{"mediarecorder.cpp"}, recorder_includes.items, &android_abi_flags);
    g.add(o.aosp.path(b, "frameworks/base/media/jni/audioeffect"), &.{ "android_media_AudioEffect.cpp", "android_media_SourceDefaultEffect.cpp", "android_media_StreamDefaultEffect.cpp", "android_media_Visualizer.cpp", "Visualizer.cpp" }, recorder_includes.items, &(android_abi_flags ++ .{"-DJNI_OnLoad=azk_audioeffect_JNI_OnLoad"}));
    g.add(o.aosp.path(b, "system/media/audio_utils"), &.{"fixedfft.cpp"}, audio_includes.items, &audio_flags);
    // Unsupported-by-default Bn bases mirror the generated service interfaces.
    const unsupported_headers = b.addWriteFiles();
    for (o.aidl_header_roots, @import("generator_sources.zig").cpp_aidl) |root, entry| {
        for (audio_host_interfaces) |interface| {
            if (!std.mem.eql(u8, entry.input, interface)) continue;
            const gen_unsupported = b.addSystemCommand(&.{ "python3", "-B" });
            gen_unsupported.addFileArg(b.path("tools/gen-unsupported-cpp.py"));
            gen_unsupported.addFileArg(root.path(b, b.fmt("{s}.h", .{interface[0 .. interface.len - ".aidl".len]})));
            const out = gen_unsupported.addOutputDirectoryArg("unsupported");
            gen_unsupported.setName(b.fmt("generate unsupported base for {s}", .{interface}));
            _ = unsupported_headers.addCopyDirectory(out, "", .{});
        }
    }
    audio_includes.append(b.allocator, unsupported_headers.getDirectory()) catch @panic("OOM");
    // Stock libaaudio: the public C ABI over its legacy AudioTrack/AudioRecord path.
    // The MMAP client compiles but is never selected: the host reports no MMAP policy.
    var aaudio_includes: std.ArrayList(std.Build.LazyPath) = .empty;
    aaudio_includes.appendSlice(b.allocator, audio_includes.items) catch @panic("OOM");
    for ([_][]const u8{ "binding", "client", "core", "fifo", "flowgraph", "legacy", "utility" }) |dir| {
        aaudio_includes.append(b.allocator, o.aosp.path(b, b.fmt("frameworks/av/media/libaaudio/src/{s}", .{dir}))) catch @panic("OOM");
    }
    g.add(o.aosp.path(b, "frameworks/av/media/libaaudio/src"), &gen.libaaudio_srcs, aaudio_includes.items, &(audio_flags ++ [_][]const u8{"-DAAUDIO_API=__attribute__((visibility(\"default\")))"}));
    g.add(b.path("src/native/audio"), &audio_host_srcs, audio_includes.items, &audio_flags);
    bridge_includes.append(b.allocator, o.aosp.path(b, "frameworks/native/opengl/libs/EGL")) catch @panic("OOM");
    g.add(b.path(""), &our_srcs, bridge_includes.items, &.{});
    g.add(o.aosp.path(b, "frameworks/base/native/android"), &.{"hardware_buffer_jni.cpp"}, hwui_includes, &android_abi_flags);
    const platform_files_index = objects.items.len;
    g.add(b.path("src/native"), &.{"PlatformFiles.cpp"}, &.{}, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"core_jni_helpers.cpp"}, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), apex_inc }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &gen.input_handle_jni_srcs, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), apex_inc }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{"apex/android_region.cpp"}, &.{ apex_inc, hw_jni_inc }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &gen.buffer_jni_srcs, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), apex_inc }, &.{});
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{ "apex/android_canvas.cpp", "apex/android_paint.cpp" }, &.{ apex_inc, hw_jni_inc }, &.{});
    for (gen.surface_jni_srcs) |file| {
        // Original Android branches register HardwareBuffer and image-plane operations.
        if (std.mem.eql(u8, file, "android_media_ImageReader.cpp") or std.mem.eql(u8, file, "android_media_Utils.cpp")) {
            g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{file}, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), extractor_headers.getDirectory(), apex_inc }, &android_abi_flags);
            continue;
        }
        const flags: []const []const u8 = if (std.mem.eql(u8, file, "android_view_TunnelModeEnabledListener.cpp"))
            &.{"-DARTZIG_HOST_GRAPHICS"}
        else
            &.{};
        g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{file}, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), apex_inc }, flags);
    }
    g.add(o.aosp.path(b, "frameworks/base/libs/hwui"), &.{"apex/properties.cpp"}, &.{apex_inc}, &.{});
    const texture_inc = o.aosp.path(b, "frameworks/native/libs/nativedisplay/include");
    g.add(o.aosp.path(b, "frameworks/native/libs/nativedisplay"), &gen.surface_texture_srcs, &.{ texture_inc, b.path("src/native/graphics/include") }, &@import("graphics.zig").egl_consumer_flags);
    for (gen.texture_jni_srcs) |file| {
        const flags: []const []const u8 = if (std.mem.eql(u8, file, "android_view_Surface.cpp")) &android_abi_flags else &.{};
        g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{file}, &.{ o.aosp.path(b, "frameworks/base/core/jni"), o.aosp.path(b, "frameworks/base/core/jni/include"), extractor_headers.getDirectory(), apex_inc, texture_inc }, flags);
    }
    g.add(o.aosp.path(b, "frameworks/base/core/jni"), &.{"com_google_android_gles_jni_GLImpl.cpp"}, &.{o.aosp.path(b, "frameworks/base/core/jni/include")}, &.{});

    const media_format_includes = &[_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/av/media/module/foundation/include"),
        o.aosp.path(b, "frameworks/av/media/module/foundation/include/media/stagefright/foundation"),
        o.aosp.path(b, "frameworks/av/include"),
        o.aosp.path(b, "frameworks/av/media/ndk/include"),
    };
    // Include AMessage/AString's original Parcel methods now that libbinder is
    // available. The host-only variant omits methods native MediaCodec needs.
    g.add(o.aosp.path(b, "frameworks/av/media/module/foundation"), &gen.media_format_foundation_srcs, media_format_includes, &.{"-D__ANDROID__"});
    g.add(o.aosp.path(b, "frameworks/av/media/ndk"), &.{"NdkMediaFormat.cpp"}, media_format_includes, &.{"-DEXPORT=__attribute__((visibility(\"default\")))"});
    g.add(o.aosp.path(b, "frameworks/av/media/libmedia"), &.{"NdkMediaFormatPriv.cpp"}, media_format_includes, &.{});
    const image_reader_includes = media_format_includes ++ &[_]std.Build.LazyPath{
        o.aosp.path(b, "frameworks/base/core/jni"),
        o.aosp.path(b, "frameworks/native/libs/nativewindow/include-private"),
    };
    g.add(o.aosp.path(b, "frameworks/av/media/ndk"), &.{ "NdkImageReader.cpp", "NdkImage.cpp" }, image_reader_includes, &.{"-DEXPORT=__attribute__((visibility(\"default\")))"});
    const legacy_drm_includes = &.{
        o.aosp.path(b, "frameworks/av/include"),
        o.aosp.path(b, "frameworks/av/drm/common/include"),
        o.aosp.path(b, "frameworks/av/drm/libdrmframework/include"),
        o.aosp.path(b, "frameworks/base/core/jni/include"),
    };
    g.add(o.aosp.path(b, "frameworks/av/drm/common"), &gen.legacy_drm_common_srcs, legacy_drm_includes, &.{});
    g.add(o.aosp.path(b, "frameworks/av/drm/libdrmframework"), &gen.legacy_drm_client_srcs, legacy_drm_includes, &.{});
    // Keep the original standalone-library registrar while sharing the native island.
    g.add(o.aosp.path(b, "frameworks/base/drm/jni"), &.{"android_drm_DrmManagerClient.cpp"}, legacy_drm_includes, &.{"-DJNI_OnLoad=azk_legacy_drm_JNI_OnLoad"});

    const pdfium_objects = objects.items[pdfium_start..][0..@import("gen/pdfium_sources.zig").sources.len];
    var pdf_objects: std.ArrayList(std.Build.LazyPath) = .empty;
    var pdf_g = g;
    pdf_g.objects = &pdf_objects;
    const pdf_root = o.aosp.path(b, "packages/providers/MediaProvider/pdf/framework/libs/pdfClient");
    pdf_g.add(pdf_root, &.{ "annotation.cc", "document.cc", "file.cc", "form_filler.cc", "form_widget_info.cc", "image_object.cc", "jni_conversion.cc", "linux_fileops.cc", "normalize.cc", "page.cc", "page_object.cc", "path_object.cc", "pdf_document_jni.cc", "rect.cc", "text_object.cc", "utf.cc", "utils/annot.cc", "utils/annot_hider.cc", "utils/byte_value.cc", "utils/pdf_strings.cc", "utils/text.cc", "utils/utf.cc" }, &.{ pdf_root, o.aosp.path(b, "external/pdfium"), o.aosp.path(b, "external/pdfium/public"), extractor_headers.getDirectory() }, &(android_abi_flags ++ .{ "-fexceptions", "-include", "vector" }));

    // Zig's bundled LLD rejects valid weak-hidden libc++ COMDAT relocations in
    // Skia before resolving them locally. Keep Zig's compiler and libc++, but
    // delegate this one final ELF link to GNU bfd.
    const link = b.addSystemCommand(&.{
        b.graph.zig_exe,                      "c++",                           "-fuse-ld=bfd", "-shared", "-Wl,-z,defs", "-Wl,--gc-sections",
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
    link.addFileArg(o.host_paths_library);
    link.addFileArg(o.perfetto.sdk.getEmittedBin());
    link.addFileArg(o.perfetto.base.getEmittedBin());
    link.addFileArg(o.binder_ndk.getEmittedBin());
    link.addFileArg(o.binder_cpp.getEmittedBin());
    // Stock InputChannel JNI uses InputChannelCore and the shared native
    // transport. This final ELF link owns the C++ runtime and Rust archive.
    link.addArtifactArg(o.input_dispatcher_jni);
    link.addFileArg(o.input_dispatcher.getEmittedBin());
    link.addFileArg(o.protobuf.getEmittedBin());
    link.addFileArg(o.input.native.getEmittedBin());
    link.addFileArg(o.inputflinger.getEmittedBin());
    link.addFileArg(o.inputflinger_external.getEmittedBin());
    // libinputflinger_rs links the input crate, so it also provides the
    // verifier's bridge exports; linking both staticlibs would duplicate std.
    link.addFileArg(o.inputflinger_rust);
    {
        const codec2 = o.codec2;
        link.addArg("-Wl,--wrap=fopen,--wrap=stat,--wrap=opendir,--wrap=FT_Open_Face");
        // Share libgui/foundation globals with JNI in one ELF image. Linking
        // another static libgui into a client executable duplicates global
        // construction/destruction (notably LayerStatePermissions).
        link.addArg("-Wl,-u,AMediaCodec_createCodecByName");
        // The FFmpeg HAL component reuses both original SimpleC2 base units.
        // Keep their shared foundation/Codec2 globals in this same ELF image.
        link.addArg("-Wl,-u,_ZN7android17SimpleC2Component4intfEv");
        link.addArg("-Wl,-u,_ZN7android17SimpleC2InterfaceIvE10BaseParams16noPrivateBuffersEv");
        link.addArg("-Wl,-u,_ZNK4aidl7android8hardware5media2c25utils14ComponentStore6statusEv");
        // JNI and MediaPlayerService share every libcamera_client parcelable and
        // parameter implementation, including symbols used only by the service.
        link.addArg("-Wl,--whole-archive");
        link.addFileArg(codec2.camera.getEmittedBin());
        link.addArg("-Wl,--no-whole-archive");
        link.addFileArg(codec2.client.getEmittedBin());
        link.addFileArg(codec2.transport.getEmittedBin());
    }
    // NuPlayer renderfright uses the original UI dataspace diagnostic.
    link.addArg("-Wl,-u,_Z16dataspaceDetails19android_dataspace_t");
    link.addFileArg(o.gui.getEmittedBin());
    // Original MPEG-TS playback discovers CAS through this shared HIDL client.
    link.addArg("-Wl,-u,_ZN7android8hardware3cas4V1_016IMediaCasService10getServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEEb");
    link.addFileArg(o.hidl.getEmittedBin());
    link.addFileArg(o.ui.getEmittedBin());
    for (o.host_library_roots) |root| {
        link.addPrefixedDirectoryArg("-L", root);
        link.addPrefixedDirectoryArg("-Wl,-rpath,", root);
    }
    link.addFileArg(o.resolver);
    for (o.host_libraries) |library| link.addArg(b.fmt("-l{s}", .{library}));
    link.addArgs(o.host_link_flags);
    link.addArgs(&.{ "-ldl", "-lpthread", "-lm", "-lcrypto", "-o" });
    const output = link.addOutputFileArg("libandroid_platform.so");
    // Original libpdfclient is a separate JNI library with private PDFium
    // globals, preserving the original jni.lds lifetime boundary.
    const pdf_link = b.addSystemCommand(&.{ b.graph.zig_exe, "c++", "-fuse-ld=bfd", "-shared", "-Wl,-z,defs", "-Wl,--gc-sections", "-Wl,-soname,libpdfclient.so", "-Wl,-rpath,$ORIGIN", "-Wl,-rpath,$ORIGIN/..", "-Wl,--wrap=fopen,--wrap=stat,--wrap=opendir,--wrap=FT_Open_Face" });
    pdf_link.step.name = "link original libpdfclient.so";
    pdf_link.removeEnvironmentVariable("NIX_LDFLAGS");
    pdf_link.addPrefixedFileArg("-Wl,--version-script,", pdf_root.path(b, "jni.lds"));
    for (pdf_objects.items) |object| pdf_link.addFileArg(object);
    for (pdfium_objects) |object| pdf_link.addFileArg(object);
    pdf_link.addFileArg(objects.items[platform_files_index]);
    pdf_link.addFileArg(output);
    pdf_link.addFileArg(o.binder_cpp.getEmittedBin());
    for (o.host_library_roots) |root| {
        pdf_link.addPrefixedDirectoryArg("-L", root);
        pdf_link.addPrefixedDirectoryArg("-Wl,-rpath,", root);
    }
    pdf_link.addArgs(&.{ "-lfreetype", "-licuuc", "-licui18n", "-ljpeg", "-lz", "-ldl", "-lpthread", "-lm", "-o" });
    const pdfclient = pdf_link.addOutputFileArg("libpdfclient.so");
    return .{ .pdfclient = pdfclient, .native_compiles = native_compiles.items, .hint_client = objects.items[hint_start], .file = output, .step = &link.step, .binder_jni_sources = binder_jni_sources.items, .binder_jni_includes = common_includes.items, .vintf_objects = objects.items[vintf_start..vintf_end] };
}
