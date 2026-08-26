//! androlinzberg: run an Android APK with a Zig (Skia + Wayland) platform layer
//! on the embedded artzig ART runtime.
//!
//! Steps:
//!   zig build            — build the launcher and ART runtime (zig-out/bin/)
//!   zig build run -- ... — run an APK or Java graphics probe
const std = @import("std");
const Scanner = @import("wayland").Scanner;

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{ .default_target = .{ .cpu_model = .baseline } });
    const optimize = b.standardOptimizeOption(.{});

    const cpp_audit = b.addSystemCommand(&.{ "bash", "tools/check-native-boundary.sh" });
    cpp_audit.addFileInput(b.path("tools/check-native-boundary.sh"));
    cpp_audit.setName("audit application C++ boundary");
    const cpp_audit_step = b.step("cpp-audit", "Verify that C++ is limited to HWUI/Skia/AOSP ABI bridges");
    cpp_audit_step.dependOn(&cpp_audit.step);
    b.getInstallStep().dependOn(&cpp_audit.step);

    // --- zig-wayland: generate protocol bindings
    const scanner = Scanner.create(b, .{});
    scanner.addSystemProtocol("stable/xdg-shell/xdg-shell.xml");
    scanner.addSystemProtocol("stable/viewporter/viewporter.xml");
    scanner.addSystemProtocol("staging/fractional-scale/fractional-scale-v1.xml");
    scanner.addSystemProtocol("stable/linux-dmabuf/linux-dmabuf-v1.xml");
    scanner.addSystemProtocol("stable/tablet/tablet-v2.xml");
    scanner.addSystemProtocol("staging/cursor-shape/cursor-shape-v1.xml");
    scanner.addCustomProtocol(b.path("src/protocol/wlr-virtual-pointer-unstable-v1.xml"));
    scanner.addCustomProtocol(b.path("src/protocol/virtual-keyboard-unstable-v1.xml"));
    scanner.generate("wl_compositor", 4);
    scanner.generate("wl_seat", 5);
    scanner.generate("wl_output", 2);
    scanner.generate("xdg_wm_base", 2);
    scanner.generate("wl_subcompositor", 1);
    scanner.generate("wp_viewporter", 1);
    scanner.generate("wp_fractional_scale_manager_v1", 1);
    scanner.generate("zwp_linux_dmabuf_v1", 4);
    scanner.generate("wp_cursor_shape_manager_v1", 1);
    scanner.generate("zwlr_virtual_pointer_manager_v1", 2);
    scanner.generate("zwp_virtual_keyboard_manager_v1", 1);
    // --- embedded artzig runtime (ART) module
    const artzig_dep = b.dependency("artzig", .{ .target = target, .optimize = optimize });
    const art_mod = artzig_dep.module("art");
    @import("artzig").installRuntime(b, artzig_dep, b.getInstallStep());
    const libart_overlay = artzig_dep.namedLazyPath("libart-so");
    const libjavacrypto_overlay = artzig_dep.namedLazyPath("libjavacrypto-so");
    const bionic_overlays = .{
        .{ artzig_dep.namedLazyPath("libdl-bio-so"), "libdl_bio.so" },
        .{ artzig_dep.namedLazyPath("libpthread-bio-so"), "libpthread_bio.so" },
        .{ artzig_dep.namedLazyPath("libc-bio-so"), "libc_bio.so" },
        .{ artzig_dep.namedLazyPath("libstdcpp-bio-so"), "libstdc++_bio.so" },
    };

    // --- Skia: static, built with zig c++ from the pinned skia-src (build/skia.zig)
    const skia_lib = @import("build/skia.zig").build(b, .{
        .root = b.path("vendor/skia-src"),
        .target = target,
        .include_dirs = &.{
            "artzig/vendor/system/core/libcutils/include",
            "artzig/vendor/system/logging/liblog/include",
        },
    });
    b.step("skia", "Build static Skia").dependOn(&b.addInstallArtifact(skia_lib, .{}).step);

    const wayland_mod = b.createModule(.{
        .root_source_file = scanner.result,
        .target = target,
        .optimize = optimize,
    });

    // --- native island: hwui + JNI + our shims, linked against static Skia
    // --- C++ headers for the libs/input enum AIDLs (AOSP prebuilt aidl in aosp-env)
    const aidl_cpp = blk: {
        const r = b.addSystemCommand(&.{ "bash", "tools/gen-aidl-cpp.sh" });
        const out = r.addOutputDirectoryArg("aidl-cpp");
        r.addFileInput(b.path("tools/gen-aidl-cpp.sh"));
        inline for (.{ "IInputConstants", "InputConfig", "MotionEventFlag", "PointerIconType", "InputEventInjectionSync", "InputEventInjectionResult" }) |n| {
            r.addFileInput(artzig_dep.path("extra/frameworks-native/libs/input/android/os/" ++ n ++ ".aidl"));
        }
        r.setName("aidl --lang=cpp (libs/input enums)");
        break :blk out;
    };

    const island = @import("build/island.zig").build(b, .{
        .target = target,
        .optimize = optimize,
        .skia = skia_lib,
        .art = art_mod,
        .aidl_cpp = aidl_cpp,
    });
    b.installArtifact(island);
    b.step("island", "Build libandroid_platform.so").dependOn(&b.addInstallArtifact(island, .{}).step);

    // --- framework Java (dex), framework-res.apk, AIDL Java (build/java.zig)
    const java = @import("build/java.zig").build(b, .{ .artzig = artzig_dep });
    b.getInstallStep().dependOn(java.java_step);
    b.getInstallStep().dependOn(&b.addInstallFileWithDir(
        libart_overlay,
        .{ .custom = "lib/art" },
        "libart.so",
    ).step);
    b.getInstallStep().dependOn(&b.addInstallFileWithDir(
        libjavacrypto_overlay,
        .{ .custom = "lib/art" },
        "libjavacrypto.so",
    ).step);
    inline for (bionic_overlays) |overlay| {
        b.getInstallStep().dependOn(&b.addInstallFileWithDir(
            overlay[0],
            .{ .custom = "lib/art" },
            overlay[1],
        ).step);
    }
    b.getInstallStep().dependOn(&b.addInstallFileWithDir(
        b.path("src/bionic_translation.cfg"),
        .{ .custom = "share/bionic_translation/cfg.d" },
        "bionic_translation.cfg",
    ).step);

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
    exe.step.dependOn(&b.addInstallArtifact(island, .{}).step);
    b.installArtifact(exe);

    // --- azk_codec: MediaCodec backend over system FFmpeg. The system build
    // includes VA-API and DRM hwcontexts, which the minimal vendored build does
    // not. Our C-ABI remains compiled into the executable and is resolved by
    // the native island with dlsym(RTLD_DEFAULT).
    const av_tc = b.addTranslateC(.{
        .root_source_file = b.path("src/codec/azk_ffmpeg.h"),
        .target = target,
        .optimize = optimize,
    });
    inline for (.{ "libavcodec", "libavutil", "libva", "libdrm" }) |pc| {
        var code: u8 = 0;
        const out = b.runAllowFail(&.{ "pkg-config", "--cflags-only-I", pc }, &code, .ignore) catch @panic("pkg-config " ++ pc);
        var it = std.mem.tokenizeAny(u8, out, " \n");
        while (it.next()) |flag| if (std.mem.startsWith(u8, flag, "-I")) {
            av_tc.addIncludePath(.{ .cwd_relative = b.dupe(flag[2..]) });
        };
    }
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
    var pipewire_code: u8 = 0;
    const pipewire_cflags = b.runAllowFail(&.{ "pkg-config", "--cflags-only-I", "libpipewire-0.3" }, &pipewire_code, .ignore) catch @panic("pkg-config libpipewire-0.3");
    var pipewire_flags = std.mem.tokenizeAny(u8, pipewire_cflags, " \n");
    while (pipewire_flags.next()) |flag| if (std.mem.startsWith(u8, flag, "-I")) {
        audio_mod.addIncludePath(.{ .cwd_relative = b.dupe(flag[2..]) });
    };
    audio_mod.linkSystemLibrary("pipewire-0.3", .{ .needed = true, .use_pkg_config = .no });
    const host_renderer_mod = b.createModule(.{
        .root_source_file = b.path("src/platform/host_renderer.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{.{ .name = "art", .module = art_mod }},
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
        },
    });
    exe.root_module.addImport("host_natives", host_natives_mod);
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
    const native_test_step = b.step("native-test", "Build Zig JNI adapters and run native unit tests");
    native_test_step.dependOn(&run_audio_tests.step);
    native_test_step.dependOn(&run_wayland_tests.step);
    native_test_step.dependOn(&codec_jni_check.step);
    native_test_step.dependOn(&host_renderer_check.step);
    native_test_step.dependOn(&run_test_control.step);
    native_test_step.dependOn(&platform_abi_check.step);
    native_test_step.dependOn(&launcher_check.step);
    native_test_step.dependOn(&cpp_audit.step);

    // run the *installed* launcher: it resolves the island/dex/res relative to
    // its own install tree (zig-out/), which the cache-dir copy does not have.
    const run = std.Build.Step.Run.create(b, "run androlinzberg");
    run.addFileArg(b.graph.path(.install_prefix, "bin/androlinzberg"));
    run.step.dependOn(b.getInstallStep());
    run.addPassthruArgs();
    b.step("run", "Run the launcher: zig build run -- <app.apk>").dependOn(&run.step);
}
