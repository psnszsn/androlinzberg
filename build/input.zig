//! Pinned input channels, publisher/consumer transport and Rust verification.
const std = @import("std");
const graphics = @import("graphics.zig");
const declarations = @import("generator_sources.zig");
pub const Generated = struct {
    headers: std.Build.LazyPath,
    sources: std.Build.LazyPath,
    verifier: ?std.Build.LazyPath = null,
    rust: ?*@import("rust.zig").Builder = null,
};

pub const Library = struct {
    native: *std.Build.Step.Compile,
    verifier: std.Build.LazyPath,

    pub fn link(self: Library, mod: *std.Build.Module) void {
        mod.linkLibrary(self.native);
        // Zig doesn't propagate arbitrary archive inputs through a static
        // Compile library. Keep the Rust archive explicit at the final link.
        mod.addObjectFile(self.verifier);
    }
};

pub fn generate(b: *std.Build, aosp: std.Build.LazyPath, runner: *std.Build.Step.Compile, aidl_tool: *std.Build.Step.Compile, gui: graphics.Generated, aidl: []const std.Build.LazyPath) Generated {
    const headers = b.addWriteFiles();
    const sources = b.addWriteFiles();
    _ = headers.addCopyFile(aosp.path(b, "external/rust/cxx/include/cxx.h"), "rust/cxx.h");
    const entry = for (declarations.aconfig) |item| {
        if (std.mem.eql(u8, item.package, "com.android.input.flags")) break item;
    } else unreachable;
    const cache = b.addRunArtifact(runner);
    cache.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    cache.addArgs(&.{ "create-cache", "--package", entry.package, "--container", entry.container, "--default-permission", "READ_ONLY", "--allow-read-write", "false" });
    for (entry.declarations) |file| {
        cache.addArg("--declarations");
        cache.addFileArg(aosp.path(b, file));
    }
    cache.addArg("--cache");
    const flags_cache = cache.addOutputFileArg("input-flags.pb");
    const flags = b.addRunArtifact(runner);
    flags.addFileArg(aosp.path(b, "prebuilts/build-tools/linux-x86/bin/aconfig"));
    flags.addArgs(&.{ "create-cpp-lib", "--cache" });
    flags.addFileArg(flags_cache);
    flags.addArgs(&.{ "--mode", "production", "--out" });
    const directory = flags.addOutputDirectoryArg("flags");
    _ = headers.addCopyDirectory(directory.path(b, "include"), "", .{});
    _ = sources.addCopyFile(directory.path(b, "com_android_input_flags.cc"), "com_android_input_flags.cc");
    const labels = b.addSystemCommand(&.{"python3"});
    labels.addFileArg(aosp.path(b, "system/core/toolbox/generate-input.h-labels.py"));
    labels.addFileArg(aosp.path(b, "bionic/libc/kernel/uapi/linux/input.h"));
    labels.addFileArg(aosp.path(b, "bionic/libc/kernel/uapi/linux/input-event-codes.h"));
    _ = headers.addCopyFile(labels.captureStdOut(.{}), "input.h-labels.h");
    // Keep the base include directory independent of the Rust bridge outputs:
    // bindgen reads Input.h, while the bridge itself depends on bindgen.
    const base: Generated = .{ .headers = headers.getDirectory(), .sources = sources.getDirectory() };
    const rust = b.allocator.create(@import("rust.zig").Builder) catch @panic("oom");
    rust.* = .{ .b = b, .aosp = aosp, .runner = runner };
    const constants = b.addWriteFiles();
    var rust_files: std.ArrayList(std.Build.LazyPath) = .empty;
    for ([_][]const u8{ "IInputConstants", "InputConfig", "InputEventInjectionResult", "InputEventInjectionSync", "MotionEventFlag", "PointerIconType" }) |name| {
        const generate_aidl = b.addRunArtifact(aidl_tool);
        generate_aidl.addArgs(&.{ "--lang=rust", "-I" });
        generate_aidl.addDirectoryArg(aosp.path(b, "frameworks/native/libs/input"));
        generate_aidl.addArg("-o");
        const output = generate_aidl.addOutputDirectoryArg("rust-aidl");
        generate_aidl.addFileArg(aosp.path(b, b.fmt("frameworks/native/libs/input/android/os/{s}.aidl", .{name})));
        const file = b.fmt("android/os/{s}.rs", .{name});
        const copied = constants.addCopyFile(output.path(b, file), file);
        rust_files.append(b.allocator, copied) catch @panic("oom");
    }
    const glue = b.addSystemCommand(&.{"python3"});
    glue.addFileArg(aosp.path(b, "system/tools/aidl/build/aidl_rust_glue.py"));
    const constants_source = glue.addOutputFileArg("lib.rs");
    glue.addDirectoryArg(constants.getDirectory());
    for (rust_files.items) |file| glue.addFileArg(file);
    rust.addGenerated("inputconstants-rust", constants_source);
    rust.addGenerated("binder-bindings", bindings(b, aosp, gui, base, aidl, .binder));
    rust.addGenerated("libinput_bindgen", bindings(b, aosp, gui, base, aidl, .input));
    const cxxbridge = rust.build("cxxbridge").file;
    const output_headers = b.addWriteFiles();
    const output_sources = b.addWriteFiles();
    _ = output_headers.addCopyDirectory(base.headers, "", .{});
    _ = output_sources.addCopyDirectory(base.sources, "", .{});
    for ([_]bool{ true, false }) |header| {
        const bridge = @import("command.zig").create(b);
        bridge.addFileArg(cxxbridge);
        bridge.addFileArg(aosp.path(b, "frameworks/native/libs/input/rust/lib.rs"));
        if (header) bridge.addArg("--header");
        const output = bridge.run.captureStdOut(.{});
        if (header) {
            _ = output_headers.addCopyFile(output, "input_cxx_bridge.rs.h");
        } else {
            _ = output_sources.addCopyFile(output, "input_cxx_bridge_generated.cpp");
        }
    }
    return .{ .headers = output_headers.getDirectory(), .sources = output_sources.getDirectory(), .verifier = rust.build("libinput_rust_ffi").file, .rust = rust };
}

fn bindings(b: *std.Build, aosp: std.Build.LazyPath, gui: graphics.Generated, input: Generated, aidl: []const std.Build.LazyPath, kind: enum { binder, input }) std.Build.LazyPath {
    const run = b.addSystemCommand(&.{"python3"});
    run.addFileArg(b.path("tools/rust-bindgen.py"));
    run.addArgs(&.{ "--zig", b.graph.zig_exe, "--zig-lib" });
    run.addDirectoryArg(.zig_lib);
    run.addArgs(&.{ "--kind", @tagName(kind), "--wrapper" });
    run.addFileArg(aosp.path(b, if (kind == .binder) "frameworks/native/libs/binder/rust/sys/BinderBindings.hpp" else "frameworks/native/libs/input/InputWrapper.hpp"));
    run.addArg("--flags");
    run.addFileArg(aosp.path(b, if (kind == .binder) "frameworks/native/libs/binder/rust/libbinder_ndk_bindgen_flags.txt" else "frameworks/native/libs/input/Android.bp"));
    run.addArg("--output");
    const output = run.addOutputDirectoryArg("bindings");
    run.addArgs(&.{ "--", "-std=c++23", "-fno-exceptions", "-fno-rtti", "-D__ANDROID_HOST__", "-D_GNU_SOURCE", "-DNDEBUG", "-Wno-invalid-specialization", "-include" });
    run.addFileArg(b.path("src/native/graphics/include/azk/graphics_compat.h"));
    if (kind == .input) {
        run.addArg("-include");
        run.addFileArg(b.path("src/native/azk_prelude.h"));
    }
    const includes = b.createModule(.{ .target = b.graph.host });
    configure(b, includes, aosp, gui, input, aidl);
    for (includes.include_dirs.items) |include| switch (include) {
        .path => |path| {
            run.addArg("-I");
            run.addDirectoryArg(path);
        },
        else => unreachable,
    };
    return output.path(b, "bindings.rs");
}

pub fn configure(b: *std.Build, mod: *std.Build.Module, aosp: std.Build.LazyPath, gui: graphics.Generated, input: Generated, aidl: []const std.Build.LazyPath) void {
    graphics.configure(b, mod, aosp, gui);
    mod.addIncludePath(input.headers);
    mod.addIncludePath(aosp.path(b, "frameworks/native/libs/input"));
    for (aidl) |path| mod.addIncludePath(path);
}

pub fn cppFlags(b: *std.Build) []const []const u8 {
    return b.allocator.dupe([]const u8, &.{ "-std=c++23", "-DRUST_CXX_NO_EXCEPTIONS", "-ftrivial-auto-var-init=zero", "-DNDEBUG", "-fno-exceptions", "-fno-rtti", "-ffunction-sections", "-fdata-sections", "-D_GNU_SOURCE", "-D__ANDROID_HOST__", "-DARTZIG_HOST_THREAD_PRIORITY", "-DANDROID_UTILS_CALLSTACK_ENABLED=0", "-Wno-invalid-specialization", "-Wno-deprecated-declarations", "-Wno-deprecated-literal-operator", "-Wno-character-conversion", "-include", b.pathResolve(&.{"src/native/azk_prelude.h"}), "-include", b.pathResolve(&.{"src/native/graphics/include/azk/graphics_compat.h"}) }) catch @panic("oom");
}

pub fn build(b: *std.Build, target: std.Build.ResolvedTarget, aosp: std.Build.LazyPath, gui: graphics.Generated, input: Generated, aidl: []const std.Build.LazyPath, gui_lib: *std.Build.Step.Compile, binder: *std.Build.Step.Compile, ndk: *std.Build.Step.Compile, broker: *std.Build.Step.Compile) Library {
    input.verifier.?.addStepDependencies(b.step("input-verifier", "Build the pinned Rust input verifier and generated Binder dependency"));
    const mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true, .pic = true });
    configure(b, mod, aosp, gui, input, aidl);
    const flags = cppFlags(b);
    mod.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/libs/input"), .files = &.{ "Input.cpp", "InputTransport.cpp", "PrintTools.cpp", "InputConsumer.cpp", "InputVerifier.cpp", "FromRustToCpp.cpp", "InputEventLabels.cpp", "TouchVideoFrame.cpp" }, .flags = flags });
    // C++ streams stay with the executable's C++ runtime, as in RenderEngine.
    mod.addCSourceFiles(.{ .root = aosp.path(b, "system/libbase"), .files = &.{ "logging.cpp", "threads.cpp", "file.cpp" }, .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, "system/core/libcutils/properties.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = input.sources.path(b, "com_android_input_flags.cc"), .flags = flags });
    mod.addCSourceFile(.{ .file = input.sources.path(b, "input_cxx_bridge_generated.cpp"), .flags = flags });
    mod.addCSourceFile(.{ .file = aosp.path(b, "external/rust/cxx/src/cxx.cc"), .flags = flags });
    mod.linkLibrary(gui_lib);
    const library = b.addLibrary(.{ .name = "input_channels", .root_module = mod });
    const transport: Library = .{ .native = library, .verifier = input.verifier.? };
    b.step("input-channels", "Compile pinned input channel transport").dependOn(&library.step);
    b.step("input-transport", "Compile stock input publisher/consumer transport with Rust verification").dependOn(&library.step);
    const linker = b.addExecutable(.{ .name = "rust-linker", .root_module = b.createModule(.{
        .root_source_file = b.path("tools/rust-linker.zig"),
        .target = b.graph.host,
        .optimize = .ReleaseSafe,
    }) });
    const rust_tests = input.rust.?.buildTest("libinput_rust_ffi", .{
        .linker = linker,
        .native = &.{ library.getEmittedBin(), binder.getEmittedBin(), ndk.getEmittedBin() },
    });
    _ = @import("surfaceflinger.zig").runBinaryTest(b, rust_tests.file, binder, ndk, broker, "input-verifier", "Run pinned Rust gesture verification and keyboard classification regressions", .standalone, null, &.{}, &.{});
    const test_mod = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, test_mod, aosp, gui, input, aidl);
    var test_flags: std.ArrayList([]const u8) = .empty;
    test_flags.appendSlice(b.allocator, flags) catch @panic("oom");
    test_flags.append(b.allocator, "-UNDEBUG") catch @panic("oom");
    test_mod.addIncludePath(aosp.path(b, "external/googletest/googletest"));
    test_mod.addIncludePath(aosp.path(b, "external/googletest/googletest/include"));
    test_mod.addCSourceFile(.{ .file = aosp.path(b, "frameworks/native/libs/input/tests/InputChannel_test.cpp"), .flags = test_flags.items });
    test_mod.addCSourceFile(.{ .file = b.path("tests/native/HostLooperTest.cpp"), .flags = test_flags.items });
    test_mod.addCSourceFiles(.{ .root = aosp.path(b, "external/googletest/googletest"), .files = &.{ "src/gtest-all.cc", "src/gtest_main.cc" }, .flags = test_flags.items });
    transport.link(test_mod);
    test_mod.linkLibrary(binder);
    test_mod.linkLibrary(ndk);
    @import("surfaceflinger.zig").runTest(b, test_mod, binder, ndk, broker, "input-channel", "Verify pinned InputChannel identity, transport, duplication and peer close", .standalone, null);
    const publisher = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, publisher, aosp, gui, input, aidl);
    publisher.addIncludePath(aosp.path(b, "external/googletest/googletest"));
    publisher.addIncludePath(aosp.path(b, "external/googletest/googletest/include"));
    publisher.addCSourceFiles(.{ .root = aosp.path(b, "frameworks/native/libs/input/tests"), .files = &.{ "InputPublisherAndConsumer_test.cpp", "InputVerifier_test.cpp" }, .flags = test_flags.items });
    publisher.addCSourceFiles(.{ .root = aosp.path(b, "external/googletest/googletest"), .files = &.{ "src/gtest-all.cc", "src/gtest_main.cc" }, .flags = test_flags.items });
    transport.link(publisher);
    publisher.linkLibrary(binder);
    publisher.linkLibrary(ndk);
    @import("surfaceflinger.zig").runTest(b, publisher, binder, ndk, broker, "input-publisher", "Verify pinned input publisher/consumer event contracts with the Rust verifier", .standalone, null);
    const broker_test = b.createModule(.{ .target = target, .optimize = .ReleaseFast, .link_libc = true, .link_libcpp = true });
    configure(b, broker_test, aosp, gui, input, aidl);
    broker_test.addCSourceFile(.{ .file = b.path("tests/native/InputChannelBrokerTest.cpp"), .flags = test_flags.items });
    transport.link(broker_test);
    broker_test.linkLibrary(binder);
    broker_test.linkLibrary(ndk);
    @import("surfaceflinger.zig").runTest(b, broker_test, binder, ndk, broker, "input-channel-broker", "Verify stock InputChannelCore transfer over AZKB and peer death", .application, null);
    return transport;
}
