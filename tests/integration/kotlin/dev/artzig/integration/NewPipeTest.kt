package dev.artzig.integration

import dev.artzig.testkit.ApkRule
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
        expectVisible(text = "Live")
        expectVisible(description = "Search")
        expectVisible(description = "Open Drawer")
        assertLog("ApkHost", "starting org.schabi.newpipe.MainActivity")
    }

    @Test
    fun opensAndEditsSearch() = app.run {
        click(description = "Search")
        waitFor(className = "android.widget.EditText")
        setText(value = "kotlin integration", className = "android.widget.EditText")
        expectVisible(text = "kotlin integration")
        key(value = "Escape", className = "android.widget.EditText")
        click(description = "Navigate up")
        expectVisible(text = "Live")
    }

    @Test
    fun hamburgerOpensNavigationDrawer() = app.run {
        expectVisible(description = "Open Drawer")
        val closed = screenshot("drawer-closed.png")
        assertScreenshotSize(closed, 412, 915)
        click(description = "Open Drawer")
        waitFor(text = "Downloads")
        waitForPresentedFrames(30)
        val open = screenshot("drawer-open.png")
        waitForPresentedFrames(3)
        assertDrawerOpened(closed, open, screenshot("drawer-settled.png"))
    }

    @Test
    fun revealingVideoControlsKeepsCompositorFullscreen() = app.run {
        waitFor(text = "LIVE")
        waitFor(text = "Associated Press")
        click(text = "Associated Press", index = 0)
        waitFor(description = "Comments")
        click(description = "Play video, duration:")
        if (!isVisible(description = "Toggle screen orientation")) {
            waitFor(className = "android.widget.ProgressBar", stateDescription = "0%")
            if (!isVisible(description = "Toggle screen orientation")) {
                click(description = "Play video, duration:", settle = WaitKind.NOW)
            }
        }
        waitFor(description = "Toggle screen orientation")
        click(description = "Toggle screen orientation", settle = WaitKind.NOW)
        waitForFullscreen(true)

        waitFor(description = "Toggle screen orientation", absent = true)
        assertFullscreenVideoVisible(screenshot("fullscreen-video.png"))
        click(description = "Play video, duration:", settle = WaitKind.NOW)
        assertFullscreenRemains(true, durationMillis = 2_000)
        waitFor(description = "Toggle screen orientation")
    }

    @Test
    fun fullscreenButtonExitsAndRestoresVideoDetails() = app.run {
        waitFor(text = "LIVE")
        waitFor(text = "Associated Press")
        click(text = "Associated Press", index = 0)
        waitFor(description = "Comments")
        waitFor(text = "Background")
        waitForFullscreen(false)

        click(description = "Play video, duration:")
        if (!isVisible(description = "Toggle screen orientation")) {
            waitFor(className = "android.widget.ProgressBar", stateDescription = "0%")
            if (!isVisible(description = "Toggle screen orientation")) {
                click(description = "Play video, duration:", settle = WaitKind.NOW)
            }
        }
        waitFor(description = "Toggle screen orientation")
        click(description = "Toggle screen orientation", settle = WaitKind.NOW)

        waitForFullscreen(true)
        waitFor(description = "Comments", absent = true)
        if (isVisible(description = "Toggle screen orientation")) {
            click(description = "Play video, duration:", settle = WaitKind.NOW)
        }
        waitFor(description = "Toggle screen orientation", absent = true)

        click(description = "Play video, duration:", settle = WaitKind.NOW)
        waitFor(description = "Toggle screen orientation")
        waitForFullscreen(true)
        click(description = "Toggle screen orientation", settle = WaitKind.NOW)

        waitForFullscreen(false)
        waitFor(description = "Play video, duration:")
        waitFor(text = "Comments are disabled")
        waitFor(description = "Comments")
        waitFor(text = "Background")
    }

}

/** Exercises the desktop-sized configuration used by a normal compositor. */
class NewPipeDesktopTest {
    @get:Rule
    val app = ApkRule("newpipe-0.29.1", LaunchSize(1600, 900))

    @Test
    fun hamburgerOpensNavigationDrawer() = app.run {
        expectVisible(description = "Open Drawer")
        val closed = screenshot("drawer-closed.png")
        assertScreenshotSize(closed, 1600, 900)
        click(description = "Open Drawer")
        waitFor(text = "Downloads")
        waitForPresentedFrames(30)
        val open = screenshot("drawer-open.png")
        waitForPresentedFrames(3)
        assertDrawerOpened(closed, open, screenshot("drawer-settled.png"))
    }
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

private fun assertDrawerOpened(closedScreenshot: Path, openScreenshot: Path, settledScreenshot: Path) {
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
    for (y in 0 until open.height step 4) {
        for (x in 0 until open.width * 2 / 5 step 4) {
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
