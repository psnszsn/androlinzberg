const std = @import("std");

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
//   const dex = artzig.d8(b, dep, jar_or_classes, "classes.dex");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const aosp_dep = b.dependency("aosp", .{});
    const aosp = aosp_dep.path("");
    const bionic_translation = b.dependency("bionic_translation", .{}).path("");

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

    const vendor_dir = aosp_dep.builder.root.toString(b.allocator) catch @panic("OOM");
    const out_dir = (b.root.join(b.allocator, "out") catch @panic("OOM")).toString(b.allocator) catch @panic("OOM");
    const root_dir = b.root.toString(b.allocator) catch @panic("OOM");
    // headers Soong's host sysroot provides that a plain glibc doesn't (libcap); from pkg-config
    const extra_cflags: []const []const u8 = blk: {
        var code: u8 = 0;
        const stdout = b.runAllowFail(&.{ "pkg-config", "--cflags-only-I", "libcap" }, &code, .ignore) catch break :blk &.{};
        var list: std.ArrayList([]const u8) = .empty;
        var it = std.mem.tokenizeAny(u8, stdout, " \n");
        while (it.next()) |t| if (std.mem.startsWith(u8, t, "-I")) list.append(b.allocator, b.fmt("-isystem{s}", .{t[2..]})) catch @panic("OOM");
        break :blk list.items;
    };
    const art_step = b.step("gen-art", "Build the whole ART host runtime from build/gen/art.zig with Zig");
    var art_opts: @import("build/gen/art.zig").Options = .{
        .target = target,
        .optimize = optimize,
        .vendor = aosp,
        .out = b.path("out"),
        .vendor_dir = vendor_dir,
        .out_dir = out_dir,
        .root_dir = root_dir,
        .extra_cflags = extra_cflags,
    };
    // genrules ported to Zig (operator_out, mterp, asm_defines, vendored aconfig): no Soong out/ needed
    art_opts.gen_dirs = @import("build/genrules.zig").build(b, .{ .vendor = aosp, .art_opts = art_opts });
    const art_gen = @import("build/gen/art.zig").build(b, art_opts);
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
        .vendor = aosp,
        .target = target,
        .optimize = optimize,
    });
    // consumers (app/) compile framework Java against the boot classpath
    b.addNamedLazyPath("core-all-jar", javalib.class_jars.get("core-all").?);
    const java_step = b.step("java", "Build the boot classpath jars (core-oj, ... ) with the system JDK");
    {
        var it = javalib.jars.iterator();
        while (it.next()) |e| {
            const inst = b.addInstallFileWithDir(e.value_ptr.*, .{ .custom = "java" }, b.fmt("{s}.jar", .{e.key_ptr.*}));
            java_step.dependOn(&inst.step);
        }
    }

    // core-icu4j: d8 of the vendored i18n SDK class jar
    const icu4j_dex = blk: {
        const d8run = b.addSystemCommand(&.{ "java", "-Xmx2048M", "-Dcom.android.tools.r8.emitRecordAnnotationsInDex", "-Dcom.android.tools.r8.emitPermittedSubclassesAnnotationsInDex", "-cp" });
        d8run.addFileArg(aosp.path(b, "prebuilts/r8/r8.jar"));
        d8run.addArgs(&.{ "com.android.tools.r8.D8", "--debug", "--min-api", "31", "--classpath" });
        d8run.addFileArg(javalib.class_jars.get("core-all").?);
        d8run.addArg("--output");
        const dexdir = d8run.addOutputDirectoryArg("core-icu4j-dex");
        d8run.addFileArg(aosp.path(b, "prebuilts/runtime/mainline/i18n/sdk/java/core-icu4j.jar"));
        const mk = b.addSystemCommand(&.{ "jar", "--create", "--file" });
        const out = mk.addOutputFileArg("core-icu4j.jar");
        mk.addArg("-C");
        mk.addDirectoryArg(dexdir);
        mk.addArg(".");
        break :blk out;
    };
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
        .core_icu4j_dex = icu4j_dex,
    });
    b.addNamedLazyPath("art-runtime", runtime);

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
    const dex = d8With(b, b.path("tools/d8.sh"), b.path("dist/art"), classes).path(b, "classes.dex");
    const dex_install = b.addInstallFileWithDir(dex, .bin, "hello-classes.dex");
    example_step.dependOn(&dex_install.step);

    const run = b.addRunArtifact(exe);
    run.addFileArg(dex);
    run.addArg("Hello");
    run.step.dependOn(example_step);
    b.step("run", "Run examples/hello").dependOn(&run.step);
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
pub fn javac(b: *std.Build, sources: []const std.Build.LazyPath) std.Build.LazyPath {
    const cmd = b.addSystemCommand(&.{ "javac", "-source", "17", "-target", "17", "-Xlint:-options", "-d" });
    const out = cmd.addOutputDirectoryArg("classes");
    for (sources) |s| cmd.addFileArg(s);
    return out;
}

/// Run d8 (from the runtime tree, via the system `java`) on a jar or a directory of .class
/// files; returns the output directory containing classes.dex.
pub fn d8(b: *std.Build, dep: *std.Build.Dependency, input: std.Build.LazyPath) std.Build.LazyPath {
    return d8With(b, dep.path("tools/d8.sh"), dep.path("dist/art"), input);
}

pub fn d8With(b: *std.Build, script: std.Build.LazyPath, tree: std.Build.LazyPath, input: std.Build.LazyPath) std.Build.LazyPath {
    const cmd = std.Build.Step.Run.create(b, "d8");
    cmd.addFileArg(script);
    cmd.addFileArg(tree.path(b, "framework/d8.jar"));
    const out_dir = cmd.addOutputDirectoryArg("dex");
    cmd.addDirectoryArg(input);
    return out_dir;
}
