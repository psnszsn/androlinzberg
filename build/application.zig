//! The cacheable application layout consumed by run and test steps.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    launcher: LazyPath,
    broker: LazyPath,
    island: LazyPath,
    stats_jni: LazyPath,
    binder_ndk: LazyPath,
    binder_cpp: LazyPath,
    art_runtime: LazyPath,
    libart: LazyPath,
    libjavacrypto: LazyPath,
    bionic: [4]NamedLibrary,
    framework_jar: LazyPath,
    framework_compat_config: LazyPath,
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
    _ = tree.addCopyFile(options.binder_cpp, "lib/art/libbinder.so");
    // Stock NetworkUtils loads this module name. Its hosted native is registered by the
    // framework registrar already carried in libbinder_ndk, so expose that same Zig library.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libframework-connectivity-jni.so");
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libframework-connectivity-tiramisu-jni.so");
    // VintfRuntimeInfo uses the same registered framework JNI library.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libvintf_jni.so");
    // Stock MediaCrypto loads this name; its no-plugin JNI is registered in the same library.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libmedia_jni.so");
    // Legacy DRM JNI is registered from the native island during framework startup.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libdrmframework_jni.so");
    // Stock AudioEffect uses the same hosted JNI registrar.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libaudioeffect_jni.so");
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libcom_android_net_module_util_jni.so");
    _ = tree.addCopyFile(options.libart, "lib/art/libart.so");
    _ = tree.addCopyFile(options.libjavacrypto, "lib/art/libjavacrypto.so");
    for (options.bionic) |library| {
        _ = tree.addCopyFile(library.file, b.pathJoin(&.{ "lib/art", library.name }));
    }
    _ = tree.addCopyFile(options.framework_jar, "framework/framework.jar");
    _ = tree.addCopyFile(options.framework_compat_config, "bin/art/etc/compatconfig/framework.xml");
    _ = tree.addCopyFile(options.framework_app_context, "framework/framework-app-context.jar");
    _ = tree.addCopyFile(options.framework_odex, "framework/oat/x86_64/framework.odex");
    _ = tree.addCopyFile(options.framework_vdex, "framework/oat/x86_64/framework.vdex");
    _ = tree.addCopyFile(options.framework_resources, "res/framework-res.apk");
    _ = tree.addCopyFile(b.path("tools/dexopt-apk.sh"), "share/androlinzberg/dexopt-apk.sh");
    _ = tree.addCopyFile(b.path("src/bionic_translation.cfg"), "share/bionic_translation/cfg.d/bionic_translation.cfg");
    return tree.getDirectory();
}
