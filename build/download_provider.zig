//! The pinned downloads provider as a separately signed system APK.
const std = @import("std");
const LazyPath = std.Build.LazyPath;
const SourceFiles = @import("artzig").SourceSet;
const command = @import("command.zig");

pub fn build(b: *std.Build, aosp: *std.Build.Dependency, framework: LazyPath, system_modules: LazyPath, core: LazyPath, resources: LazyPath) LazyPath {
    const root = "packages/providers/DownloadProvider";
    const compile_res = b.addSystemCommand(&.{ "aapt2", "compile", "--dir" });
    compile_res.addDirectoryArg(aosp.path(root ++ "/res"));
    compile_res.addArg("-o");
    const compiled_res = compile_res.addOutputFileArg("download-resources.zip");

    const link = b.addSystemCommand(&.{ "aapt2", "link", "--manifest" });
    link.addFileArg(aosp.path(root ++ "/AndroidManifest.xml"));
    link.addArg("-I");
    link.addFileArg(resources);
    link.addArgs(&.{ "--min-sdk-version", "35", "--target-sdk-version", "35", "--java" });
    const generated = link.addOutputDirectoryArg("download-res-java");
    link.addArg("-o");
    const resource_apk = link.addOutputFileArg("download-resources.apk");
    link.addFileArg(compiled_res);

    var collector = SourceFiles.Collector.init(b);
    const source_root = SourceFiles.Root.dependency(b, aosp, root ++ "/src/com/android/providers/downloads");
    inline for (.{
        "Constants",                 "DownloadDrmHelper", "DownloadIdleService", "DownloadInfo",
        "DownloadJobService",        "DownloadNotifier",  "DownloadProvider",    "DownloadReceiver",
        "DownloadStorageProvider",   "DownloadThread",    "Helpers",             "MediaScanTriggerJob",
        "MediaStoreDownloadsHelper", "OpenHelper",        "RawDocumentsHelper",  "RealSystemFacade",
        "StopRequestException",      "StorageUtils",      "SystemFacade",
    }) |name| collector.addFile(source_root, name ++ ".java");
    const sources = collector.select(".java");
    const javac = command.create(b);
    javac.addArgs(&.{ "javac", "--system" });
    javac.addDirectoryArg(system_modules);
    javac.addArgs(&.{ "-source", "21", "-target", "21", "-encoding", "UTF-8", "-XDstringConcat=inline", "-proc:none", "-classpath" });
    javac.addJoinedPathInputs(":", &.{
        .{ .file = framework },
        .{ .file = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar") },
    });
    javac.addArg("-d");
    const classes = javac.addOutputDirectoryArg("download-classes");
    javac.addPrefixedFileArg("@", sources.responseFile(b, "download-sources.rsp"));
    javac.addFileArg(generated.path(b, "com/android/providers/downloads/R.java"));
    sources.addInputs(javac.run);
    javac.run.setName("compile stock DownloadProvider Java");

    const jar = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    const class_jar = jar.addOutputFileArg("download-classes.jar");
    jar.addArg("-C");
    jar.addDirectoryArg(classes);
    jar.addArg(".");
    const dex = b.addSystemCommand(&.{ "java", "-Xmx512M", "-cp" });
    dex.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    dex.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    dex.addFileArg(core);
    dex.addArg("--lib");
    dex.addFileArg(framework);
    dex.addArg("--output");
    const dex_files = dex.addOutputDirectoryArg("download-dex");
    dex.addFileArg(class_jar);

    const merge = b.addSystemCommand(&.{"bash"});
    merge.addFileArg(b.path("tools/merge-system-apk.sh"));
    merge.addFileArg(resource_apk);
    merge.addDirectoryArg(dex_files);
    const unsigned = merge.addOutputFileArg("DownloadProvider-unsigned.apk");
    const apk = @import("generators.zig").signApk(b, aosp, unsigned, "DownloadProvider.apk", false, "azk-platform");
    apk.addStepDependencies(b.step("download-provider", "Build the original downloads provider system APK"));
    return apk;
}
