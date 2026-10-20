package dev.artzig.uitest.host

import android.app.ActivityTaskManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.view.Display
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import dev.artzig.uitest.UiApp.assertLog
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.screenshot
import dev.artzig.uitest.UiApp.waitFor
import dev.artzig.uitest.UiApp.waitUntilGone
import dev.artzig.uitest.UiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * An APK launched through ATMS as desktop task windows, in a desktop (labwc)
 * session: modal dialog hit-testing for real pointer input, the app's custom
 * pointer icon on the compositor, Wayland keyboard text, a second independent
 * task with its own focus, and a foreign shell joining the session's storage.
 */
class DesktopLauncherUiTest {
    @Test
    fun dismissingDialogsRestoresPointerInput() {
        val increment = label("increment counter")
        waitFor(increment)
        repeat(3) { count ->
            WaylandSeat.tap(waitFor(label("show attached dialog")).visibleCenter)
            expectVisible("Fixture dialog")
            WaylandSeat.tap(waitFor(label("close dialog")).visibleCenter)
            waitUntilGone(By.text("Fixture dialog"))
            // Wait for the stock surface/input animation barrier, not a fixed
            // sleep: a still-visible modal dim is allowed to block the activity.
            checkNotNull(android.view.WindowManagerGlobal.getWindowManagerService()).syncInputTransactions(true)
            WaylandSeat.tap(waitFor(increment).visibleCenter)
            expectVisible("Counter: ${count + 1}")
        }
    }

    @Test
    fun taskWindowsTakeDesktopInputIndependently() {
        assertTrue(HostSession.joinFromForeignShell("dev.artzig.StockResourcePathSmoke").contains("stock-resource-join: PASS"))

        val increment = label("increment counter")
        click(increment)
        expectVisible("Counter: 1")
        val button = waitFor(increment).visibleBounds
        val task = windows().single().taskId

        click(label("show attached dialog"))
        expectVisible("Fixture dialog")
        // A modal window's touchable region covers its task, so the stock
        // accessibility window list reports it in place of the activity. The
        // list updates after the dialog's text is already visible.
        assertEquals(listOf(task to "Fixture dialog"), awaitWindowTitles(listOf(task to "Fixture dialog")))
        // The button's centre lies inside the dialog's outer frame (including
        // its shadow): that click neither activates the covered activity nor
        // counts as an outside dismissal.
        WaylandSeat.tap(Point(button.centerX(), button.centerY()))
        SystemClock.sleep(500)
        expectVisible("Fixture dialog")
        // The same button extends well beyond the dialog's left edge. Its
        // outside click dismisses the modal without also invoking the button.
        WaylandSeat.tap(Point(button.left + button.width() / 20, button.centerY()))
        waitUntilGone(By.text("Fixture dialog"))
        // The covered activity is observable again: neither click reached it.
        expectVisible("Counter: 1")
        assertEquals(listOf(task), awaitWindows(1).map { it.taskId })

        click(label("show attached dialog"))
        click(label("close dialog"))
        WaylandSeat.tap(waitFor(increment).visibleCenter)
        expectVisible("Counter: 2")
        // The pointer rests on the button, which sets a custom icon with hotspot
        // (2, 3). The launcher configuration places the task at the output's
        // origin, so screen and compositor coordinates agree.
        awaitCursor(button.centerX() - 2, button.centerY() - 3)

        val editor = By.desc("Fixture editor")
        WaylandSeat.tapThenType(waitFor(editor).visibleCenter, "abc")
        waitFor(editor.text("abc"))
        WaylandSeat.tapThenKey(waitFor(editor).visibleCenter, "Backspace")
        waitFor(By.desc("Fixture editor").text("ab"))
        val long = "A".repeat(1024)
        WaylandSeat.tapThenType(waitFor(By.desc("Fixture editor")).visibleCenter, long)
        waitFor(By.desc("Fixture editor").text("ab$long"))
        waitFor(By.desc("Fixture editor")).text = "ab"
        waitFor(By.desc("Fixture editor").text("ab"))

        click(label("open independent task"))
        val sibling = awaitWindows(2).first { it.taskId != task }.taskId
        // The new task has focus; Wayland keys go to its editor only.
        WaylandSeat.tapThenType(editorIn(sibling).visibleCenter, "Z")
        awaitEditor(sibling, "Z")
        awaitEditor(task, "ab")

        UiTest.device.findObjects(label("finish this activity")).maxBy { it.visibleBounds.left }.click()
        awaitWindows(1)
        WaylandSeat.tapThenType(waitFor(By.desc("Fixture editor")).visibleCenter, "C")
        waitFor(By.desc("Fixture editor").text("abC"))

        // A second launcher joins the session as a host client (not the app):
        // it installs the package again, which, as `adb install -r` does, stops
        // the running package, and the system starts it afresh.
        val joined = HostSession.launchFromForeignShell()
        assertTrue(joined, "Desktop launch accepted: dev.artzig.fixtures.windows" in joined)
        awaitWindows(1)
        waitFor(increment)
        expectVisible("Counter: 0")
    }

    @Test
    fun fractionalScalingRendersAtOutputResolution() {
        val output = checkNotNull(System.getenv("AZK_HEADLESS_OUTPUT"))
        val tasks = ActivityTaskManager.getService()
        fun task() = tasks.getTasks(1, false, false, Display.DEFAULT_DISPLAY).single()
        expectVisible("Counter: 0")
        val initialDeadline = SystemClock.uptimeMillis() + 30_000
        while (task().configuration.windowConfiguration.bounds.let { it.width() != 640 || it.height() != 480 }) {
            check(SystemClock.uptimeMillis() < initialDeadline) { "The desktop fixture did not reach 640x480" }
            SystemClock.sleep(50)
        }
        val initial = task().configuration
        val logicalWidth = initial.windowConfiguration.bounds.width()
        val logicalHeight = initial.windowConfiguration.bounds.height()
        val baseDensity = initial.densityDpi
        val desktop = HostSession.capture()
        var clicks = 0
        try {
            // Scale alone must redraw at more pixels without changing the dp layout.
            // Returning to 100% also catches accidentally multiplying the last override.
            for (scale in listOf(1.25, 1.5, 2.0, 1.0)) {
                // The harness records a fixed logical desktop extent for virtual
                // pointer input. Change physical resolution with scale to retain it.
                val mode = "${(desktop.width * scale).toInt()}x${(desktop.height * scale).toInt()}"
                HostSession.run("wlr-randr", "--output", output, "--custom-mode", mode, "--scale", scale.toString())
                val width = kotlin.math.ceil(logicalWidth * scale).toInt()
                val height = kotlin.math.ceil(logicalHeight * scale).toInt()
                val density = Math.round(baseDensity * scale).toInt()
                val deadline = SystemClock.uptimeMillis() + 30_000
                while (true) {
                    val config = task().configuration
                    val bounds = config.windowConfiguration.bounds
                    if (bounds.width() == width && bounds.height() == height && config.densityDpi == density) break
                    check(SystemClock.uptimeMillis() < deadline) {
                        "scale=$scale expected ${width}x$height density=$density, got $config"
                    }
                    SystemClock.sleep(50)
                }
                val config = task().configuration
                assertEquals(initial.screenWidthDp, config.screenWidthDp)
                assertEquals(initial.screenHeightDp, config.screenHeightDp)
                assertLog("WindowFixture", "\"densityDpi\":$density")
                // An actual Wayland click must still address the scaled Android view.
                WaylandSeat.tap(waitFor(label("increment counter")).visibleCenter)
                expectVisible("Counter: ${++clicks}")

                UiTest.device.waitForIdle()
                val area = waitFor(By.desc("Fixture counter")).visibleBounds
                val androidImage = screenshot(area)
                File(HostSession.artifacts, "text-$scale.png").outputStream().use {
                    androidImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val bounds = task().configuration.windowConfiguration.bounds
                // The labwc fixture places this task at (0,0). Compare rendered
                // Android text pixels with physical compositor pixels, at 1:1.
                // Explicit grim scale keeps the capture in physical output pixels.
                val capture = HostSession.Capture(HostSession.run("grim", "-s", scale.toString(), "-t", "ppm", "-"))
                var different = 0
                for (y in 0 until androidImage.height) for (x in 0 until androidImage.width) {
                    val pixel = androidImage.getPixel(x, y)
                    val host = capture.pixel(area.left - bounds.left + x, area.top - bounds.top + y)
                    val rgb = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (rgb.indices.any { kotlin.math.abs(rgb[it] - host[it]) > 3 }) different++
                }
                assertTrue("scale=$scale compositor resampled text: $different pixels differ",
                    different < androidImage.width * androidImage.height / 100)
                androidImage.recycle()
            }
        } finally {
            HostSession.run("wlr-randr", "--output", output, "--custom-mode", "${desktop.width}x${desktop.height}", "--scale", "1")
        }
    }

    private fun label(text: String): BySelector = By.text(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE))

    private fun windows() = UiTest.instrumentation.uiAutomation.windows.filter { it.taskId > 0 }

    @Test
    fun cursorRemainsVisibleAtScaledWindowEdges() {
        click(label("show cursor edges"))
        expectVisible("Corner clicks: 0")
        HostSession.awaitLog("Framebuffer size has been limited to [720x720] from [16384x16384]")
        val output = checkNotNull(System.getenv("AZK_HEADLESS_OUTPUT"))
        val desktop = HostSession.capture()
        var clicks = 0
        try {
            for (scale in listOf(1.25, 1.5, 2.0, 2.5, 1.0)) {
                HostSession.run("wlr-randr", "--output", output, "--custom-mode",
                    "${(desktop.width * scale).toInt()}x${(desktop.height * scale).toInt()}", "--scale", scale.toString())
                val deadline = SystemClock.uptimeMillis() + 30_000
                while (WaylandSeat.window().geometry.let { it[2] != (640 * scale).toInt() || it[3] != (480 * scale).toInt() }) {
                    check(SystemClock.uptimeMillis() < deadline) { "task did not reach scale $scale" }
                    SystemClock.sleep(50)
                }
                for (corner in listOf("top left", "bottom left", "bottom right", "top right")) {
                    val point = waitFor(By.desc("Cursor $corner")).visibleCenter
                    WaylandSeat.tap(point)
                    expectVisible("Corner clicks: ${++clicks}")
                    // This task is at the desktop origin; grim -s 1 captures
                    // logical Wayland coordinates, including the cursor plane.
                    try {
                        awaitCursor((point.x / scale).toInt() - 2, (point.y / scale).toInt() - 3)
                    } finally {
                        HostSession.run("grim", "-c", "-s", "1", File(HostSession.artifacts, "cursor-$scale-${corner.replace(' ', '-')}.png").path)
                    }
                }
            }
        } finally {
            HostSession.run("wlr-randr", "--output", output, "--custom-mode", "${desktop.width}x${desktop.height}", "--scale", "1")
        }
    }

    private fun awaitWindowTitles(expected: List<Pair<Int, String>>): List<Pair<Int, String?>> {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            val titles = windows().map { it.taskId to it.title?.toString() }
            if (titles == expected || SystemClock.uptimeMillis() >= deadline) return titles
            SystemClock.sleep(50)
        }
    }

    private fun awaitWindows(count: Int): List<android.view.accessibility.AccessibilityWindowInfo> {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            windows().let { if (it.size == count) return it }
            check(SystemClock.uptimeMillis() < deadline) { "expected $count task windows: ${windows()}" }
            SystemClock.sleep(50)
        }
    }

    /** The second task opens to the right of the first. */
    private fun editorIn(task: Int): UiObject2 {
        awaitWindows(2)
        val editors = UiTest.device.findObjects(By.desc("Fixture editor")).sortedBy { it.visibleBounds.left }
        val order = windows().sortedBy { window -> android.graphics.Rect().also(window::getBoundsInScreen).left }.map { it.taskId }
        return editors[order.indexOf(task)]
    }

    private fun awaitEditor(task: Int, text: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (editorIn(task).text != text) {
            check(SystemClock.uptimeMillis() < deadline) { "task $task editor shows '${editorIn(task).text}', not '$text'" }
            SystemClock.sleep(50)
        }
    }

    /** The fixture's icon is magenta then cyan along its ninth row. */
    private fun awaitCursor(x: Int, y: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            val capture = HostSession.Capture(HostSession.run("grim", "-c", "-s", "1", "-t", "ppm", "-"))
            val colors = listOf(capture.pixel(x + 8, y + 8), capture.pixel(x + 24, y + 8))
            if (colors == listOf(listOf(255, 0, 255), listOf(0, 255, 255))) return
            check(SystemClock.uptimeMillis() < deadline) { "the custom pointer icon or its hotspot differ: $colors" }
            SystemClock.sleep(20)
        }
    }
}
