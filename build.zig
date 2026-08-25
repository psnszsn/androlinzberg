//! androlinzberg: run an Android APK with a Zig (Skia + Wayland) platform layer
//! on the embedded artzig ART runtime.
//!
//! Steps:
//!   zig build            — build the launcher and ART runtime (zig-out/bin/)
//!   zig build run -- ... — run it (M0: --smoke draws Skia frames on Wayland)
const std = @import("std");
const Scanner = @import("wayland").Scanner;

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{ .default_target = .{ .cpu_model = .baseline } });
    const optimize = b.standardOptimizeOption(.{});

    // --- zig-wayland: generate protocol bindings
    const scanner = Scanner.create(b, .{});
    scanner.addSystemProtocol("stable/xdg-shell/xdg-shell.xml");
    scanner.addSystemProtocol("stable/viewporter/viewporter.xml");
    scanner.addSystemProtocol("staging/fractional-scale/fractional-scale-v1.xml");
    scanner.addSystemProtocol("stable/linux-dmabuf/linux-dmabuf-v1.xml");
    scanner.addSystemProtocol("stable/tablet/tablet-v2.xml");
    scanner.addSystemProtocol("staging/cursor-shape/cursor-shape-v1.xml");
    scanner.generate("wl_compositor", 4);
    scanner.generate("wl_shm", 1);
    scanner.generate("wl_seat", 5);
    scanner.generate("wl_output", 2);
    scanner.generate("xdg_wm_base", 2);
    scanner.generate("wl_subcompositor", 1);
    scanner.generate("wp_viewporter", 1);
    scanner.generate("wp_fractional_scale_manager_v1", 1);
    scanner.generate("zwp_linux_dmabuf_v1", 4);
    scanner.generate("wp_cursor_shape_manager_v1", 1);
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
    inline for (.{ "libavcodec", "libavutil", "libswscale", "libva", "libdrm" }) |pc| {
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
    inline for (.{ "libavcodec", "libavutil", "libswscale" }) |lib| {
        codec_mod.linkSystemLibrary(lib, .{ .needed = true, .use_pkg_config = .force });
    }
    codec_mod.linkSystemLibrary("libva", .{ .needed = true, .use_pkg_config = .force });
    exe.root_module.addImport("azk_codec", codec_mod);
    exe.rdynamic = true;

    // run the *installed* launcher: it resolves the island/dex/res relative to
    // its own install tree (zig-out/), which the cache-dir copy does not have.
    const run = std.Build.Step.Run.create(b, "run androlinzberg");
    run.addFileArg(b.graph.path(.install_prefix, "bin/androlinzberg"));
    run.step.dependOn(b.getInstallStep());
    run.addPassthruArgs();
    b.step("run", "Run the launcher: zig build run -- <app.apk>").dependOn(&run.step);
}
