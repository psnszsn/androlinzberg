//! Build the packaged AOSP Skia as a static library with zig c++.
//! Source groups/defines come from build/gen/skia_sources.zig, generated from
//! skia's own GN build by tools/regen-skia.sh — the same technique skia uses to
//! produce AOSP's Android.bp (gn/gn_to_bp.py). Building it ourselves means the
//! library and every consumer (hwui island) see one config and one toolchain:
//! no more header/.so layout mismatches (SK_HIDE_PATH_EDIT_METHODS et al).
const std = @import("std");
const gen = @import("gen/skia_sources.zig");

pub const Options = struct {
    /// external/skia in the root AOSP dependency.
    root: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    host_include_roots: []const std.Build.LazyPath,
    /// extra include dirs (framework mode needs cutils/trace.h + log/log.h)
    include_dirs: []const std.Build.LazyPath = &.{},
};

/// pkg-config names for the system libs skia's GN "libs" refer to.
fn pkgConfigName(lib: []const u8) ?[]const u8 {
    const map = .{
        .{ "harfbuzz", "harfbuzz" },     .{ "harfbuzz-subset", "harfbuzz-subset" }, .{ "z", "zlib" },
        .{ "fontconfig", "fontconfig" }, .{ "expat", "expat" },                     .{ "freetype", "freetype2" },
        .{ "png", "libpng" },            .{ "EGL", "egl" },                         .{ "jpeg", "libjpeg" },
        .{ "GLESv2", "glesv2" },         .{ "webp", "libwebp" },                    .{ "webpdemux", "libwebpdemux" },
        .{ "webpmux", "libwebpmux" },
    };
    inline for (map) |m| {
        if (std.mem.eql(u8, lib, m[0])) return m[1];
    }
    return null; // pthread/dl come with libc
}

// Use the original Soong writer with our GN dependency selection. AOSP's
// Linux config explicitly disables Android AHardwareBuffer support; its full
// Android config enables unrelated codecs not present in this GN graph.
pub fn config(b: *std.Build, root: std.Build.LazyPath) std.Build.LazyPath {
    const run = b.addSystemCommand(&.{"python3"});
    run.addFileArg(b.path("tools/skia-config.py"));
    run.addFileArg(root.path(b, "gn/gn_to_bp_utils.py"));
    const output = run.addOutputDirectoryArg("skia-config");
    run.addArgs(&gen.defines);
    return output;
}

pub const Result = struct { library: *std.Build.Step.Compile, compiles: []const *std.Build.Step.Run };

pub fn build(b: *std.Build, o: Options) Result {
    const mod = b.createModule(.{
        .target = o.target,
        // never build skia unoptimized: -O0 rasterization is unusable
        .optimize = .ReleaseFast,
        .link_libc = true,
        // Skia is linked into libandroid_platform.so.
        .pic = true,
    });
    const lib = b.addLibrary(.{ .name = "skia", .linkage = .static, .root_module = mod });
    const user_config = config(b, o.root);
    mod.addIncludePath(user_config);
    mod.addIncludePath(o.root);

    mod.addIncludePath(o.root.path(b, "include/config"));
    mod.addIncludePath(o.root.path(b, "vma_android/include"));
    mod.addIncludePath(o.root.path(b, "include/third_party/vulkan"));
    for (o.include_dirs) |d| mod.addIncludePath(d);

    var base_flags = std.ArrayList([]const u8).empty;
    base_flags.appendSlice(b.allocator, &.{
        "-std=c++20",          "-fPIC",                       "-fno-exceptions",   "-fno-rtti",
        "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-fstrict-aliasing", "-ffunction-sections",
        "-fdata-sections",     "-Wno-attributes",             "-Wno-psabi",        "-w",
    }) catch @panic("oom");
    base_flags.appendSlice(b.allocator, &.{ "-D__BIONIC_AVAILABILITY_GUARD(x)=1", "-D__INTRODUCED_IN(x)=" }) catch @panic("oom");
    for (gen.defines) |d| base_flags.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");

    var compiles: std.ArrayList(*std.Build.Step.Run) = .empty;
    var object_index: usize = 0;
    for (gen.groups) |g| {
        var flags = std.ArrayList([]const u8).empty;
        flags.appendSlice(b.allocator, base_flags.items) catch @panic("oom");
        for (g.flags) |flag| {
            flags.append(b.allocator, if (std.mem.eql(u8, flag, "-march=skylake-avx512"))
                "-march=skylake_avx512"
            else
                flag) catch @panic("oom");
        }
        // Zig's bundled Clang does not expose __builtin_ia32_vcvtph2ps256;
        // retain skcms' own baseline-forwarding implementation for this TU.
        if (std.mem.eql(u8, g.name, "//modules/skcms:skcms_TransformHsw"))
            flags.append(b.allocator, "-DSKCMS_DISABLE_HSW") catch @panic("oom");
        var cflags: std.ArrayList([]const u8) = .empty;
        for (flags.items) |flag| if (!std.mem.startsWith(u8, flag, "-std=") and
            !std.mem.eql(u8, flag, "-fno-rtti") and
            !std.mem.eql(u8, flag, "-fvisibility-inlines-hidden"))
        {
            cflags.append(b.allocator, flag) catch @panic("oom");
        };
        for (g.files) |file| {
            const is_c = std.mem.endsWith(u8, file, ".c");
            const compile = b.addSystemCommand(&.{
                b.graph.zig_exe,                                           if (is_c) "cc" else "c++", "-c", "-target",
                o.target.query.zigTriple(b.allocator) catch @panic("oom"), "-O3",
            });
            compile.step.name = b.fmt("compile Skia {s}", .{file});
            compile.addArgs(if (is_c) cflags.items else flags.items);
            if (std.mem.eql(u8, file, "src/gpu/ganesh/vk/GrVkCaps.cpp") or
                std.mem.eql(u8, file, "src/gpu/ganesh/gl/GrGLContext.cpp"))
                compile.addArg("-D__unused=__attribute__((unused))");
            compile.addPrefixedDirectoryArg("-I", user_config);
            compile.addPrefixedDirectoryArg("-I", o.root);
            compile.addPrefixedDirectoryArg("-I", o.root.path(b, "include/config"));
            compile.addPrefixedDirectoryArg("-I", o.root.path(b, "vma_android/include"));
            compile.addPrefixedDirectoryArg("-I", o.root.path(b, "include/third_party/vulkan"));
            for (o.include_dirs) |include| compile.addPrefixedDirectoryArg("-I", include);
            for (o.host_include_roots) |include| compile.addPrefixedDirectoryArg("-isystem", include);
            compile.addFileArg(o.root.path(b, file));
            compile.addArg("-o");
            const object = compile.addOutputFileArg(b.fmt("skia-tu-{d}.o", .{object_index}));
            object_index += 1;
            mod.addObjectFile(object);
            compiles.append(b.allocator, compile) catch @panic("oom");
        }
    }
    for (gen.system_libs) |l| {
        if (pkgConfigName(l)) |pc| lib.root_module.linkSystemLibrary(pc, .{ .needed = true });
    }
    return .{ .library = lib, .compiles = compiles.items };
}
