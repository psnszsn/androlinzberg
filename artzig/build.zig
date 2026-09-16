const std = @import("std");

pub const SourceSet = @import("build/source_files.zig");
/// Compatibility alias for downstream packages; new build interfaces use SourceSet.
pub const SourceFiles = SourceSet;
pub const systemModules = @import("build/system_modules.zig").build;

// artzig build:
//   zig build dist      — assemble the relocatable runtime tree into zig-out/dist/art
//   zig build example   — build examples/hello (Zig host embedding ART) into zig-out/
//   zig build run       — run it
//
// As a dependency, consumers use:
//   const artzig = @import("artzig");                          // this file's pub decls
//   const dep = b.dependency("artzig", .{ .target = t, .optimize = o });
//   exe.root_module.addImport("art", dep.module("art"));
//   artzig.installRuntime(b, dep, exe);                        // builds + installs ART next to the exe
//   const classes = artzig.javac(b, &.{b.path("Hello.java")});
//   const dex = artzig.d8(b, dep, &.{classes.jar}, .{}).path(b, "classes.dex");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const aosp_dep = b.dependency("aosp", .{});
    const aosp = aosp_dep.path("");
    const bionic_translation = b.dependency("bionic_translation", .{}).path("");
    b.addNamedLazyPath("r8-jar", aosp.path(b, "prebuilts/r8/r8.jar"));

    // --- the `art` module: translated jni.h + src/art.zig
    const jni_tc = b.addTranslateC(.{
        .root_source_file = aosp.path(b, "libnativehelper/include_jni/jni.h"),
        .target = target,
        .optimize = optimize,
    });
    const jni_mod = jni_tc.createModule();
    const art_mod = b.addModule("art", .{
        .root_source_file = b.path("src/art.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    art_mod.addImport("jni_c", jni_mod);

    const icu_native = @import("build/icu.zig").native(b, SourceSet.Root.dependency(b, aosp_dep, ""), target, optimize);
    const art_step = b.step("gen-art", "Build the whole ART host runtime from build/gen/art.zig with Zig");
    var art_opts: @import("build/gen/art.zig").Options = .{
        .target = target,
        .optimize = optimize,
        .vendor = aosp,
        .project = b.path(""),
        .icu = icu_native.stable,
        .host = @import("build/host_toolchain.zig").discover(b),
    };
    // genrules ported to Zig (operator_out, mterp, asm_defines, vendored aconfig): no Soong out/ needed
    art_opts.generated = @import("build/genrules.zig").build(b, .{ .vendor = aosp, .art_opts = art_opts });
    const art_gen = @import("build/gen/art.zig").build(b, art_opts);
    // PaletteWriteCrashThreadStacks consumes LogMessage's std::ostream. Keep
    // original logging with this DSO's static libc++ locale/facet identities,
    // as the platform island and RenderEngine do. The stock version script
    // keeps these implementation symbols private to libartpalette.
    art_gen.libartpalette.root_module.addIncludePath(aosp.path(b, "system/libbase/include"));
    art_gen.libartpalette.root_module.addIncludePath(aosp.path(b, "system/logging/liblog/include"));
    art_gen.libartpalette.root_module.addIncludePath(aosp.path(b, "external/fmtlib/include"));
    art_gen.libartpalette.root_module.addCSourceFiles(.{
        .root = aosp,
        .files = &.{ "system/libbase/logging.cpp", "system/libbase/threads.cpp" },
        .flags = &.{ "-std=gnu++20", "-D_GNU_SOURCE", "-D_FILE_OFFSET_BITS=64", "-D_LARGEFILE64_SOURCE" },
    });
    // Share the existing pre-VM identity authority with original framework JNI.
    // Keep every original export and the original local-symbol restrictions.
    const identity_exports = b.addSystemCommand(&.{
        "python3", "-c",
        \\import pathlib, sys
        \\source = pathlib.Path(sys.argv[1]).read_text()
        \\marker = "  global:" + chr(10)
        \\assert source.count(marker) == 1
        \\exports = ("azk_process_identity_initialize", "azk_process_map_credentials", "azk_process_check_uid_change", "azk_process_check_gid_change")
        \\pathlib.Path(sys.argv[2]).write_text(source.replace(marker, marker + "".join("    " + name + ";" + chr(10) for name in exports), 1))
    });
    identity_exports.addFileArg(aosp.path(b, "libcore/libjavacore.map"));
    art_gen.libjavacore.setVersionScript(identity_exports.addOutputFileArg("libjavacore-host.map"));
    const process_identity = b.addLibrary(.{ .name = "artzig-process-identity", .linkage = .static, .root_module = b.createModule(.{
        .root_source_file = b.path("src/process_identity.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .pic = true,
    }) });
    art_gen.libjavacore.root_module.linkLibrary(process_identity);
    const identity_test = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/process_identity.zig"),
        .target = target,
        .optimize = optimize,
    }) });
    b.addNamedLazyPath("process-identity-test-bin", identity_test.getEmittedBin());
    b.step("process-identity-test", "Verify native identity initialization and immutable getters").dependOn(&b.addRunArtifact(identity_test).step);
    const host_paths = b.addLibrary(.{ .name = "artzig-host-paths", .linkage = .static, .root_module = b.createModule(.{
        .root_source_file = b.path("src/host_paths.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .pic = true,
    }) });
    b.addNamedLazyPath("host-paths-library", host_paths.getEmittedBin());
    b.addNamedLazyPath("host-paths-headers", b.path("src"));
    for ([_]*std.Build.Step.Compile{ art_gen.libjavacore, art_gen.libopenjdk }) |native| {
        native.root_module.linkLibrary(host_paths);
    }
    const gen_step = b.step("gen-libartbase", "Build libartbase.so + deps with Zig");
    gen_step.dependOn(&b.addInstallArtifact(art_gen.libartbase, .{ .dest_dir = .{ .override = .{ .custom = "gen" } } }).step);
    // The runtime is relocatable, so retain only the explicit $ORIGIN rpaths
    // configured by build/gen/art.zig. Zig's default per-library rpaths point
    // back into .zig-cache and break bare dlopen() calls after installation.
    inline for (@typeInfo(@import("build/gen/art.zig").Artifacts).@"struct".field_names) |name| {
        @field(art_gen, name).each_lib_rpath = false;
    }
    // The generated libcrypto target omits BoringSSL's bcm.cc object, leaving
    // libjavacrypto with unresolved EVP_* entry points. The complete archive
    // is already built for libjavacore/libopenjdk; include it here as well.
    art_gen.libjavacrypto.root_module.linkLibrary(art_gen.libcrypto_static_static);
    // These targets each fan out across hundreds of C++ translation units.
    // Give the build runner realistic memory weights so it does not start the
    // largest ART compilations concurrently and drive Clang into SIGSEGV.
    const heavy_art_rss = 16 * 1024 * 1024 * 1024;
    inline for (.{
        art_gen.libart_runtime_static,
        art_gen.libart_compiler_static,
        art_gen.libdex2oat_static_static,
        art_gen.libart,
    }) |artifact| artifact.step.max_rss = heavy_art_rss;
    // Consumers that embed ART can install the freshly built runtime DSO as
    // an overlay while taking data files and the boot image from dist/art.
    b.addNamedLazyPath("libart-so", art_gen.libart.getEmittedBin());
    b.addNamedLazyPath("libjavacrypto-so", art_gen.libjavacrypto.getEmittedBin());
    @import("build/gen/art.zig").installAll(b, art_gen, art_step, "gen-art");

    // --- boot classpath jars built with the system JDK (no Soong)
    const javalib = @import("build/javalib.zig").build(b, .{
        .vendor = SourceSet.Root.dependency(b, aosp_dep, ""),
        .target = target,
        .optimize = optimize,
    });
    // consumers (app/) compile framework Java against the boot classpath
    b.addNamedLazyPath("core-all-jar", javalib.class_jars.get("core-all").?);
    b.addNamedLazyPath("core-all-classes", javalib.core_all_classes);
    b.addNamedLazyPath("conscrypt-classes", javalib.class_jars.get("conscrypt").?);
    const java_step = b.step("java", "Build the boot classpath jars (core-oj, ... ) with the system JDK");
    {
        var it = javalib.jars.iterator();
        while (it.next()) |e| {
            const inst = b.addInstallFileWithDir(e.value_ptr.*, .{ .custom = "java" }, b.fmt("{s}.jar", .{e.key_ptr.*}));
            java_step.dependOn(&inst.step);
        }
    }

    const icu_java = @import("build/icu.zig").java(b, SourceSet.Root.dependency(b, aosp_dep, ""), javalib);
    const icu_jni = @import("build/icu.zig").jni(b, SourceSet.Root.dependency(b, aosp_dep, ""), target, optimize, icu_native, art_gen);
    b.addNamedLazyPath("core-icu4j-classes", icu_java.classes);
    java_step.dependOn(&b.addInstallFileWithDir(icu_java.dex, .{ .custom = "java" }, "core-icu4j.jar").step);
    // bionic_translation: the shim linker/libc for Android native libraries
    const bionic = @import("build/bionic.zig").build(b, .{
        .target = target,
        .optimize = optimize,
        .src = bionic_translation,
    });
    inline for (.{ bionic.dl_bio, bionic.pthread_bio, bionic.c_bio, bionic.stdcpp_bio }) |artifact| {
        artifact.each_lib_rpath = false;
    }
    b.addNamedLazyPath("libdl-bio-so", bionic.dl_bio.getEmittedBin());
    b.addNamedLazyPath("libpthread-bio-so", bionic.pthread_bio.getEmittedBin());
    b.addNamedLazyPath("libc-bio-so", bionic.c_bio.getEmittedBin());
    b.addNamedLazyPath("libstdcpp-bio-so", bionic.stdcpp_bio.getEmittedBin());
    const bionic_loader_test = b.addExecutable(.{ .name = "bionic-loader-test", .root_module = b.createModule(.{
        .root_source_file = b.path("src/bionic_loader_test.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    }) });
    bionic_loader_test.rdynamic = true;
    bionic_loader_test.root_module.linkLibrary(bionic.dl_bio);
    b.addNamedLazyPath("bionic-loader-test-bin", bionic_loader_test.getEmittedBin());
    b.step("bionic-loader-test", "Verify native process handles, global exports and explicit DSO scope")
        .dependOn(&b.addRunArtifact(bionic_loader_test).step);
    const bionic_step = b.step("bionic", "Build bionic_translation (libdl_bio, libc_bio, ...)");
    inline for (.{ bionic.dl_bio, bionic.pthread_bio, bionic.c_bio, bionic.stdcpp_bio }) |a| {
        bionic_step.dependOn(&b.addInstallArtifact(a, .{ .dest_dir = .{ .override = .{ .custom = "dist/art/lib64" } } }).step);
    }

    const dist_step = b.step("dist", "Assemble the relocatable runtime tree (zig-out/dist/art) from Zig-built pieces");
    const runtime = @import("build/dist.zig").build(b, dist_step, .{
        .vendor = aosp,
        .arts = art_gen,
        .bionics = &.{ bionic.dl_bio, bionic.pthread_bio, bionic.c_bio, bionic.stdcpp_bio },
        .jars = &javalib,
        .core_icu4j_dex = icu_java.dex,
        .icu_libraries = &.{ icu_native.common, icu_native.i18n, icu_native.stable, icu_native.android, icu_jni },
    });
    b.addNamedLazyPath("art-runtime", runtime);
    const partition_test = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/data_partition.zig"),
        .target = target,
        .optimize = optimize,
    }) });
    b.addNamedLazyPath("data-partition-test-bin", partition_test.getEmittedBin());
    b.step("data-partition-test", "Verify persistent app storage, session policy and shared ART cache").dependOn(&b.addRunArtifact(partition_test).step);
    const certificate_test = b.addExecutable(.{ .name = "system-certificates-test", .root_module = b.createModule(.{
        .root_source_file = b.path("src/system_certificates_test.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    }) });
    certificate_test.root_module.addImport("jni_c", jni_mod);
    b.addNamedLazyPath("system-certificates-test-bin", certificate_test.getEmittedBin());
    const run_certificates = b.addRunArtifact(certificate_test);
    run_certificates.addDirectoryArg(runtime);
    b.step("system-certificates-test", "Verify native certificate installation and stock standalone ART trust").dependOn(&run_certificates.step);

    // --- example
    const exe = b.addExecutable(.{
        .name = "hello",
        .root_module = b.createModule(.{
            .root_source_file = b.path("examples/hello/main.zig"),
            .target = target,
            .optimize = optimize,
            .imports = &.{.{ .name = "art", .module = art_mod }},
        }),
    });
    const example_step = b.step("example", "Build examples/hello");
    example_step.dependOn(&b.addInstallArtifact(exe, .{}).step);
    installRuntimeFromPath(b, runtime, example_step);

    // javac + d8 the example's Java
    const classes = javac(b, &.{b.path("examples/hello/Hello.java")});
    const dex = d8WithR8(b, aosp.path(b, "prebuilts/r8/r8.jar"), &.{classes.jar}, .{}).path(b, "classes.dex");
    const dex_install = b.addInstallFileWithDir(dex, .bin, "hello-classes.dex");
    example_step.dependOn(&dex_install.step);

    const run = @import("build/command.zig").create(b);
    run.setPathEnvironment("ARTZIG_ROOT", runtime);
    run.addFileArg(exe.getEmittedBin());
    run.addFileArg(dex);
    run.addArg("Hello");
    b.step("run", "Run examples/hello").dependOn(&run.run.step);
}

// ---------------------------------------------------------------------------
// Helpers usable from a consumer's build.zig via @import("artzig").

/// Build and install the dependency's generated runtime to zig-out/bin/art,
/// where `art.locateTree` looks first (next to the executable).
pub fn installRuntime(b: *std.Build, dep: *std.Build.Dependency, step: *std.Build.Step) void {
    installRuntimeFromPath(b, dep.namedLazyPath("art-runtime"), step);
}

pub fn installRuntimeFromPath(b: *std.Build, tree: std.Build.LazyPath, step: *std.Build.Step) void {
    const inst = b.addInstallDirectory(.{
        .source_dir = tree,
        .install_dir = .bin,
        .install_subdir = "art",
    });
    step.dependOn(&inst.step);
}

/// Compile Java sources with the system `javac` into a directory of .class files.
/// Targets Java 17 class files (d8 handles anything ART's libcore can't run natively).
pub const JavaClasses = struct {
    directory: std.Build.LazyPath,
    jar: std.Build.LazyPath,
};

pub fn javac(b: *std.Build, sources: []const std.Build.LazyPath) JavaClasses {
    const cmd = b.addSystemCommand(&.{ "javac", "-source", "17", "-target", "17", "-Xlint:-options", "-d" });
    const directory = cmd.addOutputDirectoryArg("classes");
    for (sources) |s| cmd.addFileArg(s);
    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const output = jar.addOutputFileArg("classes.jar");
    jar.addArg("-C");
    jar.addDirectoryArg(directory);
    jar.addArg(".");
    return .{ .directory = directory, .jar = output };
}

pub const D8Options = struct {
    min_api: u16 = 26,
    libraries: []const std.Build.LazyPath = &.{},
};

/// Run the pinned D8 on explicit jar or class-file inputs. Directory inputs are
/// deliberately not accepted: package compiler output into a jar first so the
/// build graph knows the complete input artifact.
pub fn d8(
    b: *std.Build,
    dep: *std.Build.Dependency,
    inputs: []const std.Build.LazyPath,
    options: D8Options,
) std.Build.LazyPath {
    return d8WithR8(b, dep.namedLazyPath("r8-jar"), inputs, options);
}

fn d8WithR8(
    b: *std.Build,
    r8_jar: std.Build.LazyPath,
    inputs: []const std.Build.LazyPath,
    options: D8Options,
) std.Build.LazyPath {
    const cmd = b.addSystemCommand(&.{ "java", "-cp" });
    cmd.addFileArg(r8_jar);
    cmd.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", b.fmt("{d}", .{options.min_api}) });
    for (options.libraries) |library| {
        cmd.addArg("--lib");
        cmd.addFileArg(library);
    }
    cmd.addArg("--output");
    const out_dir = cmd.addOutputDirectoryArg("dex");
    for (inputs) |input| cmd.addFileArg(input);
    return out_dir;
}
