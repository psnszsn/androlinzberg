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
    const host_libc = b.option([]const u8, "host-libc", "Pinned glibc runtime providing ART's private stack query") orelse
        b.graph.environ_map.get("AZK_HOST_LIBC");

    const build_tools_test = b.step("build-tools-test", "Run build graph support unit tests");
    @import("build/command.zig").addTests(b, build_tools_test);
    const aosp_closure_tests = b.addSystemCommand(&.{ "python3", "-B", "tools/test_aosp_closure.py" });
    build_tools_test.dependOn(&aosp_closure_tests.step);
    const aosp_packaging_tests = b.addSystemCommand(&.{ "python3", "-B" });
    aosp_packaging_tests.addFileArg(b.path("tools/test_aosp_packaging.py"));
    aosp_packaging_tests.addFileInput(b.path("aosp/package-artzig-aosp.py"));
    build_tools_test.dependOn(&aosp_packaging_tests.step);
    const source_identity_tests = b.addSystemCommand(&.{ "python3", "-B" });
    source_identity_tests.addFileArg(b.path("tools/test_aosp_source_identity.py"));
    source_identity_tests.addFileInput(b.path("tools/aosp-source-identity.py"));
    build_tools_test.dependOn(&source_identity_tests.step);
    const native_provenance_tests = b.addSystemCommand(&.{ "python3", "-B" });
    native_provenance_tests.addFileArg(b.path("tools/test_native_client_audit.py"));
    native_provenance_tests.addFileInput(b.path("tools/native-client-audit.py"));
    native_provenance_tests.addFileInput(b.path("tools/native_rust_audit.py"));
    build_tools_test.dependOn(&native_provenance_tests.step);
    const doc_header_tests = b.addSystemCommand(&.{ "python3", "-B", "tools/test_javadoc_headers.py" });
    build_tools_test.dependOn(&doc_header_tests.step);
    const native_runner_tests = b.addSystemCommand(&.{ "python3", "-B" });
    native_runner_tests.addFileArg(b.path("tools/test_binder_facade_runner.py"));
    native_runner_tests.addFileInput(b.path("tools/binder-facade-test.py"));
    build_tools_test.dependOn(&native_runner_tests.step);
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
    scanner.addSystemProtocol("stable/presentation-time/presentation-time.xml");
    scanner.addSystemProtocol("staging/fractional-scale/fractional-scale-v1.xml");
    scanner.addSystemProtocol("stable/linux-dmabuf/linux-dmabuf-v1.xml");
    scanner.addSystemProtocol("stable/tablet/tablet-v2.xml");
    scanner.addSystemProtocol("unstable/text-input/text-input-unstable-v3.xml");
    scanner.addSystemProtocol("unstable/pointer-constraints/pointer-constraints-unstable-v1.xml");
    scanner.addSystemProtocol("unstable/relative-pointer/relative-pointer-unstable-v1.xml");
    scanner.addSystemProtocol("staging/cursor-shape/cursor-shape-v1.xml");
    scanner.addCustomProtocol(b.path("src/protocol/wlr-virtual-pointer-unstable-v1.xml"));
    scanner.addCustomProtocol(b.path("src/protocol/virtual-keyboard-unstable-v1.xml"));
    scanner.generate("wl_compositor", 6);
    scanner.generate("wl_shm", 1);
    scanner.generate("wl_seat", 8);
    scanner.generate("wl_data_device_manager", 3);
    scanner.generate("wl_output", 4);
    scanner.generate("xdg_wm_base", 6);
    scanner.generate("wl_subcompositor", 1);
    scanner.generate("wp_viewporter", 1);
    scanner.generate("wp_presentation", 2);
    scanner.generate("wp_fractional_scale_manager_v1", 1);
    scanner.generate("zwp_linux_dmabuf_v1", 4);
    scanner.generate("zwp_pointer_constraints_v1", 1);
    scanner.generate("zwp_relative_pointer_manager_v1", 1);
    scanner.generate("wp_cursor_shape_manager_v1", 1);
    scanner.generate("zwp_tablet_manager_v2", 1);
    scanner.generate("zwp_text_input_manager_v3", 1);
    scanner.generate("zwlr_virtual_pointer_manager_v1", 2);
    scanner.generate("zwp_virtual_keyboard_manager_v1", 1);
    // --- embedded artzig runtime (ART) module
    // The root AOSP archive supplies framework/native/Skia/generator sources. Its
    // current Android 16 r1 local URL is content-addressed by build.zig.zon's
    // package hash, which changes whenever the selected source closure changes.
    const aosp_dep = b.dependency("aosp", .{});
    const aosp = aosp_dep.path("");
    const webview_dep = b.dependency("webview", .{});
    const artzig_dep = b.dependency("artzig", .{ .target = target, .optimize = optimize });
    const art_mod = artzig_dep.module("art");
    const identity_tests = b.addSystemCommand(&.{"env"});
    identity_tests.addFileArg(artzig_dep.namedLazyPath("process-identity-test-bin"));
    b.step("process-identity-test", "Verify immutable authenticated native identity").dependOn(&identity_tests.step);
    const certificate_tests = b.addSystemCommand(&.{"env"});
    certificate_tests.addFileArg(artzig_dep.namedLazyPath("system-certificates-test-bin"));
    certificate_tests.addDirectoryArg(artzig_dep.namedLazyPath("art-runtime"));
    b.step("system-certificates-test", "Verify stock standalone ART trust and native certificate installation").dependOn(&certificate_tests.step);
    const partition_tests = b.addSystemCommand(&.{"env"});
    partition_tests.addFileArg(artzig_dep.namedLazyPath("data-partition-test-bin"));
    b.step("data-partition-test", "Verify persistent app storage, session policy and shared ART cache").dependOn(&partition_tests.step);
    const bionic_loader_tests = b.addSystemCommand(&.{"env"});
    bionic_loader_tests.addFileArg(artzig_dep.namedLazyPath("bionic-loader-test-bin"));
    b.step("bionic-loader-test", "Verify native process handles, global exports and explicit DSO scope").dependOn(&bionic_loader_tests.step);

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
    const skia_android_headers = b.addWriteFiles();
    _ = skia_android_headers.addCopyFile(aosp.path(b, "bionic/libc/include/sys/system_properties.h"), "sys/system_properties.h");
    const skia_build = @import("build/skia.zig").build(b, .{
        .root = aosp.path(b, "external/skia"),
        .wuffs = aosp.path(b, "external/wuffs-mirror-release-c"),
        .target = target,
        .host_include_roots = host_toolchain.skia.roots,
        .include_dirs = &.{
            skia_android_headers.getDirectory(),
            aosp.path(b, "frameworks/native/libs/nativewindow/include"),
            aosp.path(b, "frameworks/native/libs/arect/include"),
            aosp.path(b, "system/core/libcutils/include"),
            aosp.path(b, "system/logging/liblog/include"),
        },
    });
    const skia_lib = skia_build.library;
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
    b.step("sysprop-cpp-tool", "Build the AOSP native sysprop generator")
        .dependOn(&b.addInstallArtifact(sysprop_cpp_tool, .{}).step);

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

    const cppstream_tool = @import("build/javastream.zig").build(b, .{
        .backend = .cpp,
        .root = aosp.path(b, "frameworks/base/tools/streaming_proto"),
        .protobuf_src = aosp.path(b, "external/protobuf"),
        .target = target,
        .protobuf = protobuf.library,
    });

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
        .perfetto_headers = perfetto.headers,
        .aosp = aosp_dep,
        .protobuf = protobuf.library,
        .protoc = protobuf.protoc,
        .sysprop_tool = sysprop_tool,
        .sysprop_cpp_tool = sysprop_cpp_tool,
        .javastream_tool = javastream_tool,
        .cppstream_tool = cppstream_tool,
        .javanano_tool = javanano_tool,
    });
    // The broker serves the stable IServiceManager from the interface the
    // pinned AIDL generates, over its own frame transport (broker_aidl.zig).
    const aidl_zig = @import("build/aidl.zig");
    const broker_aidl = b.createModule(.{ .root_source_file = b.path("src/binder/broker_aidl.zig"), .target = target, .optimize = optimize });
    const servicemanager_aidl = aidl_zig.zigModule(b, generated.aidl_tool, .{
        .root = aosp.path(b, "frameworks/native/libs/binder/aidl"),
        .files = &.{
            "android/os/ConnectionInfo.aidl",      "android/os/IClientCallback.aidl", "android/os/IServiceCallback.aidl",
            "android/os/IServiceManager.aidl",     "android/os/Service.aidl",         "android/os/ServiceDebugInfo.aidl",
            "android/os/ServiceWithMetadata.aidl",
        },
        .runtime = broker_aidl,
        .target = target,
        .optimize = optimize,
    });
    binder_broker.root_module.addImport("aidl", broker_aidl);
    binder_broker.root_module.addImport("servicemanager_aidl", servicemanager_aidl);

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
    binder_ndk.root_module.addImport("servicemanager_aidl", servicemanager_aidl);
    binder_ndk.root_module.linkSystemLibrary("egl", .{ .needed = true });
    binder_ndk.root_module.linkSystemLibrary("gbm", .{ .needed = true });
    binder_ndk.root_module.addCSourceFile(.{ .file = b.path("src/native/binder/ProcessPeer.c"), .flags = &.{ "-std=c11", "-Wall", "-Wextra", "-Werror" } });
    b.step("binder-ndk", "Build libbinder_ndk.so (native NDK binder ABI)")
        .dependOn(&binder_ndk.step);

    const binder_cpp = @import("build/binder_cpp.zig").build(b, target, optimize, aosp, binder_ndk);
    b.step("binder-cpp", "Build the hosted C++ libbinder facade").dependOn(&binder_cpp.step);
    const hidl = @import("build/hidl.zig").build(b, target, aosp, generated.native.hidl_header_roots, generated.native.hidl_sources, binder_cpp);
    const graphics = @import("build/graphics.zig").build(b, target, optimize, aosp, generated.native.gui, hidl);
    const gui_host = graphics.gui;
    const codec2 = @import("build/codec2.zig").build(b, .{
        .target = target,
        .aosp = aosp,
        .renderengine_headers = generated.native.renderengine.headers,
        .renderengine_sources = generated.native.renderengine.sources,
        .package_native_headers = generated.native.input_dispatcher.headers,
        .package_native_sources = generated.native.input_dispatcher.sources,
        .host_include_roots = host_toolchain.island.include_roots,
        .generated = .{ .headers = generated.native.surfaceflinger.headers, .sources = generated.native.surfaceflinger.sources },
        .gui_generated = generated.native.gui,
        .headers = generated.native.aidl_header_roots,
        .sources = generated.native.aidl_source_roots,
        .hidl_headers = generated.native.hidl_header_roots,
        .flag_headers = generated.native.aconfig_header_roots,
        .flag_sources = generated.native.aconfig_sources,
        .media_stats = generated.native.media_stats,
        .sysprop_headers = generated.native.codec2_sysprop.headers,
        .sysprop_source = generated.native.codec2_sysprop.source,
        .binder = binder_cpp,
        .ndk = binder_ndk,
        .broker = binder_broker,
        .gui = gui_host,
    });
    const input_transport = @import("build/input.zig").build(b, target, aosp, generated.native.gui, generated.native.input, generated.native.aidl_header_roots, gui_host, binder_cpp, binder_ndk, binder_broker);
    const renderengine = @import("build/renderengine.zig").build(b, target, aosp, generated.native.gui, generated.native.renderengine, gui_host, skia_lib);
    b.step("renderengine", "Compile pinned GL/Ganesh RenderEngine over host graphics").dependOn(&renderengine.step);
    const renderengine_test = b.addExecutable(.{ .name = "renderengine-test", .root_module = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
    }) });
    @import("build/renderengine.zig").configure(b, renderengine_test.root_module, aosp, generated.native.gui, generated.native.renderengine);
    var renderengine_test_flags: std.ArrayList([]const u8) = .empty;
    renderengine_test_flags.appendSlice(b.allocator, @import("build/renderengine.zig").cppFlags(b)) catch @panic("oom");
    renderengine_test_flags.append(b.allocator, "-UNDEBUG") catch @panic("oom");
    renderengine_test.root_module.addCSourceFile(.{ .file = b.path("tests/native/RenderEngineTest.cpp"), .flags = renderengine_test_flags.items });
    renderengine_test.root_module.linkLibrary(renderengine);
    renderengine_test.root_module.linkLibrary(binder_cpp);
    renderengine_test.root_module.linkLibrary(binder_ndk);
    const renderengine_run = @import("build/command.zig").create(b);
    renderengine_run.addFileArg(renderengine_test.getEmittedBin());
    renderengine_run.run.has_side_effects = true;
    renderengine_run.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    const renderengine_log = b.addInstallFileWithDir(renderengine_run.run.captureStdOut(.{}), .prefix, "test-results/renderengine/run.log");
    const renderengine_errors = b.addInstallFileWithDir(renderengine_run.run.captureStdErr(.{}), .prefix, "test-results/renderengine/renderer.log");
    const renderengine_check = b.step("renderengine-test", "Verify pinned GPU composition and buffer ownership");
    renderengine_check.dependOn(&renderengine_log.step);
    renderengine_check.dependOn(&renderengine_errors.step);
    const sf = @import("build/surfaceflinger.zig");
    const perfetto_build = @import("build/perfetto.zig");
    const dispatcher_options: @import("build/input_dispatcher.zig").Options = .{
        .target = target,
        .aosp = aosp,
        .gui = generated.native.gui,
        .input_generated = generated.native.input,
        .aidl = generated.native.aidl_header_roots,
        .generated = generated.native.input_dispatcher,
        .perfetto_generated = generated.native.perfetto,
        .transport = input_transport,
        .perfetto = perfetto.sdk,
        .protobuf = protobuf.library,
        .binder = binder_cpp,
        .ndk = binder_ndk,
        .broker = binder_broker,
    };
    const input_dispatcher = @import("build/input_dispatcher.zig").build(b, dispatcher_options);
    const perfetto_test_mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    perfetto_build.configure(b, perfetto_test_mod, aosp, generated.native.perfetto);
    perfetto_test_mod.addCSourceFile(.{ .file = b.path("tests/native/PerfettoTest.cpp"), .flags = perfetto_build.cpp_flags });
    perfetto_test_mod.linkLibrary(perfetto.sdk);
    const perfetto_test = b.addExecutable(.{ .name = "perfetto-test", .root_module = perfetto_test_mod });
    const perfetto_run = b.addRunArtifact(perfetto_test);
    perfetto_run.has_side_effects = true;
    const perfetto_log = b.addInstallFileWithDir(perfetto_run.captureStdOut(.{}), .prefix, "test-results/perfetto/run.log");
    b.step("perfetto-test", "Verify pinned tracing sessions and generated frame packet ABI").dependOn(&perfetto_log.step);
    const sf_frontend = sf.build(b, target, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger, renderengine);
    b.step("surfaceflinger-frontend", "Compile pinned SurfaceFlinger layer state and composition preparation").dependOn(&sf_frontend.step);
    const host_composer = sf.buildHostComposer(b, target, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger);
    b.step("host-composer", "Compile host GPU composer and software display clock").dependOn(&host_composer.step);
    const host_composer_test = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    sf.configure(b, host_composer_test, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger);
    var host_composer_flags: std.ArrayList([]const u8) = .empty;
    host_composer_flags.appendSlice(b.allocator, sf.cppFlags(b)) catch @panic("oom");
    host_composer_flags.append(b.allocator, "-UNDEBUG") catch @panic("oom");
    host_composer_test.addCSourceFile(.{ .file = b.path("tests/native/HostComposerTest.cpp"), .flags = host_composer_flags.items });
    host_composer_test.linkLibrary(host_composer);
    host_composer_test.linkLibrary(renderengine);
    host_composer_test.linkLibrary(binder_cpp);
    host_composer_test.linkLibrary(binder_ndk);
    sf.runTest(b, host_composer_test, binder_cpp, binder_ndk, binder_broker, "host-composer", "Verify host composer state, GPU target ownership and software vsync lifetime", .standalone, null);
    const sf_core = sf.buildServiceCore(b, target, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger, sf_frontend, perfetto.sdk, protobuf.library);
    b.step("surfaceflinger-core", "Compile pinned SurfaceFlinger scheduling and composition service core").dependOn(&sf_core.step);
    const sf_service_mod = b.createModule(.{ .root_source_file = b.path("src/surfaceflinger_main.zig"), .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    sf_service_mod.linkLibrary(sf_core);
    sf_service_mod.linkLibrary(host_composer);
    sf_service_mod.linkLibrary(binder_cpp);
    sf_service_mod.linkLibrary(binder_ndk);
    sf_service_mod.addRPathSpecial("$ORIGIN/../lib/art");
    const sf_service = b.addExecutable(.{ .name = "azk-surfaceflinger", .root_module = sf_service_mod });
    @import("build/input_dispatcher.zig").testSurfaceFlinger(b, dispatcher_options, input_dispatcher, sf_service);
    const sf_install = b.addInstallArtifact(sf_service, .{});
    b.step("surfaceflinger", "Install the supervised hosted SurfaceFlinger process").dependOn(&sf_install.step);
    const sf_service_test = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    sf.configure(b, sf_service_test, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger);
    sf_service_test.addCSourceFile(.{ .file = b.path("tests/native/SurfaceFlingerServiceTest.cpp"), .flags = host_composer_flags.items });
    sf_service_test.linkLibrary(gui_host);
    sf_service_test.linkLibrary(binder_cpp);
    sf_service_test.linkLibrary(binder_ndk);
    const sf_service_probe = b.addExecutable(.{ .name = "surfaceflinger-service-test", .root_module = sf_service_test });
    const sf_retire = sf.runBinaryTest(b, sf_service_probe.getEmittedBin(), binder_cpp, binder_ndk, binder_broker, "surfaceflinger-service", "Verify hosted SF transactions, permissions and session retirement", .application, sf_service, &.{}, &.{});
    sf_retire.addArgs(&.{ "--retire-broker", "--probe-arg=retire" });
    const sf_restart = sf.runBinaryTest(b, sf_service_probe.getEmittedBin(), binder_cpp, binder_ndk, binder_broker, "surfaceflinger-restart", "Verify original composer clients reconnect to a replacement SF service", .application, sf_service, &.{}, &.{});
    sf_restart.addArgs(&.{ "--restart-service", "--probe-arg=restart" });
    b.top_level_steps.get("surfaceflinger-service-test").?.step.dependOn(&b.top_level_steps.get("surfaceflinger-restart-test").?.step);

    const sf_output_test = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    sf.configure(b, sf_output_test, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger);
    sf_output_test.addCSourceFile(.{ .file = b.path("tests/native/SurfaceFlingerOutputTest.cpp"), .flags = host_composer_flags.items });
    sf_output_test.linkLibrary(renderengine);
    sf_output_test.linkLibrary(binder_cpp);
    sf_output_test.linkLibrary(binder_ndk);
    sf.runTest(b, sf_output_test, binder_cpp, binder_ndk, binder_broker, "surfaceflinger-output", "Verify running SF mirrored subtrees through independent GPU output queues", .system, sf_service);

    for ([_]bool{ false, true }) |gpu_test|
        sf.testTarget(b, target, aosp, generated.native.gui, generated.native.renderengine, generated.native.surfaceflinger, sf_frontend, binder_cpp, binder_ndk, binder_broker, gpu_test);
    b.step("ui", "Compile pinned GraphicBuffer/AHardwareBuffer over host GBM").dependOn(&graphics.ui.step);
    b.step("gui", "Compile pinned BufferQueue/Surface with the host gralloc backend").dependOn(&gui_host.step);
    const gui_test = b.addExecutable(.{ .name = "bufferqueue-test", .root_module = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
    }) });
    // The standalone executable owns libgui here. Export its C++ symbols for
    // the original parcel loader, as the production shared library does.
    gui_test.rdynamic = true;
    @import("build/graphics.zig").configure(b, gui_test.root_module, aosp, generated.native.gui);
    gui_test.root_module.addCSourceFile(.{ .file = b.path("tests/native/BufferQueueTest.cpp"), .flags = &.{
        "-std=c++23", "-fno-rtti", "-fno-exceptions", "-UNDEBUG", "-Wno-invalid-specialization", "-Wno-inconsistent-missing-override", "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}),
    } });
    gui_test.root_module.linkLibrary(gui_host);
    gui_test.root_module.linkLibrary(binder_cpp);
    gui_test.root_module.linkLibrary(binder_ndk);
    gui_test.root_module.linkSystemLibrary("GLESv2", .{});
    gui_test.root_module.linkSystemLibrary("vulkan", .{});
    const gui_run = b.addSystemCommand(&.{"python3"});
    gui_run.addFileArg(b.path("tools/binder-facade-test.py"));
    gui_run.addArg("--probe");
    gui_run.addArtifactArg(gui_test);
    gui_run.addArg("--library");
    gui_run.addArtifactArg(binder_cpp);
    gui_run.addArg("--library");
    gui_run.addArtifactArg(binder_ndk);
    gui_run.addArg("--broker");
    gui_run.addArtifactArg(binder_broker);
    gui_run.addArgs(&.{ "--output", b.pathResolve(&.{"zig-out/test-results/bufferqueue"}) });
    gui_run.addArg("--producer-crash");
    gui_run.has_side_effects = true;
    const gui_test_step = b.step("gui-test", "Exercise stock BufferQueue and host GBM allocation across AZKB (requires GPU)");
    gui_test_step.dependOn(&gui_run.step);
    const graphics_budget_step = b.step("graphics-budget-test", "Verify allocation/import memory and handle admission on a GPU");
    for ([_][]const u8{ "count", "bytes" }) |dimension| {
        const run = b.addSystemCommand(&.{"python3"});
        run.addFileArg(b.path("tools/binder-facade-test.py"));
        run.addArg("--standalone");
        run.addArg("--probe");
        run.addArtifactArg(gui_test);
        run.addArg("--probe-arg");
        run.addArg(b.fmt("budget-{s}", .{dimension}));
        run.addArg("--library");
        run.addArtifactArg(binder_cpp);
        run.addArg("--library");
        run.addArtifactArg(binder_ndk);
        run.addArg("--broker");
        run.addArtifactArg(binder_broker);
        run.addArgs(&.{ "--output", b.pathResolve(&.{b.fmt("zig-out/test-results/graphics-budget-{s}", .{dimension})}) });
        run.has_side_effects = true;
        graphics_budget_step.dependOn(&run.step);
        gui_test_step.dependOn(&run.step);
    }
    const surface_queue_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/surface_queue.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{.{ .name = "wayland", .module = wayland_mod }},
    });
    @import("build/graphics.zig").configure(b, surface_queue_mod, aosp, generated.native.gui);
    const surface_server = b.addExecutable(.{ .name = "wayland-surface-server", .root_module = b.createModule(.{
        .root_source_file = b.path("tests/native/wayland_surface_server.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
        .imports = &.{ .{ .name = "surface_queue", .module = surface_queue_mod }, .{ .name = "wayland", .module = wayland_mod } },
    }) });
    surface_server.root_module.linkLibrary(gui_host);
    surface_server.root_module.linkLibrary(binder_cpp);
    surface_server.root_module.linkLibrary(binder_ndk);
    surface_server.root_module.linkSystemLibrary("wayland-client", .{});
    surface_server.root_module.linkSystemLibrary("xkbcommon", .{});
    // Zig generated by `aidl --lang=zig` marshals through src/binder/aidl.zig.
    const aidl_runtime = b.createModule(.{ .root_source_file = b.path("src/binder/aidl.zig"), .target = target, .optimize = optimize });
    const task_presenter_parcelables = b.createModule(.{
        .root_source_file = b.path("src/platform/task_presenter_parcelables.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{ .{ .name = "surface_queue", .module = surface_queue_mod }, .{ .name = "aidl", .module = aidl_runtime } },
    });
    const task_presenter = b.addExecutable(.{ .name = "azk-task-presenter", .root_module = b.createModule(.{
        .root_source_file = b.path("src/platform/task_presenter.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
        .imports = &.{
            .{ .name = "surface_queue", .module = surface_queue_mod },
            .{ .name = "aidl", .module = aidl_runtime },
            .{ .name = "task_presenter_parcelables", .module = task_presenter_parcelables },
            .{ .name = "task_presenter_aidl", .module = aidl_zig.zigModule(b, generated.aidl_tool, .{
                .root = b.path("aidl-compat"),
                .files = &.{ "dev/artzig/window/IWaylandTaskEvents.aidl", "dev/artzig/window/IWaylandTaskPresenter.aidl" },
                .runtime = aidl_runtime,
                .custom = task_presenter_parcelables,
                .target = target,
                .optimize = optimize,
            }) },
        },
    }) });
    task_presenter.root_module.linkLibrary(gui_host);
    task_presenter.root_module.linkLibrary(binder_cpp);
    task_presenter.root_module.linkLibrary(binder_ndk);
    task_presenter.root_module.linkSystemLibrary("wayland-client", .{});
    task_presenter.root_module.linkSystemLibrary("xkbcommon", .{});
    const install_task_presenter = b.addInstallArtifact(task_presenter, .{});
    b.step("task-presenter", "Build the session Wayland task presentation service").dependOn(&install_task_presenter.step);
    const surface_producer = b.addExecutable(.{ .name = "wayland-surface-producer", .root_module = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
    }) });
    @import("build/graphics.zig").configure(b, surface_producer.root_module, aosp, generated.native.gui);
    surface_producer.root_module.addCSourceFile(.{ .file = b.path("tests/native/WaylandSurfaceProducer.cpp"), .flags = &.{
        "-std=c++23", "-fno-rtti", "-fno-exceptions", "-UNDEBUG", "-Wno-invalid-specialization", "-Wno-inconsistent-missing-override", "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}),
    } });
    surface_producer.root_module.linkSystemLibrary("vulkan", .{});
    surface_producer.root_module.linkLibrary(gui_host);
    surface_producer.root_module.linkLibrary(binder_cpp);
    surface_producer.root_module.linkLibrary(binder_ndk);
    const surface_test_step = b.step("surface-wayland-test", "Present stock Surface frames in Cage and verify resize, teardown and peer loss");
    const surface_fence_step = b.step("surface-fence-test", "Verify delayed GPU acquire fences without blocking Wayland dispatch");
    const surface_pacing_step = b.step("surface-pacing-test", "Verify bounded frame submission paced by real compositor callbacks");
    const drag_icon_step = b.step("surface-drag-icon-test", "Verify GPU BufferQueue drag-icon geometry, updates and teardown");
    var previous_surface_run: ?*std.Build.Step = null;
    for ([_][]const u8{ "normal", "producer-crash", "consumer-crash", "broker-loss", "backpressure", "pointer-capture-unavailable", "pointer-capture", "cursor-animation", "delayed-fence", "fence-shutdown", "fence-timeout", "paced", "paced-fallback", "drag-icon", "drag-icon-v4", "drag-icon-offset", "drag-icon-v4-offset" }) |scenario| {
        const output = b.pathResolve(&.{ "zig-out/test-results/wayland-surface", scenario });
        const surface_run = b.addSystemCommand(&.{ "env", "WLR_RENDERER=gles2" });
        if (std.mem.eql(u8, scenario, "pointer-capture") or std.mem.startsWith(u8, scenario, "drag-icon")) {
            surface_run.addArg("SHOT_COMPOSITOR=labwc");
            surface_run.addPrefixedFileArg("SHOT_LABWC_CONFIG=", b.path("testdata/wayland/surface-capture.xml"));
        }
        surface_run.addFileArg(b.path("tools/shot.sh"));
        surface_run.addArgs(&.{ b.pathResolve(&.{ output, "screenshot.png" }), "--runner", "--", "python3" });
        surface_run.addFileArg(b.path("tests/native/wayland-surface-test.py"));
        surface_run.addArg("--broker");
        surface_run.addArtifactArg(binder_broker);
        surface_run.addArg("--server");
        surface_run.addArtifactArg(surface_server);
        surface_run.addArg("--producer");
        surface_run.addArtifactArg(surface_producer);
        surface_run.addArg("--library");
        surface_run.addArtifactArg(binder_cpp);
        surface_run.addArg("--library");
        surface_run.addArtifactArg(binder_ndk);
        surface_run.addArgs(&.{ "--output", output });
        surface_run.has_side_effects = true;
        surface_run.addArgs(&.{ "--scenario", scenario });
        if (std.mem.startsWith(u8, scenario, "drag-icon")) {
            drag_icon_step.dependOn(&surface_run.step);
        } else if (std.mem.startsWith(u8, scenario, "paced")) {
            surface_pacing_step.dependOn(&surface_run.step);
        } else if (std.mem.eql(u8, scenario, "delayed-fence") or std.mem.startsWith(u8, scenario, "fence-")) {
            surface_fence_step.dependOn(&surface_run.step);
        } else {
            if (previous_surface_run) |previous| surface_run.step.dependOn(previous);
            previous_surface_run = &surface_run.step;
        }
        surface_test_step.dependOn(&surface_run.step);
    }
    const output_lifecycle_step = b.step("wayland-output-test", "Track output scale, transform, removal and reappearance through the real compositor");
    var previous_output_run: ?*std.Build.Step = null;
    for ([_][]const u8{ "preferred", "fallback" }) |scenario| {
        const output = b.pathResolve(&.{ "zig-out/test-results/wayland-output", scenario });
        const run = b.addSystemCommand(&.{ "env", "WLR_RENDERER=gles2", "WLR_HEADLESS_OUTPUTS=2", "SHOT_COMPOSITOR=labwc" });
        run.addFileArg(b.path("tools/shot.sh"));
        run.addArgs(&.{ b.pathResolve(&.{ output, "screenshot.png" }), "--runner", "--", "python3" });
        run.addFileArg(b.path("tests/native/wayland-output-test.py"));
        run.addArg("--client");
        run.addArtifactArg(surface_server);
        run.addArgs(&.{ "--output", output, "--scenario", scenario });
        run.has_side_effects = true;
        if (previous_output_run) |previous| run.step.dependOn(previous);
        previous_output_run = &run.step;
        output_lifecycle_step.dependOn(&run.step);
    }
    const graphics_buffer_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/platform/graphics_buffer.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    }) });
    graphics_buffer_tests.root_module.linkSystemLibrary("gbm", .{});
    graphics_buffer_tests.root_module.linkSystemLibrary("EGL", .{});
    graphics_buffer_tests.root_module.linkLibrary(binder_cpp);
    graphics_buffer_tests.root_module.linkLibrary(binder_ndk);
    b.step("graphics-buffer-test", "Check host graphics handle validation without a GPU")
        .dependOn(&b.addRunArtifact(graphics_buffer_tests).step);

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
    facade_run.addArg("--release-pressure");
    facade_run.addArgs(&.{ "--declare-service", "dev.artzig.facade.v2.IStore/default", "--declare-service", "dev.artzig.facade.v2.IStore/secondary" });
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
    const island_options: @import("build/island.zig").Options = .{
        .host_paths_library = artzig_dep.namedLazyPath("host-paths-library"),
        .host_paths_headers = artzig_dep.namedLazyPath("host-paths-headers"),
        .xsd = generated.native.xsd,
        .android_account_headers = generated.native.android_account_headers,
        .cppstream_headers = generated.native.cppstream_headers,
        .input_dispatcher_headers = generated.native.input_dispatcher.headers,
        .media_generated = generated.native.media,
        .hwui_stats = generated.native.hwui_stats,
        .surfaceflinger_generated = generated.native.surfaceflinger,
        .target = target,
        .optimize = optimize,
        .skia = skia_lib,
        .binder_ndk = binder_ndk,
        .binder_cpp = binder_cpp,
        .ui = graphics.ui,
        .gui = gui_host,
        .input = input_transport,
        .input_dispatcher = input_dispatcher,
        .input_dispatcher_jni = @import("build/input_dispatcher.zig").buildJni(b, dispatcher_options),
        .perfetto = perfetto,
        .protobuf = protobuf.library,
        .input_headers = generated.native.input.headers,
        .graphics_headers = generated.native.gui.headers,
        .art = art_mod,
        .aidl_header_roots = generated.native.aidl_header_roots,
        .aidl_source_roots = generated.native.aidl_source_roots,
        .hidl_header_roots = generated.native.hidl_header_roots,
        .hidl = hidl,
        .aconfig_header_roots = generated.native.aconfig_header_roots,
        .incremental_sysprop_headers = generated.native.incremental_sysprop_headers,
        .incremental_sysprop_source = generated.native.incremental_sysprop_source,
        .host_include_roots = host_toolchain.island.include_roots,
        .host_library_roots = host_toolchain.island.library_roots,
        .host_libraries = host_toolchain.island.libraries,
        .resolver = host_toolchain.resolver,
        .host_link_flags = host_toolchain.island.link_flags,
        .aosp = aosp,
        .codec2 = codec2,
    };
    const island = @import("build/island.zig").build(b, island_options);
    const linked_native_inputs = @import("build/native_provenance.zig").record(b, island.step.cast(std.Build.Step.Run).?);
    @import("build/vintf.zig").testSuite(b, island_options, island, binder_broker);
    @import("build/performance_hint.zig").testSuite(b, island_options, island, binder_broker);
    const media_service_probe = @import("build/codec2.zig").serviceProbe(b, codec2, island.file, binder_cpp, binder_ndk);
    b.step("island", "Build libandroid_platform.so").dependOn(island.step);
    @import("build/codec2.zig").cameraTest(b, codec2, island.file, binder_cpp, binder_ndk, binder_broker);
    @import("build/codec2.zig").clientTest(b, codec2, island.file, binder_cpp, binder_ndk, binder_broker);
    @import("build/codec2.zig").resourceTests(b, codec2, island.file, binder_cpp, binder_ndk, binder_broker);
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
    const window_fixture = @import("build/window_fixtures.zig").build(b, aosp_dep, java);
    const testkit_jars = testkit.jars.all();
    const testkit_tree = b.addWriteFiles();
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
        _ = testkit_tree.addCopyFile(entry[0], std.fs.path.basename(entry[1]));
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

    // Zig AIDL backend self-test: tests/aidl-zig compiled by `aidl --lang=zig`
    // and, as the wire oracle, by the pinned NDK backend into one process.
    const probe_files = [_][]const u8{ "Choice", "IProbe", "IProbeCallback", "Kind", "Pair", "Point", "Shape" };
    var probe_aidl: [probe_files.len][]const u8 = undefined;
    for (probe_files, &probe_aidl) |name, *file| file.* = b.fmt("dev/artzig/probe/{s}.aidl", .{name});
    const aidl_zig_test = b.addExecutable(.{
        .name = "azk-aidl-zig-test",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/aidl_test.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .link_libcpp = true,
            .imports = &.{
                .{ .name = "aidl", .module = aidl_runtime },
                .{ .name = "probe", .module = aidl_zig.zigModule(b, generated.aidl_tool, .{
                    .root = b.path("tests/aidl-zig"),
                    .files = &probe_aidl,
                    .version = .{ .number = 3, .hash = "probe-hash" },
                    .runtime = aidl_runtime,
                    .target = target,
                    .optimize = optimize,
                }) },
            },
        }),
    });
    const probe_ndk = b.addRunArtifact(generated.aidl_tool);
    probe_ndk.addArgs(&.{ "--lang=ndk", "--structured", "--version=3", "--hash=probe-hash" });
    probe_ndk.addPrefixedDirectoryArg("-I", b.path("tests/aidl-zig"));
    probe_ndk.addArg("-h");
    const probe_ndk_headers = probe_ndk.addOutputDirectoryArg("probe-ndk-headers");
    probe_ndk.addArg("-o");
    const probe_ndk_sources = probe_ndk.addOutputDirectoryArg("probe-ndk-sources");
    for (probe_aidl) |file| probe_ndk.addFileArg(b.path(b.fmt("tests/aidl-zig/{s}", .{file})));
    const probe_ndk_flags = [_][]const u8{ "-std=gnu++23", "-D__ANDROID_HOST__", "-D__INTRODUCED_IN(x)=", "-D__ANDROID_API_U__=34", "-D__ANDROID_API_S__=31", "-Wno-deprecated-declarations" };
    for ([_][]const u8{ "include_ndk", "include_platform", "include_cpp" }) |include|
        aidl_zig_test.root_module.addIncludePath(aosp.path(b, b.fmt("frameworks/native/libs/binder/ndk/{s}", .{include})));
    aidl_zig_test.root_module.addIncludePath(probe_ndk_headers);
    for (probe_files) |name|
        aidl_zig_test.root_module.addCSourceFile(.{ .file = probe_ndk_sources.path(b, b.fmt("dev/artzig/probe/{s}.cpp", .{name})), .flags = &probe_ndk_flags });
    aidl_zig_test.root_module.addCSourceFile(.{ .file = b.path("tests/aidl-zig/ProbeNdk.cpp"), .flags = &probe_ndk_flags });
    aidl_zig_test.root_module.linkLibrary(binder_ndk);
    const aidl_zig_test_run = b.addRunArtifact(aidl_zig_test);
    aidl_zig_test_run.addFileArg(binder_broker.getEmittedBin());
    b.step("aidl-zig-test", "Round-trip the Zig AIDL backend against itself and the NDK backend").dependOn(&aidl_zig_test_run.step);

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
                .{ .name = "art", .module = art_mod },
            },
        }),
    });
    exe.root_module.addCSourceFile(.{ .file = b.path("src/native/LauncherSignals.c"), .flags = &.{ "-std=c11", "-D_POSIX_C_SOURCE=200809L", "-Wall", "-Wextra", "-Werror" } });
    exe.root_module.addCSourceFile(.{ .file = b.path("src/native/ProcessAppData.c"), .flags = &.{ "-std=c11", "-Wall", "-Wextra", "-Werror" } });
    if (host_libc) |root| exe.root_module.addRPath(b.graph.cwdRelativePath(b.fmt("{s}/lib", .{root})));
    exe.root_module.linkSystemLibrary("fontconfig", .{ .needed = true }); // platform/fonts.zig

    // --- azk_codec: MediaCodec backend over system FFmpeg. The system build
    // includes VA-API and DRM hwcontexts, which the minimal vendored build does
    // not. This backend is linked into the Codec2 service and native tests.
    const av_tc = b.addTranslateC(.{
        .root_source_file = b.path("src/codec/azk_ffmpeg.h"),
        .target = target,
        .optimize = optimize,
    });
    for (host_toolchain.codec.roots) |root| av_tc.addSystemIncludePath(root);
    av_tc.addIncludePath(b.path("src/codec"));
    av_tc.addIncludePath(b.path("src/native/graphics/include"));
    const codec_mod = b.createModule(.{
        .root_source_file = b.path("src/codec/azk_codec.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    const av_mod = av_tc.createModule();
    codec_mod.addImport("av", av_mod);
    codec_mod.addIncludePath(b.path("src/codec"));
    codec_mod.addIncludePath(b.path("src/native/graphics/include"));
    codec_mod.addCSourceFile(.{
        .file = b.path("src/codec/azk_vaapi.c"),
        .flags = &.{"-std=c11"},
    });
    inline for (.{ "libavcodec", "libavutil" }) |lib| {
        codec_mod.linkSystemLibrary(lib, .{ .needed = true, .use_pkg_config = .force });
    }
    codec_mod.linkSystemLibrary("libva", .{ .needed = true, .use_pkg_config = .force });
    const codec_work_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/codec/work_test.zig"),
        .target = target,
        .optimize = .Debug,
        .imports = &.{ .{ .name = "azk_codec", .module = codec_mod }, .{ .name = "av", .module = av_mod } },
    }) });
    b.step("codec-work-test", "Verify FFmpeg output and Codec2 work lifetimes").dependOn(&b.addRunArtifact(codec_work_tests).step);
    @import("build/codec2.zig").ffmpegTest(b, codec2, island.file, codec_mod, binder_cpp, binder_ndk, binder_broker, sf_service);
    const media_service = @import("build/codec2.zig").service(b, codec2, island.file, codec_mod, binder_cpp, binder_ndk, host_toolchain.resolver);
    const event_fd_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/event_fd.zig"),
        .target = target,
        .optimize = optimize,
    });
    exe.root_module.addImport("event_fd", event_fd_mod);
    surface_queue_mod.addImport("event_fd", event_fd_mod);
    const message_queue_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/message_queue.zig"),
        .target = target,
        .optimize = optimize,
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
            .{ .name = "message_queue", .module = message_queue_mod },
        },
    });
    exe.root_module.addImport("host_natives", host_natives_mod);
    exe.root_module.addImport("apk_optimizer", apk_optimizer_mod);
    exe.rdynamic = true;

    const wayland_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/platform/wayland.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .imports = &.{
                .{ .name = "wayland", .module = wayland_mod },
                .{ .name = "event_fd", .module = event_fd_mod },
            },
        }),
    });
    wayland_tests.root_module.linkSystemLibrary("wayland-client", .{ .needed = true });
    wayland_tests.root_module.linkSystemLibrary("xkbcommon", .{ .needed = true });
    const run_wayland_tests = b.addRunArtifact(wayland_tests);
    b.step("wayland-unit-test", "Test Wayland event ownership and protocol state").dependOn(&run_wayland_tests.step);
    const data_transfer_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/platform/data_transfers.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{.{ .name = "event_fd", .module = event_fd_mod }},
    }) });
    b.step("data-transfer-test", "Test bounded Wayland offer streams, deadlines and cancellation")
        .dependOn(&b.addRunArtifact(data_transfer_tests).step);
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
            .imports = &.{ .{ .name = "art", .module = art_mod }, .{ .name = "event_fd", .module = event_fd_mod } },
        }),
    });
    const run_test_control = b.addRunArtifact(test_control_check);
    b.step("test-control-test", "Verify bounded test-control socket ownership and framing").dependOn(&run_test_control.step);
    const platform_abi_test_mod = b.createModule(.{
        .root_source_file = b.path("src/native/platform_abi.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{
            .{ .name = "art", .module = art_mod },
            .{ .name = "graphics_types", .module = b.createModule(.{ .root_source_file = b.path("src/platform/graphics_types.zig") }) },
        },
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
    const launch_probe = b.addExecutable(.{
        .name = "binder-launch-probe",
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/binder/launch_probe.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    binder_broker_tests.root_module.addImport("aidl", broker_aidl);
    binder_broker_tests.root_module.addImport("servicemanager_aidl", servicemanager_aidl);
    const run_binder_broker_tests = b.addRunArtifact(binder_broker_tests);
    const launch_probe_options = b.addOptions();
    launch_probe_options.addOptionPath("path", launch_probe.getEmittedBin());
    binder_broker_tests.root_module.addOptions("launch_probe", launch_probe_options);
    const process_peer_test = b.addExecutable(.{
        .name = "process-peer-test",
        .root_module = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true }),
    });
    process_peer_test.root_module.addCSourceFiles(.{
        .files = &.{ "src/native/binder/ProcessPeer.c", "tests/native/ProcessPeerTest.c" },
        .flags = &.{ "-std=c11", "-UNDEBUG", "-Wall", "-Wextra", "-Werror" },
    });
    const run_process_peer_test = b.addRunArtifact(process_peer_test);
    b.step("process-peer-test", "Verify process control socket peer lifetime").dependOn(&run_process_peer_test.step);
    const process_app_data_test = b.addExecutable(.{
        .name = "process-app-data-test",
        .root_module = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true }),
    });
    process_app_data_test.root_module.addCSourceFiles(.{
        .files = &.{ "src/native/ProcessAppData.c", "tests/native/ProcessAppDataTest.c" },
        .flags = &.{ "-std=c11", "-UNDEBUG", "-Wall", "-Wextra", "-Werror" },
    });
    const run_process_app_data_test = b.addRunArtifact(process_app_data_test);
    b.step("process-app-data-test", "Verify child zygote app data isolation").dependOn(&run_process_app_data_test.step);
    const broker_test_step = b.step("binder-broker-test", "Run broker protocol unit tests");
    broker_test_step.dependOn(&run_binder_broker_tests.step);
    broker_test_step.dependOn(&run_process_peer_test.step);
    broker_test_step.dependOn(&run_process_app_data_test.step);
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
        .shell_manifest = aosp.path(b, "frameworks/base/packages/Shell/AndroidManifest.xml"),
        .pdfclient = island.pdfclient,
        .launcher = exe.getEmittedBin(),
        .broker = binder_broker.getEmittedBin(),
        .surfaceflinger = sf_service.getEmittedBin(),
        .media_service = media_service.getEmittedBin(),
        .task_presenter = task_presenter.getEmittedBin(),
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
        .webview_provider = webview_dep.path("webview.apk"),
        .desktop_transfer_provider = java.desktop_transfer_provider,
    });
    b.getInstallStep().dependOn(&b.addInstallDirectory(.{
        .source_dir = application_tree,
        .install_dir = .prefix,
        .install_subdir = "",
    }).step);

    const session_test = b.addExecutable(.{
        .name = "azk-session-test",
        .root_module = b.createModule(.{ .root_source_file = b.path("src/binder/session_test.zig"), .target = target, .optimize = .Debug }),
    });
    const session_test_run = b.addRunArtifact(session_test);
    session_test_run.addArg("--test");
    session_test_run.has_side_effects = true;
    b.step("session-test", "Verify service startup ordering, failures, deadlines and teardown").dependOn(&session_test_run.step);
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
    system_test_run.prependPathEnvironment("LD_LIBRARY_PATH", application_tree.path(b, "lib"));
    system_test_run.prependPathEnvironment("LD_LIBRARY_PATH", application_tree.path(b, "lib/art"));
    system_test_run.addFileArg(system_test_driver.getEmittedBin());
    system_test_run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    system_test_run.addFileArg(application_tree.path(b, "bin/azk-binder-broker"));
    system_test_run.addFileArg(java.fixtures.test_a);
    system_test_run.addFileArg(java.fixtures.test_b);
    system_test_run.addFileArg(ndk_system_client.getEmittedBin());
    system_test_run.addFileArg(java.fixtures.identity_a);
    system_test_run.addFileArg(java.fixtures.identity_b);
    system_test_run.addFileArg(application_tree.path(b, "bin/azk-surfaceflinger"));
    system_test_run.addFileArg(@import("build/service_fixtures.zig").build(b, aosp_dep, java));
    system_test_run.addFileArg(application_tree.path(b, "bin/azk-media-service"));
    system_test_run.addFileArg(media_service_probe.getEmittedBin());
    system_test_run.run.setName("run hosted system-service tests");
    b.step("system-test", "Run multi-process hosted system-service tests").dependOn(&system_test_run.run.step);

    const native_test_step = b.step("native-test", "Build Zig JNI adapters and run native unit tests");
    const scheduling_test = b.addExecutable(.{ .name = "thread-scheduling-test", .root_module = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = true,
    }) });
    scheduling_test.root_module.addIncludePath(aosp.path(b, "system/logging/liblog/include"));
    scheduling_test.root_module.addCSourceFile(.{ .file = b.path("src/native/binder/ThreadScheduling.cpp"), .flags = &.{
        "-std=c++20", "-D_GNU_SOURCE", "-Dsetpriority=azk_test_setpriority", "-Dgetpriority=azk_test_getpriority", "-Dtgkill=azk_test_tgkill", "-D__android_log_print=azk_test_log",
    } });
    scheduling_test.root_module.addCSourceFile(.{ .file = b.path("tests/native/ThreadSchedulingTest.cpp"), .flags = &.{ "-std=c++20", "-D_GNU_SOURCE", "-UNDEBUG" } });
    const scheduling_run = b.addRunArtifact(scheduling_test);
    b.step("thread-scheduling-test", "Verify permitted boosts and unprivileged thread-priority fallback").dependOn(&scheduling_run.step);
    native_test_step.dependOn(&scheduling_run.step);

    const process_backend_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/native/process_backend.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    }) });
    const run_process_backend_tests = b.addRunArtifact(process_backend_tests);
    b.step("process-backend-test", "Test authenticated proc identity translation").dependOn(&run_process_backend_tests.step);
    native_test_step.dependOn(&run_process_backend_tests.step);
    const kernel_metadata_tests = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/native/kernel_metadata.zig"),
        .target = target,
        .optimize = optimize,
    }) });
    const run_kernel_metadata_tests = b.addRunArtifact(kernel_metadata_tests);
    b.step("kernel-metadata-test", "Test running-kernel stack configuration metadata").dependOn(&run_kernel_metadata_tests.step);
    native_test_step.dependOn(&run_kernel_metadata_tests.step);
    const host_stack_step = b.step("host-stack-test", "Check ART stack bounds under Chromium-style affinity denial");
    const host_stack_test = b.addExecutable(.{
        .name = "host-stack-test",
        .root_module = b.createModule(.{ .target = target, .optimize = .ReleaseSafe, .link_libc = true }),
    });
    host_stack_test.root_module.addCSourceFile(.{ .file = b.path("tests/native/HostStackTest.c"), .flags = &.{ "-std=c11", "-Wall", "-Wextra", "-Werror" } });
    if (host_libc) |root| host_stack_test.root_module.addRPath(b.graph.cwdRelativePath(b.fmt("{s}/lib", .{root})));
    const host_stack_run = b.addRunArtifact(host_stack_test);
    host_stack_step.dependOn(&host_stack_run.step);
    if (host_libc != null) native_test_step.dependOn(host_stack_step);

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
        .root_source_file = b.path("src/native/egl_backend.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    }) });
    egl_backend_tests.root_module.addImport("graphics_types", b.createModule(.{ .root_source_file = b.path("src/platform/graphics_types.zig") }));
    for ([_][]const u8{ "egl", "gbm", "glesv2" }) |lib| egl_backend_tests.root_module.linkSystemLibrary(lib, .{ .needed = true });
    egl_backend_tests.root_module.addObjectFile(island.file);
    const run_egl_backend_tests = @import("build/command.zig").create(b);
    setApplicationEnvironment(run_egl_backend_tests, application_tree);
    run_egl_backend_tests.prependPathEnvironment("LD_LIBRARY_PATH", application_tree.path(b, "lib"));
    run_egl_backend_tests.addFileArg(egl_backend_tests.getEmittedBin());
    b.step("egl-test", "Test EGL offscreen rendering and surface lifetime").dependOn(&run_egl_backend_tests.run.step);
    native_test_step.dependOn(&run_egl_backend_tests.run.step);
    native_test_step.dependOn(&session_test_run.step);
    native_test_step.dependOn(&run_wayland_tests.step);
    native_test_step.dependOn(&codec_work_tests.step);
    native_test_step.dependOn(&run_apk_optimizer_tests.step);
    native_test_step.dependOn(&dexopt_transaction_test.step);
    native_test_step.dependOn(&run_test_control.step);
    const input_placement_test = b.addTest(.{ .root_module = b.createModule(.{
        .root_source_file = b.path("src/platform/test_input_placement.zig"),
        .target = target,
        .optimize = optimize,
    }) });
    native_test_step.dependOn(&b.addRunArtifact(input_placement_test).step);
    native_test_step.dependOn(&run_binder_broker_tests.step);
    native_test_step.dependOn(&ndk_abi_check.step);
    native_test_step.dependOn(&platform_abi_check.step);
    native_test_step.dependOn(&launcher_check.step);
    native_test_step.dependOn(&cpp_audit.step);

    // Compile the library-version fixture through the same projected/stored
    // context as real APKs, then require the stock loader to map its artifact.
    const loader_dexopt = @import("build/command.zig").create(b);
    setApplicationEnvironment(loader_dexopt, application_tree);
    loader_dexopt.addArg("bash");
    loader_dexopt.addFileArg(b.path("tools/dexopt-apk.sh"));
    loader_dexopt.addDirectoryArg(application_tree.path(b, "bin/art"));
    loader_dexopt.addFileArg(java.kotlin_shadow_fixture);
    loader_dexopt.addFileArg(application_tree.path(b, "framework/framework.jar"));
    const loader_cache = loader_dexopt.addOutputDirectoryArg("loader-dexopt-cache");
    loader_dexopt.addFileArg(java.app_context_jar);
    loader_dexopt.run.setName("compile stock APK loader fixture (speed)");

    const jvm_test_run = @import("build/command.zig").create(b);
    setApplicationEnvironment(jvm_test_run, application_tree);
    jvm_test_run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    jvm_test_run.addArg("--javatest");
    jvm_test_run.addFileArg(java.kotlin_shadow_fixture);
    jvm_test_run.addFileArg(java.classes_jar);
    jvm_test_run.addFileArg(java.platform_api_classes);
    jvm_test_run.setEnvironment("AZK_TEST_CLASS", "dev.artzig.KotlinClassLoaderSmoke");
    jvm_test_run.setPathEnvironment("ARTZIG_RUNTIME_CACHE_DIR", loader_cache);
    // Hermetic: a nonexistent WAYLAND_DISPLAY keeps the session's clipboard
    // monitor off the developer's real desktop clipboard (empty falls back
    // to wayland-0).
    jvm_test_run.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    jvm_test_run.run.setName("run JVM integration tests");
    const jvm_test_step = b.step("jvm-test", "Run JVM and Kotlin class-loading integration tests");
    jvm_test_step.dependOn(&jvm_test_run.run.step);
    jvm_test_step.dependOn(&identity_tests.step);
    b.step("classloader-jvm-test", "Verify stock APK loaders, platform identity and mapped AOT code")
        .dependOn(&jvm_test_run.run.step);
    @import("build/task_shell.zig").addGeometryTests(b, aosp_dep, jvm_test_step);
    const intent_policy = @import("build/command.zig").create(b);
    setApplicationEnvironment(intent_policy, application_tree);
    intent_policy.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    intent_policy.addArg("--javatest");
    intent_policy.setEnvironment("AZK_TEST_CLASS", "dev.artzig.IntentFirewallSmoke");
    intent_policy.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    _ = intent_policy.setOutputDirectoryEnvironment("ARTZIG_DATA_DIR", "intent-policy-data");
    intent_policy.run.setName("verify pinned intent policy and Linux file observation");
    intent_policy.run.has_side_effects = true;
    b.step("intent-policy-test", "Verify stock intent matching and live policy reload").dependOn(&intent_policy.run.step);
    jvm_test_step.dependOn(&intent_policy.run.step);
    const activity_options = @import("build/command.zig").create(b);
    setApplicationEnvironment(activity_options, application_tree);
    activity_options.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    activity_options.addArg("--javatest");
    activity_options.setEnvironment("AZK_TEST_CLASS", "dev.artzig.ActivityOptionsSmoke");
    activity_options.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    activity_options.run.setName("verify stock launch options, transition input and splash listener ownership");
    b.step("activity-options-test", "Verify stock launch options and transition input semantics").dependOn(&activity_options.run.step);
    jvm_test_step.dependOn(&activity_options.run.step);

    const buffer_jni_test = @import("build/buffer_jni.zig").testLibrary(b, target, aosp, generated.native.gui, binder_cpp, binder_ndk, island.file);
    const task_output = @import("build/command.zig").create(b);
    setApplicationEnvironment(task_output, application_tree);
    task_output.addArg("python3");
    task_output.addFileArg(b.path("tools/binder-facade-test.py"));
    task_output.addArgs(&.{ "--retire-broker", "--system-client", "--server" });
    task_output.addFileArg(sf_service.getEmittedBin());
    task_output.addArg("--broker");
    task_output.addFileArg(binder_broker.getEmittedBin());
    // Java registration and C++ DT_NEEDED must load the same installed runtime.
    task_output.addArg("--library");
    task_output.addFileArg(application_tree.path(b, "lib/art/libbinder.so"));
    task_output.addArg("--library");
    task_output.addFileArg(application_tree.path(b, "lib/art/libbinder_ndk.so"));
    task_output.addArg("--probe");
    task_output.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    task_output.addArg("--probe-arg=--javatest");
    task_output.addPrefixedFileArg("--probe-file-arg=", buffer_jni_test.getEmittedBin());
    task_output.addArg("--probe-arg=--task-outputs");
    task_output.setEnvironment("AZK_TEST_CLASS", "dev.artzig.SurfaceBufferSmoke");
    task_output.setEnvironment("AZK_BINDER_ROLE", "system");
    task_output.setEnvironment("AZK_WINDOW_BACKEND", "stock");
    task_output.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    task_output.addArg("--output");
    const task_output_results = task_output.addOutputDirectoryArg("task-output-jvm");
    task_output.run.has_side_effects = true;
    const task_output_install = b.addInstallDirectory(.{ .source_dir = task_output_results, .install_dir = .prefix, .install_subdir = "test-results/task-output-jvm" });
    b.step("task-output-jvm-test", "Verify private task output composition through stock Java/JNI and SF death").dependOn(&task_output_install.step);
    b.step("input-dispatcher-jvm-test", "Verify Java policy and receiver delivery from SF-published geometry").dependOn(&task_output_install.step);
    jvm_test_step.dependOn(&task_output_install.step);
    const buffer_jvm = @import("build/command.zig").create(b);
    setApplicationEnvironment(buffer_jvm, application_tree);
    buffer_jvm.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    buffer_jvm.addArg("--javatest");
    buffer_jvm.addFileArg(buffer_jni_test.getEmittedBin());
    buffer_jvm.setEnvironment("AZK_TEST_CLASS", "dev.artzig.BufferJniSmoke");
    buffer_jvm.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    buffer_jvm.run.setName("verify stock Java buffer ownership, pixels and fences");
    buffer_jvm.run.has_side_effects = true;
    const buffer_jvm_log = b.addInstallFileWithDir(buffer_jvm.run.captureStdOut(.{}), .prefix, "test-results/buffer-jvm/run.log");
    b.step("buffer-jvm-test", "Verify stock GraphicBuffer, HardwareBuffer and SyncFence JNI").dependOn(&buffer_jvm_log.step);
    b.step("image-queue-jvm-test", "Verify stock image queues, ownership transfer and transition luminance").dependOn(&buffer_jvm_log.step);
    const texture_jvm = @import("build/command.zig").create(b);
    setApplicationEnvironment(texture_jvm, application_tree);
    texture_jvm.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    texture_jvm.addArg("--javatest");
    texture_jvm.addFileArg(buffer_jni_test.getEmittedBin());
    texture_jvm.setEnvironment("AZK_TEST_CLASS", "dev.artzig.SurfaceBufferSmoke");
    texture_jvm.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    texture_jvm.run.setName("verify Surface queues and native scheduling alongside Wayland owner pacing");
    texture_jvm.run.has_side_effects = true;
    const texture_jvm_log = b.addInstallFileWithDir(texture_jvm.run.captureStdOut(.{}), .prefix, "test-results/surface-buffer-jvm/run.log");
    b.step("surface-buffer-jvm-test", "Verify stock Surface queues, private task outputs and JNI lifetimes").dependOn(&texture_jvm_log.step);
    const surface_jvm = @import("build/command.zig").create(b);
    setApplicationEnvironment(surface_jvm, application_tree);
    surface_jvm.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    surface_jvm.addArg("--javatest");
    surface_jvm.setEnvironment("AZK_TEST_CLASS", "dev.artzig.SurfaceClientSmoke");
    surface_jvm.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    surface_jvm.run.setName("verify stock Java surface clients and frame dispatch through SF");
    surface_jvm.run.has_side_effects = true;
    const surface_jvm_log = b.addInstallFileWithDir(surface_jvm.run.captureStdOut(.{}), .prefix, "test-results/surface-client-jvm/run.log");
    const surface_jvm_step = b.step("surface-client-jvm-test", "Verify stock SurfaceControl transactions, ownership and vsync");
    surface_jvm_step.dependOn(&surface_jvm_log.step);
    jvm_test_step.dependOn(surface_jvm_step);
    const input_handle_test = @import("build/input_jni.zig").testLibrary(b, target, aosp, generated.native.gui, binder_cpp, binder_ndk, island.file);
    const input_channel_jvm = @import("build/command.zig").create(b);
    setApplicationEnvironment(input_channel_jvm, application_tree);
    input_channel_jvm.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    input_channel_jvm.addArg("--javatest");
    input_channel_jvm.addFileArg(input_handle_test.getEmittedBin());
    input_channel_jvm.setEnvironment("AZK_TEST_CLASS", "dev.artzig.InputChannelSmoke");
    input_channel_jvm.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    input_channel_jvm.run.setName("verify stock input channels, event JNI and MessageQueue callbacks");
    input_channel_jvm.run.has_side_effects = true;
    const input_channel_log = b.addInstallFileWithDir(input_channel_jvm.run.captureStdOut(.{}), .prefix, "test-results/input-channel-jvm/run.log");
    const input_channel_jvm_step = b.step("input-channel-jvm-test", "Verify stock input JNI, Binder identity, Parcel, event callbacks and Looper");
    input_channel_jvm_step.dependOn(&input_channel_log.step);
    jvm_test_step.dependOn(input_channel_jvm_step);
    const stock_window_boot = @import("build/command.zig").create(b);
    setApplicationEnvironment(stock_window_boot, application_tree);
    stock_window_boot.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    stock_window_boot.addArg("--javatest");
    stock_window_boot.addFileArg(window_fixture.apk);
    stock_window_boot.setEnvironment("AZK_TEST_CLASS", "dev.artzig.window.StockWindowBootSmoke");
    stock_window_boot.setEnvironment("AZK_BINDER_PACKAGE", "dev.artzig.fixtures.windows");
    stock_window_boot.setEnvironment("AZK_WINDOW_BACKEND", "stock");
    stock_window_boot.setEnvironment("AZK_TEST_INSPECTION", "1");
    stock_window_boot.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    stock_window_boot.run.setName("verify stock WMS and ATMS session startup");
    stock_window_boot.run.has_side_effects = true;
    b.step("stock-window-boot-test", "Boot stock window/task services and query their shared display")
        .dependOn(&stock_window_boot.run.step);
    const stock_cold_launch = @import("build/command.zig").create(b);
    setApplicationEnvironment(stock_cold_launch, application_tree);
    stock_cold_launch.addFileArg(system_test_driver.getEmittedBin());
    stock_cold_launch.addArg("--cold-window");
    stock_cold_launch.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    stock_cold_launch.addFileArg(application_tree.path(b, "bin/azk-binder-broker"));
    stock_cold_launch.addFileArg(application_tree.path(b, "bin/azk-surfaceflinger"));
    stock_cold_launch.addFileArg(window_fixture.apk);
    stock_cold_launch.setEnvironment("AZK_WINDOW_BACKEND", "stock");
    stock_cold_launch.setEnvironment("AZK_TEST_INSPECTION", "1");
    stock_cold_launch.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    stock_cold_launch.run.has_side_effects = true;
    stock_cold_launch.run.setName("verify install-time uid and stock ATMS first-process launch");
    b.step("stock-cold-launch-test", "Install an APK and launch its first process through stock ATMS")
        .dependOn(&stock_cold_launch.run.step);
    const suspension_compositor = @import("build/command.zig").create(b);
    suspension_compositor.addArgs(&.{ "nix", "build", "--impure", "--cores", "6", "--max-jobs", "1", "--file" });
    suspension_compositor.addFileArg(b.path("testdata/wayland/labwc-suspension.nix"));
    suspension_compositor.run.addFileInput(b.path("testdata/wayland/labwc-suspension.patch"));
    suspension_compositor.run.addFileInput(b.path("testdata/wayland/labwc-tablet.patch"));
    suspension_compositor.run.addFileInput(b.path("testdata/wayland/labwc-ime.patch"));
    suspension_compositor.run.addFileInput(b.path("testdata/wayland/labwc-clipboard.patch"));
    suspension_compositor.run.addFileInput(artzig_dep.path("flake.nix"));
    suspension_compositor.run.addFileInput(artzig_dep.path("flake.lock"));
    suspension_compositor.addArg("--out-link");
    const suspension_labwc = suspension_compositor.addOutputChildArg("suspension-compositor", "labwc");
    suspension_compositor.run.setName("build pinned labwc suspension protocol fixture");
    for ([_][]const u8{ "cage", "labwc", "labwc-suspension", "labwc-legacy", "labwc-tablet", "labwc-outputs" }) |scenario| {
        const legacy_shell = std.mem.eql(u8, scenario, "labwc-legacy");
        const mixed_outputs = std.mem.eql(u8, scenario, "labwc-outputs");
        const tablet_fixture = std.mem.eql(u8, scenario, "labwc-tablet");
        const suspension_fixture = std.mem.eql(u8, scenario, "labwc-suspension") or tablet_fixture;
        const compositor = if (legacy_shell or suspension_fixture or mixed_outputs) "labwc" else scenario;
        const task_artifacts = b.pathResolve(&.{b.fmt("zig-out/test-results/stock-wayland-task-{s}", .{scenario})});
        const stock_task_wayland = @import("build/command.zig").create(b);
        setApplicationEnvironment(stock_task_wayland, application_tree);
        stock_task_wayland.setEnvironment("AZK_WINDOW_BACKEND", "stock");
        stock_task_wayland.setEnvironment("AZK_TEST_INSPECTION", "1");
        stock_task_wayland.setEnvironment("AZK_TASK_WINDOWS", "1");
        stock_task_wayland.setEnvironment("WLR_RENDERER", "gles2");
        stock_task_wayland.setEnvironment("SHOT_COMPOSITOR", compositor);
        stock_task_wayland.setEnvironment("XKB_DEFAULT_LAYOUT", "us");
        if (mixed_outputs) {
            stock_task_wayland.setEnvironment("WLR_HEADLESS_OUTPUTS", "2");
            stock_task_wayland.setEnvironment("AZK_TEST_MIXED_OUTPUTS", "1");
        }
        if (tablet_fixture) stock_task_wayland.setEnvironment("AZK_TEST_TABLET", "1");
        if (legacy_shell) stock_task_wayland.setEnvironment("AZK_TEST_XDG_SHELL_VERSION", "2");
        if (legacy_shell or suspension_fixture)
            stock_task_wayland.setFileEnvironment("SHOT_LABWC_BINARY", suspension_labwc.path(b, "bin/labwc"));
        stock_task_wayland.setEnvironment("AZK_TEST_SUSPENSION_EXPECTED", if (suspension_fixture) "1" else "0");
        if (std.mem.eql(u8, compositor, "labwc"))
            stock_task_wayland.setEnvironment("SHOT_LABWC_CONFIG", b.pathResolve(&.{if (mixed_outputs) "testdata/wayland/task-outputs.xml" else "testdata/wayland/task-desktop.xml"}));
        stock_task_wayland.addFileArg(b.path("tools/shot.sh"));
        stock_task_wayland.addArgs(&.{ b.pathResolve(&.{ task_artifacts, "screenshot.png" }), "--runner", "--", "python3" });
        stock_task_wayland.addFileArg(b.path("tests/native/stock-wayland-task-test.py"));
        stock_task_wayland.addArg("--seat");
        stock_task_wayland.addFileArg(surface_server.getEmittedBin());
        stock_task_wayland.addArgs(&.{ "--output", task_artifacts, "--" });
        stock_task_wayland.addFileArg(system_test_driver.getEmittedBin());
        stock_task_wayland.addArg("--cold-window");
        stock_task_wayland.addFileArg(application_tree.path(b, "bin/androlinzberg"));
        stock_task_wayland.addFileArg(application_tree.path(b, "bin/azk-binder-broker"));
        stock_task_wayland.addFileArg(application_tree.path(b, "bin/azk-surfaceflinger"));
        stock_task_wayland.addFileArg(window_fixture.apk);
        stock_task_wayland.addFileArg(application_tree.path(b, "bin/azk-task-presenter"));
        stock_task_wayland.run.has_side_effects = true;
        stock_task_wayland.run.setName("verify stock APK task pixels through session Wayland presenter");
        b.step(if (mixed_outputs) "stock-task-output-test" else if (tablet_fixture) "stock-task-tablet-test" else if (suspension_fixture) "stock-task-desktop-suspension-test" else if (legacy_shell) "stock-task-desktop-legacy-test" else if (std.mem.eql(u8, compositor, "cage")) "stock-task-wayland-test" else "stock-task-desktop-test", "Present two stock APK tasks through the session-owned Wayland service")
            .dependOn(&stock_task_wayland.run.step);
    }
    for ([_][]const u8{ "desktop", "video", "ime", "clipboard", "drag", "drag-pressure" }) |fixture| {
        const video_fixture = std.mem.eql(u8, fixture, "video");
        const ime_fixture = std.mem.eql(u8, fixture, "ime");
        const clipboard_fixture = std.mem.eql(u8, fixture, "clipboard");
        const drag_pressure = std.mem.eql(u8, fixture, "drag-pressure");
        const drag_fixture = std.mem.eql(u8, fixture, "drag") or drag_pressure;
        const desktop_launcher = @import("build/command.zig").create(b);
        setApplicationEnvironment(desktop_launcher, application_tree);
        desktop_launcher.setEnvironment("AZK_WINDOW_BACKEND", "stock");
        desktop_launcher.setEnvironment("AZK_TASK_WINDOWS", "1");
        desktop_launcher.setEnvironment("WLR_RENDERER", "gles2");
        desktop_launcher.setEnvironment("SHOT_COMPOSITOR", "labwc");
        desktop_launcher.setEnvironment("XKB_DEFAULT_LAYOUT", "us");
        if (ime_fixture) {
            desktop_launcher.setEnvironment("AZK_TEST_IME", "1");
            desktop_launcher.setFileEnvironment("SHOT_LABWC_BINARY", suspension_labwc.path(b, "bin/labwc"));
        }
        if (clipboard_fixture or drag_fixture) {
            desktop_launcher.setEnvironment("AZK_TEST_CLIPBOARD", "1");
            desktop_launcher.setFileEnvironment("SHOT_LABWC_BINARY", suspension_labwc.path(b, "bin/labwc"));
        }
        desktop_launcher.addArg("env");
        desktop_launcher.addPrefixedFileArg("SHOT_LABWC_CONFIG=", b.path("testdata/wayland/launcher-desktop.xml"));
        desktop_launcher.addFileArg(b.path("tools/shot.sh"));
        const launcher_artifacts = b.pathResolve(&.{if (video_fixture) "zig-out/test-results/stock-video" else if (ime_fixture) "zig-out/test-results/stock-ime" else if (clipboard_fixture) "zig-out/test-results/stock-clipboard" else if (drag_pressure) "zig-out/test-results/stock-drag-pressure" else if (drag_fixture) "zig-out/test-results/stock-drag" else "zig-out/test-results/stock-desktop-launcher"});
        desktop_launcher.addArgs(&.{ b.pathResolve(&.{ launcher_artifacts, "screenshot.png" }), "--runner", "--", "python3" });
        desktop_launcher.addFileArg(b.path("tests/native/stock-wayland-task-test.py"));
        if (video_fixture) desktop_launcher.addArg("--video");
        if (ime_fixture) desktop_launcher.addArg("--ime");
        if (clipboard_fixture) desktop_launcher.addArg("--clipboard");
        if (drag_fixture) desktop_launcher.addArg("--drag");
        if (drag_pressure) desktop_launcher.addArgs(&.{ "--drag-rounds", "16" });
        desktop_launcher.addArgs(&.{ "--launcher", "--testkit" });
        desktop_launcher.addDirectoryArg(testkit_tree.getDirectory());
        desktop_launcher.addArg("--seat");
        desktop_launcher.addFileArg(surface_server.getEmittedBin());
        desktop_launcher.addArgs(&.{ "--output", launcher_artifacts, "--" });
        desktop_launcher.addFileArg(application_tree.path(b, "bin/androlinzberg"));
        desktop_launcher.addFileArg(window_fixture.apk);
        desktop_launcher.run.has_side_effects = true;
        desktop_launcher.run.setName("verify APK desktop launch and compositor close through ATMS");
        b.step(if (video_fixture) "stock-video-test" else if (ime_fixture) "stock-ime-test" else if (clipboard_fixture) "stock-clipboard-test" else if (drag_pressure) "stock-drag-pressure-test" else if (drag_fixture) "stock-drag-test" else "stock-desktop-launcher-test", "Launch an APK through ATMS and verify task-scoped UI through Wayland")
            .dependOn(&desktop_launcher.run.step);
    }
    const session_loss_step = b.step("stock-session-loss-test", "Retire an idle APK session after service or compositor death");
    for ([_][]const u8{ "system", "surfaceflinger", "presenter", "media", "broker", "compositor" }) |fault| {
        const run = @import("build/command.zig").create(b);
        setApplicationEnvironment(run, application_tree);
        run.setEnvironment("AZK_WINDOW_BACKEND", "stock");
        run.setEnvironment("AZK_TASK_WINDOWS", "1");
        run.setEnvironment("WLR_RENDERER", "gles2");
        run.setEnvironment("SHOT_COMPOSITOR", "labwc");
        if (std.mem.eql(u8, fault, "compositor")) run.setEnvironment("SHOT_EXPECT_COMPOSITOR_LOSS", "1");
        run.addArg("env");
        run.addPrefixedFileArg("SHOT_LABWC_CONFIG=", b.path("testdata/wayland/launcher-desktop.xml"));
        run.addFileArg(b.path("tools/shot.sh"));
        const output = b.pathResolve(&.{ "zig-out/test-results/stock-session-loss", fault });
        run.addArgs(&.{ b.pathResolve(&.{ output, "screenshot.png" }), "--runner", "--", "python3" });
        run.addFileArg(b.path("tests/native/desktop-session-loss-test.py"));
        run.addArgs(&.{ "--fault", fault, "--output", output, "--seat" });
        run.addFileArg(surface_server.getEmittedBin());
        run.addArg("--");
        run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
        run.addFileArg(window_fixture.apk);
        run.run.has_side_effects = true;
        run.run.setName(b.fmt("verify desktop session retirement after {s} loss", .{fault}));
        session_loss_step.dependOn(&run.run.step);
    }
    const framework_module_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(framework_module_smoke, application_tree);
    framework_module_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    framework_module_smoke.addArg("--javatest");
    framework_module_smoke.setEnvironment(
        "AZK_TEST_CLASS",
        "dev.artzig.FrameworkModuleSmoke",
    );
    for ([_][]const u8{ "thumbnail.mp4", "thumbnail.webm", "tone.flac", "tone.ogg", "tone.opus", "tone.ts", "tone.mpg", "silence.amr" }) |fixture|
        framework_module_smoke.run.addFileInput(b.path(b.fmt("testdata/media/{s}", .{fixture})));
    framework_module_smoke.setEnvironment("AZK_TEST_THUMBNAIL", b.pathResolve(&.{"testdata/media/thumbnail.mp4"}));
    framework_module_smoke.setEnvironment("AZK_BINDER_PACKAGE", "org.example.permissions");
    framework_module_smoke.setEnvironment("AZK_WINDOW_BACKEND", "stock");
    // Fixture APKs (installed server-side by the permission-grant test).
    framework_module_smoke.addFileArg(java.fixtures.permissions);
    framework_module_smoke.addFileArg(java.fixtures.example);
    // Hermetic: a nonexistent WAYLAND_DISPLAY keeps the session's clipboard
    // monitor off the developer's real desktop clipboard (empty falls back
    // to wayland-0).
    framework_module_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    framework_module_smoke.run.setName("run hosted framework module tests");
    b.step("framework-module-test", "Test stock framework values and hosted service contracts").dependOn(&framework_module_smoke.run.step);
    jvm_test_step.dependOn(&framework_module_smoke.run.step);
    const audio_delivery = @import("build/command.zig").create(b);
    setApplicationEnvironment(audio_delivery, application_tree);
    audio_delivery.addArg("python3");
    audio_delivery.addFileArg(b.path("tests/native/stock-audio-delivery-test.py"));
    audio_delivery.addArgs(&.{ "--output", "zig-out/test-results/stock-audio-delivery", "--" });
    audio_delivery.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    audio_delivery.addArg("--javatest");
    audio_delivery.setEnvironment("AZK_TEST_CLASS", "dev.artzig.StockAudioDeliverySmoke");
    audio_delivery.run.addArgs(&.{ "--env-path", "AZK_TEST_APK" });
    audio_delivery.run.addFileArg(java.fixtures.example);
    audio_delivery.run.addFileInput(b.path("tests/native/audio_capture_analysis.py"));
    audio_delivery.run.has_side_effects = true;
    audio_delivery.run.setName("verify stock AudioTrack PCM at a private PipeWire sink");
    const audio_capture_checks = b.addSystemCommand(&.{"python3"});
    audio_capture_checks.addFileArg(b.path("tests/native/audio_capture_analysis.py"));
    audio_delivery.run.step.dependOn(&audio_capture_checks.step);
    b.step("stock-audio-delivery-test", "Capture known stock AudioTrack PCM through the hosted service and PipeWire")
        .dependOn(&audio_delivery.run.step);
    const webview_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(webview_smoke, application_tree);
    webview_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    webview_smoke.addArg("--javatest");
    webview_smoke.setEnvironment("AZK_TEST_CLASS", "dev.artzig.StockWebViewSmoke");
    webview_smoke.run.addArgs(&.{ "--env-path", "AZK_TEST_APK" });
    webview_smoke.run.addFileArg(java.fixtures.example);
    webview_smoke.setEnvironment("WAYLAND_DISPLAY", "azk-hermetic-none");
    b.step("webview-test", "Run stock WebView against the bundled Android Chromium provider").dependOn(&webview_smoke.run.step);
    const framework_compat_smoke = @import("build/command.zig").create(b);
    setApplicationEnvironment(framework_compat_smoke, application_tree);
    framework_compat_smoke.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    framework_compat_smoke.addArg("--javatest");
    framework_compat_smoke.setEnvironment(
        "AZK_TEST_CLASS",
        "dev.artzig.FrameworkCompatSmoke",
    );
    framework_compat_smoke.setEnvironment("AZK_BINDER_PACKAGE", "org.example");
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
    framework_compat_smoke.setEnvironment("AZK_TASK_WINDOWS", "0");
    framework_compat_smoke.run.setName("run framework compatibility smoke");
    jvm_test_step.dependOn(&framework_compat_smoke.run.step);
    b.step("framework-compat-test", "Verify framework contracts independently of the window client selection")
        .dependOn(&framework_compat_smoke.run.step);
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
    b.step("binder-jni-test", "Verify stock JNI Java/NDK Binder and Parcel ownership").dependOn(&ndk_bridge_test.run.step);
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
    integration_run.setFileEnvironment("AZK_APK_REGISTRY", window_fixture.registry);
    integration_run.setFileEnvironment("AZK_APK_WINDOW_FIXTURE", window_fixture.apk);
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

    // UI tests on ART: JUnit + androidx UiAutomator over stock UiAutomation,
    // one session per test, orchestrated by tools/ui-test-runner.zig under Cage.
    const ui_tests = @import("build/ui_tests.zig");
    const ui_tests_dex = ui_tests.build(b, aosp_dep, java, testkit.jars.junit_hamcrest);
    const ui_test_runner = b.addExecutable(.{ .name = "ui-test-runner", .root_module = b.createModule(.{
        .root_source_file = b.path("tools/ui-test-runner.zig"),
        .target = b.graph.host,
        .optimize = .ReleaseSafe,
    }) });
    // The presenter probe: a native inspector on the Zig generated from the
    // presenter's AIDL. It never carries a Surface, so its aidl_custom is inert.
    const probe_parcelables = b.createModule(.{
        .root_source_file = b.path("tests/ui/probe/parcelables.zig"),
        .target = target,
        .optimize = optimize,
        .imports = &.{.{ .name = "aidl", .module = aidl_runtime }},
    });
    const presenter_probe = b.addExecutable(.{ .name = "presenter-probe", .root_module = b.createModule(.{
        .root_source_file = b.path("tests/ui/probe/presenter_probe.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{
            .{ .name = "aidl", .module = aidl_runtime },
            .{ .name = "task_presenter_aidl", .module = aidl_zig.zigModule(b, generated.aidl_tool, .{
                .root = b.path("aidl-compat"),
                .files = &.{ "dev/artzig/window/IWaylandTaskEvents.aidl", "dev/artzig/window/IWaylandTaskPresenter.aidl" },
                .runtime = aidl_runtime,
                .custom = probe_parcelables,
                .target = target,
                .optimize = optimize,
            }) },
        },
    }) });
    presenter_probe.root_module.linkLibrary(binder_ndk);
    const ui_test_run = @import("build/command.zig").create(b);
    setApplicationEnvironment(ui_test_run, application_tree);
    ui_test_run.setEnvironment("WLR_RENDERER", "gles2");
    ui_test_run.addArg("bash");
    ui_test_run.addFileArg(b.path("tools/shot.sh"));
    ui_test_run.addArgs(&.{ "zig-out/test-results/ui/suite.png", "--runner", "--" });
    ui_test_run.addFileArg(ui_test_runner.getEmittedBin());
    ui_test_run.addArg("--launcher");
    ui_test_run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    ui_test_run.addArg("--apk");
    ui_test_run.addFileArg(window_fixture.apk);
    ui_test_run.addArg("--dex");
    ui_test_run.addFileArg(ui_tests_dex);
    ui_test_run.addArg("--probe");
    ui_test_run.addFileArg(presenter_probe.getEmittedBin());
    ui_test_run.addArgs(&.{ "--artifacts", "zig-out/test-results/ui" });
    ui_test_run.addArgs(&ui_tests.pinned_apks);
    ui_test_run.addArg("--named-apk");
    ui_test_run.addPrefixedFileArg("webview=", java.fixtures.webview);
    ui_test_run.addArgs(&ui_tests.tests);
    ui_test_run.addPassthruArgs();
    ui_test_run.run.setName("run UiAutomator tests on ART, one session per test");
    b.step("ui-test", "Run JUnit/UiAutomator UI tests on ART under Cage").dependOn(&ui_test_run.run.step);

    // Desktop (labwc) scenarios: one window fixture launched through ATMS as a
    // task window, with the protocol under test enabled in the session.
    inline for (.{
        .{ .step = "ui-desktop-clipboard-test", .directory = "stock-clipboard", .environment = "AZK_TEST_CLIPBOARD", .tests = &[_][]const u8{"dev.artzig.uitest.host.DesktopClipboardUiTest#representationsCrossTheDesktopSelectionInBothDirections"} },
        .{ .step = "ui-desktop-ime-test", .directory = "stock-ime", .environment = "AZK_TEST_IME", .tests = &[_][]const u8{"dev.artzig.uitest.host.DesktopImeUiTest#unicodeCompositionSurvivesFocusAndProtocolChanges"} },
    }) |scenario| {
        const ui = @import("build/command.zig").create(b);
        setApplicationEnvironment(ui, application_tree);
        ui.setEnvironment("AZK_WINDOW_BACKEND", "stock");
        ui.setEnvironment("AZK_TASK_WINDOWS", "1");
        ui.setEnvironment("WLR_RENDERER", "gles2");
        ui.setEnvironment("SHOT_COMPOSITOR", "labwc");
        ui.setEnvironment("XKB_DEFAULT_LAYOUT", "us");
        ui.setEnvironment(scenario.environment, "1");
        ui.setFileEnvironment("SHOT_LABWC_BINARY", suspension_labwc.path(b, "bin/labwc"));
        ui.addArg("env");
        ui.addPrefixedFileArg("SHOT_LABWC_CONFIG=", b.path("testdata/wayland/launcher-desktop.xml"));
        ui.addFileArg(b.path("tools/shot.sh"));
        ui.addArgs(&.{ b.fmt("zig-out/test-results/{s}/suite.png", .{scenario.directory}), "--runner", "--" });
        ui.addFileArg(ui_test_runner.getEmittedBin());
        ui.addArg("--launcher");
        ui.addFileArg(application_tree.path(b, "bin/androlinzberg"));
        ui.addArg("--apk");
        ui.addFileArg(window_fixture.apk);
        ui.addArg("--dex");
        ui.addFileArg(ui_tests_dex);
        ui.addArg("--probe");
        ui.addFileArg(presenter_probe.getEmittedBin());
        ui.addArg("--seat");
        ui.addFileArg(surface_server.getEmittedBin());
        ui.addArgs(&.{ "--artifacts", b.fmt("zig-out/test-results/{s}", .{scenario.directory}) });
        ui.addArgs(scenario.tests);
        ui.addPassthruArgs();
        ui.run.has_side_effects = true;
        ui.run.setName(b.fmt("run {s} on ART under labwc", .{scenario.step}));
        b.step(scenario.step, "Desktop protocol scenario through UiAutomator on ART").dependOn(&ui.run.step);
    }

    // Stock WebView through the same runner. The overlay variant also enables
    // Chromium's SurfaceControl overlays and checks its rendering trace.
    inline for (.{
        .{ .step = "webview-ui-test", .directory = "webview", .overlay = false, .description = "Render and interact with stock WebView through UiAutomator under Cage" },
        .{ .step = "webview-overlay-test", .directory = "webview-overlay", .overlay = true, .description = "Verify Chromium platform decoding, overlay transactions and retained video pixels" },
    }) |fixture| {
        const ui = @import("build/command.zig").create(b);
        setApplicationEnvironment(ui, application_tree);
        ui.setEnvironment("WLR_RENDERER", "gles2");
        const trace = b.fmt("zig-out/test-results/{s}/rendering-trace.json", .{fixture.directory});
        if (fixture.overlay) {
            ui.setEnvironment("AZK_WEBVIEW_VIDEO_SOURCE", "avc");
            ui.setEnvironment("AZK_WEBVIEW_TRACE_OUT", b.pathResolve(&.{trace}));
            ui.setEnvironment("AZK_WEBVIEW_CONFIGURE_OVERLAYS", "1");
        }
        ui.addArg("bash");
        ui.addFileArg(b.path("tools/shot.sh"));
        ui.addArgs(&.{ b.fmt("zig-out/test-results/{s}/suite.png", .{fixture.directory}), "--runner", "--" });
        ui.addFileArg(ui_test_runner.getEmittedBin());
        ui.addArg("--launcher");
        ui.addFileArg(application_tree.path(b, "bin/androlinzberg"));
        ui.addArg("--apk");
        ui.addFileArg(java.fixtures.webview);
        ui.addArg("--dex");
        ui.addFileArg(ui_tests_dex);
        ui.addArg("--probe");
        ui.addFileArg(presenter_probe.getEmittedBin());
        ui.addArgs(&.{ "--artifacts", b.fmt("zig-out/test-results/{s}", .{fixture.directory}), "dev.artzig.uitest.WebViewUiTest#rendersInteractsAndPlaysVideo" });
        ui.run.setName(b.fmt("run {s} on ART", .{fixture.step}));
        const step = b.step(fixture.step, fixture.description);
        if (fixture.overlay) {
            const check = b.addSystemCommand(&.{ "python3", "-B" });
            check.addFileArg(b.path("tests/webview-overlay-check.py"));
            check.addArg(trace);
            check.step.dependOn(&ui.run.step);
            check.has_side_effects = true;
            step.dependOn(&check.step);
        } else step.dependOn(&ui.run.step);
    }

    // A host ART process joins the session as the broker's inspector role and
    // drives the fixture through stock UiAutomation and AccessibilityManagerService.
    const uiautomation_run = @import("build/command.zig").create(b);
    uiautomation_run.setFileEnvironment("AZK_LAUNCHER", application_tree.path(b, "bin/androlinzberg"));
    setApplicationEnvironment(uiautomation_run, application_tree);
    uiautomation_run.setEnvironment("WLR_RENDERER", "gles2");
    uiautomation_run.setFileEnvironment("AZK_APK_WINDOW_FIXTURE", window_fixture.apk);
    uiautomation_run.addArg("bash");
    uiautomation_run.addFileArg(b.path("tools/shot.sh"));
    uiautomation_run.addArgs(&.{ "zig-out/test-results/uiautomation/suite.png", "--runner", "--" });
    uiautomation_run.addFileArg(b.path("tests/uiautomation/inspector-smoke"));
    uiautomation_run.run.setName("drive the window fixture through stock UiAutomation as inspector");
    b.step("uiautomation-test", "Drive an APK from a host process through stock UiAutomation").dependOn(&uiautomation_run.run.step);

    const run = @import("build/command.zig").create(b);
    setApplicationEnvironment(run, application_tree);
    run.addFileArg(application_tree.path(b, "bin/androlinzberg"));
    run.addPassthruArgs();
    run.run.setName("run androlinzberg");
    b.step("run", "Run the launcher: zig build run -- <app.apk>").dependOn(&run.run.step);

    const binder_jni_audit = b.step("binder-jni-source-test", "Verify compiled stock Binder JNI inputs, headers and ELF owners");
    const native_client_audit = b.step("native-client-source-test", "Audit compiled native island sources, flags, restored client headers and ELF owners");
    const source_identity = b.step("source-identity-test", "Compare exact compiled static sources with a clean pinned AOSP checkout");
    if (b.option([]const u8, "aosp-reference", "Absolute path to the clean pinned AOSP reference checkout")) |reference| {
        const compare = b.addSystemCommand(&.{ "python3", "-B" });
        compare.addFileArg(b.path("tools/aosp-source-identity.py"));
        compare.addArg("--reference");
        // External operator-supplied reference, not a project/generated input.
        // The comparison always runs so changes in this checkout are observed.
        if (!std.fs.path.isAbsolute(reference))
            std.debug.panic("aosp-reference must be an absolute checkout path", .{});
        native_client_audit.dependOn(@import("build/native_provenance.zig").add(b, std.mem.concat(b.allocator, *std.Build.Step.Run, &.{ island.native_compiles, skia_build.compiles }) catch @panic("oom"), generated.native.input.rust.?, linked_native_inputs, reference, aosp, island.file, binder_ndk.getEmittedBin(), island.pdfclient));
        compare.addArg(reference);
        compare.addArg("--package");
        compare.addDirectoryArg(aosp);
        compare.addArg("--classes");
        compare.addFileArg(java.classes_jar);
        for (java.static_sources) |sources| {
            compare.addArg("--sources");
            compare.addFileArg(sources);
        }
        compare.addArg("--output");
        const report = compare.addOutputFileArg("source-identity.json");
        // Read the reference anew on each invocation, including local changes.
        compare.has_side_effects = true;
        const install_report = b.addInstallFileWithDir(report, .prefix, "test-results/source-identity.json");
        source_identity.dependOn(&install_report.step);
        const binder_compare = b.addSystemCommand(&.{ "python3", "-B" });
        binder_compare.addFileArg(b.path("tools/binder-jni-audit.py"));
        binder_compare.addArgs(&.{ "--reference", reference, "--repo" });
        binder_compare.addDirectoryArg(b.path(""));
        for (island.binder_jni_sources) |source| {
            binder_compare.addArg("--source");
            binder_compare.addFileArg(source);
        }
        for (island.binder_jni_includes) |include| {
            binder_compare.addArg("--include");
            binder_compare.addDirectoryArg(include);
        }
        binder_compare.addArg("--platform");
        binder_compare.addFileArg(island.file);
        binder_compare.addArg("--ndk");
        binder_compare.addArtifactArg(binder_ndk);
        binder_compare.addArg("--output");
        const binder_report = binder_compare.addOutputFileArg("binder-jni-source.json");
        binder_compare.has_side_effects = true;
        const install_binder_report = b.addInstallFileWithDir(binder_report, .prefix, "test-results/binder-jni-source.json");
        binder_jni_audit.dependOn(&install_binder_report.step);
    } else {
        source_identity.dependOn(&b.addFail("source-identity-test requires -Daosp-reference=/absolute/path/to/clean/android-16.0.0_r1").step);
        binder_jni_audit.dependOn(&b.addFail("binder-jni-source-test requires -Daosp-reference=/absolute/path/to/clean/android-16.0.0_r1").step);
        native_client_audit.dependOn(&b.addFail("native-client-source-test requires -Daosp-reference=/absolute/path/to/clean/android-16.0.0_r1").step);
    }

    const acceptance = b.step("acceptance-test", "Run the complete stock-client acceptance suite and source comparison");
    const acceptance_environment = b.addSystemCommand(&.{ "python3", "-c" });
    acceptance_environment.addArg(
        \\import os, sys
        \\if len(sys.argv) != 1:
        \\    sys.exit('acceptance-test runs the complete suite; use individual targets for test filters')
        \\if os.environ.get('AZK_TEST_REQUIRE_EGL') != '1':
        \\    sys.exit('acceptance-test requires AZK_TEST_REQUIRE_EGL=1')
        \\if os.environ.get('AZK_WINDOW_BACKEND') or os.environ.get('AZK_TASK_WINDOWS'):
        \\    sys.exit('acceptance-test requires unset AZK_WINDOW_BACKEND/AZK_TASK_WINDOWS')
    );
    acceptance_environment.addPassthruArgs();
    acceptance_environment.has_side_effects = true;
    acceptance.dependOn(&acceptance_environment.step);
    for ([_][]const u8{
        "install",
        "integration-test",
        "jvm-test",
        "native-test",
        "system-test",
        "binder-test",
        "binder-broker-test",
        "binder-cpp-test",
        "ndk-test",
        "gui-test",
        "graphics-buffer-test",
        "graphics-budget-test",
        "renderengine-test",
        "surfaceflinger-service-test",
        "surfaceflinger-output-test",
        "surfaceflinger-composition-test",
        "input-dispatcher-test",
        "input-surfaceflinger-test",
        "surface-wayland-test",
        "surface-fence-test",
        "surface-pacing-test",
        "surface-drag-icon-test",
        "wayland-output-test",
        "wayland-unit-test",
        "stock-window-boot-test",
        "stock-cold-launch-test",
        "stock-task-wayland-test",
        "stock-desktop-launcher-test",
        "stock-task-desktop-test",
        "stock-task-output-test",
        "stock-task-desktop-legacy-test",
        "stock-task-desktop-suspension-test",
        "stock-task-tablet-test",
        "stock-video-test",
        "stock-audio-delivery-test",
        "stock-ime-test",
        "stock-clipboard-test",
        "stock-drag-test",
        "stock-drag-pressure-test",
        "stock-session-loss-test",
        "webview-test",
        "webview-ui-test",
        "webview-overlay-test",
        "bionic-loader-test",
        "build-tools-test",
        "cpp-audit",
        "graph-audit",
        "image-queue-jvm-test",
        "process-app-data-test",
        "data-partition-test",
        "egl-test",
        "framework-module-test",
        "source-identity-test",
        "binder-jni-source-test",
        "native-client-source-test",
        "codec2-client-test",
        "camera-client-test",
        "vintf-test",
        "codec2-transport-test",
        "codec2-allocator-test",
        "codec2-bufferpool-test",
        "codec2-ffmpeg-test",
        "codec2-service-test",
        "codec-video-allocator-test",
        "codec-work-test",
        "media-resource-test",
        "ndk-abi",
    }) |name| {
        const required = b.top_level_steps.get(name) orelse
            std.debug.panic("acceptance target does not exist: {s}", .{name});
        acceptance.dependOn(&required.step);
    }
}
