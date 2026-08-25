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
    art: *std.Build.Module,
    /// output dir of tools/gen-aidl-cpp.sh (build step); contains gen/
    aidl_cpp: std.Build.LazyPath,
};

const Roots = struct {
    app: []const u8,
    az: []const u8,
    fb: []const u8,
    hw: []const u8,
    v: []const u8,
    minikin: []const u8,
    sks: []const u8,
    aidl: []const u8,

    fn subst(self: Roots, b: *std.Build, s: []const u8) []const u8 {
        var out = s;
        const pairs = .{
            .{ "$MINIKIN", self.minikin }, .{ "$AIDL", self.aidl }, .{ "$APP", self.app },
            .{ "$AZ", self.az },           .{ "$FB", self.fb },     .{ "$HW", self.hw },
            .{ "$V", self.v },             .{ "$SKS", self.sks },
        };
        inline for (pairs) |p| {
            out = std.mem.replaceOwned(u8, b.allocator, out, p[0], p[1]) catch @panic("oom");
        }
        return out;
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
    "src/native/ArtzigGpuPipeline.cpp", "src/native/HardwareBufferRender.cpp",
    "src/native/HwuiRenderProxyBridge.cpp", "src/native/AospJniBridge.cpp",
    "src/native/AospParcelAbi.cpp",
};

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const app = "."; // paths are build-root relative (zig runs compiles from the build root)
    const r = Roots{
        .app = app,
        .az = b.pathJoin(&.{ app, "artzig" }),
        .fb = b.pathJoin(&.{ app, "artzig", "extra", "frameworks-base" }),
        .hw = b.pathJoin(&.{ app, "artzig", "extra", "frameworks-base", "libs", "hwui" }),
        .v = b.pathJoin(&.{ app, "artzig", "vendor" }),
        .minikin = b.pathJoin(&.{ app, "artzig", "extra", "minikin" }),
        .sks = b.pathJoin(&.{ app, "vendor", "skia-src" }),
        .aidl = b.pathJoin(&.{ app, ".native-cache", "aidl-cpp" }),
    };

    const mod = b.createModule(.{
        .root_source_file = b.path("src/native/platform_abi.zig"),
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
        .imports = &.{.{ .name = "art", .module = o.art }},
    });
    const lib = b.addLibrary(.{ .name = "android_platform", .linkage = .dynamic, .root_module = mod });

    for (gen.include_dirs) |d| mod.addIncludePath(.{ .cwd_relative = r.subst(b, d) });

    // common flags
    var cxx = std.ArrayList([]const u8).empty;
    cxx.appendSlice(b.allocator, &.{
        "-std=gnu++23",            "-fno-exceptions",             "-fno-rtti", "-w",                                          "-D_GNU_SOURCE", "-DNDEBUG",
        // zig enables UBSan for C/C++ in Debug; AOSP code trips it (null memcpy of 0 bytes)
        "-fno-sanitize=undefined",
        // math/half.h specializes std::is_floating_point; libc++ marks that an error
        "-Wno-invalid-specialization",
        // zig hashes `-include` files into the C cache, so it must be a real path
        "-include",  b.pathResolve(&.{"src/native/azk_prelude.h"}),
    }) catch @panic("oom");
    var cc = std.ArrayList([]const u8).empty;
    cc.appendSlice(b.allocator, &.{ "-std=gnu17", "-w", "-D_GNU_SOURCE", "-DNDEBUG", "-fno-sanitize=undefined" }) catch @panic("oom");
    for (gen.defines) |d| {
        cxx.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");
        cc.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");
    }

    const G = struct {
        b: *std.Build,
        mod: *std.Build.Module,
        cxx: []const []const u8,
        cc: []const []const u8,
        fn add(self: @This(), root: []const u8, files: []const []const u8, extra: []const []const u8) void {
            var cpp: std.ArrayList([]const u8) = .empty;
            var c: std.ArrayList([]const u8) = .empty;
            for (files) |f| {
                if (std.mem.endsWith(u8, f, ".c")) c.append(self.b.allocator, f) catch @panic("oom") else cpp.append(self.b.allocator, f) catch @panic("oom");
            }
            if (cpp.items.len > 0) {
                var flags = std.ArrayList([]const u8).empty;
                flags.appendSlice(self.b.allocator, self.cxx) catch @panic("oom");
                flags.appendSlice(self.b.allocator, extra) catch @panic("oom");
                self.mod.addCSourceFiles(.{ .root = .{ .cwd_relative = root }, .files = cpp.items, .flags = flags.items });
            }
            if (c.items.len > 0) {
                var flags = std.ArrayList([]const u8).empty;
                flags.appendSlice(self.b.allocator, self.cc) catch @panic("oom");
                flags.appendSlice(self.b.allocator, extra) catch @panic("oom");
                self.mod.addCSourceFiles(.{ .root = .{ .cwd_relative = root }, .files = c.items, .flags = flags.items });
            }
        }
    };
    const g = G{ .b = b, .mod = mod, .cxx = cxx.items, .cc = cc.items };
    const I = struct {
        fn i(bb: *std.Build, p: []const u8) []const u8 {
            return bb.fmt("-I{s}", .{p});
        }
    }.i;
    const binder_inc = I(b, b.pathJoin(&.{ r.az, "extra/frameworks-native/libs/binder/include" }));
    const zip_inc = I(b, b.pathJoin(&.{ r.v, "system/libziparchive/include" }));
    const zip_incfs_inc = I(b, b.pathJoin(&.{ r.v, "system/libziparchive/incfs_support/include" }));
    // generated AIDL C++ headers come from a build step (LazyPath), so they go
    // on the module include path rather than a per-group -I string
    mod.addIncludePath(o.aidl_cpp.path(b, "gen"));
    const aidl_inc = "-DARTZIG_AIDL_CPP_INCLUDED";
    const input_compat_inc = I(b, b.pathJoin(&.{ r.app, "src/native/input-compat" }));
    const apex_inc = I(b, b.pathJoin(&.{ r.hw, "apex/include" }));
    const hw_jni_inc = I(b, b.pathJoin(&.{ r.hw, "jni" }));
    const fn_inc = I(b, b.pathJoin(&.{ r.az, "extra/frameworks-native/include" }));

    g.add(b.pathJoin(&.{ r.v, "system/libbase" }), &gen.libbase_srcs, &.{I(b, b.pathJoin(&.{ r.v, "system/libbase" }))});
    g.add(b.pathJoin(&.{ r.v, "system/logging/liblog" }), &gen.liblog_srcs, &.{ I(b, b.pathJoin(&.{ r.v, "system/logging/liblog" })), "-DLIBLOG_LOG_TAG=1006", "-DANDROID_DEBUGGABLE=0" });
    g.add(b.pathJoin(&.{ r.v, "system/core/libcutils" }), &gen.libcutils_srcs, &.{I(b, b.pathJoin(&.{ r.v, "system/core/libcutils" }))});
    g.add(b.pathJoin(&.{ r.v, "system/core/libutils" }), &gen.libutils_srcs, &.{ I(b, b.pathJoin(&.{ r.v, "system/core/libutils" })), "-DANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION" });
    g.add(b.pathJoin(&.{ r.v, "external/fmtlib" }), &gen.fmtlib_srcs, &.{});
    g.add(r.minikin, &minikin_srcs, &.{ I(b, b.pathJoin(&.{ r.minikin, "libs" })), I(b, b.pathJoin(&.{ r.minikin, "libs/minikin" })) });
    g.add(r.sks, &skia_extra_srcs, &.{});
    g.add(b.pathJoin(&.{ r.fb, "libs/hostgraphics" }), &gen.hostgraphics_srcs, &.{});
    g.add(b.pathJoin(&.{ r.az, "extra/frameworks-native/libs/ui" }), &gen.fn_ui_srcs, &.{I(b, b.pathJoin(&.{ r.az, "extra/frameworks-native/libs/ui/include_private" }))});
    g.add(b.pathJoin(&.{ r.fb, "libs/androidfw" }), &gen.androidfw_srcs, &.{ I(b, b.pathJoin(&.{ r.fb, "libs/androidfw/include_pathutils" })), zip_inc, zip_incfs_inc, binder_inc });
    g.add(b.pathJoin(&.{ r.v, "system/libziparchive" }), &gen.ziparchive_srcs, &.{ zip_inc, zip_incfs_inc, I(b, b.pathJoin(&.{ r.v, "system/libziparchive" })), "-DINCFS_SUPPORT_DISABLED=1", "-DZIPARCHIVE_DISABLE_CALLBACK_API=1", "-DZLIB_CONST", "-D_FILE_OFFSET_BITS=64" });
    g.add(b.pathJoin(&.{ r.az, "extra/incremental_delivery/incfs/util" }), &.{"map_ptr.cpp"}, &.{});
    g.add(b.pathJoin(&.{ r.az, "extra/frameworks-native/libs/input" }), &gen.input_srcs, &.{ fn_inc, I(b, b.pathJoin(&.{ r.az, "extra/frameworks-native/libs/input" })), input_compat_inc, aidl_inc, binder_inc });
    g.add(b.pathJoin(&.{ r.az, "extra/frameworks-native/libs/attestation" }), &gen.attestation_srcs, &.{fn_inc});
    g.add(b.pathJoin(&.{ r.fb, "core/jni" }), &gen.core_jni_srcs, &.{ I(b, b.pathJoin(&.{ r.app, "src/native/sqlite-compat" })), I(b, b.pathJoin(&.{ r.fb, "core/jni" })), I(b, b.pathJoin(&.{ r.fb, "core/jni/include" })), zip_inc, aidl_inc, apex_inc, input_compat_inc, binder_inc });
    g.add(r.hw, &gen.hwui_srcs, &.{ apex_inc, I(b, b.pathJoin(&.{ r.app, "src/native" })) });
    g.add(r.hw, &.{"apex/android_matrix.cpp"}, &.{ apex_inc, hw_jni_inc });
    g.add(r.hw, &gen.jni_srcs, &.{hw_jni_inc});
    g.add(b.pathJoin(&.{ r.v, "libnativehelper" }), &gen.nativehelper_srcs, &.{ "-U_GNU_SOURCE", "-D_POSIX_C_SOURCE=200809L", "-D_DEFAULT_SOURCE" });
    g.add(r.app, &our_srcs, &.{ hw_jni_inc, I(b, b.pathJoin(&.{ r.fb, "core/jni/include" })), binder_inc, I(b, b.pathJoin(&.{ r.app, "src/platform" })) });

    mod.linkLibrary(o.skia);
    for ([_][]const u8{
        "harfbuzz",    "harfbuzz-icu", "freetype2", "fontconfig", "icu-uc", "icu-i18n",
        "libpng",      "zlib",         "openssl",   "sqlite3",    "egl",    "gl",
        "wayland-egl", "gbm",          "libdrm",
    }) |pc| mod.linkSystemLibrary(pc, .{ .needed = true });
    return lib;
}
