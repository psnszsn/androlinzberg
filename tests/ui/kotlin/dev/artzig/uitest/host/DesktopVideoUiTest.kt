package dev.artzig.uitest.host

import android.graphics.Color
import android.os.SystemClock
import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.waitFor
import org.junit.Test
import java.util.regex.Pattern

/**
 * MediaCodec output to a SurfaceView and a TextureView of a task window, in a
 * desktop (labwc) session: decoded frames reach SurfaceFlinger's composition
 * within each view's own bounds, and the rendered callbacks follow the surface.
 */
class DesktopVideoUiTest {
    @Test
    fun decodedFramesFollowTheirSurface() {
        click(By.text(Pattern.compile("show decoded video", Pattern.CASE_INSENSITIVE)))
        expectVisible("Surface video queued")
        expectVisible("Surface rendered callback verified")
        // The SurfaceView's own layer carries the frame and the fixture's red overlay.
        awaitVideo("Decoded SurfaceView", minimumCyan = 25_000, minimumRed = 900)
        for ((action, expected, callback, cyan) in listOf(
            Stage("MOVE VIDEO TO TEXTURE", "Texture video pixels verified", "Texture rendered callback verified", 25_000),
            Stage("RESIZE VIDEO", "Resized video pixels verified", "Resized rendered callback verified", 45_000),
            Stage("REMOVE VIDEO", "Video surfaces released", "Released callbacks cancelled", 0),
        )) {
            click(action)
            expectVisible(expected)
            expectVisible(callback)
            // Each check is restricted to its own view, so a stale SurfaceView
            // image cannot satisfy the TextureView assertion.
            if (cyan > 0) awaitVideo("Decoded TextureView", cyan, 0)
        }
    }

    private data class Stage(val action: String, val expected: String, val callback: String, val cyan: Int)

    private fun awaitVideo(description: String, minimumCyan: Int, minimumRed: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            val bitmap = UiApp.screenshot(waitFor(By.desc(description)).visibleBounds)
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            val cyan = pixels.count { Color.red(it) < 12 && Color.green(it) > 243 && Color.blue(it) > 243 }
            val red = pixels.count { Color.red(it) > 243 && Color.green(it) < 12 && Color.blue(it) < 12 }
            if (cyan >= minimumCyan && red >= minimumRed) return
            check(SystemClock.uptimeMillis() < deadline) { "$description: cyan=$cyan (need $minimumCyan), red=$red (need $minimumRed)" }
            SystemClock.sleep(50)
        }
    }
}
