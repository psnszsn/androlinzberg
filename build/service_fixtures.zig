//! Independently signed service APK, with no service implementation in the framework.
const std = @import("std");
const command = @import("command.zig");

pub fn build(b: *std.Build, aosp: *std.Build.Dependency, framework: @import("java.zig").Result) std.Build.LazyPath {
    const kotlinc = command.create(b);
    kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    kotlinc.addArgs(&.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-Xsam-conversions=class", "-Xstring-concat=inline", "-classpath" });
    kotlinc.addJoinedPaths(":", &.{ framework.compile_boot_jar, framework.classes_jar, aosp.path("external/kotlinc/lib/kotlin-stdlib.jar") });
    kotlinc.addArg("-d");
    const classes = kotlinc.addOutputFileArg("service-fixture-classes.jar");
    kotlinc.addFileArg(b.path("src/java/dev/artzig/fixtures/services/InstanceService.kt"));
    const d8 = b.addSystemCommand(&.{ "java", "-Xmx512M", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(framework.compile_boot_jar);
    d8.addArg("--classpath");
    d8.addFileArg(framework.classes_jar);
    d8.addArg("--output");
    const dex = d8.addOutputFileArg("service-fixture-dex.jar");
    d8.addFileArg(classes);
    d8.addFileArg(aosp.path("external/kotlinc/lib/kotlin-stdlib.jar"));
    const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
    const resources = link.addOutputFileArg("service-fixture-res.apk");
    link.addArg("--manifest");
    link.addFileArg(b.path("testdata/fixture-apks/dev.artzig.fixtures.services/AndroidManifest.xml"));
    link.addArg("-I");
    link.addFileArg(framework.resources.apk);
    link.addArgs(&.{ "--min-sdk-version", "35", "--target-sdk-version", "35" });
    const package = b.addSystemCommand(&.{"python3"});
    package.addFileArg(b.path("tools/window-fixture-apk.py"));
    package.addArg("package");
    package.addFileArg(resources);
    package.addFileArg(dex);
    const unsigned = package.addOutputFileArg("service-fixture-unsigned.apk");
    const signed = @import("generators.zig").signApk(b, aosp, unsigned, "service-fixture.apk", true, "azk-untrusted");
    return signed;
}
