//! UI tests that run on ART: JUnit and the pinned androidx UiAutomator driving
//! apps through stock UiAutomation. Test code is compiled against the framework
//! and dexed into one jar that dev.artzig.testing.HostTestRunner loads; the
//! host orchestrator (tools/ui-test-runner.zig) runs each test in its own
//! session.
const std = @import("std");
const command = @import("command.zig");

pub const sources = [_][]const u8{
    "tests/ui/kotlin/dev/artzig/uitest/HostInstrumentation.kt",
    "tests/ui/kotlin/dev/artzig/uitest/NewPipeUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/UiTestMain.kt",
    "tests/ui/kotlin/dev/artzig/uitest/WebViewUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/SessionDiagnosticsUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/UiApp.kt",
    "tests/ui/kotlin/dev/artzig/uitest/WindowFixtureUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/WindowInputUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/host/DesktopClipboardUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/host/DesktopImeUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/host/HostSession.kt",
    "tests/ui/kotlin/dev/artzig/uitest/host/WaylandInputUiTest.kt",
    "tests/ui/kotlin/dev/artzig/uitest/host/WaylandSeat.kt",
};

/// `Class#method[@WxH]`: the orchestrator's default selection. A size suffix is
/// the launch size and headless output mode of that test's session.
pub const tests = [_][]const u8{
    "dev.artzig.uitest.SessionDiagnosticsUiTest#unsupportedFrameworkCallIsJournalled",
    "dev.artzig.uitest.WindowFixtureUiTest#launchesAndUpdatesState@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#dialogRemainsAttachedToTheActivity@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#dialogLifecycleRestoresStateAndPreservesCallbackOrder@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#sharedBackCallbackRemainsScopedToEachDialogWindow@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#activityPendingIntentsLaunchAndDeliverResults@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#visibleActivityReceivesAudioFocusCallbacks@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#recreationRestoresSavedCounter@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#permissionRequestReturnsConfiguredGrants@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#textureViewRendersResizesAndReleases@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#frameCallbacksFollowSubmissionAndResume@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#layoutSchedulingAndNonModalPopupInput@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#surfaceViewFollowsAttachmentVisibilityAndDetach@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#virtualAccessibilityNodeIsReachableAndActionable@412x915",
    "dev.artzig.uitest.WindowFixtureUiTest#windowRegistryPreservesLayoutAndDefersRemoval@412x915",
    "dev.artzig.uitest.WindowInputUiTest#clickingButtonUpdatesStatus",
    "dev.artzig.uitest.WindowInputUiTest#twoClicksCountTwice",
    "dev.artzig.uitest.WebViewUiTest#rendersInteractsAndPlaysVideo@1280x720@webview",
    // NewPipe needs network access and the pinned APK (AZK_APK_NEWPIPE_0_29_1).
    "dev.artzig.uitest.NewPipeUiTest#launchesHomeWithoutRuntimeIssues@412x915@newpipe",
    "dev.artzig.uitest.NewPipeUiTest#backHonorsNonCancelableKeepAndroidOpenDialog@412x915@newpipe",
    "dev.artzig.uitest.NewPipeUiTest#opensAndEditsSearch@412x915@newpipe",
    "dev.artzig.uitest.NewPipeUiTest#hamburgerOpensNavigationDrawer@412x915@newpipe",
    "dev.artzig.uitest.NewPipeDesktopUiTest#hamburgerOpensNavigationDrawer@1600x900@newpipe",
    "dev.artzig.uitest.NewPipeDesktopUiTest#revealingVideoControlsKeepsCompositorFullscreen@1600x900@newpipe",
    "dev.artzig.uitest.NewPipeDesktopUiTest#fullscreenButtonExitsAndRestoresVideoDetails@1600x900@newpipe",
    "dev.artzig.uitest.NewPipeDesktopUiTest#videoButtonEntersCompositorFullscreen@1600x900@newpipe",
    // Host path: real Wayland input through the presenter and InputDispatcher.
    "dev.artzig.uitest.host.WaylandInputUiTest#tapReachesTheView",
    "dev.artzig.uitest.host.WaylandInputUiTest#overlappingChildrenInterceptionAndRemoval",
    "dev.artzig.uitest.host.WaylandInputUiTest#seekBarDragPreservesTrackingCallbacks",
    "dev.artzig.uitest.host.WaylandInputUiTest#hostF11EntersAndExitsCompositorFullscreen",
    // Presenter-only checks by the native probe (tests/ui/probe).
    "probe#presentedFramesAdvance",
    "probe#resourcesAreAccounted",
};

/// tests/apks.json: `--pinned-apk` arguments of the orchestrator.
pub const pinned_apks = [_][]const u8{ "--pinned-apk", "newpipe=AZK_APK_NEWPIPE_0_29_1=18447bfb1e06d113edc88df93f471827280de06f6f3d4dc42f56f28b9c1bab79" };

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
