//! UI tests that run on ART: JUnit and the pinned androidx UiAutomator driving
//! apps through stock UiAutomation. Test code is compiled against the framework
//! and dexed into one jar that dev.artzig.testing.HostTestRunner loads; the
//! host orchestrator (tools/ui-test-runner.zig) runs each test in its own
//! session.
const std = @import("std");
const command = @import("command.zig");

pub const sources = [_][]const u8{
    "tests/ui/kotlin/dev/artzig/uitest/HostInstrumentation.kt",
    "tests/ui/kotlin/dev/artzig/uitest/UiTestMain.kt",
    "tests/ui/kotlin/dev/artzig/uitest/SessionDiagnosticsUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/WindowInputUiTest.kt",
};

/// `Class#method` per line; the orchestrator's default selection.
pub const tests = [_][]const u8{
    "dev.artzig.uitest.SessionDiagnosticsUiTest#unsupportedFrameworkCallIsJournalled",
    "dev.artzig.uitest.WindowInputUiTest#clickingButtonUpdatesStatus",
    "dev.artzig.uitest.WindowInputUiTest#twoClicksCountTwice",
};

const androidx = "prebuilts/sdk/current/androidx/m2repository/androidx/";

/// The `classes.jar` of an AAR from the pinned AOSP prebuilts.
fn aarClasses(b: *std.Build, aosp: *std.Build.Dependency, aar: []const u8, name: []const u8) std.Build.LazyPath {
    const unzip = b.addSystemCommand(&.{ "unzip", "-p" });
    unzip.addFileArg(aosp.path(aar));
    unzip.addArg("classes.jar");
    return unzip.captureStdOut(.{ .basename = name });
}

pub fn build(
    b: *std.Build,
    aosp: *std.Build.Dependency,
    framework: @import("java.zig").Result,
    junit_hamcrest: std.Build.LazyPath,
) std.Build.LazyPath {
    const uiautomator = aarClasses(b, aosp, androidx ++ "test/uiautomator/uiautomator/2.4.0-alpha01/uiautomator-2.4.0-alpha01.aar", "uiautomator-classes.jar");
    const tracing = aarClasses(b, aosp, androidx ++ "tracing/tracing-android/1.3.0-beta01/tracing-android-1.3.0-beta01.aar", "tracing-classes.jar");
    const kotlin_stdlib = aosp.path("external/kotlinc/lib/kotlin-stdlib.jar");

    const kotlinc = command.create(b);
    kotlinc.addFileArg(aosp.path("external/kotlinc/bin/kotlinc"));
    kotlinc.addArgs(&.{ "-no-jdk", "-no-stdlib", "-no-reflect", "-nowarn", "-language-version", "2.0", "-api-version", "2.0", "-jvm-target", "21", "-Xsam-conversions=class", "-Xstring-concat=inline", "-classpath" });
    kotlinc.addJoinedPaths(":", &.{ framework.compile_boot_jar, framework.classes_jar, kotlin_stdlib, uiautomator, junit_hamcrest });
    kotlinc.addArg("-d");
    const classes = kotlinc.addOutputFileArg("ui-test-classes.jar");
    for (sources) |source| kotlinc.addFileArg(b.path(source));
    kotlinc.run.setName("compile ART UI tests");

    const d8 = b.addSystemCommand(&.{ "java", "-Xmx1024M", "-cp" });
    d8.addFileArg(aosp.path("prebuilts/r8/r8.jar"));
    d8.addArgs(&.{ "com.android.tools.r8.D8", "--min-api", "35", "--lib" });
    d8.addFileArg(framework.compile_boot_jar);
    d8.addArg("--classpath");
    d8.addFileArg(framework.classes_jar);
    d8.addArg("--output");
    const dex = d8.addOutputFileArg("ui-tests.jar");
    for ([_]std.Build.LazyPath{ classes, uiautomator, tracing, junit_hamcrest }) |jar| d8.addFileArg(jar);
    d8.setName("dex ART UI tests");
    return dex;
}
