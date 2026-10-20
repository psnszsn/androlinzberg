package dev.artzig.uitest

import android.graphics.Bitmap
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * The verbs the app tests are written in, over androidx UiAutomator and the
 * session's typed journal. Everything observed comes from Android APIs:
 * accessibility nodes, UiAutomation screenshots (SurfaceFlinger's composition)
 * and the processes' log records.
 */
object UiApp {
    private const val TIMEOUT = 30_000L
    private val device get() = UiTest.device
    private var logCursor = 0L
    // Every record read so far, so assertions on different tags can interleave.
    private val logs = ArrayList<Pair<String, String>>()

    fun text(value: String): BySelector = By.text(value)
    fun description(value: String): BySelector = By.desc(value)

    fun waitFor(selector: BySelector, timeoutMillis: Long = TIMEOUT): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMillis)) { "no node matches $selector" }

    fun expectVisible(text: String): UiObject2 = waitFor(By.text(text))

    fun waitUntilGone(selector: BySelector) =
        assertTrue("still present: $selector", device.wait(Until.gone(selector), TIMEOUT))

    /**
     * Waits for `text` in a window of one task. A selector searches every
     * window, which cannot tell two tasks showing the same activity apart.
     */
    fun expectVisibleInTask(taskId: Int, text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT
        while (true) {
            val found = UiTest.instrumentation.uiAutomation.windows.any { window ->
                window.taskId == taskId && window.root?.let { shows(it, text) } == true
            }
            if (found) return
            check(android.os.SystemClock.uptimeMillis() < deadline) { "task $taskId never showed: $text" }
            android.os.SystemClock.sleep(50)
        }
    }

    private fun shows(node: android.view.accessibility.AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString() == text || (0 until node.childCount).any { index -> node.getChild(index)?.let { shows(it, text) } == true }

    fun click(selector: BySelector) = waitFor(selector).click()
    fun click(text: String) = click(By.text(text))

    /** UiDevice.pressBack() reports whether a window-content event followed in
     * time, not whether the key was delivered; the caller's next expectation
     * is the assertion. */
    fun pressBack() {
        device.pressBack()
    }

    /** A log record of any session process with this tag containing `fragment`. */
    fun assertLog(tag: String, fragment: String, timeoutMillis: Long = TIMEOUT) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            val journal = UiTest.diagnostics.read(logCursor, Long.MAX_VALUE)
            logCursor = journal.logCursor
            for (record in journal.logs) logs += record.tag to record.message
            if (logs.any { it.first == tag && fragment in it.second }) return
            assertTrue("no $tag log contains: $fragment", android.os.SystemClock.uptimeMillis() < deadline)
            Thread.sleep(100)
        }
    }

    /** Check after the action's terminal UI state, so its logs have arrived. */
    fun assertNoLog(fragment: String) {
        val journal = UiTest.diagnostics.read(logCursor, Long.MAX_VALUE)
        logCursor = journal.logCursor
        for (record in journal.logs) logs += record.tag to record.message
        assertTrue("unexpected log contains: $fragment", logs.none { fragment in it.second })
    }

    /**
     * SurfaceFlinger's composition of the active window's area, through the
     * stock UiAutomation screenshot path (WindowManager.captureDisplay). The
     * hosted display is a large synthetic desktop that tasks occupy regions of,
     * so the capture is cropped to the window instead of covering the display,
     * which `UiAutomation.takeScreenshot()` would request. `area` narrows the
     * capture further, to one view's screen bounds.
     */
    fun screenshot(area: android.graphics.Rect? = null): Bitmap {
        val crop = area ?: android.graphics.Rect().also(
            assertNotNullValue(UiTest.instrumentation.uiAutomation.rootInActiveWindow, "active window")::getBoundsInScreen)
        val listener = android.window.ScreenCapture.createSyncCaptureListener()
        assertTrue(UiTest.instrumentation.connection.takeScreenshot(crop, listener, android.view.Display.DEFAULT_DISPLAY))
        val buffer = assertNotNullValue(listener.buffer, "screen capture of $crop")
        return assertNotNullValue(buffer.asBitmap(), "capture bitmap").copy(Bitmap.Config.ARGB_8888, false)
    }

    fun assertColorArea(color: Int, minimumPixels: Int, maximumPixels: Int = Int.MAX_VALUE) {
        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT
        while (true) {
            val image = screenshot()
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            val count = pixels.count { it == color }
            if (count in minimumPixels..maximumPixels) return
            // A frame committed just after the UI state changed may still be in flight.
            assertTrue("screenshot has $count pixels of ${color.toUInt().toString(16)}, expected $minimumPixels..$maximumPixels",
                android.os.SystemClock.uptimeMillis() < deadline)
            Thread.sleep(100)
        }
    }

    private fun <T> assertNotNullValue(value: T?, what: String): T {
        assertNotNull(what, value)
        return value!!
    }
}
