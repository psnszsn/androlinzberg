//! Signed base + feature APKs; activity code, native library and assets live in the split.
const std = @import("std");
pub const Result = struct {
    xapk: std.Build.LazyPath,
    base: std.Build.LazyPath,
    split: std.Build.LazyPath,
    wrong_signer: std.Build.LazyPath,
};
pub fn build(b: *std.Build, aosp: *std.Build.Dependency, framework: @import("java.zig").Result, dex: std.Build.LazyPath) Result {
    var resources: [2]std.Build.LazyPath = undefined;
    for ([_][]const u8{ "", "/split" }, 0..) |suffix, i| {
        const link = b.addSystemCommand(&.{ "aapt2", "link", "-o" });
        resources[i] = link.addOutputFileArg(if (i == 0) "base-res.apk" else "split-res.apk");
        link.addArg("--manifest");
        link.addFileArg(b.path(b.fmt("testdata/fixture-apks/dev.artzig.fixtures.xapk{s}/AndroidManifest.xml", .{suffix})));
        link.addArg("-I");
        link.addFileArg(framework.resources.apk);
        if (i == 1) {
            link.addArg("-A");
            link.addDirectoryArg(b.path("testdata/fixture-apks/dev.artzig.fixtures.xapk/split/assets"));
        }
    }
    const native = b.addLibrary(.{ .name = "xapk_fixture", .linkage = .dynamic, .root_module = b.createModule(.{
        .root_source_file = b.path("testdata/fixture-apks/dev.artzig.fixtures.xapk/native.zig"),
        .target = b.resolveTargetQuery(.{ .cpu_arch = .x86_64, .os_tag = .linux, .abi = .gnu }),
        .optimize = .ReleaseSmall,
    }) });
    const package = b.addSystemCommand(&.{"python3"});
    package.addFileArg(b.path("tools/window-fixture-apk.py"));
    package.addArg("package");
    package.addFileArg(resources[1]);
    package.addFileArg(dex);
    const unsigned = package.addOutputFileArg("feature-unsigned.apk");
    package.addFileArg(native.getEmittedBin());
    const kotlinc = @import("command.zig").create(b);
    kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    kotlinc.addArgs(&.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-classpath" });
    kotlinc.addJoinedPaths(":", &.{ framework.compile_boot_jar, aosp.path("external/kotlinc/lib/kotlin-stdlib.jar") });
    kotlinc.addArg("-d");
    const base_classes = kotlinc.addOutputFileArg("base-classes.jar");
    kotlinc.addFileArg(b.path("src/java/dev/artzig/fixtures/xapk/XapkBase.kt"));
    const d8 = b.addSystemCommand(&.{ "java", "-Xmx512M", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(framework.compile_boot_jar);
    d8.addArg("--output");
    const base_dex = d8.addOutputFileArg("base-dex.jar");
    d8.addFileArg(base_classes);
    const base_package = b.addSystemCommand(&.{"python3"});
    base_package.addFileArg(b.path("tools/window-fixture-apk.py"));
    base_package.addArg("package");
    base_package.addFileArg(resources[0]);
    base_package.addFileArg(base_dex);
    const base_unsigned = base_package.addOutputFileArg("base-unsigned.apk");
    const sign = @import("generators.zig").signApk;
    const base = sign(b, aosp, base_unsigned, "base.apk", true, "azk-platform");
    const split = sign(b, aosp, unsigned, "feature.apk", true, "azk-platform");
    const wrong_signer = sign(b, aosp, unsigned, "wrong-signer.apk", true, "azk-untrusted");
    const envelope = b.addSystemCommand(&.{"python3"});
    envelope.addFileArg(b.path("tools/xapk-fixture.py"));
    envelope.addFileArg(base);
    envelope.addFileArg(split);
    const xapk = envelope.addOutputFileArg("split-fixture.xapk");
    return .{ .xapk = xapk, .base = base, .split = split, .wrong_signer = wrong_signer };
}
