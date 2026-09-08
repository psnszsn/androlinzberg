package dev.artzig.integration

import dev.artzig.testkit.ApkRule
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

    private fun assertColorArea(path: java.nio.file.Path, color: Int, minimumPixels: Int) {
        val image = javax.imageio.ImageIO.read(path.toFile())
        var pixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) == color) pixels++
        }
        check(pixels >= minimumPixels) { "Compositor screenshot contains only $pixels expected texture pixels: $path" }
    }
}
