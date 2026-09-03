//! The cacheable application layout consumed by run and test steps.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    launcher: LazyPath,
    broker: LazyPath,
    island: LazyPath,
    stats_jni: LazyPath,
    binder_ndk: LazyPath,
    art_runtime: LazyPath,
    libart: LazyPath,
    libjavacrypto: LazyPath,
    bionic: [4]NamedLibrary,
    framework_jar: LazyPath,
    framework_app_context: LazyPath,
    framework_odex: LazyPath,
    framework_vdex: LazyPath,
    framework_resources: LazyPath,
};

pub const NamedLibrary = struct {
    file: LazyPath,
    name: []const u8,
};

pub fn build(b: *std.Build, options: Options) LazyPath {
    const tree = b.addWriteFiles();
    _ = tree.addCopyFile(options.launcher, "bin/androlinzberg");
    _ = tree.addCopyFile(options.broker, "bin/azk-binder-broker");
    _ = tree.addCopyDirectory(options.art_runtime, "bin/art", .{});
    _ = tree.addCopyFile(options.island, "lib/libandroid_platform.so");
    _ = tree.addCopyFile(options.stats_jni, "lib/art/libstats_jni.so");
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libbinder_ndk.so");
    _ = tree.addCopyFile(options.libart, "lib/art/libart.so");
    _ = tree.addCopyFile(options.libjavacrypto, "lib/art/libjavacrypto.so");
    for (options.bionic) |library| {
        _ = tree.addCopyFile(library.file, b.pathJoin(&.{ "lib/art", library.name }));
    }
    _ = tree.addCopyFile(options.framework_jar, "framework/framework.jar");
    _ = tree.addCopyFile(options.framework_app_context, "framework/framework-app-context.jar");
    _ = tree.addCopyFile(options.framework_odex, "framework/oat/x86_64/framework.odex");
    _ = tree.addCopyFile(options.framework_vdex, "framework/oat/x86_64/framework.vdex");
    _ = tree.addCopyFile(options.framework_resources, "res/framework-res.apk");
    _ = tree.addCopyFile(b.path("tools/dexopt-apk.sh"), "share/androlinzberg/dexopt-apk.sh");
    _ = tree.addCopyFile(b.path("src/bionic_translation.cfg"), "share/bionic_translation/cfg.d/bionic_translation.cfg");
    return tree.getDirectory();
}
