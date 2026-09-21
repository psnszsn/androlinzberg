package dev.artzig.uitest

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.waitFor
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Stock WebView on the bundled Chromium provider: web content renders, receives
 * input and plays video, and a paused frame stays on screen.
 *
 * AZK_WEBVIEW_VIDEO_SOURCE selects the producer (encoded, avc, i420, canvas);
 * AZK_WEBVIEW_CONFIGURE_OVERLAYS enables Chromium's SurfaceControl overlays
 * through WebView's own developer UI, and AZK_WEBVIEW_TRACE_OUT saves the
 * rendering trace that tests/webview-overlay-check.py inspects.
 */
class WebViewUiTest {
    private val device get() = UiTest.device

    @Test
    fun rendersInteractsAndPlaysVideo() {
        // Newer Chromium keeps WebView overlays behind its supported developer
        // setting. Change it only through its UI, in this session's own data.
        if (System.getenv("AZK_WEBVIEW_CONFIGURE_OVERLAYS") == "1") {
            click("CONFIGURE WEBVIEW OVERLAYS")
            click("OK")
            waitFor(By.res("com.android.webview:id/flag_search_bar"), 60_000).text = "WebViewSurfaceControl not"
            click(By.res("com.android.webview:id/flag_toggle"))
            click("Enabled")
            device.pressBack()
            expectVisible("START WEBVIEW")
            click("START WEBVIEW")
        }
        waitFor(By.text("WebView input verified"), 60_000)
        waitFor(By.text("Count: 0"), 60_000)
        click("Count: 0")
        expectVisible("Count: 1")
        val trace = System.getenv("AZK_WEBVIEW_TRACE_OUT")
        if (trace != null) click("START RENDERING TRACE")

        click(when (val source = System.getenv("AZK_WEBVIEW_VIDEO_SOURCE") ?: "encoded") {
            "encoded" -> "Start video"
            "avc" -> "Start H264 video"
            "i420" -> "Start I420 video"
            "canvas" -> "Start canvas video"
            else -> error("unknown AZK_WEBVIEW_VIDEO_SOURCE $source")
        })
        waitFor(By.text("Video frames ready"), 60_000)
        // DOM callbacks precede presentation. Require a visible moving frame
        // before pausing, then check that the same frame is retained after pause.
        val playing = awaitFrame(null)
        click("Pause video")
        expectVisible("Video paused")
        awaitFrame(playing)

        if (trace != null) {
            click("SAVE RENDERING TRACE")
            waitFor(By.text("Rendering trace saved"), 30_000)
            assertTrue("empty rendering trace", java.io.File(trace).length() > 0)
        }
    }

    private fun waitFor(selector: androidx.test.uiautomator.BySelector, timeout: Long) =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "no node matches $selector" }

    private fun awaitFrame(playing: Bitmap?): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            val frame = UiApp.screenshot()
            val problem = frameProblem(frame, playing) ?: return frame
            assertTrue(problem, SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(100)
        }
    }

    /** Rendered text and video, and preservation of the displayed frame across pause. */
    private fun frameProblem(image: Bitmap, playing: Bitmap?): String? {
        if (playing != null && (image.width != playing.width || image.height != playing.height)) return "capture size changed"
        if (image.width < 320 || image.height < 240) return "unexpected capture size ${image.width}x${image.height}"
        val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        val before = playing?.let { p -> IntArray(p.width * p.height).also { p.getPixels(it, 0, p.width, 0, 0, p.width, p.height) } }
        var ink = 0
        var video = 0
        var compared = 0
        var changed = 0
        for (index in pixels.indices) {
            val pixel = pixels[index]
            // CSS body colour #16324f, tolerating colour conversion and antialiasing;
            // black or white empty buffers cannot satisfy this.
            if (index / image.width < image.height / 2) {
                val r = pixel ushr 16 and 255
                val g = pixel ushr 8 and 255
                val b = pixel and 255
                if (r in 14..30 && g in 42..58 && b in 71..87) ink++
            }
            if (isVideoGreen(pixel)) video++
            if (before != null && isVideoGreen(before[index])) {
                compared++
                if (listOf(0, 8, 16).any { shift -> abs((pixel ushr shift and 255) - (before[index] ushr shift and 255)) > 2 }) changed++
            }
        }
        if (before != null && compared < 10_000) return "no visible video in the playing-frame baseline"
        if (changed != 0) return "paused video changed $changed of $compared displayed pixels"
        if (video < 10_000) return "Chromium video frame is not visible: $video matching pixels"
        if (ink < 200) return "WebView heading is not visible: $ink matching pixels"
        return null
    }

    // The fixture is green; red producer frames, black video fallbacks, the white
    // page and blue text cannot satisfy this.
    private fun isVideoGreen(pixel: Int): Boolean {
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        return r < 100 && g > 140 && b in 70..150 && g > r + 60 && g > b + 30
    }
}
