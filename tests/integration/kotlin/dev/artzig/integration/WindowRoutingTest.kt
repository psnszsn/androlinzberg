package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import dev.artzig.testkit.LaunchSize
import kotlin.test.Test
import org.junit.Rule

/** Input travels through Wayland and stock InputDispatcher into real APK views. */
class WindowRoutingTest {
    @get:Rule
    val app = ApkRule("window-contracts", LaunchSize(412, 915))

    @Test
    fun overlappingChildrenInterceptionAndRemoval() = app.run {
        click(text = "SHOW INPUT ROUTING")
        expectVisible(text = "Input routing ready")
        click(text = "ROUTE SIBLINGS")
        drag(.2, .5, .8, .5, description = "Pointer upper child")
        expectVisible(text = "Overlapping sibling routing verified")
        click(text = "ROUTE INTERCEPT DOWN")
        click(description = "Pointer routing region")
        expectVisible(text = "Down interception bypasses child verified")
        click(text = "ROUTE INTERCEPT MOVE")
        drag(.1, .5, .9, .5, description = "Pointer routing region")
        expectVisible(text = "Move interception cancels child verified")
        click(text = "ROUTE DETACH")
        drag(.1, .5, .9, .5, description = "Pointer routing region")
        expectVisible(text = "Removed target canceled before detach verified")
        assertLog("InputRoutingFixture", "Removed target canceled before detach verified")
        assertVerifiedFrame(screenshot("input-routing.png"))
    }

    @Test
    fun seekBarDragPreservesTrackingCallbacks() = app.run {
        click(text = "SHOW INPUT ROUTING")
        click(text = "ROUTE SEEK")
        drag(.15, .5, .85, .5, description = "Seek pointer control")
        expectVisible(text = "Seek drag start, change and stop verified")
        assertLog("InputRoutingFixture", "Seek drag start, change and stop verified")
        assertVerifiedFrame(screenshot("seek-drag.png"))
    }

    private fun assertVerifiedFrame(path: java.nio.file.Path) {
        val image = javax.imageio.ImageIO.read(path.toFile())
        var pixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) == 0xff00ffff.toInt()) pixels++
        }
        check(pixels >= 5_000) { "Missing verification marker in compositor screenshot: $path" }
    }
}
