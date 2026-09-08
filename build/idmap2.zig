//! Pinned libidmap2 and its self-targeting overlay entry points.
const std = @import("std");
const declarations = @import("generator_sources.zig");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    aosp: LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    island: LazyPath,
    binder_cpp: LazyPath,
    protobuf: *std.Build.Step.Compile,
    protoc: *std.Build.Step.Compile,
    host_include_roots: []const LazyPath,
    host_library_roots: []const LazyPath,
};

// frameworks/base/cmds/idmap2/Android.bp: libidmap2 + self_targeting.
const sources = [_][]const u8{
    "libidmap2/BinaryStreamVisitor.cpp",
    "libidmap2/CommandLineOptions.cpp",
    "libidmap2/FabricatedOverlay.cpp",
    "libidmap2/FileUtils.cpp",
    "libidmap2/Idmap.cpp",
    "libidmap2/PolicyUtils.cpp",
    "libidmap2/PrettyPrintVisitor.cpp",
    "libidmap2/RawPrintVisitor.cpp",
    "libidmap2/ResourceContainer.cpp",
    "libidmap2/ResourceMapping.cpp",
    "libidmap2/ResourceUtils.cpp",
    "libidmap2/Result.cpp",
    "libidmap2/SysTrace.cpp",
    "libidmap2/XmlParser.cpp",
    "self_targeting/SelfTargeting.cpp",
};

pub const Result = struct { file: LazyPath, step: *std.Build.Step };

pub fn build(b: *std.Build, o: Options) Result {
    const root = o.aosp.path(b, "frameworks/base/cmds/idmap2");
    const proto = b.addRunArtifact(o.protoc);
    proto.addPrefixedDirectoryArg("-I", root);
    proto.addArg("--cpp_out");
    const generated = proto.addOutputDirectoryArg("idmap2-proto");
    proto.addFileArg(o.aosp.path(b, declarations.idmap2_proto.input.path));
    const mod = b.createModule(.{
        .target = o.target,
        .optimize = o.optimize,
        .link_libc = true,
        .link_libcpp = true,
        .pic = true,
    });
    const library = b.addLibrary(.{ .name = "idmap2-pinned", .linkage = .static, .root_module = mod });
    for ([_][]const u8{
        "frameworks/base/cmds/idmap2",
        "frameworks/base/cmds/idmap2/include",
        "frameworks/native/include",
        "system/incremental_delivery/incfs/util/include",
        "system/core/libutils/binder/include",
        "system/core/libsystem/include",
        "frameworks/base/cmds/idmap2/libidmap2_policies/include",
        "frameworks/base/libs/androidfw/include",
        "system/libbase/include",
        "system/core/libutils/include",
        "system/core/libcutils/include",
        "system/logging/liblog/include",
        "system/libziparchive/include",
        "external/protobuf/src",
        "external/fmtlib/include",
    }) |path| mod.addIncludePath(o.aosp.path(b, path));
    mod.addIncludePath(generated);
    for (o.host_include_roots) |path| mod.addSystemIncludePath(path);
    for (o.host_library_roots) |path| mod.addLibraryPath(path);
    const flags = &.{ "-std=gnu++23", "-fno-rtti", "-fno-exceptions", "-fno-sanitize=undefined", "-DNDEBUG", "-DSTATIC_ANDROIDFW_FOR_TOOLS", "-D__ANDROID_HOST__", "-w", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}) };
    mod.addCSourceFiles(.{ .root = root, .files = &sources, .flags = flags });
    mod.addCSourceFile(.{ .file = generated.path(b, declarations.idmap2_proto.source), .flags = flags });
    // Like the native island, link with a clean environment: the development
    // shell's NIX_LDFLAGS also contains an uninstalled workspace output directory.
    const link = b.addSystemCommand(&.{
        b.graph.zig_exe,            "c++",                           "-shared",             "-fuse-ld=bfd", "-Wl,-z,defs",
        "-Wl,-soname,libidmap2.so", "-Wl,-rpath,$ORIGIN:$ORIGIN/..", "-Wl,--whole-archive",
    });
    link.removeEnvironmentVariable("NIX_LDFLAGS");
    link.addArtifactArg(library);
    link.addArg("-Wl,--no-whole-archive");
    link.addArtifactArg(o.protobuf);
    link.addFileArg(o.island);
    link.addFileArg(o.binder_cpp);
    for (o.host_library_roots) |path| {
        link.addPrefixedDirectoryArg("-L", path);
        link.addPrefixedDirectoryArg("-Wl,-rpath,", path);
    }
    link.addArgs(&.{ "-lz", "-lpthread", "-ldl", "-o" });
    const output = link.addOutputFileArg("libidmap2.so");
    link.setName("link original libidmap2.so");
    return .{ .file = output, .step = &link.step };
}
