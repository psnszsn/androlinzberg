//! The cacheable application layout consumed by run and test steps.
const std = @import("std");
const LazyPath = std.Build.LazyPath;

pub const Options = struct {
    launcher: LazyPath,
    broker: LazyPath,
    surfaceflinger: LazyPath,
    media_service: LazyPath,
    task_presenter: LazyPath,
    island: LazyPath,
    pdfclient: LazyPath,
    idmap2: LazyPath,
    stats_jni: LazyPath,
    webview_support: LazyPath,
    egl_support: LazyPath,
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
    download_provider: LazyPath,
    webview_provider: LazyPath,
    desktop_transfer_provider: LazyPath,
    /// Pinned frameworks/base/packages/Shell manifest: the permissions of uid 2000.
    shell_manifest: LazyPath,
};

pub const NamedLibrary = struct {
    file: LazyPath,
    name: []const u8,
};

pub fn build(b: *std.Build, options: Options) LazyPath {
    const tree = b.addWriteFiles();
    _ = tree.addCopyDirectory(b.path("src/native/codec2/config"), "", .{});
    _ = tree.addCopyFile(options.launcher, "bin/androlinzberg");
    _ = tree.addCopyFile(options.shell_manifest, "etc/shell/AndroidManifest.xml");
    _ = tree.addCopyFile(options.broker, "bin/azk-binder-broker");
    _ = tree.addCopyFile(options.surfaceflinger, "bin/azk-surfaceflinger");
    _ = tree.addCopyFile(options.media_service, "bin/azk-media-service");
    _ = tree.addCopyFile(options.task_presenter, "bin/azk-task-presenter");
    _ = tree.addCopyDirectory(options.art_runtime, "bin/art", .{});
    _ = tree.addCopyFile(options.island, "lib/libandroid_platform.so");
    _ = tree.addCopyFile(options.pdfclient, "lib/art/libpdfclient.so");
    _ = tree.addCopyFile(options.idmap2, "lib/art/libidmap2.so");
    _ = tree.addCopyFile(options.stats_jni, "lib/art/libstats_jni.so");
    _ = tree.addCopyFile(options.webview_support, "lib/art/libwebviewchromium_plat_support.so");
    _ = tree.addCopyFile(options.egl_support, "lib/art/libazk_egl.so");
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libbinder_ndk.so");
    _ = tree.addCopyFile(options.binder_cpp, "lib/art/libbinder.so");
    // Stock NetworkUtils loads this module name. Its hosted native is registered by the
    // framework registrar already carried in libbinder_ndk, so expose that same Zig library.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libframework-connectivity-jni.so");
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libframework-connectivity-tiramisu-jni.so");
    // Original media JNI registrars run from the native island during framework startup.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libmedia_jni.so");
    // Legacy DRM JNI is registered from the native island during framework startup.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libdrmframework_jni.so");
    // Original AudioEffect/Visualizer JNI is registered from that same island.
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libaudioeffect_jni.so");
    _ = tree.addCopyFile(options.binder_ndk, "lib/art/libcom_android_net_module_util_jni.so");
    // The overlays are the runtime's own artifacts, whose $ORIGIN runpaths
    // describe bin/art/lib64. Installed under lib/art they must still find
    // libartbase, liblog and the other runtime libraries there; the build
    // cache runpaths that hid this in-tree are not part of an installation.
    _ = tree.addCopyFile(relocateOverlay(b, options.libart, "libart.so"), "lib/art/libart.so");
    _ = tree.addCopyFile(relocateOverlay(b, options.libjavacrypto, "libjavacrypto.so"), "lib/art/libjavacrypto.so");
    for (options.bionic) |library| {
        _ = tree.addCopyFile(library.file, b.pathJoin(&.{ "lib/art", library.name }));
    }
    _ = tree.addCopyFile(options.framework_jar, "framework/framework.jar");
    _ = tree.addCopyFile(options.framework_compat_config, "bin/art/etc/compatconfig/framework.xml");
    _ = tree.addCopyFile(options.framework_app_context, "framework/framework-app-context.jar");
    _ = tree.addCopyFile(options.framework_odex, "framework/oat/x86_64/framework.odex");
    _ = tree.addCopyFile(options.framework_vdex, "framework/oat/x86_64/framework.vdex");
    _ = tree.addCopyFile(options.framework_resources, "res/framework-res.apk");
    _ = tree.addCopyFile(options.download_provider, "apps/DownloadProvider.apk");
    _ = tree.addCopyFile(options.webview_provider, "apps/WebView.apk");
    // The bionic bridge needs a filesystem ELF instead of an apk!/lib path.
    const webview_library = b.addSystemCommand(&.{ "unzip", "-p" });
    webview_library.addFileArg(options.webview_provider);
    webview_library.addArg("lib/x86_64/libwebviewchromium.so");
    _ = tree.addCopyFile(webview_library.captureStdOut(.{}), "apps/lib/com.android.webview/libwebviewchromium.so");
    _ = tree.addCopyFile(options.desktop_transfer_provider, "apps/DesktopTransferProvider.apk");
    _ = tree.addCopyFile(b.path("tools/dexopt-apk.sh"), "share/androlinzberg/dexopt-apk.sh");
    _ = tree.addCopyFile(b.path("src/bionic_translation.cfg"), "share/bionic_translation/cfg.d/bionic_translation.cfg");
    return tree.getDirectory();
}

fn relocateOverlay(b: *std.Build, library: LazyPath, name: []const u8) LazyPath {
    const patch = b.addSystemCommand(&.{ "patchelf", "--add-rpath", "$ORIGIN/../../bin/art/lib64", "--output" });
    const relocated = patch.addOutputFileArg(name);
    patch.addFileArg(library);
    return relocated;
}
