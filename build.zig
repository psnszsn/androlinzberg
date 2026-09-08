//! androlinzberg: run an Android APK with a Zig (Skia + Wayland) platform layer
//! on the embedded artzig ART runtime.
//!
//! Steps:
//!   zig build            — build the launcher and ART runtime (zig-out/bin/)
//!   zig build run -- ... — run an APK or Java graphics probe
const std = @import("std");
const Scanner = @import("wayland").Scanner;

fn setApplicationEnvironment(command: @import("build/command.zig").Command, tree: std.Build.LazyPath) void {
    command.setPathEnvironment("ARTZIG_INSTALL_ROOT", tree);
    command.setPathEnvironment("ARTZIG_ROOT", tree.path(command.b, "bin/art"));
    // glibc reads LD_LIBRARY_PATH at process startup. ART later dlopens its
    // built-in JNI libraries by soname, so direct cache-tree executions must
    // expose the emitted runtime's lib64 directory to the child process, ahead
    // of whatever the caller's environment already lists (Mesa for Cage).
    command.prependPathEnvironment("LD_LIBRARY_PATH", tree.path(command.b, "bin/art/lib64"));
}

pub fn build(b: *std.Build) void {
    // The pinned Zig configuration cache does not invalidate dependency roots
    // when only a package hash changes. Track both manifests so source-package
    // updates cannot silently reuse a graph pointing at the previous archive.
    b.dependOnFileContents(b.path("build.zig.zon"));
    b.dependOnFileContents(b.path("artzig/build.zig.zon"));
    const target = b.standardTargetOptions(.{ .default_target = .{ .cpu_model = .baseline } });
    const optimize = b.standardOptimizeOption(.{});
    const host_toolchain = @import("build/host_toolchain.zig").discover(b);

    const build_tools_test = b.step("build-tools-test", "Run build graph support unit tests");
    @import("build/command.zig").addTests(b, build_tools_test);
    const aosp_closure_tests = b.addSystemCommand(&.{ "python3", "-B", "tools/test_aosp_closure.py" });
    build_tools_test.dependOn(&aosp_closure_tests.step);
    const doc_header_tests = b.addSystemCommand(&.{ "python3", "-B", "tools/test_javadoc_headers.py" });
    build_tools_test.dependOn(&doc_header_tests.step);
    const audit_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("tools/build-audit.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    audit_tests.root_module.addImport("generator_sources", b.createModule(.{
        .root_source_file = b.path("build/generator_sources.zig"),
    }));
    build_tools_test.dependOn(&b.addRunArtifact(audit_tests).step);
    const source_set_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("artzig/build/source_files.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    build_tools_test.dependOn(&b.addRunArtifact(source_set_tests).step);
    const jar_entry_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("artzig/build/jar_entries.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    build_tools_test.dependOn(&b.addRunArtifact(jar_entry_tests).step);
    const generator_declaration_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("build/generator_sources.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    build_tools_test.dependOn(&b.addRunArtifact(generator_declaration_tests).step);
    const rewrite_java_package_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("tools/rewrite-java-package.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    build_tools_test.dependOn(&b.addRunArtifact(rewrite_java_package_tests).step);
    const mime_types_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("tools/minimize-mime-types.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    build_tools_test.dependOn(&b.addRunArtifact(mime_types_tests).step);

    // --- zig-wayland: generate protocol bindings
    const scanner = Scanner.create(b, .{});
    scanner.addSystemProtocol("stable/xdg-shell/xdg-shell.xml");
    scanner.addSystemProtocol("stable/viewporter/viewporter.xml");
    scanner.addSystemProtocol("staging/fractional-scale/fractional-scale-v1.xml");
    scanner.addSystemProtocol("stable/linux-dmabuf/linux-dmabuf-v1.xml");
    scanner.addSystemProtocol("stable/tablet/tablet-v2.xml");
    scanner.addSystemProtocol("unstable/pointer-constraints/pointer-constraints-unstable-v1.xml");
    scanner.addSystemProtocol("unstable/relative-pointer/relative-pointer-unstable-v1.xml");
    scanner.addSystemProtocol("staging/cursor-shape/cursor-shape-v1.xml");
    scanner.addCustomProtocol(b.path("src/protocol/wlr-virtual-pointer-unstable-v1.xml"));
    scanner.addCustomProtocol(b.path("src/protocol/virtual-keyboard-unstable-v1.xml"));
    scanner.generate("wl_compositor", 4);
    scanner.generate("wl_shm", 1);
    scanner.generate("wl_seat", 8);
    scanner.generate("wl_output", 2);
    scanner.generate("xdg_wm_base", 2);
    scanner.generate("wl_subcompositor", 1);
    scanner.generate("wp_viewporter", 1);
    scanner.generate("wp_fractional_scale_manager_v1", 1);
    scanner.generate("zwp_linux_dmabuf_v1", 4);
    scanner.generate("zwp_pointer_constraints_v1", 1);
    scanner.generate("zwp_relative_pointer_manager_v1", 1);
    scanner.generate("wp_cursor_shape_manager_v1", 1);
    scanner.generate("zwlr_virtual_pointer_manager_v1", 2);
    scanner.generate("zwp_virtual_keyboard_manager_v1", 1);
    // --- embedded artzig runtime (ART) module
    // The root AOSP archive supplies framework/native/Skia/generator sources. Its
    // current Android 16 r1 local URL is content-addressed by build.zig.zon's
    // package hash, which changes whenever the selected source closure changes.
    const aosp_dep = b.dependency("aosp", .{});
    const aosp = aosp_dep.path("");
    const artzig_dep = b.dependency("artzig", .{ .target = target, .optimize = optimize });
    const art_mod = artzig_dep.module("art");
    const build_audit = b.addExecutable(.{
        .name = "build-audit",
        .root_module = b.createModule(.{
            .root_source_file = b.path("tools/build-audit.zig"),
            .target = b.graph.host,
            .optimize = .Debug,
        }),
    });
    build_audit.root_module.addImport("generator_sources", b.createModule(.{
        .root_source_file = b.path("build/generator_sources.zig"),
    }));
    const SourceSet = @import("artzig").SourceSet;
    var project_jvm = SourceSet.Collector.init(b);
    inline for (.{ "artzig/src", "artzig/examples", "src", "tests", "testdata", "tools" }) |root| {
        project_jvm.addTree(SourceSet.Root.project(b, root), .{ .extensions = &.{ ".java", ".kt" } });
    }
    const project_jvm_files = project_jvm.select(null);
    const jvm_source_audit = b.addRunArtifact(build_audit);
    jvm_source_audit.addArg("jvm-source");
    jvm_source_audit.addDirectoryArg(b.path(""));
    jvm_source_audit.addDirectoryArg(aosp);
    jvm_source_audit.addFileArg(b.path("tools/stub-audit.txt"));
    for (project_jvm_files.items) |file| jvm_source_audit.addFileArg(file.path);
    project_jvm_files.addInputs(jvm_source_audit);
    jvm_source_audit.setName("audit JVM source provenance");

    var project_cpp = SourceSet.Collector.init(b);
    project_cpp.addTree(SourceSet.Root.project(b, "src"), .{ .extensions = &.{ ".cpp", ".cc", ".cxx" } });
    const project_cpp_files = project_cpp.select(null);
    const cpp_audit = b.addRunArtifact(build_audit);
    cpp_audit.addArg("cpp-boundary");
    for (project_cpp_files.items) |file| cpp_audit.addFileArg(file.path);
    project_cpp_files.addInputs(cpp_audit);
    cpp_audit.setName("audit application C++ boundary");
    const cpp_audit_step = b.step("cpp-audit", "Verify that C++ is limited to HWUI/Skia/AOSP ABI bridges");
    cpp_audit_step.dependOn(&cpp_audit.step);
    b.getInstallStep().dependOn(&cpp_audit.step);

    var graph_sources = SourceSet.Collector.init(b);
    graph_sources.addFile(SourceSet.Root.project(b, ""), "build.zig");
    graph_sources.addFile(SourceSet.Root.project(b, "artzig"), "build.zig");
    graph_sources.addTree(SourceSet.Root.project(b, "build"), .{ .extensions = &.{".zig"} });
    graph_sources.addTree(SourceSet.Root.project(b, "artzig/build"), .{ .extensions = &.{".zig"} });
    const graph_files = graph_sources.select(".zig");
    const graph_audit = b.addRunArtifact(build_audit);
    graph_audit.addArg("graph");
    for (graph_files.items) |file| graph_audit.addFileArg(file.path);
    graph_files.addInputs(graph_audit);
    graph_audit.setName("audit LazyPath build graph invariants");
    const graph_audit_step = b.step("graph-audit", "Reject script, install-prefix, and path-identity regressions");
    graph_audit_step.dependOn(&graph_audit.step);
    build_tools_test.dependOn(&graph_audit.step);
    b.getInstallStep().dependOn(&graph_audit.step);
    const libart_overlay = artzig_dep.namedLazyPath("libart-so");
    const libjavacrypto_overlay = artzig_dep.namedLazyPath("libjavacrypto-so");
    const bionic_overlays = .{
        .{ artzig_dep.namedLazyPath("libdl-bio-so"), "libdl_bio.so" },
        .{ artzig_dep.namedLazyPath("libpthread-bio-so"), "libpthread_bio.so" },
        .{ artzig_dep.namedLazyPath("libc-bio-so"), "libc_bio.so" },
        .{ artzig_dep.namedLazyPath("libstdcpp-bio-so"), "libstdc++_bio.so" },
    };
    const binder_broker = b.addExecutable(.{
        .name = "azk-binder-broker",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/broker_main.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });

    // --- Skia: static, built with zig c++ from the pinned AOSP tree.
    const skia_lib = @import("build/skia.zig").build(b, .{
        .root = aosp.path(b, "external/skia"),
        .target = target,
        .host_include_roots = host_toolchain.skia.roots,
        .include_dirs = &.{
            aosp.path(b, "system/core/libcutils/include"),
            aosp.path(b, "system/logging/liblog/include"),
        },
    });
    b.step("skia", "Build static Skia").dependOn(&b.addInstallArtifact(skia_lib, .{}).step);

    // --- protobuf: static, built with zig c++ from the packaged AOSP source, so the
    // host codegen tools (sysprop_java/javastream) link it with one C++ stdlib.
    const protobuf = @import("build/protobuf.zig").build(b, .{
        .root = aosp.path(b, "external/protobuf"),
        .target = target,
        .with_protoc = true, // libprotoc: needed by the protoc-gen-javastream plugin.
    });
    b.step("protobuf", "Build static protobuf from packaged AOSP sources")
        .dependOn(&b.addInstallArtifact(protobuf.library, .{}).step);
    b.step("protoc", "Build protoc from packaged AOSP sources")
        .dependOn(&b.addInstallArtifact(protobuf.protoc, .{}).step);

    const perfetto = @import("build/perfetto.zig").build(b, .{
        .root = aosp.path(b, "external/perfetto"),
        .protobuf_root = aosp.path(b, "external/protobuf"),
        .protobuf = protobuf.library,
        .protoc = protobuf.protoc,
        .target = target,
        .optimize = .ReleaseFast,
    });
    b.step("perfetto", "Build the stock Linux Perfetto C SDK and generators")
        .dependOn(&perfetto.sdk.step);

    // --- sysprop_java: the AOSP sysprop host generator, built from packaged
    // source against libprotobuf (no prebuilt exists). Generates android.sysprop.*.
    const sysprop_tool = @import("build/sysprop.zig").build(b, .{
        .vendor = aosp,
        .target = target,
        .protobuf = protobuf.library,
        .protoc = protobuf.protoc,
    });
    b.step("sysprop-tool", "Build the sysprop_java host generator")
        .dependOn(&b.addInstallArtifact(sysprop_tool, .{}).step);
    const sysprop_cpp_tool = @import("build/sysprop.zig").build(b, .{
        .language = .cpp,
        .vendor = aosp,
        .target = target,
        .protobuf = protobuf.library,
        .protoc = protobuf.protoc,
    });

    // --- protoc-gen-javastream: the AOSP protoc plugin that emits the framework
    // *Proto stream classes, built from packaged source against libprotoc.
    const javastream_tool = @import("build/javastream.zig").build(b, .{
        .root = aosp.path(b, "frameworks/base/tools/streaming_proto"),
        .protobuf_src = aosp.path(b, "external/protobuf"),
        .target = target,
        .protobuf = protobuf.library,
    });
    b.step("javastream-tool", "Build the protoc-gen-javastream plugin")
        .dependOn(&b.addInstallArtifact(javastream_tool, .{}).step);

    // --- protoc-gen-javanano: nano message classes (MetricsProto), built from
    // the packaged protobuf fork's javanano generator.
    const javanano_tool = @import("build/javanano.zig").build(b, .{
        .root = aosp.path(b, "external/protobuf"),
        .target = target,
        .protobuf = protobuf.library,
    });
    b.step("javanano-tool", "Build the protoc-gen-javanano plugin")
        .dependOn(&b.addInstallArtifact(javanano_tool, .{}).step);

    const generated = @import("build/generators.zig").build(b, .{
        .aosp = aosp_dep,
        .protobuf = protobuf.library,
        .protoc = protobuf.protoc,
        .sysprop_tool = sysprop_tool,
        .sysprop_cpp_tool = sysprop_cpp_tool,
        .javastream_tool = javastream_tool,
        .javanano_tool = javanano_tool,
    });

    const wayland_mod = b.createModule(.{
        .root_source_file = scanner.result,
        .target = target,
        .optimize = optimize,
    });

    // --- libbinder_ndk.so: the NDK binder C ABI in Zig over the AZKB protocol.
    // Installed under lib/art so the bionic linker resolves the soname; the
    // .cfg above maps `libbinder_ndk.so` to it.
    const binder_ndk = b.addLibrary(.{
        .name = "binder_ndk",
        .linkage = .dynamic,
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/ndk.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            // JNI type bindings for the true-sharing bridge (compile-time only;
            // no runtime dependency in a standalone, non-JVM process).
            .imports = &.{.{ .name = "art", .module = art_mod }},
        }),
    });
    // The framework registrar also provides EGL14 JNI when ART loads this
    // library independently (including its connectivity JNI alias).
    binder_ndk.root_module.linkSystemLibrary("egl", .{ .needed = true });
    binder_ndk.root_module.linkSystemLibrary("gbm", .{ .needed = true });
    b.step("binder-ndk", "Build libbinder_ndk.so (native NDK binder ABI)")
        .dependOn(&binder_ndk.step);

    const binder_cpp = @import("build/binder_cpp.zig").build(b, target, optimize, aosp, binder_ndk);
    b.step("binder-cpp", "Build the hosted C++ libbinder facade").dependOn(&binder_cpp.step);

    const facade_test = b.addExecutable(.{ .name = "binder-facade-test", .root_module = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
    }) });
    facade_test.root_module.addIncludePath(b.path("src/native/binder/include"));
    for (@import("build/binder_cpp.zig").includes) |path| facade_test.root_module.addIncludePath(aosp.path(b, path));
    facade_test.root_module.addIncludePath(generated.native.binder_graphics_headers);
    const facade_flags = &.{ "-std=gnu++23", "-fno-rtti", "-fno-exceptions", "-UNDEBUG" };
    facade_test.root_module.addCSourceFile(.{ .file = b.path("tests/native/BinderFacadeTest.cpp"), .flags = facade_flags });
    facade_test.root_module.addCSourceFile(.{ .file = generated.native.binder_graphics_sources.path(b, "android/gui/IFpsListener.cpp"), .flags = facade_flags });
    facade_test.root_module.linkLibrary(binder_cpp);
    facade_test.root_module.linkLibrary(binder_ndk);
    const facade_run = b.addSystemCommand(&.{"python3"});
    facade_run.addFileArg(b.path("tools/binder-facade-test.py"));
    facade_run.addArg("--probe");
    facade_run.addArtifactArg(facade_test);
    facade_run.addArg("--library");
    facade_run.addArtifactArg(binder_cpp);
    facade_run.addArg("--library");
    facade_run.addArtifactArg(binder_ndk);
    facade_run.addArg("--broker");
    facade_run.addArtifactArg(binder_broker);
    facade_run.addArgs(&.{ "--output", b.pathResolve(&.{"zig-out/test-results/binder-facade"}) });
    facade_run.has_side_effects = true;
    b.step("binder-cpp-test", "Test generated graphics Binder and shared Parcel ownership").dependOn(&facade_run.step);

    // Stock StatsLog loads libstats_jni itself. The host has no statsd daemon,
    // so this tiny JNI library supplies AOSP's Ravenwood-equivalent no-op sink.
    const stats_jni = b.addLibrary(.{
        .name = "stats_jni",
        .linkage = .dynamic,
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/platform/stats_log.zig"),
            .target = target,
            .optimize = optimize,
            .imports = &.{.{ .name = "art", .module = art_mod }},
        }),
    });
    b.step("stats-jni", "Build the hosted StatsLog JNI sink")
        .dependOn(&stats_jni.step);

    // --- native island: hwui + JNI + our shims, linked against static Skia
    const island = @import("build/island.zig").build(b, .{
        .target = target,
        .optimize = optimize,
        .skia = skia_lib,
        .binder_ndk = binder_ndk,
        .binder_cpp = binder_cpp,
        .perfetto = perfetto,
        .art = art_mod,
        .aidl_header_roots = generated.native.aidl_header_roots,
        .aidl_source_roots = generated.native.aidl_source_roots,
        .hidl_header_roots = generated.native.hidl_header_roots,
        .hidl_sources = generated.native.hidl_sources,
        .aconfig_header_roots = generated.native.aconfig_header_roots,
        .incremental_sysprop_headers = generated.native.incremental_sysprop_headers,
        .incremental_sysprop_source = generated.native.incremental_sysprop_source,
        .host_include_roots = host_toolchain.island.include_roots,
        .host_library_roots = host_toolchain.island.library_roots,
        .host_libraries = host_toolchain.island.libraries,
        .host_link_flags = host_toolchain.island.link_flags,
        .aosp = aosp,
    });
    b.step("island", "Build libandroid_platform.so").dependOn(island.step);
    const idmap2 = @import("build/idmap2.zig").build(b, .{
        .aosp = aosp,
        .target = target,
        .optimize = optimize,
        .island = island.file,
        .binder_cpp = binder_cpp.getEmittedBin(),
        .protobuf = protobuf.library,
        .protoc = protobuf.protoc,
        .host_include_roots = host_toolchain.island.include_roots,
        .host_library_roots = host_toolchain.island.library_roots,
    });
    b.step("idmap2", "Build original self-targeting overlay compiler").dependOn(idmap2.step);

    // --- framework JVM jar (Java + Kotlin), framework-res.apk, generated Java
    const java = @import("build/java.zig").build(b, .{
        .artzig = artzig_dep,
        .aosp = aosp_dep,
        .generated = generated,
        .source_audit = &jvm_source_audit.step,
    });
    const testkit = @import("build/testkit.zig").build(b, aosp_dep);
    const testkit_jars = testkit.jars.all();
    const testkit_bundle = b.addWriteFiles();
    inline for (.{
        .{ testkit.jars.testkit, "azk-test/testkit.jar" },
        .{ testkit.jars.tests, "azk-test/tests.jar" },
        .{ testkit.jars.junit_hamcrest, "azk-test/junit-hamcrest.jar" },
        .{ testkit.jars.gson, "azk-test/gson.jar" },
        .{ testkit.jars.kotlin_stdlib, "azk-test/kotlin-stdlib.jar" },
        .{ testkit.jars.kotlin_stdlib_jdk7, "azk-test/kotlin-stdlib-jdk7.jar" },
        .{ testkit.jars.kotlin_stdlib_jdk8, "azk-test/kotlin-stdlib-jdk8.jar" },
        .{ testkit.jars.kotlin_test, "azk-test/kotlin-test.jar" },
        .{ testkit.jars.kotlin_test_junit, "azk-test/kotlin-test-junit.jar" },
    }) |entry| {
        b.getInstallStep().dependOn(&b.addInstallFileWithDir(entry[0], .lib, entry[1]).step);
        _ = testkit_bundle.addCopyFile(entry[0], std.fs.path.basename(entry[1]));
    }
    b.installBinFile("tools/azk-test", "azk-test");

    // ABI-conformance check: the emitted .so must export every A* symbol the
    // prebuilt libbinder_ndk.so does, so unmodified NDK/AIDL clients resolve.
    const ndk_abi_check = b.addRunArtifact(build_audit);
    ndk_abi_check.addArg("ndk-abi");
    ndk_abi_check.addFileArg(binder_ndk.getEmittedBin());
    ndk_abi_check.addFileArg(aosp.path(b, "prebuilts/runtime/mainline/platform/sdk/x86_64/lib/libbinder_ndk.so"));
    b.step("ndk-abi", "Check libbinder_ndk exports the full prebuilt ABI").dependOn(&ndk_abi_check.step);

    // Native binder self-test: links the ABI and drives it against a broker.
    const ndk_test = b.addExecutable(.{
        .name = "azk-ndk-test",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/ndk_test.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    ndk_test.root_module.linkLibrary(binder_ndk);
    const ndk_test_run = b.addRunArtifact(ndk_test);
    ndk_test_run.addFileArg(binder_broker.getEmittedBin());
    b.step("ndk-test", "Run the native binder ABI self-test").dependOn(&ndk_test_run.step);

    // Cross-language system-test client: links the ABI and, connecting to the
    // shared session broker via inherited env, calls a Java-registered service.
    // Installed to bin so the system-test driver can spawn it as a peer.
    const ndk_system_client = b.addExecutable(.{
        .name = "azk-ndk-system-client",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/ndk_system_client.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    ndk_system_client.root_module.linkLibrary(binder_ndk);
    b.getInstallStep().dependOn(&b.addInstallArtifact(ndk_system_client, .{}).step);

    // --- artzig runtime (ART) module

    // --- launcher exe
    const exe = b.addExecutable(.{
        .name = "androlinzberg",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{
                .{ .name = "wayland", .module = wayland_mod },
                .{ .name = "art", .module = art_mod },
            },
        }),
    });
    exe.root_module.linkSystemLibrary("wayland-client", .{ .needed = true });
    exe.root_module.linkSystemLibrary("xkbcommon", .{ .needed = true });
    exe.root_module.linkSystemLibrary("fontconfig", .{ .needed = true }); // platform/fonts.zig

    // --- azk_codec: MediaCodec backend over system FFmpeg. The system build
    // includes VA-API and DRM hwcontexts, which the minimal vendored build does
    // not. Our C-ABI remains compiled into the executable and is resolved by
    // the native island with dlsym(RTLD_DEFAULT).
    const av_tc = b.addTranslateC(.{
        .root_source_file = b.path("src/codec/azk_ffmpeg.h"),
        .target = target,
        .optimize = optimize,
    });
    for (host_toolchain.codec.roots) |root| av_tc.addSystemIncludePath(root);
    av_tc.addIncludePath(b.path("src/codec"));
    const codec_mod = b.createModule(.{
        .root_source_file = b.path("src/codec/azk_codec.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    codec_mod.addImport("av", av_tc.createModule());
    codec_mod.addIncludePath(b.path("src/codec"));
    codec_mod.addCSourceFile(.{
        .file = b.path("src/codec/azk_vaapi.c"),
        .flags = &.{"-std=c11"},
    });
    inline for (.{ "libavcodec", "libavutil" }) |lib| {
        codec_mod.linkSystemLibrary(lib, .{ .needed = true, .use_pkg_config = .force });
    }
    codec_mod.linkSystemLibrary("libva", .{ .needed = true, .use_pkg_config = .force });
    exe.root_module.addImport("azk_codec", codec_mod);
    const codec_jni_mod = b.createModule(.{
        .root_source_file = b.path("src/codec/jni.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{
            .{ .name = "art", .module = art_mod },
            .{ .name = "azk_codec", .module = codec_mod },
        },
    });
    const pipewire_tc = b.addTranslateC(.{
        .root_source_file = b.path("src/platform/azk_pipewire.h"),
        .target = target,
        .optimize = optimize,
    });
    pipewire_tc.addIncludePath(b.path("src/platform"));
    const audio_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/audio.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{
            .{ .name = "art", .module = art_mod },
            .{ .name = "pipewire", .module = pipewire_tc.createModule() },
        },
    });
    audio_mod.addIncludePath(b.path("src/platform"));
    audio_mod.addCSourceFile(.{
        .file = b.path("src/platform/azk_pipewire.c"),
        .flags = &.{ "-std=c11", "-D_GNU_SOURCE" },
    });
    for (host_toolchain.pipewire.roots) |root| audio_mod.addSystemIncludePath(root);
    audio_mod.linkSystemLibrary("pipewire-0.3", .{ .needed = true, .use_pkg_config = .no });
    const event_fd_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/event_fd.zig"),
        .target = target,
        .optimize = optimize,
    });
    exe.root_module.addImport("event_fd", event_fd_mod);
    const host_renderer_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/host_renderer.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{
            .{ .name = "art", .module = art_mod },
            .{ .name = "event_fd", .module = event_fd_mod },
        },
    });
    const message_queue_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/message_queue.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{
            .{ .name = "art", .module = art_mod },
            .{ .name = "event_fd", .module = event_fd_mod },
        },
    });
    const apk_optimizer_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/apk_optimizer.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{.{ .name = "event_fd", .module = event_fd_mod }},
    });
    const host_natives_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/host_natives.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{
            .{ .name = "art", .module = art_mod },
            .{ .name = "audio", .module = audio_mod },
            .{ .name = "codec_jni", .module = codec_jni_mod },
            .{ .name = "host_renderer", .module = host_renderer_mod },
            .{ .name = "message_queue", .module = message_queue_mod },
        },
    });
    exe.root_module.addImport("host_natives", host_natives_mod);
    exe.root_module.addImport("apk_optimizer", apk_optimizer_mod);
    exe.rdynamic = true;

    const audio_tests = b.addTest(.{ .root_module = audio_mod });
    const run_audio_tests = b.addRunArtifact(audio_tests);
    const wayland_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/platform/wayland.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{.{ .name = "wayland", .module = wayland_mod }},
        }),
    });
    wayland_tests.root_module.linkSystemLibrary("wayland-client", .{ .needed = true });
    wayland_tests.root_module.linkSystemLibrary("xkbcommon", .{ .needed = true });
    const run_wayland_tests = b.addRunArtifact(wayland_tests);
    const codec_jni_check = b.addTest(.{ .root_module = codec_jni_mod });
    const host_renderer_check = b.addTest(.{ .root_module = host_renderer_mod });
    const apk_optimizer_check = b.addTest(.{ .root_module = apk_optimizer_mod });
    const run_apk_optimizer_tests = b.addRunArtifact(apk_optimizer_check);
    const dexopt_transaction_test = b.addSystemCommand(&.{ "bash", "tools/test-dexopt-apk.sh" });
    dexopt_transaction_test.addFileInput(b.path("tools/test-dexopt-apk.sh"));
    dexopt_transaction_test.addFileInput(b.path("tools/dexopt-apk.sh"));
    dexopt_transaction_test.addFileInput(b.path("testdata/dexopt/fake-dex2oat64"));
    const test_control_check = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/test_control.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{.{ .name = "art", .module = art_mod }},
        }),
    });
    const run_test_control = b.addRunArtifact(test_control_check);
    const platform_abi_test_mod = b.createModule(.{
        .root_source_file = b.path("src/native/platform_abi.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{.{ .name = "art", .module = art_mod }},
    });
    const platform_abi_check = b.addObject(.{ .name = "platform-abi-check", .root_module = platform_abi_test_mod });
    const launcher_check = b.addObject(.{ .name = "launcher-check", .root_module = exe.root_module });
    // --- hosted Binder broker + protocol tests
    const binder_broker_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/broker_test.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    const run_binder_broker_tests = b.addRunArtifact(binder_broker_tests);
    b.step("binder-broker-test", "Run broker protocol unit tests").dependOn(&run_binder_broker_tests.step);
    // Loaded in the provider class loader so stock JNI_OnLoad resolves its classes.
    const webview_support = b.addLibrary(.{
        .name = "webviewchromium_plat_support",
        .linkage = .dynamic,
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/platform/webview_support.zig"),
            .target = target,
            .optimize = optimize,
            .imports = &.{.{ .name = "art", .module = art_mod }},
        }),
    });
    webview_support.root_module.addObjectFile(island.file);
    const egl_support = b.addLibrary(.{
        .name = "azk_egl",
        .linkage = .dynamic,
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/platform/egl_support.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    egl_support.root_module.addObjectFile(island.file);
    const application_tree = @import("build/application.zig").build(b, .{
        .launcher = exe.getEmittedBin(),
        .broker = binder_broker.getEmittedBin(),
        .island = island.file,
        .idmap2 = idmap2.file,
        .stats_jni = stats_jni.getEmittedBin(),
        .webview_support = webview_support.getEmittedBin(),
        .egl_support = egl_support.getEmittedBin(),
        .binder_ndk = binder_ndk.getEmittedBin(),
        .binder_cpp = binder_cpp.getEmittedBin(),
        .art_runtime = artzig_dep.namedLazyPath("art-runtime"),
        .libart = libart_overlay,
        .libjavacrypto = libjavacrypto_overlay,
        .bionic = .{
            .{ .file = bionic_overlays[0][0], .name = bionic_overlays[0][1] },
            .{ .file = bionic_overlays[1][0], .name = bionic_overlays[1][1] },
            .{ .file = bionic_overlays[2][0], .name = bionic_overlays[2][1] },
            .{ .file = bionic_overlays[3][0], .name = bionic_overlays[3][1] },
        },
        .framework_jar = java.classpath_jar,
        .framework_compat_config = java.compat_config,
        .framework_app_context = java.app_context_jar,
        .framework_odex = java.framework_odex,
        .framework_vdex = java.framework_vdex,
        .framework_resources = java.resources.apk,
        .download_provider = java.download_provider,
        .webview_provider = aosp.path(b, "external/chromium-webview/133.0.6943.137/x86_64/webview.apk"),
    });
    b.getInstallStep().dependOn(&b.addInstallDirectory(.{
        .source_dir = application_tree,
        .install_dir = .prefix,
        .install_subdir = "",
    }).step);

    const binder_test_driver = b.addExecutable(.{
        .name = "azk-binder-test",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/binder_test.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    const binder_test_run = @import("build/command.zig").create(b);
    setApplicationEnvironment(binder_test_run, application_tree);
    binder_test_run.addFileArg(binder_test_driver.getEmittedBin());
    binder_test_run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    binder_test_run.addFileArg(application_tree.path(b, "bin/azk-binder-broker"));
    binder_test_run.run.setName("run hosted Binder tests");
    b.step("binder-test", "Run multi-process hosted Binder tests through the broker").dependOn(&binder_test_run.run.step);

    const system_test_driver = b.addExecutable(.{
        .name = "azk-system-test",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/system_test.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    const system_test_run = @import("build/command.zig").create(b);
    setApplicationEnvironment(system_test_run, application_tree);
    system_test_run.addFileArg(system_test_driver.getEmittedBin());
    system_test_run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    system_test_run.addFileArg(application_tree.path(b, "bin/azk-binder-broker"));
    system_test_run.addFileArg(java.fixtures.test_a);
    system_test_run.addFileArg(java.fixtures.test_b);
    system_test_run.addFileArg(ndk_system_client.getEmittedBin());
    system_test_run.addFileArg(java.fixtures.identity_a);
    system_test_run.addFileArg(java.fixtures.identity_b);
    system_test_run.run.setName("run hosted system-service tests");
    b.step("system-test", "Run multi-process hosted system-service tests").dependOn(&system_test_run.run.step);

    const process_memory_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/binder/process_memory.zig"),
        .target = target,
        .optimize = optimize,
    }) });
    const run_process_memory_tests = b.addRunArtifact(process_memory_tests);
    b.step("process-memory-test", "Test Android process-memory accounting").dependOn(&run_process_memory_tests.step);
    const native_test_step = b.step("native-test", "Build Zig JNI adapters and run native unit tests");
    const media_format_tests = b.addExecutable(.{
        .name = "azk-media-format-test",
        .root_module = b.createModule(.{
            .root_source_file = b.path("tests/native/media_format.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    const media_format_test_run = @import("build/command.zig").create(b);
    setApplicationEnvironment(media_format_test_run, application_tree);
    media_format_test_run.addFileArg(media_format_tests.getEmittedBin());
    media_format_test_run.addFileArg(application_tree.path(b, "lib/libandroid_platform.so"));
    media_format_test_run.run.setName("test stock media NDK format ABI");
    b.step("media-format-test", "Test stock media NDK typed metadata and ownership").dependOn(&media_format_test_run.run.step);
    native_test_step.dependOn(&media_format_test_run.run.step);

    const egl_backend_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/binder/egl_backend.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    }) });
    for ([_][]const u8{ "egl", "gbm", "glesv2" }) |lib| egl_backend_tests.root_module.linkSystemLibrary(lib, .{ .needed = true });
    const run_egl_backend_tests = b.addRunArtifact(egl_backend_tests);
    b.step("egl-test", "Test EGL offscreen rendering and surface lifetime").dependOn(&run_egl_backend_tests.step);
    native_test_step.dependOn(&run_egl_backend_tests.step);
    native_test_step.dependOn(&run_process_memory_tests.step);
    native_test_step.dependOn(&run_audio_tests.step);
    native_test_step.dependOn(&run_wayland_tests.step);
    native_test_step.dependOn(&codec_jni_check.step);
    native_test_step.dependOn(&host_renderer_check.step);
    native_test_step.dependOn(&run_apk_optimizer_tests.step);
    native_test_step.dependOn(&dexopt_transaction_test.step);
    native_test_step.dependOn(&run_test_control.step);
    native_test_step.dependOn(&run_binder_broker_tests.step);
    native_test_step.dependOn(&ndk_abi_check.step);
    native_test_step.dependOn(&platform_abi_check.step);
    native_test_step.dependOn(&launcher_check.step);
    native_test_step.dependOn(&cpp_audit.step);

    const jvm_test_run = @import("build/command.zig").create(b);
    setApplicationEnvironment(jvm_test_run, application_tree);
    jvm_test_run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    jvm_test_run.addArg("--javatest");
    jvm_test_run.addFileArg(java.kotlin_shadow_fixture);
    jvm_test_run.setEnvironment("AZK_TEST_CLASS", "dev.artzig.KotlinClassLoaderSmoke");
    // Hermetic: a nonexistent WAYLAND_DISPLAY keeps the session's clipboard
    // monitor off the developer's real desktop clipboard (empty falls back
    // to wayland-0).
    jvm_test_run.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    jvm_test_run.run.setName("run JVM integration tests");
    const jvm_test_step = b.step("jvm-test", "Run JVM and Kotlin class-loading integration tests");
    jvm_test_step.dependOn(&jvm_test_run.run.step);
    const framework_module_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(framework_module_smoke, application_tree);
    framework_module_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    framework_module_smoke.addArg("--javatest");
    framework_module_smoke.setEnvironment(
        "AZK_TEST_CLASS",
        "dev.artzig.FrameworkModuleSmoke",
    );
    // Fixture APKs (installed server-side by the permission-grant test).
    framework_module_smoke.addFileArg(java.fixtures.permissions);
    framework_module_smoke.addFileArg(java.fixtures.example);
    // Hermetic: a nonexistent WAYLAND_DISPLAY keeps the session's clipboard
    // monitor off the developer's real desktop clipboard (empty falls back
    // to wayland-0).
    framework_module_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    framework_module_smoke.run.setName("run hosted framework module tests");
    jvm_test_step.dependOn(&framework_module_smoke.run.step);
    const webview_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(webview_smoke, application_tree);
    webview_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    webview_smoke.addArg("--javatest");
    webview_smoke.setEnvironment("AZK_TEST_CLASS", "dev.artzig.StockWebViewSmoke");
    webview_smoke.run.addArgs(&.{ "--env-path", "AZK_TEST_APK" });
    webview_smoke.run.addFileArg(java.fixtures.example);
    webview_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    b.step("webview-test", "Run stock WebView against the bundled Android Chromium provider").dependOn(&webview_smoke.run.step);
    const webview_ui = @import("build/command.zig").create(b);
    setApplicationEnvironment(webview_ui, application_tree);
    webview_ui.setEnvironment("WLR_RENDERER", "gles2");
    webview_ui.setPathEnvironment("AZK_TESTKIT_ROOT", testkit_bundle.getDirectory());
    webview_ui.addArg("bash");
    webview_ui.addFileArg(b.path("tools/shot.sh"));
    webview_ui.addArg("zig-out/test-results/webview/screenshot.png");
    webview_ui.addArg("--test");
    webview_ui.addFileArg(b.path("tests/webview-ui"));
    webview_ui.addArg("--");
    webview_ui.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    webview_ui.addFileArg(java.fixtures.webview);
    b.step("webview-ui-test", "Render and interact with stock WebView through ApkHost and Cage").dependOn(&webview_ui.run.step);
    const framework_compat_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(framework_compat_smoke, application_tree);
    framework_compat_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    framework_compat_smoke.addArg("--javatest");
    framework_compat_smoke.setEnvironment(
        "AZK_TEST_CLASS",
        "dev.artzig.FrameworkCompatSmoke",
    );
    // Absolute fixture paths on argv (the smoke must not depend on the CWD):
    // the androidfw parser fixture, then the generated package fixture.
    framework_compat_smoke.addFileArg(
        aosp.path(b, "frameworks/base/libs/androidfw/tests/data/app/app.apk"),
    );
    framework_compat_smoke.addFileArg(java.fixtures.example);
    // Hermetic: a nonexistent WAYLAND_DISPLAY keeps the session's clipboard
    // monitor off the developer's real desktop clipboard (empty falls back
    // to wayland-0).
    framework_compat_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    framework_compat_smoke.run.setName("run framework compatibility smoke");
    jvm_test_step.dependOn(&framework_compat_smoke.run.step);
    const connectivity_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(connectivity_smoke, application_tree);
    connectivity_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    connectivity_smoke.addArg("--javatest");
    connectivity_smoke.setEnvironment(
        "AZK_TEST_CLASS",
        "dev.artzig.ConnectivitySmoke",
    );
    connectivity_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    connectivity_smoke.run.setName("run connectivity handle and socket-binding smoke");
    jvm_test_step.dependOn(&connectivity_smoke.run.step);
    // JNI true-sharing bridge: loads libbinder_ndk into the ART process and
    // round-trips a binder across the language boundary in both directions.
    const ndk_bridge_test = @import("build/command.zig").create(b);
    setApplicationEnvironment(ndk_bridge_test, application_tree);
    ndk_bridge_test.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    ndk_bridge_test.addArg("--javatest");
    ndk_bridge_test.addFileArg(application_tree.path(b, "lib/art/libbinder_ndk.so"));
    ndk_bridge_test.setEnvironment("AZK_TEST_CLASS", "dev.artzig.NdkBridgeTest");
    ndk_bridge_test.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    ndk_bridge_test.run.setName("run libbinder_ndk JNI bridge test");
    jvm_test_step.dependOn(&ndk_bridge_test.run.step);
    const jvm_exit_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(jvm_exit_smoke, application_tree);
    jvm_exit_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    jvm_exit_smoke.addArg("--javatest");
    jvm_exit_smoke.setEnvironment("AZK_TEST_CLASS", "dev.artzig.JvmExitSmoke");
    jvm_exit_smoke.run.expectExitCode(23);
    // Hermetic: a nonexistent WAYLAND_DISPLAY keeps the session's clipboard
    // monitor off the developer's real desktop clipboard (empty falls back
    // to wayland-0).
    jvm_exit_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    jvm_exit_smoke.run.setName("run hosted JVM exit smoke");
    jvm_test_step.dependOn(&jvm_exit_smoke.run.step);
    b.step("jvm-exit-test", "Verify ART exits without libc cleanup")
        .dependOn(&jvm_exit_smoke.run.step);

    const testkit_step = b.step("testkit", "Build the host Kotlin testkit jars");
    for (testkit_jars) |path| path.addStepDependencies(testkit_step);

    const azk_test_run = @import("build/command.zig").create(b);
    azk_test_run.addArgs(&.{ "java", "-cp" });
    azk_test_run.addJoinedPaths(":", &testkit_jars);
    azk_test_run.addArg("dev.artzig.testkit.AzkTestCli");
    azk_test_run.addPassthruArgs();
    azk_test_run.run.setName("run Kotlin azk-test client");
    b.step("azk-test", "Run the Kotlin test-control CLI").dependOn(&azk_test_run.run.step);

    const testkit_unit_run = @import("build/command.zig").create(b);
    testkit_unit_run.addArgs(&.{ "java", "-cp" });
    testkit_unit_run.addJoinedPaths(":", &testkit_jars);
    testkit_unit_run.addArg("dev.artzig.testkit.TestkitUnitTestMain");
    testkit_unit_run.run.setName("run Kotlin testkit unit tests");
    const testkit_test_step = b.step("testkit-test", "Run host Kotlin testkit unit tests");
    testkit_test_step.dependOn(&testkit_unit_run.run.step);

    const integration_run = @import("build/command.zig").create(b);
    integration_run.setFileEnvironment("AZK_LAUNCHER", application_tree.path(b, "bin/androlinzberg"));
    setApplicationEnvironment(integration_run, application_tree);
    // Through the wrapper, not Step.Run.setEnvironmentVariable: the harness must
    // inherit the invoking shell's live environment (Mesa for Cage, the APK paths).
    integration_run.setEnvironment("WLR_RENDERER", "gles2");
    integration_run.setEnvironment("AZK_APK_REGISTRY", "tests/apks.json");
    integration_run.setEnvironment("AZK_ARTIFACT_ROOT", "zig-out/test-results/integration");
    integration_run.setEnvironment("AZK_SHARED_CACHE", "zig-out/test-cache/integration");
    integration_run.addArg("bash");
    integration_run.addFileArg(b.path("tools/shot.sh"));
    integration_run.addArgs(&.{ "zig-out/test-results/integration/suite.png", "--runner", "--" });
    integration_run.addFileArg(@import("build/command.zig").executable(b).getEmittedBin());
    integration_run.addArgs(&.{ "--arg", "java", "--arg", "-cp", "--join", ":", b.fmt("{d}", .{testkit_jars.len}) });
    for (testkit_jars) |jar| integration_run.addFileArg(jar);
    integration_run.addArgs(&.{ "--arg", "dev.artzig.testkit.IntegrationTestMain", "--passthru" });
    integration_run.addPassthruArgs();
    integration_run.run.step.dependOn(&testkit_unit_run.run.step);
    integration_run.run.setName("run APK integration tests under Cage");
    b.step("integration-test", "Run Kotlin APK integration tests under Cage").dependOn(&integration_run.run.step);

    const run = @import("build/command.zig").create(b);
    setApplicationEnvironment(run, application_tree);
    run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    run.addPassthruArgs();
    run.run.setName("run androlinzberg");
    b.step("run", "Run the launcher: zig build run -- <app.apk>").dependOn(&run.run.step);
}
