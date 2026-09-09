package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import dev.artzig.testkit.Selector
import dev.artzig.testkit.LaunchSize
import kotlin.test.Test
import org.junit.Rule

class WindowFixtureTest {
    @get:Rule
    val app = ApkRule("window-contracts", LaunchSize(412, 915))

    @Test
    fun launchesAndUpdatesState() = app.run {
        expectVisible(text = "Counter: 0")
        click(text = "INCREMENT COUNTER")
        expectVisible(text = "Counter: 1")
        assertLog("WindowFixture", "\"event\":\"increment\"")
    }

    @Test
    fun dialogRemainsAttachedToTheActivity() = app.run {
        click(text = "SHOW ATTACHED DIALOG")
        expectVisible(text = "Fixture dialog")
        click(text = "CLOSE DIALOG")
        waitFor(text = "Fixture dialog", absent = true)
        expectVisible(text = "Counter: 0")
    }

    @Test
    fun recreationRestoresSavedCounter() = app.run {
        click(text = "INCREMENT COUNTER")
        click(text = "RECREATE ACTIVITY")
        expectVisible(text = "Counter: 1")
        assertLog("WindowFixture", "\"event\":\"save\"")
        assertLog("WindowFixture", "\"event\":\"restore\"")
    }

    @Test
    fun permissionRequestReturnsConfiguredGrants() = app.run {
        click(text = "CHECK PERMISSION RESULTS")
        expectVisible(text = "PERMISSION RESULTS VERIFIED")
        assertLog("WindowFixture", "\"event\":\"permission-result\"")
        click(text = "INCREMENT COUNTER")
        expectVisible(text = "Counter: 1")
    }

    @Test
    fun textureViewRendersResizesAndReleases() = app.run {
        click(text = "SHOW TEXTUREVIEW")
        expectVisible(text = "Texture cyan frame verified")
        assertColorArea(screenshot("texture-cyan.png"), 0xff00ffff.toInt(), 8_000)
        click(text = "RESIZE TEXTURE")
        expectVisible(text = "Texture resized magenta frame verified")
        assertColorArea(screenshot("texture-magenta.png"), 0xffff00ff.toInt(), 16_000)
        click(text = "REMOVE TEXTURE")
        expectVisible(text = "Texture released")
        assertLog("TextureFixture", "surface released after detach")
    }

    @Test
    fun frameCallbacksFollowSubmissionAndResume() = app.run {
        click(text = "SHOW FRAME CALLBACKS")
        expectVisible(text = "Frame callbacks verified")
        assertLog("FrameFixture", "commit order=first,second,fromDraw; unattached callback not submitted")
        assertColorArea(screenshot("frame-committed-blue.png"), 0xff0000ff.toInt(), 40_000)
        click(text = "COVER FRAME WINDOW")
        expectVisible(text = "RETURN TO FRAME WINDOW")
        click(text = "RETURN TO FRAME WINDOW")
        expectVisible(text = "Resumed frame committed")
        assertLog("FrameFixture", "stop,resume,commit; callbacks completed once on main")
        assertColorArea(screenshot("frame-resumed-cyan.png"), 0xff00ffff.toInt(), 40_000)
    }

    @Test
    fun layoutSchedulingAndNonModalPopupInput() = app.run {
        click(text = "SHOW LAYOUT AND POPUPS")
        expectVisible(text = "Layout scheduling verified")
        assertLog("LayoutFixture", "draw-only invalidation; reentrant second pass; post after layout; no duplicate traversal")
        click(text = "SHOW POPUP WINDOWS")
        expectVisible(text = "Two popup windows laid out")
        assertLog("LayoutFixture", "shared main queue: first=80x90,second=120x130")
        val popupFrame = screenshot("two-popup-windows.png")
        assertColorArea(popupFrame, 0xff00ffff.toInt(), 5_000)
        assertColorArea(popupFrame, 0xffff00ff.toInt(), 12_000)
        click(text = "INCREMENT UNDERLYING")
        expectVisible(text = "UNDERLYING CLICKS: 1")
        val first = Selector(description = "Close first popup", allWindows = true, visible = true)
        val second = Selector(description = "Close second popup", allWindows = true, visible = true)
        waitFor(first)
        waitFor(second)
        click(first)
        waitFor(first, absent = true)
        waitFor(second)
        assertColorArea(screenshot("second-popup-remains.png"), 0xffff00ff.toInt(), 12_000)
        click(second)
        expectVisible(text = "Popup windows dismissed")
        waitFor(second, absent = true)
        expectVisible(text = "UNDERLYING CLICKS: 1")
    }

    private fun assertColorArea(path: java.nio.file.Path, color: Int, minimumPixels: Int) {
        val image = javax.imageio.ImageIO.read(path.toFile())
        var pixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) == color) pixels++
        }
        check(pixels >= minimumPixels) { "Compositor screenshot contains only $pixels expected texture pixels: $path" }
    }
}
