package dev.artzig.uitest.host

import android.graphics.Point
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.waitFor
import dev.artzig.uitest.UiApp.waitUntilGone
import dev.artzig.uitest.UiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * An APK launched through ATMS as desktop task windows, in a desktop (labwc)
 * session: modal dialog hit-testing for real pointer input, the app's custom
 * pointer icon on the compositor, Wayland keyboard text, a second independent
 * task with its own focus, and a foreign shell joining the session's storage.
 */
class DesktopLauncherUiTest {
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

    private fun label(text: String): BySelector = By.text(Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE))

    private fun windows() = UiTest.instrumentation.uiAutomation.windows.filter { it.taskId > 0 }

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
            val capture = HostSession.Capture(HostSession.run("grim", "-c", "-t", "ppm", "-"))
            val colors = listOf(capture.pixel(x + 8, y + 8), capture.pixel(x + 24, y + 8))
            if (colors == listOf(listOf(255, 0, 255), listOf(0, 255, 255))) return
            check(SystemClock.uptimeMillis() < deadline) { "the custom pointer icon or its hotspot differ: $colors" }
            SystemClock.sleep(20)
        }
    }
}
