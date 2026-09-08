package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import dev.artzig.testkit.AppSession
import dev.artzig.testkit.LaunchSize
import dev.artzig.testkit.WaitKind
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

class NewPipeTest {
    @get:Rule
    val app = ApkRule("newpipe-0.29.1", LaunchSize(412, 915))

    @Test
    fun launchesHomeWithoutRuntimeIssues() = app.run {
        dismissKeepAndroidOpenDialog()
        expectVisible(text = "Live")
        expectVisible(description = "Search")
        expectVisible(description = "Open Drawer")
        expectVisible(resourceId = "org.schabi.newpipe:id/main_tab_layout")
    }

    @Test
    fun backHonorsNonCancelableKeepAndroidOpenDialog() = app.run {
        expectVisible(text = "Keep Android Open")
        key(value = "Back", text = "Keep Android Open")
        expectVisible(text = "Keep Android Open")
        key(value = "Back", text = "Keep Android Open")
        expectVisible(text = "Keep Android Open")
        click(text = "OK")
        waitFor(text = "Keep Android Open", absent = true)
        expectVisible(text = "Live")
    }

    @Test
    fun hostF11EntersAndExitsFullscreen() = app.run {
        dismissKeepAndroidOpenDialog()
        waitForFullscreen(false)
        key(value = "F11", description = "Search")
        waitForFullscreen(true)
        key(value = "F11", description = "Search")
        waitForFullscreen(false)
    }

    @Test
    fun opensAndEditsSearch() = app.run {
        dismissKeepAndroidOpenDialog()
        click(description = "Search")
        waitFor(className = "android.widget.EditText")
        type(value = "kotlin integration", className = "android.widget.EditText")
        expectVisible(text = "kotlin integration")
        key(value = "Escape", className = "android.widget.EditText")
        click(description = "Navigate up")
        expectVisible(text = "Live")
    }

    @Test
    fun hamburgerOpensNavigationDrawer() = app.run {
        dismissKeepAndroidOpenDialog()
        expectVisible(description = "Open Drawer")
        val closed = screenshot("drawer-closed.png")
        assertScreenshotSize(closed, 412, 915)
        click(description = "Open Drawer")
        waitFor(text = "Downloads")
        waitForPresentedFrames(30)
        val panel = drawerPanelBounds()
        val open = screenshot("drawer-open.png")
        waitForPresentedFrames(3)
        assertEquals(panel, drawerPanelBounds(), "drawer bounds were still moving")
        assertDrawerOpened(closed, open, screenshot("drawer-settled.png"), panel)
    }


}

/** Exercises the desktop-sized configuration used by a normal compositor. */
class NewPipeDesktopTest {
    @get:Rule
    val app = ApkRule("newpipe-0.29.1", LaunchSize(1600, 900))

    @Test
    fun hamburgerOpensNavigationDrawer() = app.run {
        dismissKeepAndroidOpenDialog()
        expectVisible(description = "Open Drawer")
        val closed = screenshot("drawer-closed.png")
        assertScreenshotSize(closed, 1600, 900)
        click(description = "Open Drawer")
        waitFor(text = "Downloads")
        waitForPresentedFrames(30)
        val panel = drawerPanelBounds()
        val open = screenshot("drawer-open.png")
        waitForPresentedFrames(3)
        assertEquals(panel, drawerPanelBounds(), "drawer bounds were still moving")
        assertDrawerOpened(closed, open, screenshot("drawer-settled.png"), panel)
    }

    // In its narrow phone UI, NewPipe requests screen rotation for this button.
    // Stock Android ignores orientation requests from resizable freeform tasks
    // (DesktopActivityOrientationChangeHandler handles only non-resizable ones).
    // Desktop UI requests immersive presentation directly; exercise its complete
    // enter/reveal/exit contract without synthesizing an Android rotation.
    @Test
    fun revealingVideoControlsKeepsCompositorFullscreen() = app.run {
        dismissKeepAndroidOpenDialog()
        waitForLiveFeed()
        // The YouTube live feed is dynamic. Exercise the first live card
        // instead of pinning the test to a channel that may leave the feed.
        click(text = "LIVE", index = 0)
        waitFor(description = "Comments")
        // A playing live stream keeps drawing; wait for presentation instead of global idleness.
        click(description = "Play video, duration:", settle = WaitKind.FRAME_PRESENTED)
        revealPlayerControls()
        enterPlayerFullscreen()
        assertFullscreenVideoVisible(screenshot("fullscreen-video.png"))
        tapPlayerSurface()
        assertFullscreenRemains(true, durationMillis = 2_000)
        waitFor(description = "Toggle screen orientation")
    }

    @Test
    fun fullscreenButtonExitsAndRestoresVideoDetails() = app.run {
        dismissKeepAndroidOpenDialog()
        waitForLiveFeed()
        click(text = "LIVE", index = 0)
        waitFor(description = "Comments")
        waitFor(text = "Background")
        waitForFullscreen(false)
        // The first text in the active detail window is its video title. The
        // live feed changes; preserve that identity across the fullscreen cycle.
        val title = waitFor(role = "text", index = 0)
            .getAsJsonObject("observation").getAsJsonArray("matches")[0]
            .asJsonObject.get("text").asString
        assertTrue(title.isNotBlank())

        // A playing live stream keeps drawing; wait for presentation instead of global idleness.
        click(description = "Play video, duration:", settle = WaitKind.FRAME_PRESENTED)
        revealPlayerControls()
        enterPlayerFullscreen()

        tapPlayerSurface()
        waitFor(description = "Toggle screen orientation")
        waitForFullscreen(true)
        click(description = "Toggle screen orientation", settle = WaitKind.NOW)

        waitForFullscreen(false)
        waitFor(description = "Play video, duration:")
        // Live streams may now provide real comments instead of a disabled message.
        waitFor(text = title)
        waitFor(description = "Comments")
        waitFor(text = "Background")
    }

    @Test
    fun videoButtonEntersCompositorFullscreen() = app.run {
        dismissKeepAndroidOpenDialog()
        waitForLiveFeed()
        click(text = "LIVE", index = 0)
        waitFor(description = "Comments")
        waitForFullscreen(false)

        // A playing live stream keeps drawing; wait for presentation instead of global idleness.
        click(description = "Play video, duration:", settle = WaitKind.FRAME_PRESENTED)
        revealPlayerControls()
        // Exercise presentation changes without continuous video frames to poll them.
        pausePlayer()
        enterPlayerFullscreen()
    }
}

private fun AppSession.dismissKeepAndroidOpenDialog() {
    expectVisible(text = "Keep Android Open")
    expectVisible(text = "SOLUTION")
    expectVisible(text = "DETAILS")
    click(text = "OK")
    waitFor(text = "Keep Android Open", absent = true)
}

private fun assertScreenshotSize(screenshot: Path, width: Int, height: Int) {
    val image = ImageIO.read(screenshot.toFile())
        ?: throw AssertionError("could not decode compositor screenshot: $screenshot")
    assertEquals(width, image.width, "unexpected compositor screenshot width: $screenshot")
    assertEquals(height, image.height, "unexpected compositor screenshot height: $screenshot")
}

private fun assertFullscreenVideoVisible(screenshot: Path) {
    val image = ImageIO.read(screenshot.toFile())
        ?: throw AssertionError("could not decode compositor screenshot: $screenshot")
    var visiblePixels = 0
    var sampledPixels = 0
    for (y in image.height / 4 until image.height * 3 / 4 step 3) {
        for (x in image.width / 8 until image.width * 7 / 8 step 3) {
            val pixel = image.getRGB(x, y)
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            if (red + green + blue > 48) visiblePixels++
            sampledPixels++
        }
    }
    assertTrue(
        visiblePixels.toDouble() / sampledPixels > 0.05,
        "fullscreen video remained black: $screenshot",
    )
}

/** DrawerLayout hides its content child from accessibility while its drawer is
 * open. Observe that visible panel; desktop thumbnails behind it load/fade
 * independently and are not evidence that the drawer is still moving.
 */
private fun AppSession.drawerPanelBounds(): java.awt.Rectangle {
    val drawer = expectVisible(className = "androidx.drawerlayout.widget.DrawerLayout")
        .getAsJsonObject("observation").getAsJsonArray("matches").single().asJsonObject
    val children = drawer.getAsJsonArray("children").map { it.asString }.toSet()
    val panel = expectVisible(className = "android.widget.FrameLayout")
        .getAsJsonObject("observation").getAsJsonArray("matches")
        .map { it.asJsonObject }.single { it["id"].asString in children }
        .getAsJsonObject("bounds")
    val left = panel["left"].asInt
    val top = panel["top"].asInt
    return java.awt.Rectangle(left, top, panel["right"].asInt - left, panel["bottom"].asInt - top)
}

private fun assertDrawerOpened(closedScreenshot: Path, openScreenshot: Path, settledScreenshot: Path,
    panel: java.awt.Rectangle) {
    val closed = ImageIO.read(closedScreenshot.toFile())
        ?: throw AssertionError("could not decode compositor screenshot: $closedScreenshot")
    val open = ImageIO.read(openScreenshot.toFile())
        ?: throw AssertionError("could not decode compositor screenshot: $openScreenshot")
    val settled = ImageIO.read(settledScreenshot.toFile())
        ?: throw AssertionError("could not decode compositor screenshot: $settledScreenshot")
    check(
        closed.width == open.width && closed.height == open.height &&
            open.width == settled.width && open.height == settled.height,
    ) {
        "compositor screenshot size changed while opening the drawer"
    }
    var changedPixels = 0
    var unsettledPixels = 0
    var sampledPixels = 0
    check(panel.width > 0 && panel.height > 0 && panel.x >= 0 && panel.y >= 0 &&
        panel.x + panel.width <= open.width && panel.y + panel.height <= open.height)
    for (y in panel.y until panel.y + panel.height step 4) {
        for (x in panel.x until panel.x + panel.width step 4) {
            val before = closed.getRGB(x, y)
            val after = open.getRGB(x, y)
            if (colorDelta(before, after) > 30) changedPixels++
            if (colorDelta(after, settled.getRGB(x, y)) > 30) unsettledPixels++
            sampledPixels++
        }
    }
    assertTrue(
        changedPixels.toDouble() / sampledPixels > 0.05,
        "NewPipe navigation drawer did not change the compositor image: $openScreenshot",
    )
    assertTrue(
        unsettledPixels.toDouble() / sampledPixels < 0.01,
        "NewPipe navigation drawer was still moving after its presentation wait: $settledScreenshot",
    )

    val phoneLayout = open.width < 600
    val boundarySearchEnd = if (phoneLayout) open.width * 9 / 10 else open.width / 2
    val boundary = (1 until boundarySearchEnd).maxBy { x ->
        var score = 0L
        for (y in open.height / 5 until open.height step 4) {
            score += colorDelta(open.getRGB(x - 1, y), open.getRGB(x, y))
        }
        score
    }
    val expectedBoundary = if (phoneLayout) {
        (open.width * 3 / 5)..(open.width * 4 / 5)
    } else {
        (open.width / 6)..(open.width / 4)
    }
    assertTrue(
        boundary in expectedBoundary,
        "NewPipe navigation drawer is only partly visible (edge at x=$boundary): $openScreenshot",
    )
}

private fun colorDelta(first: Int, second: Int): Int =
    kotlin.math.abs((first ushr 16 and 0xff) - (second ushr 16 and 0xff)) +
        kotlin.math.abs((first ushr 8 and 0xff) - (second ushr 8 and 0xff)) +
        kotlin.math.abs((first and 0xff) - (second and 0xff))

/**
 * Brings up the main player's control overlay after "Play video". NewPipe shows the
 * controls when playback starts and auto-hides them about two seconds later, and a
 * tap on the surface toggles them, so poll: tap only while they are hidden, until the
 * orientation toggle (part of the overlay) is present.
 */
/**
 * Taps the player surface to toggle its controls. The tap lands in the upper
 * quarter of the surface: its centre is the play/pause button whenever the
 * controls are showing, and pausing a stream keeps the controls on screen.
 */
private fun AppSession.tapPlayerSurface() {
    click(description = "Play video, duration:", settle = WaitKind.NOW, xFraction = 0.5, yFraction = 0.25)
}

private fun AppSession.revealPlayerControls() {
    repeat(10) {
        if (isVisible(description = "Toggle screen orientation")) return
        if (isVisible(description = "Play video, duration:")) {
            tapPlayerSurface()
        }
        Thread.sleep(700)
    }
    waitFor(description = "Toggle screen orientation")
}

/** NewPipe's visible controls can retain only LAYOUT_* flags when the player
 * expands. Finish that layout transition before tapping its new bounds, then
 * hide controls so the app actually requests hidden bars. Layout flags alone
 * are not an Android fullscreen request. This also works while playback is paused. */
private fun AppSession.enterPlayerFullscreen() {
    click(description = "Toggle screen orientation", settle = WaitKind.NOW)
    waitFor(description = "Comments", absent = true)
    if (isVisible(description = "Toggle screen orientation")) tapPlayerSurface()
    waitFor(description = "Toggle screen orientation", absent = true)
    waitForFullscreen(true)
}

private fun AppSession.pausePlayer() {
    repeat(30) {
        if (isVisible(description = "Pause")) {
            click(description = "Pause", settle = WaitKind.FRAME_PRESENTED)
            waitFor(description = "Play")
            return
        }
        if (isVisible(description = "Play")) return
        Thread.sleep(100)
    }
    throw AssertionError("player did not expose its play/pause control")
}

/** The live YouTube feed can return a transient extraction error. Retry that
 * explicit application error once during setup; retain its screenshot and the
 * ordinary issue-checked UI step before requiring the same live-card state. */
private fun AppSession.waitForLiveFeed() {
    try { waitFor(text = "LIVE") }
    catch (failure: dev.artzig.testkit.VarlinkException) {
        if (failure.response["error"]?.asString != "dev.artzig.androlinzberg.test1.Timeout" ||
            !isVisible(text = "Could not parse website") || !isVisible(text = "RETRY")) throw failure
        screenshot("live-feed-load-error.png")
        click(text = "RETRY", settle = WaitKind.NOW)
        waitFor(text = "LIVE")
    }
}
