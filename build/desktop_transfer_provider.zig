//! Host desktop file staging, with an ordinary package UID and stock URI grants.
const std = @import("std");
const command = @import("command.zig");
pub fn build(b: *std.Build, aosp: *std.Build.Dependency, framework: std.Build.LazyPath, core: std.Build.LazyPath, resources_apk: std.Build.LazyPath) std.Build.LazyPath {
    const kotlinc = command.create(b);
    kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    kotlinc.addArgs(&.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-Xsam-conversions=class", "-Xstring-concat=inline", "-classpath" });
    kotlinc.addJoinedPaths(":", &.{ core, framework, aosp.path("external/kotlinc/lib/kotlin-stdlib.jar") });
    kotlinc.addArg("-d");
    const classes = kotlinc.addOutputFileArg("desktop-transfer-provider-classes.jar");
    kotlinc.addFileArg(b.path("src/java/dev/artzig/desktop/DesktopTransferStore.kt"));
    kotlinc.addFileArg(b.path("src/java/dev/artzig/desktop/DesktopTransferProvider.kt"));
    const d8 = b.addSystemCommand(&.{ "java", "-Xmx512M", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(core);
    d8.addArg("--classpath");
    d8.addFileArg(framework);
    d8.addArg("--output");
    const dex = d8.addOutputFileArg("desktop-transfer-provider-dex.jar");
    d8.addFileArg(classes);
    d8.addFileArg(aosp.path("external/kotlinc/lib/kotlin-stdlib.jar"));
    const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
    const resources = link.addOutputFileArg("desktop-transfer-provider-res.apk");
    link.addArg("--manifest");
    link.addFileArg(b.path("testdata/desktop-transfer-provider/AndroidManifest.xml"));
    link.addArg("-I");
    link.addFileArg(resources_apk);
    link.addArgs(&.{ "--min-sdk-version", "35", "--target-sdk-version", "35" });
    const package = b.addSystemCommand(&.{"python3"});
    package.addFileArg(b.path("tools/window-fixture-apk.py"));
    package.addArg("package");
    package.addFileArg(resources);
    package.addFileArg(dex);
    const unsigned = package.addOutputFileArg("desktop-transfer-provider-unsigned.apk");
    const signed = @import("generators.zig").signApk(b, aosp, unsigned, "DesktopTransferProvider.apk", false, "azk-platform");
    signed.addStepDependencies(b.step("desktop-transfer-provider", "Build the grant-aware desktop URI provider APK"));
    return signed;
}
