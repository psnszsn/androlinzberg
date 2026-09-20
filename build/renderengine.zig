//! Pinned SurfaceFlinger GL/Ganesh rendering, sharing libgui, GBM and Skia.
const std = @import("std");
const graphics = @import("graphics.zig");
const declarations = @import("generator_sources.zig");
pub const Generated = graphics.Generated;

pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, runner: *std.Build.Step.Compile, aidl_tool: *std.Build.Step.Compile, sysprop: *std.Build.Step.Compile) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    for (declarations.renderengine_flag_packages) |package| {
        for (declarations.aconfig) |decl| {
            if (!std.mem.eql(u8, decl.package, package)) continue;
            const cache = b.addRunArtifact(runner);
            cache.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
            cache.addArgs(&.{ "create-cache", "--package", package, "--container", decl.container, "--default-permission", "read_only", "--allow-read-write", "false" });
            for (decl.declarations) |file| {
                cache.addArg("--declarations");
                cache.addFileArg(aosp.path(b, file));
            }
            cache.addArg("--cache");
            const data = cache.addOutputFileArg("flags.pb");
            const generate_flags = b.addRunArtifact(runner);
            generate_flags.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
            generate_flags.addArgs(&.{ "create-cpp-lib", "--mode", "production", "--cache" });
            generate_flags.addFileArg(data);
            generate_flags.addArg("--out");
            const output = generate_flags.addOutputDirectoryArg("flags");
            _ = headers.addCopyDirectory(output.path(b, "include"), "", .{});
            const filename = flagFilename(b, package);
            _ = sources.addCopyFile(output.path(b, filename), filename);
            break;
        } else @panic("missing RenderEngine aconfig declaration");
    }
    for (declarations.renderengine_ndk_aidl) |file| {
        const run = b.addRunArtifact(aidl_tool);
        run.addArgs(&.{ "--lang=ndk", "--structured", "--stability=vintf" });
        for ([_][]const u8{ "graphics/composer/aidl", "graphics/common/aidl", "common/aidl" }) |root|
            run.addPrefixedDirectoryArg("-I", aosp.path(b, b.fmt("hardware/interfaces/{s}", .{root})));
        run.addArg("-h");
        const h = run.addOutputDirectoryArg("headers");
        run.addArg("-o");
        const s = run.addOutputDirectoryArg("sources");
        run.addFileArg(aosp.path(b, file));
        _ = headers.addCopyDirectory(h, "", .{});
        _ = sources.addCopyDirectory(s, "", .{});
    }
    const props = b.addRunArtifact(sysprop);
    props.addArg("--header-dir");
    const prop_headers = props.addOutputDirectoryArg("headers");
    props.addArg("--public-header-dir");
    _ = props.addOutputDirectoryArg("public");
    props.addArg("--source-dir");
    const prop_sources = props.addOutputDirectoryArg("sources");
    props.addArgs(&.{ "--include-name", "SurfaceFlingerProperties.sysprop.h" });
    props.addFileArg(aosp.path(b, declarations.surfaceflinger_sysprop));
    _ = headers.addCopyDirectory(prop_headers, "", .{});
    _ = sources.addCopyDirectory(prop_sources, "", .{});
    return .{ .headers = headers.getDirectory(), .sources = sources.getDirectory() };
}
fn flagFilename(b: *std.Build, package: []const u8) []const u8 {
    const path = b.fmt("{s}.cc", .{package});
    for (path[0..package.len]) |*ch| if (ch.* == '.') {
        ch.* = '_';
    };
    return path;
}
pub const includes = [_][]const u8{
    "external/skia",                                            "external/skia/linux",                             "external/skia/linux/include/config",
    "external/skia/include/config",                             "external/skia/include/core",                      "external/skia/include/utils",
    "external/skia/include/private",                            "external/skia/include/effects",                   "frameworks/native/libs/renderengine",
    "frameworks/native/libs/renderengine/include",              "frameworks/native/libs/shaders/include",          "frameworks/native/libs/tonemap/include",
    "frameworks/native/services/surfaceflinger/common/include", "frameworks/native/libs/tracing_perfetto/include", "system/server_configurable_flags/libflags/include",
};
pub fn configure(b: *std.Build, mod: *std.Build.Module, aosp: std.Build.LazyPath, gui: Generated, generated: Generated) void {
    mod.addIncludePath(@import("skia.zig").config(b, aosp.path(b, "external/skia")));
    graphics.configure(b, mod, aosp, gui);
    mod.addIncludePath(generated.headers);
    for (includes) |path| mod.addIncludePath(aosp.path(b, path));
}
pub fn cppFlags(b: *std.Build) []const []const u8 {
    var flags: std.ArrayList([]const u8) = .empty;
    flags.appendSlice(b.allocator, &.{ "-std=c++23", "-fno-exceptions", "-fno-rtti", "-fno-sanitize=undefined", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DARTZIG_HOST_GRAPHICS", "-DARTZIG_HOST_THREAD_PRIORITY", "-DEGL_EGLEXT_PROTOTYPES", "-DGL_GLEXT_PROTOTYPES", "-DANDROID_UTILS_CALLSTACK_ENABLED=0", "-DDEBUG_ONLY_CODE=0", "-Wno-deprecated-declarations", "-Wno-deprecated-literal-operator", "-Wno-invalid-specialization", "-Wno-inconsistent-missing-override", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}), "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}), "-D__INTRODUCED_IN(x)=", "-D__INTRODUCED_IN_64(x)=", "-D__INTRODUCED_IN_32(x)=" }) catch @panic("oom");
    for (@import("gen/skia_sources.zig").defines) |define| flags.append(b.allocator, b.fmt("-D{s}", .{define})) catch @panic("oom");
    return flags.toOwnedSlice(b.allocator) catch @panic("oom");
}
pub const render_sources = [_][]const u8{
    "ExternalTexture.cpp",                         "RenderEngine.cpp",                  "threaded/RenderEngineThreaded.cpp",
    "skia/AutoBackendTexture.cpp",                 "skia/Cache.cpp",                    "skia/ColorSpaces.cpp",
    "skia/GLExtensions.cpp",                       "skia/SkiaRenderEngine.cpp",         "skia/SkiaGLRenderEngine.cpp",
    "skia/compat/GaneshBackendTexture.cpp",        "skia/compat/GaneshGpuContext.cpp",  "skia/debug/CaptureTimer.cpp",
    "skia/debug/CommonPool.cpp",                   "skia/debug/SkiaCapture.cpp",        "skia/debug/SkiaMemoryReporter.cpp",
    "skia/filters/BlurFilter.cpp",                 "skia/filters/GainmapFactory.cpp",   "skia/filters/GaussianBlurFilter.cpp",
    "skia/filters/KawaseBlurDualFilter.cpp",       "skia/filters/KawaseBlurFilter.cpp", "skia/filters/LinearEffect.cpp",
    "skia/filters/LutShader.cpp",                  "skia/filters/MouriMap.cpp",         "skia/filters/StretchShaderFactory.cpp",
    "skia/filters/EdgeExtensionShaderFactory.cpp",
};
pub fn build(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: Generated, generated: Generated, gui_lib: *std.Build.Step.Compile, skia: *std.Build.Step.Compile) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, aosp, gui, generated);
    const flags = cppFlags(b);
    mod.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/libs/renderengine"), .files = &render_sources, .flags = flags });
    for ([_][]const u8{
        "frameworks/native/services/surfaceflinger/common/FlagManager.cpp",
        "frameworks/native/libs/shaders/shaders.cpp",
        "frameworks/native/libs/tonemap/tonemap.cpp",
        "system/server_configurable_flags/libflags/server_configurable_flags.cc",
        // LogMessage exposes std::ostream. Keep it with its consumer's static
        // libc++ runtime, as the native island does, instead of exporting its
        // stream across libbinder's separate libc++ locale globals.
        "system/libbase/logging.cpp",
        "system/libbase/threads.cpp",
        "external/skia/src/gpu/android/AHardwareBufferUtils.cpp",
        "external/skia/src/gpu/ganesh/GrAHardwareBufferUtils.cpp",
        "external/skia/src/gpu/ganesh/gl/AHardwareBufferGL.cpp",
        "external/skia/src/gpu/ganesh/vk/AHardwareBufferVk.cpp",
        "external/skia/tools/SkSharingProc.cpp",
    }) |file| mod.addCSourceFile(.{ .file = aosp.path(b, file), .flags = if (std.mem.eql(u8, file, "external/skia/src/gpu/ganesh/gl/AHardwareBufferGL.cpp")) std.mem.concat(b.allocator, []const u8, &.{ flags, &graphics.egl_image_flags }) catch @panic("oom") else flags });
    mod.addCSourceFile(.{ .file = b.path("src/native/graphics/HostTrace.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = b.path("src/native/graphics/HostRenderEngineCapabilities.cpp"), .flags = flags });
    for (declarations.renderengine_flag_packages) |package|
        mod.addCSourceFile(.{ .file = generated.sources.path(b, flagFilename(b, package)), .flags = flags });
    mod.addCSourceFile(.{ .file = generated.sources.path(b, "SurfaceFlingerProperties.sysprop.cpp"), .flags = flags });
    for (declarations.renderengine_ndk_aidl) |file| {
        const start = (std.mem.indexOf(u8, file, "/android/") orelse unreachable) + 1;
        mod.addCSourceFile(.{ .file = generated.sources.path(b, b.fmt("{s}.cpp", .{file[start .. file.len - 5]})), .flags = flags });
    }
    mod.linkLibrary(gui_lib);
    mod.linkLibrary(skia);
    return b.addLibrary(.{ .name = "renderengine_host", .root_module = mod });
}
