//! Build Skia (vendor/skia-src, pinned rev) as a static library with zig c++.
//! Source groups/defines come from build/gen/skia_sources.zig, generated from
//! skia's own GN build by tools/regen-skia.sh — the same technique skia uses to
//! produce AOSP's Android.bp (gn/gn_to_bp.py). Building it ourselves means the
//! library and every consumer (hwui island) see one config and one toolchain:
//! no more header/.so layout mismatches (SK_HIDE_PATH_EDIT_METHODS et al).
const std = @import("std");
const gen = @import("gen/skia_sources.zig");

pub const Options = struct {
    /// vendor/skia-src
    root: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    /// extra include dirs (framework mode needs cutils/trace.h + log/log.h)
    include_dirs: []const []const u8 = &.{},
};

/// zig pins the target CPU features explicitly, so a per-file `-march=` is
/// ignored: SIMD groups become their own objects with a matching cpu_model.
fn cpuForFlags(flags: []const []const u8) ?*const std.Target.Cpu.Model {
    for (flags) |f| {
        if (std.mem.eql(u8, f, "-march=haswell") or std.mem.eql(u8, f, "-mavx2")) return &std.Target.x86.cpu.haswell;
        if (std.mem.eql(u8, f, "-march=skylake-avx512") or std.mem.eql(u8, f, "-mavx512f")) return &std.Target.x86.cpu.skylake_avx512;
    }
    return null;
}

/// pkg-config names for the system libs skia's GN "libs" refer to.
fn pkgConfigName(lib: []const u8) ?[]const u8 {
    const map = .{
        .{ "fontconfig", "fontconfig" }, .{ "expat", "expat" },
        .{ "freetype", "freetype2" },    .{ "png", "libpng" },
        .{ "EGL", "egl" },               .{ "jpeg", "libjpeg" },
        .{ "GLESv2", "glesv2" },         .{ "webp", "libwebp" },
        .{ "webpdemux", "libwebpdemux" }, .{ "webpmux", "libwebpmux" },
    };
    inline for (map) |m| {
        if (std.mem.eql(u8, lib, m[0])) return m[1];
    }
    return null; // pthread/dl come with libc
}

pub fn build(b: *std.Build, o: Options) *std.Build.Step.Compile {
    const mod = b.createModule(.{
        .target = o.target,
        // never build skia unoptimized: -O0 rasterization is unusable
        .optimize = .ReleaseFast,
        .link_libc = true,
        .link_libcpp = true,
    });
    const lib = b.addLibrary(.{ .name = "skia", .linkage = .static, .root_module = mod });
    mod.addIncludePath(o.root);
    for (o.include_dirs) |d| mod.addIncludePath(.{ .cwd_relative = d });

    var base_flags = std.ArrayList([]const u8).empty;
    base_flags.appendSlice(b.allocator, &.{
        "-std=c++20",             "-fno-exceptions",  "-fno-rtti",
        "-fvisibility=hidden",    "-fvisibility-inlines-hidden",
        "-fstrict-aliasing",      "-ffunction-sections", "-fdata-sections",
        "-Wno-attributes",        "-w",
    }) catch @panic("oom");
    for (gen.defines) |d| base_flags.append(b.allocator, b.fmt("-D{s}", .{d})) catch @panic("oom");

    for (gen.groups) |g| {
        var flags = std.ArrayList([]const u8).empty;
        flags.appendSlice(b.allocator, base_flags.items) catch @panic("oom");
        if (cpuForFlags(g.flags)) |cpu| {
            var q = o.target.query;
            q.cpu_model = .{ .explicit = cpu };
            const simd_mod = b.createModule(.{
                .target = b.resolveTargetQuery(q),
                .optimize = .ReleaseFast,
                .link_libc = true,
                .link_libcpp = true,
            });
            simd_mod.addIncludePath(o.root);
            for (o.include_dirs) |d| simd_mod.addIncludePath(.{ .cwd_relative = d });
            // object name: "//modules/skcms:skcms_TransformHsw" -> "skia_skcms_TransformHsw"
            const short = g.name[(std.mem.lastIndexOfScalar(u8, g.name, ':') orelse 0) + 1 ..];
            const obj = b.addObject(.{ .name = b.fmt("skia_{s}", .{short}), .root_module = simd_mod });
            var cflags: std.ArrayList([]const u8) = .empty;
            for (flags.items) |fl| if (!std.mem.startsWith(u8, fl, "-std=") and !std.mem.eql(u8, fl, "-fno-rtti") and !std.mem.eql(u8, fl, "-fvisibility-inlines-hidden")) cflags.append(b.allocator, fl) catch @panic("oom");
            var cpp_files: std.ArrayList([]const u8) = .empty;
            var c_files: std.ArrayList([]const u8) = .empty;
            for (g.files) |f| {
                if (std.mem.endsWith(u8, f, ".c")) c_files.append(b.allocator, f) catch @panic("oom") else cpp_files.append(b.allocator, f) catch @panic("oom");
            }
            if (cpp_files.items.len > 0) simd_mod.addCSourceFiles(.{ .root = o.root, .files = cpp_files.items, .flags = flags.items });
            if (c_files.items.len > 0) simd_mod.addCSourceFiles(.{ .root = o.root, .files = c_files.items, .flags = cflags.items });
            mod.addObject(obj);
            continue;
        }
        flags.appendSlice(b.allocator, g.flags) catch @panic("oom");
        // skcms is C++ but lives outside src/; C sources need C flags
        var cpp: std.ArrayList([]const u8) = .empty;
        var c: std.ArrayList([]const u8) = .empty;
        for (g.files) |f| {
            if (std.mem.endsWith(u8, f, ".c")) c.append(b.allocator, f) catch @panic("oom") else cpp.append(b.allocator, f) catch @panic("oom");
        }
        if (cpp.items.len > 0) mod.addCSourceFiles(.{
            .root = o.root,
            .files = cpp.items,
            .flags = flags.items,
        });
        if (c.items.len > 0) {
            var cflags = std.ArrayList([]const u8).empty;
            for (flags.items) |fl| if (!std.mem.startsWith(u8, fl, "-std=") and !std.mem.eql(u8, fl, "-fno-rtti") and !std.mem.eql(u8, fl, "-fvisibility-inlines-hidden")) cflags.append(b.allocator, fl) catch @panic("oom");
            mod.addCSourceFiles(.{ .root = o.root, .files = c.items, .flags = cflags.items });
        }
    }
    for (gen.system_libs) |l| {
        if (pkgConfigName(l)) |pc| lib.root_module.linkSystemLibrary(pc, .{ .needed = true });
    }
    return lib;
}
